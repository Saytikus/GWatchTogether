package com.saytikus.gwatchtogether.platform.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal class WindowsLoopbackPortAllocator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ISidecarPortAllocator {
    override suspend fun allocate(): SidecarPorts =
        withContext(ioDispatcher) {
            val address = InetAddress.getByName("127.0.0.1")
            val apiPort =
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(address, 0))
                    socket.localPort
                }
            var udpPort: Int
            do {
                udpPort =
                    DatagramSocket(null).use { socket ->
                        socket.reuseAddress = false
                        socket.bind(InetSocketAddress(address, 0))
                        socket.localPort
                    }
            } while (udpPort == apiPort)
            SidecarPorts(apiTcp = apiPort, iceUdp = udpPort)
        }
}

internal class PrivateLiveKitConfigFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) : ISidecarConfigFactory {
    override suspend fun create(ports: SidecarPorts): ISidecarConfigLease {
        var createdDirectory: Path? = null
        try {
            return withContext(ioDispatcher + NonCancellable) {
                val directory = Files.createTempDirectory("gwt-livekit-")
                createdDirectory = directory
                try {
                    setOwnerOnlyAcl(directory, inherit = true)
                    val configPath = directory.resolve("livekit.yaml")
                    val yaml = renderConfig(ports)
                    Files.writeString(
                        configPath,
                        yaml,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    setOwnerOnlyAcl(configPath, inherit = false)
                    FileConfigLease(directory, configPath, ioDispatcher)
                } catch (failure: Exception) {
                    deleteTree(directory)
                    createdDirectory = null
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            createdDirectory?.let { directory ->
                withContext(ioDispatcher + NonCancellable) { deleteTree(directory) }
            }
            throw cancelled
        }
    }

    private fun renderConfig(ports: SidecarPorts): String {
        val apiKey = randomHex(16)
        val apiSecret = randomHex(32)
        return """
            port: ${ports.apiTcp}
            rtc:
              tcp_port: 0
              udp_port: ${ports.iceUdp}
              use_external_ip: false
              node_ip: 127.0.0.1
              enable_loopback_candidate: true
              ips:
                includes:
                  - 127.0.0.1/32
            keys:
              $apiKey: $apiSecret
            logging:
              level: warn
              json: true

            """.trimIndent()
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun setOwnerOnlyAcl(
        path: Path,
        inherit: Boolean,
    ) {
        val view =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw IOException("Private Windows ACL support is unavailable.")
        val builder =
            AclEntry
                .newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(*EnumSet.allOf(AclEntryPermission::class.java).toTypedArray())
        if (inherit) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        }
        view.setAcl(listOf(builder.build()))
    }

    private fun deleteTree(directory: Path) {
        try {
            Files.deleteIfExists(directory.resolve("livekit.yaml"))
            Files.deleteIfExists(directory)
        } catch (_: IOException) {
            // The failed lease is never returned to the caller or included in public state.
        }
    }
}

private class FileConfigLease(
    private val directory: Path,
    override val path: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : ISidecarConfigLease {
    override suspend fun delete(): Boolean =
        withContext(ioDispatcher) {
            try {
                Files.deleteIfExists(path)
                Files.deleteIfExists(directory)
                true
            } catch (_: IOException) {
                false
            }
        }
}

internal class WindowsSidecarProcessLauncher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ISidecarProcessLauncher {
    override suspend fun launch(
        executable: Path,
        configFile: Path,
    ): ISidecarProcess {
        var createdProcess: Process? = null
        try {
            return withContext(ioDispatcher + NonCancellable) {
                if (!Files.isRegularFile(executable) || !Files.isRegularFile(configFile)) {
                    throw IOException("The sidecar executable or private config is unavailable.")
                }
                val builder =
                    ProcessBuilder(
                        executable.toString(),
                        "--config",
                        configFile.toString(),
                        "--bind",
                        "127.0.0.1",
                    )
                builder.environment().apply {
                    val systemRoot =
                        System.getenv("SystemRoot") ?: throw IOException("Windows SystemRoot is unavailable.")
                    clear()
                    put("SystemRoot", systemRoot)
                    put("WINDIR", systemRoot)
                    System.getenv("TEMP")?.let { put("TEMP", it) }
                    System.getenv("TMP")?.let { put("TMP", it) }
                }
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
                ProcessSidecarProcess(builder.start().also { createdProcess = it }, ioDispatcher)
            }
        } catch (cancelled: CancellationException) {
            withContext(ioDispatcher + NonCancellable) {
                createdProcess?.let { process ->
                    if (process.isAlive) process.destroyForcibly()
                    process.waitFor(2_000, TimeUnit.MILLISECONDS)
                }
            }
            throw cancelled
        }
    }
}

private class ProcessSidecarProcess(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher,
) : ISidecarProcess {
    override val processId: Long = process.pid()

    override fun isAlive(): Boolean = process.isAlive

    override suspend fun awaitExit(): Int = withContext(ioDispatcher) { process.waitFor() }

    override suspend fun awaitExit(timeoutMillis: Long): Boolean =
        withContext(ioDispatcher) {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        }

    override fun requestStop() {
        process.destroy()
    }

    override fun forceStop() {
        process.destroyForcibly()
    }
}

internal class WindowsLiveKitReadinessProbe(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ISidecarReadinessProbe {
    override suspend fun inspect(
        processId: Long,
        ports: SidecarPorts,
    ): SidecarEndpointSnapshot =
        withContext(ioDispatcher) {
            val endpoints = queryEndpoints(processId, ports)
            SidecarEndpointSnapshot(
                apiHealthy = isHealthy(ports.apiTcp),
                apiListenerOwners = endpoints.apiListenerOwners,
                iceUdpOwners = endpoints.iceUdpOwners,
                ownedEndpointsAreLoopbackOnly = endpoints.loopbackOnly,
            )
        }

    override suspend fun hasOwnedEndpoints(
        processId: Long,
        ports: SidecarPorts,
    ): Boolean =
        withContext(ioDispatcher) {
            val endpoints = queryEndpoints(processId, ports)
            processId in endpoints.apiListenerOwners || processId in endpoints.iceUdpOwners
        }

    private fun isHealthy(port: Int): Boolean {
        val connection =
            URI("http://127.0.0.1:$port/")
                .toURL()
                .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = HEALTH_TIMEOUT_MILLIS
            connection.readTimeout = HEALTH_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.responseCode == 200
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun queryEndpoints(
        processId: Long,
        ports: SidecarPorts,
    ): EndpointOwnerSnapshot {
        val systemRoot = System.getenv("SystemRoot") ?: throw IOException("Windows SystemRoot is unavailable.")
        val powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        if (!Files.isRegularFile(powershell)) throw IOException("Windows endpoint inspection is unavailable.")

        val builder =
            ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                Base64.getEncoder().encodeToString(ENDPOINT_QUERY_SCRIPT.toByteArray(StandardCharsets.UTF_16LE)),
            )
        builder.environment().apply {
            clear()
            put("SystemRoot", systemRoot)
            put("WINDIR", systemRoot)
            System.getenv("PSModulePath")?.let { put("PSModulePath", it) }
            System.getenv("PATH")?.let { put("PATH", it) }
            put("GWT_LK_API_PORT", ports.apiTcp.toString())
            put("GWT_LK_ICE_PORT", ports.iceUdp.toString())
            put("GWT_LK_PROCESS_ID", processId.toString())
        }
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val probe = builder.start()
        try {
            if (!probe.waitFor(ENDPOINT_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw IOException("Windows endpoint inspection timed out.")
            }
            val bytes = probe.inputStream.use { it.readNBytes(MAX_ENDPOINT_OUTPUT_BYTES + 1) }
            if (probe.exitValue() != 0) throw IOException("Windows endpoint inspection failed.")
            if (bytes.size >
                MAX_ENDPOINT_OUTPUT_BYTES
            ) {
                throw IOException("Windows endpoint response exceeded its bound.")
            }
            val columns = bytes.toString(StandardCharsets.US_ASCII).trim().split('|')
            if (columns.size != 3) throw IOException("Windows endpoint response was invalid.")
            return EndpointOwnerSnapshot(
                apiListenerOwners = parseProcessIds(columns[0]),
                iceUdpOwners = parseProcessIds(columns[1]),
                loopbackOnly =
                    when (columns[2]) {
                        "True" -> true
                        "False" -> false
                        else -> throw IOException("Windows endpoint response was invalid.")
                    },
            )
        } finally {
            if (probe.isAlive) {
                probe.destroyForcibly()
                probe.waitFor(ENDPOINT_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun parseProcessIds(value: String): Set<Long> {
        if (value.isBlank()) return emptySet()
        return value
            .split(',')
            .map { field ->
                field.toLongOrNull() ?: throw IOException("Windows endpoint response was invalid.")
            }.toSet()
    }

    private data class EndpointOwnerSnapshot(
        val apiListenerOwners: Set<Long>,
        val iceUdpOwners: Set<Long>,
        val loopbackOnly: Boolean,
    )

    private companion object {
        const val HEALTH_TIMEOUT_MILLIS = 500
        const val ENDPOINT_QUERY_TIMEOUT_MILLIS = 2_000L
        const val MAX_ENDPOINT_OUTPUT_BYTES = 256
        val ENDPOINT_QUERY_SCRIPT =
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}apiPort = [int]${'$'}env:GWT_LK_API_PORT
            ${'$'}icePort = [int]${'$'}env:GWT_LK_ICE_PORT
            ${'$'}targetProcessId = [long]${'$'}env:GWT_LK_PROCESS_ID
            ${'$'}apiOwners = @(Get-NetTCPConnection -State Listen -LocalPort ${'$'}apiPort -ErrorAction SilentlyContinue |
                ForEach-Object OwningProcess | Sort-Object -Unique)
            ${'$'}iceOwners = @(Get-NetUDPEndpoint -LocalPort ${'$'}icePort -ErrorAction SilentlyContinue |
                ForEach-Object OwningProcess | Sort-Object -Unique)
            ${'$'}ownedTcp = @(Get-NetTCPConnection -OwningProcess ${'$'}targetProcessId -ErrorAction SilentlyContinue)
            ${'$'}ownedUdp = @(Get-NetUDPEndpoint -OwningProcess ${'$'}targetProcessId -ErrorAction SilentlyContinue)
            ${'$'}nonLoopback = @(${'$'}ownedTcp + ${'$'}ownedUdp | Where-Object {
                ${'$'}_.LocalAddress -notin @('127.0.0.1', '::1')
            })
            ${'$'}loopbackOnly = ${'$'}nonLoopback.Count -eq 0
            [Console]::Out.WriteLine("${'$'}(${'$'}apiOwners -join ',')|${'$'}(${'$'}iceOwners -join ',')|${'$'}loopbackOnly")
            """.trimIndent()
    }
}
