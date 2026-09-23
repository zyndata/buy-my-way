package dev.gorny.buymyway.core.imports

import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.ShoppingList
import dev.gorny.buymyway.core.sync.Merge
import dev.gorny.buymyway.core.text.TextKey

/**
 * What importing a text into a list does to it, decided before anything is written (PLAN.md
 * Phase 8, task 3: „merge rules (sum same name+unit), then one batch of ops").
 *
 * Pure, so „importing the same text twice sums quantities and adds no duplicate" is a JVM test
 * rather than something only a phone can show.
 */
data class ImportPlan(
    /** Lines the list does not hold yet, in the order they were read. */
    val added: List<ImportedItem>,
    /** Lines that meet an item already there; its quantity grows instead. */
    val summed: List<Summed>,
) {
    /** An item whose quantity an import adds to. [revive] when it was sitting in „Kupione". */
    data class Summed(val itemId: String, val quantity: Double?, val revive: Boolean)

    val isEmpty: Boolean get() = added.isEmpty() && summed.isEmpty()

    companion object {
        /**
         * Two lines are the same thing when they name it the same way in the same unit. The name
         * is compared folded, so „Ziemniaki" meets „ziemniaki", and the unit through
         * [EatMyWayImport.unitKey], so „1 ząbek" meets „2 ząbki" but „2 szt." never meets „200 g".
         */
        fun key(name: String, unit: String?): String = TextKey.fold(name) + "\u0000" + EatMyWayImport.unitKey(unit)

        /**
         * [imported] against what [existing] already holds. Lines that name the same thing twice
         * within one text are folded together first, so a text listing „Mleko" under two headings
         * adds one item, not two.
         *
         * A match in „Kupione" is revived rather than added again, which is what adding a bought
         * name by hand does too (STATE.md decision 36).
         */
        fun of(imported: List<ImportedItem>, list: ShoppingList?, existing: Collection<Item>): ImportPlan {
            // An item still to buy is the one an import should grow; „Kupione" is the fallback,
            // and the newest of several namesakes wins. Each item answers to one key, so no two
            // lines of a collapsed import ever meet the same item.
            val byKey = LinkedHashMap<String, Item>()
            existing.filter { Merge.isVisible(it, list) }
                .sortedWith(compareBy({ it.checked }, { -it.updatedAt }))
                .forEach { byKey.getOrPut(key(it.name, it.unit)) { it } }

            val added = mutableListOf<ImportedItem>()
            val summed = mutableListOf<Summed>()
            for (line in collapse(imported)) {
                val match = byKey[key(line.name, line.unit)]
                if (match == null) added += line
                else summed += Summed(match.id, add(match.quantity, line.quantity), match.checked)
            }
            return ImportPlan(added, summed)
        }

        /** [imported] with the lines that name one thing folded into one, in first-seen order. */
        fun collapse(imported: List<ImportedItem>): List<ImportedItem> {
            val byKey = LinkedHashMap<String, ImportedItem>()
            for (line in imported) {
                val k = key(line.name, line.unit)
                val seen = byKey[k]
                byKey[k] = if (seen == null) line else seen.copy(quantity = add(seen.quantity, line.quantity))
            }
            return byKey.values.toList()
        }

        /**
         * Two quantities added. „No quantity" is not zero — it is a line that never said how
         * much — so it is kept where nothing else says otherwise, and never turns a counted
         * line into an uncounted one.
         */
        private fun add(a: Double?, b: Double?): Double? = when {
            a == null -> b
            b == null -> a
            else -> a + b
        }
    }
}
