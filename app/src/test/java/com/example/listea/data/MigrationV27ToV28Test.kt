package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives PDF passages somewhere to keep their marks.
 *
 * **One table, and two kinds of thing it must not do.** It must not take a mark away when the
 * template that offered its tool is deleted — that is the entire hide-not-delete rule, and it is why
 * the table has no foreign key to `templates`. It must take a mark away when its Asset is deleted,
 * because an Asset an import stopped finding is no longer a file for a passage to be on.
 *
 * The fixture is built at the version the entities declare and [subtractV28Additions] takes the
 * table back off, following the other migration tests. `27.json` and `28.json` are read from disk:
 * the first must not have gained this table, and the second is the shape Room will validate every
 * upgrade against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV27ToV28Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun writeFixture() {
        context.deleteDatabase(V27_DB)
        Room.databaseBuilder(context, ListeaDatabase::class.java, V27_DB)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .let { database ->
                database.exec(
                    "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-1', 1, 'task-public')"
                )
                database.exec(
                    "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, " +
                        "sourceMissing, publicId) VALUES " +
                        "('a-1', 't-1', 0, 'content://root', 'doc.pdf', 0, 'asset-public')"
                )
                database.subtractV28Additions()
                database.close()
            }
        rewind()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v27 database`() {
        assertFalse(tables().contains("task_asset_marks"))
        assertEquals(V27, fixture().version)
    }

    @Test
    fun `the step takes a v27 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        assertTrue(tables().contains("task_asset_marks"))
        assertEquals(
            listOf(
                "id", "assetId", "toolId", "authoredTemplateId", "authoredTemplateVersion",
                "kind", "value", "anchorVersion", "documentFingerprint", "pageIndex", "charStart",
                "charEnd", "charParser", "exactText", "prefix", "suffix", "createdAt", "updatedAt"
            ),
            columnsOf(database, "task_asset_marks")
        )
        assertEquals(
            listOf("index_task_asset_marks_assetId"),
            indicesOf(database, "task_asset_marks")
        )
    }

    @Test
    fun `the exported schema records the table and leaves the previous version alone`() {
        val v27 = schemaJson("27.json")
        val v28 = schemaJson("28.json")

        assertFalse("v27.json must not gain a table that did not exist at v27", v27.contains("task_asset_marks"))
        assertTrue(v28.contains("task_asset_marks"))
        assertTrue(v28.contains("index_task_asset_marks_assetId"))
        assertTrue(v28.contains("ON DELETE CASCADE"))
    }

    // --- what the keys mean ----------------------------------------------------------------------

    @Test
    fun `deleting an Asset deletes its marks`() {
        val database = migrate()
        database.exec(
            "INSERT INTO task_asset_marks (id, assetId, toolId, authoredTemplateId, " +
                "authoredTemplateVersion, kind, value, anchorVersion, documentFingerprint, " +
                "pageIndex, charStart, charEnd, charParser, exactText, prefix, suffix, " +
                "createdAt, updatedAt) VALUES " +
                "('m-1', 'a-1', 'tool-comment', 'tpl', 1, 'comment', 'note', 1, 'sha', " +
                "0, 0, 3, 'nucleus-pdfium/158.0.8111.0', 'word', '', '', 1, 1)"
        )

        database.exec("DELETE FROM assets WHERE id = 'a-1'")

        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_asset_marks"))
    }

    @Test
    fun `deleting a template leaves its marks alone`() {
        val database = migrate()
        database.exec("INSERT INTO templates (id, name, createdAt) VALUES ('tpl', 'PDF', 1)")
        database.exec(
            "INSERT INTO task_asset_marks (id, assetId, toolId, authoredTemplateId, " +
                "authoredTemplateVersion, kind, value, anchorVersion, documentFingerprint, " +
                "pageIndex, charStart, charEnd, charParser, exactText, prefix, suffix, " +
                "createdAt, updatedAt) VALUES " +
                "('m-1', 'a-1', 'tool-comment', 'tpl', 1, 'comment', 'note', 1, 'sha', " +
                "0, 0, 3, 'nucleus-pdfium/158.0.8111.0', 'word', '', '', 1, 1)"
        )

        database.exec("DELETE FROM templates WHERE id = 'tpl'")

        assertEquals(1, count(database, "SELECT COUNT(*) FROM task_asset_marks"))
        assertEquals(
            "tpl",
            text(database, "SELECT authoredTemplateId FROM task_asset_marks WHERE id = 'm-1'")
        )
    }

    // --- fixture and reading helpers -------------------------------------------------------------

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V27_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V27 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V27_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_27_28,
                ListeaDatabase.MIGRATION_28_29,
                ListeaDatabase.MIGRATION_29_30,
                ListeaDatabase.MIGRATION_30_31,
                ListeaDatabase.MIGRATION_31_32,
                ListeaDatabase.MIGRATION_32_33,
                ListeaDatabase.MIGRATION_33_34
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

    private fun indicesOf(database: ListeaDatabase, table: String): List<String> =
        database.openHelper.writableDatabase.query("PRAGMA index_list($table)").use { row ->
            buildList {
                while (row.moveToNext()) {
                    val name = row.getString(1)
                    // The primary key's automatic index is SQLite's, not this step's.
                    if (!name.startsWith("sqlite_autoindex")) add(name)
                }
            }
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
            context.getDatabasePath(V27_DB).path,
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
        const val V27_DB = "v27-fixture.db"
        const val V27 = 27
        const val CURRENT = 34
    }
}
