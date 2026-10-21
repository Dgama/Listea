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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Create Tasks and Refresh Import store now that a Todo owns its standing answers.
 *
 * Three things can go wrong here and all three are pinned below. A new Todo could be born with the
 * wrong standing answers — silently not following its upstream, or keeping a deletion policy nobody
 * chose. An address could be written that no delivery can use, leaving a Todo whose every export
 * fails; the *Use default* radio over an empty default is exactly that, and it must refuse before
 * anything is registered. And a run with no dialog at all — the upstream sync — must write back
 * exactly what it read, because *nobody was asked* is not *reset to the default*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateTasksAddressTest {

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

    // --- seeding ---------------------------------------------------------------------------------

    @Test
    fun `Create Tasks seeds the standing answers and starts the address empty`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.setFollowUpstreamNewTodos(true)
            viewModel.setDefaultWebhookEnabled(true)
            // Wait on the settings the ViewModel reads, not on the writes: the dialog is built from
            // the StateFlow, and a test that only scheduled the write would assert a seed that had
            // not happened.
            viewModel.settings.first { it.followUpstreamNewTodos && it.defaultWebhookEnabled }

            viewModel.requestCreateTasks(ROOT, "")
            val options = viewModel.confirm().options

            assertTrue("a new Todo follows its upstream when Settings says so", options.followUpstream)
            assertEquals("no deletion policy is invented", false, options.keepStateOnDelete)
            assertEquals("the address is not snapshotted until Confirm", "", options.webhookUrl)
            assertTrue("the radio starts on the default", options.webhookFromDefault)
        }

    @Test
    fun `Refresh Import seeds the Todo's own standing answers`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val source = todo(
            followUpstream = true,
            keepStateOnDelete = true,
            webhookUrl = "https://example.com/hook",
            webhookFromDefault = false
        )

        viewModel.requestSourceRefresh(source.id)
        val options = viewModel.confirm().options

        assertTrue(options.followUpstream)
        assertTrue(options.keepStateOnDelete)
        assertEquals("https://example.com/hook", options.webhookUrl)
        assertEquals(false, options.webhookFromDefault)
        assertTrue("a refresh still adopts the offered template version", options.upgradeTemplates)
    }

    // --- refusing what cannot be delivered -------------------------------------------------------

    @Test
    fun `Use default over an empty default refuses and writes no Todo`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestCreateTasks(ROOT, "")
        val options = viewModel.confirm().options

        viewModel.confirmImportFolder(
            options.copy(
                exportChoice = ExportChoice.By(ExportKind.WEBHOOK),
                webhookUrl = "",
                webhookFromDefault = true
            )
        )

        val error = viewModel.finished() as ImportFolderRequest.Error
        assertTrue(error.message.contains("No webhook address is set"))
        assertEquals("nothing may be registered on a refused run", emptyList<SourceEntity>(), dao.sourcesAt(ROOT, ""))
    }

    @Test
    fun `an unusable custom address refuses before registering`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestCreateTasks(ROOT, "")
        val options = viewModel.confirm().options

        viewModel.confirmImportFolder(
            options.copy(
                exportChoice = ExportChoice.By(ExportKind.WEBHOOK),
                webhookUrl = "not a url",
                webhookFromDefault = false
            )
        )

        val error = viewModel.finished() as ImportFolderRequest.Error
        assertTrue(error.message.contains("Not a valid URL"))
        assertEquals(emptyList<SourceEntity>(), dao.sourcesAt(ROOT, ""))
    }

    // --- writing ---------------------------------------------------------------------------------

    @Test
    fun `a custom address and the standing answers are written to the new Todo`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.requestCreateTasks(ROOT, "")
            val options = viewModel.confirm().options

            viewModel.confirmImportFolder(
                options.copy(
                    displayName = "Holiday",
                    exportChoice = ExportChoice.By(ExportKind.WEBHOOK),
                    followUpstream = true,
                    keepStateOnDelete = true,
                    webhookUrl = " https://example.com/hook ",
                    webhookFromDefault = false
                )
            )
            assertTrue(viewModel.finished() is ImportFolderRequest.Report)

            val created = dao.sourcesAt(ROOT, "").single()
            assertEquals("Holiday", created.displayName)
            assertEquals("https://example.com/hook", created.webhookUrl)
            assertEquals(false, created.webhookFromDefault)
            assertTrue(created.followUpstream)
            assertTrue(created.keepStateOnDelete)
        }

    @Test
    fun `a default copied at Confirm is a snapshot, not a subscription`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setDefaultWebhookUrl(DEFAULT)
        viewModel.settings.first { it.defaultWebhookUrl == DEFAULT }

        viewModel.requestCreateTasks(ROOT, "")
        val options = viewModel.confirm().options
        // What the dialog submits for *Use default*: the live value at the moment of Confirm.
        viewModel.confirmImportFolder(
            options.copy(
                exportChoice = ExportChoice.By(ExportKind.WEBHOOK),
                webhookUrl = viewModel.settings.value.defaultWebhookUrl,
                webhookFromDefault = true
            )
        )
        viewModel.finished()
        val created = dao.sourcesAt(ROOT, "").single()
        assertEquals(DEFAULT, created.webhookUrl)
        assertTrue(created.webhookFromDefault)

        viewModel.setDefaultWebhookUrl("https://moved.example.com/hook")
        viewModel.settings.first { it.defaultWebhookUrl.startsWith("https://moved") }

        assertEquals(
            "a Todo posts where it was configured; the default is a source, not a leash",
            DEFAULT,
            dao.getSource(created.id)!!.webhookUrl
        )
    }

    @Test
    fun `a refresh writes changed standing answers back to the Todo`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val source = todo(
            followUpstream = true,
            keepStateOnDelete = true,
            webhookUrl = "https://example.com/hook"
        )

        viewModel.requestSourceRefresh(source.id)
        val options = viewModel.confirm().options
        viewModel.confirmImportFolder(
            options.copy(
                followUpstream = false,
                keepStateOnDelete = false,
                webhookUrl = "https://other.example.com/hook",
                webhookFromDefault = false
            )
        )
        assertTrue(viewModel.finished() is ImportFolderRequest.Report)

        val refreshed = dao.getSource(source.id)!!
        assertEquals(false, refreshed.followUpstream)
        assertEquals(false, refreshed.keepStateOnDelete)
        assertEquals("https://other.example.com/hook", refreshed.webhookUrl)
        assertEquals(false, refreshed.webhookFromDefault)
    }

    @Test
    fun `an upstream sync keeps a blank address and still imports`() = runTest(dispatcher) {
        val viewModel = viewModel()
        // The gate-off upgrade's shape: webhook method, no address. Following the upstream must
        // still bring material in; refusing here would turn an export problem into an import
        // outage, and the sync was never asked about either.
        val source = todo(
            followUpstream = true,
            exportKind = ExportChoice.By(ExportKind.WEBHOOK),
            webhookUrl = ""
        )

        viewModel.syncTodoUpstream(source.id)

        assertTrue(viewModel.finished() is ImportFolderRequest.Report)
        val synced = dao.getSource(source.id)!!
        assertEquals("", synced.webhookUrl)
        assertTrue(synced.followUpstream)
    }

    // --- reaching the states above ---------------------------------------------------------------

    private suspend fun todo(
        followUpstream: Boolean = false,
        keepStateOnDelete: Boolean = false,
        exportKind: ExportChoice = ExportChoice.By(ExportKind.WEBHOOK),
        webhookUrl: String = "",
        webhookFromDefault: Boolean = false
    ): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = "Picked folder",
        importerKind = ImporterKind.FILE_AS_TASK,
        keepTree = true,
        exportKind = encodeExportChoice(exportKind),
        followUpstream = followUpstream,
        keepStateOnDelete = keepStateOnDelete,
        webhookUrl = webhookUrl,
        webhookFromDefault = webhookFromDefault,
        now = 0
    )

    private suspend fun ListsViewModel.confirm(): ImportFolderRequest.Confirm =
        importFolderRequest.first { it is ImportFolderRequest.Confirm } as ImportFolderRequest.Confirm

    private suspend fun ListsViewModel.finished(): ImportFolderRequest =
        importFolderRequest.first {
            it is ImportFolderRequest.Report || it is ImportFolderRequest.Error
        }!!

    private fun viewModel() = ListsViewModel(application, dao, PICKED, dispatcher)

    private companion object {
        const val ROOT = "content://root"
        const val DEFAULT = "https://example.com/default"

        val PICKED = FakeFolderSource(
            rootName = "2026-09-11",
            files = listOf(
                FolderFile("a.jpg", "content://root/doc/a.jpg", "2026-09-11/a.jpg"),
                FolderFile("b.jpg", "content://root/doc/b.jpg", "2026-09-11/b.jpg")
            )
        )
    }
}
