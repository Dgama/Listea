package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The migrations nothing else walks, run end to end against a database in the shape they expect.
 *
 * Two steps on the v0.2 path transform data rather than add a column, and they were written without
 * a database to run them against: V8 moves every review decision off the row that held it and onto
 * the file it is about, and V11 rebuilds the unsent-payload store as a record of every delivery.
 * Neither had ever executed anywhere but on a device. The plan flagged exactly this — Room's
 * validation checks the schema a migration *ends* at, and a `CREATE TABLE` written by hand is only
 * checked against the entities if the table survives to the current version unchanged. So the
 * assertions below are not about the shape arriving; they are about what the statements do.
 *
 * One walk, from V7, covers both, because both sit on the path: V7 is the oldest schema that can be
 * written out by hand. Everything it needs is derivable — V6 added `unsent_webhooks` and V7 changed
 * nothing — and everything under it would mean reconstructing the v1 `CREATE TABLE`s that no
 * migration records, which is the eleven-historical-commits problem the plan declined to fund.
 *
 * A second fixture starts at V9, because V8's own `INSERT` writes `updatedAt = 0` on every file row
 * it makes and V10 therefore takes the standby-clock branch for all of them. V9 is the oldest
 * version at which a file row can carry a real timestamp, so it is the earliest place the other
 * branch of that choice can be seen — and starting there rather than later keeps both V10 and V11
 * on the path as well.
 *
 * **What this proves and what it does not.** It proves these statements run, in this order, against
 * a populated database and leave the values they claim to leave. It does not prove the app behaves
 * correctly on a real folder of photographs, and it does not stand in for opening a v12 install
 * once on a device and looking at it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationChainTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a v7 database walks the whole chain and keeps every decision`() {
        writeV7Fixture()

        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V7_DB)
            .addMigrations(*CHAIN_FROM_7)
            .build()

        // --- V8: every source-backed item learned the path a decision is stored against -----------
        //
        // Built from the folder its List is linked to, and the three shapes are separate: a List
        // over a subfolder prefixes, a List over the granted folder itself does not, and an item
        // with no source path at all is left alone rather than given an empty one.
        assertEquals(
            "2026-07-11/a.jpg",
            text(database, "SELECT rootRelativePath FROM list_items WHERE id = 1")
        )
        assertEquals(
            "2026-07-11/b.jpg",
            text(database, "SELECT rootRelativePath FROM list_items WHERE id = 2")
        )
        assertEquals(true, isNull(database, "SELECT rootRelativePath FROM list_items WHERE id = 3"))
        assertEquals("c.jpg", text(database, "SELECT rootRelativePath FROM list_items WHERE id = 4"))
        assertEquals("c.jpg", text(database, "SELECT rootRelativePath FROM list_items WHERE id = 5"))

        // --- V8: the decisions moved, and the rows they came from stopped answering ---------------
        //
        // At V13 a file's decision is a Task's state, so this is where V8's copy across is read
        // from. Both halves matter: the file that has what the row had, and the row that no longer
        // offers a second opinion about it.
        assertEquals(1, int(database, fileState("2026-07-11/a.jpg", "isCompleted")))
        assertEquals(1, int(database, fileState("2026-07-11/a.jpg", "isFavorite")))
        assertEquals("custom1", text(database, fileState("2026-07-11/a.jpg", "customActions")))
        assertEquals(0, int(database, fileState("2026-07-11/b.jpg", "isCompleted")))
        assertEquals("custom2", text(database, fileState("2026-07-11/b.jpg", "customActions")))

        assertEquals(0, int(database, "SELECT isCompleted FROM list_items WHERE id = 1"))
        assertEquals(0, int(database, "SELECT isFavorite FROM list_items WHERE id = 1"))
        assertEquals("", text(database, "SELECT customActions FROM list_items WHERE id = 1"))
        assertEquals(0, int(database, "SELECT isCompleted FROM list_items WHERE id = 2"))

        // Presence came across with the decision, and stayed a fact about the file: `b.jpg` was
        // missing before the migration and is missing after it.
        assertEquals(
            1,
            int(database, "SELECT sourceMissing FROM assets WHERE relativePath = '2026-07-11/b.jpg'")
        )
        assertEquals(
            0,
            int(database, "SELECT sourceMissing FROM assets WHERE relativePath = '2026-07-11/a.jpg'")
        )

        // Two Lists over the same folder naming the same file is the shape V8's `MAX()` was written
        // for and the one its KDoc says cannot happen. It happened here, and what it produced is
        // what the KDoc promises: one file, carrying the union rather than either row's answer.
        assertEquals(1, int(database, fileState("c.jpg", "isCompleted")))
        assertEquals(1, int(database, fileState("c.jpg", "isFavorite")))
        assertEquals("custom1", text(database, fileState("c.jpg", "customActions")))
        assertEquals(1, int(database, "SELECT COUNT(*) FROM assets WHERE relativePath = 'c.jpg'"))
        assertEquals(
            2,
            int(
                database,
                "SELECT COUNT(*) FROM list_items WHERE taskId = " +
                    "(SELECT taskId FROM assets WHERE relativePath = 'c.jpg')"
            )
        )

        // --- V10: a name for every row that could go on the wire ----------------------------------
        //
        // The membership's own time is in its id, because it had one. The file's row was written by
        // V8 with `updatedAt = 0`, so there its fingerprint is asserted instead: the identity the
        // id was built from is still the file's path, whatever clock stood in for the time.
        assertEquals(
            newItemPublicId(fileIdentity("2026-07-11/a.jpg"), 1000, random = 0).substringBeforeLast('-'),
            text(database, "SELECT publicId FROM list_items WHERE id = 1").substringBeforeLast('-')
        )
        assertEquals(
            newItemPublicId(manualItemIdentity(2, "Buy milk"), 1002, random = 0).substringBeforeLast('-'),
            text(database, "SELECT publicId FROM list_items WHERE id = 3").substringBeforeLast('-')
        )
        val fileId = text(
            database,
            "SELECT li.publicId FROM legacy_item_ids li JOIN assets a ON a.id = li.assetId " +
                "WHERE a.relativePath = '2026-07-11/a.jpg'"
        )
        assertEquals(ITEM_PUBLIC_ID_LENGTH, fileId.length)
        assertEquals(
            newItemPublicId(fileIdentity("2026-07-11/a.jpg"), 0, random = 0).split("-")[1],
            fileId.split("-")[1]
        )

        // --- V11: the failure log became a delivery record ----------------------------------------
        //
        // Both old outcomes are recovered from the words the rows already carried, because at V11
        // there was no column that said which was which — only a fixed phrase for the one case a
        // user had chosen.
        assertEquals(2, int(database, "SELECT COUNT(*) FROM webhook_history"))
        assertEquals("FAILED", text(database, "SELECT outcome FROM webhook_history WHERE id = 1"))
        assertEquals(9000L, long(database, "SELECT at FROM webhook_history WHERE id = 1"))
        assertEquals("HTTP 500", text(database, "SELECT detail FROM webhook_history WHERE id = 1"))
        assertEquals(
            "{\"n\":1}",
            text(database, "SELECT payload FROM webhook_history WHERE id = 1")
        )
        assertEquals("DISABLED", text(database, "SELECT outcome FROM webhook_history WHERE id = 2"))
        assertEquals(
            0,
            int(
                database,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'unsent_webhooks'"
            )
        )

        // --- V13: the typed item is a Task with no Asset, and named where cleanup can find it -----
        assertEquals(1, int(database, "SELECT isCompleted FROM list_items WHERE id = 3"))
        assertEquals("", text(database, "SELECT customActions FROM list_items WHERE id = 3"))
        assertEquals(1, int(database, "SELECT COUNT(*) FROM legacy_manual_tasks"))
        assertEquals(
            1,
            int(
                database,
                "SELECT ts.isCompleted FROM task_state ts JOIN list_items i ON i.taskId = ts.taskId " +
                    "WHERE i.id = 3"
            )
        )
        assertEquals(
            0,
            int(
                database,
                "SELECT COUNT(*) FROM assets a JOIN list_items i ON i.taskId = a.taskId " +
                    "WHERE i.id = 3"
            )
        )

        // Every membership resolved to a Task, including the two that resolved to the same one.
        assertEquals(0, int(database, "SELECT COUNT(*) FROM list_items WHERE taskId IS NULL"))
        assertEquals(
            4,
            int(
                database,
                "SELECT COUNT(*) FROM lists WHERE taskReviewTemplateId = " +
                    "'builtin.media.rapid-review' AND taskReviewTemplateVersion = 1"
            )
        )

        // --- V24: the marks become a set, and the set agrees with the columns beside it -----------
        //
        // The step is additive, so the two columns it copied from are still here to be read: a file
        // that carried `★` and `custom1` carries both as ids now, and the file that carried `★`
        // alone carries exactly one. Read against the pairs V20 wrote, which is what makes this a
        // check on the merge rather than a restatement of it.
        assertEquals("custom1,favorite", selections(database, "2026-07-11/a.jpg"))
        assertEquals("custom2", selections(database, "2026-07-11/b.jpg"))
        assertEquals("custom1,favorite", selections(database, "c.jpg"))
        assertEquals(1, int(database, "SELECT COUNT(*) FROM templates"))
        assertEquals(
            "builtin.media.asset-control",
            text(database, "SELECT id FROM templates")
        )

        database.close()
    }

    @Test
    fun `a v9 database names each row from its own time`() {
        writeV9Fixture()

        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V9_DB)
            .addMigrations(*CHAIN_FROM_9)
            .build()

        // The two rows are about the same file, so their ids fingerprint identically — and they
        // were still given different names, because V10 hands each one the time it recorded rather
        // than one clock for the whole run. A backfill that read the wall clock once would make
        // every row in a database sort to the same instant.
        val itemId = text(database, "SELECT publicId FROM list_items WHERE id = 1")
        val stateId = text(database, "SELECT publicId FROM file_review_state WHERE id = 1")

        assertEquals(
            newItemPublicId(fileIdentity("2026-07-11/a.jpg"), 1000, random = 0).substringBeforeLast('-'),
            itemId.substringBeforeLast('-')
        )
        assertEquals(
            newItemPublicId(fileIdentity("2026-07-11/a.jpg"), 5000, random = 0).substringBeforeLast('-'),
            stateId.substringBeforeLast('-')
        )
        assertEquals(itemId.split("-")[1], stateId.split("-")[1])
        assertNotEquals(itemId.split("-")[0], stateId.split("-")[0])

        // And the file's name is the one that reaches a receiver, which is where V13 put it.
        assertEquals(stateId, text(database, "SELECT publicId FROM legacy_item_ids"))
        assertEquals(
            1,
            int(
                database,
                "SELECT ts.isCompleted FROM task_state ts JOIN assets a ON a.taskId = ts.taskId " +
                    "WHERE a.relativePath = '2026-07-11/a.jpg'"
            )
        )

        // An empty failure log is the ordinary case, and V11 has to survive it: no rows to classify
        // and nothing to carry over, but the table it becomes still has to be the one V12 declares.
        assertEquals(0, int(database, "SELECT COUNT(*) FROM webhook_history"))

        // V24 runs on this path too, and the seed it writes is the same one whatever came before it
        // — a database that never had a `list_items` decision column still needs something to
        // review under. What the pair at the end of this path holds is not asserted as a literal,
        // because which of the two v9 rows V13 preferred for the marks is a question this walk is
        // not about; the merge is checked instead, and checked on every row: the set must be the two
        // columns beside it, with `★` folded in and the result sorted.
        assertEquals(1, int(database, "SELECT COUNT(*) FROM template_versions"))
        assertEquals(
            0,
            int(
                database,
                "SELECT COUNT(*) FROM task_asset_state WHERE selections <> CASE " +
                    "WHEN isFavorite = 0 THEN customActions " +
                    "WHEN customActions = '' THEN 'favorite' " +
                    "ELSE customActions || ',favorite' END"
            )
        )

        database.close()
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * A v7 database: a List over a subfolder, a List with items typed into it, two Lists over the
     * granted folder itself that name the same file, and a failure log holding both of the outcomes
     * V6 could write.
     */
    private fun writeV7Fixture() {
        val database = openFixture(V7_DB)
        createLists(database)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS list_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, listId INTEGER NOT NULL, " +
                "title TEXT NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "sourceUri TEXT, sourceRelativePath TEXT, " +
                "sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                "custom1 INTEGER NOT NULL DEFAULT 0, custom2 INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(listId) REFERENCES lists(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_list_items_listId ON list_items (listId)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS unsent_webhooks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                "listTitle TEXT NOT NULL, itemCount INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "failedAt INTEGER NOT NULL, reason TEXT NOT NULL)"
        )

        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "sourceRootUri, sourceRelativePath) VALUES " +
                "(1, 'Trip', 1000, 0, '', 'content://root', '2026-07-11'), " +
                "(2, 'Shopping', 1001, 0, '', NULL, NULL), " +
                "(3, 'Root', 1002, 0, '', 'content://root', ''), " +
                "(4, 'Root too', 1003, 0, '', 'content://root', '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, listId, title, isCompleted, sortOrder, createdAt, " +
                "sourceRelativePath, sourceMissing, isFavorite, custom1, custom2) VALUES " +
                "(1, 1, 'a.jpg', 1, 0, 1000, 'a.jpg', 0, 1, 1, 0), " +
                "(2, 1, 'b.jpg', 0, 1, 1001, 'b.jpg', 1, 0, 0, 1), " +
                "(3, 2, 'Buy milk', 1, 0, 1002, NULL, 0, 0, 0, 0), " +
                "(4, 3, 'c.jpg', 1, 0, 1003, 'c.jpg', 0, 0, 0, 0), " +
                "(5, 4, 'c.jpg', 0, 0, 1004, 'c.jpg', 0, 1, 1, 0)"
        )
        database.execSQL(
            "INSERT INTO unsent_webhooks (id, event, listTitle, itemCount, payload, failedAt, " +
                "reason) VALUES " +
                "(1, 'LIST_COMPLETED', 'Trip', 2, '{\"n\":1}', 9000, 'HTTP 500'), " +
                "(2, 'LIST_COMPLETED', 'Trip', 2, '{\"n\":2}', 9001, " +
                "'Nothing was sent · you chose not to send it')"
        )

        database.version = 7
        database.close()
    }

    /**
     * A v9 database: the shape V8 leaves, where a file row can still carry a time of its own.
     *
     * `unsent_webhooks` is created and left empty rather than omitted — V7 always makes it, so a
     * database without it is one that never existed, and V11 would fail on a fixture that has none
     * for a reason the app could never produce.
     */
    private fun writeV9Fixture() {
        val database = openFixture(V9_DB)
        createLists(database)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS list_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, listId INTEGER NOT NULL, " +
                "title TEXT NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "sourceUri TEXT, sourceRelativePath TEXT, rootRelativePath TEXT, " +
                "sourceMissing INTEGER NOT NULL DEFAULT 0, sourceSizeBytes INTEGER, " +
                "sourceModifiedAt INTEGER, isFavorite INTEGER NOT NULL DEFAULT 0, " +
                "custom1 INTEGER NOT NULL DEFAULT 0, custom2 INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(listId) REFERENCES lists(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_list_items_listId ON list_items (listId)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
                "ON list_items (rootRelativePath)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS file_review_state (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, rootUri TEXT NOT NULL, " +
                "relativePath TEXT NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, " +
                "isFavorite INTEGER NOT NULL DEFAULT 0, custom1 INTEGER NOT NULL DEFAULT 0, " +
                "custom2 INTEGER NOT NULL DEFAULT 0, sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_file_review_state_rootUri_relativePath " +
                "ON file_review_state (rootUri, relativePath)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS unsent_webhooks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                "listTitle TEXT NOT NULL, itemCount INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "failedAt INTEGER NOT NULL, reason TEXT NOT NULL)"
        )

        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
                "sourceRootUri, sourceRelativePath) " +
                "VALUES (1, 'Trip', 1000, 0, '', 'content://root', '2026-07-11')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, listId, title, isCompleted, sortOrder, createdAt, " +
                "sourceRelativePath, rootRelativePath, sourceMissing) " +
                "VALUES (1, 1, 'a.jpg', 1, 0, 1000, 'a.jpg', '2026-07-11/a.jpg', 0)"
        )
        database.execSQL(
            "INSERT INTO file_review_state (id, rootUri, relativePath, isCompleted, updatedAt) " +
                "VALUES (1, 'content://root', '2026-07-11/a.jpg', 1, 5000)"
        )

        database.version = 9
        database.close()
    }

    /** The `lists` table as it stood from V5 to V12 — the one table no migration in this range adds to. */
    private fun createLists(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS lists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, " +
                "webhookEnabled INTEGER NOT NULL, webhookUrl TEXT NOT NULL, " +
                "lastDeliveryAt INTEGER, lastDeliveryStatus TEXT, lastDeliveryCode INTEGER, " +
                "lastDeliveryError TEXT, sourceRootUri TEXT, sourceRelativePath TEXT, " +
                "reviewCurrentItemId INTEGER)"
        )
    }

    private fun openFixture(name: String): SQLiteDatabase {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun fileState(relativePath: String, column: String): String =
        "SELECT ts.$column FROM task_state ts JOIN assets a ON a.taskId = ts.taskId " +
            "WHERE a.relativePath = '$relativePath'"

    /**
     * One file's pair record, by the path the file is located at.
     *
     * Keyed on the Asset rather than the Task, because that is where the marks live now: a Task with
     * two Assets has two answers and this asks about one of them.
     */
    private fun selections(database: ListeaDatabase, relativePath: String): String =
        text(
            database,
            "SELECT s.selections FROM task_asset_state s JOIN assets a ON a.id = s.assetId " +
                "WHERE a.relativePath = '$relativePath'"
        )

    private fun text(database: ListeaDatabase, sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun int(database: ListeaDatabase, sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun long(database: ListeaDatabase, sql: String): Long =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun isNull(database: ListeaDatabase, sql: String): Boolean =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.isNull(0)
        }

    private companion object {
        const val V7_DB = "migration-chain-v7.db"
        const val V9_DB = "migration-chain-v9.db"

        val CHAIN_FROM_7 = arrayOf(
            ListeaDatabase.MIGRATION_7_8,
            ListeaDatabase.MIGRATION_8_9,
            ListeaDatabase.MIGRATION_9_10,
            ListeaDatabase.MIGRATION_10_11,
            ListeaDatabase.MIGRATION_11_12,
            ListeaDatabase.MIGRATION_12_13,
            ListeaDatabase.MIGRATION_13_14,
            ListeaDatabase.MIGRATION_14_15,
            ListeaDatabase.MIGRATION_15_16,
            ListeaDatabase.MIGRATION_16_17,
            ListeaDatabase.MIGRATION_17_18,
            ListeaDatabase.MIGRATION_18_19,
            ListeaDatabase.MIGRATION_19_20,
            ListeaDatabase.MIGRATION_20_21,
            ListeaDatabase.MIGRATION_21_22,
            ListeaDatabase.MIGRATION_22_23,
            ListeaDatabase.MIGRATION_23_24,
            ListeaDatabase.MIGRATION_24_25,
            ListeaDatabase.MIGRATION_25_26,
            ListeaDatabase.MIGRATION_26_27,
            ListeaDatabase.MIGRATION_27_28
        )

        val CHAIN_FROM_9 = arrayOf(
            ListeaDatabase.MIGRATION_9_10,
            ListeaDatabase.MIGRATION_10_11,
            ListeaDatabase.MIGRATION_11_12,
            ListeaDatabase.MIGRATION_12_13,
            ListeaDatabase.MIGRATION_13_14,
            ListeaDatabase.MIGRATION_14_15,
            ListeaDatabase.MIGRATION_15_16,
            ListeaDatabase.MIGRATION_16_17,
            ListeaDatabase.MIGRATION_17_18,
            ListeaDatabase.MIGRATION_18_19,
            ListeaDatabase.MIGRATION_19_20,
            ListeaDatabase.MIGRATION_20_21,
            ListeaDatabase.MIGRATION_21_22,
            ListeaDatabase.MIGRATION_22_23,
            ListeaDatabase.MIGRATION_23_24,
            ListeaDatabase.MIGRATION_24_25,
            ListeaDatabase.MIGRATION_25_26,
            ListeaDatabase.MIGRATION_26_27,
            ListeaDatabase.MIGRATION_27_28
        )
    }
}
