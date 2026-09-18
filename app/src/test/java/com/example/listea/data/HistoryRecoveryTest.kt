package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan
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
 * Changing your mind back: what a dissolved Todo can offer, and to whom.
 *
 * The whole feature is one join, and its correctness is almost entirely a question of *scope*. The
 * match is `(rootUri, groupPath)` and nothing else, so this file is about the ways a looser match
 * would go wrong rather than about the copy itself. Two histories can sit at one folder, so a match
 * that did not name the history would mix two abandonments into a Todo that never existed as an
 * intention; a path the new run did not produce has nothing to receive a judgment, and a history
 * whose grouping rule differs has no path in common at all, so "recovers nothing" is an answer and
 * not a failure.
 *
 * What a recovery is *not* is the other half. Nothing about the old Tasks returns — they had no ids
 * worth keeping — so a history is a source of judgments that is read and left alone. `not consumed`
 * is asserted rather than assumed because a delete-on-read would be invisible in every other test
 * here and would make a recovery a thing you can only do once.
 *
 * Everything here is real SQL through Room, for the reason [ListsDaoTest] gives.
 *
 * **What this file stopped covering in v0.5.1, and does not pretend to.** The feature reads and
 * writes `task_state.isFavorite` / `customActions`, which v0.5.1 retired — the marks live on the
 * Task–Asset pairs now. So [judge] and [stateOf] are the *dead* columns at both ends, and every
 * `favorite` and `actions` assertion below still passes while the app would show neither. The
 * `completed` half is honest, because completion stayed on `task_state`. This is recorded rather
 * than repaired: the fix is a per-file history and a schema step, and it is `v0.5.1-plan.md` §9.
 * Until then, read these tests as covering *scope* — which Todo a judgment reaches — and not the
 * marks themselves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRecoveryTest {

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

    // --- what a folder offers --------------------------------------------------------------------

    @Test
    fun `a folder offers back the histories dissolved at it, newest first`() = runTest {
        dissolve(judgedTodo("photos", "a.jpg", REASONED), at = 5000)
        dissolve(judgedTodo("photos", "b.jpg", REASONED), at = 6000)
        dissolve(judgedTodo("clips", "c.mp4", REASONED), at = 7000)

        val offered = dao.dissolvedHistoriesAt(ROOT, "photos")

        // Newest first, because the picker is opened by somebody who has just changed their mind and
        // the most recent abandonment is the one they are looking for.
        assertEquals(listOf(6000L, 5000L), offered.map { it.dissolvedAt })
        assertEquals(listOf(1, 1), offered.map { it.taskCount })
        assertEquals(listOf("Photos", "Photos"), offered.map { it.sourceName })
        // Scoped to the folder the intention was made at, not to the grant. A Todo dissolved over
        // `Holiday/` is not offered to something being imported at the root of the same grant —
        // they are different places, and a name in common would be a coincidence.
        assertEquals(listOf(7000L), dao.dissolvedHistoriesAt(ROOT, "clips").map { it.dissolvedAt })
        assertTrue(dao.dissolvedHistoriesAt(ROOT, "").isEmpty())
    }

    @Test
    fun `a Todo that kept nothing offers nothing`() = runTest {
        val quiet = importFiles("photos", "a.jpg")
        dao.deleteTodo(quiet, keepState = false, now = 5000)

        // Nothing was written, so there is no history — which is the point of the checkbox rather
        // than an accident of the read. A user who declined to keep the decisions is not asked,
        // later, whether they would like them back.
        assertTrue(dao.dissolvedHistoriesAt(ROOT, "photos").isEmpty())
    }

    // --- what a recovery does --------------------------------------------------------------------

    @Test
    fun `a recovered Task takes the judgment that was made about its path`() = runTest {
        val todo = importFiles("photos", "a.jpg", "b.jpg")
        judge(todo, "photos/a.jpg", completed = true, favorite = true, actions = "urgent")
        dao.deleteTodo(todo, keepState = true, now = 5000)

        val result = reimport("photos", listOf("a.jpg", "b.jpg"), recoverFrom = todo)

        assertEquals(2, result.tasksRecovered)
        val fresh = sourceOf("photos")
        // `completed` is the half of this that is still true of the app. The `favorite` and `urgent`
        // in the same triple are read off the retired columns — see the class note — so this test
        // says the judgment *reaches* the right Task and not that a user would see it.
        assertEquals(Judgment(true, true, "urgent"), stateOf(fresh, "photos/a.jpg"))
        // Untouched stays untouched. Recovering a Task that was never judged writes the absence of a
        // judgment rather than inventing one, which is why the snapshot records the absence too.
        assertEquals(Judgment(false, false, ""), stateOf(fresh, "photos/b.jpg"))
    }

    @Test
    fun `a file the new import did not produce has nothing to recover`() = runTest {
        val todo = importFiles("photos", "a.jpg", "b.jpg")
        judge(todo, "photos/a.jpg", completed = true, favorite = false, actions = "")
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // Only one of the two paths comes back. The history holds two judgments and reaches one
        // Task, and the run reports what it actually reached rather than what it was offered.
        val result = reimport("photos", listOf("b.jpg"), recoverFrom = todo)

        assertEquals(1, result.tasksRecovered)
        assertEquals(Judgment(false, false, ""), stateOf(sourceOf("photos"), "photos/b.jpg"))
    }

    @Test
    fun `a history whose grouping rule differs recovers nothing at all`() = runTest {
        // A folder-as-Task Todo judged one group, `alice`.
        val folders = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            folders.id,
            "photos",
            plan(
                ImporterKind.FOLDER_AS_TASK,
                "photos",
                FolderSnapshot(
                    files = listOf(scanned("alice/a.jpg")),
                    directories = listOf("alice")
                )
            ),
            2000
        )
        judge(folders.id, "photos/alice", completed = true, favorite = false, actions = "")
        dao.deleteTodo(folders.id, keepState = true, now = 5000)

        // The same folder, imported file-as-Task this time, so the paths are the files rather than
        // the folders. The judgments were about *groups* and the new Todo has no such groups, so
        // there is nothing to match — honest rather than broken, and the picker is what should say
        // so beforehand by reporting the count a history carries.
        val result = reimport("photos", listOf("alice/a.jpg"), recoverFrom = folders.id)

        assertEquals(0, result.tasksRecovered)
        assertEquals(
            Judgment(false, false, ""),
            stateOf(sourceOf("photos"), "photos/alice/a.jpg")
        )
    }

    @Test
    fun `two histories at one folder do not leak into each other`() = runTest {
        val first = importFiles("photos", "a.jpg")
        judge(first, "photos/a.jpg", completed = true, favorite = false, actions = "urgent")
        dao.deleteTodo(first, keepState = true, now = 5000)

        val second = importFiles("photos", "a.jpg")
        judge(second, "photos/a.jpg", completed = false, favorite = true, actions = "later")
        dao.deleteTodo(second, keepState = true, now = 6000)

        // Both are offered, because both happened.
        assertEquals(2, dao.dissolvedHistoriesAt(ROOT, "photos").size)

        // And recovering from one takes that one entirely. A match that did not name the history
        // would read both rows for the same path and land on whichever the database returned last,
        // producing a Task judged `urgent` and `later` at once — a judgment nobody made.
        val result = reimport("photos", listOf("a.jpg"), recoverFrom = first)
        assertEquals(1, result.tasksRecovered)
        assertEquals(Judgment(true, false, "urgent"), stateOf(sourceOf("photos"), "photos/a.jpg"))
    }

    @Test
    fun `a history is read, not consumed`() = runTest {
        val todo = importFiles("photos", "a.jpg")
        judge(todo, "photos/a.jpg", completed = true, favorite = true, actions = "urgent")
        dao.deleteTodo(todo, keepState = true, now = 5000)

        val first = reimport("photos", listOf("a.jpg"), recoverFrom = todo)
        assertEquals(1, first.tasksRecovered)
        // The new Task is then judged differently, which is what a second run would overwrite.
        judge(sourceOf("photos"), "photos/a.jpg", completed = false, favorite = false, actions = "")

        // A later clock, because two Todos at one folder registered in the same millisecond leave
        // "the newest" with nothing to be newest *than* — and `sourcesCovering` breaks that tie by
        // id, which is a UUID. The app cannot produce the tie; only a fixture with a frozen clock
        // can, so the fixture is what has to avoid it.
        val second = reimport("photos", listOf("a.jpg"), recoverFrom = todo, now = 7000)

        assertEquals(1, second.tasksRecovered)
        assertEquals(Judgment(true, true, "urgent"), stateOf(sourceOf("photos"), "photos/a.jpg"))
        // Still on offer afterwards. A history that a recovery deleted would make this a thing you
        // can do once and then have to remember not to want again.
        assertEquals(1, dao.dissolvedHistoriesAt(ROOT, "photos").size)
    }

    // --- the fixture -----------------------------------------------------------------------------

    /** Why a Task was judged, so a recovered judgment is unmistakably the one that was made. */
    private data class Judgment(
        val completed: Boolean,
        val favorite: Boolean,
        val actions: String
    )

    /**
     * A Todo over [folder] holding one file-as-Task Task per name.
     *
     * The snapshot speaks in paths relative to the scanned folder — `plan` rebases them through the
     * import root — so a Task made of `a.jpg` under `photos` lands on `photos/a.jpg`, which is the
     * identity the history is keyed by.
     */
    private suspend fun importFiles(folder: String, vararg names: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = displayNameFor(folder),
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FILE_AS_TASK,
                folder,
                FolderSnapshot(names.map { scanned(it) }, emptyList())
            ),
            2000
        )
        return source.id
    }

    /** A Todo that has been judged and then deleted, which is the only way a history exists. */
    private suspend fun judgedTodo(folder: String, name: String, action: String): String {
        val todo = importFiles(folder, name)
        judge(todo, "$folder/$name", completed = true, favorite = false, actions = action)
        return todo
    }

    private suspend fun dissolve(todoId: String, at: Long) {
        dao.deleteTodo(todoId, keepState = true, now = at)
    }

    /** A second Todo over the same folder, born knowing whatever [recoverFrom] has to say. */
    private suspend fun reimport(
        folder: String,
        names: List<String>,
        recoverFrom: String? = null,
        now: Long = 6000
    ): ImportResult {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = displayNameFor(folder),
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = now
        )
        return dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FILE_AS_TASK,
                folder,
                FolderSnapshot(names.map { scanned(it) }, emptyList())
            ),
            now,
            recoverFrom = recoverFrom
        )
    }

    private fun displayNameFor(folder: String) =
        folder.replaceFirstChar { it.uppercaseChar() }

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/photos/$relativePath"
    )

    /**
     * Writes a judgment straight to the row rather than through `submitReview`.
     *
     * The judgment is the fixture here and the recovery is the subject; going through the review
     * path would also record an event and refresh completion, which are other tests' business.
     *
     * `isFavorite` and `customActions` are the columns v0.5.1 retired, so this seam writes what the
     * feature writes and nothing a user can see. See the class note, and `v0.5.1-plan.md` §9.
     */
    private fun judge(
        sourceId: String,
        groupPath: String,
        completed: Boolean,
        favorite: Boolean,
        actions: String
    ) {
        rawExec(
            "UPDATE task_state SET isCompleted = ${if (completed) 1 else 0}, " +
                "isFavorite = ${if (favorite) 1 else 0}, customActions = '$actions' " +
                "WHERE taskId = (SELECT taskId FROM task_imports WHERE sourceId = '$sourceId' " +
                "AND groupPath = '$groupPath')"
        )
    }

    private fun stateOf(sourceId: String, groupPath: String): Judgment =
        database.openHelper.writableDatabase.query(
            "SELECT ts.isCompleted, ts.isFavorite, ts.customActions FROM task_imports ti " +
                "JOIN task_state ts ON ts.taskId = ti.taskId " +
                "WHERE ti.sourceId = '$sourceId' AND ti.groupPath = '$groupPath'"
        ).use { cursor ->
            assertTrue("no Task at $groupPath in $sourceId", cursor.moveToFirst())
            Judgment(cursor.getInt(0) != 0, cursor.getInt(1) != 0, cursor.getString(2))
        }

    /**
     * The newest Todo over a folder, which after a `reimport` is the one it just made.
     *
     * Requires the one it just made to be the newest, which is why [reimport]'s clock is a parameter
     * rather than a constant: a test that imports twice is a test that has to say when.
     */
    private suspend fun sourceOf(folder: String): String =
        dao.sourcesCovering(ROOT, folder).maxByOrNull { it.createdAt }!!.id

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private companion object {
        const val ROOT = "content://root"
        const val REASONED = "urgent"
    }
}
