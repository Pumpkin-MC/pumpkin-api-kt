package io.github.pumpkinmc.gradle

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import java.net.URI
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

private val toolVersions: Properties by lazy {
    Properties().apply {
        checkNotNull(PumpkinPlugin::class.java.getResourceAsStream("tool-versions.properties")) {
            "Packaged tool versions are missing."
        }.use { load(it) }
    }
}

abstract class PumpkinExtension @Inject constructor(objects: ObjectFactory) {
    val apiGroup: Property<String> = objects.property(String::class.java).convention("io.github.pumpkin-mc")
    val apiArtifact: Property<String> = objects.property(String::class.java).convention("pumpkin-api-kt")
    val apiVersion: Property<String> = objects.property(String::class.java).convention("0.1.0-dev")
    val wasmToolsVersion: Property<String> = objects.property(String::class.java).convention(toolVersions.getProperty("wasmToolsVersion"))
    val binaryenVersion: Property<String> = objects.property(String::class.java).convention(toolVersions.getProperty("binaryenVersion"))
    val pluginClass: Property<String> = objects.property(String::class.java)
}

class PumpkinPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<PumpkinExtension>("pumpkin")
        val apiSources = project.configurations.create("pumpkinApiSources") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            description = "The generated Pumpkin Kotlin API source snapshot."
        }

        project.afterEvaluate {
            configurePlugin(project, extension, apiSources)
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    private fun configurePlugin(
        project: Project,
        extension: PumpkinExtension,
        apiSources: Configuration,
    ) {
        require(project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
            "io.github.pumpkin-mc.plugin requires org.jetbrains.kotlin.multiplatform."
        }

        // Binaryen 130 fixes Linux release stack overflows on Kotlin-generated Wasm.
        // https://github.com/WebAssembly/binaryen/pull/8595
        project.plugins.apply(BinaryenPlugin::class.java)
        project.extensions.getByType(BinaryenEnvSpec::class.java).version.set(extension.binaryenVersion)

        project.dependencies.add(
            apiSources.name,
            "${extension.apiGroup.get()}:${extension.apiArtifact.get()}-wasm-wasi:${extension.apiVersion.get()}:sources@jar",
        )

        val unpackApiSources = project.tasks.register<Sync>("unpackPumpkinApiSources") {
            group = "build setup"
            description = "Unpacks the published Pumpkin API source snapshot for component compilation."
            from(project.provider { project.zipTree(apiSources.singleFile) })
            exclude("**/plugin/PluginFactory.kt")
            into(project.layout.buildDirectory.dir("generated/pumpkin-api"))
        }

        val pluginBootstrapDirectory = project.layout.buildDirectory.dir("generated/pumpkin-plugin-bootstrap")
        val pluginFactorySource = pluginBootstrapDirectory.map { it.file("plugin/PluginFactory.kt") }
        val generatePluginBootstrap = project.tasks.register("generatePumpkinPluginBootstrap") {
            group = "build setup"
            description = "Generates the bridge from Pumpkin's WIT exports to the plugin implementation."
            inputs.property("pluginClass", extension.pluginClass)
            outputs.file(pluginFactorySource)

            doLast {
                val pluginClass = checkNotNull(extension.pluginClass.orNull) {
                    "Set pumpkin.pluginClass to your no-argument PumpkinPlugin implementation."
                }
                val output = pluginFactorySource.get().asFile
                output.parentFile.mkdirs()
                output.writeText(
                    """
                    package plugin

                    internal fun createPlugin(): PumpkinPlugin = $pluginClass()
                    """.trimIndent() + "\n",
                )
            }
        }

        project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            .sourceSets.named("wasmWasiMain") {
                kotlin.srcDir(unpackApiSources)
                kotlin.srcDir(pluginBootstrapDirectory)
            }

        project.tasks.named("compileKotlinWasmWasi") {
            dependsOn(generatePluginBootstrap)
        }

        val unpackedApiDirectory = unpackApiSources.map { it.destinationDir }
        val witDirectory = unpackedApiDirectory.map { it.resolve("wit/v0.1") }
        val reactorAdapter = unpackedApiDirectory.map { it.resolve("wasi/wasi_snapshot_preview1.reactor.wasm") }
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
        val wasmToolsDirectory = project.layout.buildDirectory.dir(
            "tools/wasm-tools/${extension.wasmToolsVersion.get()}",
        )
        val wasmTools = wasmToolsDirectory.map { it.file("bin/wasm-tools$executableSuffix") }
        val wasmToolsTarget = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "aarch64-macos"
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "x86_64-macos"
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "aarch64-windows"
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "x86_64-windows"
            System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "aarch64-linux"
            System.getProperty("os.arch") in setOf("x86_64", "amd64") -> "x86_64-linux"
            else -> error("Unsupported wasm-tools host architecture: ${System.getProperty("os.arch")}")
        }
        val wasmToolsArchiveExtension = if (wasmToolsTarget.endsWith("windows")) "zip" else "tar.gz"
        val wasmToolsUrl = "https://github.com/bytecodealliance/wasm-tools/releases/download/v${extension.wasmToolsVersion.get()}/" +
            "wasm-tools-${extension.wasmToolsVersion.get()}-$wasmToolsTarget.$wasmToolsArchiveExtension"

        val installWasmTools = project.tasks.register("installWasmTools") {
            group = "build setup"
            description = "Downloads the pinned wasm-tools release executable."
            inputs.property("version", extension.wasmToolsVersion)
            inputs.property("target", wasmToolsTarget)
            inputs.property("url", wasmToolsUrl)
            outputs.file(wasmTools)

            onlyIf("the requested wasm-tools version is not already installed") {
                !installedWasmToolsMatches(wasmTools.get().asFile, extension.wasmToolsVersion.get())
            }

            doLast {
                val installationDirectory = wasmToolsDirectory.get().asFile
                val archive = temporaryDir.resolve("wasm-tools.$wasmToolsArchiveExtension")
                val installedExecutable = wasmTools.get().asFile
                installationDirectory.deleteRecursively()
                installationDirectory.mkdirs()
                installedExecutable.parentFile.mkdirs()

                URI(wasmToolsUrl).toURL().openStream().use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                fun runCommand(vararg command: String) {
                    check(ProcessBuilder(*command).inheritIO().start().waitFor() == 0) {
                        "wasm-tools archive extraction failed"
                    }
                }

                if (wasmToolsTarget.endsWith("windows")) {
                    runCommand(
                        "powershell", "-NoProfile", "-Command",
                        "Expand-Archive -Force '$archive' '$installationDirectory'; " +
                            "Get-ChildItem -Path '$installationDirectory' -Recurse -Filter wasm-tools.exe | " +
                            "Select-Object -First 1 | Copy-Item -Destination '$installedExecutable'",
                    )
                } else {
                    runCommand("tar", "-xzf", archive.absolutePath, "-C", installationDirectory.absolutePath, "--strip-components=1")
                }

                if (!installedExecutable.isFile) {
                    val downloadedExecutable = installationDirectory.walkTopDown().firstOrNull {
                        it.isFile && it.name == "wasm-tools$executableSuffix"
                    }
                    checkNotNull(downloadedExecutable) { "wasm-tools archive did not contain wasm-tools$executableSuffix" }
                    downloadedExecutable.copyTo(installedExecutable, overwrite = true)
                }
                installedExecutable.setExecutable(true)
                check(installedWasmToolsMatches(installedExecutable, extension.wasmToolsVersion.get())) {
                    "The downloaded wasm-tools executable does not report the requested version."
                }
            }
        }

        val projectWasmName = project.name
        val compileReleaseWasm = project.tasks.named<BinaryenExec>(
            "compileProductionExecutableKotlinWasmWasiOptimize",
        )
        val releaseCoreWasm = compileReleaseWasm.flatMap {
            it.outputDirectory.file("$projectWasmName.wasm")
        }
        val embeddedReleaseComponent = project.layout.buildDirectory.file(
            "intermediates/pumpkin-components/release/$projectWasmName-embedded.wasm",
        )
        val releaseComponent = project.layout.buildDirectory.file("$projectWasmName.wasm")

        val embedComponentWitRelease = project.tasks.register("embedComponentWitRelease", org.gradle.api.tasks.Exec::class.java) {
            group = "build"
            description = "Embeds Pumpkin's WIT into the release Kotlin/Wasm module."
            dependsOn(compileReleaseWasm, installWasmTools)
            inputs.dir(witDirectory)
            inputs.file(releaseCoreWasm)
            inputs.file(wasmTools)
            outputs.file(embeddedReleaseComponent)

            executable = wasmTools.get().asFile.absolutePath
            argumentProviders.add(componentEmbedArguments(witDirectory, releaseCoreWasm, embeddedReleaseComponent))
            doFirst {
                embeddedReleaseComponent.get().asFile.parentFile.mkdirs()
            }
        }

        val assemblePluginRelease = project.tasks.register("assemblePluginRelease", org.gradle.api.tasks.Exec::class.java) {
            group = "build"
            description = "Creates the release WebAssembly component that Pumpkin can load."
            dependsOn(embedComponentWitRelease)
            inputs.file(embeddedReleaseComponent)
            inputs.file(reactorAdapter)
            inputs.file(wasmTools)
            outputs.file(releaseComponent)

            executable = wasmTools.get().asFile.absolutePath
            argumentProviders.add(componentNewArguments(embeddedReleaseComponent, reactorAdapter, releaseComponent))
        }

        val validatePluginRelease = project.tasks.register("validatePluginRelease", org.gradle.api.tasks.Exec::class.java) {
            group = "verification"
            description = "Validates the release WebAssembly component."
            dependsOn(assemblePluginRelease)
            inputs.file(releaseComponent)
            inputs.file(wasmTools)
            commandLine(wasmTools.get().asFile.absolutePath, "validate", releaseComponent.get().asFile.absolutePath)
        }

        project.tasks.named("assemble") { dependsOn(assemblePluginRelease) }
        project.tasks.named("check") { dependsOn(validatePluginRelease) }
    }

    private fun installedWasmToolsMatches(executable: File, version: String): Boolean {
        if (!executable.isFile) return false
        return runCatching {
            val process = ProcessBuilder(executable.absolutePath, "--version").redirectErrorStream(true).start()
            try {
                process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    Regex("^wasm-tools ${Regex.escape(version)}(?:\\s|$)")
                        .containsMatchIn(process.inputStream.bufferedReader().use { it.readText() })
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrDefault(false)
    }

    private fun componentEmbedArguments(
        witDirectory: org.gradle.api.provider.Provider<java.io.File>,
        releaseCoreWasm: org.gradle.api.provider.Provider<RegularFile>,
        embeddedReleaseComponent: org.gradle.api.provider.Provider<RegularFile>,
    ) = CommandLineArgumentProvider {
        listOf(
            "component", "embed",
            witDirectory.get().absolutePath,
            releaseCoreWasm.get().asFile.absolutePath,
            "-o", embeddedReleaseComponent.get().asFile.absolutePath,
        )
    }

    private fun componentNewArguments(
        embeddedReleaseComponent: org.gradle.api.provider.Provider<RegularFile>,
        reactorAdapter: org.gradle.api.provider.Provider<java.io.File>,
        releaseComponent: org.gradle.api.provider.Provider<RegularFile>,
    ) = CommandLineArgumentProvider {
        listOf(
            "component", "new",
            embeddedReleaseComponent.get().asFile.absolutePath,
            "--adapt", "wasi_snapshot_preview1=${reactorAdapter.get().absolutePath}",
            "-o", releaseComponent.get().asFile.absolutePath,
        )
    }
}
