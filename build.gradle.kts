import java.time.Instant
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    alias(libs.plugins.shadow)
}

group = "org.a1tsign"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    implementation(libs.hikaricp)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    implementation(libs.postgresql)

    implementation(libs.java.jwt)
    implementation(libs.bcrypt)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.a1tsign.vds.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val projectVersion = project.version.toString()
    val buildTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    inputs.property("version", projectVersion)
    inputs.property("buildTimestamp", buildTimestamp)
    filesMatching("version.properties") {
        expand("version" to projectVersion, "buildTimestamp" to buildTimestamp)
    }
}

tasks.shadowJar {
    mergeServiceFiles()
    // Only the two paths shadow actually has a transformer for get an explicit INCLUDE, so every
    // copy reaches that transformer instead of being silently dropped before it runs:
    // *.kotlin_module (KotlinModuleMetadataTransformer) and META-INF/services/* (the
    // ServiceFileTransformer registered by mergeServiceFiles() above, which merges the provider
    // lists). Everything else keeps the task's default EXCLUDE, so a genuine class/resource
    // conflict elsewhere still surfaces instead of silently picking one side.
    eachFile {
        if (name.endsWith(".kotlin_module") || path.startsWith("META-INF/services/")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
}
