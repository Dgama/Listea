package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration that gives a Task a place to have come from, without changing what a Task is.
 *
 * A raw v13 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: schemas 1..11 were never exported, and the only honest way to test a migration against
 * real old data is to write the old shape out by hand. This one is written to the shape `13.json`
 * records, and it deliberately contains every case the migration has to tell apart:
 *
 * - a file-backed Task whose `list_items` row carries a document URI and measurements, which must be
 *   copied onto its Asset;
 * - a file-backed Task whose `list_items` row carries neither, whose Asset must keep nulls;
 * - a Quick-Review-only file that has no `list_items` row at all, whose Asset must keep nulls;
 * - a manual item with no Asset, which must get no `legacy_source_tasks` row.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a `CREATE TABLE` here that disagrees with `14.json` fails this test rather than
 * a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV13ToV14Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the claim set ---------------------------------------------------------------------------

    @Test
    fun `every file-backed Task is claimed, and nothing else is`() {
        writeFixture()
        val database = migrate()

        // The three Tasks that have a file-backed Asset are named; the manual one is not, because
        // it has no source material for anything to import and legacy_manual_tasks already names it.
        assertEquals(
            3,
            database.count("SELECT COUNT(*) FROM legacy_source_tasks")
        )
        for (relativePath in listOf("2026-07-11/a.jpg", "2026-07-11/b.jpg", "2026-07-11/solo.jpg")) {
            assertEquals(
                1,
                database.count(
                    "SELECT COUNT(*) FROM legacy_source_tasks WHERE taskId = " +
                        "(SELECT taskId FROM assets WHERE relativePath = '$relativePath')"
                )
            )
        }
        // The manual item's Task is not in the claim set.
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM legacy_source_tasks WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 40)"
            )
        )
        database.close()
    }

    @Test
    fun `no Source is invented and no provenance is written`() {
        writeFixture()
        val database = migrate()

        assertEquals(0, database.count("SELECT COUNT(*) FROM sources"))
        assertEquals(0, database.count("SELECT COUNT(*) FROM task_imports"))
        assertEquals(0, database.count("SELECT COUNT(*) FROM legacy_task_ids"))
        database.close()
    }

    // --- the Asset backfill ---------------------------------------------------------------------

    @Test
    fun `an Asset with a list row copies the URI and measurements off it`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            "content://root/a.jpg",
            database.text(
                "SELECT sourceUri FROM assets WHERE relativePath = '2026-07-11/a.jpg'"
            )
        )
        assertEquals(
            4096,
            database.count("SELECT sizeBytes FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            555,
            database.count("SELECT lastModified FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        database.close()
    }

    @Test
    fun `an Asset whose list row has no measurements keeps nulls`() {
        writeFixture()
        val database = migrate()

        assertTrue(
            database.isNull("SELECT sourceUri FROM assets WHERE relativePath = '2026-07-11/b.jpg'")
        )
        assertTrue(
            database.isNull("SELECT sizeBytes FROM assets WHERE relativePath = '2026-07-11/b.jpg'")
        )
        assertTrue(
            database.isNull("SELECT lastModified FROM assets WHERE relativePath = '2026-07-11/b.jpg'")
        )
        database.close()
    }

    @Test
    fun `a Quick Review only file keeps nulls, because nothing else carried them`() {
        writeFixture()
        val database = migrate()

        assertTrue(
            database.isNull("SELECT sourceUri FROM assets WHERE relativePath = '2026-07-11/solo.jpg'")
        )
        assertTrue(
            database.isNull("SELECT sizeBytes FROM assets WHERE relativePath = '2026-07-11/solo.jpg'")
        )
        assertTrue(
            database.isNull("SELECT lastModified FROM assets WHERE relativePath = '2026-07-11/solo.jpg'")
        )
        database.close()
    }

    // --- nothing else moved ---------------------------------------------------------------------

    @Test
    fun `the pre-existing tables are row for row identical`() {
        val before = writeFixture()
        val database = migrate()

        assertEquals(before.tasks, database.count("SELECT COUNT(*) FROM tasks"))
        assertEquals(before.state, database.count("SELECT COUNT(*) FROM task_state"))
        assertEquals(before.events, database.count("SELECT COUNT(*) FROM review_events"))
        assertEquals(before.items, database.count("SELECT COUNT(*) FROM list_items"))
        assertEquals(before.lists, database.count("SELECT COUNT(*) FROM lists"))
        assertEquals(before.itemIds, database.count("SELECT COUNT(*) FROM legacy_item_ids"))
        assertEquals(before.manual, database.count("SELECT COUNT(*) FROM legacy_manual_tasks"))
        assertEquals(
            "FILE-A",
            database.text(
                "SELECT publicId FROM legacy_item_ids WHERE assetId = " +
                    "(SELECT id FROM assets WHERE relativePath = '2026-07-11/a.jpg')"
            )
        )
        database.close()
    }

    @Test
    fun `assets differs only in the three new columns`() {
        writeFixture()
        val database = migrate()

        // The six original columns keep their values, so the migration is additive rather than a
        // rebuild. Identity, ordering and presence are facts about the file and do not move.
        assertEquals(
            "asset-a",
            database.text("SELECT id FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            "task-a",
            database.text("SELECT taskId FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            0,
            database.count("SELECT sortOrder FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            "content://root",
            database.text("SELECT rootUri FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            "2026-07-11/a.jpg",
            database.text("SELECT relativePath FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        assertEquals(
            0,
            database.count("SELECT sourceMissing FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )
        database.close()
    }

    @Test
    fun `per-List progress is unchanged`() = runTest {
        val before = writeFixture()
        val database = migrate()

        val summaries = database.listsDao().observeSummaries().first().associateBy { it.id }
        assertEquals(before.completed, summaries.values.sumOf { it.completedItems })
        assertEquals(before.itemCount, summaries.values.sumOf { it.totalItems })
        assertEquals(1, summaries.getValue(1L).completedItems)
        assertEquals(1, summaries.getValue(2L).completedItems)
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    private class Before(val itemCount: Int, val completed: Int) {
        val tasks: Int get() = 4
        val state: Int get() = 4
        val events: Int get() = 0
        val items: Int get() = 3
        val lists: Int get() = 2
        val itemIds: Int get() = 1
        val manual: Int get() = 1
    }

    /**
     * A v13 database with the four shapes the migration tells apart.
     *
     * List 1 is folder-backed at `content://root` over `2026-07-11`; list 2 is manual. Task A's
     * file has a full `list_items` row; Task B's has one with no measurements; Task C exists only
     * through a `file_review_state` row; Task D is a manual item.
     */
    private fun writeFixture(): Before {
        context.deleteDatabase(V13_DB)
        val file = context.getDatabasePath(V13_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE lists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, " +
                "webhookEnabled INTEGER NOT NULL, webhookUrl TEXT NOT NULL, " +
                "lastDeliveryAt INTEGER, lastDeliveryStatus TEXT, lastDeliveryCode INTEGER, " +
                "lastDeliveryError TEXT, sourceRootUri TEXT, sourceRelativePath TEXT, " +
                "reviewCurrentItemId INTEGER, " +
                "taskReviewTemplateId TEXT NOT NULL DEFAULT 'builtin.media.rapid-review', " +
                "taskReviewTemplateVersion INTEGER NOT NULL DEFAULT 1)"
        )
        database.execSQL(
            "CREATE TABLE list_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "publicId TEXT NOT NULL, listId INTEGER NOT NULL, taskId TEXT NOT NULL, " +
                "title TEXT NOT NULL, isCompleted INTEGER NOT NULL, sortOrder INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, sourceUri TEXT, sourceRelativePath TEXT, " +
                "rootRelativePath TEXT, sourceMissing INTEGER NOT NULL, " +
                "sourceSizeBytes INTEGER, sourceModifiedAt INTEGER, " +
                "isFavorite INTEGER NOT NULL, customActions TEXT NOT NULL, " +
                "FOREIGN KEY(listId) REFERENCES lists(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        database.execSQL("CREATE INDEX index_list_items_listId ON list_items (listId)")
        database.execSQL(
            "CREATE INDEX index_list_items_rootRelativePath ON list_items (rootRelativePath)"
        )
        database.execSQL("CREATE INDEX index_list_items_taskId ON list_items (taskId)")
        database.execSQL(
            "CREATE TABLE webhook_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                "listTitle TEXT NOT NULL, itemCount INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "at INTEGER NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE file_review_state (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, publicId TEXT NOT NULL, " +
                "rootUri TEXT NOT NULL, relativePath TEXT NOT NULL, " +
                "isCompleted INTEGER NOT NULL, isFavorite INTEGER NOT NULL, " +
                "customActions TEXT NOT NULL, sourceMissing INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_file_review_state_rootUri_relativePath " +
                "ON file_review_state (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE tasks (id TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))"
        )
        database.execSQL(
            "CREATE TABLE assets (" +
                "id TEXT NOT NULL, taskId TEXT NOT NULL, sortOrder INTEGER NOT NULL, " +
                "rootUri TEXT, relativePath TEXT, sourceMissing INTEGER NOT NULL, " +
                "PRIMARY KEY(id), FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL("CREATE INDEX index_assets_taskId ON assets (taskId)")
        database.execSQL(
            "CREATE UNIQUE INDEX index_assets_rootUri_relativePath ON assets (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE task_state (" +
                "taskId TEXT NOT NULL, isCompleted INTEGER NOT NULL, " +
                "isFavorite INTEGER NOT NULL, customActions TEXT NOT NULL, " +
                "updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE TABLE review_events (" +
                "id TEXT NOT NULL, taskId TEXT NOT NULL, at INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, templateId TEXT NOT NULL, " +
                "templateVersion INTEGER NOT NULL, response TEXT NOT NULL, " +
                "contextKind TEXT, contextRef TEXT, PRIMARY KEY(id))"
        )
        database.execSQL(
            "CREATE INDEX index_review_events_taskId_at ON review_events (taskId, at)"
        )
        database.execSQL(
            "CREATE TABLE legacy_item_ids (assetId TEXT NOT NULL, publicId TEXT NOT NULL, " +
                "PRIMARY KEY(assetId))"
        )
        database.execSQL(
            "CREATE TABLE legacy_manual_tasks (taskId TEXT NOT NULL, PRIMARY KEY(taskId))"
        )

        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "sourceRootUri, sourceRelativePath) " +
                "VALUES (1, 'Trip', 1000, 0, '', 'content://root', '2026-07-11'), " +
                "(2, 'Shopping', 1001, 0, '', NULL, NULL)"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES " +
                "('task-a', 1000), ('task-b', 1001), ('task-solo', 1002), ('task-manual', 1003)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) " +
                "VALUES ('asset-a', 'task-a', 0, 'content://root', '2026-07-11/a.jpg', 0), " +
                "('asset-b', 'task-b', 0, 'content://root', '2026-07-11/b.jpg', 0), " +
                "('asset-solo', 'task-solo', 0, 'content://root', '2026-07-11/solo.jpg', 0)"
        )
        database.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-a', 1, 0, '', 1000), ('task-b', 0, 0, '', 1000), " +
                "('task-solo', 0, 0, '', 1000), ('task-manual', 1, 0, '', 1000)"
        )
        database.execSQL(
            "INSERT INTO legacy_item_ids (assetId, publicId) VALUES ('asset-a', 'FILE-A')"
        )
        database.execSQL(
            "INSERT INTO legacy_manual_tasks (taskId) VALUES ('task-manual')"
        )
        // 10: full measurements. 11: none. 12: none. 40: manual.
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions) " +
                "VALUES (10, 'ITEM-A', 1, 'task-a', 'a.jpg', 1, 0, 1000, 'content://root/a.jpg', " +
                "'a.jpg', '2026-07-11/a.jpg', 0, 4096, 555, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "isFavorite, customActions) " +
                "VALUES (11, 'ITEM-B', 1, 'task-b', 'b.jpg', 0, 1, 1001, NULL, 'b.jpg', " +
                "'2026-07-11/b.jpg', 0, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceMissing, isFavorite, customActions) " +
                "VALUES (40, 'ITEM-MILK', 2, 'task-manual', 'Buy milk', 1, 0, 1002, 0, 0, '')"
        )
        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, isCompleted, " +
                "isFavorite, customActions, sourceMissing, updatedAt) " +
                "VALUES (100, 'FILE-SOLO', 'content://root', '2026-07-11/solo.jpg', 0, 0, '', 0, 1000)"
        )

        val before = Before(
            itemCount = database.rawInt("SELECT COUNT(*) FROM list_items"),
            completed = database.rawInt(
                "SELECT COALESCE(SUM(ts.isCompleted), 0) FROM list_items i " +
                    "JOIN task_state ts ON ts.taskId = i.taskId"
            )
        )
        database.version = 13
        database.close()
        return before
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V13_DB)
            .addMigrations(ListeaDatabase.MIGRATION_13_14)
            .build()

    private fun ListeaDatabase.count(sql: String): Int =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun ListeaDatabase.text(sql: String): String =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun ListeaDatabase.isNull(sql: String): Boolean =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.isNull(0)
        }

    private fun SQLiteDatabase.rawInt(sql: String): Int =
        rawQuery(sql, null).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private companion object {
        const val V13_DB = "migration-v13-to-v14.db"
    }
}
