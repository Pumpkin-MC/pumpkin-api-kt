import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("io.github.pumpkin-mc.plugin") version "0.1.0-dev"
}

repositories {
    val ciRepository = providers.environmentVariable("PUMPKIN_CI_REPOSITORY").orNull
    if (ciRepository != null) {
        maven { url = uri(ciRepository) }
    } else {
        mavenLocal()
    }
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
    apiVersion.set("0.1.0-dev")
    pluginClass.set("example.ExamplePlugin")
}
