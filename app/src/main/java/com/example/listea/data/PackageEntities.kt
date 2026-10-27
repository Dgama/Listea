package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The Package tables: a Todo's import checkpoint and the Resources a Package declares.
 *
 * **There is no `packages` table.** A Package is a directory the producer writes; the app's copy is
 * provenance on the Todo and the Task, not a new entity with a lifecycle. That is why a Package can
 * be renamed, re-revisioned, or vanish from disk without this schema having to decide what a
 * "deleted Package" would mean: the checkpoint stops being refreshed, the Tasks keep their ids and
 * judgments, and absence is never deletion.
 *
 * **A Prompt's payload is per part.** `task_part_payloads.payload` holds the canonical JSON and its
 * `kind`/`schemaVersion` envelope; see [TaskPartPayloadEntity]. Core reads the envelope and stores
 * the body opaquely, which is what lets a later Viewer read a Prompt imported by an older build
 * without a re-import and keeps Core from learning every kind's field names.
 *
 * **`sourceId` is a Todo id.** The protocol calls the key `todoId`; the app's table for a Todo is
 * `sources`, and the foreign keys here name it in the app's vocabulary.
 */

/**
 * What a Todo last saw at one Package, so a re-import can tell unchanged content from a conflict.
 *
 * Keyed by `(sourceId, packageKey)` — the Todo is part of the identity because two Todos over the
 * same Package are two independent sets of Tasks. [fingerprint] covers the canonical manifest and
 * the canonical description members, not Asset or Resource bytes; see `PackageProtocol`.
 *
 * [rootUri] and [relativePath] locate the Package directory in the granted tree. They are stored
 * here rather than on `task_imports` because the locator belongs to the Package, not to each Task.
 */
@Entity(
    tableName = "package_checkpoints",
    primaryKeys = ["sourceId", "packageKey"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"]
        )
    ]
)
data class PackageCheckpointEntity(
    val sourceId: String,
    val packageKey: String,
    val rootUri: String,
    val relativePath: String,
    val protocol: String,
    val title: String,
    val revision: Int,
    val fingerprint: String,
    val lastSeenAt: Long
)

/**
 * One Resource a Package declares: a file a Prompt may *use* without the file being a page.
 *
 * A Resource is not an Asset. The photo beside a dating profile is read by the profile Viewer to
 * draw the card; it is not a separate page and does not appear in the Task's asset list. That
 * distinction is why this is a table of its own rather than a flag on `assets`.
 *
 * [missing] is a runtime availability state refreshed at import, not a deletion. A later import
 * that finds the file clears it; a file disappearing between imports is discovered when the
 * Viewer resolves it.
 */
@Entity(
    tableName = "package_resources",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"]
        )
    ],
    indices = [Index(value = ["sourceId", "packageKey", "resourceKey"], unique = true)]
)
data class PackageResourceEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val packageKey: String,
    val resourceKey: String,
    val kind: String,
    val rootUri: String,
    val relativePath: String,
    val missing: Boolean,
    val updatedAt: Long
)

