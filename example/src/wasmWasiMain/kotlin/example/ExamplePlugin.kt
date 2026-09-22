package example

import plugin.PluginMetadata
import plugin.PumpkinPlugin

import pumpkin.Context
import pumpkin.Logging

class ExamplePlugin : PumpkinPlugin() {
    override fun metadata() = PluginMetadata(
        name = "Example Kotlin Plugin",
        version = "0.1.0",
        authors = listOf("You"),
        description = "An example plugin written in Kotlin",
        dependencies = listOf(),
        permissions = listOf(),
    )

    override fun initPlugin() {
        Logging.log(Logging.Level.INFO, "Init Kotlin plugin!")
    }

    override fun onLoad(context: Context.Context): Result<Unit> {
        Logging.log(Logging.Level.INFO, "Load Kotlin plugin!")
        return Result.success(Unit)
    }

    override fun onUnload(context: Context.Context): Result<Unit> {
        Logging.log(Logging.Level.INFO, "Unload Kotlin plugin!")
        return Result.success(Unit)
    }
}
