package com.example.listea

import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadMarkRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.data.decodeActionIds
import com.example.listea.data.encodeActionIds
import org.json.JSONArray
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_COMMENT_VERSION
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
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
 * Since v0.6.0 the body is the hierarchy **event → list → task → asset**. The assembly itself
 * (`exportTaskViews`, reading rows the way delivery reads them) is exercised here rather than a
 * hand-built [ExportTaskView], so these tests pin the contract the database actually produces.
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
        val payload = payload(
            rows = listOf(item(completed = true, favourite = true, actions = "custom1")),
            assets = listOf(assetRow(favourite = true, actions = "custom1"))
        )

        assertEquals(
            """{"version":2,"event":"list.completed",""" +
                """"list":{"id":7,"title":"Trip","completedAt":null},""" +
                """"tasks":[{"id":"TASK-PUBLIC-1","title":"a.jpg","isCompleted":true,""" +
                """"templates":[{"id":"test.control","name":"Test control","version":1}],""" +
                """"assets":[{"id":"ASSET-1","title":"a.jpg","relativePath":"2026-07-11",""" +
                """"sourceMissing":false,"actions":["favorite","cust1"]}]}]}""",
            buildWebhookPayload(payload)
        )
    }

    @Test
    fun `a solidus in a folder is escaped on the wire`() {
        // Android's org.json escapes "/" as "\/". It has always shipped that way, so receivers
        // already accept it, and "tidying" the escaping would be a wire change however legal.
        val payload = payload(assets = listOf(assetRow(path = "2026-07-11/bilibili/a.jpg")))

        val body = buildWebhookPayload(payload)

        assertTrue(body.contains("\"relativePath\":\"2026-07-11\\/bilibili\""))
        assertFalse(body.contains("\"relativePath\":\"2026-07-11/bilibili\""))
    }

    @Test
    fun `the list's completion time is null until it has one, then a number`() {
        val bodyOf = { completedAt: Long? ->
            JSONObject(buildWebhookPayload(payload(list = list().copy(completedAt = completedAt))))
                .getJSONObject("list")
        }

        // The key is present either way, so a receiver can tell "not complete" from "no such field".
        assertTrue(bodyOf(null).has("completedAt"))
        assertEquals(JSONObject.NULL, bodyOf(null).get("completedAt"))
        assertEquals(1234L, bodyOf(1234).getLong("completedAt"))
    }

    @Test
    fun `a task goes out under its public id, and the row id is not on the wire at all`() {
        val json = firstTask(buildWebhookPayload(payload()))

        assertEquals("TASK-PUBLIC-1", json.getString("id"))
        assertFalse(json.has("listId"))
        assertFalse(json.has("rowId"))
        assertFalse(json.has("taskId"))
    }

    @Test
    fun `an asset goes out under its own public id, never the list row's`() {
        // A List row's id names the *membership*, and since v0.6.0 an Asset has a name of its own.
        // A folder round never had a row id to borrow; this is what makes both paths spell it the
        // same way.
        val json = firstAsset(buildWebhookPayload(payload(rows = listOf(item(publicId = "ROW-1")))))

        assertEquals("ASSET-1", json.getString("id"))
        assertFalse(json.has("assetId"))
    }

    @Test
    fun `a manual task sends empty templates and empty assets, not invented ones`() {
        // A manual item has no source file and no import. Two empty arrays are the honest shape:
        // a receiver can tell "checked, nothing attached" from "the payload never says".
        val manual = ListItemEntity(
            publicId = "MANUAL-1",
            listId = 7,
            taskId = "task-manual-1",
            title = "Buy milk",
            sortOrder = 0,
            createdAt = 1000
        )

        val payload = payload(
            rows = listOf(manual),
            assets = emptyList(),
            attachment = null
        )
        val json = firstTask(buildWebhookPayload(payload))

        assertEquals("Buy milk", json.getString("title"))
        assertTrue(json.has("templates"))
        assertEquals(0, json.getJSONArray("templates").length())
        assertTrue(json.has("assets"))
        assertEquals(0, json.getJSONArray("assets").length())
    }

    @Test
    fun `an asset with no path sends null, not a made-up folder`() {
        // A fake path would be worse than none for a downstream tool, but the key still has to be
        // there so a receiver can distinguish "no file" from "this payload never says".
        val json = firstAsset(buildWebhookPayload(payload(assets = listOf(assetRow(path = null)))))

        assertTrue(json.has("relativePath"))
        assertEquals(JSONObject.NULL, json.get("relativePath"))
        assertEquals("", json.getString("title"))
    }

    @Test
    fun `actions resolve to the wire values the template carries at delivery time`() {
        // The Asset still holds the id it was marked with; only the wire value moved.
        val json = firstAsset(
            buildWebhookPayload(
                payload(
                    assets = listOf(assetRow(actions = "custom1")),
                    resolver = resolver(custom1Value = "keep")
                )
            )
        )

        assertEquals(listOf("keep"), json.getJSONArray("actions").strings())
    }

    @Test
    fun `actions are emitted in the template's button order, not the order they were tapped`() {
        // The order is the version's own list — the order the chip row draws — and not the order
        // the ids happen to sit in on the Asset, which a user decided by tapping.
        val json = firstAsset(
            buildWebhookPayload(
                payload(assets = listOf(assetRow(favourite = true, actions = "custom2,custom1")))
            )
        )

        assertEquals(listOf("favorite", "cust1", "cust2"), json.getJSONArray("actions").strings())
    }

    @Test
    fun `an asset carrying nothing sends an empty action array`() {
        // The Task's control offers three buttons and the Asset is marked with none of them, so
        // the empty array is the answer about the Asset rather than about the control.
        val json = firstAsset(buildWebhookPayload(payload(assets = listOf(assetRow()))))

        assertEquals(0, json.getJSONArray("actions").length())
    }

    @Test
    fun `checking a task is not an action`() {
        // Completion and actions are independent on the wire; neither implies the other.
        val json = firstTask(buildWebhookPayload(payload(rows = listOf(item(completed = true)))))

        assertEquals(true, json.getBoolean("isCompleted"))
        assertEquals(0, firstAsset(buildWebhookPayload(payload())).getJSONArray("actions").length())
    }

    @Test
    fun `an event whose tasks are all unchecked sends an empty array, not a missing one`() {
        val kept = webhookItems(
            listOf(item(completed = false)),
            AppSettings(webhookCompletedItemsOnly = true)
        )

        val body = JSONObject(buildWebhookPayload(payload(rows = kept)))

        // A receiver being told "nothing was checked" is not the same as being told nothing.
        assertTrue(body.has("tasks"))
        assertEquals(0, body.getJSONArray("tasks").length())
    }

    @Test
    fun `a template this build cannot read is still named, with a null name`() {
        // The reference is the Task's own fact — id and attached version — and dropping it because
        // the controls are unreadable would lose the one thing a receiver can still join on.
        val json = firstTask(
            buildWebhookPayload(
                payload(attachment = encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(LINEAGE), 9))))
            )
        )

        val template = json.getJSONArray("templates").getJSONObject(0)
        assertEquals(LINEAGE, template.getString("id"))
        assertEquals(9, template.getInt("version"))
        assertTrue(template.has("name"))
        assertEquals(JSONObject.NULL, template.get("name"))
    }

    @Test
    fun `a shared file is two Tasks with two Asset ids`() {
        // The same file under two Todos is the content of two Tasks. Two Asset ids, one per Task,
        // is what lets a receiver tell the judgments apart instead of merging them into one file.
        val rows = listOf(item(taskId = "task-a", publicId = "ROW-A"), item(taskId = "task-b", publicId = "ROW-B"))
        val assets = listOf(
            assetRow(taskId = "task-a", publicId = "ASSET-A"),
            assetRow(taskId = "task-b", publicId = "ASSET-B")
        )

        val body = JSONObject(
            buildWebhookPayload(
                payload(rows = rows, assets = assets)
            )
        )

        assertEquals(2, body.getJSONArray("tasks").length())
        assertEquals("ASSET-A", body.getJSONArray("tasks").getJSONObject(0).getJSONArray("assets").getJSONObject(0).getString("id"))
        assertEquals("ASSET-B", body.getJSONArray("tasks").getJSONObject(1).getJSONArray("assets").getJSONObject(0).getString("id"))
    }

    @Test
    fun `a body carrying a mark says version 3 and pins the mark's fields`() {
        // The version is a claim about *this* body. The quote's page and text are the stored anchor
        // the viewer verified, the tool id is what lets a receiver group by the reviewer's tool, and
        // a comment's value is the reviewer's own text.
        val body = buildWebhookPayload(
            payload(
                assets = listOf(assetRow(path = "papers/a.pdf")),
                resolver = pdfResolver(),
                attachment = PDF_ATTACHMENT,
                marks = mapOf(
                    ASSET_ID to listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment", value = "I think so"))
                ),
                fingerprints = mapOf(ASSET_ID to FINGERPRINT)
            )
        )

        assertTrue(body.startsWith("""{"version":3,"event":"list.completed","""))
        assertTrue(
            body.contains(
                """"actions":[],"marks":[{"id":"m-1","kind":"comment","toolId":"comment-1",""" +
                    """"quote":{"page":2,"text":"the passage"},"value":"I think so"}]}"""
            )
        )
    }

    @Test
    fun `a tag sends the current tool value, not the one stored with the mark`() {
        val json = firstAsset(
            buildWebhookPayload(
                payload(
                    assets = listOf(assetRow(path = "papers/a.pdf")),
                    resolver = pdfResolver(tagValue = "funny_sentence"),
                    attachment = PDF_ATTACHMENT,
                    marks = mapOf(ASSET_ID to listOf(mark(id = "m-1", toolId = "tag-1", kind = "tag", value = "old"))),
                    fingerprints = mapOf(ASSET_ID to FINGERPRINT)
                )
            )
        )

        assertEquals("funny_sentence", json.getJSONArray("marks").getJSONObject(0).getString("value"))
    }

    @Test
    fun `an asset without marks gains no marks key while its sibling carries one`() {
        // The key is present only where the array is. A mark-free image beside a marked PDF must
        // still serialise exactly as v2 did; only the body's version line moves.
        val json = firstTask(
            buildWebhookPayload(
                payload(
                    assets = listOf(
                        assetRow(publicId = "ASSET-PDF", path = "papers/a.pdf"),
                        assetRow(publicId = "ASSET-IMG", path = "papers/a.jpg")
                    ),
                    resolver = pdfResolver(),
                    attachment = PDF_ATTACHMENT,
                    marks = mapOf(
                        ASSET_ID to listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment"))
                    ),
                    fingerprints = mapOf(ASSET_ID to FINGERPRINT)
                )
            )
        )
        val assets = json.getJSONArray("assets")

        assertTrue(assets.getJSONObject(0).has("marks"))
        assertFalse(assets.getJSONObject(1).has("marks"))
    }

    @Test
    fun `a mark whose document changed leaves the body at version 2`() {
        // Omission is the honest answer: the old quote may no longer be in this file. With every
        // mark omitted there is nothing new on the wire, so the body makes no claim it cannot keep.
        val body = buildWebhookPayload(
            payload(
                assets = listOf(assetRow(path = "papers/a.pdf")),
                resolver = pdfResolver(),
                attachment = PDF_ATTACHMENT,
                marks = mapOf(
                    ASSET_ID to listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment", fingerprint = "old"))
                ),
                fingerprints = mapOf(ASSET_ID to FINGERPRINT)
            )
        )

        assertTrue(body.startsWith("""{"version":2,"""))
        assertFalse(firstAsset(body).has("marks"))
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun list() = ListEntity(
        id = 7,
        title = "Trip",
        createdAt = 1000
    )

    private fun firstTask(payload: String): JSONObject =
        JSONObject(payload).getJSONArray("tasks").getJSONObject(0)

    private fun firstAsset(payload: String): JSONObject =
        firstTask(payload).getJSONArray("assets").getJSONObject(0)

    /**
     * One row in a List, as delivery reads it. Its `publicId` is deliberately a row id: the body
     * must name the Task by the Task's own id instead.
     */
    private fun item(
        taskId: String = TASK_ID,
        publicId: String = "ROW-PUBLIC-1",
        completed: Boolean = false,
        favourite: Boolean = false,
        actions: String = ""
    ) = ListItemEntity(
        id = 99,
        publicId = publicId,
        listId = 7,
        taskId = taskId,
        title = "a.jpg",
        isCompleted = completed,
        sortOrder = 0,
        createdAt = 1000,
        sourceUri = "content://root/a.jpg",
        sourceRelativePath = "a.jpg",
        rootRelativePath = "a.jpg",
        selections = encodeActionIds(
            decodeActionIds(actions) +
                if (favourite) setOf(Templates.FAVOURITE_BUTTON_ID) else emptySet()
        )
    )

    /** One Asset row, as `getPayloadAssets` returns it. */
    private fun assetRow(
        taskId: String = TASK_ID,
        publicId: String = "ASSET-1",
        path: String? = "2026-07-11/a.jpg",
        sourceMissing: Boolean = false,
        favourite: Boolean = false,
        actions: String = ""
    ) = PayloadAssetRow(
        taskId = taskId,
        assetId = "asset-$taskId",
        publicId = publicId,
        relativePath = path,
        sourceMissing = sourceMissing,
        sortOrder = 0,
        selections = encodeActionIds(
            decodeActionIds(actions) +
                if (favourite) setOf(Templates.FAVOURITE_BUTTON_ID) else emptySet()
        )
    )

    /**
     * A whole payload, assembled the way delivery assembles it: rows in, Task and Asset facts from
     * the projections, one resolver, and [exportTaskViews] deciding what the wire sees.
     */
    private fun payload(
        event: String = EVENT_LIST_COMPLETED,
        list: ListEntity = list(),
        rows: List<ListItemEntity> = listOf(item()),
        assets: List<PayloadAssetRow> = listOf(assetRow()),
        attachment: String? = ATTACHMENT,
        resolver: TemplateResolver = resolver(),
        marks: Map<String, List<PayloadMarkRow>> = emptyMap(),
        fingerprints: Map<String, String> = emptyMap()
    ): ExportPayload {
        val tasks = rows.map { it.taskId }.distinct()
            .associateWith { PayloadTaskRow(it, TASK_PUBLIC_ID, null, attachment) }
        return ExportPayload(
            event = event,
            listId = list.id,
            listTitle = list.title,
            completedAt = list.completedAt,
            sourceId = null,
            sourceTitle = null,
            at = 1000,
            tasks = exportTaskViews(
                rows, tasks, assets.groupBy { it.taskId }, resolver,
                marksByAsset = marks,
                fingerprintByAsset = fingerprints
            )
        )
    }

    /** One stored passage, as `getPayloadMarks` returns it. */
    private fun mark(
        id: String,
        toolId: String,
        kind: String,
        value: String = "value",
        fingerprint: String = FINGERPRINT,
        createdAt: Long = 0
    ) = PayloadMarkRow(
        id = id,
        assetId = ASSET_ID,
        toolId = toolId,
        kind = kind,
        value = value,
        pageIndex = 2,
        exactText = "the passage",
        documentFingerprint = fingerprint,
        createdAt = createdAt
    )

    /**
     * A PDF control offering the two passage tools. Separate from [resolver] because a PDF's
     * control is what marks resolve against, and its scope is what makes it govern a PDF at all.
     */
    private fun pdfResolver(tagValue: String = "funny_sentence"): TemplateResolver = TemplateResolver.of(
        listOf(TemplateEntity(id = PDF_LINEAGE, name = "PDF review", createdAt = 0)),
        listOf(
            TemplateVersionEntity(
                templateId = PDF_LINEAGE,
                version = 1,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.PDF))),
                buttons = encodeTemplateButtons(
                    listOf(
                        TemplateButton("comment-1", "Comment", ButtonType.COMMENT, ""),
                        TemplateButton("tag-1", "Funny", ButtonType.TAG, tagValue)
                    )
                ),
                buttonsVersion = TEMPLATE_BUTTONS_COMMENT_VERSION,
                createdAt = 0
            )
        )
    )

    /**
     * The template version the actions in these tests resolve against: `★` and the two
     * out-of-the-box tags, with whatever wire value a test is about.
     *
     * A template rather than a settings page since v0.5.4. What a button is called and what it
     * sends belong to the version an id came from, so a wire value is changed by resolving against
     * a different version rather than by editing the app's settings.
     */
    private fun resolver(custom1Value: String = "cust1"): TemplateResolver = TemplateResolver.of(
        listOf(TemplateEntity(id = LINEAGE, name = "Test control", createdAt = 0)),
        listOf(
            TemplateVersionEntity(
                templateId = LINEAGE,
                version = 1,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.IMAGE))),
                buttons = encodeTemplateButtons(
                    listOf(
                        TemplateButton(
                            id = Templates.FAVOURITE_BUTTON_ID,
                            name = "★",
                            type = ButtonType.BOOLEAN,
                            recordValue = Templates.FAVOURITE_BUTTON_ID
                        ),
                        TemplateButton("custom1", "C1", ButtonType.TAG, custom1Value),
                        TemplateButton("custom2", "C2", ButtonType.TAG, "cust2")
                    )
                ),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = 0
            )
        )
    )

    private companion object {
        /** The Task every fixture item belongs to, so the payload's lookup can name it. */
        const val TASK_ID = "task-99"

        /** What that Task's own row says its public id is. */
        const val TASK_PUBLIC_ID = "TASK-PUBLIC-1"

        /** The one lineage these tests resolve against. */
        const val LINEAGE = "test.control"

        /** A Task attached to it at version 1, as `task_imports.assetControls` holds it. */
        val ATTACHMENT =
            encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(LINEAGE), 1)))

        /** The Task's one Asset, as `PayloadAssetRow.assetId` spells it. */
        const val ASSET_ID = "asset-task-99"

        /** The hash the viewer would compute for the document these anchors were verified on. */
        const val FINGERPRINT = "fp-current"

        /** The PDF control's lineage, and the attachment that names its version. */
        const val PDF_LINEAGE = "test.pdf"

        val PDF_ATTACHMENT =
            encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(PDF_LINEAGE), 1)))
    }
}
