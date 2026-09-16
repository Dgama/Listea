package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The v14 tables: where a Task came from, and where a Task's content is measured.
 *
 * v0.2 gave the model a Task and an Asset but no story for how either came to exist. A Task was
 * minted lazily, one file at a time, as a side effect of *looking* at a folder — which is why the
 * same folder reached through two Lists, or entered twice, was a question the schema could not
 * answer. These tables answer it: a [SourceEntity] is a registered origin, [TaskImportEntity] is
 * the provenance of one Task under that origin, and the two legacy tables are the compatibility
 * claim sets that name what a v0.1 database already contained.
 *
 * The split is the same one [TaskEntities] makes about the Task itself: a Task has no opinion about
 * where it was found, and that is a *rule* rather than an unfinished edge. `groupPath` and
 * `displayName` are exactly the things a Task must not carry, so they live on a row *about* the
 * import, in the same style as [LegacyItemIdEntity] and [LegacyManualTaskEntity]. A Task with its
 * `task_imports` row deleted is still a Task; it is simply no longer in any catalog.
 */

/**
 * A registered origin from which raw material can be discovered.
 *
 * In v0.2.5 a Source is a folder: the SAF grant the folder sits under, the import root beneath that
 * grant (`''` is the grant root itself), a display name, and the importer that turns the folder's
 * contents into Tasks. It owns no state and no history — those belong to the Tasks it produces —
 * and its identity is deliberately not a path: [id] is opaque, and the `(rootUri, relativePath)`
 * pair is a uniqueness constraint that recognises the same folder again, not an identity.
 *
 * The two `lastImport` columns are the durable summary of the most recent run; the detailed result
 * is returned to the caller and shown, then dropped. There is deliberately no import history table:
 * nothing in v0.2.5 reads one, and the Source row plus [TaskImportEntity.lastSeenAt] is the whole
 * provenance reconciliation needs.
 */
@Entity(
    tableName = "sources",
    indices = [Index(value = ["rootUri", "relativePath"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val rootUri: String,
    val relativePath: String,
    val displayName: String,
    val importerKind: String,
    val importerVersion: Int,
    val createdAt: Long,
    val lastImportAt: Long? = null,
    val lastImportStatus: String? = null
)

/**
 * The provenance of one Task under one [SourceEntity]: what the importer grouped to make it, what
 * it called it, and when it was last seen.
 *
 * [groupPath] is the root-relative path of the thing the importer grouped — a file's own path for
 * file-as-Task, the child folder's path for folder-as-Task — and it is the one string the catalog's
 * containment algebra (§6) needs to place a Task in the source tree. It is not identity; a path may
 * rename under a Task that keeps its id, and the row is a claim about an import, not the Task.
 *
 * [displayName] is what the *importer* called the thing, and is deliberately distinct from the
 * List-side title on [ListItemEntity] and from any wire value: a Task reached from two Lists may be
 * titled differently in each, and the webhook keeps sending the List's title.
 *
 * The foreign key to [SourceEntity] is NO ACTION rather than CASCADE, because a Source is never
 * deleted in v0.2.5 and the row must outlive whatever later release adds deletion — a provenance
 * row is a fact about how the Task was made, not a child of the Source that made it.
 */
@Entity(
    tableName = "task_imports",
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
    indices = [Index(value = ["sourceId", "groupPath"])]
)
data class TaskImportEntity(
    @PrimaryKey val taskId: String,
    val sourceId: String,
    val groupPath: String,
    val displayName: String,
    val importedAt: Long,
    val lastSeenAt: Long
)

/**
 * The Tasks that exist because someone once looked at a folder, before import existed.
 *
 * A closed claim set, in the same sense [LegacyManualTaskEntity] is: every file-backed Task in a
 * v0.1 database was minted by a browse, not by an import, and modelling that as a [SourceEntity]
 * rooted at `''` would occupy `(rootUri, '')` — the one key a user needs to import their whole
 * grant. So the legacy set is *named* rather than *personified*: it has no importer, no root, no
 * status, and it can only shrink — when a real import covers one of its files, the Task is adopted
 * into that Source and this claim row goes.
 *
 * A Task named here is reachable through any List that contains it, exactly as today. It is not in
 * any catalog until it is imported, which is the honest statement: nothing has yet decided that it
 * is imported material.
 */
@Entity(
    tableName = "legacy_source_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LegacySourceTaskEntity(
    @PrimaryKey val taskId: String
)

/**
 * The Task-level counterpart of [LegacyItemIdEntity]: a stable wire id for a Task with no single
 * Asset to borrow one from.
 *
 * A folder round over a multi-Asset Task has no `list_items` row and no single Asset whose
 * [LegacyItemIdEntity] can name it, yet the wire still has to carry a stable `id`. This table is
 * where that name lives, and — like [LegacyItemIdEntity] — it is a compatibility boundary, written
 * lazily by the webhook adapter the first time such a Task is reported. Never by an import run,
 * never by the catalog.
 */
@Entity(
    tableName = "legacy_task_ids",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LegacyTaskIdEntity(
    @PrimaryKey val taskId: String,
    val publicId: String
)
