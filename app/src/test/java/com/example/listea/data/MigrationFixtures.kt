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
 * The objects only a step *newer* than the fixtures' own creates.
 *
 * A fixture is opened at the version the app ships, so any test that names the tables it expects
 * sees every later release's there too. Several of them assert the table list in full — "this step
 * added one table and nothing else appeared" — and that assertion is worth keeping exact rather
 * than replacing with a count, because a rebuilt copy under a different name is precisely what it
 * is there to catch. Subtracting these keeps each one about the step it was written for, and keeps
 * the list that has to be maintained as the chain grows in a single place.
 *
 * Indexes are named here too: the comparison is over `sqlite_master`, so an index a later step
 * creates is as much a new object as the table it sits on.
 */
/**
 * The objects a step *newer* than the fixtures' own replaces rather than adds.
 *
 * One release so far has taken something away: 32 -> 33 scopes Asset uniqueness to the part, so
 * the Task-wide unique index is dropped and a part-keyed one created in its place. A test comparing
 * `sqlite_master` before and after has to allow for the disappearance, exactly as it allows for the
 * additions in [TablesFromLaterSteps].
 */
internal val ObjectsReplacedByLaterSteps = setOf(
    "index_assets_taskId_rootUri_relativePath",
    // 32 -> 33 moves the Package links onto parts, so the Task-keyed tables and their indices go.
    "task_payloads",
    "task_resources",
    "index_task_resources_resourceId",
    "package_prompt_tasks",
    "index_package_prompt_tasks_sourceId_packageKey_promptKey"
)

internal val TablesFromLaterSteps = listOf(
    "templates",
    "template_versions",
    "schema_backfills",
    "task_asset_marks",
    "index_task_asset_marks_assetId",
    // The v0.7.1 questionnaire: its lineage, its versions and the answers a Task keeps.
    "addons",
    "addon_versions",
    "task_review_answers",
    "index_task_review_answers_taskId",
    // The v0.8.1 event rows: a submission's events, split out for the Info sheet.
    "review_event_answers",
    "index_review_event_answers_assetId",
    // The v0.8.2 Package: a Prompt payload, a Todo's checkpoint, and the links between them.
    "task_payloads",
    "package_checkpoints",
    "package_prompt_tasks",
    "index_package_prompt_tasks_sourceId_packageKey_promptKey",
    "package_resources",
    "index_package_resources_sourceId_packageKey_resourceKey",
    "task_resources",
    "index_task_resources_resourceId",
    // The v0.8.4 parts: a Task's decision units, their state/payload/Resource rows, and the
    // composition a Package Task belongs to. The payload and Resource links above are gone at this
    // version — the step moved them — but they stay here because the *fixtures* still have them.
    "task_parts",
    "index_task_parts_taskId_position",
    "task_part_payloads",
    "task_part_state",
    "task_part_resources",
    "index_task_part_resources_resourceId",
    "package_compositions",
    "index_package_compositions_source_package_composition",
    "package_composition_prompts",
    "index_package_composition_prompts_source_composition_prompt",
    "index_assets_taskId_partKey_rootUri_relativePath",
    "index_review_events_taskId_partKey_at"
)

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
    // The 24 → 25 step's export choice, the 26 → 27 step's Todo address, and the v0.7.1
    // attachment. A Set because more than one release appends to `sources` and `webhook_history`,
    // and the fixture has to subtract all of them to come back to the version under test.
    "sources" to setOf(
        "exportKind", "webhookUrl", "webhookFromDefault", "addOn", "taskReview", "composition"
    ),
    // The v0.8.4 parts: a Task's completion provenance, an Asset's owning part, an event's part,
    // a structured answer's field, and a dissolved record's part records.
    "task_state" to setOf("completionSource"),
    "review_events" to setOf("partKey"),
    "review_event_answers" to setOf("fieldKey"),
    "dissolved_task_state" to setOf("partRecords"),
    "lists" to setOf("exportKind"),
    "webhook_history" to setOf("kind", "webhookUrl", "roundId"),
    // The v0.6.1 step's wire identity, appended to the two rows that leave the device.
    "tasks" to setOf("publicId"),
    "assets" to setOf("publicId", "partKey"),
    // The v0.7.1 attachment and the v0.8.1 Task Review stamp, both appended to `sources` and to
    // the Todo's per-Task copy.
    "task_imports" to setOf("addOn", "taskReview")
)

/** One statement, against a database being built or wound back. */
internal fun ListeaDatabase.exec(sql: String) {
    openHelper.writableDatabase.execSQL(sql)
}

/**
 * Whether the schema at this point in a wind-back still has [table].
 *
 * The subtraction chain runs newest first, so a step that rebuilds or clears a table can be reached
 * by a fixture older than the table itself — [subtractV24Additions] has already dropped the template
 * tables by the time it walks down to [subtractV30Additions]. A v22 fixture never had them, and a
 * v23 fixture has had them taken off, so a bare `DELETE` is a statement against a table that is not
 * there.
 */
internal fun ListeaDatabase.hasTable(table: String): Boolean =
    openHelper.writableDatabase.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table)
    ).use { it.moveToFirst() }

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
    // The step above this one, first: a fixture has to come back to v26, and a database left at
    // v28 would still hold the marks table that step created.
    subtractV28Additions()

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

/**
 * Takes the one table `MIGRATION_27_28` creates back off, leaving a database in v27's shape.
 *
 * The step only adds a table, so there is no rebuild and no pragma to juggle: a `DROP` is exactly
 * what undoes a `CREATE`, and the table has no predecessor to restore. A fixture that held marks
 * would fail its own "really a v27" assertion, which is what keeps the subtraction honest as the
 * chain grows rather than a line nobody reads.
 */
internal fun ListeaDatabase.subtractV28Additions() {
    // The step above this one, first: a fixture has to come back to v27, and a database left at
    // v29 would still hold the questionnaire tables and the two attachment columns.
    subtractV29Additions()

    exec("DROP TABLE IF EXISTS task_asset_marks")
}

/**
 * Takes everything `MIGRATION_30_31` adds back off, leaving a database in v30's shape.
 *
 * One table, dropped outright because that step creates it. Its index goes with the table, and no
 * other object moves: the step writes no existing column and rewrites no response. Every fixture
 * older than v31 reaches this through [subtractV30Additions], which is why `IF EXISTS` is not
 * decoration — a v22 fixture has never had the table at all.
 */
internal fun ListeaDatabase.subtractV31Additions() {
    // The step above this one, first: a database left at v32 would still hold the Package tables.
    subtractV32Additions()

    exec("DROP TABLE IF EXISTS review_event_answers")
}

/**
 * Takes everything `MIGRATION_31_32` adds back off, leaving a database in v31's shape.
 *
 * Five new tables and nothing else: the step writes no existing column and backfills nothing,
 * because a pre-Package database has no Prompt to backfill. Children are dropped before parents so
 * the order is valid with foreign keys on, though every table is empty in a fixture.
 */
internal fun ListeaDatabase.subtractV32Additions() {
    // The step above this one, first: a database left at v33 would hold the part tables and the
    // columns the Package tables were moved into.
    subtractV33Additions()

    exec("DROP TABLE IF EXISTS task_resources")
    exec("DROP TABLE IF EXISTS package_prompt_tasks")
    exec("DROP TABLE IF EXISTS package_resources")
    exec("DROP TABLE IF EXISTS package_checkpoints")
    exec("DROP TABLE IF EXISTS task_payloads")
}

/**
 * Takes everything `MIGRATION_32_33` adds back off, leaving a database in v32's shape.
 *
 * This one is not only a subtraction. The step moved three things rather than appending to them:
 * `task_payloads` and `task_resources` became part-keyed, and `package_prompt_tasks` became the
 * composition pair. The v32 tables therefore have to be *rebuilt from* their successors before the
 * successors are dropped, or a fixture rewound to v32 would be missing tables v32 always had.
 *
 * Each value is carried on the one row v32 could hold. A v33 Task may hold several part payloads
 * where v32 held one, so the copy takes the first part in key order and `OR IGNORE`s the rest; no
 * fixture in this suite writes two, and a fixture that did could not be a v32 in the first place.
 * The old Resource and Prompt-key uniqueness is likewise reconstructed from the part rows.
 *
 * The five columns the step appended are removed by rebuilding, [subtractV24Additions]'s rule and
 * for the same reason: minSdk 24 has no `DROP COLUMN`, so a fixture must come back to v32 in a shape
 * this app itself could have produced.
 */
internal fun ListeaDatabase.subtractV33Additions() {
    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- the v32 tables, back from their part-keyed successors --------------------------------
    exec(
        "CREATE TABLE task_payloads (`taskId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`schemaVersion` INTEGER NOT NULL, `payload` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT OR IGNORE INTO task_payloads (taskId, kind, schemaVersion, payload, revision, " +
            "updatedAt) SELECT taskId, kind, schemaVersion, payload, revision, updatedAt " +
            "FROM task_part_payloads ORDER BY taskId, partKey"
    )

    exec(
        "CREATE TABLE task_resources (`taskId` TEXT NOT NULL, `resourceId` TEXT NOT NULL, " +
            "`required` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `resourceId`), " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`resourceId`) REFERENCES `package_resources`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT OR IGNORE INTO task_resources (taskId, resourceId, required) " +
            "SELECT taskId, resourceId, required FROM task_part_resources"
    )
    exec(
        "CREATE INDEX IF NOT EXISTS `index_task_resources_resourceId` " +
            "ON `task_resources` (`resourceId`)"
    )

    exec(
        "CREATE TABLE package_prompt_tasks (`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`packageKey` TEXT NOT NULL, `promptKey` TEXT NOT NULL, `present` INTEGER NOT NULL, " +
            "PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )"
    )
    exec(
        "INSERT OR IGNORE INTO package_prompt_tasks (taskId, sourceId, packageKey, promptKey, " +
            "present) SELECT pc.taskId, pc.sourceId, pc.packageKey, pcp.promptKey, pc.present " +
            "FROM package_compositions pc " +
            "JOIN package_composition_prompts pcp ON pcp.taskId = pc.taskId " +
            "ORDER BY pc.taskId, pcp.promptKey"
    )
    exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_package_prompt_tasks_sourceId_packageKey_promptKey` " +
            "ON `package_prompt_tasks` (`sourceId`, `packageKey`, `promptKey`)"
    )

    // --- and now the v33 objects go -----------------------------------------------------------
    exec("DROP TABLE IF EXISTS package_composition_prompts")
    exec("DROP TABLE IF EXISTS package_compositions")
    exec("DROP TABLE IF EXISTS task_part_state")
    exec("DROP TABLE IF EXISTS task_part_payloads")
    exec("DROP TABLE IF EXISTS task_part_resources")
    exec("DROP TABLE IF EXISTS task_parts")

    // --- task_state: no completionSource ------------------------------------------------------
    exec(
        "CREATE TABLE task_state_v32 (`taskId` TEXT NOT NULL, " +
            "`isCompleted` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
            "`customActions` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT INTO task_state_v32 (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
            "SELECT taskId, isCompleted, isFavorite, customActions, updatedAt FROM task_state"
    )
    exec("DROP TABLE task_state")
    exec("ALTER TABLE task_state_v32 RENAME TO task_state")

    // --- assets: no partKey, and the Task-wide uniqueness comes back --------------------------
    exec(
        "CREATE TABLE assets_v32 (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, `rootUri` TEXT, `relativePath` TEXT, " +
            "`sourceMissing` INTEGER NOT NULL, `sourceUri` TEXT, `sizeBytes` INTEGER, " +
            "`lastModified` INTEGER, `publicId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT INTO assets_v32 (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
            "sourceUri, sizeBytes, lastModified, publicId) " +
            "SELECT id, taskId, sortOrder, rootUri, relativePath, sourceMissing, sourceUri, " +
            "sizeBytes, lastModified, publicId FROM assets"
    )
    exec("DROP TABLE assets")
    exec("ALTER TABLE assets_v32 RENAME TO assets")
    exec("CREATE INDEX IF NOT EXISTS `index_assets_taskId` ON `assets` (`taskId`)")
    exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_assets_taskId_rootUri_relativePath` " +
            "ON `assets` (`taskId`, `rootUri`, `relativePath`)"
    )
    exec(
        "CREATE INDEX IF NOT EXISTS `index_assets_rootUri_relativePath` " +
            "ON `assets` (`rootUri`, `relativePath`)"
    )

    // --- review_events: no partKey -----------------------------------------------------------
    exec(
        "CREATE TABLE review_events_v32 (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
            "`at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `templateId` TEXT NOT NULL, " +
            "`templateVersion` INTEGER NOT NULL, `response` TEXT NOT NULL, " +
            "`contextKind` TEXT, `contextRef` TEXT, PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO review_events_v32 (id, taskId, at, kind, templateId, templateVersion, " +
            "response, contextKind, contextRef) SELECT id, taskId, at, kind, templateId, " +
            "templateVersion, response, contextKind, contextRef FROM review_events"
    )
    exec("DROP TABLE review_events")
    exec("ALTER TABLE review_events_v32 RENAME TO review_events")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_review_events_taskId_at` " +
            "ON `review_events` (`taskId`, `at`)"
    )

    // --- review_event_answers: no fieldKey ----------------------------------------------------
    exec(
        "CREATE TABLE review_event_answers_v32 (`reviewEventId` TEXT NOT NULL, " +
            "`ordinal` INTEGER NOT NULL, `assetId` TEXT, `type` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, PRIMARY KEY(`reviewEventId`, `ordinal`), " +
            "FOREIGN KEY(`reviewEventId`) REFERENCES `review_events`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    exec(
        "INSERT INTO review_event_answers_v32 (reviewEventId, ordinal, assetId, type, value) " +
            "SELECT reviewEventId, ordinal, assetId, type, value FROM review_event_answers"
    )
    exec("DROP TABLE review_event_answers")
    exec("ALTER TABLE review_event_answers_v32 RENAME TO review_event_answers")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_review_event_answers_assetId` " +
            "ON `review_event_answers` (`assetId`)"
    )

    // --- dissolved_task_state: no partRecords --------------------------------------------------
    exec(
        "CREATE TABLE dissolved_task_state_v32 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`sourcePath` TEXT NOT NULL, `groupPath` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, " +
            "`recordVersion` INTEGER NOT NULL, `records` TEXT NOT NULL, " +
            "`dissolvedAt` INTEGER NOT NULL)"
    )
    exec(
        "INSERT INTO dissolved_task_state_v32 (id, sourceId, sourceName, rootUri, sourcePath, " +
            "groupPath, importerKind, isCompleted, recordVersion, records, dissolvedAt) " +
            "SELECT id, sourceId, sourceName, rootUri, sourcePath, groupPath, importerKind, " +
            "isCompleted, recordVersion, records, dissolvedAt FROM dissolved_task_state"
    )
    exec("DROP TABLE dissolved_task_state")
    exec("ALTER TABLE dissolved_task_state_v32 RENAME TO dissolved_task_state")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_dissolved_task_state_sourceId` " +
            "ON `dissolved_task_state` (`sourceId`)"
    )
    exec(
        "CREATE INDEX IF NOT EXISTS `index_dissolved_task_state_rootUri_sourcePath` " +
            "ON `dissolved_task_state` (`rootUri`, `sourcePath`)"
    )

    // --- sources: no composition --------------------------------------------------------------
    exec(
        "CREATE TABLE sources_v32 (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `importerVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `keepTree` INTEGER NOT NULL, " +
            "`keepStateOnDelete` INTEGER NOT NULL, `contentPolicy` TEXT NOT NULL, " +
            "`followUpstream` INTEGER NOT NULL, `assetControls` TEXT NOT NULL, " +
            "`addOn` TEXT NOT NULL, `taskReview` TEXT NOT NULL DEFAULT '', " +
            "`exportKind` TEXT NOT NULL, `webhookUrl` TEXT NOT NULL DEFAULT '', " +
            "`webhookFromDefault` INTEGER NOT NULL DEFAULT 0, `lastImportAt` INTEGER, " +
            "`lastImportStatus` TEXT, PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO sources_v32 (id, rootUri, relativePath, displayName, importerKind, " +
            "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
            "followUpstream, assetControls, addOn, taskReview, exportKind, webhookUrl, " +
            "webhookFromDefault, lastImportAt, lastImportStatus) " +
            "SELECT id, rootUri, relativePath, displayName, importerKind, importerVersion, " +
            "createdAt, keepTree, keepStateOnDelete, contentPolicy, followUpstream, " +
            "assetControls, addOn, taskReview, exportKind, webhookUrl, webhookFromDefault, " +
            "lastImportAt, lastImportStatus FROM sources"
    )
    exec("DROP TABLE sources")
    exec("ALTER TABLE sources_v32 RENAME TO sources")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` " +
            "ON `sources` (`rootUri`, `relativePath`)"
    )

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}

/**
 * Takes everything `MIGRATION_29_30` adds back off, leaving a database in v29's shape.
 *
 * Five columns and two seeded lineages come off. The columns are append-only `ALTER`s in the
 * migration, so the three tables are rebuilt to remove them — [subtractV24Additions]'s rule, and
 * here for the same reason: minSdk 24 has no `DROP COLUMN`. The DDL is transcribed from `29.json`;
 * the pragmas are needed because `template_versions` and `task_imports` hold foreign keys that a
 * rename would otherwise rewrite to point at the temporary names.
 *
 * **The two lineages are the step's own seed, not a fixture's rows.** No test writes a template
 * called `builtin.media.rapid-review` or `builtin.image.sample-review`, and in v29 neither existed
 * as a row at all — the rapid review was code-defined and the sample review had not been thought
 * of. Leaving them behind would make the fixture a v29 that already stored what this step stored.
 */
internal fun ListeaDatabase.subtractV30Additions() {
    // The step above this one, first: a fixture has to come back to v29, and a database left at
    // v31 would still hold the event table.
    subtractV31Additions()

    // A fixture older than v24 is walked here after [subtractV24Additions] dropped the template
    // tables, so the seed and the rebuild below only run when there is something to take apart.
    val templates = hasTable("template_versions") && hasTable("templates")

    // The seed first, so the rebuild below carries only the rows a v29 database could hold.
    if (templates) {
        exec(
            "DELETE FROM template_versions WHERE templateId IN " +
                "('builtin.media.rapid-review', 'builtin.image.sample-review')"
        )
        exec(
            "DELETE FROM templates WHERE id IN " +
                "('builtin.media.rapid-review', 'builtin.image.sample-review')"
        )
    }

    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- template_versions: no kind, definition, definitionVersion ------------------------------
    if (templates) {
        exec(
            "CREATE TABLE template_versions_v29 (`templateId` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, `scope` TEXT NOT NULL, `buttons` TEXT NOT NULL, " +
                "`buttonsVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`templateId`, `version`), " +
                "FOREIGN KEY(`templateId`) REFERENCES `templates`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        exec(
            "INSERT INTO template_versions_v29 (templateId, version, scope, buttons, " +
                "buttonsVersion, createdAt) " +
                "SELECT templateId, version, scope, buttons, buttonsVersion, createdAt " +
                "FROM template_versions"
        )
        exec("DROP TABLE template_versions")
        exec("ALTER TABLE template_versions_v29 RENAME TO template_versions")
    }

    // --- sources: no taskReview -----------------------------------------------------------------
    exec(
        "CREATE TABLE sources_v29 (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `importerVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `keepTree` INTEGER NOT NULL, " +
            "`keepStateOnDelete` INTEGER NOT NULL, `contentPolicy` TEXT NOT NULL, " +
            "`followUpstream` INTEGER NOT NULL, `assetControls` TEXT NOT NULL, " +
            "`addOn` TEXT NOT NULL, `exportKind` TEXT NOT NULL, " +
            "`webhookUrl` TEXT NOT NULL DEFAULT '', " +
            "`webhookFromDefault` INTEGER NOT NULL DEFAULT 0, `lastImportAt` INTEGER, " +
            "`lastImportStatus` TEXT, PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO sources_v29 (id, rootUri, relativePath, displayName, importerKind, " +
            "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
            "followUpstream, assetControls, addOn, exportKind, webhookUrl, webhookFromDefault, " +
            "lastImportAt, lastImportStatus) " +
            "SELECT id, rootUri, relativePath, displayName, importerKind, importerVersion, " +
            "createdAt, keepTree, keepStateOnDelete, contentPolicy, followUpstream, " +
            "assetControls, addOn, exportKind, webhookUrl, webhookFromDefault, lastImportAt, " +
            "lastImportStatus FROM sources"
    )
    exec("DROP TABLE sources")
    exec("ALTER TABLE sources_v29 RENAME TO sources")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` " +
            "ON `sources` (`rootUri`, `relativePath`)"
    )

    // --- task_imports: no taskReview ------------------------------------------------------------
    exec(
        "CREATE TABLE task_imports_v29 (`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`groupPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
            "`assetControls` TEXT NOT NULL, `addOn` TEXT NOT NULL, PRIMARY KEY(`taskId`), " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )"
    )
    exec(
        "INSERT INTO task_imports_v29 (taskId, sourceId, groupPath, displayName, importedAt, " +
            "lastSeenAt, assetControls, addOn) " +
            "SELECT taskId, sourceId, groupPath, displayName, importedAt, lastSeenAt, " +
            "assetControls, addOn FROM task_imports"
    )
    exec("DROP TABLE task_imports")
    exec("ALTER TABLE task_imports_v29 RENAME TO task_imports")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_task_imports_sourceId_groupPath` " +
            "ON `task_imports` (`sourceId`, `groupPath`)"
    )

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}

/**
 * Takes everything `MIGRATION_28_29` adds back off, leaving a database in v28's shape.
 *
 * Three tables drop outright, because that step creates them. The two columns are `ALTER`s, so
 * `sources` and `task_imports` are rebuilt rather than altered — [subtractV24Additions]'s rule and
 * for the same reason: minSdk 24 has no `DROP COLUMN`, and a fixture must come back to an older
 * shape the way the app itself could. The DDL is transcribed from `28.json`; the pragmas are needed
 * for `task_imports`, whose foreign keys to `tasks` and `sources` a rename would otherwise rewrite
 * to point at the temporary name.
 */
internal fun ListeaDatabase.subtractV29Additions() {
    // The step above this one, first: a fixture has to come back to v28, and a database left at
    // v30 would still hold kind/definition columns and the seeded Task Reviews.
    subtractV30Additions()

    exec("DROP TABLE IF EXISTS task_review_answers")
    exec("DROP TABLE IF EXISTS addon_versions")
    exec("DROP TABLE IF EXISTS addons")

    exec("PRAGMA foreign_keys = OFF")
    exec("PRAGMA legacy_alter_table = ON")

    // --- sources: no addOn ---------------------------------------------------------------------
    exec(
        "CREATE TABLE sources_v28 (`id` TEXT NOT NULL, `rootUri` TEXT NOT NULL, " +
            "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importerKind` TEXT NOT NULL, `importerVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `keepTree` INTEGER NOT NULL, " +
            "`keepStateOnDelete` INTEGER NOT NULL, `contentPolicy` TEXT NOT NULL, " +
            "`followUpstream` INTEGER NOT NULL, `assetControls` TEXT NOT NULL, " +
            "`exportKind` TEXT NOT NULL, `webhookUrl` TEXT NOT NULL DEFAULT '', " +
            "`webhookFromDefault` INTEGER NOT NULL DEFAULT 0, `lastImportAt` INTEGER, " +
            "`lastImportStatus` TEXT, PRIMARY KEY(`id`))"
    )
    exec(
        "INSERT INTO sources_v28 (id, rootUri, relativePath, displayName, importerKind, " +
            "importerVersion, createdAt, keepTree, keepStateOnDelete, contentPolicy, " +
            "followUpstream, assetControls, exportKind, webhookUrl, webhookFromDefault, " +
            "lastImportAt, lastImportStatus) " +
            "SELECT id, rootUri, relativePath, displayName, importerKind, importerVersion, " +
            "createdAt, keepTree, keepStateOnDelete, contentPolicy, followUpstream, " +
            "assetControls, exportKind, webhookUrl, webhookFromDefault, lastImportAt, " +
            "lastImportStatus FROM sources"
    )
    exec("DROP TABLE sources")
    exec("ALTER TABLE sources_v28 RENAME TO sources")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_sources_rootUri_relativePath` " +
            "ON `sources` (`rootUri`, `relativePath`)"
    )

    // --- task_imports: no addOn ----------------------------------------------------------------
    exec(
        "CREATE TABLE task_imports_v28 (`taskId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`groupPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`importedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
            "`assetControls` TEXT NOT NULL, PRIMARY KEY(`taskId`), " +
            "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )"
    )
    exec(
        "INSERT INTO task_imports_v28 (taskId, sourceId, groupPath, displayName, importedAt, " +
            "lastSeenAt, assetControls) " +
            "SELECT taskId, sourceId, groupPath, displayName, importedAt, lastSeenAt, " +
            "assetControls FROM task_imports"
    )
    exec("DROP TABLE task_imports")
    exec("ALTER TABLE task_imports_v28 RENAME TO task_imports")
    exec(
        "CREATE INDEX IF NOT EXISTS `index_task_imports_sourceId_groupPath` " +
            "ON `task_imports` (`sourceId`, `groupPath`)"
    )

    exec("PRAGMA legacy_alter_table = OFF")
    exec("PRAGMA foreign_keys = ON")
}
