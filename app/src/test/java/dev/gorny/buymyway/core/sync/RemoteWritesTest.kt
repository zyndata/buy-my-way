package dev.gorny.buymyway.core.sync

import dev.gorny.buymyway.core.model.Category
import dev.gorny.buymyway.core.model.ItemContent
import dev.gorny.buymyway.core.model.Op
import dev.gorny.buymyway.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes a change writes to RTDB (STATE.md decision 56). `firebase/test/rules.test.mjs`
 * checks the same shapes against the rules; these tests pin what the app sends.
 */
class RemoteWritesTest {
    private val uid = "uid-a"
    private val list = Merge.blankList("list-1").copy(
        name = "Sobota", ownerUid = uid, categoryOrder = listOf("warzywa", "inne"), createdAt = 100, updatedAt = 100, updatedBy = uid,
    )
    private val item = Merge.blankItem("item-1", "list-1").copy(
        name = "mleko", categoryId = "nabial", createdAt = 200, createdBy = uid, updatedAt = 200, updatedBy = uid,
    )
    private val known = ListState(list = list, items = mapOf(item.id to item))

    @Test
    fun anItemPutWritesTheContentGroupItsStampAndTheServerTime() {
        val op = Op.ItemPut("op", "list-1", uid, 300, "item-1", ItemContent(name = "mleko 2%", quantity = 2.0, unit = "l", categoryId = "nabial"))
        val paths = RemoteWrites.forOp(op, uid, known)
        val base = "lists/list-1/items/item-1"
        assertEquals("mleko 2%", paths["$base/name"])
        assertEquals(2.0, paths["$base/quantity"])
        assertEquals(300L, paths["$base/updatedAt"])
        assertEquals(uid, paths["$base/updatedBy"])
        assertEquals(RemoteWrites.SERVER_TIME, paths["$base/changedAt"])
        // Absent fields are written as null, so an edit that clears the note removes it.
        assertTrue(paths.containsKey("$base/note"))
        assertNull(paths["$base/note"])
        // The tick is not touched.
        assertFalse(paths.keys.any { it.endsWith("/checked") || it.endsWith("/checkedAt") })
    }

    @Test
    fun anEditRepeatsTheCreationTheDeviceKnows() {
        val op = Op.ItemPut("op", "list-1", uid, 300, "item-1", item.content.copy(note = "bez laktozy"))
        val paths = RemoteWrites.forOp(op, uid, known)
        assertEquals(200L, paths["lists/list-1/items/item-1/createdAt"])
        assertEquals(uid, paths["lists/list-1/items/item-1/createdBy"])
    }

    @Test
    fun aNewItemIsCreatedAtItsOwnPut() {
        val op = Op.ItemPut("op", "list-1", uid, 300, "item-9", ItemContent(name = "chleb"))
        val paths = RemoteWrites.forOp(op, uid, known)
        assertEquals(300L, paths["lists/list-1/items/item-9/createdAt"])
    }

    @Test
    fun aTickWritesOnlyTheTickGroup() {
        val paths = RemoteWrites.forOp(Op.ItemCheck("op", "list-1", uid, 400, "item-1", checked = true), uid, known)
        assertEquals(
            mapOf(
                "lists/list-1/items/item-1/checked" to true,
                "lists/list-1/items/item-1/checkedAt" to 400L,
                "lists/list-1/items/item-1/checkedBy" to uid,
                "lists/list-1/items/item-1/changedAt" to RemoteWrites.SERVER_TIME,
            ),
            paths,
        )
    }

    @Test
    fun aListPutKeepsTheOwnerAndTheCreationAndNamesTheListInUserLists() {
        val op = Op.ListPut("op", "list-1", uid, 500, "Niedziela", listOf("inne", "warzywa"))
        val paths = RemoteWrites.forOp(op, uid, known)
        assertEquals("Niedziela", paths["lists/list-1/meta/name"])
        assertEquals(listOf("inne", "warzywa"), paths["lists/list-1/meta/categoryOrder"])
        assertEquals(uid, paths["lists/list-1/meta/ownerUid"])
        assertEquals(100L, paths["lists/list-1/meta/createdAt"])
        assertEquals("owner", paths["userLists/$uid/list-1"])
    }

    @Test
    fun deletingAListRemovesItsItemsCategoriesAndPhotosAndKeepsTheMetaAsTheTombstone() {
        val paths = RemoteWrites.forOp(Op.ListDelete("op", "list-1", uid, 600), uid, known)
        assertEquals(
            mapOf(
                "lists/list-1/meta/deletedAt" to 600L,
                "lists/list-1/items" to null,
                "lists/list-1/categories" to null,
                "photos/list-1" to null,
            ),
            paths,
        )
    }

    @Test
    fun categoryOpsAndClearing() {
        assertEquals(
            mapOf(
                "lists/list-1/categories/c1/name" to "Chemia",
                "lists/list-1/categories/c1/builtin" to false,
                "lists/list-1/categories/c1/updatedAt" to 700L,
                "lists/list-1/categories/c1/updatedBy" to uid,
            ),
            RemoteWrites.forOp(Op.CategoryPut("op", "list-1", uid, 700, "c1", "Chemia", builtin = false), uid, known),
        )
        assertEquals(
            mapOf("lists/list-1/categories/c1/deletedAt" to 800L, "lists/list-1/categories/c1/moveItemsTo" to "inne"),
            RemoteWrites.forOp(Op.CategoryDelete("op", "list-1", uid, 800, "c1", "inne"), uid, known),
        )
        assertEquals(
            mapOf("lists/list-1/meta/clearedAt" to 900L),
            RemoteWrites.forOp(Op.ClearChecked("op", "list-1", uid, 900), uid, known),
        )
    }

    @Test
    fun aWholeListCarriesEveryKnownNodeAndLeavesOutTheOnesWithoutContent() {
        val category = Category("warzywa", "list-1", "Warzywa i owoce", true, 100, uid, null, null)
        val tombstoneOnly = Merge.blankItem("item-x", "list-1").copy(deletedAt = 50)
        val state = ListState(list, mapOf(category.id to category), mapOf(item.id to item, tombstoneOnly.id to tombstoneOnly))
        val paths = RemoteWrites.wholeList(state, uid)
        assertEquals(NodeCodec.listToNode(list), paths["lists/list-1/meta"])
        assertEquals(NodeCodec.categoryToNode(category), paths["lists/list-1/categories/warzywa"])
        assertEquals(NodeCodec.itemToNode(item) + ("changedAt" to RemoteWrites.SERVER_TIME), paths["lists/list-1/items/item-1"])
        assertFalse(paths.containsKey("lists/list-1/items/item-x"))
        assertEquals("owner", paths["userLists/$uid/list-1"])
    }

    @Test
    fun removingAListAfterThirtyDays() {
        assertEquals(
            mapOf("lists/list-1" to null, "photos/list-1" to null, "userLists/$uid/list-1" to null),
            RemoteWrites.removeList("list-1", uid),
        )
    }

    @Test
    fun prefsRoundTrip() {
        val stamped = NodeCodec.Stamped("nabial,warzywa", 42)
        assertEquals(stamped, NodeCodec.stampedFromNode(NodeCodec.stampedToNode(stamped)))
        val memory = NodeCodec.Memory("mleko", "Mleko", "nabial", 43)
        val node = NodeCodec.memoryToNode(memory)
        assertEquals(RemoteWrites.SERVER_TIME, node["changedAt"])
        assertEquals(memory, NodeCodec.memoryFromNode("mleko", node))
        assertNull(NodeCodec.memoryFromNode("x", mapOf("name" to "x")))
        assertEquals(1234L, NodeCodec.changedAt(mapOf("changedAt" to 1234L)))
    }

    // --- Phase 5 -------------------------------------------------------------------------

    @Test
    fun anEditorsRenameDoesNotClaimTheListInUserLists() {
        val op = Op.ListPut("op", "list-1", "uid-b", 300, "Biedronka", list.categoryOrder)
        val paths = RemoteWrites.forOp(op, "uid-b", known)
        assertEquals(uid, paths["lists/list-1/meta/ownerUid"])
        assertEquals("uid-b", paths["lists/list-1/meta/updatedBy"])
        assertFalse(paths.keys.any { it.startsWith("userLists/") })
        // The owner's own rename still names the list in their userLists.
        assertEquals("owner", RemoteWrites.forOp(op.copy(actor = uid), uid, known)["userLists/$uid/list-1"])
    }

    @Test
    fun theManualOrderTravelsWithTheContent() {
        val op = Op.ItemPut("op", "list-1", uid, 300, "item-1", item.content.copy(manualKey = 2.5))
        val paths = RemoteWrites.forOp(op, uid, known)
        assertEquals(2.5, paths["lists/list-1/items/item-1/manualKey"])
        val unplaced = RemoteWrites.forOp(op.copy(content = item.content), uid, known)
        assertTrue(unplaced.containsKey("lists/list-1/items/item-1/manualKey"))
        assertNull(unplaced["lists/list-1/items/item-1/manualKey"])
    }

    @Test
    fun theEmailIndexIsKeyedByTheAddressItself() {
        assertEquals("alice,smith@example,com", RemoteWrites.emailKey(" Alice.Smith@Example.com "))
        assertNull(RemoteWrites.emailKey("no-at-sign"))
        assertNull(RemoteWrites.emailKey("we#ird@example.com"))
        assertNull(RemoteWrites.emailKey("a/b@example.com"))
        val paths = RemoteWrites.profile(uid, "Alice", "alice.smith@example.com", null, 5)
        assertEquals(uid, paths["emailIndex/alice,smith@example,com"])
        assertEquals(5L, paths["users/$uid/updatedAt"])
        assertFalse(RemoteWrites.profile(uid, "Alice", null, null, 5).keys.any { it.startsWith("emailIndex/") })
    }

    @Test
    fun sharingActsWriteTheMemberAndTheUserListsEntryTogether() {
        val invite = RemoteWrites.invite("t".repeat(22), "list-1", Role.VIEWER, uid, "Alice", "Sobota", 999)
        @Suppress("UNCHECKED_CAST")
        val node = invite["invites/${"t".repeat(22)}"] as Map<String, Any?>
        assertEquals(mapOf("listId" to "list-1", "role" to "viewer", "by" to uid, "expiresAt" to 999L, "listName" to "Sobota", "byName" to "Alice"), node)
        assertEquals(mapOf("listId" to "list-1", "expiresAt" to 999L), invite["users/$uid/invites/${"t".repeat(22)}"])

        val accept = RemoteWrites.accept("tok", "list-1", Role.EDITOR, "uid-b")
        assertEquals(mapOf("role" to "editor", "since" to RemoteWrites.SERVER_TIME, "invite" to "tok"), accept["lists/list-1/members/uid-b"])
        assertEquals("editor", accept["userLists/uid-b/list-1"])

        val change = RemoteWrites.setMember("list-1", "uid-b", Role.VIEWER, isNew = false)
        assertEquals(mapOf("lists/list-1/members/uid-b/role" to "viewer", "userLists/uid-b/list-1" to "viewer"), change)

        assertEquals(
            mapOf("lists/list-1/members/uid-b" to null, "userLists/uid-b/list-1" to null, "lists/list-1/presence/uid-b" to null),
            RemoteWrites.removeMember("list-1", "uid-b"),
        )
        assertEquals(
            mapOf("lists/list-1/members" to null, "userLists/uid-b/list-1" to null),
            RemoteWrites.makePrivate("list-1", uid, listOf(uid, "uid-b")),
        )
    }

    @Test
    fun theOwnersCleanupRemovesNodesTheirPhotosAndEveryMembersEntry() {
        assertEquals(
            mapOf(
                "lists/list-1/items/i1" to null,
                "photos/list-1/i1" to null,
                "lists/list-1/categories/c1" to null,
            ),
            RemoteWrites.purge("list-1", listOf("i1"), listOf("c1")),
        )
        assertEquals(
            mapOf("lists/list-1" to null, "photos/list-1" to null, "userLists/$uid/list-1" to null, "userLists/uid-b/list-1" to null),
            RemoteWrites.removeList("list-1", uid, listOf(uid, "uid-b")),
        )
    }

    @Test
    fun aPhotoIsOneNodeWithItsBytesInBase64() {
        val bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, -1, 7)
        val paths = RemoteWrites.putPhoto("list-1", "item-1", NodeCodec.Photo(bytes, 800, 600, uid, 700))
        @Suppress("UNCHECKED_CAST")
        val node = paths.getValue("photos/list-1/item-1") as Map<String, Any?>
        assertEquals(setOf("webp", "w", "h", "by", "at"), node.keys)
        assertEquals(uid, node["by"])
        assertEquals(700L, node["at"])
        val back = NodeCodec.photoFromNode(node)!!
        assertTrue(bytes.contentEquals(back.webp))
        assertEquals(800 to 600, back.width to back.height)
        assertEquals(700L, back.at)
        assertEquals(mapOf("photos/list-1/item-1" to null), RemoteWrites.removePhoto("list-1", "item-1"))
    }

    @Test
    fun aPhotoNodeReadLeniently() {
        // Numbers come back as Long or Double; bad base64 or no bytes is not a photo.
        val node = mapOf("webp" to "AAEC", "w" to 3.0, "h" to 2L, "by" to uid, "at" to 9.0)
        val photo = NodeCodec.photoFromNode(node)!!
        assertEquals(3 to 2, photo.width to photo.height)
        assertEquals(9L, NodeCodec.photoAt(node))
        assertNull(NodeCodec.photoFromNode(node + ("webp" to "not base64!")))
        assertNull(NodeCodec.photoFromNode(node - "webp"))
    }
}
