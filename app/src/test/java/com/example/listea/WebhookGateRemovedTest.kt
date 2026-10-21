package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ExportRecordSummary
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ReviewItem
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings switch no longer decides whether anything is sent.
 *
 * Until v0.6.2 `defaultWebhookEnabled` was a global POST gate: a Todo could be fully configured and
 * a completed List would still record *Nothing was sent · the default webhook is switched off*.
 * With per-Todo addresses the switch became a preference for what a new Todo starts on, so a
 * round with a configured address must ask and then attempt even when it is off — and a user who
 * declines must read as having declined, not as having a switch that is off.
 *
 * The destination is a closed loopback port rather than a name: this test is about the branch that
 * gets reached, and a refused connection reaches it in milliseconds without DNS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebhookGateRemovedTest {

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
    fun `a switched-off default does not stop a Todo with its own address`() = runTest(dispatcher) {
        val viewModel = viewModel()
        // The old global switch, written off on purpose.
        viewModel.setDefaultWebhookEnabled(true)
        viewModel.settings.first { it.defaultWebhookEnabled }
        viewModel.setDefaultWebhookEnabled(false)
        viewModel.settings.first { !it.defaultWebhookEnabled }

        val task = todoAndTask()
        list("Demo", task)
        viewModel.setItemCompleted(itemOf(task, "y.jpg"), true)

        // The app still asks, because something really is about to leave.
        viewModel.webhookConfirmation.first { it != null }
        viewModel.confirmWebhookSend()

        val record = viewModel.keptRows(1).single()
        // It was attempted and failed to connect. The two outcomes that would mean the switch
        // still gated it are DISABLED and DEFERRED; both are wrong here.
        assertEquals(NoticeOutcome.FAILED.name, record.outcome)
        assertNotEquals(NoticeOutcome.DISABLED.name, record.outcome)
        assertEquals(CLOSED_PORT, record.webhookUrl)
    }

    @Test
    fun `declining under a switched-off default reads as a decline`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setDefaultWebhookEnabled(false)
        viewModel.settings.first { !it.defaultWebhookEnabled }

        val task = todoAndTask()
        list("Demo", task)
        viewModel.setItemCompleted(itemOf(task, "y.jpg"), true)

        viewModel.webhookConfirmation.first { it != null }
        viewModel.declineWebhookSend()

        val record = viewModel.keptRows(1).single()
        assertEquals(NoticeOutcome.DECLINED.name, record.outcome)
        assertNotEquals(NoticeOutcome.DISABLED.name, record.outcome)
    }

    // --- fixture ----------------------------------------------------------------------------------

    private suspend fun todoAndTask(): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "b",
            displayName = "b",
            importerKind = ImporterKind.FILE_AS_TASK,
            exportKind = encodeExportChoice(ExportChoice.By(ExportKind.WEBHOOK)),
            webhookUrl = CLOSED_PORT,
            now = 1000
        )
        dao.runImport(
            source.id,
            "b",
            plan(
                ImporterKind.FILE_AS_TASK,
                "b",
                FolderSnapshot(listOf(scanned("b", "y.jpg")), emptyList())
            ),
            2000
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    private suspend fun list(title: String, taskId: String): Long =
        dao.createListFromTasks(title, listOf(taskId), true, 3000)

    private fun itemOf(taskId: String, file: String): ReviewItem {
        val path = childRelativePath("b", file)
        return ReviewItem(
            id = 0L,
            taskId = taskId,
            publicId = "row-$taskId",
            title = file,
            sourceUri = "$ROOT/$path",
            relativePath = path,
            sourceMissing = false,
            target = DecisionTarget.File(rootUri = ROOT, relativePath = path)
        )
    }

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    private suspend fun ListsViewModel.keptRows(count: Int): List<ExportRecordSummary> =
        webhookHistory.first { it.size >= count }

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)

    private companion object {
        const val ROOT = "content://root"

        /** Nothing listens on port 1, so the connection is refused without a DNS lookup. */
        const val CLOSED_PORT = "http://127.0.0.1:1/hook"
    }
}
