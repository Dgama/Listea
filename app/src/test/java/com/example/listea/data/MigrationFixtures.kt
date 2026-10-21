package com.example.listea.data

/**
 * What the two migration fixtures that do not write their old shape out by hand have in common.
 *
 * Most of the migration tests here restate an old schema as SQL, which has the virtue of being
 * literally what the app shipped and the vice of being DDL nobody maintains — it drifts from the
 * entities the first time one of them moves. Two of them cannot do that any more, for opposite
 * reasons. [MigrationV22ToV23Test] is about a step that changed no table, so the older database is
 * the newer one and copying four hundred lines of DDL to reproduce it would be pretending to a
 * difference that does not exist. [MigrationV23ToV24Test] is about the newest step, so the older
 * database is the newer one with that step's own additions taken back off. Both therefore build
 * their fixture at the version the entities declare and then subtract.
 *
 * The subtraction is the load-bearing part and it is why this is shared rather than copied. It has
 * to be exactly right in both directions: a column left behind makes the step fail with
 * `duplicate column name`, and a table left behind makes the fixture a database that never existed
 * — and a fixture that fails to be a v23 is a test that passes for the wrong reason. What is
 * shared here is the mechanism; the *claim* that a fixture really is the version it says it is
 * belongs to each file, and both of them assert it.
 *
 * **This grows with the chain.** The next release that adds a migration adds its own subtraction
 * here beside this one, because a fixture has to come back to whatever version the test is about
 * and no further.
 */

/**
 * The tables only a step *newer* than the fixtures' own creates.
 *
 * A fixture is opened at the version the app ships, so any test that names the tables it expects
 * sees every later release's there too. Several of them assert the table list in full — "this step
 * added one table and nothing else appeared" — and that assertion is worth keeping exact rather
 * than replacing with a count, because a rebuilt copy under a different name is precisely what it
 * is there to catch. Subtracting these keeps each one about the step it was written for, and keeps
 * the list that has to be maintained as the chain grows in a single place.
 */
internal val TablesFromLaterSteps = listOf("templates", "template_versions", "schema_backfills")

/**
 * The columns a step *newer* than the fixtures' own appends to tables that already existed.
 *
 * The same problem as [TablesFromLaterSteps] one level down. A fixture is opened at the version the
 * app ships, so `migrate()` runs every later step as well, and a table that predates the step under
 * test comes out with a column on the end of it that this step never wrote. Tests that count a
 * table's columns, or that compare its stored SQL before and after, have to allow for exactly these
 * and no more — named by table and by column, rather than as a bare `+ 1`, so that a test can still
 * tell a later step's column from one this step added.
 *
 * **`selections` on three tables, and it is one release's doing.** `MIGRATION_23_24` appends it to
 * each table that holds a set of button ids, because `★` stopped being a boolean column of its own
 * and became an id in the set — see [AssetStateEntity.selections], which is the argument for all
 * three being `ALTER`s rather than a rebuild.
 *
 * **`exportKind` and `kind`, and it is the next release's.** `MIGRATION_24_25` appends a stored
 * export choice to the two tables that hold one and the mechanism to the history that records it.
 * Every `ALTER` here rewrites the table's stored text, which is the whole reason a column is named
 * in this list rather than a table: the fixture's own SQL and the migrated database's differ for a
 * reason that is not the step under test.
 */
internal val ColumnsFromLaterSteps = mapOf(
    "task_asset_state" to setOf("selections"),
    "list_items" to setOf("selections"),
    "file_review_state" to setOf("selections"),
    // The 24 → 25 step's export choice, then the 26 → 27 step's Todo address. A Set because more
    // than one release appends to `sources` and `webhook_history`, and the fixture has to subtract
    // all of them to come back to the version under test.
    "sources" to setOf("exportKind", "webhookUrl", "webhookFromDefault"),
    "lists" to setOf("exportKind"),
    "webhook_history" to setOf("kind", "webhookUrl", "roundId"),
    // The newest step's wire identity, appended to the two rows that leave the device.
    "tasks" to setOf("publicId"),
    "assets" to setOf("publicId")
)

/** One statement, against a database being built or wound back. */
internal fun ListeaDatabase.exec(sql: String) {
    openHelper.writableDatabase.execSQL(sql)
}

/**
 * Takes everything `MIGRATION_23_24` adds back off, leaving a database in v23's shape.
 *
 * The two tables go outright, which is what the step's `CREATE TABLE IF NOT EXISTS` makes possible
 * in reverse. The three columns cannot: the step appends each with an `ALTER`, and taking a column
 * off again needs `DROP COLUMN`, which needs SQLite 3.35 while this app ships minSdk 24 — the same
 * constraint [AssetStateEntity.selections] records about the two columns it succeeds, and the reason
 * all three are rebuilt rather than altered. **A fixture gets a rebuild and not a `DROP COLUMN` even
 * though the fixture's SQLite is the host's and would take one**: a v24 fixture has to come back to
 * v23 in the same way the v23 database became a v24, or the subtraction is testing a shape this app
 * cannot produce. That the rebuild also happens to be the only form that works is not the reason.
 *
 * A rebuilt copy keeps the primary key, the foreign keys and the indices, because Room validates
 * those too: a table with the right column names and no cascade is a table the next migration test
 * fails on for a reason that has nothing to do with what it was testing. The rows come across, since
 * a fixture's rows are what its test is about and a subtraction that emptied the table would leave
 * the step nothing to backfill.
 */
internal fun ListeaDatabase.subtractV24Additions() {
    exec("DROP TABLE IF EXISTS template_versions")
    exec("DROP TABLE IF EXISTS templates")

    exec(
        "CREATE TABLE list_items_v23 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`publicId` TEXT NOT NULL, `listId` INTEGER NOT NULL, `taskId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `sourceUri` TEXT, `sourceRelativePath` TEXT, " +
            "`rootRelativePath` TEXT, `sourceMissing` INTEGER NOT NULL, `sourceSizeBytes` INTEGER, " +
            "`sourceModifiedAt` INTEGER, `isFavorite` INTEGER NOT NULL, " +
            "`customActions` TEXT NOT NULL, " +
            "FOREIGN KEY(`listId`) REFERENCES `lists`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION)"
    )
    exec(
        "INSERT INTO list_items_v23 (id, publicId, listId, taskId, title, isCompleted, sortOrder, " +
            "createdAt, sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
            "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions) " +
            "SELECT id, publicId, listId, taskId, title, isCompleted, sortOrder, createdAt, " +
            "sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, sourceSizeBytes, " +
            "sourceModifiedAt, isFavorite, customActions FROM list_items"
    )
    exec("DROP TABLE list_items")
    exec("ALTER TABLE list_items_v23 RENAME TO list_items")
    exec("CREATE INDEX IF NOT EXISTS index_list_items_listId ON list_items (listId)")
    exec(
        "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
            "ON list_items (rootRelativePath)"
    )
    exec("CREATE INDEX IF NOT EXISTS index_list_items_taskId ON list_items (taskId)")

    exec(
        "CREATE TABLE file_review_state_v23 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`publicId` TEXT NOT NULL, `rootUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
            "`isCompleted` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
            "`customActions` TEXT NOT NULL, `sourceMissing` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"
    )
    exec(
        "INSERT INTO file_review_state_v23 (id, publicId, rootUri, relativePath, isCompleted, " +
            "isFavorite, customActions, sourceMissing, updatedAt) " +
            "SELECT id, publicId, rootUri, relativePath, isCompleted, isFavorite, customActions, " +
            "sourceMissing, updatedAt FROM file_review_state"
    )
    exec("DROP TABLE file_review_state")
    exec("ALTER TABLE file_review_state_v23 RENAME TO file_review_state")
    exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_file_review_state_rootUri_relativePath " +
            "ON file_review_state (rootUri, relativePath)"
    )

    exec(
        "CREATE TABLE task_asset_state_v23 (`assetId` TEXT NOT NULL, " +
            "`isFavorite` INTEGER NOT NULL, `customActions` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`assetId`), " +
            "FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    exec(
        "INSERT INTO task_asset_state_v23 (assetId, isFavorite, customActions, updatedAt) " +
            "SELECT assetId, isFavorite, customActions, updatedAt FROM task_asset_state"
    )
    exec("DROP TABLE task_asset_state")
    exec("ALTER TABLE task_asset_state_v23 RENAME TO task_asset_state")

    // And the step above this one, first, because the fixture has to come back to v23 and not to
    // v24: a database left at v24 is one where `MIGRATION_24_25` has a column to add that is
    // already there, and the step under test fails with `duplicate column name` for a reason that
    // belongs to this function.
    subtractV25Additions()
}

/**
 * Takes everything `MIGRATION_24_25` adds back off, leaving a database in v24's shape.
 *
 * The three tables are **rebuilt rather than altered**, which is [subtractV24Additions]'s rule and
 * for the same reason: taking a column off needs `DROP COLUMN`, which needs SQLite 3.35 while this
 * app ships minSdk 24, so a fixture would be testing a shape this app cannot produce. The DDL below
 * is transcribed from `app/schemas/com.example.listea.data.ListeaDatabase/24.json` — the schema Room
 * itself exported at v24 — rather than written from the entities, because the entities are what have
 * *moved* by the time anyone reads this; the golden column lists in the test above are what checks
 * the transcription.
 *
 * **`legacy_alter_table` is on, and here it is load-bearing.** `list_items` holds a foreign key to
 * `lists` and `task_imports` holds one to `sources`. With the pragma off, an `ALTER TABLE … RENAME`
 * re-parses the whole schema and rewrites those clauses to point at the temporary names, so the
 * rebuilt tables would come back with nothing referring to them and Room would reject the fixture
 * for a reason that has nothing to do with what this step does. `foreign_keys` is off for the same
 * stretch, since the ordinary order is *drop the old table, then rename the new one into its place*
 * and the drop is of a table something still points at.
 */
internal fun ListeaDatabase.subtractV25Additions() {
    // And the step above this one, first: the fixture has to come back to v24, not to v25. A
    // database left at v25 already holds the column `MIGRATION_25_26` would add.
    subtractV26Additions()

    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- sources: no exportKind --------------------------------------------------------------
    exec(
        "CREATE TABLE sources_v24 (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `importerVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `keepTree` INTEGER NOT NULL, " +
            "`keepStateOnDelete` INTEGER NOT NULL, `contentPolicy` TEXT NOT NULL, " +
            "`followUpstream` INTEGER NOT NULL, `assetControls` TEXT NOT NULL, " +
            "`lastImportAt` INTEGER, `lastImportStatus` TEXT, PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO sources_v24 (id, rootUri, relativePath, displayName, importerKind, " +
            "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
            "followUpstream, assetControls, lastImportAt, lastImportStatus) " +
            "SELECT id, rootUri, relativePath, displayName, importerKind, importerVersion, " +
            "createdAt, keepTree, keepStateOnDelete, contentPolicy, followUpstream, " +
            "assetControls, lastImportAt, lastImportStatus FROM sources"
    )
    exec("DROP TABLE sources")
    exec("ALTER TABLE sources_v24 RENAME TO sources")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` " +
            "ON `sources` (`rootUri`, `relativePath`)"
    )

    // --- lists: no exportKind, and webhookUrl still here --------------------------------------
    exec(
        "CREATE TABLE lists_v24 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
            "`webhookEnabled` INTEGER NOT NULL, `webhookUrl` TEXT NOT NULL, " +
            "`lastDeliveryAt` INTEGER, `lastDeliveryStatus` TEXT, `lastDeliveryCode` INTEGER, " +
            "`lastDeliveryError` TEXT, `lastDissolvedCount` INTEGER, `lastDissolvedAt` INTEGER, " +
            "`lastDissolvedReason` TEXT NOT NULL, `sourceRootUri` TEXT, " +
            "`sourceRelativePath` TEXT, `reviewCurrentItemId` INTEGER, " +
            "`taskReviewTemplateId` TEXT NOT NULL DEFAULT 'builtin.media.rapid-review', " +
            "`taskReviewTemplateVersion` INTEGER NOT NULL DEFAULT 1)"
    )
    exec(
        "INSERT INTO lists_v24 (id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
            "lastDeliveryAt, lastDeliveryStatus, lastDeliveryCode, lastDeliveryError, " +
            "lastDissolvedCount, lastDissolvedAt, lastDissolvedReason, sourceRootUri, " +
            "sourceRelativePath, reviewCurrentItemId, taskReviewTemplateId, " +
            "taskReviewTemplateVersion) " +
            "SELECT id, title, createdAt, completedAt, webhookEnabled, webhookUrl, " +
            "lastDeliveryAt, lastDeliveryStatus, lastDeliveryCode, lastDeliveryError, " +
            "lastDissolvedCount, lastDissolvedAt, lastDissolvedReason, sourceRootUri, " +
            "sourceRelativePath, reviewCurrentItemId, taskReviewTemplateId, " +
            "taskReviewTemplateVersion FROM lists"
    )
    exec("DROP TABLE lists")
    exec("ALTER TABLE lists_v24 RENAME TO lists")

    // --- webhook_history: no kind --------------------------------------------------------------
    exec(
        "CREATE TABLE webhook_history_v24 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`event` TEXT NOT NULL, `listTitle` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, " +
            "`payload` TEXT NOT NULL, `at` INTEGER NOT NULL, `outcome` TEXT NOT NULL, " +
            "`detail` TEXT NOT NULL)"
    )
    exec(
        "INSERT INTO webhook_history_v24 (id, event, listTitle, itemCount, payload, at, outcome, " +
            "detail) SELECT id, event, listTitle, itemCount, payload, at, outcome, detail " +
            "FROM webhook_history"
    )
    exec("DROP TABLE webhook_history")
    exec("ALTER TABLE webhook_history_v24 RENAME TO webhook_history")

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}

/**
 * Takes everything `MIGRATION_25_26` adds back off, leaving a database in v25's shape.
 *
 * Two `ALTER`s added the columns, so both tables are rebuilt to remove them — [subtractV24Additions]'s
 * rule, and here for the same reason: minSdk 24 has no `DROP COLUMN`. The DDL is transcribed from
 * `25.json`, and the same pragmas are needed: `assets` holds a foreign key to `tasks`, and a rename
 * with `legacy_alter_table` off would rewrite it to the temporary name.
 */
internal fun ListeaDatabase.subtractV26Additions() {
    // The step above this one, first: this fixture has to come back to v25, and a database left
    // at v27 would still hold the address columns and the marker table.
    subtractV27Additions()

    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- tasks: no publicId --------------------------------------------------------------------
    exec(
        "CREATE TABLE tasks_v25 (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    exec("INSERT INTO tasks_v25 (id, createdAt) SELECT id, createdAt FROM tasks")
    exec("DROP TABLE tasks")
    exec("ALTER TABLE tasks_v25 RENAME TO tasks")

    // --- assets: no publicId -------------------------------------------------------------------
    exec(
        "CREATE TABLE assets_v25 (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, `rootUri` TEXT, `relativePath` TEXT, " +
            "`sourceMissing` INTEGER NOT NULL, `sourceUri` TEXT, `sizeBytes` INTEGER, " +
            "`lastModified` INTEGER, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT INTO assets_v25 (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
            "sourceUri, sizeBytes, lastModified) " +
            "SELECT id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
            "sourceUri, sizeBytes, lastModified FROM assets"
    )
    exec("DROP TABLE assets")
    exec("ALTER TABLE assets_v25 RENAME TO assets")
    exec("CREATE INDEX IF NOT EXISTS `index_assets_taskId` ON `assets` (`taskId`)")
    exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_assets_taskId_rootUri_relativePath` " +
            "ON `assets` (`taskId`, `rootUri`, `relativePath`)"
    )
    exec(
        "CREATE INDEX IF NOT EXISTS `index_assets_rootUri_relativePath` " +
            "ON `assets` (`rootUri`, `relativePath`)"
    )

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}

/**
 * Takes everything `MIGRATION_26_27` adds back off, leaving a database in v26's shape.
 *
 * Four `ALTER`s added columns and one `CREATE` added a table, so both altered tables are rebuilt
 * to remove their columns — [subtractV24Additions]'s rule, and here for the same reason: minSdk 24
 * has no `DROP COLUMN`. The DDL is transcribed from `26.json`. `schema_backfills` drops outright.
 *
 * `lists.webhookUrl` is deliberately left alone. The migration clears it, but the column predates
 * this step; a v26 fixture may hold any value in it, and the step under test is the one that turns
 * it into the empty override.
 */
internal fun ListeaDatabase.subtractV27Additions() {
    exec("DROP TABLE IF EXISTS schema_backfills")

    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- sources: no webhookUrl, webhookFromDefault ------------------------------------------
    exec(
        "CREATE TABLE sources_v26 (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `importerVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `keepTree` INTEGER NOT NULL, " +
            "`keepStateOnDelete` INTEGER NOT NULL, `contentPolicy` TEXT NOT NULL, " +
            "`followUpstream` INTEGER NOT NULL, `assetControls` TEXT NOT NULL, " +
            "`exportKind` TEXT NOT NULL, `lastImportAt` INTEGER, `lastImportStatus` TEXT, " +
            "PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO sources_v26 (id, rootUri, relativePath, displayName, importerKind, " +
            "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
            "followUpstream, assetControls, exportKind, lastImportAt, lastImportStatus) " +
            "SELECT id, rootUri, relativePath, displayName, importerKind, importerVersion, " +
            "createdAt, keepTree, keepStateOnDelete, contentPolicy, followUpstream, " +
            "assetControls, exportKind, lastImportAt, lastImportStatus FROM sources"
    )
    exec("DROP TABLE sources")
    exec("ALTER TABLE sources_v26 RENAME TO sources")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` " +
            "ON `sources` (`rootUri`, `relativePath`)"
    )

    // --- webhook_history: no webhookUrl, roundId ----------------------------------------------
    exec(
        "CREATE TABLE webhook_history_v26 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`event` TEXT NOT NULL, `listTitle` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, " +
            "`payload` TEXT NOT NULL, `at` INTEGER NOT NULL, `outcome` TEXT NOT NULL, " +
            "`detail` TEXT NOT NULL, `kind` TEXT NOT NULL)"
    )
    exec(
        "INSERT INTO webhook_history_v26 (id, event, listTitle, itemCount, payload, at, " +
            "outcome, detail, kind) SELECT id, event, listTitle, itemCount, payload, at, " +
            "outcome, detail, kind FROM webhook_history"
    )
    exec("DROP TABLE webhook_history")
    exec("ALTER TABLE webhook_history_v26 RENAME TO webhook_history")

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}
