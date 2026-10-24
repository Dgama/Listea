package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that gives a Task a questionnaire, and gives every existing install nothing new to see.
 *
 * **Three tables and two columns, and no row changes meaning.** The columns default to `''`, which
 * the app reads as *no add-on attached*; a Todo that predates the round therefore shows exactly the
 * pages it showed before and exports exactly the body it exported before. That is the assertion most
 * of this file is about, because it is the one an additive step can still get wrong: a column that
 * arrives `NULL`, or a backfill that decides every Task is owed a page, is invisible in the schema and
 * very visible on a phone.
 *
 * **The two cascades point in opposite directions, and both are deliberate.** An answer belongs to its
 * Task, so deleting the Task deletes the answers. A version belongs to its add-on, so deleting the
 * add-on deletes the versions. An answer does **not** belong to the add-on it was authored under —
 * that is a plain pair of columns rather than a foreign key — so replacing or removing a questionnaire
 * hides answers instead of erasing them, the same rule PDF marks follow one layer down.
 *
 * The fixture is built at the version the entities declare and [subtractV29Additions] takes this
 * step's own additions back off, following the other migration tests. `28.json` and `29.json` are
 * read from disk: the first must not have gained any of this, and the second is the shape Room
 * validates every upgrade against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV28ToV29Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun writeFixture() {
        context.deleteDatabase(V28_DB)
        Room.databaseBuilder(context, ListeaDatabase::class.java, V28_DB)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .let { database ->
                database.exec(
                    "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-1', 1, 'task-public')"
                )
                database.exec(
                    "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                        "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                        "followUpstream, assetControls, exportKind, addOn, webhookUrl, " +
                        "webhookFromDefault) VALUES " +
                        "('s-1', 'content://root', 'cos', 'cos', 'file-as-task', 1, 1, 1, 0, " +
                        "'default', 0, '', 'webhook', '', 'https://example.test/hook', 1)"
                )
                database.exec(
                    "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, " +
                        "importedAt, lastSeenAt, assetControls, addOn) VALUES " +
                        "('t-1', 's-1', '', 'cos', 1, 1, '', '')"
                )
                database.subtractV29Additions()
                database.close()
            }
        rewind()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v28 database`() {
        val tables = tables()
        assertFalse(tables.contains("addons"))
        assertFalse(tables.contains("addon_versions"))
        assertFalse(tables.contains("task_review_answers"))
        assertFalse(columnsOf("sources").contains("addOn"))
        assertFalse(columnsOf("task_imports").contains("addOn"))
        assertEquals(V28, fixture().version)
    }

    @Test
    fun `the step takes a v28 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        val tables = tables()
        assertTrue(tables.contains("addons"))
        assertTrue(tables.contains("addon_versions"))
        assertTrue(tables.contains("task_review_answers"))
        assertEquals(
            listOf("id", "name", "createdAt"),
            columnsOf(database, "addons")
        )
        assertEquals(
            listOf("addOnId", "version", "questions", "questionsVersion", "createdAt"),
            columnsOf(database, "addon_versions")
        )
        assertEquals(
            listOf(
                "taskId", "questionId", "kind", "value", "authoredAddOnId",
                "authoredAddOnVersion", "createdAt", "updatedAt"
            ),
            columnsOf(database, "task_review_answers")
        )
    }

    @Test
    fun `the exported schema records the additions and leaves the previous version alone`() {
        val v28 = schemaJson("28.json")
        val v29 = schemaJson("29.json")

        for (table in listOf("addons", "addon_versions", "task_review_answers")) {
            assertFalse("v28.json must not gain $table", v28.contains(table))
            assertTrue("v29.json must carry $table", v29.contains(table))
        }
        assertFalse(v28.contains("addOn"))
        assertTrue(v29.contains("addOn"))
        assertTrue(v29.contains("ON DELETE CASCADE"))
    }

    // --- what the step leaves alone ---------------------------------------------------------------

    @Test
    fun `an existing Todo and Task read as carrying no add-on`() {
        val database = migrate()

        assertEquals(
            "",
            text(database, "SELECT addOn FROM sources WHERE id = 's-1'")
        )
        assertEquals(
            "",
            text(database, "SELECT addOn FROM task_imports WHERE taskId = 't-1'")
        )
        assertEquals(
            "the Task has no answers until somebody gives it some",
            0,
            count(database, "SELECT COUNT(*) FROM task_review_answers")
        )
        // And the rebuild that removed the column kept the row whole.
        assertEquals(
            "https://example.test/hook",
            text(database, "SELECT webhookUrl FROM sources WHERE id = 's-1'")
        )
        assertEquals(1, count(database, "SELECT webhookFromDefault FROM sources WHERE id = 's-1'"))
        assertEquals(
            "assetControls is still where the Todo's list of file templates lives",
            "",
            text(database, "SELECT assetControls FROM task_imports WHERE taskId = 't-1'")
        )
    }

    // --- the two cascades ---------------------------------------------------------------------------

    @Test
    fun `deleting a Task deletes the answers it kept`() {
        val database = migrate()
        database.exec(
            "INSERT INTO task_review_answers (taskId, questionId, kind, value, " +
                "authoredAddOnId, authoredAddOnVersion, createdAt, updatedAt) VALUES " +
                "('t-1', 'q-rating', 'rating', '4', 'addon-1', 1, 1, 1)"
        )

        database.exec("DELETE FROM tasks WHERE id = 't-1'")

        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_review_answers"))
        // The Todo's bookkeeping went with the Task as it always has.
        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_imports"))
    }

    @Test
    fun `deleting an add-on deletes its versions and leaves answers alone`() {
        val database = migrate()
        database.exec("INSERT INTO addons (id, name, createdAt) VALUES ('addon-1', 'Task review', 1)")
        database.exec(
            "INSERT INTO addon_versions (addOnId, version, questions, questionsVersion, " +
                "createdAt) VALUES ('addon-1', 1, '[]', 1, 1), ('addon-1', 2, '[]', 1, 2)"
        )
        database.exec(
            "INSERT INTO task_review_answers (taskId, questionId, kind, value, " +
                "authoredAddOnId, authoredAddOnVersion, createdAt, updatedAt) VALUES " +
                "('t-1', 'q-rating', 'rating', '4', 'addon-1', 1, 1, 1)"
        )

        database.exec("DELETE FROM addons WHERE id = 'addon-1'")

        assertEquals(0, count(database, "SELECT COUNT(*) FROM addon_versions"))
        assertEquals(
            "the answer is hidden by the missing lineage, never deleted by it",
            1,
            count(database, "SELECT COUNT(*) FROM task_review_answers")
        )
    }

    // --- fixture and reading helpers -------------------------------------------------------------

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V28_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V28 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V28_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_28_29,
                ListeaDatabase.MIGRATION_29_30,
                ListeaDatabase.MIGRATION_30_31
            )
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun schemaJson(name: String): String =
        java.io.File("schemas/com.example.listea.data.ListeaDatabase/$name").readText()

    private fun columnsOf(database: ListeaDatabase, table: String): List<String> =
        database.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { row ->
            buildList { while (row.moveToNext()) add(row.getString(1)) }
        }

    private fun columnsOf(table: String): List<String> =
        fixture().use { database ->
            database.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

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

    private fun version(database: ListeaDatabase): Int =
        count(database, "PRAGMA user_version")

    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V28_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

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
        const val V28_DB = "v28-fixture.db"
        const val V28 = 28
        const val CURRENT = 31
    }
}
