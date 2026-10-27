package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadPartRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.encode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A half-answered Task is still exported as a whole Task.
 *
 * Completion policies (`AnyPart`, `ExplicitOnly`) make a Task finished while parts remain
 * unanswered, and a person may export a round without finishing anything at all. The body must say
 * both facts plainly: `completed` is the Task's own state, and each part's `result` is the part's own
 * state. A pending part is `null`, never omitted — its question was part of the Task whether or
 * not somebody reached it.
 *
 * Detached parts are the other half of partial: the Package no longer asks them, so they are not
 * rendered at all, but the Task still held more than one decision and keeps the v6 shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportPartialTest {

    @Test
    fun `a partly answered Task exports every present part and nulls the pending one`() {
        val body = buildWebhookPayload(
            payload(
                completed = false,
                parts = listOf(
                    part("q1", response = choice("like", "Like")),
                    part("q2", response = structured(FieldAnswer("ate", "text", "Soup"))),
                    part("q3", response = null)
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val task = json.getJSONArray("tasks").getJSONObject(0)
        assertFalse(task.getBoolean("completed"))
        val parts = task.getJSONArray("parts")
        assertEquals(3, parts.length())
        assertEquals("like", parts.getJSONObject(0).getJSONObject("result")
            .getJSONObject("choice").getString("id"))
        assertTrue(parts.getJSONObject(1).getJSONObject("result").has("fields"))
        assertTrue(parts.getJSONObject(2).isNull("result"))
    }

    @Test
    fun `completion is the Task's fact, not every part being answered`() {
        // AnyPart: one answer completes the Task while the sibling stays open.
        val body = buildWebhookPayload(
            payload(
                completed = true,
                parts = listOf(
                    part("q1", response = choice("like", "Like")),
                    part("q2", response = null)
                )
            )
        )

        val task = JSONObject(body).getJSONArray("tasks").getJSONObject(0)
        assertTrue(task.getBoolean("completed"))
        assertTrue(task.getJSONArray("parts").getJSONObject(1).isNull("result"))
    }

    @Test
    fun `a detached part is omitted and its Assets go with it`() {
        val body = buildWebhookPayload(
            payload(
                completed = false,
                parts = listOf(
                    part("q1", response = choice("like", "Like")),
                    part("q2", response = null),
                    part("q3", response = null, present = false)
                ),
                assets = listOf(
                    asset("photos/gone.jpg", partKey = "q3")
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val task = json.getJSONArray("tasks").getJSONObject(0)
        val parts = task.getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("q1", parts.getJSONObject(0).getString("partKey"))
        assertEquals("q2", parts.getJSONObject(1).getString("partKey"))
        // v6 keeps Assets under their part; a part nobody shows has nowhere to put them.
        assertFalse(task.has("assets"))
        assertFalse(body.contains("gone.jpg"))
    }

    @Test
    fun `a Task whose parts are all detached still renders the v6 shape`() {
        val body = buildWebhookPayload(
            payload(
                completed = true,
                parts = listOf(
                    part("q1", response = null, present = false),
                    part("q2", response = null, present = false)
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val task = json.getJSONArray("tasks").getJSONObject(0)
        // The count is over stored parts: a Task that held several decisions does not fall back to
        // the v5 grammar just because a revision detached them all.
        assertEquals(0, task.getJSONArray("parts").length())
        assertTrue(task.getBoolean("completed"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun payload(
        completed: Boolean,
        parts: List<PayloadPartRow>,
        assets: List<PayloadAssetRow> = emptyList()
    ): ExportPayload = ExportPayload(
        event = "list.completed",
        listId = 7,
        listTitle = "Dating",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1000,
        tasks = exportTaskViews(
            rows = listOf(item(completed)),
            tasks = mapOf(
                "TASK-1" to PayloadTaskRow(
                    taskId = "TASK-1",
                    publicId = "TASK-1",
                    displayName = "main",
                    assetControls = null
                )
            ),
            assetsByTask = if (assets.isEmpty()) emptyMap() else mapOf("TASK-1" to assets),
            resolver = TemplateResolver.of(emptyList(), emptyList()),
            partsByTask = mapOf("TASK-1" to parts)
        )
    )

    private fun item(completed: Boolean) = ListItemEntity(
        id = 1,
        publicId = "TASK-1",
        listId = 7,
        taskId = "TASK-1",
        title = "main",
        isCompleted = completed,
        sortOrder = 0,
        createdAt = 0,
        sourceUri = null,
        sourceRelativePath = null,
        rootRelativePath = "section-demo/~section/main",
        sourceMissing = false,
        selections = ""
    )

    private fun part(
        key: String,
        response: String?,
        present: Boolean = true
    ) = PayloadPartRow(
        taskId = "TASK-1",
        partKey = key,
        position = 0,
        promptKey = key,
        required = true,
        present = present,
        kind = "profile",
        schemaVersion = 1,
        revision = 1,
        response = response
    )

    private fun asset(path: String, partKey: String) = PayloadAssetRow(
        taskId = "TASK-1",
        assetId = "row-$path",
        publicId = "asset-$path",
        relativePath = path,
        sourceMissing = false,
        sortOrder = 0,
        selections = null,
        partKey = partKey
    )

    private fun choice(optionId: String, label: String): String =
        ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(ViewerEvent.Choice(optionId = optionId, label = label))
        ).encode()

    private fun structured(vararg fields: FieldAnswer): String =
        ReviewResponse.StructuredReview(completed = true, fields = fields.toList()).encode()
}
