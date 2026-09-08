# Example Kotlin plugin

This standalone Gradle project serves as an example to the published Pumpkin API and build plugin. CI builds this same example against a temporary Maven repository.

Before importing it locally, publish the development packages from the repository root:

```sh
./gradlew :api:publishToMavenLocal :gradle-plugin:publishToMavenLocal
```

Then open this directory as a Gradle project in IntelliJ, or build it here:

```sh
./gradlew build --refresh-dependencies
```

Copy `build/pumpkin-example.wasm` into the matching Pumpkin server's `plugins` directory. The plugin logs its initialization, load, and unload callbacks.

`PUMPKIN_CI_REPOSITORY` optionally selects an isolated Maven repository for CI. With no override, the example uses Maven local and the public repositories. It is intentionally a separate build, so it tests package use without substituting the repository's projects.
