package com.saytikus.gwatchtogether

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.saytikus.gwatchtogether.platform.livekit.runDesktopLiveKitSidecarHeadlessMode
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.singleOrNull() == "--livekit-sidecar-headless") {
        val exitCode =
            runBlocking {
                runDesktopLiveKitSidecarHeadlessMode(
                    input = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8)),
                    output = PrintWriter(System.out, true, StandardCharsets.UTF_8),
                )
            }
        if (exitCode != 0) exitProcess(exitCode)
        return
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "gwatchtogether",
        ) {
            App()
        }
    }
}
