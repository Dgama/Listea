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
 * The migration that lets one folder back several Todos, and one file be the content of several
 * Tasks.
 *
 * A raw v16 fixture rather than a helper-built database, for the reason [MigrationV12ToV13Test]
 * gives. This one is written to the shape `16.json` records — including the two `UNIQUE` indices
 * being relaxed, which is the whole of what the step does.
 *
 * There are two halves to check, and they are different kinds of claim. The first is that nothing
 * moved: every row of every table is still there, with the same ids and the same decisions attached.
 * The second is that the rules the step relaxes really are relaxed — the constraint that refused a
 * second Todo over one folder is gone, and so is the one that refused a second Task over one file,
 * while the constraint that was worth keeping (one Task holds at most one Asset per file) is still
 * enforced. Asserting only the first half would pass on a migration that dropped the indices and
 * forgot to build the replacement.
 *
 * Opening with [Room] is the schema validation: it runs the migration and checks the result against
 * the entities, so a statement here that disagrees with `17.json` fails this test rather than a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV16ToV17Test {

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
    fun `a Todo is still the Todo it was, and still owns the same Task`() {
        writeFixture()
        val database = migrate()

        // The step changes indices, not rows. If it had been written as a table rebuild — the
        // reflex for a constraint change — a mistake in the copy would show up here as a lost id or
        // a lost provenance row, and every List would silently stop resolving.
        assertEquals(
            "photos",
            database.text("SELECT relativePath FROM sources WHERE id = 'todo-1'")
        )
        assertEquals(
            "task-1",
            database.text("SELECT taskId FROM task_imports WHERE sourceId = 'todo-1'")
        )
        database.close()
    }

    @Test
    fun `what was decided about a Task is still decided`() {
        writeFixture()
        val database = migrate()

        // Checked state and the tagged actions are the things the user would notice losing, and
        // they are the things a constraint change has no business touching.
        assertEquals(1, database.count("SELECT isCompleted FROM task_state WHERE taskId = 'task-1'"))
        assertEquals(
            "custom1",
            database.text("SELECT customActions FROM task_state WHERE taskId = 'task-1'")
        )
        database.close()
    }

    // --- the rule that was relaxed ----------------------------------------------------------------

    @Test
    fun `a second Todo may now stand over one folder`() {
        writeFixture()
        val database = migrate()

        // The release in one statement. Two rows, one folder, two intentions: the same market
        // supplying two dishes. Before this version the insert was a constraint violation, and that
        // refusal was the whole of what stopped a folder having a second Todo.
        database.raw(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, lastImportAt, lastImportStatus) VALUES " +
                "('todo-3', 'content://root', 'photos', 'Another dish', 'file-as-task', " +
                "1, 3000, 1, NULL, NULL)"
        )
        assertEquals(2, database.count("SELECT COUNT(*) FROM sources WHERE relativePath = 'photos'"))
        database.close()
    }

    @Test
    fun `a second Task may now hold the same file`() {
        writeFixture()
        val database = migrate()

        // And the rule underneath it: a file is not owned. The two Tasks are separate rows with
        // separate state, which is what makes their decisions independent, and the Assets are
        // separate rows because an Asset is content *presented for* a Task rather than the file
        // itself.
        database.raw(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-3', 3000)"
        )
        database.raw(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) VALUES " +
                "('asset-3', 'task-3', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/a.jpg', 4096, 555)"
        )
        assertEquals(
            2,
            database.count("SELECT COUNT(*) FROM assets WHERE relativePath = 'photos/a.jpg'")
        )
        database.close()
    }

    // --- and the rule that was not -----------------------------------------------------------------

    @Test
    fun `one Task still holds at most one Asset per file`() {
        writeFixture()
        val database = migrate()

        // The invariant that was always the one worth keeping. Two Assets of one Task at one path
        // would be two answers to the same question — which URI to render, which size to show — and
        // relaxing the *cross*-Task rule is not licence to allow it within a Task.
        try {
            database.raw(
                "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                    "sourceUri, sizeBytes, lastModified) VALUES " +
                    "('asset-dup', 'task-1', 1, 'content://root', 'photos/a.jpg', 0, " +
                    "'content://root/a.jpg', 4096, 555)"
            )
            fail("one Task held the same file twice")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
        database.close()
    }

    @Test
    fun `one folder is still one Upstream`() {
        writeFixture()
        val database = migrate()

        // Deliberately untouched by this step. An Upstream is a place, and the same place added
        // twice is still a duplicate; it is the *import* over a place that a folder may have two of.
        try {
            database.raw(
                "INSERT INTO upstreams VALUES " +
                    "('up-2', 'content://root', '', 'Photos again', 5000)"
            )
            fail("the same Upstream was added twice")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
        database.close()
    }

    // --- the fixture -----------------------------------------------------------------------------

    /**
     * A v16 database in the shapes the step has to carry across.
     *
     * One grant with one Upstream over it, and two Todos: `todo-1` over the subfolder `photos` and
     * `todo-2` over the grant root. Each owns one Task, each Task has one Asset at a distinct path,
     * and one of them has been reviewed and tagged — so the step is shown to preserve a decision as
     * well as a row. A List holds one of the Tasks, because a List is a thing that would break
     * loudly if a Task id moved.
     */
    private fun writeFixture() {
        context.deleteDatabase(V16_DB)
        val file = context.getDatabasePath(V16_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
                "`webhookEnabled` INTEGER NOT NULL, `webhookUrl` TEXT NOT NULL, " +
                "`lastDeliveryAt` INTEGER, `lastDeliveryStatus` TEXT, `lastDeliveryCode` INTEGER, " +
                "`lastDeliveryError` TEXT, `sourceRootUri` TEXT, `sourceRelativePath` TEXT, " +
                "`reviewCurrentItemId` INTEGER, " +
                "`taskReviewTemplateId` TEXT NOT NULL DEFAULT 'builtin.media.rapid-review', " +
                "`taskReviewTemplateVersion` INTEGER NOT NULL DEFAULT 1)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `publicId` TEXT NOT NULL, `listId` INTEGER NOT NULL, " +
                "`taskId` TEXT NOT NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `sourceUri` TEXT, " +
                "`sourceRelativePath` TEXT, `rootRelativePath` TEXT, `sourceMissing` INTEGER NOT " +
                "NULL, `sourceSizeBytes` INTEGER, `sourceModifiedAt` INTEGER, `isFavorite` " +
                "INTEGER NOT NULL, `customActions` TEXT NOT NULL, FOREIGN KEY(`listId`) " +
                "REFERENCES `lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE NO " +
                "ACTION )"
        )
        database.execSQL("CREATE INDEX index_list_items_listId ON list_items (listId)")
        database.execSQL(
            "CREATE INDEX index_list_items_rootRelativePath ON list_items (rootRelativePath)"
        )
        database.execSQL("CREATE INDEX index_list_items_taskId ON list_items (taskId)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `webhook_history` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `event` TEXT NOT NULL, `listTitle` TEXT NOT NULL, " +
                "`itemCount` INTEGER NOT NULL, `payload` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                "`outcome` TEXT NOT NULL, `detail` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_review_state` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `isFavorite` " +
                "INTEGER NOT NULL, `customActions` TEXT NOT NULL, `sourceMissing` INTEGER NOT " +
                "NULL, `updatedAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_file_review_state_rootUri_relativePath " +
                "ON file_review_state (rootUri, relativePath)"
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
        database.execSQL("CREATE INDEX index_assets_taskId ON assets (taskId)")
        database.execSQL(
            "CREATE UNIQUE INDEX index_assets_rootUri_relativePath " +
                "ON assets (rootUri, relativePath)"
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
        database.execSQL("CREATE INDEX index_review_events_taskId_at ON review_events (taskId, at)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_item_ids` (`assetId` TEXT NOT NULL, `publicId` " +
                "TEXT NOT NULL, PRIMARY KEY(`assetId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_manual_tasks` (`taskId` TEXT NOT NULL, " +
                "PRIMARY KEY(`taskId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `sources` (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importerKind` TEXT " +
                "NOT NULL, `importerVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`keepTree` INTEGER NOT NULL, `lastImportAt` INTEGER, `lastImportStatus` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_sources_rootUri_relativePath " +
                "ON sources (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_imports` (`taskId` TEXT NOT NULL, `sourceId` TEXT " +
                "NOT NULL, `groupPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `importedAt` " +
                "INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`), " +
                "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE " +
                "CASCADE , FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION " +
                "ON DELETE NO ACTION )"
        )
        database.execSQL(
            "CREATE INDEX index_task_imports_sourceId_groupPath " +
                "ON task_imports (sourceId, groupPath)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_source_tasks` (`taskId` TEXT NOT NULL, " +
                "PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE " +
                "NO ACTION ON DELETE CASCADE )"
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
            "CREATE UNIQUE INDEX index_upstreams_rootUri_relativePath " +
                "ON upstreams (rootUri, relativePath)"
        )

        database.execSQL(
            "INSERT INTO upstreams (id, rootUri, relativePath, displayName, addedAt) VALUES " +
                "('up-1', 'content://root', '', 'Photos', 900)"
        )
        database.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, lastImportAt, lastImportStatus) VALUES " +
                "('todo-1', 'content://root', 'photos', 'Holiday', 'file-as-task', 1, 1000, 1, " +
                "2000, 'complete'), " +
                "('todo-2', 'content://root', '', 'Everything', 'folder-as-task', 1, 1100, 0, " +
                "2100, 'complete')"
        )
        database.execSQL(
            "INSERT INTO tasks (id, createdAt) VALUES ('task-1', 1000), ('task-2', 1001)"
        )
        database.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified) VALUES " +
                "('asset-1', 'task-1', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/a.jpg', 4096, 555), " +
                "('asset-2', 'task-2', 0, 'content://root', 'photos', 0, " +
                "'content://root/photos', 8192, 556)"
        )
        database.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-1', 1, 0, 'custom1', 3000), " +
                "('task-2', 0, 0, '', 3001)"
        )
        database.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt) VALUES " +
                "('task-1', 'todo-1', 'photos/a.jpg', 'a.jpg', 2000, 2000), " +
                "('task-2', 'todo-2', 'photos', 'photos', 2100, 2100)"
        )
        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl) VALUES " +
                "(1, 'Shopping', 1000, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, " +
                "sortOrder, createdAt, rootRelativePath, sourceMissing, isFavorite, " +
                "customActions) VALUES " +
                "(1, 'ITEM-A', 1, 'task-1', 'a.jpg', 1, 0, 1000, 'photos/a.jpg', 0, 0, 'custom1')"
        )
        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, isCompleted, " +
                "isFavorite, customActions, sourceMissing, updatedAt) VALUES " +
                "(1, 'ITEM-A', 'content://root', 'photos/a.jpg', 1, 0, 'custom1', 0, 3000)"
        )
        database.execSQL(
            "INSERT INTO review_events (id, taskId, at, kind, templateId, templateVersion, " +
                "response, contextKind, contextRef) VALUES " +
                "('ev-1', 'task-1', 3000, 'submitted', 'builtin.media.rapid-review', 1, " +
                "'custom1', 'list', '1')"
        )

        database.version = 16
        database.close()
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V16_DB)
            .addMigrations(
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
        const val V16_DB = "migration-v16-to-v17.db"

        /** What the fixture writes, so the "nothing moved" test states a number rather than a zero. */
        val FIXTURE_ROWS = mapOf(
            "upstreams" to 1,
            "sources" to 2,
            "tasks" to 2,
            "assets" to 2,
            "task_state" to 2,
            "lists" to 1,
            "list_items" to 1,
            "review_events" to 1,
            "task_imports" to 2
        )
    }
}
