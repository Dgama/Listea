package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.SourceEntity
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.decodeExportChoice
import com.example.listea.domain.encodeExportChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a Todo's export choice gets into the column, and how it stays there.
 *
 * The choice is a **property of a Todo**, set at import and read at every round afterwards, so the
 * interesting failures are all about a write path that forgets it rather than about the model — the
 * model is `ExportChoiceTest`'s. There are three ways a Todo's row is written and each one is here:
 * the import that creates it ([ListsDao.registerSource]), the run that configures it
 * ([ListsDao.configureSource]), and the **upstream sync that re-runs an existing Todo without a
 * dialog**.
 *
 * That last one is the regression this file exists for. `syncTodoUpstream` builds its `ImportOptions`
 * from the stored `Source` rather than from a dialog, and `applyImport` writes the options it was
 * handed back into the row — so a sync that did not read `exportKind` would reset every Todo it
 * touched to the column default. The user's choice would evaporate as a side effect of following an
 * upstream, on the one path nobody is watching, which is why it is named in
 * [the plan](v0.5.6-plan.md) §8 as an omission that is a bug rather than a default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportRoutingTest {

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

    // --- the column, and its default ------------------------------------------------------------

    /**
     * A Todo that has never been asked exports nothing.
     *
     * The default matters more here than most: the other candidate is *webhook*, which is what the
     * app did before it had a choice, and defaulting to it would mean a Todo born without an answer
     * starts posting to the user's URL. [registerSource]'s default is the failing-closed one, and
     * this is what says it is.
     */
    @Test
    fun `a Todo registered without a choice exports nothing`() = runTest(dispatcher) {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Holiday",
            importerKind = ImporterKind.FILE_AS_TASK,
            now = 0
        )

        assertEquals("none", source.exportKind)
        assertEquals(ExportChoice.Nothing, decodeStored(source.exportKind))
    }

    @Test
    fun `a Todo registered with a choice keeps it`() = runTest(dispatcher) {
        ExportKind.entries.forEach { kind ->
            val source = dao.registerSource(
                rootUri = ROOT,
                relativePath = kind.key,
                displayName = kind.label,
                importerKind = ImporterKind.FILE_AS_TASK,
                exportKind = encodeExportChoice(ExportChoice.By(kind)),
                now = 0
            )

            assertEquals(kind.key, source.exportKind)
            assertEquals(ExportChoice.By(kind), decodeStored(source.exportKind))
        }
    }

    // --- the import that writes it --------------------------------------------------------------

    /**
     * The import dialog's answer reaches the row.
     *
     * `confirmImportFolder` hands the options to [ListsViewModel.applyImport], which registers the
     * Todo for the choice among everything else. This is the Create Tasks path end to end, so a
     * dropped field is caught where it would actually be dropped.
     */
    @Test
    fun `an import writes the choice the dialog collected`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)

        viewModel.requestCreateTasks(ROOT, "")
        viewModel.confirm()
        viewModel.confirmImportFolder(
            ImportOptions(
                importer = ImporterKind.FILE_AS_TASK,
                displayName = "Holiday",
                keepTree = true,
                exportChoice = ExportChoice.By(ExportKind.TXT)
            )
        )
        viewModel.finished()

        val created = dao.sourcesAt(ROOT, "").single()
        assertEquals("txt", created.exportKind)
    }

    @Test
    fun `an import that chose nothing writes nothing`() = runTest(dispatcher) {
        // The word, not an empty string. `""` is spoken for by `sources.assetControls`, where it
        // means *nothing was ever chosen*, and a column that spelled two different answers the same
        // way is one a reader has to know the name of to interpret.
        val viewModel = viewModel(PICKED)

        viewModel.requestCreateTasks(ROOT, "")
        viewModel.confirm()
        viewModel.confirmImportFolder(
            ImportOptions(
                importer = ImporterKind.FILE_AS_TASK,
                displayName = "Holiday",
                keepTree = true,
                exportChoice = ExportChoice.Nothing
            )
        )
        viewModel.finished()

        assertEquals("none", dao.sourcesAt(ROOT, "").single().exportKind)
    }

    // --- the refresh that opens on it -----------------------------------------------------------

    /**
     * A Refresh opens on the Todo's stored answer rather than on a default.
     *
     * The dialog offers no way to change the choice — a refresh is about a Todo that exists and has
     * already said how it exports — so what it shows is what the row holds. A dialog that opened on
     * *nothing* would write that back the moment the user pressed the only button on it.
     */
    @Test
    fun `a refresh opens on the choice the Todo already made`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = todo(exportKind = encodeExportChoice(ExportChoice.By(ExportKind.TXT)))

        viewModel.requestSourceRefresh(source.id)

        assertEquals(
            ExportChoice.By(ExportKind.TXT),
            viewModel.confirm().options.exportChoice
        )
    }

    @Test
    fun `a refresh of a Todo that chose nothing opens on nothing`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = todo(exportKind = encodeExportChoice(ExportChoice.Nothing))

        viewModel.requestSourceRefresh(source.id)

        assertEquals(ExportChoice.Nothing, viewModel.confirm().options.exportChoice)
    }

    /**
     * `inherit` is a List's word, and a Todo that somehow holds it opens on *nothing*.
     *
     * Not a shape the app writes — the import dialog offers three answers and none of them is
     * *inherit* — but a row is a row, and the failing-closed reading is the one that must not become
     * an export. `inheritable = false` is the whole of the rule, and this is the end-to-end version
     * of the unit test beside it.
     */
    @Test
    fun `a Todo holding the List's word opens on nothing`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = todo(exportKind = "inherit")

        viewModel.requestSourceRefresh(source.id)

        assertEquals(ExportChoice.Nothing, viewModel.confirm().options.exportChoice)
    }

    // --- the sync that must not forget it -------------------------------------------------------

    /**
     * **The regression case.** A run nobody was asked about re-uses the answer the Todo already
     * gave, rather than re-deciding it.
     *
     * `syncTodoUpstream` is the one write path with no dialog, so it is the one where a forgotten
     * field is invisible: no screen shows the choice at that moment, and the user finds out at the
     * next review. The run is asserted to have really happened — a sync that returned early would
     * pass this test for the wrong reason.
     */
    @Test
    fun `an upstream sync keeps the choice the Todo already made`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        // Registered straight to the table rather than imported: `sourceHasChanges` treats a Todo
        // with no imports as having everything still to do, which is what makes the sync run.
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Holiday",
            importerKind = ImporterKind.FILE_AS_TASK,
            followUpstream = true,
            exportKind = encodeExportChoice(ExportChoice.By(ExportKind.TXT)),
            now = 0
        )

        viewModel.syncTodoUpstream(source.id)
        val report = viewModel.finished()

        assertTrue("the sync did not run, so this proves nothing", report is ImportFolderRequest.Report)
        assertEquals("txt", dao.getSource(source.id)!!.exportKind)
    }

    @Test
    fun `an upstream sync of a Todo that chose nothing keeps it at nothing`() = runTest(dispatcher) {
        // The other direction, and it is not the same test: a sync that fell back to a kind would
        // pass the one above and fail this one.
        val viewModel = viewModel(PICKED)
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Holiday",
            importerKind = ImporterKind.FILE_AS_TASK,
            followUpstream = true,
            exportKind = encodeExportChoice(ExportChoice.Nothing),
            now = 0
        )

        viewModel.syncTodoUpstream(source.id)
        viewModel.finished()

        assertEquals("none", dao.getSource(source.id)!!.exportKind)
    }

    // --- the kind's own flags, which need a Context to reach --------------------------------------

    /**
     * The pair of flags that makes the dispatcher's loop total.
     *
     * `asksBeforeDelivering` false is why a text export never raises the confirmation dialog — a
     * write into the user's own album is neither outward-facing nor hard to undo, and asking every
     * round would train the user to tap through the one dialog that matters. `deliveredOutcome` is
     * what puts *Txt written* in the notice and `WRITTEN` in the history rather than `SENT`.
     *
     * Asserted here rather than in `TxtExportTest` because constructing a `TxtExporter` takes a
     * `Context`, and that file is deliberately pure.
     */
    @Test
    fun `the txt kind writes without asking, and reports WRITTEN`() {
        val exporter = TxtExporter(application)

        assertEquals(ExportKind.TXT, exporter.kind)
        assertFalse(exporter.asksBeforeDelivering)
        assertEquals(NoticeOutcome.WRITTEN, exporter.deliveredOutcome)
    }

    /**
     * `exporterFor` is a `when` over the enum, and null means *there is nothing for this to deliver
     * to* rather than an exporter that will fail.
     *
     * The webhook needs a URL and there is no longer any other place one could come from — the
     * per-List URL is gone — so an empty default is the whole of the *not configured* state. The txt
     * kind needs nowhere to go, so it is never null, and that asymmetry is why the caller records
     * [ExportAttempt.Skipped] rather than asking each kind whether it is in a position to act.
     */
    @Test
    fun `a webhook with nowhere to go has no exporter, and txt always does`() {
        assertEquals(ExportKind.WEBHOOK, exporterFor(ExportKind.WEBHOOK, "https://example.invalid", application)?.kind)
        assertNull(exporterFor(ExportKind.WEBHOOK, "", application))
        assertNull(exporterFor(ExportKind.WEBHOOK, "   ", application))
        assertNotNull(exporterFor(ExportKind.TXT, "", application))
    }

    // --- reaching the states above --------------------------------------------------------------

    private fun decodeStored(word: String): ExportChoice = decodeExportChoice(word, inheritable = false)

    /** A Todo over the Upstream's own folder, written straight to the table. */
    private suspend fun todo(exportKind: String): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = "Picked folder",
        importerKind = ImporterKind.FILE_AS_TASK,
        exportKind = exportKind,
        now = 0
    )

    /** The confirmation the run reached, which is the only thing that carries the options. */
    private suspend fun ListsViewModel.confirm(): ImportFolderRequest.Confirm =
        importFolderRequest.first { it is ImportFolderRequest.Confirm } as ImportFolderRequest.Confirm

    /** Waits for the run to have ended, either way. */
    private suspend fun ListsViewModel.finished(): ImportFolderRequest =
        importFolderRequest.first {
            it is ImportFolderRequest.Report || it is ImportFolderRequest.Error
        }!!

    private fun viewModel(folders: FolderSource) =
        ListsViewModel(application, dao, folders, dispatcher)

    private companion object {
        const val ROOT = "content://root"

        /** A picked folder holding two files, so a run over it always has something to do. */
        val PICKED = FakeFolderSource(
            rootName = "2026-09-11",
            files = listOf(
                FolderFile("a.jpg", "content://root/doc/a.jpg", "2026-09-11/a.jpg"),
                FolderFile("b.jpg", "content://root/doc/b.jpg", "2026-09-11/b.jpg")
            )
        )
    }
}
