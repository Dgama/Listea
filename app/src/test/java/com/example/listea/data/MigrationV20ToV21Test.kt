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
 * The migration that moves the template's instance off the Task and onto the Task–Asset pair.
 *
 * A raw v20 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: the only honest way to test a migration against real old data is to write the old shape out
 * by hand. This fixture is [MigrationV19ToV20Test]'s one version on — the same Todos, Lists and
 * decisions, plus v20's two `assetControls` columns — and one thing is added that no earlier fixture
 * needed: **a Task with two Assets**. It is the case the whole step is about, and a fixture with one
 * file per Task could not tell a per-pair copy from a per-Task one.
 *
 * The claim the file exists for is that **no record is lost**. Every Asset keeps exactly what its
 * Task was showing before the step, so a file that read as marked yesterday still reads as marked
 * today; and the columns the marks were copied *from* are left standing rather than dropped, because
 * a migration does not trade away the only remaining copy of somebody's work in the release that
 * stops reading it. [MigrationV21ColumnsAreDead] is where the second half of that claim lives.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a statement here that disagrees with `21.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV20ToV21Test {

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
    fun `no table gained or lost a column`() {
        writeFixture()
        val database = migrate()

        // This step adds a *table* and alters nothing. A rebuild of `task_state` that quietly
        // dropped the two columns it stopped reading would show up here as a count that went down,
        // which is exactly the shape of change this release is not allowed to make.
        //
        // The counts are v20's, and the walk does not stop there: Room opens at the version the app
        // ships, so every step after this one runs too and [ColumnsFromLaterSteps] is what they add.
        // The permitted difference is a named column on a named table rather than a bare `+ 1`, so
        // an `ALTER` landing somewhere it has no business still shows up as a count that is wrong.
        V20_COLUMNS.forEach { (table, columns) ->
            val later = ColumnsFromLaterSteps[table]?.size ?: 0
            assertEquals("columns of $table", columns + later, database.columns(table).size)
        }
        // `selections` is on the end because a later step appends it, and appended is where SQLite
        // puts a column an `ALTER` adds whatever order the entity declares it in. The four this
        // step wrote are still here, in this order, which is what the assertion is about.
        assertEquals(
            listOf("assetId", "isFavorite", "customActions", "updatedAt", "selections"),
            database.columns("task_asset_state")
        )
        database.close()
    }

    @Test
    fun `the columns the marks were copied from are left exactly where they were`() {
        writeFixture()
        val database = migrate()

        // The old copy is still there, character for character. It is dead — nothing reads it — but
        // it is the only thing a rollback could re-project from, and clearing it would be this
        // release taking something away that it is not giving back.
        assertEquals(1, database.count("SELECT isFavorite FROM task_state WHERE taskId = 'task-a'"))
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_state WHERE taskId = 'task-b'")
        )
        database.close()
    }

    @Test
    fun `v20's own columns are untouched`() {
        writeFixture()
        val database = migrate()

        // The `sources` half is untouched, on both of the rows this fixture gives it: neither ever
        // named a control, and no step between here and the current version has anything to say
        // about an empty one. Non-default elsewhere in the row on purpose, for the reason the
        // column counts above are read as a delta — a step that rebuilt the table from the entity
        // would put these back to a default rather than to what the fixture wrote.
        assertEquals("", database.text("SELECT assetControls FROM sources WHERE id = 'todo-1'"))
        assertEquals("", database.text("SELECT assetControls FROM sources WHERE id = 'todo-2'"))

        // The `task_imports` half is *not* untouched, and the literal the fixture wrote is exactly
        // what must not be here: a later step translates this column, from the old per-kind text
        // into a template reference, which is v0.5.4's subject and is pinned in
        // [MigrationV23ToV24Test]. So what this test keeps is the part no later step has any
        // business with — the column arrived on the row that named a control and on the row that
        // did not, and the first still carries a value of its own rather than one invented for it.
        // Read as "carried through", not as "equal to what went in": only the first is still true.
        assertEquals(2, database.count("SELECT COUNT(*) FROM task_imports"))
        assertTrue(
            "task-a named a control and no step here may blank it",
            database.text("SELECT assetControls FROM task_imports WHERE taskId = 'task-a'")
                .isNotEmpty()
        )
        assertEquals(
            "",
            database.text("SELECT assetControls FROM task_imports WHERE taskId = 'task-b'")
        )
        database.close()
    }

    // --- the copy ---------------------------------------------------------------------------------

    @Test
    fun `every Asset of a Task gets its own copy of the record the Task was showing`() {
        writeFixture()
        val database = migrate()

        // Two Assets under one Task state: the point of the fixture. Both are marked, because both
        // were showing that Task's record before the step — this is the release that lets them stop
        // agreeing later, not one that makes them disagree on the way in.
        assertEquals(
            1,
            database.count(
                "SELECT isFavorite FROM task_asset_state WHERE assetId = 'asset-a1'"
            )
        )
        assertEquals(
            1,
            database.count(
                "SELECT isFavorite FROM task_asset_state WHERE assetId = 'asset-a2'"
            )
        )
        assertEquals(
            1,
            database.count("SELECT isCompleted FROM task_state WHERE taskId = 'task-a'")
        )
        database.close()
    }

    @Test
    fun `an action id survives the copy unchanged`() {
        writeFixture()
        val database = migrate()

        // The encoding is the same one the old column held, so what decodes to `urgent` before the
        // step decodes to `urgent` after it. A re-encode that reordered or re-escaped the set would
        // be legible here as a string that is not the one that went in.
        assertEquals(
            "urgent",
            database.text("SELECT customActions FROM task_asset_state WHERE assetId = 'asset-b1'")
        )
        assertEquals(
            "",
            database.text("SELECT customActions FROM task_asset_state WHERE assetId = 'asset-a1'")
        )
        database.close()
    }

    @Test
    fun `the copy carries the Task state's timestamp`() {
        writeFixture()
        val database = migrate()

        // The pair's `updatedAt` starts as the moment the Task it was copied from was last written,
        // rather than as the moment the migration happened. Stamping "now" would let a migration
        // look like a decision the user made, and would lose the only ordering the old row had.
        assertEquals(1000, database.int("SELECT updatedAt FROM task_asset_state WHERE assetId = 'asset-a1'"))
        assertEquals(1001, database.int("SELECT updatedAt FROM task_asset_state WHERE assetId = 'asset-b1'"))
        database.close()
    }

    @Test
    fun `a Task with no Asset gains no pair`() {
        writeFixture()
        val database = migrate()

        // A legacy manual item: a Task with a state row and no file. It has no template instance to
        // record, so it has no record, and the new table says so by having nothing in it about this
        // Task rather than by carrying an empty row that would read as "nothing marked".
        assertEquals(
            1,
            database.count("SELECT COUNT(*) FROM task_state WHERE taskId = 'task-manual'")
        )
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM task_asset_state tas JOIN assets a ON a.id = tas.assetId " +
                    "WHERE a.taskId = 'task-manual'"
            )
        )
        database.close()
    }

    @Test
    fun `a pair is exactly one row per Asset, and every Asset has one`() {
        writeFixture()
        val database = migrate()

        // The join is the claim: no Asset is left without a record, and no Asset has two. The
        // second half is what the primary key buys — a second row for one file would be a record
        // with no way to say which of them is the file's.
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM assets a LEFT JOIN task_asset_state tas ON tas.assetId = a.id " +
                    "WHERE tas.assetId IS NULL"
            )
        )
        assertEquals(
            database.count("SELECT COUNT(*) FROM assets"),
            database.count("SELECT COUNT(*) FROM task_asset_state")
        )
        database.close()
    }

    // --- the table's own rules --------------------------------------------------------------------

    @Test
    fun `a pair goes when the Asset it is filed under goes`() {
        writeFixture()
        val database = migrate()

        // The Task that goes is the one with *two* files, so the cascade has to take two pair rows
        // and leave the third. The membership row goes first and is not part of the claim: this
        // fixture's Lists still name their Tasks, and `list_items.taskId` is declared NO ACTION, so
        // deleting a Task a List still holds is a foreign-key error rather than a cascade.
        database.openHelper.writableDatabase.execSQL("DELETE FROM list_items WHERE taskId = 'task-a'")
        database.openHelper.writableDatabase.execSQL("DELETE FROM tasks WHERE id = 'task-a'")

        // The record is the Task's, filed under the file — so files that go take their records and
        // leave every other pair alone. Without the cascade these rows would outlive the only thing
        // that could name them.
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM task_asset_state " +
                    "WHERE assetId IN ('asset-a1', 'asset-a2')"
            )
        )
        assertEquals(1, database.count("SELECT COUNT(*) FROM task_asset_state"))
        assertEquals(
            1,
            database.count("SELECT COUNT(*) FROM task_asset_state WHERE assetId = 'asset-b1'")
        )
        database.close()
    }

    @Test
    fun `the table is the only thing the step added`() {
        writeFixture()
        val database = migrate()

        // Named in full rather than counted: a migration that created a second table, or that
        // replaced one of these with a rebuilt copy under a different name, is a change to the
        // schema nobody agreed to, and the list is short enough to just say.
        //
        // The walk runs past this step, so a later release's tables are there as well and are
        // subtracted rather than named — see [TablesFromLaterSteps]. The claim is unchanged: nothing
        // but `task_asset_state` appeared *because of this step*, and nothing at all was rebuilt.
        assertEquals(
            (V20_TABLES + "task_asset_state").sorted(),
            database.tables() - TablesFromLaterSteps
        )
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /** A v20 database: this file's parent fixture, one version on, with a two-Asset Task. */
    private fun writeFixture() {
        context.deleteDatabase(V20_DB)
        val file = context.getDatabasePath(V20_DB)
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
                "2000, 'ok', 'reset', 1, ''), " +
                "('todo-2', 'content://root', '', 'Everything', 'folder-as-task', 1, 1001, 0, " +
                "NULL, NULL, 'keep', 0, '')"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-a', 1000), ('task-b', 1001), " +
                "('task-manual', 1002)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) VALUES " +
                "('asset-a1', 'task-a', 0, 'content://root', 'photos/a1.jpg', 0, " +
                "'content://root/photos/a1.jpg', 4096, 555), " +
                "('asset-a2', 'task-a', 1, 'content://root', 'photos/a2.jpg', 0, " +
                "'content://root/photos/a2.jpg', 4097, 556), " +
                "('asset-b1', 'task-b', 0, 'content://root', 'photos/b.jpg', 0, " +
                "'content://root/photos/b.jpg', 8192, 557)"
        )
        database.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-a', 1, 1, '', 1000), ('task-b', 0, 0, 'urgent', 1001), " +
                "('task-manual', 1, 0, '', 1002)"
        )
        database.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt, assetControls) VALUES " +
                "('task-a', 'todo-1', 'Photos', 'Photos', 2000, 2000, " +
                "'image\tbuiltin.media.asset-control\t1\t★'), " +
                "('task-b', 'todo-1', 'b.jpg', 'b.jpg', 2000, 2000, '')"
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
                "(10, 'ITEM-A', 1, 'task-a', 'Photos', 1, 0, 1000, " +
                "'content://root/photos/a1.jpg', 'a1.jpg', 'photos/a1.jpg', 0, 1, ''), " +
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
                "(100, 'FILE-A', 'content://root', 'photos/a1.jpg', 1, 1, '', 0, 1000)"
        )
        database.execSQL(
            "INSERT INTO dissolved_task_state (id, sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, isFavorite, customActions, dissolvedAt) " +
                "VALUES (1, 'gone-1', 'Old Photos', 'content://root', 'photos', 'a.jpg', " +
                "'file-as-task', 1, 0, '', 3000)"
        )

        database.version = 20
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V20_DB)
            // This step, and the ones after it that the entity set now requires — Room opens at the
            // current version whatever the fixture was. What is under test here is one step's effect
            // on v20 data; the steps above it are [MigrationV21ToV22Test]'s subject, and their
            // effect on *this* fixture is asserted by the row counts and the column lists below.
            .addMigrations(
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
            ListeaDatabase.MIGRATION_30_31
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

    /** The columns a table actually has, in the order SQLite holds them. */
    private fun ListeaDatabase.columns(table: String): List<String> =
        openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { row ->
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
        const val V20_DB = "migration-v20-to-v21.db"

        /** Every table the v20 fixture [writeFixture] creates. */
        val V20_TABLES = listOf(
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
            "task_imports",
            "task_state",
            "tasks",
            "upstreams",
            "webhook_history"
        )

        val FIXTURE_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 2,
            "tasks" to 3,
            "assets" to 3,
            "task_state" to 3,
            "lists" to 2,
            "list_items" to 2,
            "review_events" to 1,
            "task_imports" to 2,
            "file_review_state" to 1,
            "dissolved_task_state" to 1
        )

        /** The column count every table has in the v20 fixture [writeFixture] writes. */
        val V20_COLUMNS = mapOf(
            "upstreams" to 5,
            "sources" to 14,
            "tasks" to 2,
            "assets" to 9,
            "task_state" to 5,
            "lists" to 18,
            "list_items" to 16,
            "webhook_history" to 8,
            "review_events" to 9,
            "task_imports" to 7,
            "file_review_state" to 9,
            "legacy_item_ids" to 2,
            "legacy_manual_tasks" to 1,
            "legacy_source_tasks" to 1,
            "legacy_task_ids" to 2,
            "dissolved_task_state" to 11
        )
    }
}
