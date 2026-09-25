package com.saytikus.gwatchtogether.platform.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

internal data class SidecarPorts(
    val apiTcp: Int,
    val iceUdp: Int,
)

internal data class SidecarEndpointSnapshot(
    val apiHealthy: Boolean,
    val apiListenerOwners: Set<Long>,
    val iceUdpOwners: Set<Long>,
    val ownedEndpointsAreLoopbackOnly: Boolean,
)

internal sealed interface LiveKitSidecarState {
    data object Stopped : LiveKitSidecarState

    data class Starting(
        val ports: SidecarPorts? = null,
    ) : LiveKitSidecarState

    data class Ready(
        val processId: Long,
        val ports: SidecarPorts,
    ) : LiveKitSidecarState

    data class Stopping(
        val processId: Long?,
    ) : LiveKitSidecarState

    data class Failed(
        val reason: LiveKitSidecarFailure,
        val cleanupFailure: LiveKitSidecarFailure? = null,
    ) : LiveKitSidecarState
}

internal enum class LiveKitSidecarFailure {
    UnsupportedOperatingSystem,
    PortAllocationFailed,
    PrivateConfigUnavailable,
    ProcessLaunchFailed,
    ProcessExitedBeforeReady,
    PortAlreadyOwned,
    NonLoopbackBinding,
    ReadinessProbeFailed,
    ReadinessTimedOut,
    ProcessTerminationTimedOut,
    EndpointReleaseTimedOut,
    PrivateConfigCleanupFailed,
    UnexpectedProcessExit,
    StartupCancelled,
    Closed,
}

internal data class LiveKitSidecarTimeouts(
    val readinessMillis: Long = 15_000,
    val probeIntervalMillis: Long = 250,
    val gracefulStopMillis: Long = 1_000,
    val forcedStopMillis: Long = 2_000,
    val endpointReleaseMillis: Long = 2_000,
) {
    init {
        require(readinessMillis > 0)
        require(probeIntervalMillis > 0)
        require(gracefulStopMillis > 0)
        require(forcedStopMillis > 0)
        require(endpointReleaseMillis > 0)
    }
}

internal interface ISidecarProcess {
    val processId: Long

    fun isAlive(): Boolean

    suspend fun awaitExit(): Int

    suspend fun awaitExit(timeoutMillis: Long): Boolean

    fun requestStop()

    fun forceStop()
}

internal interface ISidecarProcessLauncher {
    suspend fun launch(
        executable: Path,
        configFile: Path,
    ): ISidecarProcess
}

internal interface ISidecarPortAllocator {
    suspend fun allocate(): SidecarPorts
}

internal interface ISidecarConfigLease {
    val path: Path

    suspend fun delete(): Boolean
}

internal interface ISidecarConfigFactory {
    suspend fun create(ports: SidecarPorts): ISidecarConfigLease
}

internal interface ISidecarReadinessProbe {
    suspend fun inspect(
        processId: Long,
        ports: SidecarPorts,
    ): SidecarEndpointSnapshot

    suspend fun hasOwnedEndpoints(
        processId: Long,
        ports: SidecarPorts,
    ): Boolean
}

internal class LiveKitSidecarSupervisor(
    private val processLauncher: ISidecarProcessLauncher = WindowsSidecarProcessLauncher(),
    private val portAllocator: ISidecarPortAllocator = WindowsLoopbackPortAllocator(),
    private val configFactory: ISidecarConfigFactory = PrivateLiveKitConfigFactory(),
    private val readinessProbe: ISidecarReadinessProbe = WindowsLiveKitReadinessProbe(),
    private val timeouts: LiveKitSidecarTimeouts = LiveKitSidecarTimeouts(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val supportsPlatform: () -> Boolean = {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    },
) {
    private val mutex = Mutex()
    private val monitorScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutableState = MutableStateFlow<LiveKitSidecarState>(LiveKitSidecarState.Stopped)

    private var ownedProcess: ISidecarProcess? = null
    private var configLease: ISidecarConfigLease? = null
    private var processPorts: SidecarPorts? = null
    private var monitorJob: Job? = null
    private var closed = false

    val state: StateFlow<LiveKitSidecarState> = mutableState.asStateFlow()

    /** Starts one loopback-only sidecar and reports Ready only after health and PID-owned ports are verified. */
    suspend fun start(executable: Path): LiveKitSidecarState =
        mutex.withLock {
            if (closed) return@withLock LiveKitSidecarState.Failed(LiveKitSidecarFailure.Closed)

            val currentProcess = ownedProcess
            val currentState = mutableState.value
            if (currentState is LiveKitSidecarState.Ready && currentProcess?.isAlive() == true) {
                return@withLock currentState
            }
            if (currentProcess != null || configLease != null) {
                monitorJob?.cancel()
                val cleanupFailure =
                    withContext(NonCancellable) {
                        stopOwnedProcess(currentProcess, processPorts, configLease)
                    }
                if (cleanupFailure != null) {
                    val failed = LiveKitSidecarState.Failed(cleanupFailure)
                    mutableState.value = failed
                    return@withLock failed
                }
                clearOwnedResources()
            }

            mutableState.value = LiveKitSidecarState.Stopped
            startOwnedProcess(executable)
        }

    /** Stops only the exact Process instance launched by this supervisor; repeated calls are harmless. */
    suspend fun stop(): LiveKitSidecarState =
        mutex.withLock {
            val process = ownedProcess
            if (process == null && configLease == null) {
                if (!closed) mutableState.value = LiveKitSidecarState.Stopped
                return@withLock mutableState.value
            }

            mutableState.value = LiveKitSidecarState.Stopping(process?.processId)
            monitorJob?.cancel()
            val cleanupFailure =
                withContext(NonCancellable) {
                    stopOwnedProcess(process, processPorts, configLease)
                }
            if (cleanupFailure == null) {
                clearOwnedResources()
                mutableState.value = LiveKitSidecarState.Stopped
            } else {
                mutableState.value = LiveKitSidecarState.Failed(cleanupFailure)
            }
            mutableState.value
        }

    /** Stops the child and disables future starts atomically with respect to start. */
    suspend fun close(): LiveKitSidecarState =
        withContext(NonCancellable) {
            val result =
                mutex.withLock {
                    closed = true
                    val process = ownedProcess
                    if (process == null && configLease == null) {
                        mutableState.value = LiveKitSidecarState.Stopped
                        return@withLock mutableState.value
                    }

                    mutableState.value = LiveKitSidecarState.Stopping(process?.processId)
                    monitorJob?.cancel()
                    val cleanupFailure = stopOwnedProcess(process, processPorts, configLease)
                    if (cleanupFailure == null) {
                        clearOwnedResources()
                        mutableState.value = LiveKitSidecarState.Stopped
                    } else {
                        mutableState.value = LiveKitSidecarState.Failed(cleanupFailure)
                    }
                    mutableState.value
                }
            monitorScope.cancel()
            result
        }

    private suspend fun startOwnedProcess(executable: Path): LiveKitSidecarState {
        var process: ISidecarProcess? = null
        var lease: ISidecarConfigLease? = null
        var ports: SidecarPorts? = null
        mutableState.value = LiveKitSidecarState.Starting()

        return try {
            if (!supportsPlatform()) throw SidecarStartFailure(LiveKitSidecarFailure.UnsupportedOperatingSystem)
            ports =
                try {
                    portAllocator.allocate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SidecarStartFailure(LiveKitSidecarFailure.PortAllocationFailed)
                }
            mutableState.value = LiveKitSidecarState.Starting(ports)
            lease =
                try {
                    configFactory.create(ports)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SidecarStartFailure(LiveKitSidecarFailure.PrivateConfigUnavailable)
                }
            process =
                try {
                    processLauncher.launch(executable, lease.path)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SidecarStartFailure(LiveKitSidecarFailure.ProcessLaunchFailed)
                }
            ownedProcess = process
            configLease = lease
            processPorts = ports

            val isReady =
                withTimeoutOrNull(timeouts.readinessMillis) {
                    waitUntilReady(process, ports)
                } ?: throw SidecarStartFailure(LiveKitSidecarFailure.ReadinessTimedOut)

            if (!isReady || !process.isAlive()) {
                throw SidecarStartFailure(LiveKitSidecarFailure.ProcessExitedBeforeReady)
            }
            if (!lease.delete()) {
                throw SidecarStartFailure(LiveKitSidecarFailure.PrivateConfigCleanupFailed)
            }

            configLease = null
            val ready = LiveKitSidecarState.Ready(process.processId, ports)
            mutableState.value = ready
            monitorJob = monitorScope.launch { monitorUnexpectedExit(process) }
            ready
        } catch (cancelled: CancellationException) {
            val cleanupFailure = withContext(NonCancellable) { stopOwnedProcess(process, ports, lease) }
            if (cleanupFailure == null) {
                if (ownedProcess === process) clearOwnedResources()
            } else {
                ownedProcess = process
                configLease = lease
                processPorts = ports
            }
            mutableState.value =
                LiveKitSidecarState.Failed(
                    reason = LiveKitSidecarFailure.StartupCancelled,
                    cleanupFailure = cleanupFailure,
                )
            throw cancelled
        } catch (failure: SidecarStartFailure) {
            val cleanupFailure = withContext(NonCancellable) { stopOwnedProcess(process, ports, lease) }
            if (cleanupFailure == null) {
                if (ownedProcess === process) clearOwnedResources()
            } else {
                ownedProcess = process
                configLease = lease
                processPorts = ports
            }
            mutableState.value =
                LiveKitSidecarState.Failed(
                    reason = failure.reason,
                    cleanupFailure = cleanupFailure,
                )
            mutableState.value
        } catch (_: Exception) {
            val cleanupFailure = withContext(NonCancellable) { stopOwnedProcess(process, ports, lease) }
            if (cleanupFailure == null) {
                if (ownedProcess === process) clearOwnedResources()
            } else {
                ownedProcess = process
                configLease = lease
                processPorts = ports
            }
            mutableState.value =
                LiveKitSidecarState.Failed(
                    reason = LiveKitSidecarFailure.ReadinessProbeFailed,
                    cleanupFailure = cleanupFailure,
                )
            mutableState.value
        }
    }

    private suspend fun waitUntilReady(
        process: ISidecarProcess,
        ports: SidecarPorts,
    ): Boolean {
        while (true) {
            if (!process.isAlive()) throw SidecarStartFailure(LiveKitSidecarFailure.ProcessExitedBeforeReady)
            val snapshot =
                try {
                    readinessProbe.inspect(process.processId, ports)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SidecarStartFailure(LiveKitSidecarFailure.ReadinessProbeFailed)
                }
            if (!process.isAlive()) throw SidecarStartFailure(LiveKitSidecarFailure.ProcessExitedBeforeReady)
            if (snapshot.apiListenerOwners.any { it != process.processId } ||
                snapshot.iceUdpOwners.any { it != process.processId }
            ) {
                throw SidecarStartFailure(LiveKitSidecarFailure.PortAlreadyOwned)
            }
            if (!snapshot.ownedEndpointsAreLoopbackOnly) {
                throw SidecarStartFailure(LiveKitSidecarFailure.NonLoopbackBinding)
            }
            if (snapshot.apiHealthy &&
                process.processId in snapshot.apiListenerOwners &&
                process.processId in snapshot.iceUdpOwners
            ) {
                return true
            }
            delay(timeouts.probeIntervalMillis)
        }
    }

    private suspend fun monitorUnexpectedExit(process: ISidecarProcess) {
        try {
            process.awaitExit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed wait is still a terminal loss of trustworthy process supervision.
        }
        mutex.withLock {
            if (ownedProcess === process && mutableState.value is LiveKitSidecarState.Ready) {
                ownedProcess = null
                processPorts = null
                configLease?.delete()
                configLease = null
                mutableState.value = LiveKitSidecarState.Failed(LiveKitSidecarFailure.UnexpectedProcessExit)
            }
        }
    }

    private suspend fun stopOwnedProcess(
        process: ISidecarProcess?,
        ports: SidecarPorts?,
        lease: ISidecarConfigLease?,
    ): LiveKitSidecarFailure? {
        var terminated = process == null
        if (process != null) {
            try {
                if (process.isAlive()) {
                    process.requestStop()
                    terminated = process.awaitExit(timeouts.gracefulStopMillis)
                    if (!terminated) {
                        process.forceStop()
                        terminated = process.awaitExit(timeouts.forcedStopMillis)
                    }
                } else {
                    terminated = true
                }
            } catch (_: Exception) {
                try {
                    process.forceStop()
                    terminated = process.awaitExit(timeouts.forcedStopMillis)
                } catch (_: Exception) {
                    terminated = false
                }
            }
        }
        if (!terminated) return LiveKitSidecarFailure.ProcessTerminationTimedOut

        if (process != null && ports != null) {
            val releaseDeadline = System.nanoTime() + timeouts.endpointReleaseMillis * 1_000_000
            do {
                val stillOwned =
                    try {
                        readinessProbe.hasOwnedEndpoints(process.processId, ports)
                    } catch (_: Exception) {
                        true
                    }
                if (!stillOwned) break
                delay(minOf(timeouts.probeIntervalMillis, 100))
            } while (System.nanoTime() < releaseDeadline)
            val stillOwned =
                try {
                    readinessProbe.hasOwnedEndpoints(process.processId, ports)
                } catch (_: Exception) {
                    true
                }
            if (stillOwned) return LiveKitSidecarFailure.EndpointReleaseTimedOut
        }

        if (lease == null) return null
        return try {
            if (lease.delete()) null else LiveKitSidecarFailure.PrivateConfigCleanupFailed
        } catch (_: Exception) {
            LiveKitSidecarFailure.PrivateConfigCleanupFailed
        }
    }

    private fun clearOwnedResources() {
        ownedProcess = null
        configLease = null
        processPorts = null
        monitorJob = null
    }

    private class SidecarStartFailure(
        val reason: LiveKitSidecarFailure,
    ) : RuntimeException()
}
