package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration that takes the Task out of the Upstream, leaving a Source as a folder and a Todo as
 * what an import run made of one.
 *
 * A raw v15 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives. This one is written to the shape `15.json` records, and it holds the three kinds of row the
 * step has to tell apart:
 *
 * - a Source an import has run over, which is a Todo and stays one;
 * - a Source no import has ever touched, which was only ever a folder and becomes an Upstream;
 * - a Source no import has run over but which a `task_imports` row still names — kept, because
 *   deleting it would leave that row pointing at nothing.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a `CREATE TABLE` here that disagrees with `16.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV15ToV16Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- every Source is still known, under whichever name now fits it ---------------------------

    @Test
    fun `every Source carries over as an Upstream, keeping its folder and its name`() {
        writeFixture()
        val database = migrate()

        assertEquals(3, database.count("SELECT COUNT(*) FROM upstreams"))
        for (id in listOf("imported", "never", "orphan")) {
            assertEquals(id, database.text("SELECT id FROM upstreams WHERE id = '$id'"))
        }
        assertEquals(
            "content://root",
            database.text("SELECT rootUri FROM upstreams WHERE id = 'imported'")
        )
        assertEquals(
            "photos",
            database.text("SELECT relativePath FROM upstreams WHERE id = 'imported'")
        )
        assertEquals(
            "Photos run",
            database.text("SELECT displayName FROM upstreams WHERE id = 'imported'")
        )
        database.close()
    }

    @Test
    fun `an Upstream is dated from when its folder was added, not from when it was copied`() {
        writeFixture()
        val database = migrate()

        // createdAt meant "when this folder entered the app" and addedAt means the same thing, so
        // the copy is a rename. Stamping the migration's own clock here would lose the only record
        // of the order the user added their folders in.
        assertEquals(1000, database.count("SELECT addedAt FROM upstreams WHERE id = 'imported'"))
        assertEquals(1100, database.count("SELECT addedAt FROM upstreams WHERE id = 'never'"))
        database.close()
    }

    // --- but only a run makes a Todo --------------------------------------------------------------

    @Test
    fun `a Source an import has run over stays a Todo`() {
        writeFixture()
        val database = migrate()

        // It is both: the folder it was picked from is an Upstream, and the Tasks the run wrote are
        // a Todo. The two are no longer the same row, which is the whole point of this step.
        assertEquals(1, database.count("SELECT COUNT(*) FROM sources WHERE id = 'imported'"))
        database.close()
    }

    @Test
    fun `a Source no import has run over stops being a Todo`() {
        writeFixture()
        val database = migrate()

        // It was never anything but a folder the user pointed at. Leaving it in `sources` would make
        // the Todo tab list a row with no Tasks behind it and no run to refresh, so exactly the two
        // rows with a claim on being Todos survive: the one that ran, and the one a Task names.
        assertEquals(0, database.count("SELECT COUNT(*) FROM sources WHERE id = 'never'"))
        assertEquals(2, database.count("SELECT COUNT(*) FROM sources"))
        database.close()
    }

    @Test
    fun `a Source no run has touched is kept when a Task still names it`() {
        writeFixture()
        val database = migrate()

        // The guard on the delete. `task_imports.sourceId` is a foreign key, so removing this row
        // would leave a Task claiming a provenance that no longer exists — a state only reachable
        // from a run that failed after it had written its Tasks.
        assertEquals(1, database.count("SELECT COUNT(*) FROM sources WHERE id = 'orphan'"))
        assertEquals(
            1,
            database.count("SELECT COUNT(*) FROM task_imports WHERE sourceId = 'orphan'")
        )
        database.close()
    }

    // --- what an Upstream is, structurally -------------------------------------------------------

    @Test
    fun `one folder is one Upstream`() {
        writeFixture()
        val database = migrate()

        // The unique index is what lets Settings list folders without deduplicating them itself: a
        // root and a path together name a folder, and nothing may be added twice.
        try {
            database.raw(
                "INSERT INTO upstreams VALUES " +
                    "('second', 'content://root', 'photos', 'Photos again', 5000)"
            )
            fail("the same folder was added twice")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
        database.close()
    }

    // --- nothing else moved ----------------------------------------------------------------------

    @Test
    fun `the Tasks this step is not about are untouched`() {
        writeFixture()
        val database = migrate()

        // The step reads `sources` and nothing else. A Task, the Asset it is made of and the
        // provenance that ties it to a run are facts about material, and material did not move.
        assertEquals(2, database.count("SELECT COUNT(*) FROM tasks"))
        assertEquals(2, database.count("SELECT COUNT(*) FROM task_imports"))
        assertEquals(1, database.count("SELECT COUNT(*) FROM assets"))
        assertEquals(1, database.count("SELECT COUNT(*) FROM list_items"))
        assertEquals(
            "task-2",
            database.text("SELECT taskId FROM task_imports WHERE sourceId = 'orphan'")
        )
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /**
     * A v15 database in the shapes the step tells apart.
     *
     * Three Sources: `imported` has run and keeps its place in `sources`; `never` has not and is
     * only a folder; `orphan` has not either, but `task-2` still points at it, so it too stays.
     * `task-1` carries imported material and `task-2` carries none, so the step is shown to leave a
     * Task alone whether or not an Asset hangs off it.
     */
    private fun writeFixture() {
        context.deleteDatabase(V15_DB)
        val file = context.getDatabasePath(V15_DB)
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
                "sourceUri TEXT, sizeBytes INTEGER, lastModified INTEGER, " +
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
            "CREATE TABLE sources (" +
                "id TEXT NOT NULL, rootUri TEXT NOT NULL, relativePath TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, importerKind TEXT NOT NULL, " +
                "importerVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "keepTree INTEGER NOT NULL, lastImportAt INTEGER, lastImportStatus TEXT, " +
                "PRIMARY KEY(id))"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_sources_rootUri_relativePath " +
                "ON sources (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE task_imports (" +
                "taskId TEXT NOT NULL, sourceId TEXT NOT NULL, groupPath TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, importedAt INTEGER NOT NULL, " +
                "lastSeenAt INTEGER NOT NULL, PRIMARY KEY(taskId), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(sourceId) REFERENCES sources(id) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        database.execSQL(
            "CREATE INDEX index_task_imports_sourceId_groupPath " +
                "ON task_imports (sourceId, groupPath)"
        )
        database.execSQL(
            "CREATE TABLE legacy_source_tasks (" +
                "taskId TEXT NOT NULL, PRIMARY KEY(taskId), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE TABLE legacy_task_ids (" +
                "taskId TEXT NOT NULL, publicId TEXT NOT NULL, PRIMARY KEY(taskId), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        database.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, lastImportAt, lastImportStatus) VALUES " +
                "('imported', 'content://root', 'photos', 'Photos run', 'file-as-task', " +
                "1, 1000, 1, 2000, 'ok'), " +
                "('never', 'content://other', '', 'Albums', 'folder-as-task', " +
                "1, 1100, 0, NULL, NULL), " +
                "('orphan', 'content://third', '', 'Orphan', 'file-as-task', " +
                "1, 1200, 1, NULL, NULL)"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-1', 1000), ('task-2', 1001)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) " +
                "VALUES ('asset-1', 'task-1', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/a.jpg', 4096, 555)"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, rootRelativePath, sourceMissing, isFavorite, customActions) " +
                "VALUES (1, 'ITEM-A', 1, 'task-1', 'a.jpg', 0, 0, 1000, " +
                "'photos/a.jpg', 0, 0, '')"
        )
        database.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt) VALUES " +
                "('task-1', 'imported', '', 'Photos run', 2000, 2000), " +
                "('task-2', 'orphan', '', 'Orphan', 1500, 2000)"
        )

        database.version = 15
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V15_DB)
            // Both steps, because a v15 database has two to walk before it is current and the
            // version the app opens at decides how many are needed. V17 sits under every assertion
            // below: it relaxes two indices and moves no row.
            .addMigrations(
                ListeaDatabase.MIGRATION_15_16,
                ListeaDatabase.MIGRATION_16_17,
                ListeaDatabase.MIGRATION_17_18,
                ListeaDatabase.MIGRATION_18_19,
                ListeaDatabase.MIGRATION_19_20,
                ListeaDatabase.MIGRATION_20_21,
                ListeaDatabase.MIGRATION_21_22
            )
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

    private fun ListeaDatabase.raw(sql: String) {
        openHelper.writableDatabase.execSQL(sql)
    }

    private companion object {
        const val V15_DB = "migration-v15-to-v16.db"
    }
}
