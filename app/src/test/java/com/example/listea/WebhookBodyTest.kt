package com.example.listea

import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import org.json.JSONArray
import com.example.listea.domain.AssetKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The webhook *body*, which `WebhookPayloadTest` used to leave to the device.
 *
 * It could: assembling the body needs `org.json`, and host tests had no real implementation of it.
 * Robolectric — added in v0.2 step 0 — has one, so the thing v0.2 most needs pinned can be pinned:
 * §8 of the plan promises the wire format is unchanged through a rewrite of every read path behind
 * it, and that promise is only worth anything if the exact bytes are asserted somewhere.
 *
 * Hand-written expectations rather than a snapshot file, so a change here has to be one somebody
 * wrote down on purpose. Field *order* is part of that: it comes from the insertion order in the
 * builder, and a receiver diffing bodies would see it move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebhookBodyTest {

    @Test
    fun `a payload is exactly this, field for field`() {
        val payload = buildWebhookPayload(
            event = EVENT_LIST_COMPLETED,
            list = list(folder = "2026-07-11"),
            items = listOf(item(completed = true, favourite = true, actions = "custom1")),
            settings = AppSettings()
        )

        assertEquals(
            """{"event":"list.completed",""" +
                """"list":{"id":7,"title":"Trip","completedAt":null},""" +
                """"items":[{"id":"PUBLIC-1","title":"a.jpg","isCompleted":true,""" +
                """"relativePath":"2026-07-11","actions":["favorite","cust1"]}]}""",
            payload
        )
    }

    @Test
    fun `a solidus in a path is escaped on the wire`() {
        val list = list(folder = "2026-07-11/bilibili")

        val payload = buildWebhookPayload(EVENT_LIST_COMPLETED, list, listOf(item()), AppSettings())

        // Android's org.json escapes "/" as "\/". It has always shipped that way, so receivers
        // already accept it, and "tidying" the escaping would be a wire change however legal.
        assertTrue(payload.contains("\"relativePath\":\"2026-07-11\\/bilibili\""))
        assertFalse(payload.contains("\"relativePath\":\"2026-07-11/bilibili\""))
        assertEquals("2026-07-11/bilibili", webhookRelativePath(list, item()))
    }

    @Test
    fun `the list's completion time is null until it has one, then a number`() {
        val bodyOf = { completedAt: Long? ->
            JSONObject(
                buildWebhookPayload(
                    EVENT_LIST_COMPLETED,
                    list().copy(completedAt = completedAt),
                    emptyList(),
                    AppSettings()
                )
            ).getJSONObject("list")
        }

        // The key is present either way, so a receiver can tell "not complete" from "no such field".
        assertTrue(bodyOf(null).has("completedAt"))
        assertEquals(JSONObject.NULL, bodyOf(null).get("completedAt"))
        assertEquals(1234L, bodyOf(1234).getLong("completedAt"))
    }

    @Test
    fun `an item goes out under its public id, and the row id is not on the wire at all`() {
        val json = firstItem(buildWebhookPayload(EVENT_LIST_COMPLETED, list(), listOf(item()), AppSettings()))

        assertEquals("PUBLIC-1", json.getString("id"))
        assertFalse(json.has("listId"))
        assertFalse(json.has("rowId"))
    }

    @Test
    fun `a folder-registered file sends the public id off its own state row`() {
        // A folder round has no list rows to borrow an id from, which is why file_review_state
        // carries its own; this is the field that tells a receiver which space an id came from.
        val folderItem = ListItemEntity(
            publicId = "FILE-1",
            listId = 0,
            taskId = "task-file-1",
            title = "a.jpg",
            sortOrder = 0,
            createdAt = 1000
        )

        val json = firstItem(
            buildWebhookPayload(EVENT_QUICK_REVIEW_COMPLETED, list(), listOf(folderItem), AppSettings())
        )

        assertEquals("FILE-1", json.getString("id"))
    }

    @Test
    fun `relativePath is null, not absent, for a manual item`() {
        val manual = ListItemEntity(
            publicId = "MANUAL-1",
            listId = 7,
            taskId = "task-manual-1",
            title = "Buy milk",
            sortOrder = 0,
            createdAt = 1000
        )

        val json = firstItem(buildWebhookPayload(EVENT_LIST_COMPLETED, list(), listOf(manual), AppSettings()))

        // A fake path would be worse than none for a downstream tool, but the key still has to be
        // there so a receiver can distinguish "no file" from "this payload never says".
        assertTrue(json.has("relativePath"))
        assertEquals(JSONObject.NULL, json.get("relativePath"))
    }

    @Test
    fun `actions come from settings as they stand at delivery time`() {
        val renamed = AppSettings(
            assetControlTags = mapOf(
                AssetKind.IMAGE to listOf(DefaultCustomActions[0].copy(webhookValue = "keep"))
            )
        )
        // The row still holds the id it was marked with; only the wire value moved.
        val json = firstItem(
            buildWebhookPayload(
                EVENT_LIST_COMPLETED,
                list(),
                listOf(item(actions = "custom1")),
                renamed
            )
        )

        assertEquals(listOf("keep"), json.getJSONArray("actions").strings())
    }

    @Test
    fun `actions are emitted in settings order, not the order they were tapped`() {
        val json = firstItem(
            buildWebhookPayload(
                EVENT_LIST_COMPLETED,
                list(),
                listOf(item(favourite = true, actions = "custom2,custom1")),
                AppSettings()
            )
        )

        assertEquals(listOf("favorite", "cust1", "cust2"), json.getJSONArray("actions").strings())
    }

    @Test
    fun `an item carrying nothing sends an empty action array`() {
        val json = firstItem(buildWebhookPayload(EVENT_LIST_COMPLETED, list(), listOf(item()), AppSettings()))

        assertEquals(0, json.getJSONArray("actions").length())
    }

    @Test
    fun `checking an item is not an action`() {
        // Completion and actions are independent on the wire; neither implies the other.
        val json = firstItem(
            buildWebhookPayload(EVENT_LIST_COMPLETED, list(), listOf(item(completed = true)), AppSettings())
        )

        assertEquals(true, json.getBoolean("isCompleted"))
        assertEquals(0, json.getJSONArray("actions").length())
    }

    @Test
    fun `an event whose items are all unchecked sends an empty array, not a missing one`() {
        val kept = webhookItems(
            listOf(item(completed = false)),
            AppSettings(webhookCompletedItemsOnly = true)
        )

        val body = JSONObject(buildWebhookPayload(EVENT_LIST_COMPLETED, list(), kept, AppSettings()))

        // A receiver being told "nothing was checked" is not the same as being told nothing.
        assertTrue(body.has("items"))
        assertEquals(0, body.getJSONArray("items").length())
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun list(folder: String = "") = ListEntity(
        id = 7,
        title = "Trip",
        createdAt = 1000,
        sourceRootUri = "content://root",
        sourceRelativePath = folder
    )

    private fun firstItem(payload: String): JSONObject =
        JSONObject(payload).getJSONArray("items").getJSONObject(0)

    private fun item(
        completed: Boolean = false,
        favourite: Boolean = false,
        actions: String = ""
    ) = ListItemEntity(
        id = 99,
        publicId = "PUBLIC-1",
        listId = 7,
        taskId = "task-99",
        title = "a.jpg",
        isCompleted = completed,
        sortOrder = 0,
        createdAt = 1000,
        sourceUri = "content://root/a.jpg",
        sourceRelativePath = "a.jpg",
        rootRelativePath = "a.jpg",
        isFavorite = favourite,
        customActions = actions
    )
}
