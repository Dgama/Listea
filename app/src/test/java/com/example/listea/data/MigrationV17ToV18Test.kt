package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration that gives a Todo something to leave behind when it is deleted.
 *
 * A raw v17 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: the only honest way to test a migration against real old data is to write the old shape
 * out by hand. This one is copied statement for statement from `17.json` — the `CREATE`s and the
 * indices, in the order Room writes them — and populated with the one shape the step has to get
 * right: a Todo that has run, with Tasks of its own, a List holding two of them, and decisions
 * attached.
 *
 * Two halves, and they are different claims. The first is that nothing moved: every row is still
 * there, the Todo still owns the same Tasks, and every decision is still attached to the Task that
 * had it. The second is that the three new columns arrived with the values an untouched install
 * must have — the two note columns null, and the per-Todo default off. A migration that added the
 * table and defaulted the Todo to *keep* would pass the first half and quietly change what happens
 * the first time somebody deletes something.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a statement here that disagrees with `18.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV17ToV18Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- nothing moved ---------------------------------------------------------------------------

    @Test
    fun `every row survives the step, unchanged`() {
        writeFixture()
        val database = migrate()

        for (table in listOf("upstreams", "sources", "tasks", "assets", "task_state", "lists",
                             "list_items", "review_events", "task_imports")) {
            assertEquals(
                "row count for $table",
                FIXTURE_ROWS.getValue(table),
                database.count("SELECT COUNT(*) FROM $table")
            )
        }
        database.close()
    }

    @Test
    fun `a Todo is still the Todo it was, and still owns the same Tasks`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            "photos",
            database.text("SELECT relativePath FROM sources WHERE id = 'todo-1'")
        )
        assertEquals(
            2,
            database.count("SELECT COUNT(*) FROM task_imports WHERE sourceId = 'todo-1'")
        )
        // The provenance row is the thing a later dissolution keys its snapshot by, so a step that
        // lost one would lose the ability to recover it rather than merely to draw it.
        assertEquals(
            "a.jpg",
            database.text("SELECT groupPath FROM task_imports WHERE taskId = 'task-a'")
        )
        database.close()
    }

    @Test
    fun `what was decided about a Task is still decided`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            1,
            database.count("SELECT isCompleted FROM task_state WHERE taskId = 'task-a'")
        )
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_state WHERE taskId = 'task-b'")
        )
        database.close()
    }

    // --- the three new columns -------------------------------------------------------------------

    @Test
    fun `an existing Todo keeps its decisions on delete, which is to say it does not`() {
        writeFixture()
        val database = migrate()

        // Off, and off is the behaviour-preserving answer: before this release nothing could delete
        // a Todo at all, so a Todo that starts keeping a snapshot has begun doing something the
        // user never asked for. `docs/settings.md` is the rule — a control added after a feature
        // shipped starts where an untouched install already was.
        assertEquals(
            0,
            database.count("SELECT keepStateOnDelete FROM sources WHERE id = 'todo-1'")
        )
        database.close()
    }

    @Test
    fun `no List has a dissolution to report`() {
        writeFixture()
        val database = migrate()

        assertTrue(database.isNull("SELECT lastDissolvedCount FROM lists WHERE id = 1"))
        assertTrue(database.isNull("SELECT lastDissolvedAt FROM lists WHERE id = 1"))
        database.close()
    }

    @Test
    fun `the snapshot table arrives empty`() {
        writeFixture()
        val database = migrate()

        // Empty, and it must be: this step cannot invent a history for a Todo that was never
        // deleted, and a row here would be a recovery offer for something that never happened.
        assertEquals(0, database.count("SELECT COUNT(*) FROM dissolved_task_state"))
        // The two indices the recovery join needs. `(rootUri, sourcePath)` is how a folder is asked
        // which histories it can offer; `sourceId` is how one history's rows are read.
        assertEquals(
            "index_dissolved_task_state_sourceId",
            database.text(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = " +
                    "'dissolved_task_state' AND name = 'index_dissolved_task_state_sourceId'"
            )
        )
        assertEquals(
            "index_dissolved_task_state_rootUri_sourcePath",
            database.text(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = " +
                    "'dissolved_task_state' AND name = " +
                    "'index_dissolved_task_state_rootUri_sourcePath'"
            )
        )
        database.close()
    }

    @Test
    fun `a Task in a List still resolves through the membership it had`() {
        writeFixture()
        val database = migrate()

        // The read a dissolution has to walk through in the right order. Asserted here as well as in
        // the dissolution tests because this is the state the migration hands it: two memberships
        // into one Todo, both of which the delete will have to remove before the Tasks can go.
        assertEquals(
            2,
            database.count(
                "SELECT COUNT(*) FROM list_items li JOIN task_imports ti ON ti.taskId = li.taskId " +
                    "WHERE ti.sourceId = 'todo-1'"
            )
        )
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /**
     * A v17 database in the shape the step has to get right.
     *
     * One Todo over `photos`, holding two file-as-Task Tasks, one List holding both, decisions on
     * both, and one `file_review_state` row — the row whose `publicId` is what keeps a file's wire
     * identity alive across a dissolution, which is why the fixture carries one.
     *
     * There is no `dissolved_task_state` in it, because v17 has no such table; the migration creates
     * it. That absence is the point of writing the fixture by hand rather than asking a helper for a
     * current-version database.
     */
    private fun writeFixture() {
        context.deleteDatabase(V17_DB)
        val file = context.getDatabasePath(V17_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
            "`webhookEnabled` INTEGER NOT NULL, `webhookUrl` TEXT NOT NULL, `lastDeliveryAt` " +
            "INTEGER, `lastDeliveryStatus` TEXT, `lastDeliveryCode` INTEGER, `lastDeliveryError` " +
            "TEXT, `sourceRootUri` TEXT, `sourceRelativePath` TEXT, `reviewCurrentItemId` " +
            "INTEGER, `taskReviewTemplateId` TEXT NOT NULL DEFAULT 'builtin.media.rapid-review', " +
            "`taskReviewTemplateVersion` INTEGER NOT NULL DEFAULT 1)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT " +
            "NULL, `publicId` TEXT NOT NULL, `listId` INTEGER NOT NULL, `taskId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `sourceUri` TEXT, `sourceRelativePath` TEXT, " +
            "`rootRelativePath` TEXT, `sourceMissing` INTEGER NOT NULL, `sourceSizeBytes` " +
            "INTEGER, `sourceModifiedAt` INTEGER, `isFavorite` INTEGER NOT NULL, `customActions` " +
            "TEXT NOT NULL, FOREIGN KEY(`listId`) REFERENCES `lists`(`id`) ON UPDATE NO ACTION ON " +
            "DELETE CASCADE , FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION " +
            "ON DELETE NO ACTION )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_list_items_listId` ON `list_items` (`listId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_list_items_rootRelativePath` ON `list_items` " +
            "(`rootRelativePath`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_list_items_taskId` ON `list_items` (`taskId`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `webhook_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
            "NOT NULL, `event` TEXT NOT NULL, `listTitle` TEXT NOT NULL, `itemCount` INTEGER NOT " +
            "NULL, `payload` TEXT NOT NULL, `at` INTEGER NOT NULL, `outcome` TEXT NOT NULL, " +
            "`detail` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_review_state` (`id` INTEGER PRIMARY KEY " +
            "AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `isFavorite` INTEGER " +
            "NOT NULL, `customActions` TEXT NOT NULL, `sourceMissing` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_file_review_state_rootUri_relativePath` ON " +
            "`file_review_state` (`rootUri`, `relativePath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT " +
            "NULL, PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `assets` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, `rootUri` TEXT, `relativePath` TEXT, `sourceMissing` " +
            "INTEGER NOT NULL, `sourceUri` TEXT, `sizeBytes` INTEGER, `lastModified` INTEGER, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO " +
            "ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_assets_taskId` ON `assets` (`taskId`)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_assets_taskId_rootUri_relativePath` ON " +
            "`assets` (`taskId`, `rootUri`, `relativePath`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_assets_rootUri_relativePath` ON `assets` " +
            "(`rootUri`, `relativePath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_state` (`taskId` TEXT NOT NULL, `isCompleted` " +
            "INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `customActions` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) " +
            "REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_events` (`id` TEXT NOT NULL, `taskId` TEXT NOT " +
            "NULL, `at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `templateId` TEXT NOT NULL, " +
            "`templateVersion` INTEGER NOT NULL, `response` TEXT NOT NULL, `contextKind` TEXT, " +
            "`contextRef` TEXT, PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_review_events_taskId_at` ON `review_events` " +
            "(`taskId`, `at`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_item_ids` (`assetId` TEXT NOT NULL, `publicId` " +
            "TEXT NOT NULL, PRIMARY KEY(`assetId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_manual_tasks` (`taskId` TEXT NOT NULL, PRIMARY " +
            "KEY(`taskId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `sources` (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importerKind` TEXT NOT " +
            "NULL, `importerVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `keepTree` " +
            "INTEGER NOT NULL, `lastImportAt` INTEGER, `lastImportStatus` TEXT, PRIMARY " +
            "KEY(`id`))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` ON `sources` " +
            "(`rootUri`, `relativePath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_imports` (`taskId` TEXT NOT NULL, `sourceId` TEXT " +
            "NOT NULL, `groupPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importedAt` " +
            "INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`), FOREIGN " +
            "KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE NO " +
            "ACTION )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_imports_sourceId_groupPath` ON `task_imports` " +
            "(`sourceId`, `groupPath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_source_tasks` (`taskId` TEXT NOT NULL, PRIMARY " +
            "KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON " +
            "DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_task_ids` (`taskId` TEXT NOT NULL, `publicId` " +
            "TEXT NOT NULL, PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `upstreams` (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `addedAt` INTEGER NOT " +
            "NULL, PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_upstreams_rootUri_relativePath` ON " +
            "`upstreams` (`rootUri`, `relativePath`)"
        )
        database.execSQL(
            "INSERT INTO upstreams (id, rootUri, relativePath, displayName, addedAt) " +
                "VALUES ('up-1', 'content://root', '', 'Pictures', 1000)"
        )
        database.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, lastImportAt, lastImportStatus) VALUES " +
                "('todo-1', 'content://root', 'photos', 'Photos', 'file-as-task', 1, 1000, 1, " +
                "2000, 'ok')"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-a', 1000), ('task-b', 1001)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) VALUES " +
                "('asset-a', 'task-a', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/photos/a.jpg', 4096, 555), " +
                "('asset-b', 'task-b', 0, 'content://root', 'photos/b.jpg', 0, " +
                "'content://root/photos/b.jpg', 8192, 556)"
        )
        database.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-a', 1, 1, '', 1000), ('task-b', 0, 0, 'urgent', 1001)"
        )
        database.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt) VALUES " +
                "('task-a', 'todo-1', 'a.jpg', 'a.jpg', 2000, 2000), " +
                "('task-b', 'todo-1', 'b.jpg', 'b.jpg', 2000, 2000)"
        )
        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl) " +
                "VALUES (1, 'Trip', 1000, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "isFavorite, customActions) VALUES " +
                "(10, 'ITEM-A', 1, 'task-a', 'a.jpg', 1, 0, 1000, " +
                "'content://root/photos/a.jpg', 'a.jpg', 'photos/a.jpg', 0, 1, ''), " +
                "(11, 'ITEM-B', 1, 'task-b', 'b.jpg', 0, 1, 1001, " +
                "'content://root/photos/b.jpg', 'b.jpg', 'photos/b.jpg', 0, 0, 'urgent')"
        )
        database.execSQL(
            "INSERT INTO review_events (id, taskId, at, kind, templateId, templateVersion, " +
                "response, contextKind, contextRef) VALUES " +
                "('event-1', 'task-a', 1000, 'decide', 'builtin.media.rapid-review', 1, '{}', " +
                "'list', '1')"
        )
        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, isCompleted, " +
                "isFavorite, customActions, sourceMissing, updatedAt) VALUES " +
                "(100, 'FILE-A', 'content://root', 'photos/a.jpg', 1, 1, '', 0, 1000)"
        )

        database.version = 17
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V17_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_17_18,
                ListeaDatabase.MIGRATION_18_19,
                ListeaDatabase.MIGRATION_19_20,
                ListeaDatabase.MIGRATION_20_21,
                ListeaDatabase.MIGRATION_21_22,
                ListeaDatabase.MIGRATION_22_23
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

    private fun ListeaDatabase.isNull(sql: String): Boolean =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.isNull(0)
        }

    private companion object {
        const val V17_DB = "migration-v17-to-v18.db"

        val FIXTURE_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 1,
            "tasks" to 2,
            "assets" to 2,
            "task_state" to 2,
            "lists" to 1,
            "list_items" to 2,
            "review_events" to 1,
            "task_imports" to 2
        )
    }
}
