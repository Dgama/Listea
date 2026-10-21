package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives every Todo and every List an export choice, and every history row its kind.
 *
 * **The question this step has to answer is what an install that has already been used keeps doing.**
 * Before it, there was one way out and every Todo took it; after it there is a choice, and a Todo
 * that has never been asked must not be silently switched off — the user would find out at the next
 * review, when nothing arrived, with no screen to explain it. So the defaults are compatibility
 * answers rather than blanks: an existing Todo exports by **webhook**, because that is what it did,
 * and an existing history row is a **webhook** row, because that is what it was.
 *
 * **The List is the case a plausible rule gets wrong.** The tempting rule is *a List with a URL is a
 * webhook List* — it uses the column the release is deleting, and it reads well. It is wrong for the
 * List that was switched off and still holds a URL from when it was on: that List exported nothing,
 * and a URL-based rule would start it posting the moment the user upgrades. The rule as built reads
 * the switch (`webhookEnabled`), and the fixture below carries both shapes so the wrong rule fails
 * here rather than on somebody's phone.
 *
 * **The fixture is the app's own schema with this step's additions taken off**, following
 * [MigrationV23ToV24Test]'s reasoning: writing v24's DDL for every table by hand would reproduce a
 * schema the entities already declare and would drift the moment one moved. So it is built at the
 * current version by Room, given its rows, and then wound back — the three columns removed by
 * [subtractV25Additions], which transcribes the three tables' v24 `createSql` from
 * `app/schemas/…/24.json`, and `PRAGMA user_version` put back to 24. The exact column lists are
 * asserted below, so the transcription is checked rather than trusted.
 *
 * **The step is additive.** Nothing already stored changes meaning: three columns arrive with a
 * default, and one `UPDATE` writes a value the switch already implied. `lists.webhookUrl` is not
 * touched here; it is dead after this release and `MIGRATION_26_27` clears it when it becomes the
 * List's live override — see [ListEntity.webhookUrl].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV24ToV25Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the version, and the three columns ------------------------------------------------------

    @Test
    fun `the fixture is really a v24 database`() {
        // The subtraction, asserted. A fixture that still had the columns would give the migration
        // nothing to add, and every assertion below would pass over a schema this step never
        // produced.
        writeFixture()

        assertFalse(columnsOf("sources").contains("exportKind"))
        assertFalse(columnsOf("lists").contains("exportKind"))
        assertFalse(columnsOf("webhook_history").contains("kind"))
        assertEquals(V24, fixture().version)
    }

    /**
     * The version, and the column.
     *
     * `migrate()` registers this step and every later one, so it opens a database the app itself
     * could open — which is what makes Room's schema validation part of this test rather than
     * something checked elsewhere. The version asserted is therefore the current one, not the
     * version this step ends at. When a later release adds a step, this file's chain grows or this
     * test is retired with it; either way the failure is about the chain and not about what this
     * step does.
     */
    @Test
    fun `the step takes a v24 database to the current version`() {
        writeFixture()

        val database = migrate()

        assertEquals(CURRENT, version(database))
        assertTrue(columnsOf(GONE).contains("exportKind"))
    }

    // --- what an existing Todo keeps doing --------------------------------------------------------

    /**
     * A Todo that webhooked before the upgrade still webhooks after it.
     *
     * The column's default is the whole of this behaviour, and it is deliberately *not* the answer a
     * new Todo gets: `registerSource` defaults to `none`, because a Todo created after this release
     * has been asked and its answer is whatever the dialog collected. An existing row was never
     * asked, so what it keeps is what it did.
     */
    @Test
    fun `a Todo that existed before the step exports by webhook`() {
        writeFixture()
        val database = migrate()

        assertEquals("webhook", text(database, "SELECT exportKind FROM sources WHERE id = 's-old'"))
    }

    @Test
    fun `the step writes no Todo's choice as a kind it might not have`() {
        // Every pre-existing row, not just the first: a `WHERE` on the `UPDATE` — had there been one
        // — would leave the rest at the column default, which happens to be the same word here and
        // would be a different word the next time this default moves.
        writeFixture()
        val database = migrate()

        val kinds = column(database, "SELECT exportKind FROM sources ORDER BY id")
        assertEquals(listOf("webhook", "webhook"), kinds)
    }

    // --- what an existing List keeps doing --------------------------------------------------------

    /**
     * **The case a URL-based rule gets wrong.** This List was switched off and still holds the URL
     * from when it was on.
     *
     * Under the migration as built it stays off — its `exportKind` is `inherit`, which is the word
     * for *I have not chosen*, so it goes on following its Todos exactly as it did. Under a rule that
     * keyed off `webhookUrl` it would come back on, and the first round after the upgrade would POST
     * to an address the user stopped using.
     */
    @Test
    fun `a List that was switched off stays off, whatever URL it still holds`() {
        writeFixture()
        val database = migrate()

        assertEquals("inherit", text(database, "SELECT exportKind FROM lists WHERE id = 2"))
    }

    @Test
    fun `a List that was switched on becomes a webhook List`() {
        // It has chosen for its whole contents, which is what the dropdown on its card will now
        // read — *Webhook* rather than *Same as the Todos*.
        writeFixture()
        val database = migrate()

        assertEquals("webhook", text(database, "SELECT exportKind FROM lists WHERE id = 1"))
    }

    @Test
    fun `the List switch is still the switch`() {
        // The step changes no column it did not add. `webhookUrl` survives *this* step untouched;
        // the later step that makes it the List's live override is the one that clears the stale
        // copies, and [MigrationV26ToV27Test] asserts that. This test runs the whole chain to the
        // current version, so it sees the cleared value rather than the v24 one.
        writeFixture()
        val database = migrate()

        assertEquals(1, count(database, "SELECT webhookEnabled FROM lists WHERE id = 1"))
        assertEquals(0, count(database, "SELECT webhookEnabled FROM lists WHERE id = 2"))
        assertEquals("", text(database, "SELECT webhookUrl FROM lists WHERE id = 1"))
        assertEquals("", text(database, "SELECT webhookUrl FROM lists WHERE id = 2"))
    }

    // --- what the history says it was --------------------------------------------------------------

    @Test
    fun `a history row written before the step is a webhook row`() {
        // It cannot have been anything else: the webhook was the only way out. Reading it as an
        // unknown kind would leave two rows of the same round disagreeing about what they were.
        writeFixture()
        val database = migrate()

        assertEquals(
            listOf("webhook", "webhook"),
            column(database, "SELECT kind FROM webhook_history ORDER BY id")
        )
    }

    @Test
    fun `the history keeps every row, and keeps calling itself webhook_history`() {
        // The table's name is frozen (decision 7). Renaming a table is a rebuild for a word, and the
        // word is in the migration's history either way.
        writeFixture()
        val database = migrate()

        assertEquals(2, count(database, "SELECT COUNT(*) FROM webhook_history"))
        assertEquals(
            listOf("quickreview.completed", "review.exited"),
            column(database, "SELECT event FROM webhook_history ORDER BY id")
        )
    }

    // --- the shape the fixture rests on -------------------------------------------------------------

    @Test
    fun `the v24 tables hold the columns v24's schema declares`() {
        // Golden rather than derived, and the two catch different mistakes. This is what notices a
        // transcription slip in the DDL below, since Room only validates the schema a migration
        // *ends* at — a v24 table missing a column would be replaced by an `ALTER` that added the
        // right one, and nothing would say the fixture was wrong. A future release that adds a column
        // to `sources` fails here, which is the signal to update the fixture rather than to trust it.
        writeFixture()

        assertEquals(V24_SOURCES, columnsOf("sources"))
        assertEquals(V24_LISTS, columnsOf("lists"))
        assertEquals(V24_HISTORY, columnsOf("webhook_history"))
    }

    @Test
    fun `winding the three tables back leaves the rest of the schema alone`() {
        // The other half: a subtraction that took a row or a column off something it was not asked
        // about would make the migration's own assertions meaningless.
        writeFixture()

        // `task_imports` still points at `sources`, which is the foreign key a rename performed
        // without `legacy_alter_table` would have rewritten out from under it. Read as its own DDL
        // rather than as a column list, because the FK clause is the thing that could have been
        // rewritten — see [subtractV25Additions].
        assertTrue(hasTable("task_imports"))
        assertTrue(
            "task_imports's foreign key was rewritten by the subtraction",
            sqlOf("task_imports").contains("REFERENCES `sources`(`id`)")
        )
        assertTrue(
            "list_items's foreign key was rewritten by the subtraction",
            sqlOf("list_items").contains("REFERENCES `lists`(`id`)")
        )
        // And the tables the step does not touch kept their rows.
        assertEquals(2, countRows("sources"))
        assertEquals(2, countRows("lists"))
        assertEquals(2, countRows("webhook_history"))
    }

    // --- the fixture ---------------------------------------------------------------------------------

    /**
     * Two Todos, two Lists and two history rows, in the shapes the backfill has to tell apart.
     *
     * Two Lists and not one, because the switch is what the `UPDATE` reads: one List that was on and
     * one that was off, and the off one deliberately still holding a URL — the row a URL-based rule
     * would bring back to life.
     */
    private fun writeFixture() {
        val database = openAtCurrentVersion()
        // `exportKind` and `kind` are written here and thrown away: the rows have to satisfy the
        // *current* schema's `NOT NULL` on the way in — the column has no SQL default, because the
        // entity carries a Kotlin one — and `subtractV25Additions` takes the column back off
        // afterwards. Writing the word the migration is supposed to produce would make the
        // assertions below pass whatever the migration did, so `none` is written instead.
        database.exec(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                "followUpstream, assetControls, exportKind, lastImportAt, lastImportStatus) VALUES " +
                "('s-old', 'content://root', '', 'Holiday', 'file-as-task', 1, 1, 1, 0, " +
                "'default', 0, '', 'none', NULL, NULL), " +
                "('s-older', 'content://other', '', 'Work', 'folder-as-task', 1, 1, 0, 0, " +
                "'default', 0, '', 'none', NULL, NULL)"
        )
        database.exec(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "exportKind, lastDeliveryAt, lastDeliveryStatus, lastDeliveryCode, " +
                "lastDeliveryError, lastDissolvedCount, lastDissolvedAt, lastDissolvedReason, " +
                "sourceRootUri, sourceRelativePath, reviewCurrentItemId, taskReviewTemplateId, " +
                "taskReviewTemplateVersion) VALUES " +
                "(1, 'On', 1, NULL, 1, 'https://on.example/hook', 'none', NULL, NULL, NULL, NULL, " +
                "NULL, NULL, 'none', NULL, NULL, NULL, 'builtin.media.rapid-review', 1), " +
                "(2, 'Off', 1, NULL, 0, 'https://stale.example/hook', 'none', NULL, NULL, NULL, " +
                "NULL, NULL, NULL, 'none', NULL, NULL, NULL, 'builtin.media.rapid-review', 1)"
        )
        database.exec(
            "INSERT INTO webhook_history (id, event, listTitle, itemCount, payload, at, outcome, " +
                "detail, kind) VALUES " +
                "(1, 'quickreview.completed', 'Holiday', 2, '{}', 1, 'SENT', 'Sent · 200', 'none'), " +
                "(2, 'review.exited', 'Holiday', 1, '{}', 2, 'FAILED', 'Failed', 'none')"
        )
        database.subtractV25Additions()
        database.close()
        rewind()
    }

    /** The app's own schema, at the version the entities declare, with nothing migrated. */
    private fun openAtCurrentVersion(): ListeaDatabase {
        context.deleteDatabase(V24_DB)
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V24_DB)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    /** The version, and nothing else — see `the fixture is really a v24 database`. */
    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V24_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V24 }
    }

    private fun migrate(): ListeaDatabase =
        Room.databaseBuilder(context, ListeaDatabase::class.java, V24_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_24_25,
                ListeaDatabase.MIGRATION_25_26,
                ListeaDatabase.MIGRATION_26_27,
                ListeaDatabase.MIGRATION_27_28
            )
            .build()
            .also { it.openHelper.writableDatabase }

    // --- reading the fixture without Room ------------------------------------------------------------

    /**
     * A single column of a single row, asserted to exist rather than defaulted.
     *
     * `runTest` is not used here on purpose — nothing in this file suspends, and the name is kept
     * off the helpers so that a reader is not told otherwise.
     */
    private fun text(database: ListeaDatabase, sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun count(database: ListeaDatabase, sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun column(database: ListeaDatabase, sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { row ->
            buildList { while (row.moveToNext()) add(row.getString(0)) }
        }

    private fun version(database: ListeaDatabase): Int =
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { row ->
            assertTrue("no row for the version", row.moveToFirst())
            row.getInt(0)
        }

    /** The fixture's own shape, read without Room — see [MigrationV23ToV24Test.fixture]. */
    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V24_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun hasTable(name: String): Boolean =
        fixture().use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name)
            ).use { row ->
                assertTrue("no row for the sqlite_master count", row.moveToFirst())
                row.getInt(0) > 0
            }
        }

    private fun columnsOf(table: String): List<String> =
        fixture().use { database ->
            database.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private fun sqlOf(table: String): String =
        fixture().use { database ->
            database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table)
            ).use { row ->
                assertTrue("no row for: $table", row.moveToFirst())
                row.getString(0)
            }
        }

    private fun countRows(table: String): Int =
        fixture().use { database ->
            database.rawQuery("SELECT COUNT(*) FROM $table", null).use { row ->
                assertTrue("no row for the count of $table", row.moveToFirst())
                row.getInt(0)
            }
        }

    private companion object {
        const val V24_DB = "v24-fixture.db"
        const val V24 = 24

        /** The version the entities declare, which this step plus every later one reaches. */
        const val CURRENT = 28

        /** The table `the step takes a v24 database to the current version` writes its column into. */
        const val GONE = "sources"

        /** v24's `sources`, as `app/schemas/…/24.json` writes it. */
        val V24_SOURCES = listOf(
            "id", "rootUri", "relativePath", "displayName", "importerKind", "importerVersion",
            "createdAt", "keepTree", "keepStateOnDelete", "contentPolicy", "followUpstream",
            "assetControls", "lastImportAt", "lastImportStatus"
        )

        /** v24's `lists`. It still holds `webhookUrl`, which this step leaves exactly where it is. */
        val V24_LISTS = listOf(
            "id", "title", "createdAt", "completedAt", "webhookEnabled", "webhookUrl",
            "lastDeliveryAt", "lastDeliveryStatus", "lastDeliveryCode", "lastDeliveryError",
            "lastDissolvedCount", "lastDissolvedAt", "lastDissolvedReason", "sourceRootUri",
            "sourceRelativePath", "reviewCurrentItemId", "taskReviewTemplateId",
            "taskReviewTemplateVersion"
        )

        /** v24's `webhook_history`, before it had a `kind`. */
        val V24_HISTORY = listOf(
            "id", "event", "listTitle", "itemCount", "payload", "at", "outcome", "detail"
        )
    }
}
