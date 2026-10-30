package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step that lets a part carry a condition, its own judgment, and a stale answer.
 *
 * **The fixture is written as schema 33, not as today's entities.** The tables come from `33.json`'s
 * own `createSql`, so this test is a statement about the file a v0.8.4 build left on a device: a
 * condition and a judgment column must not exist there, and the migration must not assume they do.
 *
 * The three columns are additive, so the assertions are about nothing moving. Every part keeps its
 * key, position, origin, requiredness and availability; every answer keeps its bytes, its timestamp
 * and its revision; and the new columns read as *no condition*, *no judgment of its own* and *not
 * stale* — the exact values that make an upgraded Task behave as v0.8.4 did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration33To34Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: ListeaDatabase

    @Before
    fun writeFixture() {
        context.deleteDatabase(V33_DB)
        context.deleteDatabase(FRESH_DB)
        createV33()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v33 database`() {
        assertFalse(columnsOf("task_parts").contains("condition"))
        assertFalse(columnsOf("task_parts").contains("partJudgment"))
        assertFalse(columnsOf("task_part_state").contains("stale"))
        assertEquals(V33, rawDatabase().use { it.version })
    }

    @Test
    fun `the exported schema records the addition and leaves the previous version alone`() {
        val v33 = schemaJson("33.json")
        val v34 = schemaJson("34.json")

        assertFalse("33.json must not gain a condition", v33.contains("\"columnName\": \"condition\""))
        assertFalse("33.json must not gain a judgment", v33.contains("partJudgment"))
        assertFalse("33.json must not gain staleness", v33.contains("\"columnName\": \"stale\""))
        assertTrue(v34.contains("\"columnName\": \"condition\""))
        assertTrue(v34.contains("\"columnName\": \"partJudgment\""))
        assertTrue(v34.contains("\"columnName\": \"stale\""))
    }

    @Test
    fun `the migrated schema is the one a fresh install creates`() {
        open()
        val migrated = schemaOf(database.openHelper.writableDatabase)

        database.close()
        val fresh = Room.databaseBuilder(context, ListeaDatabase::class.java, FRESH_DB)
            .allowMainThreadQueries()
            .build()
        val created = schemaOf(fresh.openHelper.writableDatabase).also { fresh.close() }

        assertEquals("tables", created.tables, migrated.tables)
        assertEquals("columns", created.columns, migrated.columns)
        assertEquals("indexes", created.indexes, migrated.indexes)
    }

    // --- nothing existing moves ------------------------------------------------------------------

    @Test
    fun `the step takes a v33 database to the current version`() {
        open()

        assertEquals(CURRENT, version())
        assertEquals(
            listOf("gate", "menu"),
            columnRows("SELECT partKey FROM task_parts WHERE taskId = 't-1' ORDER BY position")
        )
    }

    @Test
    fun `every part keeps the facts it had and gains no branch or judgment`() {
        open()

        assertEquals(
            listOf("gate", "0", "package", "gate", "1", "1", ""),
            rawRow(
                "SELECT partKey, position, origin, promptKey, required, present, " +
                    "COALESCE(availability, '') FROM task_parts " +
                    "WHERE taskId = 't-1' AND partKey = 'gate'"
            )
        )
        assertEquals(
            "unsupported-viewer",
            rawText(
                "SELECT availability FROM task_parts " +
                    "WHERE taskId = 't-1' AND partKey = 'menu'"
            )
        )
        assertEquals(
            0,
            count(
                "SELECT COUNT(*) FROM task_parts " +
                    "WHERE condition IS NOT NULL OR partJudgment IS NOT NULL"
            )
        )
    }

    @Test
    fun `every answer keeps its bytes and reads as not stale`() {
        open()

        assertEquals(
            listOf("""{"type":"structured","completed":true}""", "20", "1", "0"),
            rawRow(
                "SELECT response, answeredAt, revision, stale FROM task_part_state " +
                    "WHERE taskId = 't-1' AND partKey = 'gate'"
            )
        )
        assertEquals(
            listOf("", "", "", "0"),
            rawRow(
                "SELECT response, COALESCE(answeredAt, ''), COALESCE(revision, ''), stale " +
                    "FROM task_part_state WHERE taskId = 't-1' AND partKey = 'menu'"
            )
        )
    }

    @Test
    fun `the new columns are writable and the stale flag defaults to zero`() {
        open()
        val writable = database.openHelper.writableDatabase

        writable.execSQL(
            "UPDATE task_parts SET condition = '{\"prompt\":\"gate\",\"field\":\"ate\"," +
                "\"equals\":\"yes\"}', partJudgment = 'builtin.media.part\t1' " +
                "WHERE taskId = 't-1' AND partKey = 'menu'"
        )
        writable.execSQL(
            "UPDATE task_part_state SET stale = 1 WHERE taskId = 't-1' AND partKey = 'menu'"
        )

        assertEquals(
            listOf(
                """{"prompt":"gate","field":"ate","equals":"yes"}""",
                "builtin.media.part\t1"
            ),
            rawRow(
                "SELECT condition, partJudgment FROM task_parts " +
                    "WHERE taskId = 't-1' AND partKey = 'menu'"
            )
        )
        assertEquals(
            1,
            count(
                "SELECT stale FROM task_part_state " +
                    "WHERE taskId = 't-1' AND partKey = 'menu'"
            )
        )
    }

    // --- fixture ---------------------------------------------------------------------------------

    /**
     * The v33 database, created by the exported schema's own statements.
     *
     * Every entity's `createSql` is executed with the table name substituted, then its indexes, so
     * the file Room opens below is validated against every table the v0.8.4 build created — not only
     * the two this step touches. The rows name no column this step introduces, which is what makes
     * `the fixture is really a v33 database` a statement about the file.
     */
    private fun createV33() {
        val file = context.getDatabasePath(V33_DB)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = JSONObject(schemaJson("33.json"))
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                raw.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
        }

        raw.execSQL(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-1', 10, 'pub-1')"
        )
        raw.execSQL(
            "INSERT INTO task_parts (taskId, partKey, position, origin, promptKey, required, " +
                "present, availability, updatedAt) VALUES " +
                "('t-1', 'gate', 0, 'package', 'gate', 1, 1, NULL, 10), " +
                "('t-1', 'menu', 1, 'package', 'menu', 1, 1, 'unsupported-viewer', 10)"
        )
        raw.execSQL(
            "INSERT INTO task_part_state (taskId, partKey, response, answeredAt, revision, " +
                "updatedAt) VALUES " +
                "('t-1', 'gate', '{\"type\":\"structured\",\"completed\":true}', 20, 1, 20), " +
                "('t-1', 'menu', '', NULL, NULL, 10)"
        )

        raw.version = V33
        raw.close()
    }

    private fun schemaJson(name: String): String =
        java.io.File("schemas/com.example.listea.data.ListeaDatabase/$name").readText()

    // --- opening and reading ---------------------------------------------------------------------

    private fun open() {
        database = Room.databaseBuilder(context, ListeaDatabase::class.java, V33_DB)
            .addMigrations(ListeaDatabase.MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
    }

    private fun rawDatabase(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V33_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun columnsOf(table: String): List<String> =
        rawDatabase().use { raw ->
            raw.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private fun version(): Int = count("PRAGMA user_version")

    private fun count(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun columnRows(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { row ->
            buildList { while (row.moveToNext()) add(row.getString(0)) }
        }

    /** One row's columns, in `SELECT` order; a null column reads as the empty string. */
    private fun rawRow(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            List(row.columnCount) { column -> row.getString(column) }
        }

    // --- schema signature ------------------------------------------------------------------------

    private class ColumnSig(
        val name: String,
        val type: String?,
        val notNull: Boolean,
        val primaryKey: Int
    ) {
        override fun equals(other: Any?): Boolean =
            other is ColumnSig && name == other.name && type == other.type &&
                notNull == other.notNull && primaryKey == other.primaryKey

        override fun hashCode(): Int = listOf(name, type, notNull, primaryKey).hashCode()
    }

    private class IndexSig(
        val unique: Boolean,
        val table: String,
        val columns: List<String>
    ) {
        override fun equals(other: Any?): Boolean =
            other is IndexSig && unique == other.unique && table == other.table &&
                columns == other.columns

        override fun hashCode(): Int = listOf(unique, table, columns).hashCode()
    }

    private class Schema(
        val tables: List<String>,
        val columns: Map<String, List<ColumnSig>>,
        val indexes: Map<String, IndexSig>
    )

    private fun schemaOf(db: SupportSQLiteDatabase): Schema {
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table' " +
                "AND name <> 'android_metadata' ORDER BY name"
        ).use { row -> buildList { while (row.moveToNext()) add(row.getString(0)) } }
        val columns = tables.associateWith { table ->
            db.query("PRAGMA table_info(`$table`)").use { row ->
                buildList {
                    while (row.moveToNext()) {
                        add(
                            ColumnSig(
                                name = row.getString(1),
                                type = row.getString(2),
                                notNull = row.getInt(3) != 0,
                                primaryKey = row.getInt(5)
                            )
                        )
                    }
                }.sortedBy { it.name }
            }
        }
        val indexes = buildMap {
            tables.forEach { table ->
                db.query("PRAGMA index_list(`$table`)").use { list ->
                    while (list.moveToNext()) {
                        val name = list.getString(1)
                        if (name.startsWith("sqlite_")) continue
                        val unique = list.getInt(2) != 0
                        val indexColumns = db.query("PRAGMA index_info(`$name`)").use { info ->
                            buildList { while (info.moveToNext()) add(info.getString(2)) }
                        }
                        put(name, IndexSig(unique, table, indexColumns))
                    }
                }
            }
        }
        return Schema(tables, columns, indexes)
    }

    private companion object {
        const val V33_DB = "v33-fixture.db"
        const val FRESH_DB = "v34-fresh.db"
        const val V33 = 33
        const val CURRENT = 34
    }
}
