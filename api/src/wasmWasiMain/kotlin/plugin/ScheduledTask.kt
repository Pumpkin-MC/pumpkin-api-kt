package plugin

/**
 * Handle for a task scheduled through [PluginTasks].
 *
 * A handle remains safe to use after the task has completed or has already
 * been cancelled.
 */
class ScheduledTask internal constructor(
    private val cancelAction: () -> Unit,
) {
    /**
     * Cancels this task if its action has not started.
     *
     * Calling this method more than once has no effect. If the action has
     * already started or completed, it is not interrupted.
     */
    fun cancel() = cancelAction()
}
