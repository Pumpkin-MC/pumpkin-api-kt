package plugin

/** A cancellation handle returned by [PluginTasks]. */
class ScheduledTask internal constructor(
    private val cancelAction: () -> Unit,
) {
    /**
     * Cancels a pending action. Does nothing if it has already started or been cancelled.
     * An action that is already running is not interrupted.
     */
    fun cancel() = cancelAction()
}
