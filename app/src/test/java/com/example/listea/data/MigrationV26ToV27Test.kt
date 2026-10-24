package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives a Todo its own webhook address, and the one-time snapshot that finishes it.
 *
 * **Two halves, because a migration cannot read DataStore.** The SQLite step adds the columns and
 * records the work in `schema_backfills`; [ListsDao.applyWebhookAddressBootstrap] copies Settings'
 * default onto the Todos that were posting through it. The tests below pin both: the schema and the
 * marker here, the values and the marker's consumption there.
 *
 * **The fixture is v26 with this step's additions taken back off**, following the other migration
 * tests: built at the version the entities declare, populated, then [subtractV27Additions] removes
 * the columns and the marker table and `PRAGMA user_version` is put back to 26.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV26ToV27Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v26 database`() {
        // The subtraction, asserted. A fixture that still held the columns would give the
        // migration nothing to add, and the address assertions below would pass over a shape this
        // step never produced.
        assertFalse(columnsOf("sources").contains("webhookUrl"))
        assertFalse(columnsOf("sources").contains("webhookFromDefault"))
        assertFalse(columnsOf("webhook_history").contains("webhookUrl"))
        assertFalse(columnsOf("webhook_history").contains("roundId"))
        assertFalse(tables().contains("schema_backfills"))
        assertEquals(V26, fixture().version)
    }

    @Test
    fun `the step takes a v26 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        assertTrue(columnsOf("sources").contains("webhookUrl"))
        assertTrue(columnsOf("sources").contains("webhookFromDefault"))
        assertTrue(columnsOf("webhook_history").contains("webhookUrl"))
        assertTrue(columnsOf("webhook_history").contains("roundId"))
        assertTrue(tables().contains("schema_backfills"))
    }

    @Test
    fun `the step records the address work for the startup bootstrap`() {
        val database = migrate()

        assertEquals(
            1,
            count(database, "SELECT COUNT(*) FROM schema_backfills WHERE name = '$WEBHOOK_ADDRESS_BACKFILL'")
        )
    }

    // --- what the step clears ---------------------------------------------------------------------

    @Test
    fun `every List's old copied address is cleared`() {
        // The column held a copy of Settings' default seeded at creation, and it becomes the List's
        // live override in this release. Keeping the copies would silently redirect every existing
        // List the moment a non-empty value started meaning something.
        val database = migrate()

        assertEquals(
            listOf("", ""),
            column(database, "SELECT webhookUrl FROM lists ORDER BY id")
        )
    }

    @Test
    fun `a history row written before the step has no address and no round id`() {
        val database = migrate()

        assertEquals(
            listOf("", ""),
            column(database, "SELECT webhookUrl FROM webhook_history ORDER BY id")
        )
        assertEquals(
            listOf("", ""),
            column(database, "SELECT roundId FROM webhook_history ORDER BY id")
        )
    }

    // --- the bootstrap ----------------------------------------------------------------------------

    @Test
    fun `with the old gate on every Todo copies the default and says where it came from`() {
        val database = migrate()

        assertTrue(bootstrap(database, gateWasOn = true, url = "https://default.example/hook"))

        assertEquals(
            listOf("https://default.example/hook", "https://default.example/hook"),
            column(database, "SELECT webhookUrl FROM sources ORDER BY id")
        )
        assertEquals(
            listOf(1, 1),
            column(database, "SELECT webhookFromDefault FROM sources ORDER BY id").map { it.toInt() }
        )
        assertEquals(0, count(database, "SELECT COUNT(*) FROM schema_backfills"))
    }

    @Test
    fun `with the old gate off every address stays empty and the marker is still consumed`() {
        // The install had delivery switched off. Copying the default would start posting work the
        // user had disabled; leaving the marker would make every start re-ask the question.
        val database = migrate()

        assertTrue(bootstrap(database, gateWasOn = false, url = "https://default.example/hook"))

        assertEquals(
            listOf("", ""),
            column(database, "SELECT webhookUrl FROM sources ORDER BY id")
        )
        assertEquals(
            listOf(0, 0),
            column(database, "SELECT webhookFromDefault FROM sources ORDER BY id").map { it.toInt() }
        )
        assertEquals(0, count(database, "SELECT COUNT(*) FROM schema_backfills"))
    }

    @Test
    fun `an empty default still consumes the marker`() {
        // Gate on but nothing to copy. The decision has been made — there is no address — and a
        // later default edit must not reach rows that existed before it.
        val database = migrate()

        assertTrue(bootstrap(database, gateWasOn = true, url = "   "))

        assertEquals(
            listOf("", ""),
            column(database, "SELECT webhookUrl FROM sources ORDER BY id")
        )
        assertEquals(
            listOf(0, 0),
            column(database, "SELECT webhookFromDefault FROM sources ORDER BY id").map { it.toInt() }
        )
        assertEquals(0, count(database, "SELECT COUNT(*) FROM schema_backfills"))
    }

    @Test
    fun `a second bootstrap does nothing, and a later default does not reach the rows`() {
        val database = migrate()
        assertTrue(bootstrap(database, gateWasOn = true, url = "https://first.example/hook"))

        assertFalse(bootstrap(database, gateWasOn = true, url = "https://later.example/hook"))

        assertEquals(
            listOf("https://first.example/hook", "https://first.example/hook"),
            column(database, "SELECT webhookUrl FROM sources ORDER BY id")
        )
    }

    @Test
    fun `a Todo created after the bootstrap keeps its own address`() {
        val database = migrate()
        bootstrap(database, gateWasOn = true, url = "https://default.example/hook")

        database.exec(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                "followUpstream, assetControls, exportKind, addOn, webhookUrl, " +
                "webhookFromDefault) VALUES " +
                "('s-new', 'content://root', 'jable', 'jable', 'file-as-task', 1, 10, 1, 0, " +
                "'default', 0, '', 'webhook', '', 'https://own.example/hook', 0)"
        )

        assertFalse(bootstrap(database, gateWasOn = true, url = "https://later.example/hook"))

        assertEquals(
            "https://own.example/hook",
            text(database, "SELECT webhookUrl FROM sources WHERE id = 's-new'")
        )
    }

    // --- fixture ----------------------------------------------------------------------------------

    /**
     * Two Todos, two Lists and two history rows, in v26's shape.
     *
     * The Lists deliberately carry the stale copied URLs the old release seeded; clearing them is
     * the one destructive-looking part of a step that otherwise only adds. The history rows carry
     * different kinds so the column a legacy row lacks is visible for a txt row as well as a
     * webhook one.
     */
    @Before
    fun writeFixture() {
        context.deleteDatabase(V26_DB)
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V26_DB)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase

        database.exec(
            // `addOn` is the later release's column, written here only because the fixture is built
            // on the current schema; [subtractV27Additions] takes it back off with the rest.
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                "followUpstream, assetControls, exportKind, addOn) VALUES " +
                "('s-old', 'content://root', 'cos', 'cos', 'file-as-task', 1, 1, 1, 0, " +
                "'default', 0, '', 'webhook', ''), " +
                "('s-older', 'content://root', 'xasiat', 'xasiat', 'file-as-task', 1, 2, 1, 0, " +
                "'default', 0, '', 'none', '')"
        )
        database.exec(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "exportKind, lastDeliveryStatus, lastDissolvedReason) VALUES " +
                "(1, 'Trip', 1, NULL, 1, 'https://on.example/hook', 'inherit', NULL, 'dissolved'), " +
                "(2, 'Work', 1, NULL, 0, 'https://stale.example/hook', 'none', NULL, 'dissolved')"
        )
        database.exec(
            "INSERT INTO webhook_history (id, event, listTitle, itemCount, payload, at, outcome, " +
                "detail, kind) VALUES " +
                "(1, 'list.completed', 'Trip', 2, '{}', 1, 'SENT', 'Sent · 200', 'webhook'), " +
                "(2, 'quickreview.completed', 'Work', 1, '{}', 2, 'WRITTEN', 'Written', 'txt')"
        )

        database.subtractV27Additions()
        database.close()
        rewind()
    }

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V26_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V26 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V26_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_26_27,
                ListeaDatabase.MIGRATION_27_28,
                ListeaDatabase.MIGRATION_28_29,
                ListeaDatabase.MIGRATION_29_30,
                ListeaDatabase.MIGRATION_30_31,
                ListeaDatabase.MIGRATION_31_32
            )
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun bootstrap(database: ListeaDatabase, gateWasOn: Boolean, url: String): Boolean =
        runBlocking {
            database.listsDao().applyWebhookAddressBootstrap(
                name = WEBHOOK_ADDRESS_BACKFILL,
                url = url,
                gateWasOn = gateWasOn
            )
        }

    // --- reading the fixture and the result without entity mapping --------------------------------

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
        count(database, "PRAGMA user_version")

    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V26_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun columnsOf(table: String): List<String> =
        fixture().use { database ->
            database.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private fun tables(): List<String> =
        fixture().use { database ->
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                null
            ).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(0)) }
            }
        }

    private companion object {
        const val V26_DB = "v26-fixture.db"
        const val V26 = 26
        const val CURRENT = 32
    }
}
