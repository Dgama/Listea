package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migration that moves every existing List onto the Task model.
 *
 * A raw v12 fixture rather than a helper-built database, for the reason the plan gives: schemas
 * 1..11 were never exported, so the only honest way to test a migration against real old data is
 * to write the old shape out by hand. This one is written to the shape `12.json` records, and it
 * deliberately contains every case the migration has to tell apart:
 *
 * - a source-backed item whose file already carries a decision, which must resolve to the Task the
 *   file was made into rather than to a fresh one;
 * - a source-backed item whose file has no state row, which is the normal lazy case and must still
 *   end up with a Task, because `list_items.taskId` is `NOT NULL`;
 * - a source-backed item whose source is gone, whose flag has to survive;
 * - two manual items sharing a title, which must stay two Tasks;
 * - a manual item carrying a root-relative path whose List has no folder — the shape the migration
 *   is told to treat as manual rather than drop;
 * - a `file_review_state` row for a file no List covers, which is what a folder Quick Review
 *   leaves behind and which must become a Task of its own.
 *
 * Opening with [Room] is the schema validation: it runs the migration and then checks the result
 * against the entities, so a `CREATE TABLE` here that disagrees with `13.json` fails this test
 * rather than a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV12ToV13Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- what the migration is for ---------------------------------------------------------------

    @Test
    fun `every membership ends up naming a Task that exists`() {
        val before = writeFixture()
        val database = migrate()

        // Every one of them, and no membership lost along the way — the rebuild is a copy, not a
        // filter, and a membership dropped here would be a file somebody can no longer review.
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM list_items WHERE taskId NOT IN (SELECT id FROM tasks)"
            )
        )
        assertEquals(before.itemCount, database.count("SELECT COUNT(*) FROM list_items"))
        database.close()
    }

    @Test
    fun `a file that already had a decision becomes the Task its List was pointing at`() {
        writeFixture()
        val database = migrate()

        // The point of the test: this item's file had a `file_review_state` row, so the migration
        // must have found it rather than minting an all-false Task beside it. If the lookup were
        // missing, these values would be 0 and the whole file's history would read as unreviewed.
        assertEquals(
            1,
            database.count(
                "SELECT isCompleted FROM task_state WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 10)"
            )
        )
        assertEquals(
            "custom1",
            database.text(
                "SELECT customActions FROM task_state WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 10)"
            )
        )
        assertEquals(
            1,
            database.count(
                "SELECT isFavorite FROM task_state WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 10)"
            )
        )
        database.close()
    }

    @Test
    fun `a file nobody has decided about still gets a Task, with everything false`() {
        writeFixture()
        val database = migrate()

        // The lazy case: no `file_review_state` row exists yet, and `list_items.taskId` is NOT
        // NULL, so a Task has to be invented. It must claim nothing — an invented "reviewed" here
        // would be the app inventing a judgment, which is the one thing the model forbids.
        val taskId = "(SELECT taskId FROM list_items WHERE id = 11)"
        assertEquals(1, database.count("SELECT COUNT(*) FROM task_state WHERE taskId = $taskId"))
        assertEquals(0, database.count("SELECT isCompleted FROM task_state WHERE taskId = $taskId"))
        assertEquals(0, database.count("SELECT isFavorite FROM task_state WHERE taskId = $taskId"))
        assertEquals("", database.text("SELECT customActions FROM task_state WHERE taskId = $taskId"))

        // And it is content for a file, so it has an Asset naming that file — otherwise the Task
        // would exist with nothing saying which file it is about.
        assertEquals(
            "content://root/2026-07-11/b.jpg",
            database.text(
                "SELECT rootUri || '/' || relativePath FROM assets WHERE taskId = $taskId"
            )
        )
        database.close()
    }

    @Test
    fun `a source that went missing is still missing afterwards`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            1,
            database.count(
                "SELECT sourceMissing FROM assets WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 12)"
            )
        )
        database.close()
    }

    @Test
    fun `two manual items with one title stay two things`() {
        writeFixture()
        val database = migrate()

        val milk = database.text("SELECT taskId FROM list_items WHERE id = 20")
        val milkAgain = database.text("SELECT taskId FROM list_items WHERE id = 21")
        assertNotEquals(milk, milkAgain)

        // And each kept its own row's decisions, which is the only place a manual item's state
        // has ever lived.
        assertEquals(1, database.count("SELECT isCompleted FROM task_state WHERE taskId = '$milk'"))
        assertEquals(0, database.count("SELECT isCompleted FROM task_state WHERE taskId = '$milkAgain'"))
        database.close()
    }

    @Test
    fun `a manual item is named as one, so the cleanup path can find it later`() {
        writeFixture()
        val database = migrate()

        // Named rather than inferred from having no Asset. Decision 4 forbids deriving what a Task
        // is from how much content it holds, and a cleanup rule that guessed would sweep up the
        // first future Task that legitimately has none.
        assertEquals(3, database.count("SELECT COUNT(*) FROM legacy_manual_tasks"))
        for (row in listOf(20, 21, 30)) {
            assertEquals(
                1,
                database.count(
                    "SELECT COUNT(*) FROM legacy_manual_tasks WHERE taskId = " +
                        "(SELECT taskId FROM list_items WHERE id = $row)"
                )
            )
        }
        database.close()
    }

    @Test
    fun `a path whose List has no folder is treated as manual rather than dropped`() {
        writeFixture()
        val database = migrate()

        // Item 30 carries a root-relative path but its List is not linked to a root, which is the
        // shape that cannot be resolved either way. Kept, given a Task, given no Asset, and named
        // as manual: a row nobody can make sense of is still somebody's item.
        val taskId = "(SELECT taskId FROM list_items WHERE id = 30)"
        assertEquals(1, database.count("SELECT COUNT(*) FROM list_items WHERE id = 30"))
        assertEquals(1, database.count("SELECT COUNT(*) FROM tasks WHERE id = $taskId"))
        assertEquals(0, database.count("SELECT COUNT(*) FROM assets WHERE taskId = $taskId"))
        database.close()
    }

    // --- what the migration must not do -----------------------------------------------------------

    @Test
    fun `no review is invented`() {
        writeFixture()
        val database = migrate()

        // The one assertion that matters most. Nothing in v0.2 recorded a submission, so a row in
        // here would be the migration claiming somebody judged something they never saw.
        assertEquals(0, database.count("SELECT COUNT(*) FROM review_events"))
        database.close()
    }

    @Test
    fun `every Task has exactly one state, and no state outlives its Task`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            database.count("SELECT COUNT(*) FROM tasks"),
            database.count("SELECT COUNT(*) FROM task_state")
        )
        assertEquals(
            0,
            database.count(
                "SELECT COUNT(*) FROM task_state WHERE taskId NOT IN (SELECT id FROM tasks)"
            )
        )
        database.close()
    }

    @Test
    fun `one file is one Asset, and one Asset is one file`() {
        writeFixture()
        val database = migrate()

        assertEquals(
            database.count("SELECT COUNT(*) FROM assets"),
            database.count("SELECT COUNT(*) FROM assets WHERE rootUri IS NOT NULL")
        )
        assertEquals(
            database.count("SELECT COUNT(*) FROM assets"),
            database.count(
                "SELECT COUNT(DISTINCT rootUri || '/' || relativePath) FROM assets"
            )
        )
        database.close()
    }

    @Test
    fun `a file keeps the name a receiver already knew it by`() {
        writeFixture()
        val database = migrate()

        // `file_review_state.publicId` is what the webhook has been sending for a folder round.
        // The row it came from is still there, but the Asset is what a file became, so this is
        // what keeps a receiver keyed on that name from seeing it change under it.
        assertEquals(
            "FILE-A",
            database.text(
                "SELECT publicId FROM legacy_item_ids WHERE assetId = " +
                    "(SELECT id FROM assets WHERE taskId = " +
                    "(SELECT taskId FROM list_items WHERE id = 10))"
            )
        )
        assertEquals(
            "FILE-SOLO",
            database.text(
                "SELECT publicId FROM legacy_item_ids WHERE assetId = " +
                    "(SELECT id FROM assets WHERE relativePath = '2026-07-11/solo.jpg')"
            )
        )
        database.close()
    }

    @Test
    fun `every public id is still there, byte for byte`() {
        val before = writeFixture()
        val database = migrate()

        assertEquals(before.publicIds, database.strings("SELECT publicId FROM list_items ORDER BY id"))
        assertEquals(
            listOf("FILE-A", "FILE-SOLO"),
            database.strings("SELECT publicId FROM file_review_state ORDER BY id")
        )
        database.close()
    }

    // --- what must look exactly as it did ----------------------------------------------------------

    @Test
    fun `progress reads the same before and after`() = runTest {
        val before = writeFixture()
        val database = migrate()

        val summaries = database.listsDao().observeSummaries().first().associateBy { it.id }

        // Not a hardcoded number: the same query the app has always run, evaluated against the
        // fixture, and then again through the DAO afterwards. A migration that silently
        // uncompleted somebody's work would show up here as a smaller number, whichever list it
        // happened to.
        assertEquals(before.completed, summaries.values.sumOf { it.completedItems })
        assertEquals(before.itemCount, summaries.values.sumOf { it.totalItems })
        assertEquals(1, summaries.getValue(1L).completedItems)
        assertEquals(1, summaries.getValue(2L).completedItems)
        database.close()
    }

    @Test
    fun `a List keeps its identity, its resume point and gets the template it always had`() {
        writeFixture()
        val database = migrate()

        assertEquals(listOf("Shopping", "Trip"), database.strings("SELECT title FROM lists ORDER BY title"))
        assertEquals(10, database.count("SELECT reviewCurrentItemId FROM lists WHERE id = 1"))
        for (listId in listOf(1, 2)) {
            assertEquals(
                "builtin.media.rapid-review",
                database.text("SELECT taskReviewTemplateId FROM lists WHERE id = $listId")
            )
            assertEquals(1, database.count("SELECT taskReviewTemplateVersion FROM lists WHERE id = $listId"))
        }
        database.close()
    }

    @Test
    fun `membership keeps its row id, its position and its cached measurements`() {
        writeFixture()
        val database = migrate()

        // Row ids are what resume positions, round snapshots and webhook queue scoping speak in,
        // so a rebuild that renumbered them would quietly move every one of those.
        assertEquals(listOf(10L, 11L, 12L, 20L, 21L, 30L), database.numbers("SELECT id FROM list_items ORDER BY id"))
        assertEquals(
            listOf(0L, 1L, 2L),
            database.numbers("SELECT sortOrder FROM list_items WHERE listId = 1 ORDER BY id")
        )
        assertEquals(4096, database.count("SELECT sourceSizeBytes FROM list_items WHERE id = 10"))
        assertEquals(555, database.count("SELECT sourceModifiedAt FROM list_items WHERE id = 10"))
        database.close()
    }

    @Test
    fun `the decision columns are carried across rather than cleared`() {
        writeFixture()
        val database = migrate()

        // They stop being read the moment the read paths move, but until then they are the only
        // remaining copy of what a manual item's state was. This migration keeps them, unlike
        // V8 which cleared its equivalents: a migration later found wrong is easier to reason
        // about with the original still on disk.
        assertEquals(
            "custom2",
            database.text("SELECT customActions FROM list_items WHERE id = 20")
        )
        assertEquals(1, database.count("SELECT isCompleted FROM list_items WHERE id = 20"))
        assertEquals(
            "custom1,custom2",
            database.text("SELECT customActions FROM list_items WHERE id = 30")
        )
        database.close()
    }

    @Test
    fun `delivery history is not touched`() {
        writeFixture()
        val database = migrate()

        assertEquals(1, database.count("SELECT COUNT(*) FROM webhook_history"))
        assertEquals("SENT", database.text("SELECT outcome FROM webhook_history"))
        database.close()
    }

    @Test
    fun `a List can be deleted without taking its Tasks with it`() {
        writeFixture()
        val database = migrate()

        // A Task is not a child of a List. The decision about a file outlives the List that
        // happened to show it, which is the property the whole `file_review_state` detour existed
        // to get — and the new foreign key is NO ACTION precisely so this cannot be forgotten.
        val tasksBefore = database.count("SELECT COUNT(*) FROM tasks")
        val orphaned = database.text("SELECT taskId FROM list_items WHERE id = 10")

        database.openHelper.writableDatabase.execSQL("DELETE FROM lists WHERE id = 1")

        // The memberships go with the List, which is also the rebuilt table's own foreign key
        // working: three rows referenced list 1, and all three cascaded away.
        assertEquals(0, database.count("SELECT COUNT(*) FROM list_items WHERE listId = 1"))
        assertEquals(3, database.count("SELECT COUNT(*) FROM list_items WHERE listId = 2"))

        // The Tasks do not. No row references them, nothing cascaded, and all seven are still
        // there — including the one whose only membership just disappeared.
        assertEquals(tasksBefore, database.count("SELECT COUNT(*) FROM tasks"))
        assertEquals(1, database.count("SELECT COUNT(*) FROM tasks WHERE id = '$orphaned'"))
        assertEquals(1, database.count("SELECT COUNT(*) FROM task_state WHERE taskId = '$orphaned'"))
        database.close()
    }

    // --- the fixture -------------------------------------------------------------------------------

    /** What the fixture says about itself, read before the migration runs. */
    private class Before(val itemCount: Int, val completed: Int, val publicIds: List<String>)

    /**
     * A v12 database with every case the migration has to tell apart.
     *
     * List 1 is folder-backed at `content://root` over `2026-07-11`; list 2 is a manual list with
     * no folder. Item ids are deliberately spread out, so a rebuild that renumbered rows from 1
     * would be visibly wrong rather than accidentally right.
     */
    private fun writeFixture(): Before {
        context.deleteDatabase(V12_DB)
        val file = context.getDatabasePath(V12_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE lists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, " +
                "webhookEnabled INTEGER NOT NULL, webhookUrl TEXT NOT NULL, " +
                "lastDeliveryAt INTEGER, lastDeliveryStatus TEXT, lastDeliveryCode INTEGER, " +
                "lastDeliveryError TEXT, sourceRootUri TEXT, sourceRelativePath TEXT, " +
                "reviewCurrentItemId INTEGER)"
        )
        database.execSQL(
            "CREATE TABLE list_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "publicId TEXT NOT NULL DEFAULT '', listId INTEGER NOT NULL, " +
                "title TEXT NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "sourceUri TEXT, sourceRelativePath TEXT, rootRelativePath TEXT, " +
                "sourceMissing INTEGER NOT NULL DEFAULT 0, sourceSizeBytes INTEGER, " +
                "sourceModifiedAt INTEGER, isFavorite INTEGER NOT NULL DEFAULT 0, " +
                "customActions TEXT NOT NULL DEFAULT '', " +
                "FOREIGN KEY(listId) REFERENCES lists(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL("CREATE INDEX index_list_items_listId ON list_items (listId)")
        database.execSQL(
            "CREATE INDEX index_list_items_rootRelativePath ON list_items (rootRelativePath)"
        )
        database.execSQL(
            "CREATE TABLE file_review_state (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "publicId TEXT NOT NULL DEFAULT '', rootUri TEXT NOT NULL, " +
                "relativePath TEXT NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                "isFavorite INTEGER NOT NULL DEFAULT 0, customActions TEXT NOT NULL DEFAULT '', " +
                "sourceMissing INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_file_review_state_rootUri_relativePath " +
                "ON file_review_state (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE webhook_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                "listTitle TEXT NOT NULL, itemCount INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "at INTEGER NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL)"
        )

        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "sourceRootUri, sourceRelativePath, reviewCurrentItemId) " +
                "VALUES (1, 'Trip', 1000, NULL, 0, '', 'content://root', '2026-07-11', 10)"
        )
        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl) " +
                "VALUES (2, 'Shopping', 1001, NULL, 0, '')"
        )

        // 10: a source-backed item whose file is already known. 11: one whose file is not. 12: one
        // whose source is gone.
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions) " +
                "VALUES (10, 'ITEM-A', 1, 'a.jpg', 0, 0, 1000, 'content://root/a.jpg', 'a.jpg', " +
                "'2026-07-11/a.jpg', 0, 4096, 555, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "isFavorite, customActions) " +
                "VALUES (11, 'ITEM-B', 1, 'b.jpg', 0, 1, 1001, 'content://root/b.jpg', 'b.jpg', " +
                "'2026-07-11/b.jpg', 0, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "isFavorite, customActions) " +
                "VALUES (12, 'ITEM-GONE', 1, 'gone.jpg', 0, 2, 1002, 'content://root/gone.jpg', " +
                "'gone.jpg', '2026-07-11/gone.jpg', 1, 0, '')"
        )
        // 20 and 21: two manual items with one title, which must not collapse into one Task.
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, isFavorite, customActions) " +
                "VALUES (20, 'ITEM-MILK', 2, 'Buy milk', 1, 0, 1003, 1, 'custom2')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, isFavorite, customActions) " +
                "VALUES (21, 'ITEM-MILK-2', 2, 'Buy milk', 0, 1, 1004, 0, '')"
        )
        // 30: a path whose List is not linked to a root. Unresolvable, and kept anyway.
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, sourceRelativePath, rootRelativePath, isFavorite, customActions) " +
                "VALUES (30, 'ITEM-ORPHAN', 2, 'orphan.jpg', 0, 2, 1005, 'orphan.jpg', " +
                "'2026-07-11/orphan.jpg', 0, 'custom1,custom2')"
        )

        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, isCompleted, " +
                "isFavorite, customActions, sourceMissing, updatedAt) " +
                "VALUES (100, 'FILE-A', 'content://root', '2026-07-11/a.jpg', 1, 1, 'custom1', " +
                "0, 2000)"
        )
        // A file no List covers, which is what a folder Quick Review leaves behind.
        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, isCompleted, " +
                "isFavorite, customActions, sourceMissing, updatedAt) " +
                "VALUES (101, 'FILE-SOLO', 'content://root', '2026-07-11/solo.jpg', 0, 0, '', " +
                "0, 2001)"
        )

        database.execSQL(
            "INSERT INTO webhook_history (id, event, listTitle, itemCount, payload, at, " +
                "outcome, detail) " +
                "VALUES (1, 'listCompleted', 'Trip', 3, '{}', 3000, 'SENT', '')"
        )

        val before = Before(
            itemCount = database.rawInt("SELECT COUNT(*) FROM list_items"),
            completed = database.rawInt(
                "SELECT COALESCE(SUM(CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted " +
                    "ELSE COALESCE(s.isCompleted, 0) END), 0) " +
                    "FROM list_items i JOIN lists l ON l.id = i.listId " +
                    "LEFT JOIN file_review_state s " +
                    "ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath"
            ),
            publicIds = database.rawStrings("SELECT publicId FROM list_items ORDER BY id")
        )

        database.version = 12
        database.close()
        return before
    }

    /** Opens the fixture, which is what runs the migration and validates the result. */
    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V12_DB)
            .addMigrations(ListeaDatabase.MIGRATION_12_13, ListeaDatabase.MIGRATION_13_14)
            .build()

    /**
     * An integer out of the migrated database.
     *
     * `Int` rather than `Long` on purpose: JUnit boxes a mismatched pair and compares with
     * `equals`, so `assertEquals(0, someLong)` passes an Integer and a Long to `Object.equals` and
     * fails on a value that is plainly equal. Every number this test reads is small enough to be
     * an Int, so keeping both sides Int removes the trap rather than working around it.
     */
    private fun ListeaDatabase.count(sql: String): Int =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun ListeaDatabase.numbers(sql: String): List<Long> =
        openHelper.writableDatabase.query(sql).use { row ->
            val values = mutableListOf<Long>()
            while (row.moveToNext()) values += row.getLong(0)
            values
        }

    private fun ListeaDatabase.text(sql: String): String =
        openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun ListeaDatabase.strings(sql: String): List<String> =
        openHelper.writableDatabase.query(sql).use { row ->
            val values = mutableListOf<String>()
            while (row.moveToNext()) values += row.getString(0)
            values
        }

    private fun SQLiteDatabase.rawInt(sql: String): Int =
        rawQuery(sql, null).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun SQLiteDatabase.rawStrings(sql: String): List<String> =
        rawQuery(sql, null).use { row ->
            val values = mutableListOf<String>()
            while (row.moveToNext()) values += row.getString(0)
            values
        }

    private companion object {
        const val V12_DB = "migration-v12-to-v13.db"
    }
}
