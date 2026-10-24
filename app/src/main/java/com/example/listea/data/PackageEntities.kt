package com.example.listea.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The v0.8.2 Package tables: a Prompt's payload, a Todo's import checkpoint, and the links between
 * a Task and the Package it came from.
 *
 * **There is no `packages` table.** A Package is a directory the producer writes; the app's copy is
 * provenance on the Todo and the Task, not a new entity with a lifecycle. That is why a Package can
 * be renamed, re-revisioned, or vanish from disk without this schema having to decide what a
 * "deleted Package" would mean: the checkpoint stops being refreshed, the Tasks keep their ids and
 * judgments, and absence is never deletion.
 *
 * **The Prompt payload is stored as text.** `task_payloads.payload` is the canonical JSON of the
 * Prompt description, and `kind`/`schemaVersion` say who may interpret it. Core reads the envelope
 * and stores the body opaquely; only a Viewer declaring that kind/schema parses the body. Keeping
 * the text rather than columns is what lets a later Viewer read a Prompt imported by an older build
 * without a re-import, and what keeps Core from learning every kind's field names.
 *
 * **`sourceId` is a Todo id.** The protocol calls the key `todoId`; the app's table for a Todo is
 * `sources`, and the foreign keys here name it in the app's vocabulary.
 */
@Entity(
    tableName = "task_payloads",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskPayloadEntity(
    @PrimaryKey val taskId: String,

    /** The Viewer family this body belongs to, e.g. `profile`. */
    val kind: String,

    /** The body's schema, interpreted together with [kind]. */
    val schemaVersion: Int,

    /** The canonical Prompt description; Core does not parse it beyond the declared envelope. */
    val payload: String,

    /** The Package revision this payload was read from. */
    val revision: Int,

    val updatedAt: Long
)

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
 * The Prompt a Task answers, if that Task came from a Package.
 *
 * [present] is the package's *current* claim: a Prompt the latest revision still names is `1`;
 * one the revision no longer names is `0`, with the Task and its history left intact. A missing
 * file is not a deletion, and neither is a missing Prompt key.
 *
 * The unique key `(sourceId, packageKey, promptKey)` is the reconciliation identity — changing a
 * promptKey creates a new Task, changing a title does not.
 */
@Entity(
    tableName = "package_prompt_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"]
        )
    ],
    indices = [Index(value = ["sourceId", "packageKey", "promptKey"], unique = true)]
)
data class PackagePromptTaskEntity(
    @PrimaryKey val taskId: String,
    val sourceId: String,
    val packageKey: String,
    val promptKey: String,
    val present: Boolean
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

/**
 * Which Resources one Task's Prompt uses, and whether each is required.
 *
 * [required] is per Prompt use, not per declaration: one photo may be required for one Prompt and
 * optional for another. A missing required Resource blocks submission at the write boundary; a
 * missing optional one merely disables the affordance that needs it.
 */
@Entity(
    tableName = "task_resources",
    primaryKeys = ["taskId", "resourceId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PackageResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["resourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("resourceId")]
)
data class TaskResourceEntity(
    val taskId: String,
    val resourceId: String,
    @ColumnInfo(name = "required") val required: Boolean
)
