package com.saytikus.gwatchtogether.platform.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveKitSidecarSupervisorTest {
    private val ports = SidecarPorts(apiTcp = 17880, iceUdp = 17881)

    @Test
    fun readyRequiresHealthAndBothOwnedLoopbackEndpoints() =
        runTest {
            val launcher = FakeProcessLauncher()
            val probe =
                FakeReadinessProbe(
                    SidecarEndpointSnapshot(
                        apiHealthy = true,
                        apiListenerOwners = setOf(101),
                        iceUdpOwners = setOf(101),
                        ownedEndpointsAreLoopbackOnly = true,
                    ),
                )
            val supervisor = supervisor(launcher, probe, StandardPorts())

            val state = supervisor.start(Path.of("livekit-server"))

            assertEquals(LiveKitSidecarState.Ready(101, ports), state)
            assertTrue(launcher.processes.single().isAlive())
            assertTrue(launcher.leases.single().deleted)
            assertEquals(LiveKitSidecarState.Stopped, supervisor.stop())
            assertEquals(LiveKitSidecarState.Stopped, supervisor.state.value)
            supervisor.close()
        }

    @Test
    fun zeroExitBeforeReadinessIsFailureAndPrivateConfigIsRemoved() =
        runTest {
            val launcher = FakeProcessLauncher().apply { exitOnLaunch = 0 }
            val supervisor = supervisor(launcher, FakeReadinessProbe(readySnapshot(101)), StandardPorts())

            val state = supervisor.start(Path.of("livekit-server"))

            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.ProcessExitedBeforeReady),
                state,
            )
            assertFalse(launcher.processes.single().isAlive())
            assertTrue(launcher.leases.single().deleted)
            supervisor.close()
        }

    @Test
    fun readinessTimeoutTerminatesOnlyTheOwnedProcess() =
        runTest {
            val launcher = FakeProcessLauncher()
            val probe = FakeReadinessProbe(readySnapshot(101).copy(apiHealthy = false))
            val supervisor =
                supervisor(
                    launcher,
                    probe,
                    StandardPorts(),
                    timeouts = testTimeouts(readinessMillis = 50, probeIntervalMillis = 10),
                )

            val state = supervisor.start(Path.of("livekit-server"))

            assertEquals(LiveKitSidecarState.Failed(LiveKitSidecarFailure.ReadinessTimedOut), state)
            assertFalse(launcher.processes.single().isAlive())
            assertEquals(1, launcher.processes.single().stopRequests)
            assertTrue(launcher.leases.single().deleted)
            supervisor.close()
        }

    @Test
    fun cleanupFailureRemainsVisibleAndCanBeRetriedBeforeRestart() =
        runTest {
            val launcher = FakeProcessLauncher()
            val probe = FakeReadinessProbe(readySnapshot(101))
            val supervisor = supervisor(launcher, probe, StandardPorts())
            assertIs<LiveKitSidecarState.Ready>(supervisor.start(Path.of("livekit-server")))
            probe.endpointsRemainOwned = true

            val failedStop = supervisor.stop()
            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.EndpointReleaseTimedOut),
                failedStop,
            )
            val failedRestart = supervisor.start(Path.of("livekit-server"))
            assertEquals(LiveKitSidecarState.Failed(LiveKitSidecarFailure.EndpointReleaseTimedOut), failedRestart)
            assertEquals(failedRestart, supervisor.state.value)

            probe.endpointsRemainOwned = false
            assertEquals(LiveKitSidecarState.Stopped, supervisor.stop())
            supervisor.close()
        }

    @Test
    fun startupCancellationStopsTheChildAndDeletesItsConfig() =
        runTest {
            val launcher = FakeProcessLauncher()
            val probe = FakeReadinessProbe(readySnapshot(101), waitForCancellation = true)
            val supervisor = supervisor(launcher, probe, StandardPorts())

            val start = async { supervisor.start(Path.of("livekit-server")) }
            runCurrent()
            start.cancelAndJoin()

            assertFalse(launcher.processes.single().isAlive())
            assertTrue(launcher.leases.single().deleted)
            assertEquals(
                LiveKitSidecarFailure.StartupCancelled,
                assertIs<LiveKitSidecarState.Failed>(supervisor.state.value).reason,
            )
            supervisor.close()
        }

    @Test
    fun cancellationDuringCloseCannotInterruptChildCleanup() =
        runTest {
            val launcher = FakeProcessLauncher()
            val supervisor = supervisor(launcher, FakeReadinessProbe(readySnapshot(101)), StandardPorts())
            assertIs<LiveKitSidecarState.Ready>(supervisor.start(Path.of("livekit-server")))
            launcher.processes.single().gracefulStopHangs = true

            val close = async { supervisor.close() }
            runCurrent()
            close.cancel()
            advanceUntilIdle()
            close.join()

            assertFalse(launcher.processes.single().isAlive())
            assertEquals(LiveKitSidecarState.Stopped, supervisor.state.value)
            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.Closed),
                supervisor.start(Path.of("livekit-server")),
            )
        }

    @Test
    fun closeSerializesWithWaitingStartsAndLeavesNoChildRunning() =
        runTest {
            val launcher = FakeProcessLauncher()
            val inspectionStarted = CompletableDeferred<Unit>()
            val releaseInspection = CompletableDeferred<Unit>()
            val probe =
                FakeReadinessProbe(
                    readySnapshot(101),
                    inspectionStarted = inspectionStarted,
                    releaseInspection = releaseInspection,
                )
            val supervisor = supervisor(launcher, probe, StandardPorts())

            val firstStart = async { supervisor.start(Path.of("livekit-server")) }
            runCurrent()
            inspectionStarted.await()
            val close = async { supervisor.close() }
            runCurrent()
            val racingStart = async { supervisor.start(Path.of("livekit-server")) }
            runCurrent()

            releaseInspection.complete(Unit)
            assertIs<LiveKitSidecarState.Ready>(firstStart.await())
            assertEquals(LiveKitSidecarState.Stopped, close.await())
            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.Closed),
                racingStart.await(),
            )
            assertEquals(1, launcher.processes.size)
            assertFalse(launcher.processes.single().isAlive())
            assertEquals(LiveKitSidecarState.Stopped, supervisor.state.value)
        }

    @Test
    fun externallyTerminatedReadyProcessIsReportedAsUnexpectedExit() =
        runTest {
            val launcher = FakeProcessLauncher()
            val supervisor = supervisor(launcher, FakeReadinessProbe(readySnapshot(101)), StandardPorts())
            supervisor.start(Path.of("livekit-server"))
            val process = launcher.processes.single()

            process.forceStop()
            advanceUntilIdle()

            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.UnexpectedProcessExit),
                supervisor.state.value,
            )
            assertEquals(0, process.stopRequests)
            assertEquals(LiveKitSidecarState.Stopped, supervisor.stop())
            supervisor.close()
        }

    @Test
    fun conflictingPortOwnerAndNonLoopbackBindingsAreRejected() =
        runTest {
            val conflictLauncher = FakeProcessLauncher()
            val conflictProbe =
                FakeReadinessProbe(
                    readySnapshot(101).copy(apiListenerOwners = setOf(202)),
                )
            val conflictSupervisor = supervisor(conflictLauncher, conflictProbe, StandardPorts())
            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.PortAlreadyOwned),
                conflictSupervisor.start(Path.of("livekit-server")),
            )
            assertFalse(conflictLauncher.processes.single().isAlive())
            conflictSupervisor.close()

            val bindingLauncher = FakeProcessLauncher()
            val bindingSupervisor =
                supervisor(
                    bindingLauncher,
                    FakeReadinessProbe(readySnapshot(101).copy(ownedEndpointsAreLoopbackOnly = false)),
                    StandardPorts(),
                )
            assertEquals(
                LiveKitSidecarState.Failed(LiveKitSidecarFailure.NonLoopbackBinding),
                bindingSupervisor.start(Path.of("livekit-server")),
            )
            assertFalse(bindingLauncher.processes.single().isAlive())
            bindingSupervisor.close()
        }

    private fun supervisor(
        launcher: FakeProcessLauncher,
        probe: FakeReadinessProbe,
        allocator: ISidecarPortAllocator,
        timeouts: LiveKitSidecarTimeouts = testTimeouts(),
    ): LiveKitSidecarSupervisor =
        LiveKitSidecarSupervisor(
            processLauncher = launcher,
            portAllocator = allocator,
            configFactory = FakeConfigFactory(launcher.leases),
            readinessProbe = probe,
            timeouts = timeouts,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            supportsPlatform = { true },
        )

    private fun readySnapshot(processId: Long) =
        SidecarEndpointSnapshot(
            apiHealthy = true,
            apiListenerOwners = setOf(processId),
            iceUdpOwners = setOf(processId),
            ownedEndpointsAreLoopbackOnly = true,
        )

    private fun testTimeouts(
        readinessMillis: Long = 100,
        probeIntervalMillis: Long = 10,
    ) = LiveKitSidecarTimeouts(
        readinessMillis = readinessMillis,
        probeIntervalMillis = probeIntervalMillis,
        gracefulStopMillis = 10,
        forcedStopMillis = 10,
        endpointReleaseMillis = 10,
    )

    private class StandardPorts : ISidecarPortAllocator {
        override suspend fun allocate(): SidecarPorts = SidecarPorts(17880, 17881)
    }

    private class FakeReadinessProbe(
        private val snapshot: SidecarEndpointSnapshot,
        private val waitForCancellation: Boolean = false,
        private val inspectionStarted: CompletableDeferred<Unit>? = null,
        private val releaseInspection: CompletableDeferred<Unit>? = null,
    ) : ISidecarReadinessProbe {
        var endpointsRemainOwned = false

        override suspend fun inspect(
            processId: Long,
            ports: SidecarPorts,
        ): SidecarEndpointSnapshot {
            if (waitForCancellation) awaitCancellation()
            inspectionStarted?.complete(Unit)
            releaseInspection?.await()
            return snapshot
        }

        override suspend fun hasOwnedEndpoints(
            processId: Long,
            ports: SidecarPorts,
        ): Boolean = endpointsRemainOwned
    }

    private class FakeProcessLauncher : ISidecarProcessLauncher {
        val processes = mutableListOf<FakeSidecarProcess>()
        val leases = mutableListOf<FakeConfigLease>()
        var exitOnLaunch: Int? = null

        override suspend fun launch(
            executable: Path,
            configFile: Path,
        ): ISidecarProcess {
            val process = FakeSidecarProcess(processId = 101)
            processes += process
            exitOnLaunch?.let(process::exit)
            return process
        }
    }

    private class FakeSidecarProcess(
        override val processId: Long,
    ) : ISidecarProcess {
        private val exit = CompletableDeferred<Int>()
        private var alive = true
        var stopRequests = 0
        var gracefulStopHangs = false

        override fun isAlive(): Boolean = alive

        override suspend fun awaitExit(): Int = exit.await()

        override suspend fun awaitExit(timeoutMillis: Long): Boolean =
            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                awaitExit()
                true
            } ?: false

        override fun requestStop() {
            stopRequests++
            if (!gracefulStopHangs) exit(143)
        }

        override fun forceStop() {
            exit(137)
        }

        fun exit(code: Int) {
            alive = false
            exit.complete(code)
        }
    }

    private class FakeConfigFactory(
        private val leases: MutableList<FakeConfigLease>,
    ) : ISidecarConfigFactory {
        override suspend fun create(ports: SidecarPorts): ISidecarConfigLease {
            val path = Files.createTempFile("gwt-sidecar-test-", ".yaml")
            val lease = FakeConfigLease(path)
            leases += lease
            return lease
        }
    }

    private class FakeConfigLease(
        override val path: Path,
    ) : ISidecarConfigLease {
        var deleted = false
            private set

        override suspend fun delete(): Boolean {
            Files.deleteIfExists(path)
            deleted = true
            return true
        }
    }
}
