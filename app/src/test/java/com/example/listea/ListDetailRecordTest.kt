package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AssetDecisions
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.ItemAction
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ScannedFile
import com.example.listea.data.taskDecisions
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
 * What the List's own screens read about a Task's files, which is not the same question as what the
 * List's row says.
 *
 * v0.5.1 gives a List two answers about marks and they are folds over pairs taken in two different
 * places. The membership query folds them in SQL onto [ListItemEntity] — it has no file on screen, so
 * one row has to answer for all of them — and [ReviewItem.taskDecisions] folds the item's own Assets
 * in Kotlin, which is what a surface that *does* have files uses. Both are right; the failure this
 * file exists to catch is that only one of them was ever being fed.
 *
 * **This is the reported bug, and it was found after the release rather than by it.** The List's
 * detail and review screens read [ListsViewModel.observeDetail], which resolved each Task's Assets
 * through `observeAssetsForList` — the bare `assets` rows — while the query written for exactly this
 * job, `observeAssetStatesForList`, had **no call site at all**. So the Assets arrived as rows with
 * no records attached, `toReviewAsset` fell back to its deliberate *nothing recorded* default, and
 * every chip on the List came up unticked however carefully the files had been marked in Quick
 * Review. The folded SQL answer was correct the whole time and simply was not the one on screen,
 * which is why the earlier per-pair tests all passed while the user saw nothing.
 *
 * It is asserted through the ViewModel rather than the DAO because that is the layer the two answers
 * met: a DAO test can only ask the question one way, and the defect lived in the wiring between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListDetailRecordTest {

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
    fun `a List's item carries each of its files' own marks`() = runTest(dispatcher) {
        val pair = twoFileTask()
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/a.jpg"), ItemAction.Custom("cust1"), true, 2000)
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/a.jpg"), ItemAction.Favourite, true, 2001)
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/b.jpg"), ItemAction.Custom("cust2"), true, 2002)
        val listId = dao.createListFromTasks("Holiday", listOf(pair.taskId), false, "", 3000)

        val item = viewModel().observeDetail(listId).first()!!.items.single()

        // Per file first. The two files are marked differently on purpose: a fix that fed the Task's
        // folded answer to both pages would pass a "not empty" assertion and still be the bug this
        // release exists to fix — a Task-level answer handed to a page.
        val byName = item.assets.associateBy { it.name }
        assertEquals(
            AssetDecisions(isFavorite = true, customActions = setOf("cust1")),
            byName.getValue("a.jpg").decisions
        )
        assertEquals(
            AssetDecisions(customActions = setOf("cust2")),
            byName.getValue("b.jpg").decisions
        )
    }

    @Test
    fun `a List's item folds its files into what its own surface shows`() = runTest(dispatcher) {
        val pair = twoFileTask()
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/a.jpg"), ItemAction.Custom("cust1"), true, 2000)
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/b.jpg"), ItemAction.Custom("cust2"), true, 2001)
        dao.setAssetAction(pair.taskId, pair.assets.getValue("alice/b.jpg"), ItemAction.Favourite, true, 2002)
        val listId = dao.createListFromTasks("Holiday", listOf(pair.taskId), false, "", 3000)

        val item = viewModel().observeDetail(listId).first()!!.items.single()

        // The chips under the row, and the judgment a submission from this row would record — both
        // read this one property, so both were empty together. The union and not the last file's:
        // a List row names no file, so nothing may be dropped for having been set on another page.
        assertEquals(
            AssetDecisions(isFavorite = true, customActions = setOf("cust1", "cust2")),
            item.taskDecisions
        )
    }

    @Test
    fun `a List's item arrives unmarked when nothing was recorded`() = runTest(dispatcher) {
        val pair = twoFileTask()
        val listId = dao.createListFromTasks("Holiday", listOf(pair.taskId), false, "", 3000)

        val item = viewModel().observeDetail(listId).first()!!.items.single()

        // The other direction, so that a fix which invented a record for every file would fail here.
        // An unmarked file and a missing row are the same thing to a reader and both mean nothing
        // was said — which is the distinction the per-pair table rests on and the reason the fold is
        // over what is there rather than over a row per file that is always present.
        assertEquals(listOf(AssetDecisions(), AssetDecisions()), item.assets.map { it.decisions })
        assertEquals(AssetDecisions(), item.taskDecisions)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)

    /** One Task over a folder of two files, with each file's Asset id, keyed by path. */
    private suspend fun twoFileTask(): MultiFileTask {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Root",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(source.id, "", folderPlan(), 2000)
        // The same registration a folder browse performs, for the reason `PerAssetRecordTest` gives:
        // the per-file readers are driven from `file_review_state`, and this fixture's subject is
        // what the List reads about the Assets rather than the registration itself.
        dao.registerFiles(
            FILES.map {
                FileReviewStateEntity(
                    publicId = "FILE-$it",
                    rootUri = ROOT,
                    relativePath = it,
                    updatedAt = 1000
                )
            }
        )
        val row = dao.getCatalogTasks(source.id).single()
        return MultiFileTask(
            taskId = row.taskId,
            assets = FILES.associateWith { rawText("SELECT id FROM assets WHERE relativePath = '$it'") }
        )
    }

    private fun folderPlan(): ImportPlan = plan(
        ImporterKind.FOLDER_AS_TASK,
        "",
        FolderSnapshot(
            FILES.map {
                ScannedFile(
                    name = it.substringAfterLast('/'),
                    relativePath = it,
                    uri = "$ROOT/${childRelativePath("", it)}"
                )
            },
            listOf("alice")
        )
    )

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "no row for: $sql" }
            cursor.getString(0)
        }

    private data class MultiFileTask(val taskId: String, val assets: Map<String, String>)

    private companion object {
        const val ROOT = "content://root"

        /** One Task over a folder's worth of files, which is the only shape a page can differ on. */
        val FILES = listOf("alice/a.jpg", "alice/b.jpg")
    }
}
