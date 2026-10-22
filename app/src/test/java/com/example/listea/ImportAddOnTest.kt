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
import com.example.listea.data.SourceEntity
import com.example.listea.data.TemplateStore
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
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
 * What an import does with the questionnaire the dialog chose.
 *
 * Three writes have to agree about one run, and this is where that is held down: the Todo's
 * `sources.addOn` is what the next dialog opens on, and every `task_imports.addOn` this run touches
 * is what each Task's review page reads. The dialog collects a reference and the run encodes it
 * once, so the two columns cannot be written from two spellings.
 *
 * The refresh and the sync are the counter-cases the template tests also pin. A refresh was asked
 * and adopts the offered version, because leaving the tick alone is the instruction; a sync was not
 * asked and must re-stamp exactly what the Todo already names. And an unreadable reference is
 * refused only where it could do harm — a Create, which has no existing row to preserve — because a
 * refresh carrying a questionnaire written by a newer build must still bring in material.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportAddOnTest {

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

    // --- the run that was asked ------------------------------------------------------------------

    @Test
    fun `a Create stamps the chosen add-on on the Todo and on every Task`() = runTest(dispatcher) {
        seed(ADD_ON, version = 1)
        val viewModel = viewModel(PICKED)
        val source = existingTodo()

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        val confirm = viewModel.confirm()

        // The picker offers the questionnaire; the draft starts at None, because an add-on is a
        // choice and the ordinary Task has no page after its last file.
        assertEquals(listOf(ADD_ON), confirm.addOns.map { it.ref.addOnId.value })
        assertEquals(null, confirm.options.addOn)

        viewModel.confirmImportFolder(
            confirm.options.copy(addOn = confirm.addOns.single().ref)
        )
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        val created = dao.sourcesAt(source.rootUri, source.relativePath)
            .single { it.id != source.id }
        assertEquals("$ADD_ON\t1", created.addOn)
        assertEquals("$ADD_ON\t1", stampedAddOns(created.id))
    }

    @Test
    fun `an untouched refresh carries the Todo and its Tasks to the offered version`() =
        runTest(dispatcher) {
            seed(ADD_ON, version = 1)
            seed(ADD_ON, version = 2)
            val viewModel = viewModel(PICKED)
            val source = todo(addOn = "$ADD_ON\t1")

            viewModel.requestSourceRefresh(source.id)
            val confirm = viewModel.confirm()

            // The dialog opens on what the Todo holds, so confirming without touching it moves it.
            assertEquals(AddOnVersionRef(AddOnId(ADD_ON), 1), confirm.options.addOn)
            assertTrue(confirm.options.upgradeAddOn)

            viewModel.confirmImportFolder(confirm.options)
            assertTrue(viewModel.finished() is ImportFolderRequest.Report)

            assertEquals("$ADD_ON\t2", dao.getSource(source.id)!!.addOn)
            assertEquals("$ADD_ON\t2", stampedAddOns(source.id))
        }

    @Test
    fun `unticking an add-on clears it from the Todo and its Tasks`() = runTest(dispatcher) {
        seed(ADD_ON, version = 1)
        val viewModel = viewModel(PICKED)
        val source = todo(addOn = "$ADD_ON\t1")

        viewModel.requestSourceRefresh(source.id)
        val confirm = viewModel.confirm()

        // What the dialog does when the chosen row is chosen again: the reference leaves the draft,
        // and the run must not put it back merely because the Todo used to hold it.
        viewModel.confirmImportFolder(confirm.options.copy(addOn = null))
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        assertEquals("", dao.getSource(source.id)!!.addOn)
        assertEquals("", stampedAddOns(source.id))
    }

    // --- the sync that was not asked -------------------------------------------------------------

    @Test
    fun `a follow-the-upstream sync keeps the version the Todo already holds`() = runTest(dispatcher) {
        seed(ADD_ON, version = 1)
        seed(ADD_ON, version = 2)
        val viewModel = viewModel(PICKED)
        // No import yet, so `sourceHasChanges` says there is everything to do and the sync runs.
        val source = todo(addOn = "$ADD_ON\t1", followUpstream = true)

        viewModel.syncTodoUpstream(source.id)
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        assertEquals("$ADD_ON\t1", dao.getSource(source.id)!!.addOn)
        assertEquals("$ADD_ON\t1", stampedAddOns(source.id))
    }

    // --- unreadable references -------------------------------------------------------------------

    @Test
    fun `a Create naming an add-on this build cannot read writes nothing`() = runTest(dispatcher) {
        seed(ADD_ON, version = 1, questionsVersion = 99)
        val viewModel = viewModel(PICKED)
        val source = existingTodo()

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        val confirm = viewModel.confirm()
        // Not offered, because the resolver skips what it cannot decode.
        assertEquals(0, confirm.addOns.size)

        // A caller that bypassed the picker: there is no row to preserve and no page this build
        // could draw, so the run refuses before registering anything.
        viewModel.confirmImportFolder(
            confirm.options.copy(addOn = AddOnVersionRef(AddOnId(ADD_ON), 1))
        )
        val finished = viewModel.finished()

        assertTrue(finished is ImportFolderRequest.Error)
        assertTrue((finished as ImportFolderRequest.Error).message.contains("Nothing was changed"))
        assertEquals(1, dao.sourcesAt(source.rootUri, source.relativePath).size)
        assertTrue(dao.getCatalogTasks(source.id).isEmpty())
    }

    @Test
    fun `a refresh carrying an unreadable held add-on still imports`() = runTest(dispatcher) {
        seed(ADD_ON, version = 1, questionsVersion = 99)
        val viewModel = viewModel(PICKED)
        val source = todo(addOn = "$ADD_ON\t1")

        viewModel.requestSourceRefresh(source.id)
        viewModel.confirmImportFolder(viewModel.confirm().options)

        // One unreadable questionnaire is not an import outage: the reference was already on the
        // row, nothing offered can replace it, and the Tasks are re-stamped with what it holds.
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)
        assertEquals("$ADD_ON\t1", dao.getSource(source.id)!!.addOn)
        assertEquals("$ADD_ON\t1", stampedAddOns(source.id))
    }

    // --- reaching the states above --------------------------------------------------------------

    private suspend fun stampedAddOns(sourceId: String): String {
        val tasks = dao.getCatalogTasks(sourceId)
        assertTrue("the run must have produced Tasks to stamp", tasks.isNotEmpty())
        val stamped = dao.getAssetControls(tasks.map { it.taskId })
        assertEquals(tasks.size, stamped.size)
        assertTrue(
            "every Task this run touched carries the same reference",
            stamped.map { it.addOn }.distinct().size == 1
        )
        return stamped.first().addOn
    }

    private suspend fun todo(
        addOn: String = "",
        followUpstream: Boolean = false
    ): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = "Photos",
        importerKind = ImporterKind.FILE_AS_TASK,
        addOn = addOn,
        followUpstream = followUpstream,
        now = 0
    )

    private suspend fun existingTodo(): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = "Picked folder",
        importerKind = ImporterKind.FILE_AS_TASK,
        keepTree = true,
        now = 0
    )

    /**
     * One version of a lineage, as the add-on tables hold it.
     *
     * The lineage row is written only for version 1: a second version of a lineage already there is
     * exactly what an edit appends, and inserting it twice would fail on the schema rather than on
     * what the test asked.
     */
    private suspend fun seed(
        lineage: String,
        version: Int,
        questionsVersion: Int = ADD_ON_QUESTIONS_VERSION
    ) {
        val write = database.addOnWriteDao()
        if (version == 1) {
            write.insertAddOn(AddOnEntity(id = lineage, name = "Review", createdAt = 0))
        }
        write.insertVersion(
            AddOnVersionEntity(
                addOnId = lineage,
                version = version,
                questions = encodeAddOnQuestions(
                    listOf(
                        AddOnQuestion(
                            id = "q1",
                            type = AddOnQuestionType.RATING,
                            prompt = "Overall rating"
                        )
                    )
                ),
                questionsVersion = questionsVersion,
                createdAt = 0
            )
        )
    }

    private suspend fun ListsViewModel.confirm(): ImportFolderRequest.Confirm =
        importFolderRequest.first { it is ImportFolderRequest.Confirm } as ImportFolderRequest.Confirm

    private suspend fun ListsViewModel.finished(): ImportFolderRequest =
        importFolderRequest.first {
            it is ImportFolderRequest.Report || it is ImportFolderRequest.Error
        }!!

    private fun viewModel(folders: FolderSource) =
        ListsViewModel(
            application,
            dao,
            folders,
            dispatcher,
            TemplateStore(database),
            AddOnStore(database)
        )

    private companion object {
        const val ROOT = "content://root"
        const val ADD_ON = "addon"

        val PICKED = FakeFolderSource(
            rootName = "2026-09-11",
            files = listOf(
                FolderFile("a.jpg", "content://root/doc/a.jpg", "2026-09-11/a.jpg"),
                FolderFile("b.jpg", "content://root/doc/b.jpg", "2026-09-11/b.jpg")
            )
        )
    }
}
