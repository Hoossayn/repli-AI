package dev.patrickgold.florisboard.ime.keyboard3

/** Expected editor acknowledgements. An obsolete callback must not consume newer pending edits. */
internal class ExpectedContentQueue<T> {
    private val list = mutableListOf<T>()

    fun popUntilOrNull(predicate: (T) -> Boolean): T? {
        val index = list.indexOfFirst(predicate)
        if (index < 0) return null
        val item = list[index]
        repeat(index + 1) { list.removeAt(0) }
        return item
    }

    fun push(item: T) { list.add(item) }
    fun clear() { list.clear() }
}
