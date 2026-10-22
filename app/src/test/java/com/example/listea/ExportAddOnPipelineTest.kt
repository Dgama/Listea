package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ScannedFile
import com.example.listea.data.toEntity
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.encodeAddOnQuestions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The export path from the tables to the wire, without a hand-built view in the middle.
 *
 * `ExportAddOnTest` pins the shape a resolver and a row list produce; this pins that the database
 * actually produces those inputs — that `task_imports.addOn` is read by the Task projection, that
 * `task_review_answers` is read for the round, and that a Task stamped with a later version loses
 * the answer whose question that version dropped. The two failures this catches are the ones an
 * assembly test cannot: a column left out of a projection, and the wrong Task's rows handed to a
 * Task that happens to share a question id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportAddOnPipelineTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(application as Context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the stamped reference and the stored rows become the Task's addOn view`() = runBlocking {
        seed()
        val taskId = importedTask(stampedVersion = 1)
        dao.insertAnswer(
            AddOnAnswer(
                questionId = "q-rating",
                type = AddOnQuestionType.RATING,
                value = AddOnAnswerValue.Rating(4),
                authoredAddOnId = LINEAGE,
                authoredAddOnVersion = 1,
                createdAt = 10,
                updatedAt = 10
            ).toEntity(taskId)
        )

        val view = exportTasks(taskId).single().addOn!!

        assertEquals(LINEAGE.value, view.id)
        assertEquals("Task review", view.name)
        assertEquals(1, view.version)
        assertEquals(listOf("q-rating"), view.answers.map { it.questionId })
        assertEquals(AddOnAnswerValue.Rating(4), view.answers.single().value)
    }

    @Test
    fun `a Task stamped with a later version drops the question that version removed`() =
        runBlocking {
            seed()
            val taskId = importedTask(stampedVersion = 1)
            listOf(
                AddOnAnswer(
                    questionId = "q-rating",
                    type = AddOnQuestionType.RATING,
                    value = AddOnAnswerValue.Rating(4),
                    authoredAddOnId = LINEAGE,
                    authoredAddOnVersion = 1,
                    createdAt = 10,
                    updatedAt = 10
                ),
                AddOnAnswer(
                    questionId = "q-gone",
                    type = AddOnQuestionType.YES_NO,
                    value = AddOnAnswerValue.YesNo(true),
                    authoredAddOnId = LINEAGE,
                    authoredAddOnVersion = 1,
                    createdAt = 20,
                    updatedAt = 20
                )
            ).forEach { dao.insertAnswer(it.toEntity(taskId)) }

            // A refresh moved the Task to version 2, which asks only the rating.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE task_imports SET addOn = '$ADD_ON\t2' WHERE taskId = '$taskId'"
            )

            val view = exportTasks(taskId).single().addOn!!

            assertEquals(2, view.version)
            assertEquals(
                "the removed question is hidden, not sent",
                listOf("q-rating"),
                view.answers.map { it.questionId }
            )
        }

    @Test
    fun `a Task with no attachment exports no addOn object`() = runBlocking {
        seed()
        val taskId = importedTask(stampedVersion = 0)

        assertNull(exportTasks(taskId).single().addOn)
    }

    // --- reaching the states above --------------------------------------------------------------

    /** The payload rows and the resolver, read the way delivery reads them. */
    private suspend fun exportTasks(taskId: String) = exportTaskViews(
        rows = listOf(
            ListItemEntity(
                id = 1,
                publicId = "ROW-1",
                listId = 1,
                taskId = taskId,
                title = "a.jpg",
                isCompleted = true,
                sortOrder = 0,
                createdAt = 0,
                sourceUri = null,
                sourceRelativePath = null,
                rootRelativePath = null,
                selections = ""
            )
        ),
        tasks = dao.getPayloadTasks(listOf(taskId)).associateBy { it.taskId },
        assetsByTask = emptyMap(),
        resolver = TemplateResolver.Empty,
        addOnResolver = AddOnResolver.of(
            database.addOnDao().addOnsOnce(),
            database.addOnDao().versionsOnce()
        ),
        answersByTask = dao.getPayloadAnswers(listOf(taskId)).groupBy { it.taskId }
    )

    /**
     * One lineage with two versions. Version 2 drops `q-gone` and keeps the rating — exactly what an
     * edit to the questionnaire appends.
     */
    private suspend fun seed() {
        val write = database.addOnWriteDao()
        write.insertAddOn(AddOnEntity(id = ADD_ON, name = "Task review", createdAt = 0))
        write.insertVersion(
            AddOnVersionEntity(
                addOnId = ADD_ON,
                version = 1,
                questions = encodeAddOnQuestions(
                    listOf(
                        AddOnQuestion("q-rating", AddOnQuestionType.RATING, "Overall rating"),
                        AddOnQuestion("q-gone", AddOnQuestionType.YES_NO, "Still a life photo?")
                    )
                ),
                questionsVersion = ADD_ON_QUESTIONS_VERSION,
                createdAt = 0
            )
        )
        write.insertVersion(
            AddOnVersionEntity(
                addOnId = ADD_ON,
                version = 2,
                questions = encodeAddOnQuestions(
                    listOf(AddOnQuestion("q-rating", AddOnQuestionType.RATING, "Overall rating"))
                ),
                questionsVersion = ADD_ON_QUESTIONS_VERSION,
                createdAt = 1
            )
        )
    }

    /** One imported Task; [stampedVersion] 0 means the Todo carried no add-on. */
    private suspend fun importedTask(stampedVersion: Int): String {
        val encoded = if (stampedVersion == 0) "" else "$ADD_ON\t$stampedVersion"
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            addOn = encoded,
            now = 0
        )
        dao.runImport(
            source.id,
            "",
            plan(
                ImporterKind.FILE_AS_TASK,
                "",
                FolderSnapshot(
                    listOf(ScannedFile(name = "a.jpg", relativePath = "a.jpg", uri = "$ROOT/a.jpg")),
                    emptyList()
                )
            ),
            1000,
            addOn = encoded
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    private companion object {
        const val ROOT = "content://root"
        const val ADD_ON = "addon"
        val LINEAGE = AddOnId(ADD_ON)
    }
}
