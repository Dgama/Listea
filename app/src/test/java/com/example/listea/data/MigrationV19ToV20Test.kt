package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration that lets a Todo, and each Task under it, record which asset control governs its
 * files.
 *
 * A raw v19 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: the only honest way to test a migration against real old data is to write the old shape out
 * by hand. This one is the parent test's fixture one version on — the same two Todos, Tasks, Lists
 * and decisions, plus the three things v19 added — and the two Todos are deliberately given
 * *non-default* standings so that this step can be seen to leave them alone.
 *
 * The claim this file exists for is a negative one. `''` is not an empty tag set, it is the absence
 * of an answer, and a migration that backfilled a synthesised empty configuration would read as
 * "this Todo chose no tags" forever — pinning every Todo on the device to a blank chip row that no
 * later Settings edit could reach. So the assertions are that the columns arrive **empty** and
 * **not** holding a configuration that decodes to nothing. The reading side of the same rule is
 * `AssetControlTest`'s.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a statement here that disagrees with `20.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV19ToV20Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- nothing moved ---------------------------------------------------------------------------

    @Test
    fun `every row survives the step, unchanged`() {
        writeFixture()
        val database = migrate()

        FIXTURE_ROWS.forEach { (table, rows) ->
            assertEquals("row count for $table", rows, database.count("SELECT COUNT(*) FROM $table"))
        }
        database.close()
    }

    @Test
    fun `no table but the two that needed a column was touched`() {
        writeFixture()
        val database = migrate()

        // The plan's "no other table changed", asserted as a delta rather than as a snapshot: the
        // fixture above is a v19 database, so the column count each table has *before* is known
        // here, and the only permitted difference is one column on each of two tables. An ALTER
        // that landed on the wrong table, a table rebuilt along the way, or a column quietly added
        // to a table this step does not own shows up as a count that is not what the delta predicts.
        //
        // Two deltas, and they are different claims. [TABLES_THAT_GAINED_A_COLUMN] is this step's
        // own work; [ColumnsFromLaterSteps] is everything the walk does after it, which the fixture
        // cannot avoid running because Room opens at the version the app ships. Keeping them apart
        // is what lets the second grow without the first silently meaning something else.
        V19_COLUMNS.forEach { (table, before) ->
            val step = if (table in TABLES_THAT_GAINED_A_COLUMN) 1 else 0
            val later = ColumnsFromLaterSteps[table]?.size ?: 0
            assertEquals("columns of $table", before + step + later, database.columns(table).size)
        }
        // And the two that did change, by name and position — the count alone would not say *which*
        // column arrived, and `ALTER TABLE ADD COLUMN` putting it at the end of what was already
        // there is what says the table was not rebuilt around it.
        //
        // For `sources` that position is read as *immediately after the columns v19 found*, rather
        // than as `.last()`: a release after this one appends its own column and the last one stops
        // being this step's. The index is what this step claimed, and it is what stays true.
        assertEquals("assetControls", database.columns("sources")[V19_COLUMNS.getValue("sources")])
        // `task_imports` is read the same way for the same reason: v0.7.1 appended `addOn` after it.
        assertEquals(
            "assetControls",
            database.columns("task_imports")[V19_COLUMNS.getValue("task_imports")]
        )
        database.close()
    }

    @Test
    fun `a Todo keeps both standings it was given`() {
        writeFixture()
        val database = migrate()

        // Non-default in the fixture on purpose, so a step that re-ran v19's ALTERs, or that rebuilt
        // `sources` from the entity, would be caught rather than hidden behind a matching default.
        assertEquals(
            "reset",
            database.text("SELECT contentPolicy FROM sources WHERE id = 'todo-1'")
        )
        assertEquals(1, database.int("SELECT followUpstream FROM sources WHERE id = 'todo-1'"))
        assertEquals(
            "keep",
            database.text("SELECT contentPolicy FROM sources WHERE id = 'todo-2'")
        )
        database.close()
    }

    @Test
    fun `a List keeps the reason it was left with`() {
        writeFixture()
        val database = migrate()

        // v19's own column, and its `drop` key rather than the delete's default: a step that
        // rebuilt `lists` would silently re-answer the question that column was added to answer.
        assertEquals(
            "drop",
            database.text("SELECT lastDissolvedReason FROM lists WHERE id = 1")
        )
        assertEquals(2, database.int("SELECT lastDissolvedCount FROM lists WHERE id = 1"))
        assertEquals(
            1,
            database.int("SELECT COUNT(*) FROM lists WHERE id = 2 AND lastDissolvedCount IS NULL")
        )
        database.close()
    }

    @Test
    fun `what was decided about a Task is still decided`() {
        writeFixture()
        val database = migrate()

        assertEquals(1, database.count("SELECT isCompleted FROM task_state WHERE taskId = 'task-a'"))
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_state WHERE taskId = 'task-b'")
        )
        assertEquals(
            "a.jpg",
            database.text("SELECT groupPath FROM dissolved_task_state WHERE sourceId = 'gone-1'")
        )
        database.close()
    }

    // --- the two new columns ---------------------------------------------------------------------

    @Test
    fun `every Todo and every Task reads as never having chosen`() {
        writeFixture()
        val database = migrate()

        // The whole upgrade: absent, not empty. `encodeAssetControls(emptyMap())` writes exactly this
        // string, so what is on the device after the step is what a Todo that has never been
        // configured looks like from then on.
        assertEquals(2, database.count("SELECT COUNT(*) FROM sources WHERE assetControls = ''"))
        assertEquals(2, database.count("SELECT COUNT(*) FROM task_imports WHERE assetControls = ''"))
        database.close()
    }

    @Test
    fun `the new columns hold no configuration that would decode to nothing`() {
        writeFixture()
        val database = migrate()

        // The failure the whole file is about, caught at the level it could actually happen: a
        // backfill of a *synthesised* empty configuration. `''` and `image\tbuiltin…\t1\t` both
        // read as "no tags", and only one of them means "never chosen" — the other pins the row to
        // an answer no Settings edit can reach.
        for (table in listOf("sources", "task_imports")) {
            assertEquals(
                "a configuration was written to $table",
                0,
                database.count(
                    "SELECT COUNT(*) FROM $table WHERE assetControls LIKE '%builtin%'"
                )
            )
        }
        database.close()
    }

    @Test
    fun `a row inserted without the column still answers as never having chosen`() {
        writeFixture()
        val database = migrate()

        // What NOT NULL DEFAULT '' buys, and the reason the ALTERs are written that way: every
        // insert path that predates this version omits the column, and it has to keep working and
        // keep meaning the same thing.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, keepStateOnDelete) VALUES " +
                "('todo-3', 'content://root', 'later', 'Later', 'file-as-task', 1, 4000, 1, 0)"
        )

        assertEquals(
            1,
            database.count("SELECT COUNT(*) FROM sources WHERE id = 'todo-3' AND assetControls = ''")
        )
        assertEquals(
            "keep",
            database.text("SELECT contentPolicy FROM sources WHERE id = 'todo-3'")
        )
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /** A v19 database: this file's parent fixture, one version on. */
    private fun writeFixture() {
        context.deleteDatabase(V19_DB)
        val file = context.getDatabasePath(V19_DB)
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
                "DEFAULT 'keep', `followUpstream` INTEGER NOT NULL DEFAULT 0, PRIMARY " +
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
                "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON " +
                "DELETE NO ACTION )"
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
                "contentPolicy, followUpstream) VALUES " +
                "('todo-1', 'content://root', 'photos', 'Photos', 'file-as-task', 1, 1000, 1, " +
                "2000, 'ok', 'reset', 1), " +
                "('todo-2', 'content://root', '', 'Everything', 'folder-as-task', 1, 1001, 0, " +
                "NULL, NULL, 'keep', 0)"
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
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "lastDissolvedCount, lastDissolvedAt, lastDissolvedReason) VALUES " +
                "(1, 'Trip', 1000, 0, '', 2, 3000, 'drop'), " +
                "(2, 'Later', 1001, 0, '', NULL, NULL, 'dissolved')"
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
        database.execSQL(
            "INSERT INTO dissolved_task_state (id, sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, isFavorite, customActions, dissolvedAt) " +
                "VALUES (1, 'gone-1', 'Old Photos', 'content://root', 'photos', 'a.jpg', " +
                "'file-as-task', 1, 0, '', 3000)"
        )

        database.version = 19
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V19_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_19_20,
                ListeaDatabase.MIGRATION_20_21,
                ListeaDatabase.MIGRATION_21_22,
                ListeaDatabase.MIGRATION_22_23,
                ListeaDatabase.MIGRATION_23_24,
                ListeaDatabase.MIGRATION_24_25,
                ListeaDatabase.MIGRATION_25_26,
                ListeaDatabase.MIGRATION_26_27,
                ListeaDatabase.MIGRATION_27_28,
            ListeaDatabase.MIGRATION_28_29,
            ListeaDatabase.MIGRATION_29_30,
            ListeaDatabase.MIGRATION_30_31,
            ListeaDatabase.MIGRATION_31_32,
            ListeaDatabase.MIGRATION_32_33,
            ListeaDatabase.MIGRATION_33_34
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

    private fun ListeaDatabase.int(sql: String): Int =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    /** The columns a table actually has, in the order SQLite holds them — `ALTER` appends. */
    private fun ListeaDatabase.columns(table: String): List<String> =
        openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { row ->
            val names = mutableListOf<String>()
            while (row.moveToNext()) names += row.getString(row.getColumnIndexOrThrow("name"))
            names
        }

    private companion object {
        const val V19_DB = "migration-v19-to-v20.db"

        val FIXTURE_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 2,
            "tasks" to 2,
            "assets" to 2,
            "task_state" to 2,
            "lists" to 2,
            "list_items" to 2,
            "review_events" to 1,
            "task_imports" to 2,
            "file_review_state" to 1,
            "dissolved_task_state" to 1
        )

        /** The column count every table has in the v19 fixture [writeFixture] writes. */
        val V19_COLUMNS = mapOf(
            "upstreams" to 5,
            "sources" to 13,
            "tasks" to 2,
            "assets" to 9,
            "task_state" to 5,
            "lists" to 18,
            "list_items" to 16,
            "webhook_history" to 8,
            "review_events" to 9,
            "task_imports" to 6,
            "file_review_state" to 9,
            "legacy_item_ids" to 2,
            "legacy_manual_tasks" to 1,
            "legacy_source_tasks" to 1,
            "legacy_task_ids" to 2,
            "dissolved_task_state" to 11
        )

        /** The only two tables v20 alters, and the whole of what the step above is allowed to do. */
        val TABLES_THAT_GAINED_A_COLUMN = setOf("sources", "task_imports")
    }
}
