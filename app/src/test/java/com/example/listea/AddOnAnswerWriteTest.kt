package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnStore
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ScannedFile
import com.example.listea.data.TemplateStore
import com.example.listea.data.toEntity
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.encodeAddOnQuestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the review page's writes do to the answer table.
 *
 * The page offers only controls that fit its question, so these tests are about the *rule* rather
 * than about the screen: one row per question, rewritten in place; `createdAt` kept across a
 * re-answer while `updatedAt` moves; clearing removes exactly one answer; and the write path refusing
 * a question the attached version does not ask or a value its type cannot hold. That refusal is the
 * second gate for a page that outlived an edit, and it is the one a stale surface would hit.
 *
 * The cascade is the table's own promise: an answer is about a Task, so a Task that goes takes its
 * answers with it — unlike an add-on edit, which only ever hides one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddOnAnswerWriteTest {

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
    fun `each question has one row and a re-answer rewrites it`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        val viewModel = viewModel()

        viewModel.setAddOnAnswer(taskId, RATING, AddOnAnswerValue.Rating(4)).join()
        viewModel.setAddOnAnswer(taskId, TEXT, AddOnAnswerValue.Text("  good light ")).join()

        val first = viewModel.observeTaskAnswers(taskId).first()
        assertEquals(2, first.size)
        // The row keeps the words without the keyboard's spaces, and names the version that asked.
        val text = first.single { it.questionId == TEXT }
        assertEquals(AddOnAnswerValue.Text("good light"), text.value)
        assertEquals(LINEAGE, text.authoredAddOnId)
        assertEquals(1, text.authoredAddOnVersion)

        viewModel.setAddOnAnswer(taskId, RATING, AddOnAnswerValue.Rating(5)).join()

        val again = viewModel.observeTaskAnswers(taskId).first()
        assertEquals("a second answer replaces the first", 2, again.size)
        assertEquals(
            AddOnAnswerValue.Rating(5),
            again.single { it.questionId == RATING }.value
        )
    }

    @Test
    fun `re-answering keeps createdAt and moves updatedAt`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        // An answer given earlier, written the way the table holds it.
        dao.insertAnswer(
            AddOnAnswer(
                questionId = RATING,
                type = AddOnQuestionType.RATING,
                value = AddOnAnswerValue.Rating(2),
                authoredAddOnId = LINEAGE,
                authoredAddOnVersion = 1,
                createdAt = 5,
                updatedAt = 5
            ).toEntity(taskId)
        )

        val before = System.currentTimeMillis()
        viewModel().setAddOnAnswer(taskId, RATING, AddOnAnswerValue.Rating(4)).join()

        val row = dao.answer(taskId, RATING)!!
        assertEquals("the row still says when the Task was first answered", 5, row.createdAt)
        assertTrue("the touch is recorded", row.updatedAt >= before)
        assertEquals("4", row.value)
    }

    @Test
    fun `clearing removes one answer and leaves the rest`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        val viewModel = viewModel()

        viewModel.setAddOnAnswer(taskId, YES_NO, AddOnAnswerValue.YesNo(true)).join()
        viewModel.setAddOnAnswer(taskId, TEXT, AddOnAnswerValue.Text("noted")).join()

        viewModel.clearAddOnAnswer(taskId, YES_NO).join()

        val left = viewModel.observeTaskAnswers(taskId).first()
        assertEquals(listOf(TEXT), left.map { it.questionId })
        assertEquals(AddOnAnswerValue.Text("noted"), left.single().value)
    }

    @Test
    fun `a question the attached version does not ask is refused`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        val viewModel = viewModel()

        // A page left open while the add-on was edited: the question id is gone from the version the
        // Task is stamped with, so the write has nothing to record it against.
        viewModel.setAddOnAnswer(taskId, "removed-question", AddOnAnswerValue.Rating(3)).join()

        assertEquals(emptyList<AddOnAnswer>(), viewModel.observeTaskAnswers(taskId).first())
    }

    @Test
    fun `a value that does not fit its type is refused`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        val viewModel = viewModel()

        viewModel.setAddOnAnswer(taskId, RATING, AddOnAnswerValue.YesNo(true)).join()
        viewModel.setAddOnAnswer(taskId, RATING, AddOnAnswerValue.Rating(6)).join()
        viewModel.setAddOnAnswer(taskId, TEXT, AddOnAnswerValue.Text("   ")).join()

        // The page answers blank text with a clear rather than a write, so blank reaching here is a
        // caller that bypassed it; storing it would make an unreadable row rather than an answer.
        assertEquals(emptyList<AddOnAnswer>(), viewModel.observeTaskAnswers(taskId).first())
    }

    @Test
    fun `deleting the Task takes its answers with it`() = runTest(dispatcher) {
        seedAddOn()
        val taskId = importedTask()
        val viewModel = viewModel()

        viewModel.setAddOnAnswer(taskId, RATING, AddOnAnswerValue.Rating(4)).join()
        assertEquals(1, viewModel.observeTaskAnswers(taskId).first().size)

        database.openHelper.writableDatabase.execSQL("DELETE FROM tasks WHERE id = '$taskId'")

        assertEquals(emptyList<AddOnAnswer>(), viewModel.observeTaskAnswers(taskId).first())
    }

    // --- reaching the states above --------------------------------------------------------------

    private suspend fun seedAddOn() {
        val write = database.addOnWriteDao()
        write.insertAddOn(AddOnEntity(id = ADD_ON, name = "Task review", createdAt = 0))
        write.insertVersion(
            AddOnVersionEntity(
                addOnId = ADD_ON,
                version = 1,
                questions = encodeAddOnQuestions(
                    listOf(
                        AddOnQuestion(RATING, AddOnQuestionType.RATING, "Overall rating"),
                        AddOnQuestion(YES_NO, AddOnQuestionType.YES_NO, "Is it a life photo?"),
                        AddOnQuestion(TEXT, AddOnQuestionType.TEXT, "Notes")
                    )
                ),
                questionsVersion = ADD_ON_QUESTIONS_VERSION,
                createdAt = 0
            )
        )
    }

    /** One imported Task, stamped with the add-on the way an import run stamps it. */
    private suspend fun importedTask(): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            addOn = "$ADD_ON\t1",
            now = 0
        )
        dao.runImport(
            source.id,
            "",
            plan(
                ImporterKind.FILE_AS_TASK,
                "",
                FolderSnapshot(
                    listOf(
                        ScannedFile(
                            name = "a.jpg",
                            relativePath = "a.jpg",
                            uri = "$ROOT/a.jpg"
                        )
                    ),
                    emptyList()
                )
            ),
            1000,
            addOn = "$ADD_ON\t1"
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    private fun viewModel() = ListsViewModel(
        application,
        dao,
        // Nothing here scans; the folder source exists only so the ViewModel can be built.
        FakeFolderSource(),
        dispatcher,
        TemplateStore(database),
        AddOnStore(database)
    )

    private companion object {
        const val ROOT = "content://root"
        const val ADD_ON = "addon"
        const val RATING = "q-rating"
        const val YES_NO = "q-yes-no"
        const val TEXT = "q-text"

        val LINEAGE = AddOnId(ADD_ON)
    }
}
