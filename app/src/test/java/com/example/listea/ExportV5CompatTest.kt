package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadPartRow
import com.example.listea.data.PayloadPromptRow
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
 * A device that upgraded does not start exporting a different document.
 *
 * Schema 33 gives every Task a part, including the Tasks that were one decision when they were
 * written. If that were enough to make a round v6, every media export would change shape on the
 * first launch after the update, and every v2 receiver would refuse it. The compatibility rule is
 * narrower than the schema: a Task is v6 when it holds more than one decision or a structured
 * answer, and a single-part Task keeps the v5/v2 body it had.
 *
 * The migration is also where the answer lives in the *old* place: existing judgments are in
 * `review_events`, not in `task_part_state`, so the export falls back to the Task-level response —
 * but only for a single-part Task, where there is no ambiguity about whose answer it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportV5CompatTest {

    @Test
    fun `a migrated single-part Task still exports the v5 body it had`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(item("TASK-1", "alice")),
                prompts = mapOf("TASK-1" to promptRow("TASK-1")),
                responses = mapOf("TASK-1" to response("like", "Like")),
                parts = mapOf("TASK-1" to listOf(part("TASK-1", "alice")))
            )
        )

        assertEquals(
            """{"version":5,"event":"list.completed",""" +
                """"list":{"id":7,"title":"Dating","completedAt":null},""" +
                """"tasks":[{"id":"TASK-1","title":"alice","isCompleted":true,"templates":[],""" +
                """"assets":[],"content":{"kind":"profile","schema":1,"revision":3,""" +
                """"promptKey":"alice"},"result":{"completed":true,""" +
                """"choice":{"id":"like","label":"Like"}}}]}""",
            body
        )
    }

    @Test
    fun `a migrated single part reads the Task-level response the migration left it`() {
        // Schema 33 left `task_part_state.response` empty for migrated rows: rewriting history into
        // current state would have been a reinterpretation. A single-part Task is unambiguous, so
        // the export reads the old location for it — and the structured body proves the fallback,
        // because a Fields result is what makes the round v6 in the first place.
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(item("TASK-1", "meal")),
                responses = mapOf("TASK-1" to structuredResponse()),
                parts = mapOf(
                    "TASK-1" to listOf(part("TASK-1", "meal", kind = "form", response = null))
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val result = json.getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("result")
        assertEquals("yes", result.getJSONArray("fields").getJSONObject(0).getString("value"))
    }

    @Test
    fun `a multi-part Task does not attribute the Task-level answer to any part`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(item("TASK-1", "section")),
                prompts = mapOf("TASK-1" to promptRow("TASK-1")),
                responses = mapOf("TASK-1" to response("like", "Like")),
                parts = mapOf(
                    "TASK-1" to listOf(
                        part("TASK-1", "q1"),
                        part("TASK-1", "q2")
                    )
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val parts = json.getJSONArray("tasks").getJSONObject(0).getJSONArray("parts")
        // The newest Task-level event names no part, and a multi-part Task has no honest way to
        // say which one it answered. Guessing would put one person's decision on another question.
        assertTrue(parts.getJSONObject(0).isNull("result"))
        assertTrue(parts.getJSONObject(1).isNull("result"))
    }

    @Test
    fun `a single-part media Task keeps the version-2 body`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(item("TASK-2", "a.jpg")),
                assets = mapOf(
                    "TASK-2" to listOf(
                        asset("TASK-2", "holiday/a.jpg", partKey = "local:0")
                    )
                ),
                parts = mapOf("TASK-2" to listOf(localPart("TASK-2")))
            )
        )

        assertEquals(
            """{"version":2,"event":"list.completed",""" +
                """"list":{"id":7,"title":"Dating","completedAt":null},""" +
                """"tasks":[{"id":"TASK-2","title":"a.jpg","isCompleted":true,"templates":[],""" +
                """"assets":[{"id":"asset-1","title":"a.jpg","relativePath":"holiday",""" +
                """"sourceMissing":false,"actions":[]}]}]}""",
            body
        )
        assertFalse(body.contains("\"parts\""))
        assertFalse(body.contains("\"content\""))
    }

    @Test
    fun `a single structured part is still v6 and the media Tasks beside it follow`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(item("TASK-1", "meal"), item("TASK-2", "a.jpg")),
                parts = mapOf(
                    "TASK-1" to listOf(
                        part("TASK-1", "meal", kind = "form", response = structuredResponse())
                    ),
                    "TASK-2" to listOf(localPart("TASK-2"))
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val tasks = json.getJSONArray("tasks")
        // The version line describes the document, not each Task: once the round is v6 every Task
        // is spelled the v6 way, including the media Task that would have been v2 alone.
        assertTrue(tasks.getJSONObject(0).has("parts"))
        assertTrue(tasks.getJSONObject(1).has("parts"))
        assertFalse(tasks.getJSONObject(1).has("assets"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun payload(
        tasks: List<ListItemEntity>,
        prompts: Map<String, PayloadPromptRow> = emptyMap(),
        responses: Map<String, String> = emptyMap(),
        parts: Map<String, List<PayloadPartRow>> = emptyMap(),
        assets: Map<String, List<PayloadAssetRow>> = emptyMap()
    ): ExportPayload = ExportPayload(
        event = "list.completed",
        listId = 7,
        listTitle = "Dating",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1000,
        tasks = exportTaskViews(
            rows = tasks,
            tasks = tasks.associate { row ->
                row.taskId to PayloadTaskRow(
                    taskId = row.taskId,
                    publicId = row.publicId,
                    displayName = row.title,
                    assetControls = null
                )
            },
            assetsByTask = assets,
            resolver = TemplateResolver.of(emptyList(), emptyList()),
            promptsByTask = prompts,
            responseByTask = responses,
            partsByTask = parts
        )
    )

    private fun item(taskId: String, title: String) = ListItemEntity(
        id = taskId.hashCode().toLong(),
        publicId = taskId,
        listId = 7,
        taskId = taskId,
        title = title,
        isCompleted = true,
        sortOrder = 0,
        createdAt = 0,
        sourceUri = null,
        sourceRelativePath = null,
        rootRelativePath = "dating-demo/$title",
        sourceMissing = false,
        selections = ""
    )

    private fun promptRow(taskId: String, promptKey: String = "alice") = PayloadPromptRow(
        taskId = taskId,
        packageKey = "dating-demo",
        promptKey = promptKey,
        kind = "profile",
        schemaVersion = 1,
        revision = 3
    )

    private fun part(
        taskId: String,
        key: String,
        kind: String = "profile",
        response: String? = null
    ) = PayloadPartRow(
        taskId = taskId,
        partKey = key,
        position = 0,
        promptKey = key,
        required = true,
        present = true,
        kind = kind,
        schemaVersion = 1,
        revision = 3,
        response = response
    )

    private fun localPart(taskId: String) = PayloadPartRow(
        taskId = taskId,
        partKey = "local:0",
        position = 0,
        promptKey = null,
        required = true,
        present = true,
        kind = null,
        schemaVersion = null,
        revision = null
    )

    private fun asset(taskId: String, path: String, partKey: String) = PayloadAssetRow(
        taskId = taskId,
        assetId = "row-$path",
        publicId = "asset-1",
        relativePath = path,
        sourceMissing = false,
        sortOrder = 0,
        selections = null,
        partKey = partKey
    )

    private fun response(optionId: String, label: String): String =
        ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(ViewerEvent.Choice(optionId = optionId, label = label))
        ).encode()

    private fun structuredResponse(): String =
        ReviewResponse.StructuredReview(
            completed = true,
            fields = listOf(FieldAnswer(fieldKey = "ate", type = "choice.single", value = "yes"))
        ).encode()
}
