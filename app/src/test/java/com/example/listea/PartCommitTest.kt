package com.example.listea

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.COMPLETION_AUTOMATIC
import com.example.listea.data.COMPLETION_EXPLICIT
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A commit is about **one part**, and the write says so.
 *
 * Before v0.8.4 `review_events` carried a Task id and the response was the Task's. A Task of three
 * questions has three answers, and the record has to be able to hold them apart: the event names
 * its part, the part's current answer moves, and no sibling part is touched. The write is also
 * scoped in the other direction — a part the Package detached, a part this build cannot present,
 * or a part key that does not exist is not an answerable decision, and the boundary refuses it
 * rather than filing a judgment on a page nobody could have been shown.
 *
 * These tests call the DAO directly, which is the unit that owns the transaction; the policy rules
 * that read the parts afterwards are [PartCompletionPolicyTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartCommitTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val application: Context get() = ApplicationProvider.getApplicationContext()
    private val template = TemplateVersionRef(TemplateId("fixture.review"), 1)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(application, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `a part commit writes that part's answer and leaves its siblings alone`() = runTest {
        task()

        dao.submitReview(
            taskId = TASK,
            partKey = "q2",
            response = ReviewResponse.MediaReview(completed = true, selections = setOf("tag")),
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 100
        )

        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(
            "q2",
            rawText("SELECT partKey FROM task_part_state")
        )
        assertEquals(
            ReviewResponse.MediaReview(completed = true, selections = setOf("tag")).encode(),
            rawText("SELECT response FROM task_part_state")
        )
        assertEquals(100, rawInt("SELECT answeredAt FROM task_part_state"))
        // The revision comes from the part's body, so a body that moved under the answer is
        // visible on exactly the answer it affects.
        assertEquals(4, rawInt("SELECT revision FROM task_part_state"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals(COMPLETION_AUTOMATIC, rawText("SELECT completionSource FROM task_state"))
    }

    @Test
    fun `the event names its part`() = runTest {
        task()

        dao.submitReview(
            TASK, "q2", media(), template, null, CompletionPolicy.SinglePart,
            completesTask = false, explicitCompletion = false, now = 100
        )

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals("q2", rawText("SELECT partKey FROM review_events"))
        assertEquals(TASK, rawText("SELECT taskId FROM review_events"))
    }

    @Test
    fun `a Task-level commit names no part`() = runTest {
        task()

        dao.submitReview(
            TASK, null, media(), template, null, CompletionPolicy.SinglePart,
            completesTask = true, explicitCompletion = false, now = 100
        )

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertNull(rawNullableText("SELECT partKey FROM review_events"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
    }

    @Test
    fun `a structured answer projects its visible fields, with their keys`() = runTest {
        task()

        dao.submitReview(
            TASK, "q1",
            ReviewResponse.StructuredReview(
                completed = true,
                fields = listOf(
                    FieldAnswer(fieldKey = "ate", type = "choice.single", value = "yes"),
                    FieldAnswer(fieldKey = "what", type = "text", value = "soup")
                )
            ),
            template, null, CompletionPolicy.SinglePart,
            completesTask = false, explicitCompletion = false, now = 100
        )

        assertEquals(2, rawInt("SELECT COUNT(*) FROM review_event_answers"))
        assertEquals(
            listOf("ate", "what"),
            rawStrings("SELECT fieldKey FROM review_event_answers ORDER BY ordinal")
        )
        // Fields and viewer events share the projection table, and a field row's `assetId` is null.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_event_answers WHERE assetId IS NOT NULL"))
    }

    @Test
    fun `a detached part is not answerable`() = runTest {
        task(q2Present = false)

        dao.submitReview(
            TASK, "q2", media(), template, null, CompletionPolicy.SinglePart,
            completesTask = false, explicitCompletion = false, now = 100
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state"))
    }

    @Test
    fun `a part this build cannot present is not answerable`() = runTest {
        task(q2Availability = "unsupported-viewer")

        dao.submitReview(
            TASK, "q2", media(), template, null, CompletionPolicy.SinglePart,
            completesTask = false, explicitCompletion = false, now = 100
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `a part key that is not this Task's writes nothing`() = runTest {
        task()

        dao.submitReview(
            TASK, "q9", media(), template, null, CompletionPolicy.SinglePart,
            completesTask = false, explicitCompletion = false, now = 100
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `a commit about a Task that is gone writes nothing`() = runTest {
        // No Task row, therefore no state row and no part: the boundary must not mint anything.
        dao.submitReview(
            "vanished", "q1", media(), template, null, CompletionPolicy.SinglePart,
            completesTask = true, explicitCompletion = false, now = 100
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state"))
    }

    @Test
    fun `an explicit completion is recorded as such`() = runTest {
        task()

        dao.submitReview(
            TASK, null, media(), template, null, CompletionPolicy.SinglePart,
            completesTask = true, explicitCompletion = true, now = 100
        )

        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals(COMPLETION_EXPLICIT, rawText("SELECT completionSource FROM task_state"))
    }

    // --- fixture ---------------------------------------------------------------------------------

    /**
     * One Task with three parts. q2 has a body at revision 4; the others have none, which is the
     * shape a locally-created part has and is enough to prove the commit is part-scoped.
     */
    private fun task(
        q2Present: Boolean = true,
        q2Availability: String? = null
    ) {
        raw(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES ('$TASK', 1, 'pub-$TASK')"
        )
        raw(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, " +
                "completionSource, updatedAt) VALUES ('$TASK', 0, 0, '', '', 0)"
        )
        raw(
            "INSERT INTO task_parts (taskId, partKey, position, origin, promptKey, required, " +
                "present, availability, updatedAt) VALUES " +
                "('$TASK', 'q1', 0, 'package', 'q1', 1, 1, NULL, 0), " +
                "('$TASK', 'q2', 1, 'package', 'q2', 1, ${if (q2Present) 1 else 0}, " +
                "${q2Availability?.let { "'$it'" } ?: "NULL"}, 0), " +
                "('$TASK', 'q3', 2, 'package', 'q3', 1, 1, NULL, 0)"
        )
        raw(
            "INSERT INTO task_part_payloads (taskId, partKey, kind, schemaVersion, payload, " +
                "revision, updatedAt) VALUES ('$TASK', 'q2', 'profile', 1, '{}', 4, 0)"
        )
    }

    private fun media() = ReviewResponse.MediaReview(completed = true, selections = emptySet())

    private fun raw(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rawNullableText(sql: String): String? =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun rawStrings(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val TASK = "task-1"
    }
}
