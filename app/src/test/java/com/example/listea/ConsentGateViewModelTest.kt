package com.example.listea

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListEntity
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * The consent gate, driven through the ViewModel and not the DAO (N2).
 *
 * `ConsentTest` holds the level *below* this one: it proves that the reads an import consent stands
 * on — checking coverage, reading the catalog — write no Source, Task, Asset or provenance row. That
 * is necessary and it is not the gate. The gate is the ViewModel's decision to ask at all, and the
 * defect the v0.2.5 review found (N1) lived exactly there: `requestListForFolder` skipped the
 * replace confirmation when the folder was un-imported, so accepting import consent deleted
 * overlapping Lists that no dialog had named. A test below this one could not have failed for that
 * reason, because the DAO was never asked to delete anything the ViewModel did not tell it to.
 *
 * So these tests drive [ListsViewModel.requestListForFolder] and [ListsViewModel.observeFolderReview]
 * themselves, over a fake [FolderSource], and watch the five tables an import would touch. Every
 * assertion is the same one the review asked for: nothing is written before consent, and a decline
 * leaves the database exactly as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentGateViewModelTest {

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

    // ------------------------------------------------------------------------------------------
    // N1: the two gates are two questions, asked one at a time.
    // ------------------------------------------------------------------------------------------

    /**
     * An un-imported folder that overlaps an existing List must be asked about *twice*, and the
     * first answer must not carry the second with it.
     *
     * This is the N1 regression, moved to where it belongs. The leak was that accepting "import this
     * folder?" ran the whole creation, deleting the overlapping List on a consent that never named
     * it. The gate that must hold is the one inside `requestListForFolder` / `confirmImportForFolder`,
     * so it is the one driven here.
     */
    @Test
    fun `an un-imported folder overlapping a list is asked about importing before it is asked about replacing`() =
        runTest(dispatcher) {
            seedOverlappingList()

            val viewModel = viewModel()
            viewModel.requestListForFolder(ROOT, "holiday", folderUri("holiday"), "Holiday")

            // First question: import. Not replace - the List has not been named to the user yet, and
            // a dialog that has not named it may not delete it.
            val consent = viewModel.settled()
            assertTrue(
                "expected import consent, was $consent",
                consent is FolderListRequest.ImportConsent
            )
            assertUntouched(expectedLists = 1)

            // Accepting the import is accepting the import. The replace question is still ahead, and
            // nothing has been written yet either.
            viewModel.confirmImportForFolder()

            val replace = viewModel.settled(previous = consent)
            assertTrue(
                "accepting import consent must not skip the replace confirmation, was $replace",
                replace is FolderListRequest.Confirm
            )
            assertEquals(listOf("Holiday"), (replace as FolderListRequest.Confirm).replacedTitles)
            assertUntouched(expectedLists = 1)
        }

    /** Declining the import consent declines everything: the List is still there, untouched. */
    @Test
    fun `declining import consent leaves the overlapping list and every table alone`() =
        runTest(dispatcher) {
            val listId = seedOverlappingList()

            val viewModel = viewModel()
            viewModel.requestListForFolder(ROOT, "holiday", folderUri("holiday"), "Holiday")
            assertTrue(viewModel.settled() is FolderListRequest.ImportConsent)

            viewModel.dismissFolderListRequest()

            assertEquals(null, viewModel.folderListRequest.value)
            assertEquals(listOf(listId), dao.getFolderLists(ROOT).map { it.id })
            assertUntouched(expectedLists = 1)
        }

    /** The gate before both questions: a folder with no conflicts is still not a list until accepted. */
    @Test
    fun `asking for a list over an un-imported folder writes nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.requestListForFolder(ROOT, "holiday", folderUri("holiday"), "Holiday")

            assertTrue(viewModel.settled() is FolderListRequest.ImportConsent)
            assertUntouched()
        }

    // ------------------------------------------------------------------------------------------
    // N2: Quick Review's consent, at the same level.
    // ------------------------------------------------------------------------------------------

    /**
     * Looking at a folder in Quick Review is not importing it, and the screen must be told so.
     *
     * The review asked that `observeFolderReview` be driven directly, and that its answer be
     * `NotImported` rather than a silent empty round — the consent dialog is the whole of what stands
     * between a browse and an import, and a `Ready(emptyList())` here would have been the app
     * pretending the folder was already in.
     */
    @Test
    fun `observing an un-imported folder asks for consent and writes nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            val review = viewModel.observeFolderReview(ROOT, "holiday").first()

            assertTrue("expected NotImported, was $review", review is FolderReview.NotImported)
            assertEquals("holiday", (review as FolderReview.NotImported).sourceRoot)
            assertUntouched()
        }

    /**
     * Declining Quick Review's consent leaves the folder un-imported.
     *
     * There is no separate "decline" call here — the dialog's *Not now* simply does not call
     * `importFolderForReview` — so this pins the thing that would actually break: that re-observing
     * the folder after the offer finds it exactly as un-imported as it was, with no Source registered
     * as a side effect of having asked.
     */
    @Test
    fun `declining quick review consent leaves the folder un-imported`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.observeFolderReview(ROOT, "holiday").first()

            val again = viewModel.observeFolderReview(ROOT, "holiday").first()

            assertTrue(again is FolderReview.NotImported)
            assertEquals(emptyList<SourceEntity>(), dao.sourcesCovering(ROOT, "holiday"))
            assertUntouched()
        }

    // ------------------------------------------------------------------------------------------

    /**
     * The five tables an import writes, and the only assertion this file really makes.
     *
     * Named individually rather than counted through a helper so a failure says which table broke,
     * and read through raw SQL rather than through DAO projections so a row that exists but is not
     * projected — a Source with no Tasks, a provenance row with no Assets — is still seen.
     */
    private fun assertUntouched(expectedLists: Int = 0) {
        assertEquals(0, rawInt("SELECT COUNT(*) FROM sources"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM assets"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports"))
        // `lists` is the only one that is ever seeded. Where N1 applies it is the count that was
        // guarded, because the leak showed up as this number dropping on a consent that never named
        // the List — so the assertion is that it is *still* what it was, not that it is zero.
        assertEquals(expectedLists, rawInt("SELECT COUNT(*) FROM lists"))
    }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    /** A folder-backed List in `holiday`, which a List over `holiday` or `holiday/…` would replace. */
    private suspend fun seedOverlappingList(): Long =
        dao.insertList(
            ListEntity(
                title = "Holiday",
                createdAt = 1_000,
                sourceRootUri = ROOT,
                sourceRelativePath = "holiday"
            )
        )

    /**
     * Waits for the gate to have *said* something, rather than for the queue to drain.
     *
     * `advanceUntilIdle()` is not enough here and the difference is the whole reason this helper
     * exists: it runs what is queued and then returns, while a Room suspend query resumes from Room's
     * own executor. A call that has not come back yet leaves the queue momentarily empty, so the
     * assertion reads `Scanning` and fails for a reason that has nothing to do with the code under
     * test. Waiting on the flow keeps this coroutine suspended until the ViewModel has actually
     * reached a decision — which is the thing being tested.
     *
     * [previous] is what makes the second wait work: after accepting one gate the state is still the
     * *old* answer until the next one is reached, so waiting for a non-null value would return the
     * value just answered rather than the question that follows it.
     */
    private suspend fun ListsViewModel.settled(previous: FolderListRequest? = null): FolderListRequest? =
        folderListRequest.first { it !== previous && it !is FolderListRequest.Scanning }

    /**
     * A folder with files in it, which is exactly the state consent exists to ask about: a
     * provider that answered nothing would mean the scan failed first and the ViewModel reported an
     * unreadable folder rather than an un-imported one.
     */
    private fun viewModel() = ListsViewModel(
        application,
        dao,
        FakeFolderSource(files = HOLIDAY_FILES, rootName = "holiday"),
        dispatcher
    )

    private companion object {
        const val ROOT = "content://root"

        fun folderUri(path: String): Uri = "content://root/$path".let(Uri::parse)

        val HOLIDAY_FILES = listOf(
            FolderFile("a.jpg", "content://root/doc/a.jpg", "holiday/a.jpg"),
            FolderFile("b.jpg", "content://root/doc/b.jpg", "holiday/b.jpg")
        )
    }
}
