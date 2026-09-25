package com.saytikus.gwatchtogether.platform.livekit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.BufferedReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveKitSidecarWindowsIntegrationTest {
    @Test
    fun windowsEndpointOwnerProbeReturnsBoundedLoopbackSnapshot() =
        runBlocking {
            requirePinnedBinary()
            val ports = WindowsLoopbackPortAllocator().allocate()

            val snapshot = WindowsLiveKitReadinessProbe().inspect(2_000_000_000, ports)

            assertFalse(snapshot.apiHealthy)
            assertTrue(snapshot.apiListenerOwners.isEmpty())
            assertTrue(snapshot.iceUdpOwners.isEmpty())
            assertTrue(snapshot.ownedEndpointsAreLoopbackOnly)
        }

    @Test
    fun explicitHeadlessDesktopCallerStartsAndStopsPinnedBinary() =
        runBlocking {
            val executable = requirePinnedBinary()
            val commandWriter = PipedWriter()
            val commandReader = BufferedReader(PipedReader(commandWriter))
            val responseWriter = PipedWriter()
            val responseReader = BufferedReader(PipedReader(responseWriter))
            val output = PrintWriter(responseWriter, true)
            var caller: kotlinx.coroutines.Deferred<Int>? = null
            try {
                caller =
                    async(Dispatchers.IO) {
                        runDesktopLiveKitSidecarHeadlessMode(commandReader, output)
                    }
                assertTrue(readHeadlessResponse(responseReader).startsWith("P0-06 internal headless mode"))
                commandWriter.write("start ${executable}\n")
                commandWriter.flush()

                val readyLine = readHeadlessResponse(responseReader)
                val match =
                    Regex("READY pid=(\\d+) api=127\\.0\\.0\\.1:(\\d+) iceUdp=127\\.0\\.0\\.1:(\\d+)")
                        .matchEntire(readyLine)
                assertTrue(match != null, "Headless caller did not report verified Ready: $readyLine")
                val ready = requireNotNull(match)
                val processId = ready.groupValues[1].toLong()
                val ports =
                    SidecarPorts(
                        apiTcp = ready.groupValues[2].toInt(),
                        iceUdp = ready.groupValues[3].toInt(),
                    )
                val probe = WindowsLiveKitReadinessProbe()
                val snapshot = probe.inspect(processId, ports)
                assertTrue(snapshot.apiHealthy)
                assertTrue(processId in snapshot.apiListenerOwners)
                assertTrue(processId in snapshot.iceUdpOwners)
                assertTrue(snapshot.ownedEndpointsAreLoopbackOnly)

                commandWriter.write("stop\n")
                commandWriter.flush()
                assertEquals("STOPPED", readHeadlessResponse(responseReader))
                assertFalse(probe.hasOwnedEndpoints(processId, ports))
                assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false))

                commandWriter.write("quit\n")
                commandWriter.flush()
                assertEquals("CLOSED", readHeadlessResponse(responseReader))
                assertEquals(0, caller.await())
            } finally {
                commandWriter.close()
                caller?.let { withTimeout(20_000) { it.await() } }
                commandReader.close()
                responseReader.close()
                responseWriter.close()
            }
        }

    @Test
    fun explicitHeadlessDesktopCallerRejectsPinnedExitZeroStartupFailure() =
        runBlocking {
            val executable = requirePinnedBinary()
            val launcher = RecordingProcessLauncher()
            val portAllocator = RecordingPortAllocator(WindowsLoopbackPortAllocator())
            val configFactory = RecordingConfigFactory(MalformedConfigFactory())
            val host =
                DesktopLiveKitSidecarHost(
                    newSupervisor(
                        processLauncher = launcher,
                        portAllocator = portAllocator,
                        configFactory = configFactory,
                    ),
                )
            val outputBuffer = StringWriter()
            val exitCode =
                runDesktopLiveKitSidecarHeadlessMode(
                    input = BufferedReader(StringReader("start $executable\nquit\n")),
                    output = PrintWriter(outputBuffer, true),
                    host = host,
                )

            assertEquals(1, exitCode)
            assertTrue(outputBuffer.toString().contains("FAILED reason=ProcessExitedBeforeReady"))
            assertTrue(outputBuffer.toString().contains("CLOSED"))
            val failedProcess = launcher.processes.single()
            assertEquals(0, failedProcess.awaitExit())
            assertFalse(failedProcess.isAlive())
            assertTrue(configFactory.lastLease?.deleted == true)
            assertFalse(
                WindowsLiveKitReadinessProbe().hasOwnedEndpoints(
                    failedProcess.processId,
                    requireNotNull(portAllocator.ports),
                ),
            )
        }

    @Test
    fun pinnedBinarySupportsRepeatedBoundedLoopbackStartAndStop() =
        runBlocking {
            val executable = requirePinnedBinary()
            val recordingProbe = RecordingReadinessProbe(WindowsLiveKitReadinessProbe())
            val configFactory = RecordingConfigFactory(PrivateLiveKitConfigFactory())
            val supervisor = newSupervisor(readinessProbe = recordingProbe, configFactory = configFactory)
            try {
                repeat(3) {
                    val startResult = supervisor.start(executable)
                    val ready =
                        assertIs<LiveKitSidecarState.Ready>(
                            startResult,
                            "Sidecar startup result: $startResult; " +
                                "last endpoint snapshot: ${recordingProbe.snapshots.lastOrNull()}",
                        )
                    assertTrue(ready.processId > 0)
                    assertTrue(ready.ports.apiTcp in 1..65535)
                    assertTrue(ready.ports.iceUdp in 1..65535)
                    val snapshot = WindowsLiveKitReadinessProbe().inspect(ready.processId, ready.ports)
                    assertTrue(snapshot.apiHealthy)
                    assertTrue(ready.processId in snapshot.apiListenerOwners)
                    assertTrue(ready.processId in snapshot.iceUdpOwners)
                    assertTrue(snapshot.ownedEndpointsAreLoopbackOnly)
                    assertTrue(configFactory.lastLease?.deleted == true)
                    val stopped = supervisor.stop()
                    assertEquals(LiveKitSidecarState.Stopped, stopped)
                    assertEquals(LiveKitSidecarState.Stopped, supervisor.stop())
                    val stillAlive =
                        ProcessHandle
                            .of(ready.processId)
                            .map(ProcessHandle::isAlive)
                            .orElse(false)
                    assertFalse(stillAlive)
                }
            } finally {
                supervisor.close()
            }
        }

    @Test
    fun occupiedApiPortAndMalformedConfigAreTerminalEvenWhenLiveKitExitsZero() =
        runBlocking {
            val executable = requirePinnedBinary()
            val allocator = WindowsLoopbackPortAllocator()
            val ownerPorts = allocator.allocate()
            var otherPorts = allocator.allocate()
            while (otherPorts.iceUdp == ownerPorts.iceUdp) otherPorts = allocator.allocate()
            val contenderPorts = SidecarPorts(ownerPorts.apiTcp, otherPorts.iceUdp)
            val owner = newSupervisor(portAllocator = FixedPortAllocator(ownerPorts))
            val contenderLauncher = RecordingProcessLauncher()
            val contender =
                newSupervisor(
                    processLauncher = contenderLauncher,
                    portAllocator = FixedPortAllocator(contenderPorts),
                )
            try {
                val ownerResult = owner.start(executable)
                val ownerReady =
                    assertIs<LiveKitSidecarState.Ready>(
                        ownerResult,
                        "Port-conflict owner startup result: $ownerResult",
                    )
                assertEquals(ownerPorts, ownerReady.ports)

                val conflict = assertIs<LiveKitSidecarState.Failed>(contender.start(executable))
                assertEquals(LiveKitSidecarFailure.ProcessExitedBeforeReady, conflict.reason)
                val failedChild = contenderLauncher.processes.single()
                assertEquals(0, failedChild.awaitExit())
                assertFalse(failedChild.isAlive())
                val contenderEndpointsRemain =
                    WindowsLiveKitReadinessProbe()
                        .hasOwnedEndpoints(failedChild.processId, contenderPorts)
                assertFalse(contenderEndpointsRemain)
                assertEquals(ownerReady, owner.state.value)
                val ownerHealth =
                    WindowsLiveKitReadinessProbe()
                        .inspect(ownerReady.processId, ownerPorts)
                        .apiHealthy
                assertTrue(ownerHealth)
            } finally {
                contender.close()
                owner.close()
            }

            val malformedLease = RecordingConfigFactory(MalformedConfigFactory())
            val malformedLauncher = RecordingProcessLauncher()
            val malformed =
                newSupervisor(
                    processLauncher = malformedLauncher,
                    configFactory = malformedLease,
                )
            try {
                val result = assertIs<LiveKitSidecarState.Failed>(malformed.start(executable))
                assertEquals(LiveKitSidecarFailure.ProcessExitedBeforeReady, result.reason)
                assertEquals(0, malformedLauncher.processes.single().awaitExit())
                assertFalse(malformedLauncher.processes.single().isAlive())
                assertTrue(malformedLease.lastLease?.deleted == true)
            } finally {
                malformed.close()
            }
        }

    @Test
    fun healthTimeoutKillsTheOwnedRealServerAndReleasesBothPorts() =
        runBlocking {
            val executable = requirePinnedBinary()
            val processLauncher = RecordingProcessLauncher()
            val portAllocator = RecordingPortAllocator(WindowsLoopbackPortAllocator())
            val configFactory = RecordingConfigFactory(PrivateLiveKitConfigFactory())
            val realProbe = WindowsLiveKitReadinessProbe()
            val supervisor =
                newSupervisor(
                    processLauncher = processLauncher,
                    portAllocator = portAllocator,
                    configFactory = configFactory,
                    readinessProbe = HealthTimeoutProbe(realProbe),
                    timeouts = integrationTimeouts(readinessMillis = 1_000),
                )
            try {
                val result = assertIs<LiveKitSidecarState.Failed>(supervisor.start(executable))
                assertEquals(LiveKitSidecarFailure.ReadinessTimedOut, result.reason)
                val child = processLauncher.processes.single()
                val ports = portAllocator.ports!!
                assertFalse(child.isAlive())
                assertFalse(realProbe.hasOwnedEndpoints(child.processId, ports))
                assertTrue(configFactory.lastLease?.deleted == true)
            } finally {
                supervisor.close()
            }
        }

    @Test
    fun externalForcedTerminationIsObservedSeparatelyFromSupervisorStop() =
        runBlocking {
            val executable = requirePinnedBinary()
            val launcher = RecordingProcessLauncher()
            val probe = WindowsLiveKitReadinessProbe()
            val supervisor = newSupervisor(processLauncher = launcher, readinessProbe = probe)
            try {
                val startResult = supervisor.start(executable)
                val ready =
                    assertIs<LiveKitSidecarState.Ready>(
                        startResult,
                        "Unexpected-exit test startup result: $startResult",
                    )
                val child = launcher.processes.single()

                child.forceStop()
                val failure =
                    withTimeout(10_000) {
                        supervisor.state.first { it is LiveKitSidecarState.Failed }
                    }

                assertEquals(
                    LiveKitSidecarState.Failed(LiveKitSidecarFailure.UnexpectedProcessExit),
                    failure,
                )
                assertFalse(child.isAlive())
                assertFalse(probe.hasOwnedEndpoints(ready.processId, ready.ports))
                val stopped = supervisor.stop()
                assertEquals(LiveKitSidecarState.Stopped, stopped)
            } finally {
                supervisor.close()
            }
        }

    private suspend fun readHeadlessResponse(reader: BufferedReader): String =
        withTimeout(20_000) {
            withContext(Dispatchers.IO) { reader.readLine() }
        } ?: error("Headless caller closed its output before reporting the expected state.")

    private fun requirePinnedBinary(): Path {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val configuredPath = System.getenv("GWT_P006_LIVEKIT_EXE")
        assumeTrue(
            "Set GWT_P006_LIVEKIT_EXE to the externally built pinned binary.",
            !configuredPath.isNullOrBlank(),
        )
        val executable = Path.of(configuredPath!!)
        assertTrue(Files.isRegularFile(executable))
        assertEquals(PINNED_LIVEKIT_SHA256, sha256(executable))
        assertFalse(isAdministrator())
        return executable
    }

    private fun newSupervisor(
        processLauncher: ISidecarProcessLauncher = WindowsSidecarProcessLauncher(),
        portAllocator: ISidecarPortAllocator = WindowsLoopbackPortAllocator(),
        configFactory: ISidecarConfigFactory = PrivateLiveKitConfigFactory(),
        readinessProbe: ISidecarReadinessProbe = WindowsLiveKitReadinessProbe(),
        timeouts: LiveKitSidecarTimeouts = integrationTimeouts(),
    ) = LiveKitSidecarSupervisor(
        processLauncher = processLauncher,
        portAllocator = portAllocator,
        configFactory = configFactory,
        readinessProbe = readinessProbe,
        timeouts = timeouts,
    )

    private fun integrationTimeouts(readinessMillis: Long = 12_000) =
        LiveKitSidecarTimeouts(
            readinessMillis = readinessMillis,
            probeIntervalMillis = 250,
            gracefulStopMillis = 1_000,
            forcedStopMillis = 2_000,
            endpointReleaseMillis = 3_000,
        )

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun isAdministrator(): Boolean {
        val systemRoot = System.getenv("SystemRoot") ?: return true
        val powershell =
            Path.of(
                systemRoot,
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe",
            )
        val script =
            "\$principal = New-Object Security.Principal.WindowsPrincipal(" +
                "[Security.Principal.WindowsIdentity]::GetCurrent()); " +
                "[Console]::Out.WriteLine(\$principal.IsInRole(" +
                "[Security.Principal.WindowsBuiltInRole]::Administrator))"
        val process =
            ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return true
        }
        val adminOutput =
            process.inputStream
                .bufferedReader(StandardCharsets.US_ASCII)
                .readText()
                .trim()
        val isAdmin = adminOutput != "False"
        return process.exitValue() != 0 || isAdmin
    }

    private class FixedPortAllocator(
        private val ports: SidecarPorts,
    ) : ISidecarPortAllocator {
        override suspend fun allocate(): SidecarPorts = ports
    }

    private class RecordingProcessLauncher(
        private val delegate: ISidecarProcessLauncher = WindowsSidecarProcessLauncher(),
    ) : ISidecarProcessLauncher {
        val processes = mutableListOf<ISidecarProcess>()

        override suspend fun launch(
            executable: Path,
            configFile: Path,
        ): ISidecarProcess {
            val process = delegate.launch(executable, configFile)
            processes += process
            return process
        }
    }

    private class RecordingPortAllocator(
        private val delegate: ISidecarPortAllocator,
    ) : ISidecarPortAllocator {
        var ports: SidecarPorts? = null
            private set

        override suspend fun allocate(): SidecarPorts = delegate.allocate().also { ports = it }
    }

    private class RecordingConfigFactory(
        private val delegate: ISidecarConfigFactory,
    ) : ISidecarConfigFactory {
        var lastLease: RecordingLease? = null

        override suspend fun create(ports: SidecarPorts): ISidecarConfigLease {
            val lease = RecordingLease(delegate.create(ports))
            lastLease = lease
            return lease
        }
    }

    private class RecordingLease(
        private val delegate: ISidecarConfigLease,
    ) : ISidecarConfigLease {
        override val path: Path = delegate.path
        var deleted = false
            private set

        override suspend fun delete(): Boolean = delegate.delete().also { deleted = it }
    }

    private class MalformedConfigFactory : ISidecarConfigFactory {
        override suspend fun create(ports: SidecarPorts): ISidecarConfigLease {
            val path = Files.createTempFile("gwt-livekit-malformed-", ".yaml")
            Files.writeString(path, "port: [\n", StandardCharsets.UTF_8)
            return object : ISidecarConfigLease {
                override val path: Path = path

                override suspend fun delete(): Boolean = Files.deleteIfExists(path).let { true }
            }
        }
    }

    private class RecordingReadinessProbe(
        private val delegate: ISidecarReadinessProbe,
    ) : ISidecarReadinessProbe {
        val snapshots = mutableListOf<SidecarEndpointSnapshot>()

        override suspend fun inspect(
            processId: Long,
            ports: SidecarPorts,
        ): SidecarEndpointSnapshot = delegate.inspect(processId, ports).also(snapshots::add)

        override suspend fun hasOwnedEndpoints(
            processId: Long,
            ports: SidecarPorts,
        ): Boolean = delegate.hasOwnedEndpoints(processId, ports)
    }

    private class HealthTimeoutProbe(
        private val delegate: ISidecarReadinessProbe,
    ) : ISidecarReadinessProbe {
        override suspend fun inspect(
            processId: Long,
            ports: SidecarPorts,
        ): SidecarEndpointSnapshot = delegate.inspect(processId, ports).copy(apiHealthy = false)

        override suspend fun hasOwnedEndpoints(
            processId: Long,
            ports: SidecarPorts,
        ): Boolean = delegate.hasOwnedEndpoints(processId, ports)
    }

    private companion object {
        const val PINNED_LIVEKIT_SHA256 =
            "a499e030f165cc15a825eb7d4afe8fc3560eaedeeb8cef3fb26e4778123cada4"
    }
}
