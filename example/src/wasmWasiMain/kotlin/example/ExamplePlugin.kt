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
        val completedTask = tasks.afterTicks(20uL) {
            Logging.log(Logging.Level.INFO, "One-shot task ran.")
        }

        val cancelledTask = tasks.afterTicks(40uL) {
            Logging.log(Logging.Level.ERROR, "Cancelled task ran unexpectedly.")
        }
        cancelledTask.cancel()
        cancelledTask.cancel()

        tasks.afterTicks(60uL) {
            completedTask.cancel()
            Logging.log(Logging.Level.INFO, "Cancellation after completion succeeded.")
        }

        tasks.afterTicks(1200uL) {
            Logging.log(Logging.Level.INFO, "Long-delay task ran.")
        }

        return Result.success(Unit)
    }

    override fun onUnload(context: Context.Context): Result<Unit> {
        Logging.log(Logging.Level.INFO, "Unload Kotlin plugin!")
        return Result.success(Unit)
    }
}
