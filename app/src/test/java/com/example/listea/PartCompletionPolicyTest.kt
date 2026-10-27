package com.example.listea

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.COMPLETION_AUTOMATIC
import com.example.listea.data.COMPLETION_EXPLICIT
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a Task of several parts counts as finished.
 *
 * [PartCommitTest] pins where an answer goes. This suite pins what the answer *means* to the Task:
 * the template's [CompletionPolicy] decides automatic completion, and a person's explicit
 * completion is a separate fact that no later answer may quietly undo.
 *
 * The four policies are not stylistic choices — each is a different question a template author
 * answers:
 *
 * - `SinglePart` keeps the pre-v0.8.4 rule: the response says whether it completed the Task;
 * - `AllRequiredParts` waits for every required, present part;
 * - `AnyPart` completes on the first required answer;
 * - `ExplicitOnly` never completes from an answer, only from the control.
 *
 * Two cases are about what does *not* count: a detached required part must not hold a Task open
 * (the Package no longer asks it), while a required part this build cannot present still does
 * (the person cannot answer it, but it has not gone away). Both are tested through the parts rows
 * the DAO reads, not through a UI state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartCompletionPolicyTest {

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
    fun `SinglePart still lets the response decide`() = runTest {
        task()

        // A response that says it completed the Task completes it, with siblings pending — the
        // rule every definition written before v0.8.4 stated and must keep stating.
        submit("q1", CompletionPolicy.SinglePart, completesTask = true)
        assertEquals(1, completed())

        // A response that does not is not completed by a sibling either.
        task()
        submit("q1", CompletionPolicy.SinglePart, completesTask = false)
        assertEquals(0, completed())
    }

    @Test
    fun `AllRequiredParts completes only when the last required part is answered`() = runTest {
        task()

        submit("q1", CompletionPolicy.AllRequiredParts)
        assertEquals(0, completed())
        submit("q2", CompletionPolicy.AllRequiredParts)
        assertEquals(0, completed())
        submit("q3", CompletionPolicy.AllRequiredParts)
        assertEquals(1, completed())
        assertEquals(COMPLETION_AUTOMATIC, source())
    }

    @Test
    fun `AnyPart completes on the first required answer`() = runTest {
        task()

        submit("q1", CompletionPolicy.AnyPart)

        assertEquals(1, completed())
        assertEquals(COMPLETION_AUTOMATIC, source())
        // A later answer is still recorded, and completion is not toggled back off by it.
        submit("q2", CompletionPolicy.AnyPart)
        assertEquals(1, completed())
    }

    @Test
    fun `ExplicitOnly never completes from an answer`() = runTest {
        task()

        submit("q1", CompletionPolicy.ExplicitOnly)
        submit("q2", CompletionPolicy.ExplicitOnly)
        submit("q3", CompletionPolicy.ExplicitOnly)
        assertEquals(0, completed())

        submit(null, CompletionPolicy.ExplicitOnly, explicit = true)
        assertEquals(1, completed())
        assertEquals(COMPLETION_EXPLICIT, source())
    }

    @Test
    fun `a detached required part does not hold the Task open`() = runTest {
        task(q3Present = false)

        submit("q1", CompletionPolicy.AllRequiredParts)
        submit("q2", CompletionPolicy.AllRequiredParts)

        assertEquals(1, completed())
    }

    @Test
    fun `a required part this build cannot present still blocks`() = runTest {
        task(q3Availability = "unsupported-viewer")

        submit("q1", CompletionPolicy.AllRequiredParts)
        submit("q2", CompletionPolicy.AllRequiredParts)

        // The part is still required and still unanswered: not being able to draw it is a reason
        // the Task stays open, exactly as an unviewable file is.
        assertEquals(0, completed())
    }

    @Test
    fun `an optional part that is never answered does not block`() = runTest {
        task(q3Required = false)

        submit("q1", CompletionPolicy.AllRequiredParts)
        submit("q2", CompletionPolicy.AllRequiredParts)

        assertEquals(1, completed())
    }

    @Test
    fun `an explicit completion with pending required parts is honoured`() = runTest {
        task()

        submit(null, CompletionPolicy.AllRequiredParts, explicit = true)

        assertEquals(1, completed())
        assertEquals(COMPLETION_EXPLICIT, source())
    }

    @Test
    fun `an automatic recompute cannot take an explicit completion away`() = runTest {
        task()

        submit(null, CompletionPolicy.AllRequiredParts, explicit = true)
        // A part answer arrives afterwards. It is automatic, and the recompute it performs is not a
        // retraction of the sign-off; the value and its provenance both stay.
        submit("q1", CompletionPolicy.AllRequiredParts)

        assertEquals(1, completed())
        assertEquals(COMPLETION_EXPLICIT, source())
    }

    @Test
    fun `an explicit completion stays explicit even when the last part would also complete`() = runTest {
        task()

        submit(null, CompletionPolicy.AllRequiredParts, explicit = true)
        submit("q1", CompletionPolicy.AllRequiredParts)
        submit("q2", CompletionPolicy.AllRequiredParts)
        submit("q3", CompletionPolicy.AllRequiredParts)

        assertEquals(1, completed())
        assertEquals(COMPLETION_EXPLICIT, source())
    }

    @Test
    fun `a Task whose required parts are all absent is not automatically complete`() = runTest {
        task(q1Present = false, q2Present = false, q3Present = false)

        submit(null, CompletionPolicy.AllRequiredParts, completesTask = true)

        assertEquals(0, completed())
    }

    // --- fixture ---------------------------------------------------------------------------------

    private var currentTask: String = ""
    private var counter = 0

    /**
     * Three package parts, all required and present unless a test says otherwise. Each test asks
     * for its own Task so one test's answers cannot leak into the next.
     */
    private fun task(
        q1Present: Boolean = true,
        q2Present: Boolean = true,
        q3Present: Boolean = true,
        q3Required: Boolean = true,
        q3Availability: String? = null
    ): String {
        val id = "task-${++counter}"
        currentTask = id
        raw("INSERT INTO tasks (id, createdAt, publicId) VALUES ('$id', 1, 'pub-$id')")
        raw(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, " +
                "completionSource, updatedAt) VALUES ('$id', 0, 0, '', '', 0)"
        )
        raw(
            "INSERT INTO task_parts (taskId, partKey, position, origin, promptKey, required, " +
                "present, availability, updatedAt) VALUES " +
                "('$id', 'q1', 0, 'package', 'q1', 1, ${bit(q1Present)}, NULL, 0), " +
                "('$id', 'q2', 1, 'package', 'q2', 1, ${bit(q2Present)}, NULL, 0), " +
                "('$id', 'q3', 2, 'package', 'q3', ${bit(q3Required)}, ${bit(q3Present)}, " +
                "${q3Availability?.let { "'$it'" } ?: "NULL"}, 0)"
        )
        return id
    }

    private suspend fun submit(
        partKey: String?,
        policy: CompletionPolicy,
        completesTask: Boolean = true,
        explicit: Boolean = false
    ) {
        dao.submitReview(
            taskId = currentTask,
            partKey = partKey,
            response = ReviewResponse.MediaReview(completed = true, selections = emptySet()),
            template = template,
            context = null,
            completion = policy,
            completesTask = completesTask,
            explicitCompletion = explicit,
            now = 100
        )
    }

    private fun completed(): Int = rawInt(
        "SELECT isCompleted FROM task_state WHERE taskId = '$currentTask'"
    )

    private fun source(): String = rawText(
        "SELECT completionSource FROM task_state WHERE taskId = '$currentTask'"
    )

    private fun bit(value: Boolean) = if (value) 1 else 0

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
}
