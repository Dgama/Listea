package com.example.listea

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ScannedFile
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A new List never starts posting because of a setting above it.
 *
 * Before v0.6.2 both creation paths copied `defaultWebhookEnabled` into *Export when this list is
 * completed*, so on an install where the default switch was on every new List began exporting
 * automatically. The switch is a per-List decision now, and the setting survives only as a
 * preselection inside Create Tasks — so creation must write off, whichever path made the List and
 * whatever the setting says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListCreationDefaultsTest {

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
    fun `a List built from picked Tasks starts with completion export off`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setDefaultWebhookEnabled(true)
        viewModel.settings.first { it.defaultWebhookEnabled }

        val task = todoAndTask()
        viewModel.createListFromCatalog("Picked", listOf(task)) {}

        // Waited for on the summaries, not on `advanceUntilIdle`: the write resumes from Room's own
        // executor, so the queue can be empty while the creation is still in flight.
        val created = viewModel.summaries
            .first { list -> list.any { it.title == "Picked" } }
            .single { it.title == "Picked" }

        assertEquals(false, dao.getList(created.id)!!.exportOnCompletion)
    }

    @Test
    fun `a List built from a folder starts with completion export off`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setDefaultWebhookEnabled(true)
        viewModel.settings.first { it.defaultWebhookEnabled }

        // Already covered by a Todo, so the folder needs no import consent and commits directly.
        dao.registerSource(
            rootUri = ROOT,
            relativePath = "b",
            displayName = "b",
            importerKind = ImporterKind.FILE_AS_TASK,
            exportKind = encodeExportChoice(ExportChoice.By(ExportKind.TXT)),
            now = 1000
        )
        viewModel.requestListForFolder(
            rootUri = ROOT,
            relativePath = "b",
            folderUri = "$ROOT/doc/b".toUri(),
            folderName = "b"
        )

        val created = dao.observeFolderLists(ROOT).first { it.isNotEmpty() }.single()
        assertEquals("b", created.title)
        assertEquals(false, created.exportOnCompletion)
    }

    // --- fixture ----------------------------------------------------------------------------------

    private suspend fun todoAndTask(): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "a",
            displayName = "a",
            importerKind = ImporterKind.FILE_AS_TASK,
            exportKind = encodeExportChoice(ExportChoice.By(ExportKind.TXT)),
            now = 1000
        )
        dao.runImport(
            source.id,
            "a",
            plan(
                ImporterKind.FILE_AS_TASK,
                "a",
                FolderSnapshot(listOf(scanned("a", "x.jpg")), emptyList())
            ),
            2000
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    private fun viewModel(): ListsViewModel = ListsViewModel(
        application,
        dao,
        FakeFolderSource(
            files = listOf(
                FolderFile("b.jpg", "$ROOT/doc/b/b.jpg", "b/b.jpg")
            )
        ),
        dispatcher
    )

    private companion object {
        const val ROOT = "content://root"
    }
}
