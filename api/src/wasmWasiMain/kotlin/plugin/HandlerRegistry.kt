package plugin

/** Stores callbacks under IDs that Pumpkin can pass back to this plugin. */
internal class HandlerRegistry<T> {
    private val handlers = mutableMapOf<UInt, T>()
    // Kotlin helpers use the upper half of the ID space; manual handlers must use the lower half.
    private val firstId = 0x8000_0000u
    private var nextId = firstId

    /** Includes removed handlers, so late callbacks can be recognized and ignored. */
    fun wasAllocated(id: UInt): Boolean =
        id in firstId..<nextId

    /** Allocates a fresh ID. Removed IDs are never reused. */
    fun add(handler: T): UInt {
        check(nextId != UInt.MAX_VALUE) {
            "Handler IDs exhausted"
        }
        val id = nextId++
        handlers[id] = handler
        return id
    }

    operator fun get(id: UInt): T? = handlers[id]

    fun remove(id: UInt): T? = handlers.remove(id)

    /** Releases callbacks without resetting IDs or forgetting which ones were allocated. */
    fun clear() = handlers.clear()
}
