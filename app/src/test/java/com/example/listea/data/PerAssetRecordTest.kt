package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.childRelativePath
import com.example.listea.domain.Templates
import com.example.listea.plan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule v0.5.1 states, at the level it was reported: **a mark belongs to one file under one
 * Task's control**, and to nothing else.
 *
 * It was reported from the camera — a tag put on the first picture came up on the second, in the
 * chips and in the info sheet both — and the cause was that the mark was stored against the Task
 * while a folder round shows several files of that Task one at a time. So the two shapes below are
 * the two ways a Task and a file can fail to line up, and every test is written to be *false* under
 * the old storage:
 *
 *  * **One Task, several files.** A folder-as-Task import puts a folder's worth of files under one
 *    Task, which is exactly the fixture the camera bug needs and the one no earlier fixture had.
 *  * **One file, several Tasks.** The same file imported by two Todos is two Tasks over one path —
 *    the chef's egg, bought from the same market for two dishes — and the two are allowed to
 *    disagree about it.
 *
 * Both are about the pair, so a `setAssetAction` call always names both halves and the assertions
 * are always about one file's record and its neighbour's. The union a List shows over a multi-file
 * Task is in here too, because that is a fold over the pairs rather than a second place they are
 * stored, and the difference matters: a fold and a copy behave the same until the day they don't.
 *
 * The Task's own half is deliberately not per-pair, and two tests say so by holding the two halves
 * apart: completion moves every file of a Task at once, while tags never move together unless they
 * were put there together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerAssetRecordTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- one Task, several files -------------------------------------------------------------------

    @Test
    fun `a tag on one file of a Task is not on its neighbour`() = runTest {
        val pair = twoFileTask()
        val first = pair.assets.getValue("alice/a.jpg")
        val second = pair.assets.getValue("alice/b.jpg")

        dao.setAssetAction(pair.taskId, first, ItemAction("p2"), enabled = true, now = 2000)
        dao.setAssetAction(pair.taskId, first, ItemAction("p5"), enabled = true, now = 2001)

        // The report, verbatim: two pictures, two tags on the first, and the second exactly as it
        // was. Both halves are asserted because the bug showed in both — the chips drew them and
        // the info sheet listed them — and one query feeding one of the two is how it survived.
        val row = catalogRow(pair.sourceId).single()
        assertEquals(
            setOf("p2", "p5"),
            row.assets.first { it.assetId == first }.decisions.selections
        )
        assertEquals(
            AssetDecisions(),
            row.assets.first { it.assetId == second }.decisions
        )
    }

    @Test
    fun `two files of one Task hold two different tags at once`() = runTest {
        val pair = twoFileTask()
        val first = pair.assets.getValue("alice/a.jpg")
        val second = pair.assets.getValue("alice/b.jpg")

        dao.setAssetAction(pair.taskId, first, ItemAction("p2"), enabled = true, now = 2000)
        dao.setAssetAction(pair.taskId, second, ItemAction("p7"), enabled = true, now = 2001)
        dao.setAssetAction(pair.taskId, first, ItemAction.Favourite, enabled = true, now = 2002)

        val row = catalogRow(pair.sourceId).single()
        val byId = row.assets.associateBy { it.assetId }

        // The positive half of the test above: not only does a tag fail to spread, two files under
        // one Task can be marked differently at the same moment — the thing the old storage made
        // impossible rather than merely unlikely.
        //
        // The first file holds both of its marks in the one set, because `★` is a button like any
        // other since v0.5.4: what used to be a boolean beside the ids is now an id among them, and
        // a set that named only `p2` would be describing a storage this build no longer has.
        assertEquals(
            setOf("p2", Templates.FAVOURITE_BUTTON_ID),
            byId.getValue(first).decisions.selections
        )
        assertEquals(setOf("p7"), byId.getValue(second).decisions.selections)
        assertTrue(Templates.FAVOURITE_BUTTON_ID in byId.getValue(first).decisions.selections)
        assertFalse(Templates.FAVOURITE_BUTTON_ID in byId.getValue(second).decisions.selections)
    }

    @Test
    fun `a file under a Task arrives with nothing marked`() = runTest {
        val pair = twoFileTask()

        // Importing a file must not assert anything about it, the same promise `registerFiles`
        // makes: a pair row exists from the moment the Asset does, and it says nothing.
        assertEquals(
            listOf(AssetDecisions(), AssetDecisions()),
            catalogRow(pair.sourceId).single().assets.map { it.decisions }
        )
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_asset_state"))
    }

    @Test
    fun `completion is the Task's, and every file of it reads the same`() = runTest {
        val pair = twoFileTask()
        dao.setAssetAction(
            pair.taskId, pair.assets.getValue("alice/a.jpg"), ItemAction.Favourite,
            enabled = true, now = 2000
        )

        dao.setTaskCompleted(pair.taskId, completed = true, now = 2100)

        // The outer band is the Task's — see the three-band rule in `v0.5.1.md` — so ticking one
        // file settles the whole Task, and both files read as done. The tag on the first is
        // untouched, which is the point of the pair being the *inner* half: a decision about the
        // whole must not quietly rewrite the decisions about the parts.
        val states = dao.getFileStates(ROOT, listOf("alice/a.jpg", "alice/b.jpg"))
        assertEquals(2, states.size)
        assertTrue(states.all { it.decisions.isCompleted })
        assertEquals(
            mapOf("alice/a.jpg" to true, "alice/b.jpg" to false),
            states.associate {
                it.relativePath to (Templates.FAVOURITE_BUTTON_ID in it.decisions.selections)
            }
        )
    }

    @Test
    fun `re-importing the folder leaves each file's tag where it was`() = runTest {
        val pair = twoFileTask()
        val first = pair.assets.getValue("alice/a.jpg")
        dao.setAssetAction(pair.taskId, first, ItemAction("p2"), enabled = true, now = 2000)

        val result = dao.runImport(
            pair.sourceId, "", folderPlan("", FILES, listOf("alice")), 3000
        )

        // A run re-reads the files it already has and updates them in place, so the Asset ids do
        // not move — and neither does anything filed under them. This is the property the whole
        // design rests on: the record is keyed by an id that survives the folder being scanned
        // again, which is what lets a tag outlive a refresh.
        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, result.tasksCreated)
        assertEquals(first, pair.assets.getValue("alice/a.jpg"))
        assertEquals(
            setOf("p2"),
            catalogRow(pair.sourceId).single().assets
                .first { it.assetId == first }.decisions.selections
        )
    }

    @Test
    fun `a List row over a multi-file Task shows the union of its files' marks`() = runTest {
        val pair = twoFileTask()
        dao.setAssetAction(
            pair.taskId, pair.assets.getValue("alice/a.jpg"), ItemAction("p2"),
            enabled = true, now = 2000
        )
        dao.setAssetAction(
            pair.taskId, pair.assets.getValue("alice/b.jpg"), ItemAction("p7"),
            enabled = true, now = 2001
        )
        dao.setAssetAction(
            pair.taskId, pair.assets.getValue("alice/b.jpg"), ItemAction.Favourite,
            enabled = true, now = 2002
        )
        val listId = dao.createListFromTasks("Holiday", listOf(pair.taskId), false,3000)

        // A List row has no single file on screen, so there is no one pair to show. It folds
        // instead, and the fold is the union: a Task's own reading is everything marked on any of
        // its files. Both readers are checked because they are separate SQL — one live, one
        // one-shot — and a payload built from the second while the screen shows the first is how
        // a receiver ends up told something nobody saw.
        val live = dao.observeItems(listId).first().single().decisions
        val oneShot = dao.getItems(listId).single().decisions

        assertEquals(
            ReviewDecisions(
                isCompleted = false,
                selections = setOf("p2", "p7", Templates.FAVOURITE_BUTTON_ID)
            ),
            live
        )
        assertEquals(live, oneShot)
    }

    // --- one file, several Tasks -------------------------------------------------------------------

    @Test
    fun `the same file under two Todos keeps two records`() = runTest {
        val east = todo("East")
        val west = todo("West")
        val eastPair = filePair(east)
        val westPair = filePair(west)

        // One path, two Tasks, two Assets: the market supplying the same egg twice. The Assets are
        // separate rows because their uniqueness is declared per Task, so each Todo's intention
        // gets its own file record to be marked up against.
        assertEquals(
            2,
            rawInt("SELECT COUNT(*) FROM assets WHERE relativePath = 'a.jpg'")
        )
        assertNotEquals(eastPair.taskId, westPair.taskId)
        assertNotEquals(eastPair.assetId, westPair.assetId)

        dao.setAssetAction(
            eastPair.taskId, eastPair.assetId, ItemAction("cust1"), enabled = true, now = 2000
        )
        dao.setAssetAction(
            eastPair.taskId, eastPair.assetId, ItemAction("cust2"), enabled = true, now = 2001
        )

        assertEquals(
            setOf("cust1", "cust2"),
            catalogRow(east.id).single().assets.single().decisions.selections
        )
        // The other Todo's record is untouched — not copied, not shared, not a rival copy of the
        // same answer. Two Todos over one file are two intentions and are allowed to disagree.
        assertEquals(
            AssetDecisions(),
            catalogRow(west.id).single().assets.single().decisions
        )
    }

    @Test
    fun `a file under a second Todo arrives with nothing marked`() = runTest {
        val east = todo("East")
        val eastPair = filePair(east)
        dao.setAssetAction(
            eastPair.taskId, eastPair.assetId, ItemAction("cust1"), enabled = true, now = 2000
        )

        val west = todo("West")
        val westPair = filePair(west)

        // Marking a file for one dish says nothing about the other dish. The second import mints a
        // Task and an Asset and no opinion, even though the very same path is already marked
        // twice over elsewhere.
        assertEquals(AssetDecisions(), catalogRow(west.id).single().assets.single().decisions)
        assertEquals(
            setOf("cust1"),
            catalogRow(east.id).single().assets.single().decisions.selections
        )
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_asset_state WHERE selections = ''"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_asset_state"))
        assertNotEquals(westPair.assetId, eastPair.assetId)
    }

    @Test
    fun `taking a mark off one Todo leaves the other Todo's alone`() = runTest {
        val east = todo("East")
        val eastPair = filePair(east)
        val west = todo("West")
        val westPair = filePair(west)
        dao.setAssetAction(
            eastPair.taskId, eastPair.assetId, ItemAction("cust1"), enabled = true, now = 2000
        )
        dao.setAssetAction(
            westPair.taskId, westPair.assetId, ItemAction("cust3"), enabled = true, now = 2001
        )

        dao.setAssetAction(
            eastPair.taskId, eastPair.assetId, ItemAction("cust1"), enabled = false, now = 3000
        )

        // The undo is addressed the same way the mark was, so it lands on one pair. A write that
        // reached by path — which is how the two Todos' rows were once the same row — would take
        // the other Todo's tag off with it.
        assertEquals(AssetDecisions(), catalogRow(east.id).single().assets.single().decisions)
        assertEquals(
            setOf("cust3"),
            catalogRow(west.id).single().assets.single().decisions.selections
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A Todo over the whole root, importing a file at its own path. */
    private suspend fun todo(displayName: String): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "",
        displayName = displayName,
        importerKind = ImporterKind.FILE_AS_TASK,
        keepTree = true,
        now = 1000
    )

    /** Imports `a.jpg` under [source] and resolves the pair it made. */
    private suspend fun filePair(source: SourceEntity): FilePair {
        dao.runImport(source.id, "a.jpg", filePlan("", listOf("a.jpg")), 2000)
        val row = catalogRow(source.id).single()
        return FilePair(row.taskId, row.assets.single().assetId)
    }

    /**
     * One Task over a folder of two files: `alice/a.jpg` and `alice/b.jpg` grouped as `alice`.
     *
     * A folder-as-Task run is the only import shape that puts more than one Asset under one Task,
     * which makes it the only fixture in which the reported bug is even expressible.
     */
    private suspend fun twoFileTask(): MultiFileTask {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Root",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(source.id, "", folderPlan("", FILES, listOf("alice")), 2000)
        // Registration as a folder round does it. The per-file readers — the browser's ticks, the
        // delete path, a folder review's queue — are driven from `file_review_state` and join out to
        // the Asset, so a fixture that never registered has no per-file row for them to answer
        // about. It asserts nothing about the files and mints no Asset of its own.
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
        val row = catalogRow(source.id).single()
        // Keyed by path rather than read off the row, because [ReviewAsset] carries only the file's
        // own name — `a.jpg`, `b.jpg` — and the paths are what the fixture is about. Reading the
        // two ids straight out of `assets` is also the plainest way to say "these two rows, and no
        // others" before a single tag has been written.
        return MultiFileTask(
            sourceId = source.id,
            taskId = row.taskId,
            assets = FILES.associateWith { rawText("SELECT id FROM assets WHERE relativePath = '$it'") }
        )
    }

    /** What a Todo's own page reads: one row per Task it imported, with every Asset of it. */
    private suspend fun catalogRow(sourceId: String): List<CatalogRow> =
        dao.getCatalogTasks(sourceId)

    private fun filePlan(importRoot: String, files: List<String>): com.example.listea.ImportPlan =
        plan(
            ImporterKind.FILE_AS_TASK,
            importRoot,
            FolderSnapshot(files.map { scanned(importRoot, it) }, emptyList())
        )

    private fun folderPlan(
        importRoot: String,
        files: List<String>,
        directories: List<String>
    ): com.example.listea.ImportPlan = plan(
        ImporterKind.FOLDER_AS_TASK,
        importRoot,
        FolderSnapshot(files.map { scanned(importRoot, it) }, directories)
    )

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    /** A Source and the one Task it imported, with every Asset it made keyed by path. */
    private data class MultiFileTask(
        val sourceId: String,
        val taskId: String,
        val assets: Map<String, String>
    )

    private companion object {
        const val ROOT = "content://root"

        /** The two files the folder-as-Task fixture imports, both under `alice`. */
        val FILES = listOf("alice/a.jpg", "alice/b.jpg")
    }
}
