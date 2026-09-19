package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ending a Todo: what goes, what stays, and in what order.
 *
 * The operation is a hand-written transaction because nothing about it can be left to the schema.
 * `sources` cascades to nothing, so every row it owns has to be named; `list_items.taskId` and
 * `task_imports.sourceId` are both `NO ACTION`, so two of those removals *refuse* to happen in the
 * wrong order. This file is where that ordering is pinned — a later change that reorders the
 * statements fails here rather than on a device, where the failure would be a partially dissolved
 * Todo and no way to say which half went.
 *
 * The other half is what must survive, and it is the harder claim. A Task's decisions go with the
 * Task, and they are supposed to; what does not go is the file's own identity. `file_review_state`
 * is keyed by path and is never touched, so a file deleted and re-imported is the same file to the
 * wire. Asserting the snapshot alone would pass on a delete that erased that row too.
 *
 * Everything here is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoDissolutionTest {

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

    // --- what goes -------------------------------------------------------------------------------

    @Test
    fun `the Todo goes and takes its Tasks, with everything hanging off them`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        dao.deleteTodo(todo, keepState = false, now = 5000)

        assertEquals(0, rawInt("SELECT COUNT(*) FROM sources"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM assets"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state"))
    }

    @Test
    fun `a List holding its Tasks loses the memberships, not the List`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = listOver(todo, "Trip")

        val report = dao.deleteTodo(todo, keepState = false, now = 5000)

        // The List survives with nothing in it. Nothing here removes a List: a List is an order over
        // Tasks, and an order nothing satisfies is empty rather than gone — its title, its webhook
        // and its template are all still the user's.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM lists"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM list_items"))
        assertEquals(listOf(ListLosingItems(listId, "Trip", 2)), report?.lists)
    }

    @Test
    fun `the report counts what was there, so the result can say what it took`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")

        val report = dao.deleteTodo(todo, keepState = false, now = 5000)

        assertEquals("Photos", report?.sourceName)
        assertEquals(2, report?.deletedTaskCount)
        assertTrue(report?.lists.orEmpty().isEmpty())
    }

    @Test
    fun `a Todo that was already gone returns null and changes nothing`() = runTest {
        val todo = importFiles("a.jpg")
        val other = dao.getSource(todo)

        assertNull(dao.deleteTodo("not-a-todo", keepState = true, now = 5000))
        // And the one that does exist is untouched by the attempt.
        assertEquals(other?.id, dao.getSource(todo)?.id)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `one Task held by two Lists loses both memberships`() = runTest {
        val todo = importFiles("a.jpg")
        val first = listOver(todo, "Trip")
        val second = listOver(todo, "Later")

        val report = dao.deleteTodo(todo, keepState = false, now = 5000)

        assertEquals(0, rawInt("SELECT COUNT(*) FROM list_items"))
        // Newest first, which is the order the Lists index reads in and so the order a result
        // screen can name them in without re-sorting.
        assertEquals(
            listOf(
                ListLosingItems(second, "Later", 1),
                ListLosingItems(first, "Trip", 1)
            ),
            report?.lists
        )
    }

    // --- what stays ------------------------------------------------------------------------------

    @Test
    fun `a file keeps the public id it had, because its own row is never touched`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        // The name is minted the way the app mints it: a registration arrives and the assignment
        // copies its `publicId` onto the Asset. Doing it by hand would test the query rather than
        // the path that reaches it.
        dao.registerFiles(
            listOf(
                FileReviewStateEntity(
                    publicId = "FILE-A",
                    rootUri = ROOT,
                    relativePath = "photos/a.jpg",
                    updatedAt = 1000
                )
            )
        )
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))

        dao.deleteTodo(todo, keepState = false, now = 5000)

        // The registration is untouched, and it is the half that matters: `file_review_state` is
        // keyed by `(rootUri, relativePath)`, so a later import of the same path finds this row and
        // mints the same name rather than a new one.
        assertEquals(
            "FILE-A",
            rawText(
                "SELECT publicId FROM file_review_state WHERE relativePath = 'photos/a.jpg'"
            )
        )
        // And the link dangles, which is deliberate rather than overlooked: it is keyed by asset id
        // with no foreign key, so the row outlives the Asset it named. Nothing reads it without
        // joining a live Asset — `assignLegacyItemIds`' `NOT EXISTS` does exactly that — so a
        // dangling row is inert rather than a wrong answer.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM legacy_item_ids li JOIN assets a ON a.id = li.assetId"
            )
        )
    }

    @Test
    fun `review history stays, addressed by a Task nothing can reach any more`() = runTest {
        val todo = importFiles("a.jpg")
        val taskId = dao.getCatalogTasks(todo).first().taskId
        rawExec(
            "INSERT INTO review_events (id, taskId, at, kind, templateId, templateVersion, " +
                "response, contextKind, contextRef) VALUES " +
                "('event-1', '$taskId', 1000, 'decide', 'builtin.media.rapid-review', 1, '{}', " +
                "NULL, NULL)"
        )

        dao.deleteTodo(todo, keepState = false, now = 5000)

        // History is append-oriented by design: it records what happened, and what happened is that
        // somebody judged this Task once. A delete that rewrote it would be editing the past.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    // --- the snapshot ----------------------------------------------------------------------------

    @Test
    fun `keeping the decisions writes one row per Task, keyed by the path it was made over`() =
        runTest {
            val todo = importFiles("a.jpg", "b.jpg")

            dao.deleteTodo(todo, keepState = true, now = 5000)

            assertEquals(2, rawInt("SELECT COUNT(*) FROM dissolved_task_state"))
            assertEquals(
                "photos/a.jpg",
                rawText("SELECT groupPath FROM dissolved_task_state WHERE groupPath = 'photos/a.jpg'")
            )
            // The other path, and the one that decides which folder offers this back. A recovery
            // belongs to the place the intention was made, not to wherever a Task happened to sit.
            assertEquals(
                "photos",
                rawText("SELECT sourcePath FROM dissolved_task_state LIMIT 1")
            )
            assertEquals("Photos", rawText("SELECT sourceName FROM dissolved_task_state LIMIT 1"))
            assertEquals(
                "file-as-task",
                rawText("SELECT importerKind FROM dissolved_task_state LIMIT 1")
            )
            assertEquals(5000, rawInt("SELECT dissolvedAt FROM dissolved_task_state LIMIT 1"))
        }

    @Test
    fun `the snapshot carries each file's own record, including the absence of one`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val taskId = dao.getCatalogTasks(todo).first { it.groupPath == "photos/a.jpg" }.taskId
        val assetId = rawText("SELECT id FROM assets WHERE taskId = '$taskId'")
        // Written straight to the pair's row rather than through `setAssetAction`: the record is the
        // fixture here and the dissolution is the subject. A `★` and an action id together, because
        // the payload spells them as one file's line and only a record holding both says so — and
        // since v0.5.4 they are one *set* on that row rather than a boolean beside a list, so the
        // star is written as the id it now is.
        rawExec(
            "UPDATE task_asset_state SET selections = 'favorite,urgent' " +
                "WHERE assetId = '$assetId'"
        )
        rawExec("UPDATE task_state SET isCompleted = 1 WHERE taskId = '$taskId'")

        dao.deleteTodo(todo, keepState = true, now = 5000)

        // Judged, and the record is the file's: its path and the ids it was carrying, on one line.
        // The literal is the format contract read from the write side — [DissolvedRecordsTest] pins
        // the rest of it — so a snapshot that dropped the file, or wrote the Task's answer once for
        // the whole Todo, fails here. The star sits inside the set in sorted position, which is
        // [encodeActionIds]'s order rather than this fixture's, and is the half that says a `★` is
        // no longer a field of the line.
        assertEquals(
            "photos/a.jpg\tfavorite,urgent",
            rawText("SELECT records FROM dissolved_task_state WHERE groupPath = 'photos/a.jpg'")
        )
        assertEquals(
            DISSOLVED_RECORDS_VERSION,
            rawInt("SELECT recordVersion FROM dissolved_task_state WHERE groupPath='photos/a.jpg'")
        )
        // Never judged, which is a fact worth recording rather than an absence to fill in: a
        // recovery that invented an empty set here would be indistinguishable from one that copied a
        // real "no". The file is still a line — it existed, and this is what it had: a path and the
        // empty set, which is the whole of a file nobody has said anything about.
        assertEquals(
            "photos/b.jpg\t",
            rawText("SELECT records FROM dissolved_task_state WHERE groupPath = 'photos/b.jpg'")
        )
        // Completion is the Task's, so it stays a column and belongs to the Task that was checked.
        assertEquals(
            1,
            rawInt("SELECT isCompleted FROM dissolved_task_state WHERE groupPath='photos/a.jpg'")
        )
        assertEquals(
            0,
            rawInt("SELECT isCompleted FROM dissolved_task_state WHERE groupPath='photos/b.jpg'")
        )
    }

    @Test
    fun `not keeping the decisions writes nothing at all`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")

        val report = dao.deleteTodo(todo, keepState = false, now = 5000)

        assertEquals(0, rawInt("SELECT COUNT(*) FROM dissolved_task_state"))
        assertEquals(false, report?.keptState)
    }

    @Test
    fun `a Todo with no Tasks dissolves to nothing, which is how a failed run is cleaned up`() =
        runTest {
            val todo = dao.registerSource(
                rootUri = ROOT,
                relativePath = "photos",
                displayName = "Photos",
                importerKind = ImporterKind.FILE_AS_TASK,
                keepTree = true,
                now = 1000
            )

            val report = dao.deleteTodo(todo.id, keepState = true, now = 5000)

            assertEquals(0, report?.deletedTaskCount)
            assertEquals(0, rawInt("SELECT COUNT(*) FROM sources"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM dissolved_task_state"))
        }

    // --- the Lists are told ----------------------------------------------------------------------

    @Test
    fun `every List that lost rows is told how many, and no other List is touched`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val holder = listOver(todo, "Trip")
        val bystander = dao.createListFromTasks(
            title = "Elsewhere",
            taskIds = emptyList(),
            exportOnCompletion = false,
            now = 1000
        )

        dao.deleteTodo(todo, keepState = false, now = 5000)

        assertEquals(2, rawInt("SELECT lastDissolvedCount FROM lists WHERE id = $holder"))
        assertEquals(5000, rawInt("SELECT lastDissolvedAt FROM lists WHERE id = $holder"))
        // A List the Todo had nothing to do with has nothing to report, and a note written to every
        // List would be a note that meant nothing on any of them.
        assertTrue(
            rawIsNull("SELECT lastDissolvedCount FROM lists WHERE id = $bystander")
        )
    }

    // --- the fixture -----------------------------------------------------------------------------

    /**
     * A Todo over `photos` holding one file-as-Task Task per name.
     *
     * The snapshot speaks in paths relative to the folder that was scanned — `plan` rebases them
     * through the import root — so a Task made of `a.jpg` here lands on `photos/a.jpg`, which is the
     * identity everything else in the app names it by.
     */
    private suspend fun importFiles(vararg names: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            source.id,
            "photos",
            plan(
                ImporterKind.FILE_AS_TASK,
                "photos",
                FolderSnapshot(
                    names.map {
                        ScannedFile(
                            name = it,
                            relativePath = it,
                            uri = "$ROOT/photos/$it"
                        )
                    },
                    emptyList()
                )
            ),
            2000
        )
        return source.id
    }

    /** A List over every Task the Todo holds. */
    private suspend fun listOver(sourceId: String, title: String): Long =
        dao.createListFromTasks(
            title = title,
            taskIds = dao.getCatalogTasks(sourceId).map { it.taskId },
            exportOnCompletion = false,
            now = 3000
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

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun rawIsNull(sql: String): Boolean =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.isNull(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
