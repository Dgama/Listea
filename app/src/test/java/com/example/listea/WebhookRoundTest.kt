package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ExportRecordEntity
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one delivery leaves in the history: a destination per row, and one round id across them.
 *
 * The History is the only place a delivery is visible after it happens, so the two facts it needs
 * to answer *where did this go* and *what happened together* are stored rather than re-derived.
 * Before v0.6.2 neither existed: every webhook went to the one Settings URL, and every row was a
 * round of its own.
 *
 * The round is driven through the real ViewModel and database, because that is where the id is
 * minted and where the address is resolved. A split round is produced the way the app produces
 * one — a List over two Todos with different addresses — and set aside via the app's own *another
 * round is open* branch, so no test here opens a socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebhookRoundTest {

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

    // --- one delivery, several rows ---------------------------------------------------------------

    @Test
    fun `the groups of one delivery share a round id and each stores its own address`() =
        runTest(dispatcher) {
            val rows = completeTwoGroupRound()

            assertEquals(2, rows.size)
            assertEquals(
                "a split round is one event in the history",
                1,
                rows.map { it.roundId }.distinct().size
            )
            assertTrue(rows.all { it.roundId.isNotEmpty() })
            assertEquals(setOf(ADDRESS_B, ADDRESS_C), rows.map { it.webhookUrl }.toSet())
            assertTrue(rows.all { it.outcome == NoticeOutcome.DEFERRED.name })
        }

    // --- resend -----------------------------------------------------------------------------------

    @Test
    fun `a resend writes back neither the address it used nor the round id`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val rows = completeTwoGroupRound()
            val resent = rows.first { it.webhookUrl == ADDRESS_B }
            val untouched = rows.first { it.id != resent.id }

            // A malformed scheme completes the attempt without opening a socket, and still carries
            // a host, which is what the detail has to name.
            viewModel.resendWebhookRecord(resent.id, "ftp://other.example/hook")

            val updated = viewModel.webhookHistory
                .first { list -> list.any { it.id == resent.id && it.outcome == NoticeOutcome.FAILED.name } }
                .first { it.id == resent.id }

            assertEquals("the temporary address must not replace the original", ADDRESS_B, updated.webhookUrl)
            assertEquals(resent.roundId, updated.roundId)
            assertTrue(updated.detail.contains("resent to other.example"))
            // The other row of the round is not part of this attempt and must not move with it.
            val stillThere = dao.getWebhookRecord(untouched.id)!!
            assertEquals(untouched.webhookUrl, stillThere.webhookUrl)
            assertEquals(untouched.roundId, stillThere.roundId)
            assertEquals(NoticeOutcome.DEFERRED.name, stillThere.outcome)
        }

    @Test
    fun `a row written before v27 falls back to the live default and says its address was lost`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val id = dao.insertWebhookRecord(
                ExportRecordEntity(
                    event = EVENT_LIST_COMPLETED,
                    listTitle = "Old round",
                    itemCount = 1,
                    payload = "{}",
                    at = 1,
                    outcome = NoticeOutcome.SENT.name,
                    detail = "Success · HTTP 200",
                    kind = ExportKind.WEBHOOK.key,
                    webhookUrl = "",
                    roundId = ""
                )
            )
            viewModel.setDefaultWebhookUrl("ftp://default.example/hook")
            viewModel.settings.first { it.defaultWebhookUrl == "ftp://default.example/hook" }

            viewModel.resendWebhookRecord(id)

            val updated = viewModel.webhookHistory
                .first { list -> list.any { it.id == id && it.outcome == NoticeOutcome.FAILED.name } }
                .first { it.id == id }

            assertEquals("the address column is not guessed after the fact", "", updated.webhookUrl)
            assertEquals("", updated.roundId)
            assertTrue(updated.detail.contains("original address not recorded"))
        }

    // --- the Test button --------------------------------------------------------------------------

    @Test
    fun `Test writes exactly one row, with a round of its own`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val url = "ftp://example.com/hook"
        viewModel.setDefaultWebhookUrl(url)
        viewModel.settings.first { it.defaultWebhookUrl == url }

        viewModel.testWebhook()
        val first = viewModel.keptRows(1).single()

        assertEquals(EVENT_EXPORT_TEST, first.event)
        assertEquals(url, first.webhookUrl)
        assertTrue(first.roundId.isNotEmpty())
        assertEquals(NoticeOutcome.FAILED.name, first.outcome)
        assertTrue(first.detail.contains("must start with http"))

        viewModel.testWebhook()
        val both = viewModel.keptRows(2)
        assertEquals("a second test is a second round", 2, both.map { it.roundId }.distinct().size)
        assertNotEquals(both[0].roundId, both[1].roundId)
    }

    // --- counting rounds --------------------------------------------------------------------------

    @Test
    fun `the round count treats every legacy row as a round of its own`() = runTest(dispatcher) {
        dao.insertWebhookRecord(legacy())
        dao.insertWebhookRecord(legacy())
        dao.insertWebhookRecord(legacy(roundId = "shared"))
        dao.insertWebhookRecord(legacy(roundId = "shared"))

        assertEquals(3, dao.observeWebhookRoundCount().first())
    }

    // --- reaching the states above ----------------------------------------------------------------

    /**
     * Two Todos, two addresses, one List, completed while another List's round is open.
     *
     * The open round is what routes the delivery through the app's *Not now* branch, so the
     * payloads are built and kept without a socket — and the addresses and round id are written
     * exactly as they would be for a real send.
     */
    private suspend fun TestScope.completeTwoGroupRound(): List<ExportRecordSummary> {
        val vm = viewModel()
        val b = todoAndTask("b", "y.jpg", ADDRESS_B)
        val c = todoAndTask("c", "z.jpg", ADDRESS_C)
        list("Demo", b, c)
        val reviewed = list("export test", todoAndTask("a", "x.jpg", "", ExportKind.TXT))
        vm.onReviewStarted(reviewed)
        advanceUntilIdle()

        vm.setItemCompleted(itemOf(b, "b", "y.jpg"), true)
        advanceUntilIdle()
        vm.setItemCompleted(itemOf(c, "c", "z.jpg"), true)

        return vm.keptRows(2).filter { it.listTitle == "Demo" }
    }

    private suspend fun todoAndTask(
        folder: String,
        file: String,
        url: String,
        kind: ExportKind = ExportKind.WEBHOOK
    ): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = folder,
            importerKind = ImporterKind.FILE_AS_TASK,
            exportKind = encodeExportChoice(ExportChoice.By(kind)),
            webhookUrl = url,
            now = 1000
        )
        dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FILE_AS_TASK,
                folder,
                FolderSnapshot(listOf(scanned(folder, file)), emptyList())
            ),
            2000
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    private suspend fun list(title: String, vararg taskIds: String): Long =
        dao.createListFromTasks(title, taskIds.toList(), true, 3000)

    private fun itemOf(taskId: String, folder: String, file: String): ReviewItem {
        val path = childRelativePath(folder, file)
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

    private fun legacy(roundId: String = "") = ExportRecordEntity(
        event = EVENT_LIST_COMPLETED,
        listTitle = "Old round",
        itemCount = 1,
        payload = "{}",
        at = 1,
        outcome = NoticeOutcome.SENT.name,
        detail = "Success · HTTP 200",
        kind = ExportKind.WEBHOOK.key,
        webhookUrl = "",
        roundId = roundId
    )

    private suspend fun ListsViewModel.keptRows(count: Int): List<ExportRecordSummary> =
        webhookHistory.first { it.size >= count }

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)

    private companion object {
        const val ROOT = "content://root"
        const val ADDRESS_B = "https://b.example/hook"
        const val ADDRESS_C = "https://c.example/hook"
    }
}
