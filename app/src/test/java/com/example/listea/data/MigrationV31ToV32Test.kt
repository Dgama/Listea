package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
 * The step that gives Packages a home without changing anything already stored.
 *
 * Five new tables and no backfill: a pre-Package database has no Prompt, so there is nothing to
 * derive, and every existing Task keeps its identity, its judgments, and its zero `task_payloads`
 * rows. The interesting assertions are therefore the shape, the surviving rows, and the cascades —
 * a Prompt payload follows its Task, a Resource link follows both, and a checkpoint belongs to the
 * Todo.
 *
 * The fixture is built at the version the entities declare and [subtractV32Additions] takes this
 * step's own tables back off. `31.json` must not gain them, and `32.json` is the shape Room
 * validates every upgrade against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV31ToV32Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun writeFixture() {
        context.deleteDatabase(V31_DB)
        Room.databaseBuilder(context, ListeaDatabase::class.java, V31_DB)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .let { database ->
                database.exec(
                    "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                        "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                        "followUpstream, assetControls, addOn, exportKind) VALUES " +
                        "('todo-1', 'content://tree/root', 'dating', 'Dating', 'folder-as-task', 1, 1, " +
                        "1, 0, 'keep', 0, '', '', 'none')"
                )
                database.exec(
                    "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-1', 1, 'task-public-1')"
                )
                database.exec(
                    "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                        "lastSeenAt, assetControls, addOn, taskReview) VALUES " +
                        "('t-1', 'todo-1', 'dating-demo/alice', 'Alice', 1, 2, '', '', '')"
                )
                database.subtractV32Additions()
                database.close()
            }
        rewind()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v31 database`() {
        val tables = tables()
        assertFalse(tables.contains("task_payloads"))
        assertFalse(tables.contains("package_checkpoints"))
        assertFalse(tables.contains("package_prompt_tasks"))
        assertFalse(tables.contains("package_resources"))
        assertFalse(tables.contains("task_resources"))
        assertEquals(V31, fixture().version)
    }

    @Test
    fun `the step takes a v31 database to the current version`() {
        val database = migrate()

        assertEquals(CURRENT, version(database))
        val tables = tables()
        for (table in listOf(
            "task_payloads",
            "package_checkpoints",
            "package_prompt_tasks",
            "package_resources",
            "task_resources"
        )) {
            assertTrue(tables.contains(table))
        }
        assertEquals(
            listOf("taskId", "kind", "schemaVersion", "payload", "revision", "updatedAt"),
            columnsOf(database, "task_payloads")
        )
        assertEquals(
            listOf(
                "sourceId", "packageKey", "rootUri", "relativePath", "protocol", "title",
                "revision", "fingerprint", "lastSeenAt"
            ),
            columnsOf(database, "package_checkpoints")
        )
    }

    @Test
    fun `the exported schema records the addition and leaves the previous version alone`() {
        val v31 = schemaJson("31.json")
        val v32 = schemaJson("32.json")

        assertFalse("v31.json must not gain task_payloads", v31.contains("task_payloads"))
        assertFalse("v31.json must not gain checkpoints", v31.contains("package_checkpoints"))
        assertTrue("v32.json must carry the Prompt payload", v32.contains("task_payloads"))
        assertTrue(v32.contains("package_prompt_tasks"))
        assertTrue(v32.contains("ON DELETE CASCADE"))
    }

    // --- additive ---------------------------------------------------------------------------------

    @Test
    fun `existing rows keep their identity and gain no payload`() {
        val database = migrate()

        assertEquals(
            1,
            count(database, "SELECT COUNT(*) FROM tasks WHERE id = 't-1' AND publicId = 'task-public-1'")
        )
        assertEquals(
            1,
            count(database, "SELECT COUNT(*) FROM task_imports WHERE taskId = 't-1'")
        )
        assertEquals(
            0,
            count(database, "SELECT COUNT(*) FROM task_payloads")
        )
    }

    // --- the links --------------------------------------------------------------------------------

    @Test
    fun `a Prompt payload follows its Task`() {
        val database = migrate()
        database.exec(
            "INSERT INTO task_payloads (taskId, kind, schemaVersion, payload, revision, updatedAt) " +
                "VALUES ('t-1', 'profile', 1, '{}', 1, 10)"
        )
        assertEquals(1, count(database, "SELECT COUNT(*) FROM task_payloads WHERE taskId = 't-1'"))

        database.exec("DELETE FROM tasks WHERE id = 't-1'")

        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_payloads"))
    }

    @Test
    fun `a Resource link follows both ends`() {
        val database = migrate()
        database.exec(
            "INSERT INTO package_resources (id, sourceId, packageKey, resourceKey, kind, rootUri, " +
                "relativePath, missing, updatedAt) VALUES " +
                "('r-1', 'todo-1', 'dating-demo', 'alice-photo', 'image', 'content://tree/root', " +
                "'dating/media/alice.jpg', 0, 10)"
        )
        database.exec(
            "INSERT INTO task_resources (taskId, resourceId, required) VALUES ('t-1', 'r-1', 1)"
        )
        assertEquals(1, count(database, "SELECT COUNT(*) FROM task_resources"))
        assertEquals(
            1,
            count(database, "SELECT required FROM task_resources WHERE taskId = 't-1'")
        )

        database.exec("DELETE FROM tasks WHERE id = 't-1'")
        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_resources"))

        database.exec(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-2', 2, 'task-public-2')"
        )
        database.exec(
            "INSERT INTO task_resources (taskId, resourceId, required) VALUES ('t-2', 'r-1', 0)"
        )
        database.exec("DELETE FROM package_resources WHERE id = 'r-1'")
        assertEquals(0, count(database, "SELECT COUNT(*) FROM task_resources"))
    }

    @Test
    fun `a checkpoint belongs to the Todo`() {
        val database = migrate()
        database.exec(
            "INSERT INTO package_checkpoints (sourceId, packageKey, rootUri, relativePath, " +
                "protocol, title, revision, fingerprint, lastSeenAt) VALUES " +
                "('todo-1', 'dating-demo', 'content://tree/root', 'dating', " +
                "'listea.package/1', 'Dating demo', 1, 'abc', 10)"
        )
        assertEquals(
            1,
            count(database, "SELECT COUNT(*) FROM package_checkpoints WHERE sourceId = 'todo-1'")
        )

        database.exec("DELETE FROM tasks WHERE id = 't-1'")
        assertEquals(
            "a Prompt going away is not a Todo going away",
            1,
            count(database, "SELECT COUNT(*) FROM package_checkpoints")
        )
    }

    @Test
    fun `the Prompt key is unique per Todo and Package`() {
        val database = migrate()
        database.exec(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-2', 2, 'task-public-2')"
        )
        database.exec(
            "INSERT INTO package_prompt_tasks (taskId, sourceId, packageKey, promptKey, present) " +
                "VALUES ('t-1', 'todo-1', 'dating-demo', 'alice', 1)"
        )
        try {
            database.exec(
                "INSERT INTO package_prompt_tasks (taskId, sourceId, packageKey, promptKey, present) " +
                    "VALUES ('t-2', 'todo-1', 'dating-demo', 'alice', 1)"
            )
            throw AssertionError("a duplicate Prompt key was accepted")
        } catch (expected: SQLiteConstraintException) {
            // The unique index is the reconciliation identity.
        }
        assertEquals(1, count(database, "SELECT COUNT(*) FROM package_prompt_tasks"))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun rewind() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V31_DB).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = V31 }
    }

    private fun migrate(): ListeaDatabase {
        val database = Room.databaseBuilder(context, ListeaDatabase::class.java, V31_DB)
            .addMigrations(ListeaDatabase.MIGRATION_31_32)
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

    private fun count(database: ListeaDatabase, sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun version(database: ListeaDatabase): Int =
        count(database, "PRAGMA user_version")

    private fun fixture(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V31_DB).path,
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
        const val V31_DB = "v31-fixture.db"
        const val V31 = 31
        const val CURRENT = 32
    }
}
