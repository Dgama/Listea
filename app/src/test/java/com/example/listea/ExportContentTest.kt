package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadPromptRow
import com.example.listea.data.PayloadTaskRow
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
 * Export v5: a structured Prompt's identity and its answer, and nothing personal.
 *
 * D6 in the v0.8.2 plan is a privacy rule as much as a shape rule: the export records the decision,
 * not the dating profile. The golden body below therefore asserts both halves at once — the exact
 * key order a receiver can rely on, and that the stem, the profile fields and the photo are absent.
 *
 * The version is a claim about the body. A media-only round must stay at 2, and a mixed round is 5
 * because one Task is structured; the media Task in that mixed body carries neither new key, which
 * is what lets a receiver read v5 without inventing answers for photographs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportContentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a structured Task is exactly this body, with no profile text`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(structuredTask("TASK-1", "alice")),
                prompts = mapOf("TASK-1" to promptRow("TASK-1")),
                responses = mapOf("TASK-1" to response("like", "Like"))
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
        assertFalse(body.contains("Likes long walks"))
        assertFalse(body.contains("photo"))
        assertFalse(body.contains("\"stem\""))
    }

    @Test
    fun `a media-only round stays at version 2 with neither new key`() {
        val body = buildWebhookPayload(
            payload(tasks = listOf(mediaTask("TASK-2", "a.jpg")))
        )

        assertTrue(body.startsWith("""{"version":2,"""))
        assertFalse(body.contains("\"content\""))
        assertFalse(body.contains("\"result\""))
    }

    @Test
    fun `a mixed round is version 5 and the media Task is unchanged`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(
                    structuredTask("TASK-1", "alice"),
                    mediaTask("TASK-2", "a.jpg")
                ),
                prompts = mapOf("TASK-1" to promptRow("TASK-1")),
                responses = mapOf("TASK-1" to response("like", "Like"))
            )
        )

        val json = JSONObject(body)
        assertEquals(5, json.getInt("version"))
        val tasks = json.getJSONArray("tasks")
        assertTrue(tasks.getJSONObject(0).has("content"))
        assertTrue(tasks.getJSONObject(0).has("result"))
        assertFalse(tasks.getJSONObject(1).has("content"))
        assertFalse(tasks.getJSONObject(1).has("result"))
    }

    @Test
    fun `an unreadable response still names the content and the completion`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(structuredTask("TASK-1", "alice")),
                prompts = mapOf("TASK-1" to promptRow("TASK-1")),
                responses = mapOf("TASK-1" to "a response from a later build")
            )
        )

        val result = JSONObject(body).getJSONArray("tasks").getJSONObject(0)
            .getJSONObject("result")
        assertTrue(result.getBoolean("completed"))
        assertFalse(result.has("choice"))
    }

    @Test
    fun `a Task with no envelope writes no content even when a response exists`() {
        val body = buildWebhookPayload(
            payload(
                tasks = listOf(mediaTask("TASK-2", "a.jpg")),
                responses = mapOf("TASK-2" to response("like", "Like"))
            )
        )

        assertTrue(body.startsWith("""{"version":2,"""))
        assertFalse(body.contains("\"content\""))
        assertFalse(body.contains("\"result\""))
    }

    @Test
    fun `the text file is the same bytes as the webhook body`() {
        val export = payload(
            tasks = listOf(structuredTask("TASK-1", "alice")),
            prompts = mapOf("TASK-1" to promptRow("TASK-1")),
            responses = mapOf("TASK-1" to response("like", "Like"))
        )

        assertEquals(
            buildWebhookPayload(export),
            TxtExporter(context).render(export)
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun payload(
        tasks: List<ListItemEntity>,
        prompts: Map<String, PayloadPromptRow> = emptyMap(),
        responses: Map<String, String> = emptyMap()
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
            assetsByTask = emptyMap(),
            resolver = TemplateResolver.of(emptyList(), emptyList()),
            promptsByTask = prompts,
            responseByTask = responses
        )
    )

    private fun structuredTask(taskId: String, title: String) = ListItemEntity(
        id = 1,
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

    private fun mediaTask(taskId: String, title: String) = ListItemEntity(
        id = 2,
        publicId = taskId,
        listId = 7,
        taskId = taskId,
        title = title,
        isCompleted = true,
        sortOrder = 1,
        createdAt = 0,
        sourceUri = "content://root/$title",
        sourceRelativePath = title,
        rootRelativePath = title,
        sourceMissing = false,
        selections = ""
    )

    private fun promptRow(taskId: String) = PayloadPromptRow(
        taskId = taskId,
        packageKey = "dating-demo",
        promptKey = "alice",
        kind = "profile",
        schemaVersion = 1,
        revision = 3
    )

    private fun response(optionId: String, label: String): String =
        ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(ViewerEvent.Choice(optionId = optionId, label = label))
        ).encode()
}
