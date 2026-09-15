package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The v13 tables: what a List is *about*, separated from what a List *contains*.
 *
 * Until now one row did both jobs. A `list_items` row was a file's membership in a List and also
 * the place its review decision lived, which is why a file reachable from two Lists needed the
 * whole `file_review_state` detour to keep the two copies from disagreeing. The split here is the
 * one that removes the need for that detour by construction:
 *
 * - [TaskEntity] is the thing judged. It has no path, no title, no List, and no opinion about
 *   where it was found. That is what lets it survive a rename, a move, a re-sync and a List being
 *   rebuilt — all of which change where a file *is* without changing what is being judged.
 * - [AssetEntity] is content presented for a Task. One per Task in the media workflow, and
 *   deliberately not the same row, so assembling what is shown cannot be mistaken for redefining
 *   what is judged.
 * - [TaskStateEntity] is what is true about the Task now, and is editable from anywhere.
 * - [ReviewEventEntity] is what somebody submitted, at one moment, under one template version. It
 *   is never edited and never deleted, because a judgment that happened is a fact about the past.
 *
 * Identity is a plain `String` here rather than [com.example.listea.domain.TaskId]. These are
 * storage rows, and the typed ids exist to keep domain code from swapping one kind of id for
 * another — a guarantee the compiler provides at the boundary and Room would need a TypeConverter
 * to preserve inside it. The mapping is one `.value` in each direction, at the edge.
 */

/**
 * One thing to be judged, and the only durable identity in the review model.
 *
 * Almost nothing about it is stored, and that is the design rather than an unfinished edge. There
 * is no kind, no lifecycle enum, no scope column and no title: a Task with zero Assets is simply a
 * Task with zero Assets, and nothing anywhere is allowed to infer what a Task *is* from how many
 * Assets it happens to have.
 *
 * No title, in particular. Titles stay on [ListItemEntity.title] and on the folder listing, so the
 * webhook's `title` field and the reconcile diff keep working unchanged. A Task reached from two
 * Lists may legitimately be titled differently in each, and the read model resolves a title from
 * whichever entry it came through.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val createdAt: Long
)

/**
 * One piece of content presented for a [TaskEntity].
 *
 * [rootUri] and [relativePath] are the same identity [FileReviewStateEntity] uses — the SAF grant
 * the file sits under, then where it sits beneath that — and they are unique together, so the
 * guarantee that one file has one decision survives the move to Tasks exactly as it was. This is
 * the constraint that makes a second List over the same folder resolve to the *same* Task rather
 * than minting a rival one.
 *
 * Both are nullable because a Task need not have file-backed content at all, and the UNIQUE index
 * tolerates that: SQLite treats two NULLs as distinct, so any number of pathless Assets can exist
 * without colliding. In the media workflow a source-backed Asset always has both.
 *
 * [sortOrder] orders Assets within one Task. It is 0 for everything v0.2 creates, because v0.2 is
 * one Asset per Task; it exists so a future Task holding several does not need a migration to say
 * which comes first.
 */
@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("taskId"),
        Index(value = ["rootUri", "relativePath"], unique = true)
    ]
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val sortOrder: Int,
    val rootUri: String?,
    val relativePath: String?,
    val sourceMissing: Boolean = false
)

/**
 * What is true about a [TaskEntity] now.
 *
 * The successor to [FileReviewStateEntity] and to the decision columns on [ListItemEntity], and it
 * exists for every Task from the moment the Task does — which is what lets the read paths drop the
 * `CASE`/`COALESCE` projection they have always needed. A Task either has state or does not exist.
 *
 * [taskId] is the primary key as well as the foreign key: the relationship is one-to-one, and
 * saying so in the schema is what makes "every Task has exactly one state" a constraint rather
 * than a convention.
 *
 * Deliberately a different fact from [ReviewEventEntity]. This says what is true and can be edited
 * by anyone from anywhere; an event says what was submitted and is never edited. The two often
 * agree, and the model must not depend on that — re-reviewing a completed Task appends an event
 * while this row does not move at all.
 */
@Entity(
    tableName = "task_state",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskStateEntity(
    @PrimaryKey val taskId: String,
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,

    /** The configured actions this Task carries, as ids, in [encodeActionIds]' encoding. */
    val customActions: String = "",

    val updatedAt: Long
)

/**
 * The decisions a Task's current state is carrying.
 *
 * The same shape the two legacy carriers expose, so a caller that used to read a decision off a
 * `list_items` or `file_review_state` row reads it off this instead and sees nothing different.
 */
val TaskStateEntity.decisions: ReviewDecisions
    get() = ReviewDecisions(isCompleted, isFavorite, decodeActionIds(customActions))

/**
 * One submitted review: what was decided, when, under which template, and from where.
 *
 * Append-only, and nothing in v0.2 edits or deletes a row here. Unchecking a Task removes nothing
 * — the judgement was still made, and a count of submissions must not be changed by a later
 * correction to a different fact.
 *
 * [templateId] and [templateVersion] are stored as two columns rather than one `id@version` token
 * so that a query can range over versions of one lineage without parsing text. They name a
 * definition that must still be resolvable after the template moves on, which is the whole reason
 * a version is persisted rather than a name.
 *
 * [response] is a canonical internal snapshot, explicitly **not** the webhook body. The payload
 * carries wire values — what a settings page currently calls each action — while this carries the
 * ids the Task is marked with, so renaming an action changes every future payload and no stored
 * row. See [com.example.listea.domain.encode] for the format.
 *
 * [contextKind] and [contextRef] record the provenance of the judgement: which List, or which
 * folder, the person was working through when they made it. Both nullable, because a Task is
 * reviewable from a place that is neither — and because provenance is worth recording but never
 * worth refusing a submission over.
 *
 * **No foreign key to `tasks`**, unlike [AssetEntity] and [TaskStateEntity] which both cascade.
 * That is deliberate and follows the plan: this table is history, and history is not owned by the
 * thing it is about. A Task could only be deleted here by a bug — nothing in v0.2 deletes a
 * reviewed Task — and if one were, the honest outcome is a review event that has outlived what it
 * was about, not a silently vanishing record of somebody's work.
 */
@Entity(
    tableName = "review_events",
    indices = [Index("taskId", "at")]
)
data class ReviewEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,

    /** When the submission happened, as the app's clock saw it. */
    val at: Long,

    /** `submitted`, and the only value v0.2 writes. */
    val kind: String,

    val templateId: String,
    val templateVersion: Int,
    val response: String,
    val contextKind: String?,
    val contextRef: String?
)

/**
 * The wire name a file-backed Task already had, kept so a webhook can still send it.
 *
 * A compatibility boundary, not part of the model: it exists because a receiver keyed on the
 * `publicId` of a `file_review_state` row must keep seeing that same id after the row it came from
 * stops being the thing that is read. Keyed by [assetId] because an Asset is what a file became.
 *
 * It is deliberately a side table rather than a column on [AssetEntity]. Compatibility vocabulary
 * placed on a model table is vocabulary that outlives the compatibility problem — and this one can
 * be dropped, with no other change, once nothing downstream is keyed on it.
 */
@Entity(tableName = "legacy_item_ids")
data class LegacyItemIdEntity(
    @PrimaryKey val assetId: String,
    val publicId: String
)

/**
 * The Tasks that were manually typed items, and so have no file behind them.
 *
 * Manual entries were the one kind of item whose state really was its own: there was no file to
 * hold a decision, so the columns on its row were the truth. Those Tasks still need to be
 * reviewable and editable after the model changes, and this table is how the cleanup path knows
 * which ones may be deleted outright — [ListsDao.deleteList] and [ListsDao.removeItem] remove the
 * membership, then delete the Tasks named here, inside one transaction.
 *
 * Naming them rather than inferring them from "has no Assets" is the point. That inference is
 * exactly what the model forbids: a Task with zero Assets is a Task with zero Assets, and a future
 * vocabulary or questionnaire Task must not be swept up by a cleanup rule that guessed.
 *
 * The set can only shrink, since manual creation is removed. When it empties, this table and the
 * whole compatibility case can be dropped together.
 */
@Entity(tableName = "legacy_manual_tasks")
data class LegacyManualTaskEntity(
    @PrimaryKey val taskId: String
)
