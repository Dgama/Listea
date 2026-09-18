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
 * The step that takes the marks off a dissolved history and leaves its files' records in a payload.
 *
 * A raw v21 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: the only honest way to test a migration against real old data is to write the old shape out
 * by hand. This fixture is [MigrationV20ToV21Test]'s one version on — the same database, its
 * `task_asset_state` now standing beside the tables v0.5.1 left — and the rows in
 * `dissolved_task_state` are chosen for what this step does to them: one carrying a `★` and an
 * action id, which are the two values that must not survive as columns and must not be guessed at
 * either.
 *
 * **This is the one step in the chain that removes a column**, and the shape of the claim follows
 * from that. Everything about a row that was ever true of it — which Todo it came from, which folder
 * it is offered at, which group path it will match, whether it was checked, when it was dissolved —
 * crosses the rebuild unchanged, and the two columns that held a *Task-level* answer to a question
 * that is now per file are gone rather than emptied. A row that came through with a version this
 * build cannot read, or with a payload that is anything other than empty, would be this step
 * inventing per-file judgments nobody made; the emptiness is asserted, not assumed.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities — including that **both indices** are back, which is the half of a table rebuild that
 * fails on open rather than on the first query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV21ToV22Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- nothing moved ---------------------------------------------------------------------------

    @Test
    fun `every row survives the rebuild, under its own id`() {
        writeFixture()
        val database = migrate()

        assertEquals(2, database.count("SELECT COUNT(*) FROM dissolved_task_state"))
        // By id, and every fact the row carries. A rebuild that re-inserted its rows — the shape a
        // copy written from the entity rather than from the old table would take — would renumber
        // them, and this is where that shows up.
        assertEquals(
            listOf("gone-1", "gone-2"),
            database.texts("SELECT sourceId FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf("Old Photos", "Old Camera"),
            database.texts("SELECT sourceName FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf("photos", "camera/raw"),
            database.texts("SELECT sourcePath FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf("a.jpg", "raw/b.jpg"),
            database.texts("SELECT groupPath FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf("file-as-task", "folder-as-task"),
            database.texts("SELECT importerKind FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf(3000, 4000),
            database.ints("SELECT dissolvedAt FROM dissolved_task_state ORDER BY id")
        )
        database.close()
    }

    @Test
    fun `the completion of every row is untouched`() {
        writeFixture()
        val database = migrate()

        // Completion is the Task's, before and after this release, and it is the one thing the old
        // snapshot recorded correctly. The pair of rows is a `1` and a `0` so a step that defaulted
        // the column, or copied the wrong one, cannot pass by agreeing with a single fixture value.
        assertEquals(
            listOf(1, 0),
            database.ints("SELECT isCompleted FROM dissolved_task_state ORDER BY id")
        )
        database.close()
    }

    @Test
    fun `the two columns the marks lived in are gone, and the two that replace them are here`() {
        writeFixture()
        val database = migrate()

        // Named in full rather than counted: the claim is not "one fewer column" but "these columns,
        // in this order", which is what Room validates and what a reader of the entity expects.
        assertEquals(
            listOf(
                "id", "sourceId", "sourceName", "rootUri", "sourcePath", "groupPath",
                "importerKind", "isCompleted", "recordVersion", "records", "dissolvedAt"
            ),
            database.columns("dissolved_task_state")
        )
        database.close()
    }

    @Test
    fun `no row comes through with a judgment in it`() {
        writeFixture()
        val database = migrate()

        // The marks the rows held were the Task's, and there is no honest per-file answer to spread
        // them across — so the payload is empty and the version says which emptiness it is. A step
        // that wrote them into the payload would be v0.5.1's bug through the migration door, and one
        // that wrote a version this build does not know would make the history unreadable instead.
        assertEquals(
            listOf(1, 1),
            database.ints("SELECT recordVersion FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(
            listOf("", ""),
            database.texts("SELECT records FROM dissolved_task_state ORDER BY id")
        )
        assertEquals(DISSOLVED_RECORDS_VERSION, 1)
        database.close()
    }

    // --- the table's own rules -------------------------------------------------------------------

    @Test
    fun `both indices are rebuilt`() {
        writeFixture()
        val database = migrate()

        // The half of a table rebuild that fails on open rather than on the first query: Room reads
        // the indices back and compares them, so a step that dropped the table without recreating
        // these would refuse the database the moment the app started.
        assertEquals(
            listOf("sourceId"),
            database.indexColumns("index_dissolved_task_state_sourceId")
        )
        assertEquals(
            listOf("rootUri", "sourcePath"),
            database.indexColumns("index_dissolved_task_state_rootUri_sourcePath")
        )
        database.close()
    }

    @Test
    fun `a row written after the step still takes an id above the ones that came through`() {
        writeFixture()
        val database = migrate()
        val raw = database.openHelper.writableDatabase

        raw.execSQL(
            "INSERT INTO dissolved_task_state (sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, recordVersion, records, dissolvedAt) " +
                "VALUES ('gone-3', 'Later', 'content://root', 'photos', 'c.jpg', 'file-as-task', " +
                "0, 1, '', 5000)"
        )

        // AUTOINCREMENT rather than a renumbering: ids are internal to this table, but a rebuild
        // that reset the sequence would hand a later history an id an earlier one already used, and
        // `dissolvedHistoriesAt` groups by `sourceId` — the picker's answer to "which history" would
        // become ambiguous.
        assertEquals(3, database.int("SELECT MAX(id) FROM dissolved_task_state"))
        database.close()
    }

    // --- what the step did not do ------------------------------------------------------------------

    @Test
    fun `a pair record is left exactly where the previous release put it`() {
        writeFixture()
        val database = migrate()

        // The records that had already left the Task's columns stay untouched: this release changes
        // where a *history* keeps the judgments, and nothing about where a live pair keeps them.
        assertEquals(
            1,
            database.count("SELECT isFavorite FROM task_asset_state WHERE assetId = 'asset-a1'")
        )
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_asset_state WHERE assetId = 'asset-b1'")
        )
        assertEquals(2000, database.int("SELECT updatedAt FROM task_asset_state WHERE assetId = 'asset-a1'"))
        database.close()
    }

    @Test
    fun `the dead columns of task_state are left standing`() {
        writeFixture()
        val database = migrate()

        // v0.5.1 kept them because a legacy manual Task's marks have nowhere else to live, and this
        // step does not revisit that: clearing them here would be a release taking away the only
        // remaining copy of somebody's work in a step that never read it.
        assertEquals(
            listOf("taskId", "isCompleted", "isFavorite", "customActions", "updatedAt"),
            database.columns("task_state")
        )
        assertEquals(1, database.count("SELECT isFavorite FROM task_state WHERE taskId = 'task-a'"))
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_state WHERE taskId = 'task-manual'")
        )
        database.close()
    }

    @Test
    fun `the table is the only thing the step changed`() {
        writeFixture()
        val database = migrate()

        // Every other table, by name and by row count. A step that rebuilt one of them on its way
        // past would be a change to the schema nobody agreed to, and the counts are what would catch
        // a rebuild that lost the rows it was supposed to carry over.
        assertEquals(V21_TABLES.sorted(), database.tables())
        V21_ROWS.forEach { (table, rows) ->
            assertEquals("row count for $table", rows, database.count("SELECT COUNT(*) FROM $table"))
        }
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /**
     * A v21 database: [MigrationV20ToV21Test]'s fixture one version on, with `task_asset_state`
     * standing where that step left it.
     *
     * The two dissolved rows carry the marks this step removes — one with both a `★` and an action
     * id, because the two are removed by the same statement and only a row holding both says so.
     */
    private fun writeFixture() {
        context.deleteDatabase(V21_DB)
        val file = context.getDatabasePath(V21_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
                "`webhookEnabled` INTEGER NOT NULL, `webhookUrl` TEXT NOT NULL, `lastDeliveryAt` " +
                "INTEGER, `lastDeliveryStatus` TEXT, `lastDeliveryCode` INTEGER, " +
                "`lastDeliveryError` TEXT, `sourceRootUri` TEXT, `sourceRelativePath` TEXT, " +
                "`reviewCurrentItemId` INTEGER, `taskReviewTemplateId` TEXT NOT NULL DEFAULT " +
                "'builtin.media.rapid-review', `taskReviewTemplateVersion` INTEGER NOT NULL " +
                "DEFAULT 1, `lastDissolvedCount` INTEGER, `lastDissolvedAt` INTEGER, " +
                "`lastDissolvedReason` TEXT NOT NULL DEFAULT 'dissolved')"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT " +
                "NULL, `publicId` TEXT NOT NULL, `listId` INTEGER NOT NULL, `taskId` TEXT NOT " +
                "NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `sortOrder` INTEGER " +
                "NOT NULL, `createdAt` INTEGER NOT NULL, `sourceUri` TEXT, `sourceRelativePath` " +
                "TEXT, `rootRelativePath` TEXT, `sourceMissing` INTEGER NOT NULL, " +
                "`sourceSizeBytes` INTEGER, `sourceModifiedAt` INTEGER, `isFavorite` INTEGER NOT " +
                "NULL, `customActions` TEXT NOT NULL, FOREIGN KEY(`listId`) REFERENCES " +
                "`lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`taskId`) " +
                "REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
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
                "NOT NULL, `event` TEXT NOT NULL, `listTitle` TEXT NOT NULL, `itemCount` INTEGER " +
                "NOT NULL, `payload` TEXT NOT NULL, `at` INTEGER NOT NULL, `outcome` TEXT NOT " +
                "NULL, `detail` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_review_state` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `isFavorite` " +
                "INTEGER NOT NULL, `customActions` TEXT NOT NULL, `sourceMissing` INTEGER NOT " +
                "NULL, `updatedAt` INTEGER NOT NULL)"
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
                "`sortOrder` INTEGER NOT NULL, `rootUri` TEXT, `relativePath` TEXT, " +
                "`sourceMissing` INTEGER NOT NULL, `sourceUri` TEXT, `sizeBytes` INTEGER, " +
                "`lastModified` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`taskId`) REFERENCES " +
                "`tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
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
        // The table v0.5.1 added, as that step leaves it: marks already off the Task and filed
        // under the file. It is here because this step must not touch it.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_asset_state` (`assetId` TEXT NOT NULL, `isFavorite` " +
                "INTEGER NOT NULL, `customActions` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`assetId`), FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON " +
                "UPDATE NO ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_events` (`id` TEXT NOT NULL, `taskId` TEXT NOT " +
                "NULL, `at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `templateId` TEXT NOT NULL, " +
                "`templateVersion` INTEGER NOT NULL, `response` TEXT NOT NULL, `contextKind` " +
                "TEXT, `contextRef` TEXT, PRIMARY KEY(`id`))"
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
                "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importerKind` TEXT " +
                "NOT NULL, `importerVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`keepTree` INTEGER NOT NULL, `keepStateOnDelete` INTEGER NOT NULL DEFAULT 0, " +
                "`lastImportAt` INTEGER, `lastImportStatus` TEXT, `contentPolicy` TEXT NOT NULL " +
                "DEFAULT 'keep', `followUpstream` INTEGER NOT NULL DEFAULT 0, `assetControls` " +
                "TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` ON `sources` " +
                "(`rootUri`, `relativePath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_imports` (`taskId` TEXT NOT NULL, `sourceId` TEXT " +
                "NOT NULL, `groupPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importedAt` " +
                "INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `assetControls` TEXT NOT NULL " +
                "DEFAULT '', PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES " +
                "`tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sourceId`) " +
                "REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_task_imports_sourceId_groupPath` ON " +
                "`task_imports` (`sourceId`, `groupPath`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_source_tasks` (`taskId` TEXT NOT NULL, PRIMARY " +
                "KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO " +
                "ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_task_ids` (`taskId` TEXT NOT NULL, `publicId` " +
                "TEXT NOT NULL, PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES " +
                "`tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
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
        // The table under test, in its v21 shape: eleven columns, the two marks among them.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `dissolved_task_state` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, " +
                "`rootUri` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `groupPath` TEXT NOT NULL, " +
                "`importerKind` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `isFavorite` " +
                "INTEGER NOT NULL, `customActions` TEXT NOT NULL, `dissolvedAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_dissolved_task_state_sourceId` ON " +
                "`dissolved_task_state` (`sourceId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_dissolved_task_state_rootUri_sourcePath` ON " +
                "`dissolved_task_state` (`rootUri`, `sourcePath`)"
        )

        database.execSQL(
            "INSERT INTO upstreams (id, rootUri, relativePath, displayName, addedAt) " +
                "VALUES ('up-1', 'content://root', '', 'Pictures', 1000)"
        )
        database.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, lastImportAt, lastImportStatus, " +
                "contentPolicy, followUpstream, assetControls) VALUES " +
                "('todo-1', 'content://root', 'photos', 'Photos', 'file-as-task', 1, 1000, 1, " +
                "2000, 'ok', 'reset', 1, '')"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-a', 1000), ('task-b', 1001), " +
                "('task-manual', 1002)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) VALUES " +
                "('asset-a1', 'task-a', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/photos/a.jpg', 4096, 555), " +
                "('asset-b1', 'task-b', 0, 'content://root', 'photos/b.jpg', 0, " +
                "'content://root/photos/b.jpg', 8192, 557)"
        )
        database.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-a', 1, 1, '', 1000), ('task-b', 0, 0, 'urgent', 1001), " +
                "('task-manual', 1, 0, 'urgent', 1002)"
        )
        database.execSQL(
            "INSERT INTO task_asset_state (assetId, isFavorite, customActions, updatedAt) VALUES " +
                "('asset-a1', 1, '', 2000), ('asset-b1', 0, 'urgent', 2001)"
        )
        database.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt, assetControls) VALUES " +
                "('task-a', 'todo-1', 'a.jpg', 'a.jpg', 2000, 2000, ''), " +
                "('task-b', 'todo-1', 'b.jpg', 'b.jpg', 2000, 2000, '')"
        )
        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "lastDissolvedCount, lastDissolvedAt, lastDissolvedReason) VALUES " +
                "(1, 'Trip', 1000, 0, '', 2, 3000, 'drop')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "isFavorite, customActions) VALUES " +
                "(10, 'ITEM-A', 1, 'task-a', 'a.jpg', 1, 0, 1000, " +
                "'content://root/photos/a.jpg', 'a.jpg', 'a.jpg', 0, 1, '')"
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
        // The rows under test: the first carries both a `★` and an action id, the second neither.
        database.execSQL(
            "INSERT INTO dissolved_task_state (id, sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, isFavorite, customActions, dissolvedAt) " +
                "VALUES (1, 'gone-1', 'Old Photos', 'content://root', 'photos', 'a.jpg', " +
                "'file-as-task', 1, 1, 'urgent', 3000), " +
                "(2, 'gone-2', 'Old Camera', 'content://root', 'camera/raw', 'raw/b.jpg', " +
                "'folder-as-task', 0, 0, '', 4000)"
        )

        database.version = 21
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V21_DB)
            // Opened at 23, so this step and the one after it run. The chain up to it is exercised
            // by the parent fixtures; what is under test here is one step's effect on v21 data, and
            // 22 → 23 changes no table so it cannot disturb what is asserted below.
            .addMigrations(ListeaDatabase.MIGRATION_21_22, ListeaDatabase.MIGRATION_22_23)
            .build()

    private fun ListeaDatabase.count(sql: String): Int =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun ListeaDatabase.int(sql: String): Int =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun ListeaDatabase.text(sql: String): String =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    /** One column of several rows, in the order the query asked for. */
    private fun ListeaDatabase.texts(sql: String): List<String> =
        openHelper.readableDatabase.query(sql).use { row ->
            val values = mutableListOf<String>()
            while (row.moveToNext()) values += row.getString(0)
            values
        }

    private fun ListeaDatabase.ints(sql: String): List<Int> =
        openHelper.readableDatabase.query(sql).use { row ->
            val values = mutableListOf<Int>()
            while (row.moveToNext()) values += row.getInt(0)
            values
        }

    /** The columns a table actually has, in the order SQLite holds them. */
    private fun ListeaDatabase.columns(table: String): List<String> =
        openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { row ->
            val names = mutableListOf<String>()
            while (row.moveToNext()) names += row.getString(row.getColumnIndexOrThrow("name"))
            names
        }

    /**
     * The columns an index is over, in order — read back from SQLite rather than from the statement
     * the migration ran, so an index created with the wrong columns or the wrong name is not
     * mistaken for the one the entity declares.
     */
    private fun ListeaDatabase.indexColumns(name: String): List<String> =
        openHelper.readableDatabase.query("PRAGMA index_info(`$name`)").use { row ->
            val names = mutableListOf<String>()
            while (row.moveToNext()) names += row.getString(row.getColumnIndexOrThrow("name"))
            names
        }

    /** Every table in the database by name, minus the ones SQLite and Room keep for themselves. */
    private fun ListeaDatabase.tables(): List<String> =
        openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                "AND name != 'android_metadata' ORDER BY name"
        ).use { row ->
            val names = mutableListOf<String>()
            while (row.moveToNext()) names += row.getString(0)
            names
        }

    private companion object {
        const val V21_DB = "migration-v21-to-v22.db"

        /** Every table the v21 fixture [writeFixture] creates — this step adds and removes none. */
        val V21_TABLES = listOf(
            "assets",
            "dissolved_task_state",
            "file_review_state",
            "legacy_item_ids",
            "legacy_manual_tasks",
            "legacy_source_tasks",
            "legacy_task_ids",
            "list_items",
            "lists",
            "review_events",
            "sources",
            "task_asset_state",
            "task_imports",
            "task_state",
            "tasks",
            "upstreams",
            "webhook_history"
        )

        val V21_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 1,
            "tasks" to 3,
            "assets" to 2,
            "task_state" to 3,
            "task_asset_state" to 2,
            "lists" to 1,
            "list_items" to 1,
            "review_events" to 1,
            "task_imports" to 2,
            "file_review_state" to 1,
            "dissolved_task_state" to 2
        )
    }
}
