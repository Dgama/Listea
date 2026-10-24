package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * The step that gives every Task and Asset the id it leaves the device under.
 *
 * Room validates the shape a migration ends at, but it cannot say whether the *values* are right:
 * a backfill that left every `publicId` equal would pass every schema check and put one name on
 * every row a receiver sees. So the precedence rules are pinned here — the old wire names where
 * they exist, the List item's name for a single-Asset Task, and a minted id everywhere else.
 *
 * **The fixture is v25 with this step's additions taken back off**, following
 * [MigrationV25ToV26Test]'s siblings: built at the version the entities declare, populated, then
 * [subtractV26Additions] removes the two columns and `PRAGMA user_version` is put back to 25.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV25ToV26Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v25 database`() {
        // The subtraction, asserted. A fixture that still had the columns would give the migration
        // nothing to add, and every assertion below would pass over a schema this step never
        // produced.
        assertFalse(columnsOf("tasks").contains("publicId"))
        assertFalse(columnsOf("assets").contains("publicId"))
        assertEquals(V25, fixture().version)
    }

    @Test
    fun `the step takes a v25 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        assertTrue(columnsOf("tasks").contains("publicId"))
        assertTrue(columnsOf("assets").contains("publicId"))
    }

    // --- what the backfill preserves --------------------------------------------------------------

    @Test
    fun `a Task named by the old folder-round table keeps its id`() {
        val database = migrate()

        assertEquals(
            "TASK-LEGACY",
            text(database, "SELECT publicId FROM tasks WHERE id = 't-legacy'")
        )
    }

    @Test
    fun `a file named by the old registration keeps its id`() {
        // A file's registration id was the name the old wire sent, so it is the name the new wire
        // keeps — even though this Task also has a List item that could have named it.
        val database = migrate()

        assertEquals(
            "ASSET-LEGACY",
            text(database, "SELECT publicId FROM assets WHERE id = 'a-legacy'")
        )
    }

    @Test
    fun `a single-Asset Task hands its old list id to the file`() {
        // That shape's List item *was* the file: one Task, one Asset. Its public id was the file's
        // name on the wire, so the Asset inherits it and a receiver's old records still join.
        val database = migrate()

        assertEquals(
            "ROW-COPIED",
            text(database, "SELECT publicId FROM assets WHERE id = 'a-row'")
        )
    }

    @Test
    fun `a multi-Asset Task's list id is not copied onto an arbitrary file`() {
        // Three Assets and one List row: the id named the Task's presentation, not any one file.
        // Copying it onto the first would tell a receiver that one of three files was the item.
        val database = migrate()

        val ids = column(database, "SELECT publicId FROM assets WHERE taskId = 't-multi'")
        assertEquals(3, ids.size)
        assertFalse(ids.contains("ROW-MULTI"))
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `a shared file under two Tasks gets two ids`() {
        // Two presentations of one file are two judgments, and their names must not collide just
        // because the path does.
        val database = migrate()

        val ids = column(
            database,
            "SELECT publicId FROM assets WHERE relativePath = 'cos/e.jpg' ORDER BY taskId"
        )
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `minted ids carry the Task's own creation time`() {
        // The time prefix says when the row came to exist, not when this release arrived. The
        // fingerprint is not asserted — it is a hint — but the clock is readable and load-bearing.
        val database = migrate()

        val task = text(database, "SELECT publicId FROM tasks WHERE id = 't-minted'")
        assertEquals(900L, itemPublicIdTime(task))

        val asset = text(database, "SELECT publicId FROM assets WHERE id = 'a-minted'")
        assertEquals(900L, itemPublicIdTime(asset))
    }

    @Test
    fun `no row is left nameless, and no two names are the same`() {
        // The migration itself refuses empty ids; this asserts the refusal held and that the
        // uniqueness the delivery path assumes is true of the fixtures it will find.
        val database = migrate()

        assertEquals(0, count(database, "SELECT COUNT(*) FROM tasks WHERE publicId = ''"))
        assertEquals(0, count(database, "SELECT COUNT(*) FROM assets WHERE publicId = ''"))

        val all = column(database, "SELECT publicId FROM tasks UNION ALL SELECT publicId FROM assets")
        assertEquals(all.size, all.distinct().size)
    }

    // --- fixture ----------------------------------------------------------------------------------

    /**
     * A v25 database holding every id shape the backfill has to tell apart.
     *
     * t-legacy  — named in `legacy_task_ids`
     * t-single  — one Asset, which `legacy_item_ids` names
     * t-row     — one Asset, named only by a List item
     * t-multi   — three Assets and one List item; nothing may be copied
     * t-minted  — no List item, no legacy name; minted at its `createdAt`
     * t-share-1/2 — the same file under two Tasks; two Assets, two ids
     */
    @Before
    fun writeFixture() {
        context.deleteDatabase(V25_DB)
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V25_DB)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase

        database.exec(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "exportKind, lastDeliveryStatus, lastDissolvedReason) VALUES " +
                "(1, 'Trip', 1, NULL, 1, '', 'none', NULL, 'none')"
        )
        database.exec(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES " +
                "('t-legacy', 500, ''), ('t-single', 600, ''), ('t-row', 700, ''), " +
                "('t-multi', 800, ''), ('t-minted', 900, ''), " +
                "('t-share-1', 1000, ''), ('t-share-2', 1100, '')"
        )
        database.exec(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified, publicId) VALUES " +
                "('a-legacy', 't-single', 0, 'content://root', 'cos/a.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-row', 't-row', 0, 'content://root', 'cos/b.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-m1', 't-multi', 0, 'content://root', 'cos/c.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-m2', 't-multi', 1, 'content://root', 'cos/d.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-m3', 't-multi', 2, 'content://root', 'cos/f.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-minted', 't-minted', 0, 'content://root', 'cos/g.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-share-1', 't-share-1', 0, 'content://root', 'cos/e.jpg', 0, NULL, NULL, NULL, ''), " +
                "('a-share-2', 't-share-2', 0, 'content://root', 'cos/e.jpg', 0, NULL, NULL, NULL, '')"
        )
        database.exec(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceMissing, isFavorite, customActions, selections) VALUES " +
                "(1, 'ROW-COPIED', 1, 't-row', 'b.jpg', 0, 0, 1, 0, 0, '', ''), " +
                "(2, 'ROW-MULTI', 1, 't-multi', 'cos', 0, 1, 1, 0, 0, '', '')"
        )
        database.exec("INSERT INTO legacy_task_ids (taskId, publicId) VALUES ('t-legacy', 'TASK-LEGACY')")
        database.exec("INSERT INTO legacy_item_ids (assetId, publicId) VALUES ('a-legacy', 'ASSET-LEGACY')")

        database.subtractV26Additions()
        database.close()
        rewind()
    }

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V25_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V25 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V25_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_25_26,
                ListeaDatabase.MIGRATION_26_27,
                ListeaDatabase.MIGRATION_27_28,
            ListeaDatabase.MIGRATION_28_29,
            ListeaDatabase.MIGRATION_29_30,
            ListeaDatabase.MIGRATION_30_31
        )
            .build()
        database.openHelper.writableDatabase
        return database
    }

    // --- reading the fixture and the result without entity mapping ---------------------------------

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

    private fun column(database: ListeaDatabase, sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { row ->
            buildList { while (row.moveToNext()) add(row.getString(0)) }
        }

    private fun version(database: ListeaDatabase): Int =
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { row ->
            assertTrue("no row for the version", row.moveToFirst())
            row.getInt(0)
        }

    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V25_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun columnsOf(table: String): List<String> =
        fixture().use { database ->
            database.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private companion object {
        const val V25_DB = "v25-fixture.db"
        const val V25 = 25
        const val CURRENT = 31
    }
}
