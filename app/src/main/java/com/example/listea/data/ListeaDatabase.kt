package com.example.listea.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TASK_REVIEW_DEFINITION_VERSION
import com.example.listea.domain.buttonsVersionFor
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.Templates
import com.example.listea.domain.assetUri
import com.example.listea.domain.encodeTaskReviewDefinition
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import com.example.listea.domain.encodeValue
import com.example.listea.domain.parse
import com.example.listea.domain.viewerEvents
import java.util.UUID

@Database(
    entities = [
        ListEntity::class,
        ListItemEntity::class,
        ExportRecordEntity::class,
        FileReviewStateEntity::class,
        TaskEntity::class,
        AssetEntity::class,
        TaskStateEntity::class,
        AssetStateEntity::class,
        ReviewEventEntity::class,
        LegacyItemIdEntity::class,
        LegacyManualTaskEntity::class,
        SourceEntity::class,
        TaskImportEntity::class,
        LegacySourceTaskEntity::class,
        LegacyTaskIdEntity::class,
        UpstreamEntity::class,
        DissolvedTaskStateEntity::class,
        TemplateEntity::class,
        TemplateVersionEntity::class,
        SchemaBackfillEntity::class,
        AssetMarkEntity::class,
        AddOnEntity::class,
        AddOnVersionEntity::class,
        TaskReviewAnswerEntity::class,
        ReviewEventAnswerEntity::class,
        PackageCheckpointEntity::class,
        PackageResourceEntity::class,
        TaskPartEntity::class,
        TaskPartPayloadEntity::class,
        TaskPartStateEntity::class,
        TaskPartResourceEntity::class,
        PackageCompositionEntity::class,
        PackageCompositionPromptEntity::class
    ],
    version = 33,
    exportSchema = true
)
abstract class ListeaDatabase : RoomDatabase() {

    abstract fun listsDao(): ListsDao

    abstract fun templateDao(): TemplateDao

    /**
     * The templates' write side, separate from the read one on purpose — see [TemplateWriteDao].
     * Both are on the database rather than one behind the other, because the split is which
     * *caller* may do what, and Room's accessors are the only place that is expressed.
     */
    abstract fun templateWriteDao(): TemplateWriteDao

    /** Add-ons: the read side and the append-only write side, the split templates already use. */
    abstract fun addOnDao(): AddOnDao

    abstract fun addOnWriteDao(): AddOnWriteDao

    companion object {
        /** V3 added the optional webhook action and its last-delivery record to each list. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN webhookEnabled INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN webhookUrl TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryAt INTEGER")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryStatus TEXT")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryCode INTEGER")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryError TEXT")
            }
        }

        /** V3.1 linked lists to a folder subtree and items to their source file. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE lists ADD COLUMN sourceRootUri TEXT")
                connection.execSQL("ALTER TABLE lists ADD COLUMN sourceRelativePath TEXT")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceUri TEXT")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceRelativePath TEXT")
            }
        }

        /** V3.3 lets a re-sync flag an item whose source file has vanished, without deleting it. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN sourceMissing INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** V3.4 remembers where swipe Review left off, by item id. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE lists ADD COLUMN reviewCurrentItemId INTEGER")
            }
        }

        /**
         * V3.5 adds the per-item review actions. Purely additive with a false default, so every
         * existing item survives unchanged and simply carries no actions.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN custom1 INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN custom2 INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * V4.5 keeps the webhook bodies that never got through, so a misconfigured endpoint
         * costs a resend rather than a round of review. A new table only: nothing existing is
         * touched, and a database that has never failed a delivery simply has none of these.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS unsent_webhooks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "listTitle TEXT NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "failedAt INTEGER NOT NULL, " +
                        "reason TEXT NOT NULL)"
                )
            }
        }

        /**
         * V8 moves a review decision off the List row and onto the file it is about.
         *
         * Three steps, in one transaction, and the order matters. Items first learn their own
         * root-relative path — the identity a decision is stored against — computed from the
         * folder their list is linked to. Every decision already recorded then moves across to
         * that identity, so nothing a user has reviewed is lost. Finally the old columns are
         * cleared on source-backed rows, because leaving them populated would leave a second,
         * silently diverging answer to "is this checked" on disk.
         *
         * Manual items are untouched throughout: they have no file, they keep their columns, and
         * the WHERE clauses here all require a source path.
         *
         * Non-overlap means one file can appear in at most one list, so the copy across cannot
         * meet the same file twice. MAX() is used anyway — if a database from some earlier state
         * did hold a duplicate, taking "reviewed" over "not reviewed" loses nothing, whereas
         * failing the migration would lose the whole database.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE list_items ADD COLUMN rootRelativePath TEXT")
                connection.execSQL(
                    "UPDATE list_items SET rootRelativePath = (" +
                        "SELECT CASE WHEN l.sourceRelativePath IS NULL OR l.sourceRelativePath = ''" +
                        " THEN list_items.sourceRelativePath" +
                        " ELSE l.sourceRelativePath || '/' || list_items.sourceRelativePath END" +
                        " FROM lists l WHERE l.id = list_items.listId" +
                        ") WHERE sourceRelativePath IS NOT NULL"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
                        "ON list_items (rootRelativePath)"
                )

                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS file_review_state (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "rootUri TEXT NOT NULL, " +
                        "relativePath TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL DEFAULT 0, " +
                        "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                        "custom1 INTEGER NOT NULL DEFAULT 0, " +
                        "custom2 INTEGER NOT NULL DEFAULT 0, " +
                        "sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_file_review_state_rootUri_relativePath " +
                        "ON file_review_state (rootUri, relativePath)"
                )

                connection.execSQL(
                    "INSERT OR IGNORE INTO file_review_state (" +
                        "rootUri, relativePath, isCompleted, isFavorite, custom1, custom2, " +
                        "sourceMissing, updatedAt" +
                        ") SELECT l.sourceRootUri, i.rootRelativePath, " +
                        "MAX(i.isCompleted), MAX(i.isFavorite), MAX(i.custom1), MAX(i.custom2), " +
                        "MIN(i.sourceMissing), 0 " +
                        "FROM list_items i JOIN lists l ON l.id = i.listId " +
                        "WHERE l.sourceRootUri IS NOT NULL AND i.rootRelativePath IS NOT NULL " +
                        "GROUP BY l.sourceRootUri, i.rootRelativePath"
                )

                connection.execSQL(
                    "UPDATE list_items SET isCompleted = 0, isFavorite = 0, custom1 = 0, custom2 = 0 " +
                        "WHERE rootRelativePath IS NOT NULL"
                )
            }
        }

        /**
         * V9 caches each source file's size and modified time on its item row.
         *
         * Purely additive and nullable, so every existing item survives as "not measured yet" —
         * which the filter and the sort already know how to treat, and which any scan of the
         * linked folder fills in. Nothing is backfilled here: this migration has no folder to
         * read, and inventing a zero would be worse than admitting an absence.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceSizeBytes INTEGER")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceModifiedAt INTEGER")
            }
        }

        /**
         * V10 gives every item a public id, which is what the webhook now sends in place of the
         * row's sequence number. See [newItemPublicId] for the shape and the reasons.
         *
         * Additive columns, then a backfill, because an id has to exist for rows that were
         * written before the scheme did: a list built last month must keep working, and a
         * delivery that found an empty id would be worse than one that never had a good one.
         *
         * The backfill runs in Kotlin rather than SQL — the id is a base32 encoding over a hash,
         * and SQLite can do neither — so each row is read, given an id built from what it already
         * knows about itself, and written back. It is the same generator new rows use, so a
         * backfilled id is indistinguishable from a fresh one and fingerprints the same file the
         * same way.
         *
         * The time in a backfilled id is the row's own: an item's createdAt, a file state's
         * updatedAt. Neither is a lie, and both keep the ids sorting in the order the rows were
         * actually made. Where there is no usable time — a file_review_state row created by the
         * V8 migration, which had none to record and stored 0 — the migration's own clock stands
         * in, which is honest in its own way: that is when the row first became something the
         * outside world could name.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()

                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE file_review_state ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )

                backfill(
                    connection = connection,
                    read = "SELECT id, listId, title, rootRelativePath, createdAt FROM list_items",
                    update = "UPDATE list_items SET publicId = ? WHERE id = ?"
                ) { row ->
                    newItemPublicId(
                        itemIdentityOf(
                            rootRelativePath = if (row.isNull(3)) null else row.getText(3),
                            listId = row.getLong(1),
                            title = row.getText(2)
                        ),
                        row.getLong(4).takeIf { it > 0 } ?: now
                    )
                }

                backfill(
                    connection = connection,
                    read = "SELECT id, relativePath, updatedAt FROM file_review_state",
                    update = "UPDATE file_review_state SET publicId = ? WHERE id = ?"
                ) { row ->
                    newItemPublicId(
                        fileIdentity(row.getText(1)),
                        row.getLong(2).takeIf { it > 0 } ?: now
                    )
                }
            }
        }

        /**
         * Reads every row [read] returns, computes an id for it, and writes that id back.
         *
         * The whole read is drained into memory before a single update goes out. Stepping a
         * cursor over a table while updating that same table is the kind of thing SQLite is
         * entitled to have an opinion about, and an item table is thousands of rows, not
         * millions. [id] is expected to be the first column of [read].
         */
        private fun backfill(
            connection: SQLiteConnection,
            read: String,
            update: String,
            assign: (SQLiteStatement) -> String
        ) {
            val assigned = mutableListOf<Pair<Long, String>>()
            connection.prepare(read).use { row ->
                while (row.step()) assigned += row.getLong(0) to assign(row)
            }
            connection.prepare(update).use { write ->
                for ((rowId, publicId) in assigned) {
                    write.bindText(1, publicId)
                    write.bindLong(2, rowId)
                    write.step()
                    write.reset()
                }
            }
        }

        /**
         * V11 turns the unsent-payload store into a record of every delivery.
         *
         * The table stopped meaning what its name said. It held only the deliveries that went
         * wrong, so it could say what had failed and never what had gone out — and once an
         * automatic delivery started asking permission first, "the user said no" became a third
         * thing it had to be able to hold. A row is now one payload plus what became of it, so
         * the columns are renamed to match: `failedAt` was a lie the moment a success could land
         * in here, and `reason` reads oddly against one.
         *
         * Rebuilt rather than altered in place. SQLite can rename a column only from 3.25, which
         * is Android 11, and this app runs on 7 — so the honest names cost a copy. Every existing
         * row survives with its payload intact and keeps its place in the history.
         *
         * The old rows are classified from the words they already carry. Nothing but a failure or
         * a switched-off webhook could ever have been written, an empty payload was never kept,
         * and the switched-off case has always said so in the same fixed phrase — so the outcome
         * is recoverable exactly, without guessing.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS webhook_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "listTitle TEXT NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "at INTEGER NOT NULL, " +
                        "outcome TEXT NOT NULL, " +
                        "detail TEXT NOT NULL)"
                )
                connection.execSQL(
                    "INSERT INTO webhook_history (" +
                        "id, event, listTitle, itemCount, payload, at, outcome, detail" +
                        ") SELECT id, event, listTitle, itemCount, payload, failedAt, " +
                        "CASE WHEN reason LIKE 'Nothing was sent%' THEN 'DISABLED' ELSE 'FAILED' END, " +
                        "reason FROM unsent_webhooks"
                )
                connection.execSQL("DROP TABLE IF EXISTS unsent_webhooks")
            }
        }


        /**
         * V12 makes the action slots a list instead of two columns.
         *
         * `custom1` and `custom2` were a promise that there would only ever be two, written into
         * the schema of both tables that hold a decision. Adding a third would have meant a
         * migration, and adding one from a settings page cannot mean a migration — so the pair of
         * booleans becomes one text column holding the ids an item carries, and the number of
         * actions stops being a fact about the database at all.
         *
         * Rebuilt rather than altered in place, for the same reason V11 was: SQLite learned to
         * drop a column in 3.35, which is Android 14, and this app runs on 7.
         *
         * The ids the old columns become are `custom1` and `custom2`, which is exactly what
         * [Templates.DefaultTagButtons] is built with. That is what makes this upgrade invisible:
         * an item marked C1 yesterday resolves to the action still called C1 today, keeps its
         * label, and goes out under the same wire value.
         *
         * Favourite is untouched. It is the one action Listea knows the meaning of, so it keeps
         * a column of its own.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                rebuildWithActionIds(
                    connection = connection,
                    table = "list_items",
                    columns = "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "publicId TEXT NOT NULL DEFAULT '', " +
                        "listId INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL DEFAULT 0, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "sourceUri TEXT, " +
                        "sourceRelativePath TEXT, " +
                        "rootRelativePath TEXT, " +
                        "sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                        "sourceSizeBytes INTEGER, " +
                        "sourceModifiedAt INTEGER, " +
                        "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                        "customActions TEXT NOT NULL DEFAULT '', " +
                        "FOREIGN KEY(listId) REFERENCES lists(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE",
                    carried = "id, publicId, listId, title, isCompleted, sortOrder, createdAt, " +
                        "sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                        "sourceSizeBytes, sourceModifiedAt, isFavorite",
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS index_list_items_listId " +
                            "ON list_items (listId)",
                        "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
                            "ON list_items (rootRelativePath)"
                    )
                )

                rebuildWithActionIds(
                    connection = connection,
                    table = "file_review_state",
                    columns = "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "publicId TEXT NOT NULL DEFAULT '', " +
                        "rootUri TEXT NOT NULL, " +
                        "relativePath TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL DEFAULT 0, " +
                        "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                        "customActions TEXT NOT NULL DEFAULT '', " +
                        "sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL",
                    carried = "id, publicId, rootUri, relativePath, isCompleted, isFavorite, " +
                        "sourceMissing, updatedAt",
                    indices = listOf(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_file_review_state_rootUri_relativePath " +
                            "ON file_review_state (rootUri, relativePath)"
                    )
                )
            }
        }

        /**
         * V13 splits what a List *contains* from what it is *about*.
         *
         * Until now one row was both: `list_items` was a file's membership in a List and the place
         * its decision lived, which is why a file reachable from two Lists needed the whole
         * `file_review_state` detour to keep the copies from disagreeing. A Task is the thing
         * judged, an Asset is content presented for it, and a membership is a membership — so a
         * file reached from two Lists is one Task with two memberships, in the schema rather than
         * only in the queries.
         *
         * Four things happen, in this order, and the order is load-bearing:
         *
         * 1. The new tables and the two `lists` template columns.
         * 2. Every file that already has a decision becomes a Task with one Asset, its state
         *    copied across, and the `publicId` it already had kept in `legacy_item_ids` so a
         *    receiver keyed on that name keeps seeing it.
         * 3. Every membership resolves the Task of the file it names — looking up what step 2 made,
         *    and inventing an all-false one where there is none, which is exactly what today's
         *    `COALESCE(..., 0)` projects. Manually typed items get a Task of their own and a name
         *    in `legacy_manual_tasks`.
         * 4. `list_items` is rebuilt with `taskId TEXT NOT NULL`.
         *
         * **No ReviewEvents are written.** A review that nobody performed must not appear in a
         * record of reviews, and a migration marker that could be counted as a submission would be
         * worse than no history at all. A List's existing progress therefore survives as TaskState
         * and reads as it always did; what it does not become is a claim about who judged what.
         *
         * The decision columns on `list_items` are carried across with their values intact rather
         * than cleared, as `MIGRATION_7_8` cleared its equivalents. They stop being read the moment
         * the read paths move, but until then they are the only remaining copy of what a manual
         * item's state was, and a migration later found wrong is easier to reason about with the
         * original still on disk. They are dropped in a later release, once no reader is provable.
         *
         * UUIDs are generated here rather than in SQL, which is why the two loops are Kotlin. Room
         * runs the whole thing in one transaction, so a failure part-way leaves nothing behind.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()

                createTaskTables(connection)
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN taskReviewTemplateId TEXT NOT NULL " +
                        "DEFAULT 'builtin.media.rapid-review'"
                )
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN taskReviewTemplateVersion INTEGER NOT NULL DEFAULT 1"
                )

                // Every file something is already known about becomes a Task with one Asset.
                val taskByFile = mutableMapOf<Pair<String, String>, String>()
                connection.prepare(
                    "SELECT publicId, rootUri, relativePath, isCompleted, isFavorite, " +
                        "customActions, sourceMissing, updatedAt FROM file_review_state"
                ).use { row ->
                    while (row.step()) {
                        val rootUri = row.getText(1)
                        val relativePath = row.getText(2)
                        // A row the V8 migration created had no time of its own and stored 0.
                        // The migration's clock stands in, which is honest: that is when this
                        // file first became something with state attached.
                        val updatedAt = row.getLong(7).takeIf { it > 0 } ?: now

                        val taskId = UUID.randomUUID().toString()
                        val assetId = UUID.randomUUID().toString()

                        insertTask(connection, taskId, updatedAt)
                        insertAsset(
                            connection = connection,
                            id = assetId,
                            taskId = taskId,
                            rootUri = rootUri,
                            relativePath = relativePath,
                            sourceMissing = row.getInt(6) != 0
                        )
                        insertTaskState(
                            connection = connection,
                            taskId = taskId,
                            isCompleted = row.getInt(3) != 0,
                            isFavorite = row.getInt(4) != 0,
                            customActions = row.getText(5),
                            updatedAt = updatedAt
                        )
                        insertLegacyItemId(connection, assetId, row.getText(0))

                        taskByFile[rootUri to relativePath] = taskId
                    }
                }

                // Every membership is given the Task of the file it names.
                val rootByList = mutableMapOf<Long, String>()
                connection.prepare(
                    "SELECT id, sourceRootUri FROM lists WHERE sourceRootUri IS NOT NULL"
                ).use { row ->
                    while (row.step()) rootByList[row.getLong(0)] = row.getText(1)
                }

                // The resolved ids land in a scratch table so the rebuild below can stay one
                // INSERT ... SELECT instead of a sixteen-argument bind per row.
                connection.execSQL(
                    "CREATE TABLE list_items_v13_tasks (" +
                        "itemId INTEGER PRIMARY KEY, taskId TEXT NOT NULL)"
                )
                connection.prepare(
                    "INSERT INTO list_items_v13_tasks (itemId, taskId) VALUES (?, ?)"
                ).use { insert ->
                    connection.prepare(
                        "SELECT id, listId, rootRelativePath, isCompleted, isFavorite, " +
                            "customActions, createdAt, sourceMissing FROM list_items"
                    ).use { row ->
                        while (row.step()) {
                            val rootUri = rootByList[row.getLong(1)]
                            val rootRelativePath = if (row.isNull(2)) null else row.getText(2)

                            val taskId = if (rootRelativePath != null && rootUri != null) {
                                taskByFile.getOrPut(rootUri to rootRelativePath) {
                                    // A file the app has never recorded a decision about. Its
                                    // Task starts with everything false, which is what the read
                                    // paths already project for exactly this case.
                                    val fresh = UUID.randomUUID().toString()
                                    insertTask(connection, fresh, now)
                                    insertAsset(
                                        connection = connection,
                                        id = UUID.randomUUID().toString(),
                                        taskId = fresh,
                                        rootUri = rootUri,
                                        relativePath = rootRelativePath,
                                        // Carried, not assumed. A file that went missing before
                                        // this migration was still missing after it, and an
                                        // Asset claiming otherwise would offer up a file that is
                                        // not there as one that could be deleted.
                                        sourceMissing = row.getInt(7) != 0
                                    )
                                    insertTaskState(
                                        connection = connection,
                                        taskId = fresh,
                                        isCompleted = false,
                                        isFavorite = false,
                                        customActions = "",
                                        updatedAt = now
                                    )
                                    fresh
                                }
                            } else {
                                // A manually typed item — or the one shape treated as one: a row
                                // carrying a root-relative path whose List is not linked to a
                                // root. Treated as manual rather than dropped, because a row
                                // nobody can resolve is still somebody's item.
                                //
                                // One Task per row, so two entries with the same title stay two
                                // things; no Asset, because there is no file; and a name in
                                // `legacy_manual_tasks`, which is what lets the cleanup path
                                // delete it later instead of guessing from an empty Asset count.
                                val fresh = UUID.randomUUID().toString()
                                insertTask(connection, fresh, now)
                                insertTaskState(
                                    connection = connection,
                                    taskId = fresh,
                                    isCompleted = row.getInt(3) != 0,
                                    isFavorite = row.getInt(4) != 0,
                                    customActions = row.getText(5),
                                    updatedAt = row.getLong(6).takeIf { it > 0 } ?: now
                                )
                                insertLegacyManualTask(connection, fresh)
                                fresh
                            }

                            insert.bindLong(1, row.getLong(0))
                            insert.bindText(2, taskId)
                            insert.step()
                            insert.reset()
                        }
                    }
                }

                rebuildListItemsWithTaskId(connection)
            }
        }

        /**
         * V14 gives a Task a place to have come from, without changing what a Task is.
         *
         * Three things happen, and none of them redefines identity. Four new tables arrive — a
         * registered Source, the provenance of a Task under one, and the two legacy claim sets that
         * name what a v0.1 database already contained. Three additive columns land on `assets`, so a
         * catalog scope has somewhere to read the document URI and the measurements that used to come
         * off `list_items` or a live folder listing. And every file-backed Task is claimed into
         * `legacy_source_tasks`, because a browse minted it and no Source has yet.
         *
         * The claim is a `SELECT DISTINCT taskId FROM assets WHERE rootUri IS NOT NULL`: a Task with
         * no file-backed Asset is a legacy manual item, and `legacy_manual_tasks` already names it —
         * it has no source material, so nothing could ever import it. The Asset columns are backfilled
         * from the `list_items` row naming the same Task, which carried `sourceUri`, `sourceSizeBytes`
         * and `sourceModifiedAt` for exactly this file; a Task that only ever existed through Quick
         * Review keeps nulls, and the first import fills them in.
         *
         * Nothing else is written. No `tasks`, no `task_state`, no `review_events`, no `list_items`,
         * no `lists`, no ids of any kind — a migration must not invent a review nobody performed, nor
         * a Source nobody registered. `sources` and `task_imports` are left empty on purpose: nothing
         * has yet decided that these Tasks are imported material.
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                createImportTables(connection)
                connection.execSQL("ALTER TABLE assets ADD COLUMN sourceUri TEXT")
                connection.execSQL("ALTER TABLE assets ADD COLUMN sizeBytes INTEGER")
                connection.execSQL("ALTER TABLE assets ADD COLUMN lastModified INTEGER")

                // Every file-backed Task is a legacy source Task until a real import adopts it.
                connection.execSQL(
                    "INSERT INTO legacy_source_tasks (taskId) " +
                        "SELECT DISTINCT taskId FROM assets WHERE rootUri IS NOT NULL"
                )

                // A file Task's cached measurements live on its list_items row today; copy them
                // across so a catalog scope can read them without a List. A file in two Lists has two
                // rows naming the same Task; one is taken, and they agree by construction.
                connection.execSQL(
                    "UPDATE assets SET " +
                        "sourceUri = (SELECT i.sourceUri FROM list_items i " +
                        "             WHERE i.taskId = assets.taskId AND i.sourceUri IS NOT NULL LIMIT 1), " +
                        "sizeBytes = (SELECT i.sourceSizeBytes FROM list_items i " +
                        "             WHERE i.taskId = assets.taskId AND i.sourceSizeBytes IS NOT NULL LIMIT 1), " +
                        "lastModified = (SELECT i.sourceModifiedAt FROM list_items i " +
                        "             WHERE i.taskId = assets.taskId AND i.sourceModifiedAt IS NOT NULL LIMIT 1) " +
                        "WHERE EXISTS (SELECT 1 FROM list_items i WHERE i.taskId = assets.taskId)"
                )
            }
        }

        /**
         * V15 gives a Source the first of its per-importer options.
         *
         * `1` — keep the folder tree — is the default because it is what the catalog already does:
         * `catalogAtScope` descends a Source's `groupPath` strings, so every file-as-Task Source
         * registered before this migration was already being read as a tree, and no existing row's
         * shape changes when it becomes an explicit column.
         *
         * Folder-as-Task Sources are the exception, and are set to `0` in the same statement. Their
         * Tasks are one per child folder, which is flat by construction, and
         * `normalizeKeepTree` refuses to store anything else — so leaving them at the default would
         * put a value in the column that the code which reads it considers impossible, and a Todo
         * opened from such a Source would offer to descend a tree that has no parents in it.
         *
         * A not-null column with a default, so nothing has to be backfilled in a second pass and
         * the table is never briefly in a state the entities cannot describe.
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN keepTree INTEGER NOT NULL DEFAULT 1"
                )
                connection.execSQL(
                    "UPDATE sources SET keepTree = 0 WHERE importerKind = 'folder-as-task'"
                )
            }
        }

        /**
         * V16 splits one row's two jobs: a registered folder becomes an Upstream, and a Source
         * becomes only what an import run writes.
         *
         * Three statements, in this order.
         *
         * 1. The table, in the shape [UpstreamEntity] declares and `16.json` records.
         * 2. **Every existing Source becomes an Upstream.** Before v16 there was no other way for a
         *    folder to be in the app, so every Source row is one somebody pointed the app at. This
         *    is what keeps the Upstreams list on screen across the upgrade: it showed Sources
         *    before and shows Upstreams after, and the rows are the same rows.
         * 3. **Sources no import ever ran on are dropped.** They were the "Add Upstream" writes —
         *    registered, named, and never read — and under the words the app now uses they were
         *    never Todos at all: a Todo is what an import produced. Keeping them would put a row
         *    in the Todo tab that no run ever created, which is the confusion this version exists
         *    to remove. The guard on `task_imports` is belt and braces: an import is the only
         *    writer of `lastImportAt`, so the two conditions cannot disagree, and if they ever did
         *    the foreign key would refuse the delete rather than orphan a Task's provenance.
         *
         * Nothing else moves. `tasks`, `assets`, `task_state`, `list_items` and every decision in
         * them are untouched, and the Sources that survive keep their ids — so a List built from
         * one still names the same Tasks afterwards.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS upstreams (" +
                        "id TEXT NOT NULL, rootUri TEXT NOT NULL, " +
                        "relativePath TEXT NOT NULL, displayName TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_upstreams_rootUri_relativePath " +
                        "ON upstreams (rootUri, relativePath)"
                )
                connection.execSQL(
                    "INSERT OR IGNORE INTO upstreams " +
                        "(id, rootUri, relativePath, displayName, addedAt) " +
                        "SELECT id, rootUri, relativePath, displayName, createdAt FROM sources"
                )
                connection.execSQL(
                    "DELETE FROM sources WHERE lastImportAt IS NULL " +
                        "AND id NOT IN (SELECT sourceId FROM task_imports)"
                )
            }
        }

        /**
         * V17 lets one folder back several Todos, and one file be the content of several Tasks.
         *
         * Both were `UNIQUE` indices, and both were the same claim made twice: that a file belongs
         * to one import and therefore to one Task. That claim is what this version retires. Two
         * Todos over one folder are two intentions — the same market supplying two dishes — and a
         * file under each is two Tasks whose decisions are allowed to disagree.
         *
         * Three statements, and no table is rebuilt, because neither change is a change of *columns*:
         *
         * 1. `sources` keeps an index on `(rootUri, relativePath)` and loses only its uniqueness.
         *    The pair is still how a caller finds the Sources at a folder; it is simply no longer
         *    how the schema forbids a second one. Dropped and recreated rather than altered,
         *    because SQLite has no way to relax a constraint in place.
         * 2. `assets` keeps a unique index and changes what it is unique *over*: one Task holds at
         *    most one Asset per file, which is the invariant that was ever worth keeping, since two
         *    Assets of one Task at one path would be two answers to the same question.
         * 3. A plain `(rootUri, relativePath)` index is added, because the reads that resolve a
         *    file to its Tasks — `file_review_state`'s joins among them — now find several rows
         *    where they used to find one, and they must not do it by table scan.
         *
         * Every existing row already satisfies the new constraint: before this version one file had
         * exactly one Asset, so `(taskId, rootUri, relativePath)` was already unique. Nothing is
         * copied, nothing is deleted, and every id survives — a List built from a Task still names
         * the same Task afterwards.
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP INDEX IF EXISTS index_sources_rootUri_relativePath")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sources_rootUri_relativePath " +
                        "ON sources (rootUri, relativePath)"
                )
                connection.execSQL("DROP INDEX IF EXISTS index_assets_rootUri_relativePath")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_assets_taskId_rootUri_relativePath " +
                        "ON assets (taskId, rootUri, relativePath)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assets_rootUri_relativePath " +
                        "ON assets (rootUri, relativePath)"
                )
            }
        }

        /**
         * V18 lets a Todo be dissolved without its judgments being lost, and lets a List say what a
         * dissolution took out of it.
         *
         * Four statements, all additive. No table is rebuilt, no column is rewritten, and every id
         * and decision on the device survives — including the four real Todos this migration runs
         * over first.
         *
         * 1. `sources.keepStateOnDelete`, defaulted to `0`. It is a *default* for the delete
         *    dialog's checkbox rather than a decision taken on the user's behalf, and `0` is the
         *    value that preserves today's behaviour: before this version nothing could delete a
         *    Todo at all, so there was no state-preserving path to preserve. The same reasoning as
         *    `MIGRATION_14_15`'s `keepTree` — not-null with a default, so nothing is backfilled in
         *    a second pass and the table is never briefly in a state the entities cannot describe.
         * 2. `lists.lastDissolvedCount` / `lastDissolvedAt`, both nullable and both left null.
         *    Nothing has been dissolved out of any existing List, and null is how the entity says
         *    so; there is no "zero" that means the same thing, because a dissolution that removed
         *    nothing is not a thing that happens.
         * 3. `dissolved_task_state`, in the shape [DissolvedTaskStateEntity] declares and `18.json`
         *    records. Written out one statement at a time, for the same reason
         *    [createImportTables] is: a column that changes in the entity is a line that has to
         *    change here, and the two can be diffed against the exported schema by eye.
         *
         * The two indices on the new table are the two reads. `sourceId` is both the recovery
         * picker's grouping and the handle `recoverStateFrom` narrows by, and `(rootUri,
         * sourcePath)` is what answers "which histories does *this* folder offer?" — a recovery
         * belongs to the folder the intention was made at. Neither is unique: a Todo dissolved
         * twice at one folder leaves two histories, and that is exactly what "load from one history
         * at a time" is choosing between.
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN keepStateOnDelete INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDissolvedCount INTEGER")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDissolvedAt INTEGER")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS dissolved_task_state (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sourceId TEXT NOT NULL, sourceName TEXT NOT NULL, " +
                        "rootUri TEXT NOT NULL, sourcePath TEXT NOT NULL, " +
                        "groupPath TEXT NOT NULL, importerKind TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL, isFavorite INTEGER NOT NULL, " +
                        "customActions TEXT NOT NULL, dissolvedAt INTEGER NOT NULL)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dissolved_task_state_sourceId " +
                        "ON dissolved_task_state (sourceId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_dissolved_task_state_rootUri_sourcePath " +
                        "ON dissolved_task_state (rootUri, sourcePath)"
                )
            }
        }

        /**
         * V19 gives a Todo the two standings it can take on its own material: what a change to one
         * of its Tasks' content means, and whether new material flows in on its own.
         *
         * Three `ALTER`s and nothing else. All three columns are not-null *with* a default, so SQLite
         * fills every existing row in the statement itself and no second pass runs over the table
         * — the same shape `MIGRATION_14_15`'s `keepTree` and `MIGRATION_17_18`'s
         * `keepStateOnDelete` already use, and for the same reason: the table is never briefly in a
         * state the entity cannot describe.
         *
         * `'keep'` and `0` are the behaviour-preserving values, which is what `docs/settings.md`
         * requires of anything added after a feature shipped. Before this version a run over a Todo
         * changed no Task's state and no run ever started by itself, so a fresh install and the
         * Todos already on a device must go on doing exactly that. The defaults are also where the
         * two columns get their *meaning*: `keep` is the tier that reconciles without touching a
         * judgment, and a Todo that does not follow its upstream is the only kind there has ever
         * been.
         *
         * The third lands on `lists`, and unlike the other two it is not a policy at all: it is the
         * reason a List that lost memberships was left with, which is why its default is the one
         * cause that could have written such a note before this version — `'dissolved'`, a Todo
         * deleted. The `drop` tier gives a second operation the same consequence, and a List that
         * reported rows vanishing without saying why is the screen `docs/file-management.md`
         * refuses; backfilling this to anything else would have rewritten what the existing notes
         * say.
         */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN contentPolicy TEXT NOT NULL DEFAULT 'keep'"
                )
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN followUpstream INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN lastDissolvedReason TEXT NOT NULL DEFAULT 'dissolved'"
                )
            }
        }

        /**
         * V20 lets a Todo, and each Task under it, record which asset control governs its files.
         *
         * Two `ALTER`s and nothing else, both not-null with a default, so SQLite fills every
         * existing row in the statement itself and the table is never briefly in a state the entity
         * cannot describe — the shape `MIGRATION_14_15`'s `keepTree` and `MIGRATION_18_19`'s
         * `contentPolicy` already use, and they say why.
         *
         * ## `''` means "never chosen", and nothing here may be read as "no tags"
         *
         * This is the one thing about this version worth being careful with, and it is subtler than
         * the defaults the two migrations above chose. `'keep'` was a policy that could be described
         * and applied; `''` is the *absence of an answer*, and every reader has to resolve it as
         * *this app's current configuration for the kind* rather than as an empty tag set. A reader
         * that gets it wrong strips the tag row off every Asset of every Todo already on the device
         * — silently, without an error, and without a row changing.
         *
         * ## Why nothing is backfilled
         *
         * Two reasons, and the second is the real one. The mechanical one: a migration runs against
         * SQLite and cannot read DataStore, so the tags the user currently has are not available to
         * it. The one that matters: there is nothing correct to write even if they were. What a Todo
         * predating this version "chose" is *the app default, continuously* — so freezing a snapshot
         * of today's list onto the row would not preserve the old behaviour, it would end it, and
         * the user would see it end the next time they edited Settings.
         *
         * Absence is therefore both behaviour-preserving and self-healing: every Asset offers what
         * it offered yesterday, and the next import run stamps the answer the user gives it. That is
         * the same "a read rather than a one-off rewrite" argument `SettingsStore`'s per-kind tag
         * keys make from the other side, and the two together are the whole upgrade path.
         */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN assetControls TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE task_imports ADD COLUMN assetControls TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v0.5.1: the record moves from the Task down to the Task–Asset pair.
         *
         * A create and a copy, and the copy is the whole of the behaviour preservation. Every Asset
         * that existed gets a row carrying its Task's marks, so a file that was showing `p2` and `p5`
         * is still showing them the moment the app reopens — including every file of a multi-Asset
         * Task, where the one Task-level record is copied to each of them.
         *
         * That last case is deliberate and is not an attempt to reconstruct a per-file answer: there
         * was no such fact to recover, and inventing a distribution across three photographs would be
         * this migration guessing. What it does is preserve what the user saw, so that the change is
         * visible from the first tap afterwards rather than from the upgrade.
         *
         * **The `task_state` columns are left populated and are not read again.** Dropping them is a
         * table rebuild — `DROP COLUMN` needs SQLite 3.35 and minSdk here is 24 — and they are the
         * only remaining home for a legacy manual item's marks, since a Task with no Asset has no
         * pair to have been copied to. Nothing consults them; the comment on
         * [TaskStateEntity.isFavorite] is where that is stated for the reader who finds them.
         *
         * The `updatedAt` copied across is the Task's, which is the only timestamp the old record
         * had. It is an honest "when this was last true" rather than a fabricated one.
         */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_asset_state (" +
                        "assetId TEXT NOT NULL, isFavorite INTEGER NOT NULL, " +
                        "customActions TEXT NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(assetId), FOREIGN KEY(assetId) REFERENCES assets(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                connection.execSQL(
                    "INSERT INTO task_asset_state (assetId, isFavorite, customActions, updatedAt) " +
                        "SELECT a.id, ts.isFavorite, ts.customActions, ts.updatedAt " +
                        "FROM assets a JOIN task_state ts ON ts.taskId = a.taskId"
                )
            }
        }

        /**
         * v0.5.2: a dissolved history carries its files' records, not a Task-level summary of them.
         *
         * v0.5.1 moved the record to the Task–Asset pair and left this table reading `task_state`'s
         * retired columns, so a dissolution recorded a value frozen at the migration and a recovery
         * wrote into a column no surface reads. The repair is not a repoint: one row per Task keyed
         * by `groupPath` has no way to say *which* file a mark was on, and the Assets are gone by the
         * time a recovery runs. So the records move into a payload — `records`, with `recordVersion`
         * beside it — and the two retired columns come off.
         *
         * **A full rebuild, because there is no `DROP COLUMN` to use.** SQLite gained it in 3.35 and
         * `minSdk = 24` ships 3.9. The standard shape: create the new table, copy, drop, rename, and
         * **recreate both indices** — Room compares what it finds against `22.json` on open, and an
         * index left behind is a migration that fails there rather than on the first query.
         *
         * **Existing rows keep their completion and get no file records.** Their marks were
         * Task-level, and there is no honest per-file answer to give them: applying the Task's to
         * every file of the recovered Task would be v0.5.1's bug arriving through the recovery door.
         * An empty payload is the admission that the old table never knew which file a mark was on,
         * and the recovery restores what it can rather than inventing what it cannot.
         */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS dissolved_task_state_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sourceId TEXT NOT NULL, sourceName TEXT NOT NULL, " +
                        "rootUri TEXT NOT NULL, sourcePath TEXT NOT NULL, " +
                        "groupPath TEXT NOT NULL, importerKind TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL, recordVersion INTEGER NOT NULL, " +
                        "records TEXT NOT NULL, dissolvedAt INTEGER NOT NULL)"
                )
                // `1` written out, and **not** [DISSOLVED_RECORDS_VERSION]. A migration is frozen at
                // what it wrote the day it shipped; a constant is not. This step wrote the
                // flag-version's shape — an empty payload, which is the admission recorded above —
                // and it must go on saying so. Interpolated, it would have started claiming version 2
                // the moment v0.5.4 added one, which is this step asserting a payload in an encoding
                // no release ever produced here. The two read the same today because an empty payload
                // is empty under both, which is exactly why the difference would go unnoticed until
                // the day one of them changed what empty means.
                connection.execSQL(
                    "INSERT INTO dissolved_task_state_new " +
                        "(id, sourceId, sourceName, rootUri, sourcePath, groupPath, " +
                        " importerKind, isCompleted, recordVersion, records, dissolvedAt) " +
                        "SELECT id, sourceId, sourceName, rootUri, sourcePath, groupPath, " +
                        " importerKind, isCompleted, 1, '', dissolvedAt " +
                        "FROM dissolved_task_state"
                )
                connection.execSQL("DROP TABLE dissolved_task_state")
                connection.execSQL(
                    "ALTER TABLE dissolved_task_state_new RENAME TO dissolved_task_state"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dissolved_task_state_sourceId " +
                        "ON dissolved_task_state (sourceId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_dissolved_task_state_rootUri_sourcePath " +
                        "ON dissolved_task_state (rootUri, sourcePath)"
                )
            }
        }

        /**
         * Repairs the Lists whose recorded completion had drifted from their own rows. **No DDL** —
         * the schema is unchanged, and this step exists because a stored answer can be wrong.
         *
         * `lists.completedAt` answers *is this List finished*, and it is what the Lists screen files
         * a row under, while the `N / N` beside it is counted live. Four writes moved a List's
         * membership without re-asking the question — `createListFromTasks`, `addTasksToList`,
         * `deleteTodo` and `dropTask` — so a List built from Tasks that were already done was
         * recorded as unfinished and stayed that way. The four are fixed in this release, so nothing
         * drifts again; this is for the rows that already had, which is the difference between
         * fixing the bug and fixing the users.
         *
         * **The two statements are [ListsDao.refreshCompletion] written as SQL**, because a
         * migration cannot call a DAO. The first completes a List that has rows and none unfinished;
         * the second clears one that has no rows at all or any unfinished row, which is the same
         * refusal to call an empty List finished that the DAO makes and the reason a List emptied by
         * a dissolution is repaired *out* of completion rather than left in it. They are mirrors of
         * `itemCount` and `unfinishedItemCount`'s join, deliberately, so the two can be diffed.
         *
         * **A repaired row is stamped with the upgrade time.** The moment it really became complete
         * was never written down, and `completedAt` is sent in webhook payloads — so an invented
         * earlier value would be a worse answer than an honest "as of now". Rows that were already
         * right are not touched at all, which is what keeps a real completion time intact.
         */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()
                connection.execSQL(
                    "UPDATE lists SET completedAt = $now " +
                        "WHERE completedAt IS NULL " +
                        "AND EXISTS (SELECT 1 FROM list_items WHERE listId = lists.id) " +
                        "AND NOT EXISTS (" + UNFINISHED_ITEMS_OF_LIST + ")"
                )
                connection.execSQL(
                    "UPDATE lists SET completedAt = NULL " +
                        "WHERE completedAt IS NOT NULL " +
                        "AND (NOT EXISTS (SELECT 1 FROM list_items WHERE listId = lists.id) " +
                        "OR EXISTS (" + UNFINISHED_ITEMS_OF_LIST + "))"
                )
            }
        }

        /**
         * The step that gives a template a row, gives a pair's marks one place to live, and turns
         * every Todo's and Task's configuration into a reference to the template that answers it.
         *
         * **It creates two tables and three columns, and it rewrites two columns.** The additions are
         * additive: every `isFavorite` and `customActions` goes on holding what it held, so the merge
         * into `selections` can be checked against the rows it came from. Two of the three are the
         * projection slots `list_items` and `file_review_state` fill from the pair table; only
         * `task_asset_state.selections` is a record. The rewrites
         * are not, and they are the half of this release that has no second chance — see
         * [rewriteAssetControls].
         *
         * **Why the rewrite is this step and not a later one.** The plan had it as a separate
         * 23 → 24 → 25 pair, so that a build could exist in which the tables are seeded and nothing
         * yet depends on them. That build is the one this codebase's own rule refuses: the resolver
         * reads a reference while the columns still hold a configuration, and the two disagree about
         * what the column means. Version 24 has never shipped and no device holds a database written
         * by a build that is not this one, so the two steps are one step and there is no version in
         * between for anything to be wrong in.
         *
         * **A database that is already at 24 does not run this again.** The maintainer's phone is
         * one: it was carried to 24 by the build that ran the additive half alone, and it holds no
         * `sources` or `task_imports` row at all, so there is nothing for the rewrite to be needed
         * for. Rows in the old shape that a future device somehow keeps are still read, with their
         * tags dropped — [decodeTemplateAttachments] is the tolerant reader that makes that true, and
         * this is the step that makes it the fallback rather than the ordinary path.
         *
         * **The `★` is merged by id rather than special-cased.** It enters the set as `favorite` and
         * is sorted with everything else by [encodeActionIds], so the string written here is the
         * string the app writes. A step that put it first, or that concatenated the two columns in
         * SQL, would produce a form the app never produces and every reader would have to normalise.
         */
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()

                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS templates (id TEXT NOT NULL, name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS template_versions (templateId TEXT NOT NULL, " +
                        "version INTEGER NOT NULL, scope TEXT NOT NULL, buttons TEXT NOT NULL, " +
                        "buttonsVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(templateId, version), " +
                        "FOREIGN KEY(templateId) REFERENCES templates(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                seedBuiltInAssetControl(connection, now)

                connection.execSQL(
                    "ALTER TABLE task_asset_state ADD COLUMN selections TEXT NOT NULL DEFAULT ''"
                )
                // The other two projection slots, and the same `ALTER` rather than a rebuild. Neither
                // table's old pair is dropped, for the reason `AssetStateEntity.selections` gives: it
                // needs SQLite 3.35 and this app ships minSdk 24, so dropping one would be a rebuild of
                // a table a List page and the folder browser read on every row — and the pair is the
                // only copy of a mark that exists if the merged column is ever found to be wrong. The
                // two are read by nothing and are written by nothing after this step; the queries fill
                // *these* slots from the pair table, which is where a decision lives now.
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN selections TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE file_review_state ADD COLUMN selections TEXT NOT NULL DEFAULT ''"
                )
                backfillSelections(connection)
                rewriteAssetControls(connection, now)
            }
        }

        /**
         * The step that stops calling one mechanism the model.
         *
         * Three columns and one `UPDATE`, and the `UPDATE` is the whole reason this is not purely
         * additive. Read it as two different questions:
         *
         * - **A Todo is being preserved.** A Todo that posted before the upgrade must post after it,
         *   or the release silently stops work the user set up — so the column default is `webhook`,
         *   which is what every existing Todo was doing. A Todo created *now* is a choice being
         *   made, and the dialog offers `none` first; that asymmetry is deliberate and lives in the
         *   Kotlin default (`SourceEntity.exportKind`), not here.
         * - **A List is being asked what it used to do.** `webhookEnabled` is the switch the user
         *   actually touched, and it is the only honest signal. `webhookUrl` is **not**: it was
         *   seeded from the settings default at creation, so every List made while a default was
         *   set holds one whether or not anybody ever switched it on, and a rule keyed on it would
         *   pin nearly every List to the webhook. A List that had the switch on becomes `webhook`,
         *   which is exactly what it did — it posted regardless of its Todos; one that did not
         *   keeps `inherit`, and follows its Todos from here on.
         *
         * `lists.webhookUrl` is **not** dropped and **not** cleared. Dropping a column needs SQLite
         * 3.35 and this app ships minSdk 24; the column is left holding what it held and is read by
         * nothing from this version on. `list_items.isCompleted` and `task_state.isFavorite` are the
         * precedent, and the trade is the same one: an unused column costs a few bytes per row, and
         * a rebuild costs a table the List page reads on every row.
         *
         * The literals are written out rather than interpolated from `ExportKind` / `ExportChoice`.
         * A migration records what it did on the day it ran, and a later release that renames a key
         * must not retroactively change what this step wrote on somebody's device.
         */
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN exportKind TEXT NOT NULL DEFAULT 'webhook'"
                )
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN exportKind TEXT NOT NULL DEFAULT 'inherit'"
                )
                connection.execSQL(
                    "ALTER TABLE webhook_history ADD COLUMN kind TEXT NOT NULL DEFAULT 'webhook'"
                )
                connection.execSQL(
                    "UPDATE lists SET exportKind = 'webhook' WHERE webhookEnabled = 1"
                )
            }
        }

        /**
         * The step that gives Tasks and Assets a name of their own on the wire.
         *
         * v0.6.0's payload speaks about the Task and the file separately, so each needs an id that
         * survives a round. Neither table had one: `legacy_task_ids` names only the folder-round
         * Tasks that needed it, and `legacy_item_ids` names only the files a registration had
         * already named. Reading those tables at delivery time would leave most of a payload
         * without ids, so the ids are made once, here, and stored.
         *
         * **What is preserved.** Where the old wire had a name for the same thing, it keeps it:
         * a Task's folder-round id becomes its `task.id`, and a file's registration id becomes its
         * `asset.id`. A Task with exactly one Asset also inherits the List item id that named it,
         * because for that shape the old item *was* the file. A multi-Asset Task's List id is not
         * copied onto an arbitrary first file — the Task is the thing that id named, and the Task
         * already has its own id above.
         *
         * Everything else is minted at the Task's own `createdAt`, so the id's time prefix still
         * says when the row came to exist rather than when this release arrived. The final queries
         * refuse to leave a row nameless: an empty id on the wire is worse than a failed upgrade.
         */
        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE tasks ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE assets ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )
                backfillPublicIds(connection)
            }
        }

        /**
         * The step that gives a Todo its own webhook address.
         *
         * Two additive columns on `sources`, two on `webhook_history`, and one deliberate clear:
         * `lists.webhookUrl` has been dead since v0.5.6 and holds a copy of whatever Settings'
         * default was on the day the List was made. It becomes the List's *override* in this
         * release, and keeping those stale copies would silently redirect every existing List
         * the moment a non-empty value started meaning something. Clearing it is the only way to
         * say *this List has not chosen an address*.
         *
         * **What this step cannot do is fill the sources.** The value a Todo should copy is
         * Settings' default URL, and whether it should copy at all depends on the old global gate
         * — both live in DataStore, which a migration cannot read. So the migration records the
         * work in `schema_backfills` and [ListsDao.applyWebhookAddressBootstrap] performs it once,
         * after the app can read both stores. See [SchemaBackfillEntity] for why the marker is
         * deleted only in the same transaction as the write.
         *
         * Legacy history rows keep an empty address and round id. The UI reads the empty round id
         * as a round of one, which is exactly what each old row was.
         */
        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN webhookUrl TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE sources ADD COLUMN webhookFromDefault INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE webhook_history ADD COLUMN webhookUrl TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE webhook_history ADD COLUMN roundId TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL("UPDATE lists SET webhookUrl = ''")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS schema_backfills (" +
                        "name TEXT NOT NULL, PRIMARY KEY(name))"
                )
                connection.execSQL(
                    "INSERT OR IGNORE INTO schema_backfills (name) VALUES ('$WEBHOOK_ADDRESS_BACKFILL')"
                )
                Log.i("ListeaDatabase", "26 -> 27")
            }
        }

        /**
         * Gives a PDF Asset a place to keep the marks made on its passages.
         *
         * One table and one index, and nothing existing is touched. A database that has never held
         * a PDF simply has no rows in it, which is the same state a fresh install reaches through
         * Room's create path.
         *
         * **The foreign key is to the Asset, and that is the only cascade.** An Asset an import run
         * stops finding takes its marks with it, because a passage with no file under it is not a
         * statement about anything. A template or a tool is deliberately *not* referenced: removing
         * one must hide its marks, never delete them, so `authoredTemplateId` and
         * `authoredTemplateVersion` are plain columns recording where a mark came from.
         *
         * The columns are written out one to a line for the reason [createImportTables] gives: a
         * column that changes in [AssetMarkEntity] is a line that has to change here, and the two
         * can be diffed against `28.json` by eye.
         */
        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_asset_marks (" +
                        "id TEXT NOT NULL, " +
                        "assetId TEXT NOT NULL, " +
                        "toolId TEXT NOT NULL, " +
                        "authoredTemplateId TEXT NOT NULL, " +
                        "authoredTemplateVersion INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " +
                        "anchorVersion INTEGER NOT NULL, " +
                        "documentFingerprint TEXT NOT NULL, " +
                        "pageIndex INTEGER NOT NULL, " +
                        "charStart INTEGER NOT NULL, " +
                        "charEnd INTEGER NOT NULL, " +
                        "charParser TEXT NOT NULL, " +
                        "exactText TEXT NOT NULL, " +
                        "prefix TEXT NOT NULL, " +
                        "suffix TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(assetId) REFERENCES assets(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_asset_marks_assetId " +
                        "ON task_asset_marks (assetId)"
                )
                Log.i("ListeaDatabase", "27 -> 28")
            }
        }

        /**
         * Gives a Task a questionnaire that is nobody's yet.
         *
         * Three new tables and two columns, and no existing row changes meaning. `sources.addOn` and
         * `task_imports.addOn` default to `''`, which reads as *no add-on attached*; a Todo that
         * predates the round therefore gains no page and keeps exporting exactly what it did, which
         * is the behaviour-preserving direction every column added after a feature shipped must
         * take.
         *
         * `task_review_answers` cascades from `tasks` and from nothing else. The add-on a row was
         * authored under is a plain pair of columns, not a foreign key, because removing or editing
         * an add-on must hide an answer rather than delete it — the same rule `task_asset_marks`
         * follows one layer down.
         *
         * Columns are written one to a line so this block and `29.json` can be diffed by eye.
         */
        internal val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `addons` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `addon_versions` (" +
                        "`addOnId` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "`questions` TEXT NOT NULL, " +
                        "`questionsVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`addOnId`, `version`), " +
                        "FOREIGN KEY(`addOnId`) REFERENCES `addons`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_review_answers` (" +
                        "`taskId` TEXT NOT NULL, " +
                        "`questionId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, " +
                        "`authoredAddOnId` TEXT NOT NULL, " +
                        "`authoredAddOnVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`, `questionId`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_review_answers_taskId " +
                        "ON `task_review_answers` (`taskId`)"
                )
                connection.execSQL(
                    "ALTER TABLE `sources` ADD COLUMN `addOn` TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE `task_imports` ADD COLUMN `addOn` TEXT NOT NULL DEFAULT ''"
                )
                Log.i("ListeaDatabase", "28 -> 29")
            }
        }

        /**
         * Puts the Task Review on the stored path, and gives the Todo and the Task a place to name
         * one.
         *
         * Additive: three columns on `template_versions` describing what a version is, and two
         * reference columns beside `assetControls`/`addOn` on `sources` and `task_imports`. Every
         * existing row keeps its meaning — a template version written before this round is an
         * `asset-control`, and an empty Task Review reference is *the built-in rapid review*, which
         * is what every Todo and Task was implicitly using anyway.
         *
         * The two seeded lineages are the point of the step. Headless is now a stored definition
         * like any other, so the fallback chain lands on a row even on an upgraded device; the
         * `INSERT OR IGNORE` is what lets the creation callback and this migration share one
         * description without either refusing the other's work.
         */
        internal val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `template_versions` ADD COLUMN `kind` TEXT NOT NULL " +
                        "DEFAULT 'asset-control'"
                )
                connection.execSQL(
                    "ALTER TABLE `template_versions` ADD COLUMN `definition` TEXT NOT NULL " +
                        "DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE `template_versions` ADD COLUMN `definitionVersion` INTEGER NOT NULL " +
                        "DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE `sources` ADD COLUMN `taskReview` TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE `task_imports` ADD COLUMN `taskReview` TEXT NOT NULL DEFAULT ''"
                )
                seedBuiltInTaskReviews(connection, System.currentTimeMillis())
                Log.i("ListeaDatabase", "29 -> 30")
            }
        }

        /**
         * Splits the events of every readable submission into rows of their own.
         *
         * **Additive, and derived from what is already stored.** No existing column moves and no
         * response is rewritten: `review_events.response` remains the permanent record, and this
         * table is the queryable child the v0.8.1 template needed to *store* an event as an event.
         * An upgrade therefore loses nothing and gains a listable record of what was caught.
         *
         * The backfill reads each response through the same domain reader the app uses — never a
         * second parser written for the migration — so a response this build cannot read is left
         * out rather than guessed at, and a later build may backfill it differently. Asset
         * resolution matches the new write path: the event's path is a source URI, and a URI no
         * Asset under that Task still carries leaves `assetId` null.
         */
        internal val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_event_answers` (" +
                        "`reviewEventId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                        "`assetId` TEXT, `type` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "PRIMARY KEY(`reviewEventId`, `ordinal`), " +
                        "FOREIGN KEY(`reviewEventId`) REFERENCES `review_events`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_event_answers_assetId` " +
                        "ON `review_event_answers` (`assetId`)"
                )
                backfillReviewEventAnswers(connection)
                Log.i("ListeaDatabase", "30 -> 31")
            }
        }

        /**
         * The Package tables: the first stored Prompt payload, its import checkpoint, and the links
         * from a Task to the Package's Prompts and Resources.
         *
         * Additive and empty. No existing row changes meaning: a Task imported from a folder has no
         * `task_payloads` row and no Package link, and every reader must treat that absence as *not
         * a Package Task* rather than as a Prompt with no body. Nothing is backfilled, because
         * there is nothing in a pre-Package database that was a Prompt.
         */
        internal val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_payloads` (" +
                        "`taskId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`schemaVersion` INTEGER NOT NULL, `payload` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `package_checkpoints` (" +
                        "`sourceId` TEXT NOT NULL, `packageKey` TEXT NOT NULL, " +
                        "`rootUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                        "`protocol` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, " +
                        "`lastSeenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `packageKey`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION )"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `package_prompt_tasks` (" +
                        "`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`packageKey` TEXT NOT NULL, `promptKey` TEXT NOT NULL, " +
                        "`present` INTEGER NOT NULL, PRIMARY KEY(`taskId`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION )"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_package_prompt_tasks_sourceId_packageKey_promptKey` " +
                        "ON `package_prompt_tasks` (`sourceId`, `packageKey`, `promptKey`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `package_resources` (" +
                        "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`packageKey` TEXT NOT NULL, `resourceKey` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, `missing` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION )"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_package_resources_sourceId_packageKey_resourceKey` " +
                        "ON `package_resources` (`sourceId`, `packageKey`, `resourceKey`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_resources` (" +
                        "`taskId` TEXT NOT NULL, `resourceId` TEXT NOT NULL, " +
                        "`required` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `resourceId`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`resourceId`) REFERENCES `package_resources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_resources_resourceId` " +
                        "ON `task_resources` (`resourceId`)"
                )
                // The round ships a new built-in Task Review. `INSERT OR IGNORE` leaves the two
                // existing rows alone and writes the third; the creation callback sees the same
                // description on a fresh install. The guard in [SeedBuiltIn] is "no templates at
                // all" and would not reach a database that already holds two, so an upgrade needs
                // this line rather than the callback.
                seedBuiltInTaskReviews(connection, System.currentTimeMillis())
                Log.i("ListeaDatabase", "31 -> 32")
            }
        }

        /**
         * Schema 33: a Task's decision units, their payloads and Resources, and compositions.
         *
         * The shape change is a key change, not a column addition: `task_payloads` and
         * `task_resources` were keyed by Task, and payloads and Resource uses now belong to a part.
         * Both tables are copied into their part-keyed successors and dropped. Every existing Task
         * becomes exactly one part, so an old database reads through the same model as a new one:
         *
         * - A Task linked to a Package Prompt keeps that Prompt as its part key (and kind `package`).
         * - A local Task is `legacy:<taskId>` unless its Todo's importer says file or folder, which
         *   is how the part knows what made it.
         * - A Task with no import row at all is `legacy`.
         *
         * The migration deliberately invents **no** `review_events.partKey`. Those submissions
         * answered the Task as a whole, and the read model treats a null part as the Task's own
         * history rather than guessing which part a person meant.
         *
         * `assets.partKey` is nullable in SQLite because `ALTER TABLE … ADD COLUMN` is the only way
         * to add it without rebuilding the table, but every row that exists when this finishes has
         * one: the backfill below is exhaustive because step 1 gives every Task a part.
         */
        internal val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()

                // 0. Completion provenance. Every existing row reads as `''` — *not recorded* —
                // because a schema-32 completion was neither automatic nor explicitly signed off
                // under a policy: there was one decision, and the checkbox was it. The first write
                // through either path stamps the word that is true from then on.
                connection.execSQL(
                    "ALTER TABLE `task_state` " +
                        "ADD COLUMN `completionSource` TEXT NOT NULL DEFAULT ''"
                )

                // 1. One part per Task, carrying what made it and which Prompt it came from.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_parts` (" +
                        "`taskId` TEXT NOT NULL, `partKey` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `origin` TEXT NOT NULL, " +
                        "`promptKey` TEXT, `required` INTEGER NOT NULL DEFAULT 1, " +
                        "`present` INTEGER NOT NULL DEFAULT 1, `availability` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`, `partKey`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_parts_taskId_position` " +
                        "ON `task_parts` (`taskId`, `position`)"
                )
                connection.execSQL(
                    "INSERT INTO `task_parts` (" +
                        "`taskId`, `partKey`, `position`, `origin`, `promptKey`, " +
                        "`required`, `present`, `availability`, `updatedAt`) " +
                        "SELECT t.`id`, " +
                        "COALESCE(ppt.`promptKey`, 'legacy:' || t.`id`), " +
                        "0, " +
                        "CASE " +
                        "WHEN ppt.`promptKey` IS NOT NULL THEN 'package' " +
                        "WHEN s.`importerKind` = 'folder-as-task' THEN 'folder' " +
                        "WHEN s.`importerKind` = 'file-as-task' THEN 'file' " +
                        "WHEN s.`importerKind` = 'package' THEN 'package' " +
                        "ELSE 'legacy' END, " +
                        "ppt.`promptKey`, 1, 1, NULL, $now " +
                        "FROM `tasks` t " +
                        "LEFT JOIN `package_prompt_tasks` ppt ON ppt.`taskId` = t.`id` " +
                        "LEFT JOIN `task_imports` ti ON ti.`taskId` = t.`id` " +
                        "LEFT JOIN `sources` s ON s.`id` = ti.`sourceId`"
                )

                // 2. Payloads move from the Task to its one part, then the old table goes.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_part_payloads` (" +
                        "`taskId` TEXT NOT NULL, `partKey` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`, `partKey`), " +
                        "FOREIGN KEY(`taskId`, `partKey`) " +
                        "REFERENCES `task_parts`(`taskId`, `partKey`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "INSERT INTO `task_part_payloads` (" +
                        "`taskId`, `partKey`, `kind`, `schemaVersion`, `payload`, " +
                        "`revision`, `updatedAt`) " +
                        "SELECT tp.`taskId`, p.`partKey`, tp.`kind`, tp.`schemaVersion`, " +
                        "tp.`payload`, tp.`revision`, tp.`updatedAt` " +
                        "FROM `task_payloads` tp " +
                        "JOIN `task_parts` p ON p.`taskId` = tp.`taskId`"
                )
                connection.execSQL("DROP TABLE `task_payloads`")

                // 3. Each part gets a state row. The stored response is empty because a schema-32
                // Task never had a current response outside `review_events`; the payload revision
                // is carried so a body that moves under an answer stays visible.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_part_state` (" +
                        "`taskId` TEXT NOT NULL, `partKey` TEXT NOT NULL, " +
                        "`response` TEXT NOT NULL DEFAULT '', `answeredAt` INTEGER, " +
                        "`revision` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`, `partKey`), " +
                        "FOREIGN KEY(`taskId`, `partKey`) " +
                        "REFERENCES `task_parts`(`taskId`, `partKey`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "INSERT INTO `task_part_state` (" +
                        "`taskId`, `partKey`, `response`, `answeredAt`, `revision`, `updatedAt`) " +
                        "SELECT p.`taskId`, p.`partKey`, '', NULL, pp.`revision`, $now " +
                        "FROM `task_parts` p " +
                        "LEFT JOIN `task_part_payloads` pp ON pp.`taskId` = p.`taskId` " +
                        "AND pp.`partKey` = p.`partKey`"
                )

                // 4. Resource uses move to the part, and the old link table goes.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_part_resources` (" +
                        "`taskId` TEXT NOT NULL, `partKey` TEXT NOT NULL, " +
                        "`resourceId` TEXT NOT NULL, `required` INTEGER NOT NULL DEFAULT 1, " +
                        "PRIMARY KEY(`taskId`, `partKey`, `resourceId`), " +
                        "FOREIGN KEY(`taskId`, `partKey`) " +
                        "REFERENCES `task_parts`(`taskId`, `partKey`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`resourceId`) REFERENCES `package_resources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_part_resources_resourceId` " +
                        "ON `task_part_resources` (`resourceId`)"
                )
                connection.execSQL(
                    "INSERT INTO `task_part_resources` " +
                        "(`taskId`, `partKey`, `resourceId`, `required`) " +
                        "SELECT tr.`taskId`, p.`partKey`, tr.`resourceId`, tr.`required` " +
                        "FROM `task_resources` tr " +
                        "JOIN `task_parts` p ON p.`taskId` = tr.`taskId`"
                )
                connection.execSQL("DROP TABLE `task_resources`")

                // 5. An Asset belongs to one part, so the uniqueness is scoped to it. The old index
                // said "one Asset per file per Task"; the new one says "per part", which lets two
                // parts of one Task ask different questions about the same file.
                connection.execSQL("ALTER TABLE `assets` ADD COLUMN `partKey` TEXT")
                connection.execSQL(
                    "UPDATE `assets` SET `partKey` = " +
                        "(SELECT p.`partKey` FROM `task_parts` p " +
                        "WHERE p.`taskId` = `assets`.`taskId`)"
                )
                connection.execSQL("DROP INDEX IF EXISTS `index_assets_taskId_rootUri_relativePath`")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_assets_taskId_partKey_rootUri_relativePath` " +
                        "ON `assets` (`taskId`, `partKey`, `rootUri`, `relativePath`)"
                )

                // 6. A submission may answer a part. Legacy rows stay null on purpose.
                connection.execSQL("ALTER TABLE `review_events` ADD COLUMN `partKey` TEXT")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_events_taskId_partKey_at` " +
                        "ON `review_events` (`taskId`, `partKey`, `at`)"
                )

                // 7. Structured fields need field identity; viewer-event rows are not reinterpreted.
                connection.execSQL(
                    "ALTER TABLE `review_event_answers` ADD COLUMN `fieldKey` TEXT"
                )

                // 8–9. One Prompt Task becomes one `prompt:<key>` composition. The composite key
                // includes `compositionKey` so a future Section composition may hold the same Prompt
                // while the old Task remains untouched.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `package_compositions` (" +
                        "`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`packageKey` TEXT NOT NULL, `compositionKey` TEXT NOT NULL, " +
                        "`present` INTEGER NOT NULL DEFAULT 1, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION )"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_package_compositions_source_package_composition` " +
                        "ON `package_compositions` (`sourceId`, `packageKey`, `compositionKey`)"
                )
                connection.execSQL(
                    "INSERT INTO `package_compositions` (" +
                        "`taskId`, `sourceId`, `packageKey`, `compositionKey`, " +
                        "`present`, `updatedAt`) " +
                        "SELECT `taskId`, `sourceId`, `packageKey`, " +
                        "'prompt:' || `promptKey`, `present`, $now " +
                        "FROM `package_prompt_tasks`"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `package_composition_prompts` (" +
                        "`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`packageKey` TEXT NOT NULL, `compositionKey` TEXT NOT NULL, " +
                        "`promptKey` TEXT NOT NULL, `present` INTEGER NOT NULL DEFAULT 1, " +
                        "PRIMARY KEY(`taskId`, `promptKey`), " +
                        "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sourceId`, `packageKey`, `compositionKey`) " +
                        "REFERENCES `package_compositions`" +
                        "(`sourceId`, `packageKey`, `compositionKey`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_package_composition_prompts_source_composition_prompt` " +
                        "ON `package_composition_prompts` " +
                        "(`sourceId`, `packageKey`, `compositionKey`, `promptKey`)"
                )
                connection.execSQL(
                    "INSERT INTO `package_composition_prompts` (" +
                        "`taskId`, `sourceId`, `packageKey`, `compositionKey`, " +
                        "`promptKey`, `present`) " +
                        "SELECT ppt.`taskId`, ppt.`sourceId`, ppt.`packageKey`, " +
                        "pc.`compositionKey`, ppt.`promptKey`, ppt.`present` " +
                        "FROM `package_prompt_tasks` ppt " +
                        "JOIN `package_compositions` pc ON pc.`taskId` = ppt.`taskId`"
                )
                connection.execSQL("DROP TABLE `package_prompt_tasks`")

                // 10–11. A history and a Todo each gain one field with a behaviour-preserving
                // default: no part records (read fail-closed), and one question per Task.
                connection.execSQL(
                    "ALTER TABLE `dissolved_task_state` " +
                        "ADD COLUMN `partRecords` TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE `sources` ADD COLUMN `composition` TEXT NOT NULL DEFAULT 'prompt'"
                )

                Log.i("ListeaDatabase", "32 -> 33")
            }
        }

        /** One row of [MIGRATION_30_31]'s backfill, held until every response has been read. */
        private data class BackfilledEvent(
            val reviewEventId: String,
            val ordinal: Int,
            val assetId: String?,
            val type: String,
            val value: String
        )

        /**
         * Reads every stored response before writing anything, then writes the event rows.
         *
         * The same rule [backfillPublicIds] records: a cursor stepping over a table while that table
         * is being updated is asking SQLite for an opinion nobody needs. Asset URIs are cached per
         * Task because a Task's submissions all name the same few Assets.
         */
        private fun backfillReviewEventAnswers(connection: SQLiteConnection) {
            val responses = mutableListOf<Triple<String, String, String>>()
            connection.prepare(
                "SELECT id, taskId, response FROM review_events ORDER BY at, id"
            ).use { row ->
                while (row.step()) {
                    responses += Triple(row.getText(0), row.getText(1), row.getText(2))
                }
            }

            val answers = mutableListOf<BackfilledEvent>()
            val assetsByTask = mutableMapOf<String, Map<String, String>>()
            for ((eventId, taskId, text) in responses) {
                val response = ReviewResponse.parse(text) ?: continue
                val events = response.viewerEvents
                if (events.isEmpty()) continue
                val byUri = assetsByTask.getOrPut(taskId) {
                    val map = mutableMapOf<String, String>()
                    connection.prepare(
                        "SELECT id, sourceUri FROM assets WHERE taskId = ?"
                    ).use { asset ->
                        asset.bindText(1, taskId)
                        while (asset.step()) {
                            val uri = if (asset.isNull(1)) null else asset.getText(1)
                            if (uri != null) map[uri] = asset.getText(0)
                        }
                    }
                    map
                }
                events.forEachIndexed { ordinal, event ->
                    answers += BackfilledEvent(
                        reviewEventId = eventId,
                        ordinal = ordinal,
                        assetId = event.assetUri()?.let { byUri[it] },
                        type = event.type.key,
                        value = event.encodeValue()
                    )
                }
            }
            if (answers.isEmpty()) return

            connection.prepare(
                "INSERT INTO `review_event_answers` " +
                    "(`reviewEventId`, `ordinal`, `assetId`, `type`, `value`) " +
                    "VALUES (?, ?, ?, ?, ?)"
            ).use { write ->
                for (answer in answers) {
                    write.bindText(1, answer.reviewEventId)
                    write.bindLong(2, answer.ordinal.toLong())
                    if (answer.assetId == null) write.bindNull(3)
                    else write.bindText(3, answer.assetId)
                    write.bindText(4, answer.type)
                    write.bindText(5, answer.value)
                    write.step()
                    write.reset()
                }
            }
        }

        /**
         * Gives every Task and Asset its public id, reading everything before writing anything.
         *
         * The same rule [backfillSelections] records: a cursor stepping over a table while that
         * table is being updated is asking SQLite for an opinion nobody needs.
         */
        private fun backfillPublicIds(connection: SQLiteConnection) {
            val now = System.currentTimeMillis()

            val taskCreatedAt = LinkedHashMap<String, Long>()
            connection.prepare("SELECT id, createdAt FROM tasks").use { row ->
                while (row.step()) taskCreatedAt[row.getText(0)] = row.getLong(1)
            }

            val legacyTaskIds = mutableMapOf<String, String>()
            connection.prepare("SELECT taskId, publicId FROM legacy_task_ids").use { row ->
                while (row.step()) legacyTaskIds[row.getText(0)] = row.getText(1)
            }

            val legacyItemIds = mutableMapOf<String, String>()
            connection.prepare("SELECT assetId, publicId FROM legacy_item_ids").use { row ->
                while (row.step()) legacyItemIds[row.getText(0)] = row.getText(1)
            }

            val listIds = mutableMapOf<String, String>()
            connection.prepare(
                "SELECT taskId, publicId FROM list_items WHERE publicId <> '' ORDER BY id"
            ).use { row ->
                while (row.step()) listIds.putIfAbsent(row.getText(0), row.getText(1))
            }

            val assets = mutableListOf<Triple<String, String, String?>>()
            val assetsPerTask = mutableMapOf<String, Int>()
            connection.prepare("SELECT id, taskId, relativePath FROM assets").use { row ->
                while (row.step()) {
                    val taskId = row.getText(1)
                    assets += Triple(row.getText(0), taskId, row.getText(2))
                    assetsPerTask[taskId] = (assetsPerTask[taskId] ?: 0) + 1
                }
            }

            val used = mutableSetOf<String>()

            // Tasks first, so an asset's fallback time prefix can use its Task's createdAt.
            val taskIds = taskCreatedAt.map { (taskId, createdAt) ->
                val preserved = legacyTaskIds[taskId]?.takeIf { it.isNotEmpty() && used.add(it) }
                val publicId = preserved
                    ?: newItemPublicId(taskIdentity(taskId), createdAt)
                used += publicId
                taskId to publicId
            }

            val assetIds = assets.map { (assetId, taskId, relativePath) ->
                val createdAt = taskCreatedAt[taskId] ?: now
                val preserved = legacyItemIds[assetId]?.takeIf { it.isNotEmpty() && used.add(it) }
                val publicId = preserved ?: run {
                    val fromList = if (assetsPerTask[taskId] == 1) {
                        listIds[taskId]?.takeIf { used.add(it) }
                    } else {
                        null
                    }
                    fromList ?: newItemPublicId(assetIdentity(taskId, relativePath), createdAt)
                }
                used += publicId
                assetId to publicId
            }

            connection.prepare("UPDATE tasks SET publicId = ? WHERE id = ?").use { write ->
                for ((taskId, publicId) in taskIds) {
                    write.bindText(1, publicId)
                    write.bindText(2, taskId)
                    write.step()
                    write.reset()
                }
            }
            connection.prepare("UPDATE assets SET publicId = ? WHERE id = ?").use { write ->
                for ((assetId, publicId) in assetIds) {
                    write.bindText(1, publicId)
                    write.bindText(2, assetId)
                    write.step()
                    write.reset()
                }
            }

            check(
                connection.prepare("SELECT COUNT(*) FROM tasks WHERE publicId = ''")
                    .use { it.step(); it.getLong(0) } == 0L &&
                    connection.prepare("SELECT COUNT(*) FROM assets WHERE publicId = ''")
                        .use { it.step(); it.getLong(0) } == 0L
            ) { "MIGRATION_25_26 left a Task or Asset without a public id" }
        }

        /**
         * The built-in, written by [builtInAssetControlRows] — the same statements a database
         * *created* at this version gets, bound the way a migration's connection binds them.
         *
         * One description of those rows with two callers, because they have to be there wherever the
         * tables are, and a migration is only how *one* of the two ways a database comes to exist
         * gets them. See [SeedBuiltInOnCreate] for the other.
         */
        private fun seedBuiltInAssetControl(connection: SQLiteConnection, now: Long) {
            for ((sql, args) in builtInAssetControlRows(now)) {
                runStatement(connection, sql, args)
            }
        }

        /**
         * The two built-in Task Reviews, written the same way: one description, two callers.
         *
         * Shared by `MIGRATION_29_30` and the creation callback, so a fresh install and an upgrade
         * cannot disagree about what `builtin.media.rapid-review@1` means. The asset-control seed is
         * deliberately *not* re-run here: a 23 → 24 migration already wrote it, and a step should
         * describe only what its own release added.
         */
        private fun seedBuiltInTaskReviews(connection: SQLiteConnection, now: Long) {
            for ((sql, args) in builtInTaskReviewRows(now)) {
                runStatement(connection, sql, args)
            }
        }

        /**
         * One statement, bound and stepped.
         *
         * The binding a migration's connection needs rather than the one a DAO's would: a
         * [SQLiteConnection] takes its arguments positionally and by type, and [builtInAssetControlRows]
         * describes its rows as `Any?` so that the seed and the minted templates below can be written
         * once rather than twice.
         */
        private fun runStatement(connection: SQLiteConnection, sql: String, args: List<Any?>) {
            connection.prepare(sql).use { statement ->
                args.forEachIndexed { index, arg ->
                    when (arg) {
                        is Long -> statement.bindLong(index + 1, arg)
                        is Int -> statement.bindLong(index + 1, arg.toLong())
                        else -> statement.bindText(index + 1, arg as String)
                    }
                }
                statement.step()
            }
        }

        /**
         * Every pair's marks, merged into one set.
         *
         * The read is drained before a single write goes out, for the reason [backfill] gives: a
         * cursor stepping over a table while that table is being updated is the kind of thing SQLite
         * is entitled to have an opinion about, and a pair table is thousands of rows, not millions.
         */
        private fun backfillSelections(connection: SQLiteConnection) {
            val merged = mutableListOf<Pair<String, String>>()

            connection.prepare(
                "SELECT assetId, isFavorite, customActions FROM task_asset_state"
            ).use { row ->
                while (row.step()) {
                    val ids = decodeActionIds(row.getText(2)).toMutableSet()
                    if (row.getLong(1) != 0L) ids += Templates.FAVOURITE_BUTTON_ID
                    merged += row.getText(0) to encodeActionIds(ids)
                }
            }

            connection.prepare(
                "UPDATE task_asset_state SET selections = ? WHERE assetId = ?"
            ).use { write ->
                for ((assetId, selections) in merged) {
                    write.bindText(1, selections)
                    write.bindText(2, assetId)
                    write.step()
                    write.reset()
                }
            }
        }

        /**
         * Both `assetControls` columns, from the configuration a Todo asked for to the template it
         * is attached to.
         *
         * **What the old shape could say and the new one cannot, and where it goes.** A configuration
         * named a kind, the built-in control, and that kind's tags — so it could say *images and
         * videos are governed differently*, which is a thing a list of attachments can only say by
         * attaching two templates. So each distinct tag list becomes one template scoped to the kinds
         * that carried it, which is what makes a Todo configured two ways hold two templates rather
         * than one that has to be wrong about a kind.
         *
         * **A group identical to the built-in attaches the built-in.** Decision 11 of
         * `docs/iterations/v0.5.4-plan.md`, and the reason the ordinary upgrade is silent: a Todo
         * that never edited anything, or that edited only one kind, keeps one attachment naming a
         * template whose buttons are the ones it had. "Identical" is the encoded form of the whole
         * button list including `★`, so it is decided by comparing strings rather than by a guess
         * about which fields matter.
         *
         * **A Todo whose two kinds differ therefore produces two templates**, in the column's own
         * order — image before video, because that is the order the old encoder wrote kinds in and
         * the order first-match resolution reads. This is the shape change
         * [docs/iterations/v0.5.4.md] §The scope describes, arriving on rows that predate it.
         *
         * Both tables are read before either is written, so a `task_imports` row is rewritten from
         * what its Todo held and not from what this step has just written to it.
         */
        private fun rewriteAssetControls(connection: SQLiteConnection, now: Long) {
            val seed = Templates.AssetControlSeed
            val seedButtons = encodeTemplateButtons(seed.buttons)
            // The `★` a minted template carries, taken from the seed rather than rebuilt: the two
            // have to be the same button for a mark on one to mean a mark on the other.
            val star = seed.buttons.firstOrNull { it.id == Templates.FAVOURITE_BUTTON_ID }
            val minted = mutableMapOf<MintedTemplate, TemplateVersionRef>()

            /**
             * One row's configuration, as the attachment string that replaces it.
             *
             * The groups keep the kinds' declaration order because a `LinkedHashMap` does, and the
             * kinds are folded in as they are met: a tag list shared by both kinds is one template
             * governing both, not two templates that happen to say the same thing.
             */
            fun attachmentsFor(encoded: String, todoName: String): String {
                val groups = LinkedHashMap<String, Pair<Set<AssetKind>, List<TemplateButton>>>()

                for ((kind, tags) in decodeLegacyAssetControls(encoded)) {
                    val buttons = listOfNotNull(star) + tags
                    val key = encodeTemplateButtons(buttons)
                    // Re-putting a key `LinkedHashMap` already holds updates it in place, so a tag
                    // list both kinds carry stays where it was first met rather than moving to the
                    // end of the column.
                    val kinds = groups[key]?.first.orEmpty() + kind
                    groups[key] = kinds to buttons
                }

                return groups.values.joinToString("\n") { (kinds, buttons) ->
                    val ref = if (encodeTemplateButtons(buttons) == seedButtons) {
                        seed.ref
                    } else {
                        val wanted = MintedTemplate(
                            name = todoName,
                            kinds = kinds,
                            buttons = buttons
                        )
                        minted.getOrPut(wanted) {
                            insertMintedTemplate(connection, wanted, now)
                        }
                    }
                    "${ref.templateId.value}\t${ref.version}"
                }
            }

            val sourceRewrites = mutableListOf<Pair<String, String>>()
            connection.prepare(
                "SELECT id, displayName, assetControls FROM sources WHERE assetControls <> ''"
            ).use { row ->
                while (row.step()) {
                    sourceRewrites += row.getText(0) to attachmentsFor(row.getText(2), row.getText(1))
                }
            }

            val importRewrites = mutableListOf<Pair<String, String>>()
            connection.prepare(
                // The Todo's name rather than the Task's, because a minted template is named for
                // the thing that chose the configuration — and a Task's own name is its file. The
                // Task's is the fallback for a row whose Source is gone, which is a row nothing can
                // name better.
                "SELECT i.taskId, COALESCE(s.displayName, i.displayName), i.assetControls " +
                    "FROM task_imports i LEFT JOIN sources s ON s.id = i.sourceId " +
                    "WHERE i.assetControls <> ''"
            ).use { row ->
                while (row.step()) {
                    importRewrites += row.getText(0) to attachmentsFor(row.getText(2), row.getText(1))
                }
            }

            connection.prepare("UPDATE sources SET assetControls = ? WHERE id = ?").use { write ->
                for ((id, attachments) in sourceRewrites) {
                    write.bindText(1, attachments)
                    write.bindText(2, id)
                    write.step()
                    write.reset()
                }
            }
            connection.prepare("UPDATE task_imports SET assetControls = ? WHERE taskId = ?")
                .use { write ->
                    for ((taskId, attachments) in importRewrites) {
                        write.bindText(1, attachments)
                        write.bindText(2, taskId)
                        write.step()
                        write.reset()
                    }
                }
        }

        /**
         * A template this step mints, as the whole of what one would say.
         *
         * The key rather than the id, because two rows that agree on all three fields are asking for
         * one template: a Todo's Tasks each carry the Todo's own configuration, so a key that left
         * the name out would mint one template per import, and a key that left the buttons out would
         * merge two that differ. Nothing distinguishes two identical templates from one, so sharing
         * is invisible — and it is what keeps the picker from listing a Todo twenty times.
         */
        private data class MintedTemplate(
            val name: String,
            val kinds: Set<AssetKind>,
            val buttons: List<TemplateButton>
        )

        /**
         * Writes one minted template, and answers the reference to it: version 1, because a template
         * this step creates has no history to append to.
         *
         * Scoped to the kinds that carried the tag list and to nothing else, so it governs exactly
         * the files the configuration it replaces governed.
         */
        private fun insertMintedTemplate(
            connection: SQLiteConnection,
            template: MintedTemplate,
            now: Long
        ): TemplateVersionRef {
            val id = UUID.randomUUID().toString()
            runStatement(
                connection,
                "INSERT INTO templates (id, name, createdAt) VALUES (?, ?, ?)",
                listOf(id, template.name, now)
            )
            runStatement(
                connection,
                "INSERT INTO template_versions " +
                    "(templateId, version, scope, buttons, buttonsVersion, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                listOf(
                    id,
                    1,
                    encodeTemplateScope(TemplateScope.ofKinds(template.kinds)),
                    encodeTemplateButtons(template.buttons),
                    // `1`, for the reason `MIGRATION_21_22` gives for its own: a migration writes the
                    // version it shipped with. Unlike [builtInAssetControlRows], which the creation
                    // callback shares and which therefore has to mean "the version this build writes",
                    // this function has one caller and it is a step that must keep describing what it
                    // wrote even if a later encoding adds a version.
                    1,
                    now
                )
            )
            return TemplateVersionRef(TemplateId(id), 1)
        }

        /**
         * One row's per-kind configuration, as the release before this one wrote it.
         *
         * `kind \t templateId \t version \t tags`, one line per kind, the tags joined by `|` as
         * `id:name:recordValue`. Frozen here rather than shared with a runtime reader, because the
         * two have different jobs: this reads the whole thing once, to keep what it said, while
         * [decodeTemplateAttachments] salvages only the *reference* out of a row like this at
         * runtime — the fallback for a database this step has already run on.
         *
         * **The template id and version are not read**, deliberately. Every configuration ever
         * written named the one control this app has ever shipped, so what a row adds to the
         * built-in is its tags and its kinds and nothing else. A row naming a control this build has
         * never heard of is therefore read as the built-in's, which is the reading the old runtime
         * gave it too — the fallback `AppSettings.controlFor` applied before this release replaced it.
         *
         * As tolerant as its siblings: a kind this build does not know costs its own line, a tag with
         * an empty id or one carrying a comma — what a record stores ids with — is dropped, and a
         * name or value that is absent stays empty rather than being invented.
         */
        private fun decodeLegacyAssetControls(
            text: String
        ): List<Pair<AssetKind, List<TemplateButton>>> =
            text.split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val fields = line.split('\t')
                val kind = AssetKind.fromKey(fields.getOrNull(0)?.trim()) ?: return@mapNotNull null
                kind to fields.getOrNull(3).orEmpty().split('|').mapNotNull { tag ->
                    val parts = tag.split(':')
                    val id = parts.getOrNull(0)?.trim().orEmpty()
                    if (id.isEmpty() || id.contains(',')) return@mapNotNull null
                    TemplateButton(
                        id = id,
                        name = parts.getOrNull(1).orEmpty(),
                        type = ButtonType.TAG,
                        recordValue = parts.getOrNull(2).orEmpty()
                    )
                }
            }

        /** The unfinished memberships of `lists.id`, as both of the statements above ask for them. */
        private const val UNFINISHED_ITEMS_OF_LIST =
            "SELECT 1 FROM list_items i LEFT JOIN task_state ts ON ts.taskId = i.taskId " +
                "WHERE i.listId = lists.id AND COALESCE(ts.isCompleted, 0) = 0"

        /**
         * The four tables V14 introduces, in the shape the entities declare and `14.json` records.
         *
         * Written out one statement at a time, for the same reason [createTaskTables] is: a column
         * that changes in an entity is a line that has to change here, and the two can be diffed
         * against the exported schema by eye rather than by decoding a loop.
         */
        private fun createImportTables(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS sources (" +
                    "id TEXT NOT NULL, rootUri TEXT NOT NULL, relativePath TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, importerKind TEXT NOT NULL, " +
                    "importerVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                    "lastImportAt INTEGER, lastImportStatus TEXT, PRIMARY KEY(id))"
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sources_rootUri_relativePath " +
                    "ON sources (rootUri, relativePath)"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS task_imports (" +
                    "taskId TEXT NOT NULL, sourceId TEXT NOT NULL, groupPath TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, importedAt INTEGER NOT NULL, " +
                    "lastSeenAt INTEGER NOT NULL, PRIMARY KEY(taskId), " +
                    "FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(sourceId) REFERENCES sources(id) " +
                    "ON UPDATE NO ACTION ON DELETE NO ACTION)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_task_imports_sourceId_groupPath " +
                    "ON task_imports (sourceId, groupPath)"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_source_tasks (" +
                    "taskId TEXT NOT NULL, PRIMARY KEY(taskId), " +
                    "FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_task_ids (" +
                    "taskId TEXT NOT NULL, publicId TEXT NOT NULL, PRIMARY KEY(taskId), " +
                    "FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
        }

        /**
         * The six tables V13 introduces, in the shape the entities declare and `13.json` records.
         *
         * Written out one statement at a time rather than driven from a list, so a column that
         * changes in an entity is a line that has to change here — and so this can be diffed
         * against the exported schema by eye rather than by decoding a loop.
         */
        private fun createTaskTables(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS tasks (" +
                    "id TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS assets (" +
                    "id TEXT NOT NULL, taskId TEXT NOT NULL, sortOrder INTEGER NOT NULL, " +
                    "rootUri TEXT, relativePath TEXT, sourceMissing INTEGER NOT NULL, " +
                    "PRIMARY KEY(id), FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_assets_taskId ON assets (taskId)"
            )
            // One file has one Asset, which is what keeps two Lists over the same folder
            // resolving to the same Task rather than minting a rival one.
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_assets_rootUri_relativePath " +
                    "ON assets (rootUri, relativePath)"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS task_state (" +
                    "taskId TEXT NOT NULL, isCompleted INTEGER NOT NULL, " +
                    "isFavorite INTEGER NOT NULL, customActions TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId), " +
                    "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            // No foreign key to `tasks`, on purpose: history is not owned by the thing it is
            // about. See [ReviewEventEntity].
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS review_events (" +
                    "id TEXT NOT NULL, taskId TEXT NOT NULL, at INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, templateId TEXT NOT NULL, " +
                    "templateVersion INTEGER NOT NULL, response TEXT NOT NULL, " +
                    "contextKind TEXT, contextRef TEXT, PRIMARY KEY(id))"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_review_events_taskId_at " +
                    "ON review_events (taskId, at)"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_item_ids (" +
                    "assetId TEXT NOT NULL, publicId TEXT NOT NULL, PRIMARY KEY(assetId))"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_manual_tasks (" +
                    "taskId TEXT NOT NULL, PRIMARY KEY(taskId))"
            )
        }

        /**
         * Copies `list_items` into the same shape with a `taskId` that has to be there.
         *
         * SQLite cannot add a `NOT NULL` column to an existing table without giving it a default,
         * and has never been able to tighten a nullable one afterwards — and `minSdk` is 24, so
         * nothing newer can be assumed. `NOT NULL DEFAULT ''` would make the empty string a
         * sentinel meaning "broken row", which is worse than the copy. So the column arrives the
         * way V11 and V12 already brought theirs here: by rebuilding the table.
         *
         * `id` is carried explicitly rather than re-assigned, so autoincrement row ids survive —
         * and with them resume positions, round snapshots and webhook queue scoping, all of which
         * speak in membership ids and none of which are Task identity.
         */
        private fun rebuildListItemsWithTaskId(connection: SQLiteConnection) {
            val carried = listOf(
                "id", "publicId", "listId", "title", "isCompleted", "sortOrder", "createdAt",
                "sourceUri", "sourceRelativePath", "rootRelativePath", "sourceMissing",
                "sourceSizeBytes", "sourceModifiedAt", "isFavorite", "customActions"
            )
            val names = carried.joinToString(", ")
            val fromOldTable = carried.joinToString(", ") { "i.$it" }

            connection.execSQL(
                "CREATE TABLE list_items_v13 (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "publicId TEXT NOT NULL, listId INTEGER NOT NULL, taskId TEXT NOT NULL, " +
                    "title TEXT NOT NULL, isCompleted INTEGER NOT NULL, " +
                    "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                    "sourceUri TEXT, sourceRelativePath TEXT, rootRelativePath TEXT, " +
                    "sourceMissing INTEGER NOT NULL, sourceSizeBytes INTEGER, " +
                    "sourceModifiedAt INTEGER, isFavorite INTEGER NOT NULL, " +
                    "customActions TEXT NOT NULL, " +
                    "FOREIGN KEY(listId) REFERENCES lists(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(taskId) REFERENCES tasks(id) " +
                    "ON UPDATE NO ACTION ON DELETE NO ACTION)"
            )
            connection.execSQL(
                "INSERT INTO list_items_v13 ($names, taskId) " +
                    "SELECT $fromOldTable, m.taskId FROM list_items i " +
                    "JOIN list_items_v13_tasks m ON m.itemId = i.id"
            )
            connection.execSQL("DROP TABLE list_items")
            connection.execSQL("ALTER TABLE list_items_v13 RENAME TO list_items")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_list_items_listId ON list_items (listId)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
                    "ON list_items (rootRelativePath)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_list_items_taskId ON list_items (taskId)"
            )
            // The scratch mapping has done its job; nothing outside this migration names it. It is
            // dropped last so a failure anywhere above leaves the transaction with something to
            // roll back to rather than a half-resolved table.
            connection.execSQL("DROP TABLE list_items_v13_tasks")
        }

        private fun insertTask(connection: SQLiteConnection, id: String, createdAt: Long) {
            connection.prepare("INSERT INTO tasks (id, createdAt) VALUES (?, ?)").use { row ->
                row.bindText(1, id)
                row.bindLong(2, createdAt)
                row.step()
            }
        }

        private fun insertAsset(
            connection: SQLiteConnection,
            id: String,
            taskId: String,
            rootUri: String,
            relativePath: String,
            sourceMissing: Boolean
        ) {
            connection.prepare(
                "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) " +
                    "VALUES (?, ?, 0, ?, ?, ?)"
            ).use { row ->
                row.bindText(1, id)
                row.bindText(2, taskId)
                row.bindText(3, rootUri)
                row.bindText(4, relativePath)
                row.bindLong(5, if (sourceMissing) 1 else 0)
                row.step()
            }
        }

        private fun insertTaskState(
            connection: SQLiteConnection,
            taskId: String,
            isCompleted: Boolean,
            isFavorite: Boolean,
            customActions: String,
            updatedAt: Long
        ) {
            connection.prepare(
                "INSERT INTO task_state " +
                    "(taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                    "VALUES (?, ?, ?, ?, ?)"
            ).use { row ->
                row.bindText(1, taskId)
                row.bindLong(2, if (isCompleted) 1 else 0)
                row.bindLong(3, if (isFavorite) 1 else 0)
                row.bindText(4, customActions)
                row.bindLong(5, updatedAt)
                row.step()
            }
        }

        private fun insertLegacyItemId(
            connection: SQLiteConnection,
            assetId: String,
            publicId: String
        ) {
            connection.prepare(
                "INSERT INTO legacy_item_ids (assetId, publicId) VALUES (?, ?)"
            ).use { row ->
                row.bindText(1, assetId)
                row.bindText(2, publicId)
                row.step()
            }
        }

        private fun insertLegacyManualTask(connection: SQLiteConnection, taskId: String) {
            connection.prepare(
                "INSERT INTO legacy_manual_tasks (taskId) VALUES (?)"
            ).use { row ->
                row.bindText(1, taskId)
                row.step()
            }
        }

        /**
         * Copies one decision-carrying table into the same shape with `custom1`/`custom2` folded
         * into a `customActions` id set.
         *
         * [carried] is every column that survives unchanged, named explicitly rather than taken
         * from `SELECT *`: the two tables differ, and a positional copy that silently kept
         * working after a column moved is not worth the brevity.
         *
         * The fold is done in SQL rather than row by row because it is four cases and no reads
         * are needed to decide between them. The order inside the string matches what
         * `encodeActionIds` produces, so a row written by this migration is byte-identical to the
         * same row written by the app.
         */
        private fun rebuildWithActionIds(
            connection: SQLiteConnection,
            table: String,
            columns: String,
            carried: String,
            indices: List<String>
        ) {
            val staging = "${table}_v12"

            connection.execSQL("DROP TABLE IF EXISTS $staging")
            connection.execSQL("CREATE TABLE $staging ($columns)")
            connection.execSQL(
                "INSERT INTO $staging ($carried, customActions) " +
                    "SELECT $carried, CASE " +
                    "WHEN custom1 = 1 AND custom2 = 1 THEN 'custom1,custom2' " +
                    "WHEN custom1 = 1 THEN 'custom1' " +
                    "WHEN custom2 = 1 THEN 'custom2' " +
                    "ELSE '' END " +
                    "FROM $table"
            )
            connection.execSQL("DROP TABLE $table")
            connection.execSQL("ALTER TABLE $staging RENAME TO $table")
            indices.forEach(connection::execSQL)
        }

        /**
         * The built-in asset control as statements and values: the two rows that make it a template
         * rather than a constant.
         *
         * The values come from [Templates.AssetControlSeed] and are written through the encoders
         * rather than spelled out in SQL, so the row a device gets is the row the app would write —
         * the same argument the test suite makes for pinning the payload formats.
         *
         * `INSERT OR IGNORE` rather than `INSERT`, so a database that already holds these two rows is
         * left alone instead of refusing to open. That is what lets one description serve both
         * callers, and it is the only reason it can: a migration runs once per database, but a
         * creation callback is not the only thing that might have written here first.
         */
        internal fun builtInAssetControlRows(now: Long): List<Pair<String, List<Any?>>> {
            val seed = Templates.AssetControlSeed
            return listOf(
                "INSERT OR IGNORE INTO templates (id, name, createdAt) VALUES (?, ?, ?)" to
                    listOf(seed.ref.templateId.value, seed.name, now),
                "INSERT OR IGNORE INTO template_versions " +
                    "(templateId, version, scope, buttons, buttonsVersion, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?)" to
                    listOf(
                        seed.ref.templateId.value,
                        seed.ref.version,
                        encodeTemplateScope(seed.scope),
                        encodeTemplateButtons(seed.buttons),
                        buttonsVersionFor(seed.buttons),
                        now
                    )
            )
        }

        /**
         * The two built-in Task Reviews as statements and values, through the same encoders.
         *
         * A row is a row whichever kind it is: the lineage row plus one immutable version carrying
         * `kind`, the definition payload and its version beside it. `INSERT OR IGNORE` for the
         * reason [builtInAssetControlRows] gives — the migration and the creation callback must not
         * refuse one another's work.
         */
        internal fun builtInTaskReviewRows(now: Long): List<Pair<String, List<Any?>>> =
            Templates.StoredTaskReviews.flatMap { seed ->
                val definition = seed.taskReview ?: return@flatMap emptyList()
                listOf(
                    "INSERT OR IGNORE INTO templates (id, name, createdAt) VALUES (?, ?, ?)" to
                        listOf(seed.ref.templateId.value, seed.name, now),
                    "INSERT OR IGNORE INTO template_versions " +
                        "(templateId, version, scope, buttons, buttonsVersion, createdAt, " +
                        "kind, definition, definitionVersion) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" to
                        listOf(
                            seed.ref.templateId.value,
                            seed.ref.version,
                            encodeTemplateScope(seed.scope),
                            encodeTemplateButtons(seed.buttons),
                            buttonsVersionFor(seed.buttons),
                            now,
                            StoredTemplateKind.TASK_REVIEW,
                            encodeTaskReviewDefinition(definition),
                            TASK_REVIEW_DEFINITION_VERSION
                        )
                )
            }

        /**
         * Keeps *every install has the built-in* true, rather than trusting two callers to make it so.
         *
         * There are two ways a database comes to exist at this version and only one of them is a
         * migration. `MIGRATION_23_24` seeds a database that was already at 23; a fresh install never
         * runs a migration at all, because Room *creates* every table at the version the app ships —
         * so a built-in that only the migration wrote would be missing for exactly the people who had
         * never upgraded. [onCreate] is that second writer.
         *
         * **[onOpen] is the third, and it is why this can be a callback at all.** The property was
         * found broken in the field twice: once as a database at 24 with both tables and no rows,
         * created by a build that predated the callback; and once, during the v0.8.4 acceptance walk,
         * as a database that held the built-ins of an earlier release but not the ones this release
         * added. Neither state is reachable by a migration — the first because no step will ever
         * visit it again, the second because the release's own step ran before the new built-ins
         * existed in [Templates.StoredTaskReviews]. Two writers that each have to remember is not an
         * invariant, it is a convention; an open that fills in what is missing is the invariant.
         *
         * **What it repairs is a row, keyed by id and version, and the writes are `INSERT OR
         * IGNORE`.** An existing row is never overwritten, so a template the user renamed keeps its
         * name and a version they edited keeps its contents. Nothing in the app deletes a template —
         * there is no delete, and `TemplateStore` has no method that could become one — so an absent
         * built-in can only mean this database was written by a build that did not have it. That is
         * what makes the repair safe, and it is the thing to re-read on the day a template can be
         * deleted: at that point a deleted built-in would be resurrected here, and the repair has to
         * narrow to something the user did not choose.
         */
        internal val SeedBuiltIn: RoomDatabase.Callback =
            object : RoomDatabase.Callback() {

                override fun onCreate(db: SupportSQLiteDatabase) {
                    writeBuiltIn(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    if (countTemplates(db) == 0L) writeBuiltIn(db)
                    repairMissingTaskReviews(db)
                    repairEarlierNames(db)
                }

                /**
                 * Adds the built-in Task Reviews a database does not already hold, version by
                 * version.
                 *
                 * This is the upgrade half of the seed. `MIGRATION_29_30` and `MIGRATION_31_32`
                 * each seeded the built-ins *their* release shipped, which was the whole list at the
                 * time; a release that adds a built-in to the list gets no such step, and without
                 * this a person who upgraded would not see the new template while a fresh install
                 * would. The round that added `builtin.structured.single-choice@2` and
                 * `builtin.structured.form@1` is exactly that case.
                 *
                 * It is not a second copy of [writeBuiltIn] because it does not rewrite the
                 * asset-control lineage: a 23 → 24 database still holds that row, and the repair's
                 * subject is the Task Reviews that the same lineage keeps growing. Running it on
                 * every open is a handful of ignored inserts, and it is the reason an install
                 * cannot be left one release's built-ins behind.
                 */
                private fun repairMissingTaskReviews(db: SupportSQLiteDatabase) {
                    val now = System.currentTimeMillis()
                    for ((sql, args) in builtInTaskReviewRows(now)) {
                        db.execSQL(sql, args.toTypedArray())
                    }
                }

                /**
                 * Moves the built-in Task Reviews onto the vocabulary that names them *headless*.
                 *
                 * The update is conditional on the old name, so it is a correction of an
                 * untouched seed rather than a rename of somebody's template: a row already
                 * carrying the new name is left alone, and so is one the user renamed to anything
                 * else. It runs on every open and is a no-op after the first.
                 */
                private fun repairEarlierNames(db: SupportSQLiteDatabase) {
                    for ((id, oldName, newName) in Templates.EarlierTaskReviewNames) {
                        db.execSQL(
                            "UPDATE templates SET name = ? WHERE id = ? AND name = ?",
                            arrayOf(newName, id, oldName)
                        )
                    }
                }

                private fun writeBuiltIn(db: SupportSQLiteDatabase) {
                    val now = System.currentTimeMillis()
                    for ((sql, args) in
                        builtInAssetControlRows(now) + builtInTaskReviewRows(now)) {
                        db.execSQL(sql, args.toTypedArray())
                    }
                }

                private fun countTemplates(db: SupportSQLiteDatabase): Long =
                    db.query("SELECT COUNT(*) FROM templates").use { row ->
                        if (row.moveToFirst()) row.getLong(0) else 0L
                    }
            }

        @Volatile
        private var instance: ListeaDatabase? = null

        fun get(context: Context): ListeaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ListeaDatabase::class.java,
                    "listea.db"
                ).addCallback(SeedBuiltIn).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                    MIGRATION_29_30,
                    MIGRATION_30_31,
                    MIGRATION_31_32,
                    MIGRATION_32_33
                )
                    .build()
                    .also { instance = it }
            }
    }
}
