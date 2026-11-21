package dev.gorny.buymyway.core.model

/**
 * Dragging an item over the list screen's rows (STATE.md decision 126): within its department,
 * or past a heading into the next one, where it takes that department's category. The rows a
 * drag passes over are each section's heading (none in the flat views) and then its items, keyed
 * as the screen keys them: an item by its id, a heading by [headerKey].
 */
object SectionDrag {
    /** Where a drag put an item: its section's category (null in the flat views) and its new key. */
    data class Landing(val categoryId: String?, val key: Double)

    fun headerKey(categoryId: String): String = "h:$categoryId"

    private data class Row(val key: String, val categoryId: String?, val item: Item?)

    private fun rows(sections: List<CategorySection>): List<Row> = sections.flatMap { section ->
        val id = section.category?.id
        listOfNotNull(id?.let { Row(headerKey(it), it, null) }) + section.items.map { Row(it.id, id, it) }
    }

    /**
     * Whether the item [fromId] may take the place of the row [toKey]: an item still to buy
     * anywhere, or a heading other than the first one, which nothing can go above.
     */
    fun canMove(sections: List<CategorySection>, fromId: String, toKey: String): Boolean {
        val rows = rows(sections)
        val from = rows.indexOfFirst { it.key == fromId }
        val to = rows.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to || rows[from].item == null) return false
        val target = rows[to].item
        return if (target != null) !target.checked else to > 0
    }

    /** The row [delta] places from [itemId] if the item may move there (TalkBack's „Przesuń wyżej / niżej"). */
    fun neighbour(sections: List<CategorySection>, itemId: String, delta: Int): String? {
        val rows = rows(sections)
        val index = rows.indexOfFirst { it.key == itemId }
        if (index < 0) return null
        val key = rows.getOrNull(index + delta)?.key ?: return null
        return key.takeIf { canMove(sections, itemId, it) }
    }

    /** The category of the section [itemId] is shown in, or null in a flat view or when it is not shown. */
    fun sectionOf(sections: List<CategorySection>, itemId: String): String? =
        sections.firstOrNull { section -> section.items.any { it.id == itemId } }?.category?.id

    /**
     * Where [fromId] lands when it takes the place of [toKey]: the section under the nearest
     * heading above it, and a key between its new neighbours there, read with [keyOf] (the
     * `sortKey`, or the `manualKey` in „Ręcznie"). Null when the move is not allowed.
     */
    fun land(sections: List<CategorySection>, fromId: String, toKey: String, keyOf: (Item) -> Double): Landing? {
        if (!canMove(sections, fromId, toKey)) return null
        val rows = rows(sections)
        val moved = Ordering.move(rows, rows.indexOfFirst { it.key == fromId }, rows.indexOfFirst { it.key == toKey })
        val at = moved.indexOfFirst { it.key == fromId }
        val heading = (at downTo 0).firstOrNull { moved[it].item == null } ?: -1
        val next = (at + 1 until moved.size).firstOrNull { moved[it].item == null } ?: moved.size
        val run = moved.subList(heading + 1, next).map { keyOf(it.item!!) }
        return Landing(moved.getOrNull(heading)?.categoryId, Ordering.sortKeyAt(run, at - heading - 1))
    }

    /**
     * [sections] with each item [moved] to another category shown in that category's section,
     * at the end (the caller sorts). A section the drag emptied stays, so the item can be
     * dragged back; a landing whose section is not shown any more leaves the item where it is.
     */
    fun regroup(sections: List<CategorySection>, moved: Map<String, String>): List<CategorySection> {
        if (moved.isEmpty()) return sections
        val shown = sections.mapNotNull { it.category?.id }.toSet()
        val leaving = sections.flatMap { section ->
            section.items.filter { item ->
                val to = moved[item.id]
                to != null && to != section.category?.id && to in shown
            }
        }
        if (leaving.isEmpty()) return sections
        val leavingIds = leaving.map { it.id }.toSet()
        return sections.map { section ->
            val id = section.category?.id
            val staying = section.items.filterNot { it.id in leavingIds }
            section.copy(items = staying + leaving.filter { moved[it.id] == id })
        }
    }
}
