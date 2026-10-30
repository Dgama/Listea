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
 * The migration that gives a Todo its two standings, and a List the reason it was left with.
 *
 * A raw v18 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives: the only honest way to test a migration against real old data is to write the old shape
 * out by hand. This one is the parent test's fixture one version on — the same Todo, Tasks, List and
 * decisions — plus the three things v18 added: `keepStateOnDelete` on the Todo, the two note columns
 * on the List, and one row in `dissolved_task_state`.
 *
 * The List's note is the reason this fixture is not simply copied forward. Both note columns are
 * *set* here, which is the state the new column's default has to be right about: a List whose note
 * already said "2 items removed when a Todo was deleted" must go on saying exactly that, and the
 * claim is that the reason arrived holding the delete's own key rather than anything the new `drop`
 * tier might write. A migration that defaulted it to the other key, or to null, would pass every
 * count in this file and change the sentence on a device that already had a note.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a statement here that disagrees with `19.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV18ToV19Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- nothing moved ---------------------------------------------------------------------------

    @Test
    fun `every row survives the step, unchanged`() {
        writeFixture()
        val database = migrate()

        for (table in listOf("upstreams", "sources", "tasks", "assets", "task_state", "lists",
                             "list_items", "review_events", "task_imports",
                             "dissolved_task_state")) {
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

        assertEquals("photos", database.text("SELECT relativePath FROM sources WHERE id = 'todo-1'"))
        assertEquals(2, database.count("SELECT COUNT(*) FROM task_imports WHERE sourceId = 'todo-1'"))
        database.close()
    }

    @Test
    fun `what was decided about a Task is still decided`() {
        writeFixture()
        val database = migrate()

        assertEquals(1, database.count("SELECT isCompleted FROM task_state WHERE taskId = 'task-a'"))
        assertEquals("urgent", database.text("SELECT customActions FROM task_state WHERE taskId = 'task-b'"))
        database.close()
    }

    // --- the three new columns -------------------------------------------------------------------

    @Test
    fun `an existing Todo keeps its content the way it always did`() {
        writeFixture()
        val database = migrate()

        // `keep` and off, and they are the behaviour-preserving answers: before this version a
        // re-import changed no Task's state, and no run ever started by itself. `docs/settings.md`
        // is the rule — a control added after a feature shipped starts where an untouched install
        // already was.
        assertEquals("keep", database.text("SELECT contentPolicy FROM sources WHERE id = 'todo-1'"))
        assertEquals(0, database.count("SELECT followUpstream FROM sources WHERE id = 'todo-1'"))
        database.close()
    }

    @Test
    fun `a List that already had a note keeps the sentence it had`() {
        writeFixture()
        val database = migrate()

        // The count and the time are the fixture's own, asserted first so that a step which somehow
        // rewrote the pair would be caught here rather than at the reason below.
        assertEquals(2, database.count("SELECT lastDissolvedCount FROM lists WHERE id = 1"))
        assertEquals(3000, database.count("SELECT lastDissolvedAt FROM lists WHERE id = 1"))
        // And the reason is the delete's own, because that is the only operation that could have
        // written a note before this version. Left null, or defaulted to the `drop` key, these Lists
        // would begin reporting a Task replacement that never happened.
        assertEquals("dissolved", database.text("SELECT lastDissolvedReason FROM lists WHERE id = 1"))
        database.close()
    }

    @Test
    fun `a List with no note is not given one`() {
        writeFixture()
        val database = migrate()

        // The second List never lost anything, and the reason column arriving with a value is fine:
        // nothing reads it while the count beside it is null. Asserted so that a future read which
        // did look at it would have this case in front of it.
        assertTrue(database.isNull("SELECT lastDissolvedCount FROM lists WHERE id = 2"))
        database.close()
    }

    @Test
    fun `a history a Todo left behind is still there, with its paths`() {
        writeFixture()
        val database = migrate()

        // The recovery join's key, and the column that decides which folder offers it back. This
        // step touches neither, and the test exists because a migration that rebuilt something
        // rather than altering three tables would be the one that lost them.
        assertEquals(
            "a.jpg",
            database.text("SELECT groupPath FROM dissolved_task_state WHERE sourceId = 'gone-1'")
        )
        assertEquals(
            "photos",
            database.text("SELECT sourcePath FROM dissolved_task_state WHERE sourceId = 'gone-1'")
        )
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    private fun writeFixture() {
        context.deleteDatabase(V18_DB)
        val file = context.getDatabasePath(V18_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
            "`webhookEnabled` INTEGER NOT NULL, `webhookUrl` TEXT NOT NULL, `lastDeliveryAt` " +
            "INTEGER, `lastDeliveryStatus` TEXT, `lastDeliveryCode` INTEGER, `lastDeliveryError` " +
            "TEXT, `sourceRootUri` TEXT, `sourceRelativePath` TEXT, `reviewCurrentItemId` " +
            "INTEGER, `taskReviewTemplateId` TEXT NOT NULL DEFAULT 'builtin.media.rapid-review', " +
            "`taskReviewTemplateVersion` INTEGER NOT NULL DEFAULT 1, `lastDissolvedCount` INTEGER, " +
            "`lastDissolvedAt` INTEGER)"
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
            "INTEGER NOT NULL, `keepStateOnDelete` INTEGER NOT NULL DEFAULT 0, " +
            "`lastImportAt` INTEGER, `lastImportStatus` TEXT, PRIMARY " +
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
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "lastDissolvedCount, lastDissolvedAt) VALUES " +
                "(1, 'Trip', 1000, 0, '', 2, 3000), (2, 'Later', 1001, 0, '', NULL, NULL)"
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
            "CREATE TABLE IF NOT EXISTS `dissolved_task_state` (`id` INTEGER PRIMARY KEY " +
            "AUTOINCREMENT NOT NULL, `sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, " +
            "`rootUri` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `groupPath` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `isFavorite` INTEGER " +
            "NOT NULL, `customActions` TEXT NOT NULL, `dissolvedAt` INTEGER NOT NULL)"
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
            "INSERT INTO dissolved_task_state (id, sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, isFavorite, customActions, dissolvedAt) " +
                "VALUES (1, 'gone-1', 'Old Photos', 'content://root', 'photos', 'a.jpg', " +
                "'file-as-task', 1, 0, '', 3000)"
        )

        database.version = 18
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V18_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_18_19,
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

    private fun ListeaDatabase.isNull(sql: String): Boolean =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.isNull(0)
        }

    private companion object {
        const val V18_DB = "migration-v18-to-v19.db"

        val FIXTURE_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 1,
            "tasks" to 2,
            "assets" to 2,
            "task_state" to 2,
            "lists" to 2,
            "list_items" to 2,
            "review_events" to 1,
            "task_imports" to 2,
            "dissolved_task_state" to 1
        )
    }
}
