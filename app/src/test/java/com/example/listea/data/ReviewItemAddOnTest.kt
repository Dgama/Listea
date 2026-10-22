package com.example.listea.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.AddOnResolver
import com.example.listea.FakeFolderSource
import com.example.listea.FolderReview
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.ListsViewModel
import com.example.listea.childRelativePath
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.encodeAddOnQuestions
import com.example.listea.plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a List reads when only some of its Tasks came from a Todo with an add-on.
 *
 * The user's case in one test: a List mixes Todos, and a questionnaire follows the *Task* rather
 * than the List. A List-wide answer would give every item a page or none; the row that decides is
 * `task_imports.addOn`, read beside `task_imports.assetControls` and folded onto the item the same
 * way. [ListsViewModel.observeDetail] is driven rather than a hand-built map, because the mapping
 * between the two queries is the thing that could silently pair the wrong Task's reference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewItemAddOnTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(application as Context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a mixed List reads some Tasks with an add-on and some without`() = runTest(dispatcher) {
        seedAddOn(ADD_ON)

        val withAddOn = todo(path = "photos", addOn = "$ADD_ON\t1")
        val without = todo(path = "clips", addOn = "")
        val listId = dao.createListFromTasks(
            title = "Trip",
            taskIds = dao.getCatalogTasks(withAddOn).map { it.taskId } +
                dao.getCatalogTasks(without).map { it.taskId },
            exportOnCompletion = false,
            now = 3000
        )

        val detail = viewModel().observeDetail(listId).first()!!
        val resolver = resolver()

        // One Task's page is drawable, the other Task simply has none — the questionnaire follows
        // the Todo that imported the Task, not the List that shows it.
        assertEquals(1, detail.items.count { it.addOn == "$ADD_ON\t1" })
        assertEquals(1, detail.items.count { it.addOn == "" })
        assertNotNull(resolver.versionFor("$ADD_ON\t1"))
        assertNull(resolver.versionFor(""))
        assertTrue(
            "every item still knows its own control column",
            detail.items.all { it.assetControls == "" }
        )
    }

    @Test
    fun `an item no Todo owns reads as no page`() = runTest(dispatcher) {
        // The manual shape: a List item with no `task_imports` row at all. `orEmpty()` in
        // `observeDetail` is the answer here rather than a defensive default.
        val source = todo(path = "photos", addOn = "")
        val listId = dao.createListFromTasks(
            title = "Trip",
            taskIds = dao.getCatalogTasks(source).map { it.taskId },
            exportOnCompletion = false,
            now = 3000
        )
        database.writeLegacyManualItem(listId = listId, title = "Buy milk", now = 4000)

        val detail = viewModel().observeDetail(listId).first()!!
        val manual = detail.items.single { it.title == "Buy milk" }

        assertEquals("", manual.addOn)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    @Test
    fun `a Todo round carries the add-on its Tasks were imported with`() = runTest(dispatcher) {
        // The defect a device walk found, end to end: the catalog projection did not select
        // `task_imports.addOn`, so a Quick Review built from catalog rows had every item pageless
        // and swiping off the last Asset went to the next Task — while the same Task opened from a
        // List did show its page. This drives the SQL, the row and the queue's mapping in one go;
        // `observeDetail` above could not have caught it, because it reads the column separately.
        seedAddOn(ADD_ON)
        val withAddOn = todo(path = "photos", addOn = "$ADD_ON\t1")

        val round = viewModel().observeTaskReview(withAddOn, ROOT, "photos").first()

        assertEquals(
            listOf("$ADD_ON\t1"),
            (round as FolderReview.Ready).items.map { it.addOn }
        )
    }

    private suspend fun seedAddOn(lineage: String) {
        database.addOnWriteDao().insertAddOn(
            AddOnEntity(id = lineage, name = "Task review", createdAt = 0)
        )
        database.addOnWriteDao().insertVersion(
            AddOnVersionEntity(
                addOnId = lineage,
                version = 1,
                questions = encodeAddOnQuestions(
                    listOf(AddOnQuestion("q1", AddOnQuestionType.RATING, "Overall rating"))
                ),
                questionsVersion = ADD_ON_QUESTIONS_VERSION,
                createdAt = 0
            )
        )
    }

    private suspend fun todo(path: String, addOn: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = path,
            displayName = path,
            importerKind = ImporterKind.FILE_AS_TASK,
            addOn = addOn,
            now = 1000
        )
        dao.runImport(source.id, path, filePlan(path, listOf("a.jpg")), 2000, addOn = addOn)
        return source.id
    }

    private suspend fun resolver(): AddOnResolver = AddOnResolver.of(
        database.addOnDao().addOnsOnce(),
        database.addOnDao().versionsOnce()
    )

    private fun filePlan(importRoot: String, names: List<String>) = plan(
        ImporterKind.FILE_AS_TASK,
        importRoot,
        FolderSnapshot(names.map { scanned(importRoot, it) }, emptyList())
    )

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    private fun viewModel() = ListsViewModel(
        application,
        dao,
        // No screen here scans; the folder source exists only so the ViewModel can be built.
        FakeFolderSource(),
        dispatcher,
        TemplateStore(database),
        AddOnStore(database)
    )

    private companion object {
        const val ROOT = "content://root"
        const val ADD_ON = "addon"
    }
}
