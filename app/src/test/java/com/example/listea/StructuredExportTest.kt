package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadPartRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.ReviewResponse
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
 * A structured answer on the wire: the fields the person actually saw, and nothing else.
 *
 * A `form` Prompt's answer is a list of visible fields, each with a key, a type and a value. The
 * export reads the stored response — which already holds only the fields that were visible and
 * answered — and spells it as `{"fields":[...]}`. The suite pins the order (the response
 * canonicalises by field key, so the body is stable rather than dependent on the form's drawing
 * order), the field grammar, and the three states a structured part can be in: pending (`null`),
 * answered with fields (the array), answered with nothing this body can spell (`""`). The last
 * case is the one a receiver must not read as "pending", because somebody did answer the question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StructuredExportTest {

    @Test
    fun `a structured part exports its visible fields in key order with their types`() {
        val body = buildWebhookPayload(
            payload(
                parts = mapOf(
                    "TASK-1" to listOf(
                        part(
                            "TASK-1",
                            "meal",
                            response = structured(
                                FieldAnswer("ate", "choice.single", "yes"),
                                FieldAnswer("what", "text", "Soup"),
                                FieldAnswer("rating", "rating", "4")
                            )
                        )
                    )
                )
            )
        )

        val json = JSONObject(body)
        assertEquals(6, json.getInt("version"))
        val result = json.getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("result")

        // One result shape per answer family: a structured part has fields, never a choice.
        assertEquals(listOf("fields"), result.keys().asSequence().toList())
        val fields = result.getJSONArray("fields")
        assertEquals(
            listOf("ate|choice.single|yes", "rating|rating|4", "what|text|Soup"),
            (0 until fields.length()).map { index ->
                val field = fields.getJSONObject(index)
                listOf(
                    field.getString("key"),
                    field.getString("type"),
                    field.getString("value")
                ).joinToString("|")
            }
        )
    }

    @Test
    fun `an answered structured part with no fields is empty, not pending`() {
        val body = buildWebhookPayload(
            payload(
                parts = mapOf(
                    "TASK-1" to listOf(
                        part("TASK-1", "q1", response = structured()),
                        // q2 has no response row: nobody has answered it.
                        part("TASK-1", "q2", response = null)
                    )
                )
            )
        )

        val parts = JSONObject(body).getJSONArray("tasks").getJSONObject(0).getJSONArray("parts")
        // A form whose every question was conditionally hidden stores an answer with no fields.
        // It was answered; saying `null` would invite the receiver to ask again.
        assertEquals("", parts.getJSONObject(0).getString("result"))
        assertTrue(parts.getJSONObject(1).isNull("result"))
    }

    @Test
    fun `an unreadable structured response is empty on a multi-part Task`() {
        val body = buildWebhookPayload(
            payload(
                parts = mapOf(
                    "TASK-1" to listOf(
                        part("TASK-1", "q1", response = "a response from a later build"),
                        part("TASK-1", "q2", response = null)
                    )
                )
            )
        )

        val parts = JSONObject(body).getJSONArray("tasks").getJSONObject(0).getJSONArray("parts")
        // The state row proves a commit happened. The value is withheld because this build cannot
        // read it, and withholding is not the same fact as never having been answered.
        assertEquals("", parts.getJSONObject(0).getString("result"))
        assertTrue(parts.getJSONObject(1).isNull("result"))
    }

    @Test
    fun `a structured part's assets are exported under the part, not the Task`() {
        val body = buildWebhookPayload(
            payload(
                parts = mapOf(
                    "TASK-1" to listOf(
                        part("TASK-1", "meal", response = structured(FieldAnswer("ate", "text", "Soup")))
                    )
                ),
                assets = mapOf(
                    "TASK-1" to listOf(asset("TASK-1", "photos/soup.jpg", partKey = "meal"))
                )
            )
        )

        val task = JSONObject(body).getJSONArray("tasks").getJSONObject(0)
        assertFalse(task.has("assets"))
        val part = task.getJSONArray("parts").getJSONObject(0)
        assertEquals("soup.jpg", part.getJSONArray("assets").getJSONObject(0).getString("title"))
        assertEquals("photos", part.getJSONArray("assets").getJSONObject(0).getString("relativePath"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun payload(
        parts: Map<String, List<PayloadPartRow>>,
        assets: Map<String, List<PayloadAssetRow>> = emptyMap()
    ): ExportPayload {
        val row = item("TASK-1", "meal")
        return ExportPayload(
            event = "list.completed",
            listId = 7,
            listTitle = "Dating",
            completedAt = null,
            sourceId = null,
            sourceTitle = null,
            at = 1000,
            tasks = exportTaskViews(
                rows = listOf(row),
                tasks = mapOf(
                    "TASK-1" to PayloadTaskRow(
                        taskId = "TASK-1",
                        publicId = "TASK-1",
                        displayName = "meal",
                        assetControls = null
                    )
                ),
                assetsByTask = assets,
                resolver = TemplateResolver.of(emptyList(), emptyList()),
                partsByTask = parts
            )
        )
    }

    private fun item(taskId: String, title: String) = ListItemEntity(
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
        rootRelativePath = "meal-demo/$title",
        sourceMissing = false,
        selections = ""
    )

    private fun part(taskId: String, key: String, response: String?) = PayloadPartRow(
        taskId = taskId,
        partKey = key,
        position = 0,
        promptKey = key,
        required = true,
        present = true,
        kind = "form",
        schemaVersion = 1,
        revision = 1,
        response = response
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

    private fun structured(vararg fields: FieldAnswer): String =
        ReviewResponse.StructuredReview(completed = true, fields = fields.toList()).encode()
}
