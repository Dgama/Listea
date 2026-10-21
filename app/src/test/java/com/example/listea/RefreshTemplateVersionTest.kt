package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.SourceEntity
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateStore
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
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
 * What a Refresh Import does with a template that has moved on.
 *
 * The dialog already promised it: a row the Todo holds on an older version is ticked and says
 * *Held at version 1. This run will attach version 2.* Before v0.6.2 the promise was kept by the
 * tick alone — the run wrote whatever reference the dialog was holding, which was the old one — so
 * a user who confirmed without touching anything re-stamped every Task with the version it already
 * had, and the template edit never reached the Todo. These tests pin the whole write path: the
 * picker seeds the held reference, the ViewModel resolves the offered version, and both the Todo's
 * row and `task_imports.assetControls` end on it.
 *
 * The counter-case is the upstream sync, which is a refresh nobody was asked about. It must keep the
 * version the Todo already holds, for the same reason it must keep the export choice: a run with no
 * dialog may not move a standing answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshTemplateVersionTest {

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

    // --- the refresh that was asked ---------------------------------------------------------------

    @Test
    fun `an untouched refresh carries the Todo and its Tasks to the offered version`() =
        runTest(dispatcher) {
            seed(LINEAGE, version = 1)
            seed(LINEAGE, version = 2)
            val viewModel = viewModel(PICKED)
            val source = todo()

            viewModel.requestSourceRefresh(source.id)
            val confirm = viewModel.confirm()

            // The dialog opens on what the Todo holds, so confirming without touching it is the
            // instruction to move it — not a second choice of the same version.
            assertEquals(
                listOf(TemplateVersionRef(TemplateId(LINEAGE), 1)),
                confirm.options.templates
            )
            assertTrue(confirm.options.upgradeTemplates)

            viewModel.confirmImportFolder(confirm.options)
            assertTrue(viewModel.finished() is ImportFolderRequest.Report)

            assertEquals("$LINEAGE\t2", dao.getSource(source.id)!!.assetControls)
            val tasks = dao.getCatalogTasks(source.id)
            assertTrue("the run must have produced Tasks to stamp", tasks.isNotEmpty())
            assertTrue(
                "every Task this run touched carries the offered version",
                tasks.all { it.assetControls == "$LINEAGE\t2" }
            )
        }

    @Test
    fun `unticking a held template removes it instead of re-attaching it`() = runTest(dispatcher) {
        seed(LINEAGE, version = 1)
        seed(LINEAGE, version = 2)
        seed(OTHER, version = 1)
        val viewModel = viewModel(PICKED)
        val source = todo(assetControls = "$LINEAGE\t1\n$OTHER\t1")

        viewModel.requestSourceRefresh(source.id)
        val confirm = viewModel.confirm()
        assertEquals(2, confirm.options.templates.size)

        // What the dialog does when the row is unticked: the lineage leaves the draft, and the run
        // must not put it back merely because the Todo used to hold it.
        val withoutLineage = confirm.options.copy(
            templates = confirm.options.templates.filter { it.templateId != TemplateId(LINEAGE) }
        )
        viewModel.confirmImportFolder(withoutLineage)
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        assertEquals("$OTHER\t1", dao.getSource(source.id)!!.assetControls)
        assertTrue(
            dao.getCatalogTasks(source.id).all { it.assetControls == "$OTHER\t1" }
        )
    }

    // --- the sync that was not asked --------------------------------------------------------------

    @Test
    fun `a follow-the-upstream sync keeps the version the Todo already holds`() = runTest(dispatcher) {
        seed(LINEAGE, version = 1)
        seed(LINEAGE, version = 2)
        val viewModel = viewModel(PICKED)
        // No import yet, so `sourceHasChanges` says there is everything to do and the sync really
        // runs — a sync that returned early would pass this for the wrong reason.
        val source = todo(followUpstream = true)

        viewModel.syncTodoUpstream(source.id)
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        assertEquals("$LINEAGE\t1", dao.getSource(source.id)!!.assetControls)
        val tasks = dao.getCatalogTasks(source.id)
        assertTrue("the sync must have produced Tasks to stamp", tasks.isNotEmpty())
        assertTrue(tasks.all { it.assetControls == "$LINEAGE\t1" })
    }

    // --- reaching the states above ----------------------------------------------------------------

    private suspend fun todo(
        assetControls: String = "$LINEAGE\t1",
        followUpstream: Boolean = false
    ): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = "Photos",
        importerKind = ImporterKind.FILE_AS_TASK,
        assetControls = assetControls,
        followUpstream = followUpstream,
        now = 0
    )

    /**
     * One version of a lineage, as the template tables hold it.
     *
     * The lineage row is written only for version 1, because a second version of a lineage already
     * there is exactly what an edit appends — and inserting it twice would fail on the schema rather
     * than on what the test asked.
     */
    private suspend fun seed(lineage: String, version: Int) {
        val write = database.templateWriteDao()
        if (version == 1) {
            write.insertTemplate(TemplateEntity(id = lineage, name = lineage, createdAt = 0))
        }
        write.insertVersion(
            TemplateVersionEntity(
                templateId = lineage,
                version = version,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.IMAGE))),
                buttons = encodeTemplateButtons(listOf(button)),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
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
        ListsViewModel(application, dao, folders, dispatcher, TemplateStore(database))

    private val button = TemplateButton(
        id = "custom1",
        name = "C1",
        type = ButtonType.TAG,
        recordValue = "cust1"
    )

    private companion object {
        const val ROOT = "content://root"
        const val LINEAGE = "tpl"
        const val OTHER = "other"

        val PICKED = FakeFolderSource(
            rootName = "2026-09-11",
            files = listOf(
                FolderFile("a.jpg", "content://root/doc/a.jpg", "2026-09-11/a.jpg"),
                FolderFile("b.jpg", "content://root/doc/b.jpg", "2026-09-11/b.jpg")
            )
        )
    }
}
