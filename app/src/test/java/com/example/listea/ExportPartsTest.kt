package com.example.listea

import com.example.listea.domain.FieldAnswer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export v6: a Task as its present parts, each with its own answer.
 *
 * Version 6 is a shape change, not a superset: a v6 Task carries `parts[]` instead of a Task-level
 * `content`/`result`, so a receiver must branch on the version line rather than half-read the body.
 * The tests pin the version decision, the key order, and the three answer spellings — a pending
 * part sends `null`, an answered part with no typed value sends `""`, never the other way round.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportPartsTest {

    @Test
    fun `a multi-part Task makes the round v6`() {
        val mapping = mapExport(payload(task(parts = twoParts(), hasMultipleParts = true)))
        assertEquals(6, mapping.version)
    }

    @Test
    fun `a single structured part makes the round v6`() {
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part(
                            key = "meal",
                            result = ExportPartResult.Fields(
                                listOf(FieldAnswer("ate", "choice.single", "yes"))
                            )
                        )
                    )
                )
            )
        )
        assertEquals(6, mapping.version)
    }

    @Test
    fun `a single-part simple Task keeps version 5`() {
        val mapping = mapExport(
            payload(
                task(
                    content = ExportContentView("profile", 1, 2, "alice"),
                    result = ExportResultView(completed = true, choice = ExportChoiceView("like", "Like")),
                    parts = listOf(part("alice", result = ExportPartResult.Choice("like", "Like")))
                )
            )
        )
        assertEquals(5, mapping.version)
    }

    @Test
    fun `a v6 body renders every Task as parts, including a media Task`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            publicId = "task-1",
                            parts = twoParts(),
                            hasMultipleParts = true
                        ),
                        task(publicId = "task-2", parts = listOf(part("local:0")))
                    )
                )
            )
        )

        assertEquals(6, body.getInt("version"))
        val tasks = body.getJSONArray("tasks")
        for (index in 0 until tasks.length()) {
            val task = tasks.getJSONObject(index)
            assertTrue(task.has("parts"))
            assertFalse(task.has("content"))
            assertFalse(task.has("result"))
            assertFalse(task.has("assets"))
            assertFalse(task.has("isCompleted"))
            assertTrue(task.has("completed"))
        }
    }

    @Test
    fun `a part's keys are identity provenance claim answer material`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            publicId = "task-1",
                            parts = listOf(
                                part(
                                    key = "q1",
                                    promptKey = "q1",
                                    result = ExportPartResult.Choice("yes", "Yes")
                                )
                            ),
                            hasMultipleParts = true
                        )
                    )
                )
            )
        )
        val part = body.getJSONArray("tasks").getJSONObject(0).getJSONArray("parts").getJSONObject(0)

        assertEquals(
            listOf(
                "partKey", "promptKey", "kind", "schema", "revision",
                "required", "result", "assets"
            ),
            part.keys().asSequence().toList()
        )
        assertEquals("q1", part.getString("partKey"))
        assertEquals("q1", part.getString("promptKey"))
        assertEquals("profile", part.getString("kind"))
        assertEquals(1, part.getInt("schema"))
        assertEquals(3, part.getInt("revision"))
        assertEquals("yes", part.getJSONObject("result").getJSONObject("choice").getString("id"))
    }

    @Test
    fun `a degenerate part has null provenance and keeps its files`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            parts = listOf(part("local:0", assetName = "photo.jpg")),
                            hasMultipleParts = true
                        )
                    )
                )
            )
        )
        val part = body.getJSONArray("tasks").getJSONObject(0).getJSONArray("parts").getJSONObject(0)

        assertTrue(part.isNull("promptKey"))
        assertTrue(part.isNull("kind"))
        assertTrue(part.isNull("schema"))
        assertTrue(part.isNull("revision"))
        assertEquals("photo.jpg", part.getJSONArray("assets").getJSONObject(0).getString("title"))
    }

    @Test
    fun `pending is not answered-empty`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            parts = listOf(
                                part("pending"),
                                part("empty", result = ExportPartResult.AnsweredEmpty)
                            ),
                            hasMultipleParts = true
                        )
                    )
                )
            )
        )
        val parts = body.getJSONArray("tasks").getJSONObject(0).getJSONArray("parts")

        assertTrue(parts.getJSONObject(0).isNull("result"))
        assertEquals("", parts.getJSONObject(1).getString("result"))
        assertNotEquals(
            parts.getJSONObject(0).get("result"),
            parts.getJSONObject(1).get("result")
        )
    }

    @Test
    fun `a structured answer is a fields array`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            parts = listOf(
                                part(
                                    key = "meal",
                                    kind = "form",
                                    result = ExportPartResult.Fields(
                                        listOf(
                                            FieldAnswer("ate", "choice.single", "yes"),
                                            FieldAnswer("what", "text", "Soup")
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val fields = body.getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("result").getJSONArray("fields")

        assertEquals(2, fields.length())
        assertEquals("ate", fields.getJSONObject(0).getString("key"))
        assertEquals("choice.single", fields.getJSONObject(0).getString("type"))
        assertEquals("Soup", fields.getJSONObject(1).getString("value"))
    }

    @Test
    fun `the add-on follows parts and stays Task level`() {
        val body = JSONObject(
            buildWebhookPayload(
                mapExport(
                    payload(
                        task(
                            parts = twoParts(),
                            hasMultipleParts = true,
                            addOn = ExportAddOnView("a", "Mood", 1, emptyList())
                        )
                    )
                )
            )
        )
        val task = body.getJSONArray("tasks").getJSONObject(0)
        assertEquals(
            listOf("id", "title", "completed", "templates", "parts", "addOn"),
            task.keys().asSequence().toList()
        )
    }

    @Test
    fun `a Task that held several parts is v6 even when one is detached`() {
        // The caller passes only present parts; the count that decides the version includes the
        // detached row, so the shape does not silently fall back to v5.
        val payload = payload(
            task(parts = listOf(part("q1")), hasMultipleParts = true)
        )
        assertEquals(6, mapExport(payload).version)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun payload(vararg tasks: ExportTaskView) = ExportPayload(
        event = "export",
        listId = 1,
        listTitle = "List",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1,
        tasks = tasks.toList()
    )

    private fun task(
        publicId: String = "task-public",
        parts: List<ExportPartView> = emptyList(),
        hasMultipleParts: Boolean = false,
        content: ExportContentView? = null,
        result: ExportResultView? = null,
        addOn: ExportAddOnView? = null
    ) = ExportTaskView(
        publicId = publicId,
        title = "Task",
        isCompleted = false,
        templates = emptyList(),
        assets = emptyList(),
        addOn = addOn,
        content = content,
        result = result,
        parts = parts,
        hasMultipleParts = hasMultipleParts
    )

    private fun twoParts(): List<ExportPartView> = listOf(part("q1"), part("q2"))

    private fun part(
        key: String,
        promptKey: String? = key.takeUnless { it == "local:0" },
        kind: String? = if (key == "local:0") null else "profile",
        result: ExportPartResult? = null,
        assetName: String? = null
    ) = ExportPartView(
        partKey = key,
        promptKey = promptKey,
        kind = kind,
        schema = if (kind == null) null else 1,
        revision = if (kind == null) null else 3,
        required = true,
        result = result,
        assets = assetName?.let {
            listOf(
                ExportAssetView(
                    publicId = "asset-$it",
                    title = it,
                    relativePath = "",
                    sourceMissing = false,
                    actions = emptyList()
                )
            )
        }.orEmpty()
    )
}
