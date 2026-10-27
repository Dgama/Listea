package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives every committed submission's events a row of their own.
 *
 * **Additive, and derived.** The response table is not touched: what a person committed stays
 * exactly where it was, byte for byte. The new table is a child of `review_events`, built by reading
 * each stored response through the app's own domain reader — the same reader the review surface
 * uses, not a second grammar written for the migration. A response this build cannot read is left
 * out rather than guessed at; a later build understands what this one did not.
 *
 * **Asset references are provenance.** The event's path is a source URI, and the backfill resolves
 * it against the Assets under the same Task. A URI no Asset still carries leaves `assetId` null:
 * the event remains a record that a point was taken, and a missing file is never a reason to drop
 * the fact.
 *
 * The fixture is built at the version the entities declare and [subtractV31Additions] takes this
 * step's own table back off. `30.json` must not gain it, and `31.json` is the shape Room validates
 * every upgrade against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV30ToV31Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun writeFixture() {
        context.deleteDatabase(V30_DB)
        Room.databaseBuilder(context, ListeaDatabase::class.java, V30_DB)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .let { database ->
                database.exec(
                    "INSERT INTO tasks (id, createdAt, publicId) VALUES " +
                        "('t-1', 1, 'task-public-1'), ('t-2', 2, 'task-public-2')"
                )
                database.exec(
                    "INSERT INTO assets (id, taskId, sortOrder, sourceMissing, sourceUri, publicId) " +
                        "VALUES ('a-1', 't-1', 0, 0, 'content://media/photo-1', 'asset-public')"
                )
                // A readable submission with one point.
                database.exec(
                    reviewEvent(
                        REVIEW_ONE, "t-1", 100,
                        primary(point(0.25, 0.50, "#1E88E5", "content://media/photo-1"))
                    )
                )
                // Two points, one from a file the Task no longer holds.
                database.exec(
                    reviewEvent(
                        REVIEW_TWO, "t-1", 200,
                        primary(
                            point(0.1000, 0.2000, "#43A047", "content://media/photo-1"),
                            point(0.3000, 0.4000, "#E53935", "content://media/gone")
                        )
                    )
                )
                // The legacy grammar: a judgment with no events.
                database.exec(
                    reviewEvent(REVIEW_LEGACY, "t-1", 300, "completed=true;selections=favorite")
                )
                // A primary envelope from a later build: unreadable here, and left alone.
                database.exec(
                    reviewEvent(
                        REVIEW_NEWER, "t-1", 400,
                        "primary=3\ncompleted=true\nevent=image.point\t0.5\t0.5\t#000000\tx"
                    )
                )
                // A Task with no Asset at all still records that a point was taken.
                database.exec(
                    reviewEvent(
                        REVIEW_NO_ASSET, "t-2", 500,
                        primary(point(0.9, 0.9, "#FFFFFF", "content://media/nowhere"))
                    )
                )

                database.subtractV31Additions()
                database.close()
            }
        rewind()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v30 database`() {
        val tables = tables()
        assertFalse(tables.contains("review_event_answers"))
        assertEquals(V30, fixture().version)
    }

    @Test
    fun `the step takes a v30 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        assertTrue(tables().contains("review_event_answers"))
        // `fieldKey` is 32 -> 33's; the fixture is read at the current version, and this list names
        // the whole shape rather than counting this step's five columns.
        assertEquals(
            listOf("reviewEventId", "ordinal", "assetId", "type", "value", "fieldKey"),
            columnsOf(database, "review_event_answers")
        )
    }

    @Test
    fun `the exported schema records the addition and leaves the previous version alone`() {
        val v30 = schemaJson("30.json")
        val v31 = schemaJson("31.json")

        assertFalse("v30.json must not gain the table", v30.contains("review_event_answers"))
        assertTrue("v31.json must carry the table", v31.contains("review_event_answers"))
        assertTrue(v31.contains("ON DELETE CASCADE"))
    }

    // --- the backfill -----------------------------------------------------------------------------

    @Test
    fun `a readable submission's events become rows, in reported order`() {
        val database = migrate()

        assertEquals(2, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-2'"))
        assertEquals(
            "a-1",
            text(database, "SELECT assetId FROM review_event_answers WHERE reviewEventId = 'r-2' AND ordinal = 0")
        )
        assertEquals(
            "image.point",
            text(database, "SELECT type FROM review_event_answers WHERE reviewEventId = 'r-2' AND ordinal = 0")
        )
        assertEquals(
            "the value is the response line's own event half",
            "0.1000\t0.2000\t#43A047\tcontent://media/photo-1",
            text(database, "SELECT value FROM review_event_answers WHERE reviewEventId = 'r-2' AND ordinal = 0")
        )
        assertEquals(
            "a file the Task no longer holds leaves the reference null, never drops the event",
            1,
            count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-2' AND ordinal = 1 AND assetId IS NULL")
        )
    }

    @Test
    fun `the asset a point came from is resolved under the same Task`() {
        val database = migrate()

        assertEquals("a-1", text(database, "SELECT assetId FROM review_event_answers WHERE reviewEventId = 'r-1'"))
        assertNull(
            "a Task with no Assets still records that the point was taken",
            database.openHelper.writableDatabase.query(
                "SELECT assetId FROM review_event_answers WHERE reviewEventId = 'r-5'"
            ).use { row ->
                assertTrue(row.moveToFirst())
                if (row.isNull(0)) null else row.getString(0)
            }
        )
        assertEquals(1, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-5'"))
    }

    @Test
    fun `a judgment with no events and a response this build cannot read write nothing`() {
        val database = migrate()

        assertEquals(0, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-3'"))
        assertEquals(0, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-4'"))
        // And the record of the judgments themselves is untouched.
        assertEquals(5, count(database, "SELECT COUNT(*) FROM review_events"))
        assertEquals(
            "completed=true;selections=favorite",
            text(database, "SELECT response FROM review_events WHERE id = 'r-3'")
        )
    }

    @Test
    fun `the ordinal continues past an unreadable later build's envelope`() {
        val database = migrate()
        // r-2's two events are ordinals 0 and 1; there is no third from r-4.
        assertEquals(2, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-2'"))
        assertEquals(
            0,
            count(
                database,
                "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-2' AND ordinal > 1"
            )
        )
    }

    @Test
    fun `deleting a submission deletes the events it caught`() {
        val database = migrate()

        database.exec("DELETE FROM review_events WHERE id = 'r-2'")

        assertEquals(0, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-2'"))
        assertEquals(
            "and the other submissions keep theirs",
            2,
            count(database, "SELECT COUNT(*) FROM review_event_answers")
        )
        assertEquals(1, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-1'"))
        assertEquals(1, count(database, "SELECT COUNT(*) FROM review_event_answers WHERE reviewEventId = 'r-5'"))
    }

    // --- fixture and reading helpers -------------------------------------------------------------

    /** One stored submission, with [lines] joined onto the response after the envelope. */
    private fun reviewEvent(
        id: String,
        taskId: String,
        at: Long,
        vararg lines: String
    ): String {
        val response = lines.joinToString("\n").replace("'", "''")
        return "INSERT INTO review_events (id, taskId, at, kind, templateId, templateVersion, " +
            "response, contextKind, contextRef) VALUES " +
            "('$id', '$taskId', $at, 'submitted', 'builtin.image.sample-review', 1, " +
            "'$response', NULL, NULL)"
    }

    /** One event line, in the response's own spelling. */
    private fun point(x: Double, y: Double, color: String, path: String): String =
        "event=image.point\t" +
            String.format(java.util.Locale.ROOT, "%.4f\t%.4f\t%s\t%s", x, y, color, path)

    /** A canonical primary response carrying [events], in order. */
    private fun primary(vararg events: String): String =
        (listOf("primary=2", "completed=true") + events).joinToString("\n")

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V30_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V30 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V30_DB)
            .addMigrations(ListeaDatabase.MIGRATION_30_31,
ListeaDatabase.MIGRATION_31_32,
ListeaDatabase.MIGRATION_32_33
)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun schemaJson(name: String): String =
        java.io.File("schemas/com.example.listea.data.ListeaDatabase/$name").readText()

    private fun columnsOf(database: ListeaDatabase, table: String): List<String> =
        database.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { row ->
            buildList { while (row.moveToNext()) add(row.getString(1)) }
        }

    private fun text(database: ListeaDatabase, sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun count(database: ListeaDatabase, sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun version(database: ListeaDatabase): Int =
        count(database, "PRAGMA user_version")

    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V30_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun tables(): List<String> =
        fixture().use { database ->
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                null
            ).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(0)) }
            }
        }

    private companion object {
        const val V30_DB = "v30-fixture.db"
        const val V30 = 30
        const val CURRENT = 33

        const val REVIEW_ONE = "r-1"
        const val REVIEW_TWO = "r-2"
        const val REVIEW_LEGACY = "r-3"
        const val REVIEW_NEWER = "r-4"
        const val REVIEW_NO_ASSET = "r-5"
    }
}
