package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the migration toolchain works before any v0.2 migration is written against it.
 *
 * Fixtures are raw SQL rather than a helper-built database because schemas 1..11 were never
 * exported and no bundle for them exists — raw fixtures aimed at the data-transforming migrations
 * are what v0.2 relies on, so the toolchain proof uses one. Opening with [Room] is what runs the
 * migration *and* validates the result against the entities, so a migration whose CREATE TABLE
 * disagrees with the schema fails here rather than on a device.
 *
 * `MigrationTestHelper` is deliberately not used: in Room 2.8.2 its framework-backed path hands
 * `SupportSQLiteDriver` an absolute path where the driver expects the bare database name, and
 * throws before reaching any migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationSmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The schema of a database built from the entities, rather than migrated to.
     *
     * Room checks the other tests' migrations *against* this, so a renamed column or a changed
     * default fails here first and in the most readable way: the value below is what the entities
     * actually say, not what a migration remembers writing.
     */
    @Test
    fun `the current schema declares the template a List is judged under`() {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, SCHEMA_DB).build()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
            "lastDissolvedReason) " +
                "VALUES (1, 'Trip', 1000, 0, '', 'dissolved')"
        )
        database.openHelper.writableDatabase
            .query(
                "SELECT taskReviewTemplateId, taskReviewTemplateVersion FROM lists WHERE id = 1"
            ).use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("builtin.media.rapid-review", row.getString(0))
                assertEquals(1, row.getInt(1))
            }
        database.close()
    }

    @Test
    fun `a database created from the entities round-trips`() {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, FRESH_DB).build()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl, " +
            "lastDissolvedReason) " +
                "VALUES (1, 'Trip', 1000, 0, '', 'dissolved')"
        )
        database.openHelper.writableDatabase
            .query("SELECT title FROM lists WHERE id = 1").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("Trip", row.getString(0))
            }
        database.close()
    }

    @Test
    fun `MIGRATION_11_12 folds custom1 and custom2 into a customActions set`() {
        writeV11Fixture()

        // Both steps, because a v11 database now has two to walk before it is current — and the
        // version the app opens at is what decides how many are needed, not what this test is
        // about. The assertions below survive V13 unchanged: it carries every decision column
        // across and rebuilds only the table they sit on.
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V11_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_11_12,
                ListeaDatabase.MIGRATION_12_13,
                ListeaDatabase.MIGRATION_13_14,
                ListeaDatabase.MIGRATION_14_15,
                ListeaDatabase.MIGRATION_15_16,
                ListeaDatabase.MIGRATION_16_17,
                ListeaDatabase.MIGRATION_17_18,
                ListeaDatabase.MIGRATION_18_19,
                ListeaDatabase.MIGRATION_19_20
            )
            .build()

        database.openHelper.writableDatabase
            .query("SELECT customActions, publicId FROM list_items WHERE id = 1").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("custom1,custom2", row.getString(0))
                assertEquals("LISTITEM-PUBLIC-ID", row.getString(1))
            }
        database.openHelper.writableDatabase
            .query("SELECT customActions FROM file_review_state WHERE id = 1").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("custom2", row.getString(0))
            }
        database.close()
    }

    /**
     * A v11 database with both decision-carrying tables populated, in the shape the app shipped
     * before `custom1` and `custom2` were folded into one column.
     */
    private fun writeV11Fixture() {
        val file = context.getDatabasePath(V11_DB)
        file.parentFile?.mkdirs()

        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS lists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, " +
                "webhookEnabled INTEGER NOT NULL, webhookUrl TEXT NOT NULL, " +
                "lastDeliveryAt INTEGER, lastDeliveryStatus TEXT, lastDeliveryCode INTEGER, " +
                "lastDeliveryError TEXT, sourceRootUri TEXT, sourceRelativePath TEXT, " +
                "reviewCurrentItemId INTEGER)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS list_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "publicId TEXT NOT NULL DEFAULT '', listId INTEGER NOT NULL, " +
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
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "publicId TEXT NOT NULL DEFAULT '', rootUri TEXT NOT NULL, " +
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
            "CREATE TABLE IF NOT EXISTS webhook_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                "listTitle TEXT NOT NULL, itemCount INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "at INTEGER NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL)"
        )

        database.execSQL(
            "INSERT INTO lists (id, title, createdAt, webhookEnabled, webhookUrl) " +
                "VALUES (1, 'Trip', 1000, 0, '')"
        )
        database.execSQL(
            "INSERT INTO list_items (id, publicId, listId, title, isCompleted, sortOrder, " +
                "createdAt, sourceMissing, isFavorite, custom1, custom2) " +
                "VALUES (1, 'LISTITEM-PUBLIC-ID', 1, 'a.jpg', 1, 0, 1000, 0, 1, 1, 1)"
        )
        database.execSQL(
            "INSERT INTO file_review_state (id, publicId, rootUri, relativePath, " +
                "isCompleted, isFavorite, custom1, custom2, sourceMissing, updatedAt) " +
                "VALUES (1, 'FILESTATE-PUBLIC-ID', 'content://root', 'a.jpg', 0, 1, 0, 1, 0, 1000)"
        )

        database.version = 11
        database.close()
    }

    private companion object {
        const val FRESH_DB = "migration-smoke-fresh.db"
        const val SCHEMA_DB = "migration-smoke-schema.db"
        const val V11_DB = "migration-smoke-v11.db"
    }
}
