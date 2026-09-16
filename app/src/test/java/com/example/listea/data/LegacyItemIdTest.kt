package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The names a folder round puts on the wire, and where they come from now that a registration is
 * not where they live.
 *
 * A list row has always been named by its own `publicId` and still is. A file resolved out of a
 * folder had no row to borrow one from, so it was named by its registration — the row v0.2 is
 * dismantling — and the name had to move somewhere that outlives it. `legacy_item_ids` is that
 * place: one row per Asset, holding the same string. §8 of the plan states the boundary, and this
 * is it: the side table is the only thing standing between a folder round and an id it can send.
 *
 * What is worth pinning is not the move, which is invisible while both copies exist, but the two
 * properties that make it safe to have made. The string is the registration's own rather than a
 * fresh one, so no receiver sees an id change it was never told about; and a file is named once,
 * so opening the same folder twice does not churn the ids under it.
 *
 * The two orders matter and are tested separately. A folder opened for review registers its files
 * before anything has been decided, and a List over the same folder mints their Tasks before
 * anybody thinks to review them — so whichever half arrives first, the naming has to catch up
 * when the other does. Minting is now reached through [ListsDao.taskIdsOf] rather than
 * through a write, because resolving a Task is a resolution concern and the write path no longer
 * mints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyItemIdTest {

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

    // --- a file is named whichever half arrives second --------------------------------------------

    @Test
    fun `a file registered before it has a Task is named once the Task arrives`() = runTest {
        register("a.jpg")

        // Registered and nothing else: no decision, so no Asset, so nothing for a name to hang on.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))

        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)

        assertEquals("FILE-a.jpg", rawText("SELECT publicId FROM legacy_item_ids"))
    }

    @Test
    fun `a file that had a Task first is named when it is registered`() = runTest {
        // A List over the folder, which mints a Task for every file under it. Then a folder review
        // that finds the same file — the order where registration has nothing to name yet, because
        // the naming is done the other way round.
        folderList(files = listOf("a.jpg"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))

        register("a.jpg")

        assertEquals("FILE-a.jpg", rawText("SELECT publicId FROM legacy_item_ids"))
    }

    @Test
    fun `a folder resolved for review names every file the round can send`() = runTest {
        // The order Quick Review actually uses: the whole listing is registered first, and the
        // Tasks are resolved after it — one of which has to be minted, because nothing has been
        // decided about it. A step that only named files on the other order would leave this queue
        // sending ids it had nowhere to resolve.
        register("a.jpg", "b.jpg")
        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)

        val resolved = dao.taskIdsOf(ROOT, listOf("a.jpg", "b.jpg"), 2000)

        assertEquals(setOf("a.jpg", "b.jpg"), resolved.map { it.relativePath }.toSet())
        assertEquals(
            setOf("FILE-a.jpg", "FILE-b.jpg"),
            dao.getFileStates(ROOT, listOf("a.jpg", "b.jpg")).map { it.publicId }.toSet()
        )
        // `b.jpg` was minted by that call and named by it; `a.jpg` was already minted, and was
        // named when it was resolved.
        assertEquals(2, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))
    }

    @Test
    fun `an Asset the migration minted is named when a Task is next resolved for it`() = runTest {
        register("a.jpg")

        // The shape the migration leaves behind. A list row whose registration was absent got a
        // Task and an Asset and no name to go with them, because there was no registration to copy
        // one from at the time. Reproduced by hand because nothing else can produce it: every
        // other Asset in the app is minted by the one function that names what it mints, and this
        // is the only state where the two halves are already in place and the name is still owed.
        rawExec("INSERT INTO tasks (id, createdAt) VALUES ('task-a', 1000)")
        rawExec(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) " +
                "VALUES ('asset-a', 'task-a', 0, '$ROOT', 'a.jpg', 0)"
        )
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))

        // A List built over the same folder resolves that Asset rather than minting a new one, and
        // takes the name the file has been owed since the registration arrived.
        folderList(files = listOf("a.jpg"))

        assertEquals("FILE-a.jpg", rawText("SELECT publicId FROM legacy_item_ids"))
    }

    // --- the string itself -------------------------------------------------------------------------

    @Test
    fun `the name a folder round sends is the registration's own`() = runTest {
        register("a.jpg")
        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)

        // Read the way the payload reads it. The same string it sent before the move, because the
        // side table was handed the registration's value rather than minting one of its own.
        assertEquals("FILE-a.jpg", dao.getFileStates(ROOT, listOf("a.jpg")).single().publicId)
        assertEquals("FILE-a.jpg", rawText("SELECT publicId FROM legacy_item_ids"))
    }

    @Test
    fun `an undecided file is still named for the queue it is in`() = runTest {
        register("a.jpg", "b.jpg")
        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)

        // `b.jpg` has no Asset, so it has no row in the side table and never will until somebody
        // decides about it — and it must still come back from the queue read carrying the name it
        // has always had. The fallback is not a rival scheme; it is the same string, covering the
        // window before there is anywhere to have copied it to.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_item_ids WHERE publicId = 'FILE-b.jpg'"))
        assertEquals(
            setOf("FILE-a.jpg", "FILE-b.jpg"),
            dao.getFileStates(ROOT, listOf("a.jpg", "b.jpg")).map { it.publicId }.toSet()
        )
    }

    @Test
    fun `the same path under two roots is two files, named separately`() = runTest {
        // A path is only unique *under a grant*, which is what the Asset's uniqueness is declared
        // on. Naming by path alone would hand one root's review the other root's id — the same way
        // reading a decision by path alone would hand it the other root's answer.
        register("a.jpg")
        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)
        dao.registerFiles(
            listOf(
                FileReviewStateEntity(
                    publicId = "FILE-other-a",
                    rootUri = OTHER,
                    relativePath = "a.jpg",
                    updatedAt = 1000
                )
            )
        )
        dao.taskIdsOf(OTHER, listOf("a.jpg"), now = 2000)

        assertEquals(
            listOf("FILE-a.jpg", "FILE-other-a"),
            rawTexts("SELECT publicId FROM legacy_item_ids ORDER BY publicId")
        )
    }

    @Test
    fun `naming a file a second time does not rename it`() = runTest {
        register("a.jpg")
        dao.taskIdsOf(ROOT, listOf("a.jpg"), now = 2000)

        // Deliberately provoked, because nothing in the app rewrites a registration's publicId —
        // and that is exactly why the promise needs a test rather than an argument. Opening a
        // folder registers the same files again every time; what a receiver holds must not move
        // underneath it because somebody looked.
        rawExec("UPDATE file_review_state SET publicId = 'FILE-renamed' WHERE relativePath = 'a.jpg'")
        register("a.jpg")

        assertEquals("FILE-a.jpg", rawText("SELECT publicId FROM legacy_item_ids"))
    }

    // --- and what is deliberately never named ------------------------------------------------------

    @Test
    fun `a file with no registration is never named`() = runTest {
        // A List over a folder mints Tasks for every file under it, and not one of those files has
        // been resolved for review. A receiver hears about them through the list row's own id, so
        // there is nothing here the side table should be holding.
        folderList(files = listOf("a.jpg", "b.jpg"))

        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private suspend fun folderList(files: List<String>): Long = dao.createFolderList(
        title = "Trip",
        rootUri = ROOT,
        relativePath = "",
        files = files.map { ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it") },
        replacedListIds = emptyList(),
        webhookEnabled = false,
        webhookUrl = "",
        now = 1000
    )

    /** Registration as Quick Review does it: idempotent, asserting nothing about the files. */
    private suspend fun register(vararg rootRelativePaths: String) = dao.registerFiles(
        rootRelativePaths.map {
            FileReviewStateEntity(
                publicId = "FILE-$it",
                rootUri = ROOT,
                relativePath = it,
                updatedAt = 1000
            )
        }
    )

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

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

    private fun rawTexts(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val ROOT = "content://root"
        const val OTHER = "content://other"
    }
}
