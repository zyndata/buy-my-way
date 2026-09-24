package dev.gorny.buymyway.core.push

import dev.gorny.buymyway.core.model.Op
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a push says, and what it never says (PLAN.md *Push sender*, STATE.md decisions 93 and 95).
 *
 * A message carries a list id, three numbers and the name of whoever made the changes. No item
 * name, note or photo ever travels through FCM: the receiving phone reads what changed from
 * RTDB itself, under the rules, as it does at any other catch-up. The names in [Tally.bought]
 * come from that read and never from the message (decision 106).
 *
 * Everything here is pure, so the JVM tests own the counting, the wording's shape and the
 * tally a phone keeps between two pushes.
 */
object PushSignal {
    /** Which channel a message belongs to, and the only two values `kind` may take. */
    const val KIND_CHANGES = "changes"
    const val KIND_SHARED = "shared"

    // The keys of both the POST body and the FCM data payload.
    const val LIST_ID = "listId"
    const val KIND = "kind"
    const val ACTOR = "actor"
    const val ADDED = "added"
    const val CHECKED = "checked"
    const val CHANGED = "changed"
    const val COUNT = "count"

    /** What one change was, as far as a notification is concerned. */
    enum class Kind { ADDED, CHECKED, CHANGED }

    @Serializable
    data class Counts(val added: Int = 0, val checked: Int = 0, val changed: Int = 0) {
        val total: Int get() = added + checked + changed

        val isEmpty: Boolean get() = total == 0

        operator fun plus(other: Counts) =
            Counts(added + other.added, checked + other.checked, changed + other.changed)

        operator fun plus(kind: Kind) = this + of(kind)
    }

    fun of(kind: Kind): Counts = when (kind) {
        Kind.ADDED -> Counts(added = 1)
        Kind.CHECKED -> Counts(checked = 1)
        Kind.CHANGED -> Counts(changed = 1)
    }

    /**
     * What an op that has just been acknowledged is worth saying. [createdNow] is true when this
     * `item.put` is the one that created the item, which is what tells „dodała 3" from „zmieniła
     * 3"; the sync layer knows it, because it holds the item's `createdAt`.
     */
    fun kindOf(op: Op, createdNow: Boolean): Kind = when (op) {
        is Op.ItemPut -> if (createdNow) Kind.ADDED else Kind.CHANGED
        is Op.ItemCheck -> if (op.checked) Kind.CHECKED else Kind.CHANGED
        else -> Kind.CHANGED
    }

    /** One line of a notification: „+3", „✓ 2", „3 zmiany". Zero counts are left out. */
    data class Part(val kind: Kind, val count: Int)

    /** The parts in the order they are read, always added → checked → anything else. */
    fun parts(counts: Counts): List<Part> = buildList {
        if (counts.added > 0) add(Part(Kind.ADDED, counts.added))
        if (counts.checked > 0) add(Part(Kind.CHECKED, counts.checked))
        if (counts.changed > 0) add(Part(Kind.CHANGED, counts.changed))
    }

    /**
     * What one phone remembers about one list between two pushes (decision 95), so a second
     * message five minutes later reads „+5, ✓ 3" and not „+2, ✓ 1". [manyActors] is true once a
     * second person has contributed to the same tally, and then no name is shown.
     */
    @Serializable
    data class Tally(
        val counts: Counts = Counts(),
        val actor: String? = null,
        val manyActors: Boolean = false,
        /**
         * What was bought since this tally started, by name, oldest first. Empty until the
         * catch-up has read the list, because a push never carries a name (decision 106). A
         * tally stored before this field existed simply decodes with none.
         */
        val bought: List<String> = emptyList(),
    ) {
        /** Adds one message to the tally. */
        fun plus(more: Counts, by: String?): Tally = copy(
            counts = counts + more,
            actor = by ?: actor,
            manyActors = manyActors || (actor != null && by != null && by != actor),
        )

        /**
         * Adds what the catch-up has just read. A name already in the tally stays where it was,
         * and only the most recent [MAX_BOUGHT] are kept: the expanded notification shows fewer
         * than that anyway, and the tally goes into DataStore.
         */
        fun plusBought(names: List<String>): Tally =
            copy(bought = (bought + names).distinct().takeLast(MAX_BOUGHT))

        /** The name to show, or null when nobody or several people are behind the numbers. */
        val singleActor: String? get() = if (manyActors) null else actor
    }

    /** How many bought names one list's tally keeps. */
    const val MAX_BOUGHT = 20

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeTally(tally: Tally): String = json.encodeToString(Tally.serializer(), tally)

    /** A tally that could not be read is no tally: a corrupt value never breaks a notification. */
    fun decodeTally(stored: String?): Tally = stored?.let {
        runCatching { json.decodeFromString(Tally.serializer(), it) }.getOrNull()
    } ?: Tally()

    /** The number in an FCM data payload, as the sender wrote it: strings, and never negative. */
    fun countOf(payload: Map<String, String>, key: String): Int =
        payload[key]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun countsOf(payload: Map<String, String>): Counts = Counts(
        added = countOf(payload, ADDED),
        checked = countOf(payload, CHECKED),
        changed = countOf(payload, CHANGED),
    )
}
