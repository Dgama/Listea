package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that repairs the Lists whose recorded completion had drifted from their own rows.
 *
 * **The one step in the chain that changes no table**, which is what its fixture is built around.
 * Every other migration test here writes its old shape out by hand, for the reason
 * [MigrationV12ToV13Test] gives — but a v22 database and a v23 one are the *same* database with a
 * different number in `PRAGMA user_version`, and that identity is this release's premise rather
 * than its conclusion. Hand-copying four hundred lines of DDL to reproduce a schema that is
 * already correct would be pretending to a difference that does not exist, and would drift from
 * the entities the moment one of them moved. So the fixture is built by the app's own Room at the
 * version the entities declare, given its rows and then **rewound to 22** — and
 * `the step changes no table` asserts the identity the shortcut rests on rather than assuming it.
 *
 * What is under test is therefore a *question* rather than a shape: `lists.completedAt` answers
 * *is this List finished*, and four writes used to move a List's membership without re-asking it.
 * The five rows below are the states those writes could leave behind, and the claim is that after
 * the step every one of them agrees with its own memberships.
 *
 * **The rows read `task_state`, not `list_items`.** Every membership here carries
 * `list_items.isCompleted = 0`, which is the dead column the older schema used for the same
 * question — so a repair written against the wrong one would complete nothing at all, and that
 * would be legible here rather than only in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV22ToV23Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the states the four writes could leave behind ----------------------------------------------

    @Test
    fun `a List whose Tasks are all done is completed by the step`() {
        // The reported defect: built from Tasks that were already complete, so nothing downstream of
        // the creation ever re-asked the question. It read `2 / 2` and sat under *In progress*.
        writeFixture()
        val database = migrate()

        assertNotNull(
            "a finished List is recorded as finished",
            database.timestamp(1)
        )
    }

    @Test
    fun `a List with an unfinished Task is left unfinished`() {
        // The other half, and the guard against a step that simply completes everything: list 2 has
        // one done membership and one not, and must stay exactly as it was.
        writeFixture()
        val database = migrate()

        assertNull(database.timestamp(2))
    }

    @Test
    fun `a List that was already recorded complete keeps its own timestamp`() {
        // The rows that were never wrong are not touched, which matters because `completedAt` is
        // not only read for null-ness: it is sent in a webhook payload. A repair that stamped every
        // complete List with the upgrade time would silently rewrite the moment it finished.
        writeFixture()
        val database = migrate()

        assertEquals(9000L, database.timestamp(3))
    }

    @Test
    fun `a List that lost all its rows stops claiming to be complete`() {
        // A dissolution empties it, and the memberships go with the Tasks. `refreshCompletion`
        // refuses to call an empty List finished, and this step is that rule written as SQL — so
        // the repair moves a row *out* of completion as readily as into it.
        writeFixture()
        val database = migrate()

        assertNull(database.timestamp(4))
    }

    @Test
    fun `a List recorded complete that still has an unfinished Task is un-completed`() {
        // A List that gained an unfinished Task, or whose Task was reopened, after it had been
        // marked — the drift in the direction nothing had noticed, because the row's own label
        // reads `1 / 2` while the screen files it under *Completed*.
        writeFixture()
        val database = migrate()

        assertNull(database.timestamp(5))
    }

    // --- and the shape it rests on --------------------------------------------------------------

    @Test
    fun `the step changes no table`() {
        // The premise the fixture is built on, asserted rather than assumed: a v22 database and a
        // v23 one differ by a number and by nothing else. If a later release makes this step's
        // schema move, this is where that is noticed — and the fixture above stops being honest at
        // the same moment.
        writeFixture()
        val before = schema()
        migrate()

        assertEquals(before, schema())
    }

    // --- the fixture ------------------------------------------------------------------------------

    /**
     * The five Lists the step is asked about, written into a database whose schema is the real one
     * and whose version is then wound back to 22.
     *
     * `task_state` and `list_items` are written separately and deliberately disagree — see the class
     * note. The `tasks` rows are owed because Room opens with foreign keys on, and they are written
     * bare: nothing the step runs reads that table.
     */
    private fun writeFixture() {
        val database = openAtCurrentVersion()
        database.exec(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "lastDissolvedReason, taskReviewTemplateId, taskReviewTemplateVersion) VALUES " +
                "(1, 'all done', 1000, NULL, 0, '', 'dissolved', 'builtin.media.rapid-review', 1), " +
                "(2, 'one left', 1000, NULL, 0, '', 'dissolved', 'builtin.media.rapid-review', 1), " +
                "(3, 'done and stamped', 1000, 9000, 0, '', 'dissolved', " +
                "'builtin.media.rapid-review', 1), " +
                "(4, 'emptied', 1000, 8000, 0, '', 'dissolved', 'builtin.media.rapid-review', 1), " +
                "(5, 'reopened', 1000, 7000, 0, '', 'dissolved', " +
                "'builtin.media.rapid-review', 1)"
        )
        // The Tasks the memberships point at. `tasks` is two columns wide, and Room opens with
        // foreign keys on, so these are owed rather than optional.
        database.exec(
            "INSERT INTO tasks (id, createdAt) VALUES " +
                "('t1', 1), ('t2', 1), ('t3', 1), ('t4', 1), ('t5', 1)"
        )
        database.exec(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('t1', 1, 0, '', 1), ('t2', 1, 0, '', 1), ('t3', 0, 0, '', 1), " +
                "('t4', 1, 0, '', 1), ('t5', 0, 0, '', 1)"
        )
        var membership = 0
        // `isCompleted = 0` on every row, on purpose: the dead column, and the trap this test
        // exists to catch a repair falling into.
        fun member(listId: Int, taskId: String) = database.exec(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceMissing, isFavorite, customActions) VALUES " +
                "(${++membership}, 'p$membership', $listId, '$taskId', 'row', 0, " +
                "$membership, 1000, 0, 0, '')"
        )
        member(1, "t1"); member(1, "t2")
        member(2, "t1"); member(2, "t3")
        member(3, "t4")
        // list 4 gets nothing: a List whose memberships were all taken away.
        member(5, "t4"); member(5, "t5")

        database.close()
        rewind()
    }

    /** The app's own schema, at the version the entities declare, with nothing migrated. */
    private fun openAtCurrentVersion(): ListeaDatabase {
        context.deleteDatabase(V22_DB)
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V22_DB)
            .allowMainThreadQueries()
            .build()
        // Opening is what creates it; nothing else here needs the Room instance.
        database.openHelper.writableDatabase
        return database
    }

    /**
     * The version, and nothing else. This is the whole difference between the fixture and the
     * database the step expects, which is the claim `the step changes no table` checks.
     */
    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V22_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = 22 }
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V22_DB)
            .addMigrations(ListeaDatabase.MIGRATION_22_23)
            .build()
            .also { it.openHelper.writableDatabase }

    private fun ListeaDatabase.exec(sql: String) {
        openHelper.writableDatabase.execSQL(sql)
    }

    /** One List's recorded completion, or null when it is not recorded as finished. */
    private fun ListeaDatabase.timestamp(listId: Int): Long? =
        openHelper.writableDatabase.query(
            "SELECT completedAt FROM lists WHERE id = $listId"
        ).use { row ->
            assertTrue("no List $listId", row.moveToFirst())
            if (row.isNull(0)) null else row.getLong(0)
        }

    /** Every table and index, as SQLite wrote them — compared across the step, not parsed. */
    private fun schema(): List<String> =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V22_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name",
                null
            )
                .use { row ->
                    buildList {
                        while (row.moveToNext()) add(row.getString(0))
                    }
                }
        }

    private companion object {
        const val V22_DB = "v22-fixture.db"
    }
}
