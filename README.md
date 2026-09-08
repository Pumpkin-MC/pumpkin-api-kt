# Pumpkin Plugin API for Kotlin

Build Kotlin plugins for [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) as WebAssembly components.

The `api` module packages generated Kotlin bindings, the WIT export bridge, and the WIT snapshot and WASI adapter needed to build a component. The `gradle-plugin` module integrates that package into a plugin author's build. Plugin authors do not need Rust, `wit-bindgen`, or a WIT checkout.

Kotlin/Wasm component support is still experimental. The planned release destinations are Maven Central for the API and the Gradle Plugin Portal for the build plugin, though that is not certain yet.

<!-- TODO: Replace the version placeholders below with published versions once available. -->

## Create a plugin

Create a Gradle project with a Gradle 9.x wrapper and JDK 17 or later. You do not need to clone this repository.

In `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "my-plugin"
```

In `build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("io.github.pumpkin-mc.plugin") version "<plugin-version>"
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.executable()
    }
}

pumpkin {
    apiVersion.set("<api-version>")
    pluginClass.set("example.MyPlugin")
}
```

> [!important]
> Put your implementation in `src/wasmWasiMain/kotlin/my_plugin/MyPlugin.kt`. The `wasmWasiMain` source set is required; `src/main/kotlin` is not the source directory for this target.

```kotlin
package my_plugin

import plugin.PluginMetadata
import plugin.PumpkinPlugin
import pumpkin.Context
import pumpkin.Logging

class MyPlugin : PumpkinPlugin() {
    override fun metadata() = PluginMetadata(
        name = "my-plugin",
        version = "0.1.0",
        authors = listOf("Your name"),
        description = "My first Kotlin plugin",
        dependencies = emptyList(),
        permissions = emptyList(),
    )

    override fun onLoad(context: Context.Context): Result<Unit> {
        Logging.log(Logging.Level.INFO, "Hello from Kotlin!")
        return Result.success(Unit)
    }
}
```

The configured class must extend `PumpkinPlugin` and be constructible without arguments. Override the callbacks your plugin uses; most optional handler defaults throw if invoked without an implementation.

## Build and load

From your plugin project:

```sh
./gradlew build
```

The Gradle plugin downloads a pinned prebuilt `wasm-tools`, compiles the Kotlin code, assembles the component, and validates it. The component is written to `build/<project-name>.wasm` (`build/my-plugin.wasm` above). Copy it into the Pumpkin server's `plugins` directory and start the server.

To assemble only the component, run `./gradlew assemblePluginRelease`. Gradle skips tasks when their inputs are unchanged. The consumer's generated sources and downloaded tools live under `build/`, so `./gradlew clean` removes them.

## How the dependency and bootstrap work

The API publishes both a Kotlin/WASI KLIB and a generated-source archive. Currently, WIT exports must be compiled as part of the final plugin to be retained by the Kotlin/Wasm linker. The Gradle plugin therefore resolves the API's source archive through `pumpkinApiSources`, unpacks it, and adds it to `wasmWasiMain`. You do not add a separate `implementation(...)` dependency or run binding generation yourself.

`generatePumpkinPluginBootstrap` generates a factory from `pumpkin.pluginClass`:

```kotlin
package plugin

internal fun createPlugin(): PumpkinPlugin = example.MyPlugin()
```

This replaces the API's placeholder factory during consumer compilation. On the first exported callback, the API bridge constructs and caches your plugin instance. Later callbacks use the same instance. The package supplies the generator-specific `PluginRootFunctionsExportsImpl` and `MetadataImpl` bridges.

## Server compatibility

Choose an API version compatible with your Pumpkin server. Errors such as `no export ... found`, `type-checking export func ...`, or missing imports indicate a possible mismatch between the plugin's packaged WIT and the server's contract. Update `pumpkin.apiVersion` to a compatible release and rebuild the component.

## API development

Contributors building the API itself need JDK 17 or later and a host Rust toolchain. Gradle builds the pinned Kotlin-enabled `wit-bindgen` from source; no Rust WebAssembly target is required.

From this repository, generate the bindings and publish both development packages locally:

```sh
git submodule update --init --recursive
./gradlew :api:publishToMavenLocal :gradle-plugin:publishToMavenLocal
```

To test these local packages in a consumer, add `mavenLocal()` before the other repositories in both `settings.gradle.kts`'s `pluginManagement.repositories` and `build.gradle.kts`'s `repositories`. Set both version placeholders to `0.1.0-dev`. After republishing changes under that version, run `./gradlew build --refresh-dependencies` in the consumer.

The [example](example/) is a standalone consumer with its own Gradle wrapper. After publishing locally, run `./example/gradlew -p example build --refresh-dependencies`, or open `example/` as a Gradle project in IntelliJ. The output is `example/build/pumpkin-example.wasm`.

The root build checks `api` and `gradle-plugin` automatically. 

Build tool pins live in `gradle/tool-versions.properties`. 

Gradle checks restored executables before reusing them. 
- Binding generation tracks the WIT directory, generator executable, and generation options as task inputs.
- Binaryen and Gradle dependencies remain managed by the Gradle setup action.

### Updating the WIT

API maintainers should update the `wit` submodule to the revision compatible with the target Pumpkin server, then regenerate and republish the API. Commit the updated submodule revision with any required callback changes so builds use the same contract.

## Attributions

This project was primarily derived from [@jmrtsh](https://github.com/jmrtsh)'s work on [Kotlin/sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin), which is licensed under [Apache-2.0](https://github.com/Kotlin/sample-wasi-http-kotlin/blob/main/LICENSE).
