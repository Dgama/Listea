package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAnswerRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.MarkKind
import com.example.listea.domain.StoredAddOnVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Task-level `addOn` object on the wire, and the version that introduces it.
 *
 * The plan's contract in one place: the object is a **sibling of `assets`, after it**; its answers
 * are in the questionnaire's order and carry typed values; only answers the attached version still
 * asks are sent; and a Task or a round with nothing answered sends exactly the body v0.7.0 sent.
 *
 * Key order is asserted rather than merely the presence of keys, because it comes from the builder's
 * insertion order and a receiver diffing bodies sees it move. The typed-value tests are the reason
 * `value` is not a string: a receiver should not have to know this build's stored spelling to read a
 * score.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportAddOnTest {

    @Test
    fun `a Task's answers are a sibling of assets, after it, and the body says version 4`() {
        val body = buildWebhookPayload(payload())

        val task = firstTask(body)
        assertEquals(
            listOf("id", "title", "isCompleted", "templates", "assets", "addOn"),
            task.keysInOrder()
        )
        val addOn = task.getJSONObject("addOn")
        assertEquals(listOf("id", "name", "version", "answers"), addOn.keysInOrder())
        assertEquals(
            listOf("questionId", "type", "prompt", "value"),
            addOn.getJSONArray("answers").getJSONObject(0).keysInOrder()
        )
        assertEquals(EXPORT_VERSION_ADD_ON, JSONObject(body).getInt("version"))
    }

    @Test
    fun `values are typed as their question asks`() {
        val answers = answers(payload())

        assertEquals(4, answers.getJSONObject(0).getInt("value"))
        assertEquals("rating", answers.getJSONObject(0).getString("type"))
        assertEquals(true, answers.getJSONObject(1).getBoolean("value"))
        assertEquals("yes-no", answers.getJSONObject(1).getString("type"))
        assertEquals("morning light", answers.getJSONObject(2).getString("value"))
        assertEquals("text", answers.getJSONObject(2).getString("type"))
    }

    @Test
    fun `answers follow the questionnaire, not the order they were written in`() {
        // The fixture's rows arrive text, Yes / No, rating; the version asks rating first.
        val answers = answers(payload())

        assertEquals(
            listOf("q-rating", "q-yes-no", "q-text"),
            (0 until answers.length()).map { answers.getJSONObject(it).getString("questionId") }
        )
    }

    @Test
    fun `an answer the version no longer asks, or asks differently, is not sent`() {
        val rows = listOf(
            answerRow("q-rating", "rating", "4"),
            // removed from this version
            answerRow("q-gone", "rating", "3"),
            // the same id, but the answer was given as a score and the version now asks for words
            answerRow("q-text", "rating", "3")
        )

        val addOn = firstTask(buildWebhookPayload(payload(answers = rows))).getJSONObject("addOn")
        val sent = addOn.getJSONArray("answers")

        assertEquals(1, sent.length())
        assertEquals("q-rating", sent.getJSONObject(0).getString("questionId"))
    }

    @Test
    fun `an answer authored by another add-on is not this questionnaire's answer`() {
        // The lineage check: a question id that happens to match is not a reuse licence.
        val rows = listOf(answerRow("q-rating", "rating", "4", addOnId = "another-add-on"))

        val body = buildWebhookPayload(payload(answers = rows))

        assertFalse("nothing visible means no object at all", firstTask(body).has("addOn"))
        assertEquals(EXPORT_VERSION, JSONObject(body).getInt("version"))
    }

    @Test
    fun `a questionnaire nobody answered sends no object and claims no version`() {
        val task = firstTask(buildWebhookPayload(payload(answers = emptyList())))

        assertFalse(task.has("addOn"))
        assertEquals(
            EXPORT_VERSION,
            JSONObject(buildWebhookPayload(payload(answers = emptyList()))).getInt("version")
        )
    }

    @Test
    fun `an unreadable questionnaire sends no object`() {
        val task = firstTask(buildWebhookPayload(payload(resolver = unreadableResolver())))

        assertFalse(task.has("addOn"))
    }

    // --- the version ladder -----------------------------------------------------------------------

    @Test
    fun `marks alone still say version 3, and both facts say 4`() {
        val marked = assembled(marks = true, addOn = null)
        assertEquals(EXPORT_VERSION_MARKS, JSONObject(buildWebhookPayload(marked)).getInt("version"))
        assertFalse(firstTask(buildWebhookPayload(marked)).has("addOn"))

        val both = assembled(marks = true, addOn = addOnView())
        val body = buildWebhookPayload(both)
        assertEquals(EXPORT_VERSION_ADD_ON, JSONObject(body).getInt("version"))
        // The marks array answers to its own rule; the questionnaire does not replace it.
        assertTrue(firstAsset(body).has("marks"))
        assertTrue(firstTask(body).has("addOn"))
    }

    // --- assembly ---------------------------------------------------------------------------------

    /**
     * A round of one, assembled the way delivery assembles it: the Task and Asset projections in,
     * one resolver, and [exportTaskViews] deciding what the wire sees.
     */
    private fun payload(
        answers: List<PayloadAnswerRow> = defaultAnswers(),
        resolver: AddOnResolver = resolver()
    ): ExportPayload = ExportPayload(
        event = EVENT_LIST_COMPLETED,
        listId = 7,
        listTitle = "Trip",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1000,
        tasks = exportTaskViews(
            rows = listOf(item()),
            tasks = mapOf(TASK_ID to PayloadTaskRow(TASK_ID, "TASK-PUBLIC-1", null, null, "$ADD_ON_ID\t1")),
            assetsByTask = emptyMap(),
            resolver = TemplateResolver.Empty,
            addOnResolver = resolver,
            answersByTask = if (answers.isEmpty()) emptyMap() else mapOf(TASK_ID to answers)
        )
    )

    /** A payload whose Tasks are built directly, for the version-ladder cases. */
    private fun assembled(marks: Boolean, addOn: ExportAddOnView?) = ExportPayload(
        event = EVENT_LIST_COMPLETED,
        listId = 7,
        listTitle = "Trip",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1000,
        tasks = listOf(
            ExportTaskView(
                publicId = "TASK-PUBLIC-1",
                title = "a.jpg",
                isCompleted = true,
                templates = emptyList(),
                assets = listOf(
                    ExportAssetView(
                        publicId = "ASSET-1",
                        title = "a.jpg",
                        relativePath = "2026-07-11",
                        sourceMissing = false,
                        actions = emptyList(),
                        marks = if (marks) {
                            listOf(
                                ExportMarkView(
                                    id = "m-1",
                                    kind = MarkKind.COMMENT,
                                    toolId = "comment-1",
                                    pageIndex = 0,
                                    text = "the passage",
                                    value = "noted"
                                )
                            )
                        } else {
                            emptyList()
                        }
                    )
                ),
                addOn = addOn
            )
        )
    )

    private fun addOnView() = ExportAddOnView(
        id = ADD_ON_ID,
        name = "Task review",
        version = 1,
        answers = listOf(
            ExportAnswerView("q-rating", AddOnQuestionType.RATING, "Rating", AddOnAnswerValue.Rating(4))
        )
    )

    /**
     * The version under test: three questions, asked in this order. Rows arrive in another.
     *
     * Chosen ids say what each case is about: `q-rating` a score, `q-yes-no` a classification,
     * `q-text` free words — the round's own scenario in three types.
     */
    private fun resolver(): AddOnResolver = AddOnResolver(
        versions = mapOf(
            AddOnVersionRef(AddOnId(ADD_ON_ID), 1) to StoredAddOnVersion(
                ref = AddOnVersionRef(AddOnId(ADD_ON_ID), 1),
                name = "Task review",
                questions = listOf(
                    AddOnQuestion("q-rating", AddOnQuestionType.RATING, "Overall rating"),
                    AddOnQuestion("q-yes-no", AddOnQuestionType.YES_NO, "Is it a life photo?"),
                    AddOnQuestion("q-text", AddOnQuestionType.TEXT, "Notes")
                )
            )
        )
    )

    /** A resolver that holds nothing, which is what a version this build cannot decode becomes. */
    private fun unreadableResolver(): AddOnResolver = AddOnResolver.Empty

    private fun defaultAnswers() = listOf(
        answerRow("q-text", "text", "morning light"),
        answerRow("q-yes-no", "yes-no", "yes"),
        answerRow("q-rating", "rating", "4")
    )

    private fun answerRow(
        questionId: String,
        kind: String,
        value: String,
        addOnId: String = ADD_ON_ID,
        version: Int = 1
    ) = PayloadAnswerRow(
        taskId = TASK_ID,
        questionId = questionId,
        kind = kind,
        value = value,
        authoredAddOnId = addOnId,
        authoredAddOnVersion = version,
        createdAt = 0,
        updatedAt = 0
    )

    private fun item() = ListItemEntity(
        id = 99,
        publicId = "ROW-PUBLIC-1",
        listId = 7,
        taskId = TASK_ID,
        title = "a.jpg",
        isCompleted = true,
        sortOrder = 0,
        createdAt = 1000,
        sourceUri = "content://root/a.jpg",
        sourceRelativePath = "a.jpg",
        rootRelativePath = "a.jpg",
        selections = ""
    )

    /** The keys in insertion order — what a receiver diffing two bodies sees move. */
    private fun JSONObject.keysInOrder(): List<String> = keys().asSequence().toList()

    private fun firstTask(body: String): JSONObject =
        JSONObject(body).getJSONArray("tasks").getJSONObject(0)

    private fun firstAsset(body: String): JSONObject =
        firstTask(body).getJSONArray("assets").getJSONObject(0)

    private fun answers(payload: ExportPayload): JSONArray =
        firstTask(buildWebhookPayload(payload)).getJSONObject("addOn").getJSONArray("answers")

    private companion object {
        const val TASK_ID = "task-99"
        const val ADD_ON_ID = "add-on-lineage"
    }
}
