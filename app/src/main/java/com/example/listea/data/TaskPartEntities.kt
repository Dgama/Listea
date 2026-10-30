package com.example.listea.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The v0.8.4 tables: a Task's decision units, their payloads, state and Resources, and the
 * composition that grouped them.
 *
 * A Task used to be one decision by construction, so its payload, Resources and response each had
 * one row keyed by `taskId`. A Task now holds 1..N **TaskParts**, and every one of those facts
 * belongs to a part. The tables here are the part-keyed successors; schema 33 migrates every
 * existing Task to exactly one part so an old database reads through the same model.
 *
 * **A part is a decision, not a file.** Assets point at a part; Resources support a part. Neither
 * is a part of its own. A degenerate part — the one the local file/folder importers synthesize —
 * has no payload and may hold several Assets, which is why the payload table is separate and
 * optional while the part row is mandatory.
 */

/**
 * One decision instantiated in a Task.
 *
 * [partKey] is stable within the Task and is the part's identity there: a Package Prompt key
 * (`q1`), the local importer's `local:0`, or a migrated Task's `legacy:<taskId>` (or its Prompt key
 * when the Task came from a Package). [position] orders the parts; [origin] records what made the
 * part; [promptKey] is Package provenance and null for a degenerate part.
 *
 * [present] is the Package's current claim, as `package_prompt_tasks.present` was: a part the
 * latest revision no longer names is retained with its history and marked absent. [availability]
 * is a runtime code (for example `unsupported-viewer`) and null when the part can be presented.
 *
 * [condition] and [partJudgment] are v0.8.5 and both nullable, so a part written before this
 * schema reads exactly as it did: no condition and no judgment of its own. A condition names an
 * earlier part's field and the values that keep this part applicable — [com.example.listea.domain
 * .encodePartCondition] is its stored shape. A judgment is a stored `TemplateVersionRef` in
 * `encodeTemplateAttachments` form; a part with none inherits its Task's Task Review.
 */
@Entity(
    tableName = "task_parts",
    primaryKeys = ["taskId", "partKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId", "position"])]
)
data class TaskPartEntity(
    val taskId: String,
    val partKey: String,
    val position: Int,
    val origin: String,
    val promptKey: String?,
    @ColumnInfo(defaultValue = "1") val required: Boolean = true,
    @ColumnInfo(defaultValue = "1") val present: Boolean = true,
    val availability: String? = null,
    val condition: String? = null,
    val partJudgment: String? = null,
    val updatedAt: Long
)

/**
 * One part's structured body: the Viewer envelope and the canonical description text.
 *
 * The successor to `task_payloads`, keyed by `(taskId, partKey)`. Core still stores the body
 * opaquely; [kind] and [schemaVersion] are the envelope that says which Viewer may interpret it.
 */
@Entity(
    tableName = "task_part_payloads",
    primaryKeys = ["taskId", "partKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskPartEntity::class,
            parentColumns = ["taskId", "partKey"],
            childColumns = ["taskId", "partKey"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskPartPayloadEntity(
    val taskId: String,
    val partKey: String,
    val kind: String,
    val schemaVersion: Int,
    val payload: String,
    val revision: Int,
    val updatedAt: Long
)

/**
 * What is true about one part now: its current canonical answer.
 *
 * The event history stays append-only in `review_events`; this row is the part's current state,
 * so a surface can render the last answer without decoding the newest event, and a reset can clear
 * one part without touching another. [revision] is the payload revision the answer was made
 * against, carried so a body that moved under an open review is visible.
 *
 * [stale] says the stored answer is no longer one the current branch asks for: the condition that
 * made this part applicable has since closed, and the answer stays on record rather than being
 * deleted. A stale answer is not an answer for completion — the part must be answered again if its
 * branch reopens.
 */
@Entity(
    tableName = "task_part_state",
    primaryKeys = ["taskId", "partKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskPartEntity::class,
            parentColumns = ["taskId", "partKey"],
            childColumns = ["taskId", "partKey"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskPartStateEntity(
    val taskId: String,
    val partKey: String,
    @ColumnInfo(defaultValue = "''") val response: String = "",
    val answeredAt: Long? = null,
    val revision: Int? = null,
    @ColumnInfo(defaultValue = "0") val stale: Boolean = false,
    val updatedAt: Long
)

/**
 * Which Resources one part uses, and whether each is required for that part.
 *
 * The successor to `task_resources`: requiredness is per part use, not per declaration, so one
 * photo may be required for one Prompt and optional for another.
 */
@Entity(
    tableName = "task_part_resources",
    primaryKeys = ["taskId", "partKey", "resourceId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskPartEntity::class,
            parentColumns = ["taskId", "partKey"],
            childColumns = ["taskId", "partKey"],
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
data class TaskPartResourceEntity(
    val taskId: String,
    val partKey: String,
    val resourceId: String,
    @ColumnInfo(name = "required", defaultValue = "1") val required: Boolean
)

/**
 * The taker's composition rule that made one Task out of a Package's Prompts.
 *
 * [compositionKey] is `prompt:<promptKey>` or `section:<sectionKey>`. Together with
 * `(sourceId, packageKey)` it is the reconciliation identity: the same rule over the same Package
 * in the same Todo is the same Task, even as membership changes.
 *
 * The composite foreign key from [PackageCompositionPromptEntity] needs this table's
 * `(sourceId, packageKey, compositionKey)` to be unique, which the index below makes true.
 */
@Entity(
    tableName = "package_compositions",
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
    indices = [
        Index(
            value = ["sourceId", "packageKey", "compositionKey"],
            unique = true,
            name = "index_package_compositions_source_package_composition"
        )
    ]
)
data class PackageCompositionEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val sourceId: String,
    val packageKey: String,
    val compositionKey: String,
    @ColumnInfo(defaultValue = "1") val present: Boolean = true,
    val updatedAt: Long
)

/**
 * One Prompt membership in a composition Task.
 *
 * [present] is the current claim: a Prompt the latest revision still names is present; one it no
 * longer names is detached with its answer and history intact. The unique index keeps a Prompt from
 * appearing twice in one composition; the composite key includes `compositionKey` so an old Task
 * and a new composition may both hold the same Prompt while the old Task remains.
 */
@Entity(
    tableName = "package_composition_prompts",
    primaryKeys = ["taskId", "promptKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PackageCompositionEntity::class,
            parentColumns = ["sourceId", "packageKey", "compositionKey"],
            childColumns = ["sourceId", "packageKey", "compositionKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["sourceId", "packageKey", "compositionKey", "promptKey"],
            unique = true,
            name = "index_package_composition_prompts_source_composition_prompt"
        )
    ]
)
data class PackageCompositionPromptEntity(
    val taskId: String,
    val sourceId: String,
    val packageKey: String,
    val compositionKey: String,
    val promptKey: String,
    @ColumnInfo(defaultValue = "1") val present: Boolean = true
)
