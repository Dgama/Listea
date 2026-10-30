package com.example.listea.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.listea.EVENT_LIST_COMPLETED
import com.example.listea.ExportPayload
import com.example.listea.TemplateResolver
import com.example.listea.buildWebhookPayload
import com.example.listea.exportTaskViews
import kotlinx.coroutines.test.runTest
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
 * The step that turns one decision per Task into one part per Task.
 *
 * **The fixture is written as schema 32, not as today's entities.** That is the whole point of this
 * test: `subtractV33Additions` builds a v32 *from* the current schema, which asks the entities what
 * v32 looked like. A step whose job is to reinterpret every stored judgment is the last place to
 * trust that shortcut — if the migration and the entity disagree about what a Prompt Task is, a
 * fixture derived from the entity would agree with the migration and hide it. So the tables below
 * are created by executing `32.json`'s own `createSql`, and the rows are the old columns, named
 * explicitly. Opening with Room afterwards is the schema validation.
 *
 * The fixture holds one of each origin the migration has to tell apart — a manual media Task, a
 * folder-imported Task, and a Package Prompt — plus the rows that hang off them: a payload, a
 * Resource use, an Asset, a mark, an Add-on answer, a submission with its answers, and a dissolved
 * history. The assertions are about what *survives*: every Task keeps its identity and its decision,
 * every body keeps its bytes and its revision, and nothing gains a part key it did not earn.
 *
 * The last two tests are the claims a receiver and a user would notice: the List's page list is
 * still one question with its files, and the v5 body is still version 5 with the same content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration32To33Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: ListeaDatabase

    @Before
    fun writeFixture() {
        context.deleteDatabase(V32_DB)
        context.deleteDatabase(FRESH_DB)
        createV32()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    // --- the fixture and the shape ----------------------------------------------------------------

    @Test
    fun `the fixture is really a v32 database`() {
        tables().let {
            assertFalse(it.contains("task_parts"))
            assertFalse(it.contains("task_part_payloads"))
            assertFalse(it.contains("package_compositions"))
            assertTrue(it.contains("task_payloads"))
            assertTrue(it.contains("package_prompt_tasks"))
        }
        assertFalse(columnsOf("assets").contains("partKey"))
        assertEquals(V32, rawDatabase().use { it.version })
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

        // Columns and indexes, compared as Room compares them. A column's DEFAULT is deliberately
        // not part of the signature: `ALTER TABLE ADD COLUMN` must supply one for `completionSource`
        // and `composition`, the entity does not declare one, and Room has never treated that as a
        // mismatch. Every column that matters here differs by existing or not.
        assertEquals("tables", created.tables, migrated.tables)
        assertEquals("columns", created.columns, migrated.columns)
        assertEquals("indexes", created.indexes, migrated.indexes)
    }

    // --- one part per Task, carrying what made it -----------------------------------------------

    @Test
    fun `every Task gains exactly one part`() {
        open()

        assertEquals(rawInt("SELECT COUNT(*) FROM tasks"), rawInt("SELECT COUNT(*) FROM task_parts"))
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM (SELECT taskId FROM task_parts " +
                    "GROUP BY taskId HAVING COUNT(*) != 1)"
            )
        )
    }

    @Test
    fun `a legacy Task's part is its own key and its own origin`() {
        open()

        // The manual task came from neither an Importer nor a Package, so it says `legacy` and is
        // named after itself; the folder task remembers that a folder made it; and the Package task
        // takes the Prompt key it already had, which is the identity the Package reconciliation
        // will look for next round.
        assertEquals(
            listOf("legacy:task-media", "legacy", ""),
            part("task-media")
        )
        assertEquals(
            listOf("legacy:task-folder", "folder", ""),
            part("task-folder")
        )
        assertEquals(
            listOf("alice", "package", "alice"),
            part("task-package")
        )
        assertEquals(0, rawInt("SELECT position FROM task_parts WHERE taskId = 'task-package'"))
    }

    // --- the rows that named the Task now name the part ------------------------------------------

    @Test
    fun `every Asset keeps its file and gains its Task's part`() {
        open()

        assertEquals(3, rawInt("SELECT COUNT(*) FROM assets"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM assets WHERE partKey IS NULL"))
        // "names an existing part of the same Task", not merely a non-null string.
        assertEquals(
            3,
            rawInt(
                "SELECT COUNT(*) FROM assets a JOIN task_parts p " +
                    "ON p.taskId = a.taskId AND p.partKey = a.partKey"
            )
        )
        assertEquals(
            "alice",
            rawText("SELECT partKey FROM assets WHERE id = 'asset-package'")
        )
        assertEquals(
            "legacy:task-media",
            rawText("SELECT partKey FROM assets WHERE id = 'asset-media'")
        )
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_assets_taskId_rootUri_relativePath'"
            )
        )
        assertEquals(
            1,
            rawInt(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_assets_taskId_partKey_rootUri_relativePath'"
            )
        )
    }

    @Test
    fun `the payload moves to the part and no byte or revision is lost`() {
        open()

        assertFalse(tables().contains("task_payloads"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_payloads"))
        assertEquals(
            listOf("alice", "profile", "1", """{"stem":"Who?"}""", "3", "150"),
            rawRow(
                "SELECT partKey, kind, schemaVersion, payload, revision, updatedAt " +
                    "FROM task_part_payloads WHERE taskId = 'task-package'"
            )
        )
        // The revision is also what the part's state reports, so a body that moved under an answer
        // is visible on the part the answer belongs to.
        assertEquals(3, rawInt("SELECT revision FROM task_part_state WHERE taskId = 'task-package'"))
        assertNull(
            rawNullableInt("SELECT revision FROM task_part_state WHERE taskId = 'task-media'")
        )
    }

    @Test
    fun `every part has a state row and none invents an answer`() {
        open()

        assertEquals(3, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state WHERE response != ''"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state WHERE answeredAt IS NOT NULL"))
    }

    @Test
    fun `a Resource use moves with its part`() {
        open()

        assertFalse(tables().contains("task_resources"))
        assertEquals(
            listOf("alice", "r-photo", "1"),
            rawRow(
                "SELECT partKey, resourceId, required FROM task_part_resources " +
                    "WHERE taskId = 'task-package'"
            )
        )
    }

    @Test
    fun `legacy submissions stay Task-level rather than guessing a part`() {
        open()

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertNull(rawNullableText("SELECT partKey FROM review_events WHERE id = 'event-1'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE partKey IS NOT NULL"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_event_answers"))
        assertNull(rawNullableText("SELECT fieldKey FROM review_event_answers"))
        assertEquals(
            "asset-media",
            rawText("SELECT assetId FROM review_event_answers")
        )
    }

    @Test
    fun `judgments that never named a part are untouched`() {
        open()

        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = 'task-media'"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = 'task-folder'"))
        assertEquals(
            0,
            rawInt("SELECT COUNT(*) FROM task_state WHERE completionSource != ''")
        )
        assertEquals(
            listOf("happy", "builtin.media.rapid-review", "1"),
            rawRow(
                "SELECT value, authoredAddOnId, authoredAddOnVersion " +
                    "FROM task_review_answers WHERE taskId = 'task-media'"
            )
        )
        assertEquals(
            listOf("happy", "Happy", "fp-doc"),
            rawRow(
                "SELECT value, exactText, documentFingerprint FROM task_asset_marks " +
                    "WHERE assetId = 'asset-media'"
            )
        )
        assertEquals(
            listOf("tag1", "1"),
            rawRow(
                "SELECT selections, isFavorite FROM task_asset_state " +
                    "WHERE assetId = 'asset-media'"
            )
        )
    }

    // --- the records that describe the Task's makeup --------------------------------------------

    @Test
    fun `a Prompt Task becomes a prompt-keyed composition`() {
        open()

        assertFalse(tables().contains("package_prompt_tasks"))
        assertEquals(
            listOf("task-package", "s-package", "dating-demo", "prompt:alice", "1"),
            rawRow(
                "SELECT taskId, sourceId, packageKey, compositionKey, present " +
                    "FROM package_compositions"
            )
        )
        assertEquals(
            listOf("task-package", "prompt:alice", "alice", "1"),
            rawRow(
                "SELECT taskId, compositionKey, promptKey, present " +
                    "FROM package_composition_prompts"
            )
        )
    }

    @Test
    fun `the Import record and the Todo's composition are unchanged`() {
        open()

        assertEquals(
            listOf("task-folder", "s-folder", "album", "album"),
            rawRow(
                "SELECT taskId, sourceId, groupPath, displayName FROM task_imports " +
                    "WHERE taskId = 'task-folder'"
            )
        )
        assertEquals(
            listOf("task-package", "s-package", "dating-demo/alice", "Alice"),
            rawRow(
                "SELECT taskId, sourceId, groupPath, displayName FROM task_imports " +
                    "WHERE taskId = 'task-package'"
            )
        )
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_imports"))
        assertEquals(
            0,
            rawInt("SELECT COUNT(*) FROM sources WHERE composition != 'prompt'")
        )
    }

    @Test
    fun `a dissolved history keeps its records and gains none`() {
        open()

        assertEquals(1, rawInt("SELECT COUNT(*) FROM dissolved_task_state"))
        assertEquals(
            listOf("""{"v":3}""", "3", "500"),
            rawRow(
                "SELECT records, recordVersion, dissolvedAt FROM dissolved_task_state"
            )
        )
        // Read fail-closed: a v32 history has no part records, so it must not look as though it had
        // an empty list of them — the reader treats '' as *this history predates parts*.
        assertEquals(
            "",
            rawText("SELECT partRecords FROM dissolved_task_state")
        )
    }

    // --- what the user and a receiver see --------------------------------------------------------

    @Test
    fun `the page list is still one question with its files`() {
        open()

        // The page model is Content → Assets → Add-on. Its data is the part, the part's Assets, and
        // the Task's answer; the migration moves where each is stored and changes none of them, so
        // the list a person swipes through is the same length before and after.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = 'task-media'"))
        assertEquals(
            1,
            rawInt(
                "SELECT COUNT(*) FROM assets WHERE taskId = 'task-media' " +
                    "AND partKey = 'legacy:task-media'"
            )
        )
        assertEquals(
            1,
            rawInt("SELECT COUNT(*) FROM task_review_answers WHERE taskId = 'task-media'")
        )
    }

    @Test
    fun `a v5 body is still a v5 body with the same content`() = runTest {
        open()
        val dao = database.listsDao()
        val rows = dao.getItems(1)
        val taskIds = rows.map { it.taskId }
        val tasks = dao.getPayloadTasks(taskIds).associateBy { it.taskId }
        val assetsByTask = dao.getPayloadAssets(taskIds).groupBy { it.taskId }
        // The v5 reader, exactly as the exporter asked for it before v6 existed: one envelope per
        // Task, no parts. A body built from the migrated rows must be the body the same rows made
        // in schema 32.
        val promptsByTask = dao.getPayloadPrompts(taskIds)
            .groupBy { it.taskId }
            .mapValues { (_, parts) -> parts.first() }

        val body = buildWebhookPayload(
            ExportPayload(
                event = EVENT_LIST_COMPLETED,
                listId = 1,
                listTitle = "Trip",
                completedAt = null,
                sourceId = null,
                sourceTitle = null,
                at = 1000,
                tasks = exportTaskViews(
                    rows = rows,
                    tasks = tasks,
                    assetsByTask = assetsByTask,
                    resolver = TemplateResolver.Empty,
                    promptsByTask = promptsByTask
                )
            )
        )

        assertTrue(body, body.startsWith("""{"version":5,"event":"list.completed","""))
        // The Prompt's identity came through the part, unchanged: kind, schema, revision, key.
        assertTrue(
            body,
            body.contains(
                """"content":{"kind":"profile","schema":1,"revision":3,"promptKey":"alice"}"""
            )
        )
        // And the v5 grammar is still the v5 grammar: one `content`, no `parts`.
        assertFalse(body, body.contains("\"parts\""))
    }

    // --- fixture ----------------------------------------------------------------------------------

    /**
     * The v32 database, created by the exported schema's own statements.
     *
     * Every entity's `createSql` is executed with the table name substituted, then its indexes. The
     * rows are then written with old column names only — no column this step introduces appears
     * here, which is what makes `the fixture is really a v32 database` a statement about the file
     * rather than about the helper.
     */
    private fun createV32() {
        val file = context.getDatabasePath(V32_DB)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = JSONObject(schemaJson("32.json"))
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }

        raw.execSQL(
            "INSERT INTO lists (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
                "exportKind, lastDissolvedReason) VALUES " +
                "(1, 'Trip', 1000, NULL, 0, '', 'none', 'dissolved')"
        )
        raw.execSQL(
            "INSERT INTO tasks (id, createdAt, publicId) VALUES " +
                "('task-media', 10, 'pub-media'), " +
                "('task-folder', 11, 'pub-folder'), " +
                "('task-package', 12, 'pub-package')"
        )
        raw.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES ('task-media', 1, 0, '', 100), ('task-folder', 0, 0, '', 100), " +
                "('task-package', 0, 0, '', 100)"
        )
        raw.execSQL(
            "INSERT INTO list_items (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
                "createdAt, sourceMissing, isFavorite, customActions) VALUES " +
                "(1, 'item-media', 1, 'task-media', 'a.jpg', 1, 0, 1000, 0, 0, ''), " +
                "(2, 'item-folder', 1, 'task-folder', 'album', 0, 1, 1001, 0, 0, ''), " +
                "(3, 'item-package', 1, 'task-package', 'Alice', 0, 2, 1002, 0, 0, '')"
        )
        raw.execSQL(
            "INSERT INTO sources (id, rootUri, relativePath, displayName, importerKind, " +
                "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
                "followUpstream, assetControls, addOn, exportKind) VALUES " +
                "('s-folder', 'content://root', '', 'Photos', 'folder-as-task', 1, 1000, 1, 0, " +
                "'keep', 0, '', '', 'none'), " +
                "('s-package', 'content://root', 'dating', 'Dating', 'package', 1, 1000, 1, 0, " +
                "'keep', 0, '', '', 'none')"
        )
        raw.execSQL(
            "INSERT INTO task_imports (taskId, sourceId, groupPath, displayName, importedAt, " +
                "lastSeenAt, assetControls, addOn) VALUES " +
                "('task-folder', 's-folder', 'album', 'album', 1000, 1000, '', ''), " +
                "('task-package', 's-package', 'dating-demo/alice', 'Alice', 1000, 150, '', '')"
        )
        raw.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "sourceUri, sizeBytes, lastModified, publicId) VALUES " +
                "('asset-media', 'task-media', 0, 'content://root', 'photos/a.jpg', 0, " +
                "'content://root/photos/a.jpg', 1000, 100, 'pub-asset-media'), " +
                "('asset-folder', 'task-folder', 0, 'content://root', 'album/one.jpg', 0, " +
                "'content://root/album/one.jpg', 1000, 110, 'pub-asset-folder'), " +
                "('asset-package', 'task-package', 0, 'content://root', " +
                "'dating/media/alice.jpg', 0, 'content://root/dating/media/alice.jpg', 2000, 150, " +
                "'pub-asset-package')"
        )
        raw.execSQL(
            "INSERT INTO task_asset_state (assetId, isFavorite, customActions, selections, " +
                "updatedAt) VALUES ('asset-media', 1, '', 'tag1', 100)"
        )
        raw.execSQL(
            "INSERT INTO task_asset_marks (id, assetId, toolId, authoredTemplateId, " +
                "authoredTemplateVersion, kind, value, anchorVersion, documentFingerprint, " +
                "pageIndex, charStart, charEnd, charParser, exactText, prefix, suffix, " +
                "createdAt, updatedAt) VALUES " +
                "('mark-1', 'asset-media', 'builtin.media.tags', 'builtin.media.rapid-review', 1, " +
                "'tag', 'happy', 1, 'fp-doc', 0, 0, 5, 'text', 'Happy', '', '', 200, 200)"
        )
        raw.execSQL(
            "INSERT INTO task_review_answers (taskId, questionId, kind, value, authoredAddOnId, " +
                "authoredAddOnVersion, createdAt, updatedAt) VALUES " +
                "('task-media', 'mood', 'tag', 'happy', 'builtin.media.rapid-review', 1, 200, 200)"
        )
        raw.execSQL(
            "INSERT INTO review_events (id, taskId, at, kind, templateId, templateVersion, " +
                "response) VALUES " +
                "('event-1', 'task-media', 210, 'primary', 'builtin.media.rapid-review', 1, " +
                "'{\"type\":\"media\",\"choice\":\"like\"}')"
        )
        raw.execSQL(
            "INSERT INTO review_event_answers (reviewEventId, ordinal, assetId, type, value) " +
                "VALUES ('event-1', 0, 'asset-media', 'tag', 'happy')"
        )
        raw.execSQL(
            "INSERT INTO package_checkpoints (sourceId, packageKey, rootUri, relativePath, " +
                "protocol, title, revision, fingerprint, lastSeenAt) VALUES " +
                "('s-package', 'dating-demo', 'content://root', 'dating', 'listea.package/1', " +
                "'Dating demo', 3, 'fp-1', 150)"
        )
        raw.execSQL(
            "INSERT INTO package_prompt_tasks (taskId, sourceId, packageKey, promptKey, present) " +
                "VALUES ('task-package', 's-package', 'dating-demo', 'alice', 1)"
        )
        raw.execSQL(
            "INSERT INTO task_payloads (taskId, kind, schemaVersion, payload, revision, " +
                "updatedAt) VALUES ('task-package', 'profile', 1, '{\"stem\":\"Who?\"}', 3, 150)"
        )
        raw.execSQL(
            "INSERT INTO package_resources (id, sourceId, packageKey, resourceKey, kind, rootUri, " +
                "relativePath, missing, updatedAt) VALUES " +
                "('r-photo', 's-package', 'dating-demo', 'photo', 'image', 'content://root', " +
                "'dating/media/alice.jpg', 0, 150)"
        )
        raw.execSQL(
            "INSERT INTO task_resources (taskId, resourceId, required) " +
                "VALUES ('task-package', 'r-photo', 1)"
        )
        raw.execSQL(
            "INSERT INTO dissolved_task_state (sourceId, sourceName, rootUri, sourcePath, " +
                "groupPath, importerKind, isCompleted, recordVersion, records, dissolvedAt) " +
                "VALUES ('s-folder', 'Photos', 'content://root', '', 'gone/album', " +
                "'folder-as-task', 1, 3, '{\"v\":3}', 500)"
        )

        raw.version = V32
        raw.close()
    }

    private fun schemaJson(name: String): String =
        java.io.File("schemas/com.example.listea.data.ListeaDatabase/$name").readText()

    // --- opening and reading ---------------------------------------------------------------------

    private fun open() {
        database = Room.databaseBuilder(context, ListeaDatabase::class.java, V32_DB)
            .addMigrations(
                ListeaDatabase.MIGRATION_32_33,
                ListeaDatabase.MIGRATION_33_34
            )
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
    }

    private fun rawDatabase(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(V32_DB).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

    private fun tables(): List<String> =
        rawDatabase().use { raw ->
            raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                null
            ).use { row -> buildList { while (row.moveToNext()) add(row.getString(0)) } }
        }

    private fun columnsOf(table: String): List<String> =
        rawDatabase().use { raw ->
            raw.rawQuery("PRAGMA table_info($table)", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(1)) }
            }
        }

    private fun part(taskId: String): List<String> = rawRow(
        "SELECT partKey, origin, COALESCE(promptKey, '') FROM task_parts WHERE taskId = '$taskId'"
    )

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getInt(0)
        }

    private fun rawNullableInt(sql: String): Int? =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            if (row.isNull(0)) null else row.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private fun rawNullableText(sql: String): String? =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            if (row.isNull(0)) null else row.getString(0)
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

        override fun toString(): String = "$name $type notNull=$notNull pk=$primaryKey"
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

        override fun toString(): String = "unique=$unique on $table($columns)"
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
        const val V32_DB = "v32-fixture.db"
        const val FRESH_DB = "v33-fresh.db"
        const val V32 = 32
    }
}
