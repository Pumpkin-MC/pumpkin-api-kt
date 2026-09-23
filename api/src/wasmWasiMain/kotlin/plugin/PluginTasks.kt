package plugin

import pumpkin.Scheduler
import pumpkin.Server

/**
 * Schedules Kotlin callbacks through Pumpkin's task scheduler.
 *
 * Callback handler IDs are allocated and managed automatically. Managed
 * handlers use the reserved range `0x8000_0000u..UInt.MAX_VALUE` and are
 * removed when their task completes, is cancelled, or the plugin unloads.
 *
 * Handler IDs allocated by this class are never reused, allowing callbacks
 * arriving after cancellation or completion to be safely ignored.
 */
class PluginTasks internal constructor(
    private val handlers: HandlerRegistry<(Server.Server) -> Unit>,
){
    private val activeTasks = mutableMapOf<UInt, ScheduledTask>()
    private var closed = false;

    /**
     * Schedules [action] to run once after [delayTicks] game ticks.
     *
     * The current [Server.Server] is passed to [action] when the task runs.
     *
     * The returned [ScheduledTask] may be used to cancel the task before its action
     * begins. Once the action has started, cancellation has no effect.
     *
     * The callback is automatically unregistered after execution or cancellation.
     * Pending tasks are also cancelled automatically when the plugin unloads.
     *
     * @param delayTicks number of game ticks to wait before running [action]
     * @param action action to execute once the delay has elapsed
     * @return a handle that may be used to cancel the pending task
     */
    fun afterTicks(delayTicks: ULong, action: (Server.Server) -> Unit): ScheduledTask {
        check(!closed) { "!!! Cannot schedule tasks after plugin unload"}
        var handlerId = 0u
        var finished = false

        // Register before calling the host so an immediate callback can find the handler.
        handlerId = handlers.add { server ->
            if (!finished) {
                // Retire the handler before user code runs, even if that code throws or reenters.
                finished = true
                handlers.remove(handlerId)
                activeTasks.remove(handlerId)
                action(server)
            }
        }

        // Pumpkin's task ID is for cancellation; our handler ID is for callback dispatch.
        val taskId = try {
            Scheduler.scheduleDelayedTask(handlerId, delayTicks)
        } catch (failure: Throwable) {
            // Don't keep a callback for a task that failed to schedule.
            finished = true
            handlers.remove(handlerId)
            throw failure
        }

        val task = ScheduledTask {
            if (!finished) {
                // Mark it finished before calling the host, which may reenter the plugin.
                finished = true
                activeTasks.remove(handlerId)
                try {
                    Scheduler.cancelTask(taskId)
                } finally {
                    handlers.remove(handlerId)
                }
            }
        }

        // The action may have already run during the scheduling call.
        if (!finished) {
            if (closed) {
                task.cancel()
            } else {
                activeTasks[handlerId] = task
            }
        }

        return task
    }

    internal fun close(): Result<Unit> {
        if (closed) {return Result.success(Unit)}
        closed = true

        val remaining = activeTasks.values.toList()

        activeTasks.clear()

        handlers.clear()

        var failure: Throwable? = null

        for (task in remaining) {
            try {
                task.cancel()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) {
                    failure = error
                } else if (previous !== error) {
                    previous.addSuppressed(error)
                }
            }
        }

        return failure?.let { Result.failure(it) }?: Result.success(Unit)
    }
}
