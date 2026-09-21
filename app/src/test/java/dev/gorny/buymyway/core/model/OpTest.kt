package dev.gorny.buymyway.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpTest {

    private val ops = listOf(
        Op.ItemPut("o1", "l", "uid-a", 1, "i", ItemContent("Mleko", 2.0, "l", "nabial", "2%", null, 1.0)),
        Op.ItemCheck("o2", "l", null, 2, "i", true),
        Op.ItemDelete("o3", "l", "uid-a", 3, "i"),
        Op.ListPut("o4", "l", "uid-a", 4, "Sobota", BuiltinCategories.IDS, "uid-a"),
        Op.ListDelete("o5", "l", "uid-a", 5),
        Op.CategoryPut("o6", "l", "uid-a", 6, "c", "Apteka", false),
        Op.CategoryDelete("o7", "l", "uid-a", 7, "c", "inne"),
        Op.ClearChecked("o8", "l", "uid-a", 8),
    )

    @Test
    fun everyOpRoundTripsThroughTheOutboxFormat() {
        for (op in ops) assertEquals(op, Op.decode(Op.encode(op)))
    }

    /** The outbox is stored on the device across app updates: these names must not change. */
    @Test
    fun serialNamesAreStable() {
        val names = ops.map { Regex("\"type\":\"([^\"]+)\"").find(Op.encode(it))!!.groupValues[1] }
        assertEquals(
            listOf(
                "item.put", "item.check", "item.delete", "list.put", "list.delete",
                "category.put", "category.delete", "items.clearChecked",
            ),
            names,
        )
    }

    @Test
    fun decodingIgnoresFieldsFromANewerBuild() {
        val text = Op.encode(ops[1]).replace("}", ",\"extra\":1}")
        assertTrue(Op.decode(text) is Op.ItemCheck)
    }
}
