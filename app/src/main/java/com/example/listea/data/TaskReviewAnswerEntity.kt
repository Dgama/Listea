package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.encodeAddOnAnswer
import com.example.listea.domain.parseAddOnAnswer

/**
 * One answer a reviewer gave to one question about one Task.
 *
 * **Keyed by the Task, not by an Asset.** The question is about the Task as a whole — *this group of
 * drawings, overall* — so there is no file to hang it on and no pair to join. The primary key is
 * `(taskId, questionId)`: one question asked of one Task has one answer, and re-answering replaces
 * the row rather than appending a history. `createdAt` is therefore preserved across a re-answer
 * while `updatedAt` moves; they are the only trace of an earlier answer, which is this release's
 * whole answer on answer history.
 *
 * **The foreign key is to the Task, and that is the only cascade.** A Task that goes takes its
 * answers with it, because an answer about no Task is not a statement about anything. The add-on is
 * deliberately *not* referenced: removing it from a Todo, or editing a question away, must hide an
 * answer, never delete it, so [authoredAddOnId] and [authoredAddOnVersion] are plain columns
 * recording which questionnaire asked. This is the same rule `task_asset_marks` follows one layer
 * down.
 *
 * [kind] is the question's type at the moment the answer was given. It is stored beside [value]
 * because a stored `3` means a rating only when the row also says `rating`; an answer whose current
 * question has changed type is hidden rather than reinterpreted. [value] is the type's own one-word
 * or free-text spelling — see `encodeAddOnAnswer`.
 */
@Entity(
    tableName = "task_review_answers",
    primaryKeys = ["taskId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"])]
)
data class TaskReviewAnswerEntity(
    val taskId: String,
    val questionId: String,
    val kind: String,
    val value: String,
    val authoredAddOnId: String,
    val authoredAddOnVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * The domain answer this row holds, or null when this build's vocabulary cannot read it.
 *
 * A kind written by a later release, or a value that does not fit its type, is dropped rather than
 * guessed at — the same rule `AssetMarkEntity.toAssetMark` follows. The row stays in the table and
 * is named by nothing; a later build that understands it will read it.
 */
fun TaskReviewAnswerEntity.toAddOnAnswer(): AddOnAnswer? {
    val type = AddOnQuestionType.fromKey(kind) ?: return null
    val parsed = parseAddOnAnswer(type, value) ?: return null
    return AddOnAnswer(
        questionId = questionId,
        type = type,
        value = parsed,
        authoredAddOnId = AddOnId(authoredAddOnId),
        authoredAddOnVersion = authoredAddOnVersion,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

/** The row an answer is stored as, under the Task it is about. */
fun AddOnAnswer.toEntity(taskId: String): TaskReviewAnswerEntity = TaskReviewAnswerEntity(
    taskId = taskId,
    questionId = questionId,
    kind = type.key,
    value = encodeAddOnAnswer(value),
    authoredAddOnId = authoredAddOnId.value,
    authoredAddOnVersion = authoredAddOnVersion,
    createdAt = createdAt,
    updatedAt = updatedAt
)
