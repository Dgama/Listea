package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two card edits v0.8.2f adds: a Todo's display name, and its add-on.
 *
 * Both are writes to the Todo itself, and both are worth pinning at this layer because the dialogs
 * are thin: renaming must trim and must refuse a blank (an unlabelled Todo is unlabelled
 * everywhere), and changing an add-on must reach the Tasks that already exist as well as the Todo —
 * one transaction, because a card claiming an add-on its Tasks do not hold is precisely the drift
 * the card exists to make impossible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoCardEditTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(application as Context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `renaming a Todo writes the trimmed name and keeps its folder`() = runTest(dispatcher) {
        val source = todo(displayName = "Photos")

        viewModel().renameTodo(source.id, "  Holiday pictures  ")
        val renamed = dao.observeSourceSummaries()
            .first { rows -> rows.any { it.id == source.id && it.displayName == "Holiday pictures" } }
            .single { it.id == source.id }

        assertEquals("Holiday pictures", renamed.displayName)
        // The name is a label, not a location: the folder the Todo reads is untouched.
        assertEquals(source.relativePath, renamed.relativePath)
        assertEquals(source.rootUri, renamed.rootUri)
    }

    @Test
    fun `a blank rename is ignored rather than clearing the name`() = runTest(dispatcher) {
        val source = todo(displayName = "Photos")

        viewModel().renameTodo(source.id, "   ")
        val unchanged = dao.observeSourceSummaries().first { rows -> rows.isNotEmpty() }
            .single { it.id == source.id }

        assertEquals("Photos", unchanged.displayName)
    }

    @Test
    fun `changing an add-on re-stamps the Todo and every Task it holds`() = runTest(dispatcher) {
        val source = todo(addOn = "old\t1")
        insertTask(source.id, taskId = "task-a")
        insertTask(source.id, taskId = "task-b")

        viewModel().setTodoAddOn(source.id, "new\t2")
        val updated = dao.observeSourceSummaries()
            .first { rows -> rows.any { it.id == source.id && it.addOn == "new\t2" } }
            .single { it.id == source.id }

        assertEquals("new\t2", updated.addOn)
        assertEquals(listOf("new\t2", "new\t2"), taskAddOns(source.id))
    }

    @Test
    fun `clearing an add-on reaches the Tasks too, and the answers are not deleted here`() =
        runTest(dispatcher) {
            val source = todo(addOn = "old\t1")
            insertTask(source.id, taskId = "task-a")

            viewModel().setTodoAddOn(source.id, "")
            val cleared = dao.observeSourceSummaries()
                .first { rows -> rows.any { it.id == source.id && it.addOn.isEmpty() } }
                .single { it.id == source.id }

            assertEquals("", cleared.addOn)
            // The Task follows the card. Its recorded answers stay in the database — the resolver's
            // lineage check hides them — so switching back is a recovery rather than a re-answer.
            assertEquals(listOf(""), taskAddOns(source.id))
        }

    // --- reaching the states above ---------------------------------------------------------------

    private suspend fun todo(
        displayName: String = "Photos",
        addOn: String = ""
    ): SourceEntity = dao.registerSource(
        rootUri = "content://root",
        relativePath = "ListeaAccept",
        displayName = displayName,
        importerKind = ImporterKind.FILE_AS_TASK,
        addOn = addOn,
        now = 0
    )

    /**
     * A Task and its import row, inserted directly.
     *
     * The DAO's own insert paths are protected and belong to a whole import run, which is more
     * machinery than this test is about; what it holds constant is the shape a run leaves behind.
     */
    private fun insertTask(sourceId: String, taskId: String) {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO tasks (id, createdAt, publicId) VALUES (?, 0, ?)", arrayOf(taskId, "pub-$taskId"))
        db.execSQL(
            "INSERT INTO task_imports " +
                "(taskId, sourceId, groupPath, displayName, importedAt, lastSeenAt, " +
                "assetControls, addOn, taskReview) VALUES (?, ?, ?, ?, 0, 0, '', 'old\t1', '')",
            arrayOf(taskId, sourceId, "ListeaAccept/$taskId", taskId)
        )
    }

    private fun taskAddOns(sourceId: String): List<String> {
        val rows = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT addOn FROM task_imports WHERE sourceId = ? ORDER BY taskId", arrayOf(sourceId))
            .use { cursor ->
                while (cursor.moveToNext()) rows += cursor.getString(0)
            }
        return rows
    }

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)
}
