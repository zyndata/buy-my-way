package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.Category
import dev.gorny.buymyway.core.model.Item
import dev.gorny.buymyway.core.model.Member
import dev.gorny.buymyway.core.model.Role
import dev.gorny.buymyway.core.model.ShoppingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NodeCodecTest {

    private val full = Item(
        id = "item-1", listId = "list-1", name = "Ziemniaki", quantity = 2.5, unit = "kg",
        categoryId = "warzywa", note = "młode", photoAt = 1_700_000_000_500, sortKey = 3.5, manualKey = 7.25,
        checked = true, checkedAt = 1_700_000_000_300, checkedBy = "uid-b",
        createdAt = 1_700_000_000_000, createdBy = "uid-a",
        updatedAt = 1_700_000_000_200, updatedBy = "uid-a", deletedAt = 1_700_000_000_900,
    )

    private val bare = Merge.blankItem("item-2", "list-1").copy(name = "Chleb", updatedAt = 5, createdAt = 5)

    @Test
    fun itemsRoundTrip() {
        for (item in listOf(full, bare)) {
            assertEquals(item, NodeCodec.itemFromNode(item.listId, item.id, NodeCodec.itemToNode(item)))
        }
    }

    @Test
    fun nullFieldsAreLeftOutOfTheNode() {
        val node = NodeCodec.itemToNode(bare)
        for (key in listOf("quantity", "unit", "note", "photoAt", "checkedAt", "checkedBy", "createdBy", "updatedBy", "deletedAt")) {
            assertFalse(key, node.containsKey(key))
        }
    }

    @Test
    fun readingToleratesUnknownFieldsOtherNumberTypesAndMissingFields() {
        val node: Map<String, Any?> = mapOf(
            "name" to "Mleko",
            "quantity" to 2L, // the SDK reads 2.0 back as a Long
            "sortKey" to 1L,
            "updatedAt" to 1_700_000_000_000.0, // and a Long may arrive as a Double
            "checked" to "yes", // wrong type: ignored
            "futureField" to mapOf("a" to 1),
        )
        val item = NodeCodec.itemFromNode("list-1", "item-3", node)
        assertEquals("Mleko", item.name)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals(1.0, item.sortKey, 0.0)
        assertEquals(1_700_000_000_000L, item.updatedAt)
        assertEquals(false, item.checked)
        assertEquals("inne", item.categoryId)
        assertEquals(0L, item.createdAt)
    }

    @Test
    fun listsRoundTripAndReadAnArrayStoredAsAMap() {
        val list = ShoppingList(
            id = "list-1", name = "Sobota", ownerUid = "uid-a", shared = true,
            categoryOrder = listOf("nabial", "warzywa", "inne"), createdAt = 1, updatedAt = 2,
            updatedBy = "uid-a", clearedAt = 3, deletedAt = null,
        )
        assertEquals(list, NodeCodec.listFromNode(list.id, NodeCodec.listToNode(list), shared = true))

        val sparse = mapOf("name" to "X", "categoryOrder" to mapOf("1" to "warzywa", "0" to "nabial", "x" to "?"))
        assertEquals(listOf("nabial", "warzywa"), NodeCodec.listFromNode("l", sparse, false).categoryOrder)
    }

    @Test
    fun categoriesRoundTrip() {
        val live = Category("apteka", "list-1", "Apteka", false, 10, "uid-a", null, null)
        val deleted = live.copy(deletedAt = 20, moveItemsTo = "inne")
        for (category in listOf(live, deleted)) {
            assertEquals(category, NodeCodec.categoryFromNode(category.listId, category.id, NodeCodec.categoryToNode(category)))
        }
    }

    @Test
    fun membersRoundTripAndAnUnknownRoleIsAViewer() {
        val profile = mapOf("name" to "Ania", "email" to "ania@example.com", "photoUrl" to "https://example.com/a.png")
        val member = Member("list-1", "uid-a", Role.EDITOR, 42, "Ania", "ania@example.com", "https://example.com/a.png")
        assertEquals(member, NodeCodec.memberFromNode("list-1", "uid-a", NodeCodec.memberToNode(member), profile))
        assertEquals(mapOf("role" to "editor", "since" to 42L), NodeCodec.memberToNode(member))

        val odd = NodeCodec.memberFromNode("list-1", "uid-b", mapOf("role" to "superuser"))
        assertEquals(Role.VIEWER, odd.role)
    }

    /** „Moje produkty" (Phase 8b, STATE.md decision 88). */
    @Test
    fun ownProductsRoundTripAndCarryTheirTombstone() {
        val live = NodeCodec.OwnProduct("chleb wiejski", "Chleb wiejski", "pieczywo", 42)
        val node = NodeCodec.ownProductToNode(live)
        assertEquals("Chleb wiejski", node["name"])
        assertEquals(RemoteWrites.SERVER_TIME, node[RemoteWrites.CHANGED_AT])
        // A live entry says nothing about deletion; a deleted one keeps its name and says so.
        assertFalse(node.containsKey("deleted"))
        assertEquals(live, NodeCodec.ownProductFromNode(live.key, node))

        val deleted = live.copy(at = 43, deleted = true)
        assertEquals(true, NodeCodec.ownProductToNode(deleted)["deleted"])
        assertEquals(deleted, NodeCodec.ownProductFromNode(deleted.key, NodeCodec.ownProductToNode(deleted)))

        // A node that is not one is rejected rather than invented.
        assertNull(NodeCodec.ownProductFromNode("k", mapOf("name" to "", "categoryId" to "inne", "at" to 1L)))
        assertNull(NodeCodec.ownProductFromNode("k", mapOf("name" to "Ser", "at" to 1L)))
    }
}
