package com.example.listea.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.util.UUID

@Database(
    entities = [
        ListEntity::class,
        ListItemEntity::class,
        WebhookRecordEntity::class,
        FileReviewStateEntity::class,
        TaskEntity::class,
        AssetEntity::class,
        TaskStateEntity::class,
        ReviewEventEntity::class,
        LegacyItemIdEntity::class,
        LegacyManualTaskEntity::class,
        SourceEntity::class,
        TaskImportEntity::class,
        LegacySourceTaskEntity::class,
        LegacyTaskIdEntity::class
    ],
    version = 14,
    exportSchema = true
)
abstract class ListeaDatabase : RoomDatabase() {

    abstract fun listsDao(): ListsDao

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
         * [DefaultCustomActions] is configured with. That is what makes this upgrade invisible:
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

        @Volatile
        private var instance: ListeaDatabase? = null

        fun get(context: Context): ListeaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ListeaDatabase::class.java,
                    "listea.db"
                ).addMigrations(
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
                    MIGRATION_13_14
                )
                    .build()
                    .also { instance = it }
            }
    }
}
