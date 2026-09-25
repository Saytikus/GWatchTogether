package com.saytikus.gwatchtogether.platform.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.file.Path

/** Desktop-owned lifecycle composition seam for the Windows LiveKit sidecar. */
internal class DesktopLiveKitSidecarHost(
    private val supervisor: LiveKitSidecarSupervisor = LiveKitSidecarSupervisor(),
) {
    val state: kotlinx.coroutines.flow.StateFlow<LiveKitSidecarState> = supervisor.state

    suspend fun start(executable: Path): LiveKitSidecarState = supervisor.start(executable)

    suspend fun stop(): LiveKitSidecarState = supervisor.stop()

    suspend fun close(): LiveKitSidecarState = supervisor.close()
}

/** Runs the explicit `--livekit-sidecar-headless` console mode; normal Desktop launch never calls this mode. */
internal suspend fun runDesktopLiveKitSidecarHeadlessMode(
    input: BufferedReader,
    output: PrintWriter,
    host: DesktopLiveKitSidecarHost = DesktopLiveKitSidecarHost(),
): Int {
    var exitCode = 0
    val shutdownHook =
        Thread(
            { runBlocking { host.close() } },
            "gwt-livekit-sidecar-shutdown",
        )
    var shutdownHookRegistered = false
    output.println("P0-06 internal headless mode: enter 'start <executable>', 'stop', or 'quit'.")
    try {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        shutdownHookRegistered = true
        while (true) {
            val command = input.readLine() ?: break
            when {
                command.startsWith("start ") -> {
                    val executablePath = command.removePrefix("start ").trim()
                    val result =
                        if (executablePath.isEmpty()) {
                            LiveKitSidecarState.Failed(LiveKitSidecarFailure.ProcessLaunchFailed)
                        } else {
                            host.start(Path.of(executablePath))
                        }
                    when (result) {
                        is LiveKitSidecarState.Ready -> {
                            output.println(
                                "READY pid=${result.processId} api=127.0.0.1:${result.ports.apiTcp} " +
                                    "iceUdp=127.0.0.1:${result.ports.iceUdp}",
                            )
                        }

                        is LiveKitSidecarState.Failed -> {
                            output.println("FAILED reason=${result.reason} cleanup=${result.cleanupFailure ?: "none"}")
                            exitCode = 1
                        }

                        else -> {
                            output.println("FAILED reason=UnexpectedLifecycleState")
                            exitCode = 1
                        }
                    }
                }

                command == "stop" -> {
                    when (val result = host.stop()) {
                        LiveKitSidecarState.Stopped -> {
                            output.println("STOPPED")
                        }

                        is LiveKitSidecarState.Failed -> {
                            output.println("FAILED reason=${result.reason}")
                            exitCode = 1
                        }

                        else -> {
                            output.println("FAILED reason=UnexpectedLifecycleState")
                            exitCode = 1
                        }
                    }
                }

                command == "quit" -> {
                    break
                }

                else -> {
                    output.println("ERROR unsupported-command")
                    exitCode = 2
                    break
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        output.println("ERROR headless-lifecycle-failed")
        exitCode = 1
    } finally {
        val closed = withContext(NonCancellable) { host.close() }
        when (closed) {
            LiveKitSidecarState.Stopped -> {
                output.println("CLOSED")
            }

            is LiveKitSidecarState.Failed -> {
                output.println("CLOSE_FAILED reason=${closed.reason}")
                exitCode = 1
            }

            else -> {
                output.println("CLOSE_FAILED reason=UnexpectedLifecycleState")
                exitCode = 1
            }
        }
        if (shutdownHookRegistered) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // The JVM shutdown hook is already running and closes the same supervisor atomically.
            }
        }
        output.flush()
    }
    return exitCode
}
