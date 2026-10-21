package com.example.listea.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A schema change that cannot finish inside its own migration, and has not been done yet.
 *
 * `MIGRATION_26_27` adds the columns that make a Todo own its webhook address, but the value it
 * should copy — Settings' default URL and the old global delivery gate — lives in DataStore, which
 * a Room migration cannot read. The migration therefore records the work here and a one-time
 * startup step, [ListsDao.applyWebhookAddressBootstrap], performs it after it can read both stores.
 *
 * **A row is the work left, not a log of work done.** The bootstrap writes the addresses and
 * deletes the marker in one transaction, so an upgrade interrupted between the two cannot leave a
 * half-applied snapshot: either the marker survives and the next start retries, or it is gone and
 * the addresses are final. A fresh install never runs the migration and never sees a row; the
 * bootstrap then costs one lookup and does nothing.
 *
 * The name is versioned (*`v27.sources.webhookUrl`*) because a later release may need a backfill of
 * its own, and a table keyed by what the work is leaves room for it without a second mechanism. One
 * name per kind of work, with the migration naming it and the bootstrap consuming it.
 */
@Entity(tableName = "schema_backfills")
data class SchemaBackfillEntity(
    @PrimaryKey val name: String
)

/**
 * The marker `MIGRATION_26_27` inserts and the startup bootstrap consumes.
 *
 * Named for the version, the table and the column it fills, so the one place that has to spell it
 * out can be found by searching for either end of the upgrade.
 */
const val WEBHOOK_ADDRESS_BACKFILL = "v27.sources.webhookUrl"
