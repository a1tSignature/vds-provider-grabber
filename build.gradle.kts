import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.pitest)
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

    implementation(libs.jsoup)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    pitest(libs.pitest.junit5.plugin)
}

pitest {
    targetClasses.set(setOf("org.a1tsign.vds.*"))
    targetTests.set(setOf("org.a1tsign.vds.*"))
    outputFormats.set(setOf("HTML"))
    timestampedReports.set(false)
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

// `-PskipFrontend` builds the backend alone, with no Node/npm required -- see buildFrontend's
// onlyIf below, mirrored on syncFrontendDist and testFrontend.
val skipFrontend = providers.gradleProperty("skipFrontend")

val requiredNodeMessage =
    "Node.js (see frontend/.nvmrc for the exact version) and npm are required to build the " +
        "frontend. Install them, or pass -PskipFrontend to build the backend without the bundled UI."

/** No `Project` access: safe to call from a task action under the configuration cache. */
fun npmAvailable(): Boolean {
    val path = System.getenv("PATH") ?: return false
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val candidates = if (isWindows) listOf("npm.cmd", "npm.exe") else listOf("npm")
    return path.splitToSequence(File.pathSeparatorChar)
        .any { dir -> candidates.any { name -> File(dir, name).canExecute() } }
}

// Captured once, at configuration time, into a plain local: referencing the script's implicit
// `Project` receiver (e.g. an unqualified `providers`/`exec` call) from inside `doLast` below is
// execution-time `Project` access, unsupported under Gradle 9's configuration cache.
// `ProviderFactory.exec` is the sanctioned replacement for `project.exec` there.
val execProviders = providers
val frontendDir = layout.projectDirectory.dir("frontend")
val frontendDistDir = layout.projectDirectory.dir("frontend/dist")

/** `npm ci` then `npm run build`, as one task so its up-to-date check covers both -- a plain [Exec] only runs a single command line. */
val buildFrontend = tasks.register("buildFrontend") {
    onlyIf("skipFrontend is not set") { !skipFrontend.isPresent }
    inputs.files(
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json"),
        frontendDir.file("vite.config.ts"),
        frontendDir.file("tsconfig.json"),
        frontendDir.file("index.html"),
        frontendDir.file(".nvmrc"),
    ).withPropertyName("frontendConfig").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(frontendDir.dir("src")).withPropertyName("frontendSrc").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(frontendDistDir).withPropertyName("frontendDist")
    doFirst {
        if (!npmAvailable()) throw GradleException(requiredNodeMessage)
    }
    doLast {
        val cwd = frontendDir.asFile
        execProviders.exec {
            workingDir(cwd)
            commandLine("npm", "ci")
        }.result.get()
        execProviders.exec {
            workingDir(cwd)
            commandLine("npm", "run", "build")
        }.result.get()
    }
}

val syncFrontendDist = tasks.register<Sync>("syncFrontendDist") {
    // No `onlyIf` here: `Sync` (unlike `Copy`) deletes anything in `into` that the current `from`
    // doesn't produce, which is exactly what clears out a *previous*, non-skipped run's bundle --
    // skipping this task outright would leave that stale output on the classpath instead. The
    // empty list on the skipped branch is what makes `from` produce nothing to sync.
    dependsOn(buildFrontend)
    from(providers.provider { if (skipFrontend.isPresent) emptyList<Any>() else listOf(frontendDistDir) })
    into(layout.buildDirectory.dir("generated/frontend/static"))
}

sourceSets {
    main {
        // Registers the parent of syncFrontendDist's "static" output dir, not that dir itself,
        // so the bundle lands on the classpath AS "static/..." -- matching the "static" basePackage
        // api/routes/StaticRoutes.kt serves in production, instead of spilling index.html/assets
        // straight into the resources root.
        resources.srcDir(layout.buildDirectory.dir("generated/frontend"))
    }
}

tasks.processResources {
    dependsOn(syncFrontendDist)
}

val testFrontend = tasks.register<Exec>("testFrontend") {
    onlyIf("skipFrontend is not set") { !skipFrontend.isPresent }
    dependsOn(buildFrontend)
    workingDir = layout.projectDirectory.dir("frontend").asFile
    commandLine("npm", "test")
    doFirst {
        if (!npmAvailable()) throw GradleException(requiredNodeMessage)
    }
}

tasks.check {
    dependsOn(testFrontend)
}
