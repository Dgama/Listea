package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.DissolvedHistory
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.SourceEntity
import com.example.listea.data.SourceSummary
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
 * What the two questions an Upstream's card leads to actually decide.
 *
 * The first is what the folder is called. A picked folder's own relative path is empty — the grant
 * root *is* the folder — so the only name the app can derive from its own vocabulary is the
 * placeholder "Picked folder"; every Upstream added by picking would have been called that, on the
 * card and in the Name field of the dialog that makes its Tasks. The provider has to be asked.
 *
 * The second is what a run stores. An Upstream exists before it has ever been imported, so the row
 * the run finds is one [ListsDao.registerSource] returns untouched — it reuses an exact match by
 * design, because most callers want the row that is already there and pass placeholders for
 * everything else. A dialog whose grouping, shape and name were therefore written nowhere would be
 * a dialog that asked three questions and acted on none of them, and the Untick "keep folder tree"
 * case would show a Todo with folders in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateTasksConfigTest {

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

    // --- what the folder is called -----------------------------------------------------------

    @Test
    fun `an Upstream is named after the folder that was picked`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)

        viewModel.registerUpstream(ROOT, "")
        val added = viewModel.newUpstream.first { it != null }!!

        // Read straight off the row the pick wrote. It used to be read back through the Source the
        // pick also created; since v16 it does not create one, and the name is the Upstream's own.
        assertEquals("2026-09-11", added.displayName)
    }

    @Test
    fun `an Upstream whose folder cannot be named falls back to the placeholder`() =
        runTest(dispatcher) {
            val viewModel = viewModel(UNNAMED)

            viewModel.registerUpstream(ROOT, "")
            val added = viewModel.newUpstream.first { it != null }!!

            assertEquals("Picked folder", added.displayName)
        }

    @Test
    fun `registering an Upstream writes no Todo`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)

        viewModel.registerUpstream(ROOT, "")
        viewModel.newUpstream.first { it != null }

        // The v16 line, and the whole of point 3: adding a folder says where material is and
        // nothing else. A Todo is what a run makes of one, so a folder that has never been read
        // has no row here and appears on no tab until it has.
        assertEquals(emptyList<SourceSummary>(), dao.observeSourceSummaries().first())
    }

    @Test
    fun `Create Tasks over an Upstream's own folder opens on the Upstream's name`() =
        runTest(dispatcher) {
            val viewModel = viewModel(PICKED)
            val source = existingTodo()
            // The user renames it, as Create Tasks lets them: the next run must offer the name the
            // card is showing, not the folder's, or the Tasks it creates would come out under a name
            // nothing on screen had used.
            dao.configureSource(
                source.id,
                "Holiday photos",
                ImporterKind.FILE_AS_TASK,
                keepTree = true
            )

            viewModel.requestCreateTasks(source.rootUri, source.relativePath)

            assertEquals("Holiday photos", viewModel.confirm().options.displayName)
        }

    @Test
    fun `Create Tasks started inside an Upstream is named after the folder it is about`() =
        runTest(dispatcher) {
            val viewModel = viewModel(PICKED)
            val source = existingTodo()

            viewModel.requestCreateTasks(source.rootUri, "bilibili")

            assertEquals("bilibili", viewModel.confirm().options.displayName)
        }

    // --- what the run stores ------------------------------------------------------------------

    /** §3, §6 — the answer that decides whether the Todo opens as a tree or as one list. */
    @Test
    fun `a run stores the name, grouping and shape it was given`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = existingTodo()

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        viewModel.confirm()
        viewModel.confirmImportFolder(
            ImportOptions(
                importer = ImporterKind.FILE_AS_TASK,
                displayName = "Holiday",
                keepTree = false
            )
        )
        viewModel.finished()

        // Create Tasks made its own Todo rather than rewriting the one already there: the two are
        // different intentions over one folder, and a run that reconfigured the first would be
        // editing an intention the user never named.
        val created = dao.sourcesAt(source.rootUri, source.relativePath)
            .single { it.id != source.id }
        assertEquals("Holiday", created.displayName)
        assertEquals(ImporterKind.FILE_AS_TASK.key, created.importerKind)
        assertEquals(false, created.keepTree)
        // And what it stood beside is untouched, name included.
        assertEquals("Picked folder", dao.getSource(source.id)!!.displayName)
    }

    @Test
    fun `a folder-as-Task run stores the grouping it was given`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = existingTodo()

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        viewModel.confirm()
        viewModel.confirmImportFolder(
            ImportOptions(
                importer = ImporterKind.FOLDER_AS_TASK,
                displayName = "Holiday",
                // Whatever the dialog was left holding. `normalizeKeepTree` owns this answer and it
                // is the same one registration would have taken.
                keepTree = true
            )
        )
        viewModel.finished()

        val created = dao.sourcesAt(source.rootUri, source.relativePath)
            .single { it.id != source.id }
        assertEquals(ImporterKind.FOLDER_AS_TASK.key, created.importerKind)
        assertEquals(false, created.keepTree)
    }

    // --- what the run says it did ---------------------------------------------------------------

    /**
     * A chosen history reaches the report **by name**, which is the only thing that lets a recovery
     * that matched nothing be answered.
     *
     * `ImportResult.tasksRecovered` counts what a recovery reached, and a count of zero is a real
     * outcome rather than an absence: a history written by a folder-as-Task Todo has no group paths
     * in common with the file-as-Task Tasks a later run mints, so a user can choose a history and
     * get nothing. The report is where that is said, and it can only say it if the name travelled
     * with the count.
     */
    @Test
    fun `a chosen history reaches the report by name`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = existingTodo()
        val options = ImportOptions(
            importer = ImporterKind.FILE_AS_TASK,
            displayName = "Holiday",
            keepTree = true
        )

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        viewModel.confirm()
        viewModel.confirmImportFolder(options)
        viewModel.finished()
        val first = dao.sourcesAt(source.rootUri, "").single { it.id != source.id }
        dao.deleteTodo(first.id, keepState = true, now = 2000)

        // The picker offered it, so the run can be asked for it. Read through the same flow the
        // dialog reads rather than assembled here, so the test chooses what a user would choose.
        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        val offered = viewModel.confirm().histories.single()
        assertEquals("Holiday", offered.sourceName)

        viewModel.confirmImportFolder(options, offered)
        val report = viewModel.finished() as ImportFolderRequest.Report

        assertEquals("Holiday", report.recoveredFrom)
        // And it really recovered: a name reported over a run that copied nothing would be the
        // silent case wearing a label.
        assertEquals(2, report.result.tasksRecovered)
    }

    /**
     * The ordinary run names nothing, which is what keeps the report silent about a recovery nobody
     * asked for. A null here is not the absence of an answer — it is the answer to a question that
     * was never put.
     */
    @Test
    fun `a run that was offered no history reports none`() = runTest(dispatcher) {
        val viewModel = viewModel(PICKED)
        val source = existingTodo()

        viewModel.requestCreateTasks(source.rootUri, source.relativePath)
        assertEquals(emptyList<DissolvedHistory>(), viewModel.confirm().histories)
        viewModel.confirmImportFolder(
            ImportOptions(
                importer = ImporterKind.FILE_AS_TASK,
                displayName = "Holiday",
                keepTree = true
            )
        )
        val report = viewModel.finished() as ImportFolderRequest.Report

        assertEquals(null, report.recoveredFrom)
        assertEquals(0, report.result.tasksRecovered)
    }

    // --- reaching the states above --------------------------------------------------------------

    /**
     * A Todo over the Upstream's own folder, as an earlier Create Tasks run would have left it.
     *
     * Written straight to the table rather than reached through the picker, because since v16 there
     * is no path from adding a folder to owning a Todo: registering says where material is, and only
     * an import writes a Todo. A test that needs one to start from has to say so itself.
     */
    private suspend fun existingTodo(): SourceEntity =
        dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Picked folder",
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 0
        )

    /** The confirmation the run reached, which is the only thing that carries the options. */
    private suspend fun ListsViewModel.confirm(): ImportFolderRequest.Confirm =
        importFolderRequest.first { it is ImportFolderRequest.Confirm } as ImportFolderRequest.Confirm

    /** Waits for the run to have ended, either way — the write being made is what is observed. */
    private suspend fun ListsViewModel.finished(): ImportFolderRequest =
        importFolderRequest.first {
            it is ImportFolderRequest.Report || it is ImportFolderRequest.Error
        }!!

    private fun viewModel(folders: FolderSource) =
        ListsViewModel(application, dao, folders, dispatcher)

    private companion object {
        const val ROOT = "content://root"

        /** A picked folder holding one file, and one child folder holding another. */
        val PICKED = FakeFolderSource(
            rootName = "2026-09-11",
            files = listOf(
                FolderFile("a.jpg", "content://root/doc/a.jpg", "2026-09-11/a.jpg"),
                FolderFile("b.jpg", "content://root/doc/b.jpg", "2026-09-11/bilibili/b.jpg")
            ),
            directories = listOf("2026-09-11/bilibili")
        )

        /** The same folder, from a provider that will not say what it is called. */
        val UNNAMED = FakeFolderSource()
    }
}
