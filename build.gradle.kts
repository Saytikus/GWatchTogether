import com.diffplug.spotless.LineEnding
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

val developmentMilestone =
    providers
        .gradleProperty("gwatchtogether.developmentMilestone")
        .orElse("POC-0")
val gitRevision =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }
        .filter { it.matches(Regex("[0-9a-f]{7,40}")) }
        .orElse("local")
val developmentBuildId =
    providers
        .gradleProperty("gwatchtogether.developmentBuildId")
        .orElse(gitRevision.map { "git-$it" })
val developmentIdentity =
    developmentMilestone.zip(developmentBuildId) { milestone, buildId ->
        "$milestone-dev.$buildId"
    }

allprojects {
    version = developmentIdentity.get()

    tasks.withType<JavaExec>().configureEach {
        systemProperty("gwatchtogether.build.identity", version.toString())
    }
    tasks.withType<Test>().configureEach {
        systemProperty("gwatchtogether.build.identity", version.toString())
    }
}

plugins {
    alias(libs.plugins.spotless)
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.protobuf) apply false
}

spotless {
    lineEndings = LineEnding.UNIX

    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/node_modules/**", "design/gui/output/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/node_modules/**", "design/gui/output/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs the repository Kotlin and Gradle Kotlin DSL quality gates."
    dependsOn("spotlessCheck")
}
