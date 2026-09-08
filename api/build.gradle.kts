import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = "io.github.pumpkin-mc"
version = providers.gradleProperty("pumpkinApiVersion").getOrElse("0.1.0-dev")

base {
    archivesName.set("pumpkin-api-kt")
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }
}

publishing {
    publications.named<MavenPublication>("kotlinMultiplatform") {
        artifactId = "pumpkin-api-kt"
    }
    publications.named<MavenPublication>("wasmWasi") {
        artifactId = "pumpkin-api-kt-wasm-wasi"
    }
}

val cargoHome = providers.environmentVariable("CARGO_HOME")
    .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
val executableSuffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
val cargoExecutable = cargoHome.map { "$it/bin/cargo$executableSuffix" }

val witDirectory = rootProject.layout.projectDirectory.dir("wit/v0.1")
val toolVersions = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/tool-versions.properties")).asText.get().reader())
}
val witBindgenRevision = toolVersions.getProperty("witBindgenRevision")
val witBindgenDirectory = layout.projectDirectory.dir("tools/wit-bindgen/$witBindgenRevision")
val witBindgen = witBindgenDirectory.file("bin/wit-bindgen$executableSuffix")
val generatedBindings = layout.buildDirectory.dir("generated/wit/wasmWasiMain/kotlin")

val installWitBindgen by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Installs Pumpkin's pinned Kotlin binding generator."

    inputs.property("revision", witBindgenRevision)
    outputs.dir(witBindgenDirectory)

    onlyIf("the pinned binding generator is not already installed") {
        val manifest = witBindgenDirectory.file(".crates.toml").asFile
        val matchesRevision = manifest.isFile &&
            manifest.readText().contains("git+https://github.com/Kotlin/wit-bindgen?rev=$witBindgenRevision#$witBindgenRevision)")
        val usable = matchesRevision && witBindgen.asFile.isFile && runCatching {
            val process = ProcessBuilder(witBindgen.asFile.absolutePath, "--version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrDefault(false)
        !usable
    }

    commandLine(
        cargoExecutable.get(),
        "install",
        "wit-bindgen-cli",
        "--force",
        "--git", "https://github.com/Kotlin/wit-bindgen",
        "--rev", witBindgenRevision,
        "--locked",
        "--root", witBindgenDirectory.asFile.absolutePath,
    )
}

val generateWitBindings by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates Kotlin bindings for the published Pumpkin API."

    dependsOn(installWitBindgen)
    inputs.dir(witDirectory)
    inputs.file(witBindgen)
    inputs.property("kotlinPackage", "pumpkin")
    inputs.property("kotlinImports", "plugin.*")
    outputs.dir(generatedBindings)

    doFirst {
        val outputDirectory = generatedBindings.get().asFile
        check(outputDirectory.deleteRecursively() || !outputDirectory.exists()) {
            "Could not remove stale bindings from $outputDirectory"
        }
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Could not create bindings directory $outputDirectory"
        }
    }

    commandLine(
        witBindgen.asFile.absolutePath,
        "kotlin",
        "--kotlin-imports", "plugin.*",
        "--kotlin-package-name", "pumpkin",
        witDirectory.asFile.absolutePath,
        "--out-dir", generatedBindings.get().asFile.absolutePath,
    )
}

kotlin {
    sourceSets.named("wasmWasiMain") {
        kotlin.srcDir(generateWitBindings)
    }
}

tasks.named<Jar>("wasmWasiSourcesJar") {
    from(rootProject.layout.projectDirectory.dir("wit/v0.1")) {
        into("wit/v0.1")
    }
    from(rootProject.layout.projectDirectory.file("wasi_snapshot_preview1.reactor.wasm")) {
        into("wasi")
    }
}
