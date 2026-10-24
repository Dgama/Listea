package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.Templates
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives every install a template to start from, and turns `★` into a button.
 *
 * **The step is additive, and that is the property under test.** It changes the meaning of nothing
 * already stored: it creates two tables, seeds one built-in version into them, and adds a column
 * whose value it computes from the two columns beside it. Everything the app read before the step it
 * still reads after it, unchanged — which is what makes this step landable on its own, and what the
 * fixture below is built to check by comparison rather than by argument.
 *
 * The comparison is available because the backfill leaves a second copy of its own answer: every
 * pair's marks are still sitting in `isFavorite` and `customActions` after `selections` has been
 * written from them. So the claim "no mark was lost or invented" is one a test can make directly,
 * file by file, on rows a real database could have produced.
 *
 * **The fixture is the app's own schema with this step's additions subtracted**, following
 * [MigrationV22ToV23Test]'s reasoning rather than [MigrationV12ToV13Test]'s. Writing v23's hundred
 * and eighty lines of DDL out by hand would be reproducing a schema the entities already declare,
 * and would drift from them the moment one moved. So it is built at the current version by Room,
 * given its rows, and then wound back: this step's additions removed by
 * [subtractV24Additions] — the two new tables dropped, `task_asset_state` rebuilt to its v23 shape —
 * and `PRAGMA user_version` put back to 23. `the fixture is really a v23 database` asserts each half
 * of that subtraction, so the shortcut is checked rather than trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV23ToV24Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the template every install starts with -----------------------------------------------------

    @Test
    fun `the built-in asset control is seeded as a stored version`() {
        writeFixture()
        val database = migrate()

        val seed = Templates.AssetControlSeed
        // Scoped to this step's seed rather than counting the whole table: the walk ends at the
        // version the app ships, and v0.8.1 seeds its two Task Reviews on the way.
        assertEquals(
            1,
            count(
                database,
                "SELECT COUNT(*) FROM templates WHERE id = '${seed.ref.templateId.value}'"
            )
        )
        assertEquals(
            seed.name,
            text(
                database,
                "SELECT name FROM templates WHERE id = '${seed.ref.templateId.value}'"
            )
        )

        assertEquals(
            1,
            count(
                database,
                "SELECT COUNT(*) FROM template_versions " +
                    "WHERE templateId = '${seed.ref.templateId.value}'"
            )
        )
        assertEquals(
            seed.ref.version,
            count(
                database,
                "SELECT version FROM template_versions " +
                    "WHERE templateId = '${seed.ref.templateId.value}'"
            )
        )
    }

    @Test
    fun `the seeded version carries the scope and the buttons in their own encodings`() {
        // Asserted twice over, and the two assertions catch different mistakes. The golden strings
        // below are what the columns must literally hold — the only thing that would notice an
        // encoder whose two halves drifted together. The decoded comparison is what ties the row to
        // the code, so a seed that gains a button without a migration to carry it fails here.
        writeFixture()
        val database = migrate()

        // Every read is scoped to this step's seed: the walk continues past v24 to the version the
        // app ships, and v0.8.1 stores two Task Reviews that are just as much rows in this table.
        val row = "WHERE templateId = '${Templates.AssetControlSeed.ref.templateId.value}'"
        assertEquals(
            "kind:image,video",
            text(database, "SELECT scope FROM template_versions $row")
        )
        assertEquals(
            "favorite\t★\tboolean\tfavorite\ncustom1\tC1\ttag\tcust1\ncustom2\tC2\ttag\tcust2",
            text(database, "SELECT buttons FROM template_versions $row")
        )
        assertEquals(
            TEMPLATE_BUTTONS_VERSION,
            count(database, "SELECT buttonsVersion FROM template_versions $row")
        )

        val seed = Templates.AssetControlSeed
        assertEquals(
            seed.scope,
            decodeTemplateScope(text(database, "SELECT scope FROM template_versions $row"))
        )
        assertEquals(
            seed.buttons,
            decodeTemplateButtons(
                text(database, "SELECT buttons FROM template_versions $row"),
                count(database, "SELECT buttonsVersion FROM template_versions $row")
            )
        )
    }

    // --- every shape a pair's marks could be in -----------------------------------------------------

    @Test
    fun `a boolean favourite becomes an id in the set`() {
        // The row this release is about: `★` was a column of its own, and the file carrying nothing
        // but it is the one a merge written per-column would leave behind.
        writeFixture()
        val database = migrate()

        assertEquals("favorite", selections(database, "a1"))
    }

    @Test
    fun `the favourite joins the tags in one sorted set`() {
        // Sorted, not concatenated in the order the columns happen to sit in: `encodeActionIds` sorts
        // so that the same set always writes the same string, and a merge that appended rather than
        // unioned would write `custom1,custom2,favorite` here and `favorite,custom1,custom2` on the
        // next device — the same marks, disagreeing about their own text.
        writeFixture()
        val database = migrate()

        assertEquals("custom1,custom2,favorite", selections(database, "a2"))
        assertEquals("custom1,favorite", selections(database, "a5"))
    }

    @Test
    fun `a pair carrying neither gets an empty set`() {
        // The ordinary case, and the one that would go wrong quietly: an unmarked file must read as
        // an empty set rather than as a set holding a blank id, which is what a naive join over a
        // null-ish value produces.
        writeFixture()
        val database = migrate()

        assertEquals("", selections(database, "a3"))
    }

    @Test
    fun `the tags come across when there is no favourite`() {
        writeFixture()
        val database = migrate()

        assertEquals("custom2", selections(database, "a4"))
    }

    @Test
    fun `the columns it copied from still hold what they held`() {
        // The verification the backfill makes available by being additive, used rather than admired:
        // after the step, each pair's `selections` can be checked against the two columns it came
        // from. A merge that dropped the favourite, doubled a tag or invented one is legible here
        // even though nothing in the app reads those columns any more.
        writeFixture()
        val database = migrate()

        assertEquals(1, count(database, "SELECT isFavorite FROM task_asset_state WHERE assetId = 'a1'"))
        assertEquals("", text(database, "SELECT customActions FROM task_asset_state WHERE assetId = 'a1'"))
        assertEquals(1, count(database, "SELECT isFavorite FROM task_asset_state WHERE assetId = 'a2'"))
        assertEquals(
            "custom1,custom2",
            text(database, "SELECT customActions FROM task_asset_state WHERE assetId = 'a2'")
        )
        assertEquals(0, count(database, "SELECT isFavorite FROM task_asset_state WHERE assetId = 'a3'"))
        assertEquals(0, count(database, "SELECT isFavorite FROM task_asset_state WHERE assetId = 'a4'"))
        assertEquals(
            "custom2",
            text(database, "SELECT customActions FROM task_asset_state WHERE assetId = 'a4'")
        )
    }

    @Test
    fun `the tables the step does not touch keep their rows`() {
        // A `CREATE TABLE` written by hand is only checked against the entities if the table survives
        // to the current version — here, through the ALTER that follows it. And a step that got its
        // `WHERE` wrong is a step that deletes marks, which nothing else would report.
        writeFixture()
        val database = migrate()

        assertEquals(5, count(database, "SELECT COUNT(*) FROM task_asset_state"))
        assertEquals(5, count(database, "SELECT COUNT(*) FROM assets"))
        assertEquals(2, count(database, "SELECT COUNT(*) FROM tasks"))
    }

    // --- and the shape the fixture rests on ---------------------------------------------------------

    @Test
    fun `the fixture is really a v23 database`() {
        // Both halves of the subtraction, asserted: a database with the two tables already in it is
        // not a fixture for this step, and one whose `task_asset_state` already has `selections` has
        // nothing for the backfill to prove.
        writeFixture()

        assertFalse("templates must not exist at v23", hasTable("templates"))
        assertFalse("template_versions must not exist at v23", hasTable("template_versions"))
        assertFalse(
            "task_asset_state must not have selections at v23",
            columnsOf("task_asset_state").contains("selections")
        )
        assertEquals(
            listOf("assetId", "isFavorite", "customActions", "updatedAt"),
            columnsOf("task_asset_state")
        )
    }

    // --- the fixture ------------------------------------------------------------------------------

    /**
     * Five pairs in the five shapes a mark could be in, written into a database whose schema is the
     * real one and then wound back to the v23 it would have been.
     *
     * Two Tasks rather than one, because the Assets are separate rows and a backfill that walked
     * tasks instead of pairs would be visible as a pair that kept another's marks. The paths are
     * distinct so the unique index over `(taskId, rootUri, relativePath)` is satisfied by
     * construction rather than by luck.
     */
    private fun writeFixture() {
        val database = openAtCurrentVersion()
        database.exec(
            "INSERT INTO tasks (id, createdAt) VALUES ('t1', 1), ('t2', 1)"
        )
        database.exec(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) VALUES " +
                "('a1', 't1', 0, 'content://root', 'a.jpg', 0), " +
                "('a2', 't1', 1, 'content://root', 'b.jpg', 0), " +
                "('a3', 't1', 2, 'content://root', 'c.jpg', 0), " +
                "('a4', 't2', 0, 'content://root', 'd.jpg', 0), " +
                "('a5', 't2', 1, 'content://root', 'e.jpg', 0)"
        )
        database.exec(
            "INSERT INTO task_asset_state (assetId, isFavorite, customActions, updatedAt) VALUES " +
                "('a1', 1, '', 1), " +
                "('a2', 1, 'custom1,custom2', 1), " +
                "('a3', 0, '', 1), " +
                "('a4', 0, 'custom2', 1), " +
                "('a5', 1, 'custom1', 1)"
        )
        database.subtractV24Additions()
        database.close()
        rewind()
    }

    /** The app's own schema, at the version the entities declare, with nothing migrated. */
    private fun openAtCurrentVersion(): ListeaDatabase {
        context.deleteDatabase(V23_DB)
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V23_DB)
            .allowMainThreadQueries()
            .build()
        // Opening is what creates it; nothing else here needs the Room instance.
        database.openHelper.writableDatabase
        return database
    }

    /** The version, and nothing else — see `the fixture is really a v23 database`. */
    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V23_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = 23 }
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V23_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_23_24,
                ListeaDatabase.MIGRATION_24_25,
                ListeaDatabase.MIGRATION_25_26,
                ListeaDatabase.MIGRATION_26_27,
                ListeaDatabase.MIGRATION_27_28,
            ListeaDatabase.MIGRATION_28_29,
            ListeaDatabase.MIGRATION_29_30,
            ListeaDatabase.MIGRATION_30_31,
            ListeaDatabase.MIGRATION_31_32
        )
            .build()
            .also { it.openHelper.writableDatabase }

    /** One pair's marks, as the one column that answers the question now holds them. */    private fun selections(database: ListeaDatabase, assetId: String): String =
        text(database, "SELECT selections FROM task_asset_state WHERE assetId = '$assetId'")

    /** A single column of a single row, asserted to exist rather than defaulted. */
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

    /**
     * The fixture's own shape, read without Room.
     *
     * Deliberately not through the Room instance: opening this database with the migration container
     * attached *is* the thing these two questions come before, and an answer that arrived after the
     * step had run would be about a different database than the one being asked about.
     */
    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V23_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun hasTable(name: String): Boolean =
        fixture().use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name)
            ).use { row ->
                assertTrue("no row for the sqlite_master count", row.moveToFirst())
                row.getInt(0) > 0
            }
        }

    private fun columnsOf(table: String): List<String> =
        fixture().use { database ->
            database.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private companion object {
        const val V23_DB = "v23-fixture.db"
    }
}
