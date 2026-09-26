plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.protobuf)
}

// Protobuf generation is JVM-only; generated Java DTOs never enter commonMain or domain models.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    api(project(":core"))
    api(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
