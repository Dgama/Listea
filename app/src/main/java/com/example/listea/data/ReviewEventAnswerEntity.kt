package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.ViewerEventType
import com.example.listea.domain.encodeValue
import com.example.listea.domain.parseEventValue

/**
 * One viewer event caught by one committed submission, as a row that can be asked for.
 *
 * **A child of the review event, not a second record of the judgment.** `review_events.response`
 * stays canonical: it holds the whole submission, is never edited, and remains the thing a later
 * build reads to know what a person committed. This table splits the same events out of that text so
 * the newest submission's events can be listed — the Info sheet's *Recorded* section — without
 * decoding every history row, and so an event can name the Asset it came from as a reference rather
 * than as a string inside a line.
 *
 * **Keyed by `(reviewEventId, ordinal)`.** An event is ordered within its submission — a template
 * that catches several points means them in the order they were reported — and the same submission
 * can carry the same point twice. The ordinal is that order; the pair is what makes the row
 * addressable. Re-submitting a Task appends a new review event and therefore a new set of rows;
 * nothing here is ever updated in place.
 *
 * **The cascade is to the review event.** Deleting a response must take the rows derived from it,
 * because a row whose parent is gone describes a submission that is no longer on record. There is
 * deliberately **no foreign key to `assets`**: [assetId] is provenance, and deleting an Asset must
 * not erase the history of what was reported about it. That is the direction `task_asset_marks`
 * and `task_review_answers` take one layer up.
 *
 * **Irrevocability is policy, not schema.** A template's commit policy decides whether a submission
 * can be replaced (a like/dislike may be revisited) or may not (a flashcard answer often may not);
 * this table records what was committed either way. When a revisable answer family arrives it will
 * need its own current-state row, as tags and PDF marks have — the append-only history here is
 * exactly wrong for that, and the split is deliberate.
 *
 * [type] is the event's vocabulary key at the moment it was caught and [value] is
 * [encodeValue]'s spelling, the same bytes the response line's event half holds. A row this build
 * cannot read is skipped, never guessed at.
 */
@Entity(
    tableName = "review_event_answers",
    primaryKeys = ["reviewEventId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["reviewEventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["assetId"])]
)
data class ReviewEventAnswerEntity(
    val reviewEventId: String,
    val ordinal: Int,

    /** The Asset the event was taken from, when the Task still holds one for that source. */
    val assetId: String?,

    /** The [ViewerEventType.key] written when the event was caught. */
    val type: String,

    /** The event's value half; see [com.example.listea.domain.encodeValue]. */
    val value: String,

    /**
     * The structured field this row answers, for a `form` Viewer's body; null for a viewer event.
     *
     * Schema 33 adds the column without backfilling it: a row that predates structured answers
     * answered the Task as a whole and is never reinterpreted as a field. A field row's [type] is
     * the field's declared type and [value] its encoded answer, so the two families share a table
     * without either reading the other's rows.
     */
    val fieldKey: String? = null
)

/**
 * The event this row holds, or null when this build's vocabulary cannot read it.
 *
 * A type or value written by a later release is dropped rather than guessed at — the same rule
 * [TaskReviewAnswerEntity.toAddOnAnswer] follows. The row itself stays, and a later build that knows
 * the type will read it.
 */
fun ReviewEventAnswerEntity.toViewerEvent(): ViewerEvent? {
    // A field row's `type` is the field's declared type (`choice.single`), not an event key. It is
    // a different family in the same table, and reading it as an event would be a guess.
    if (fieldKey != null) return null
    val eventType = ViewerEventType.fromKey(type) ?: return null
    return parseEventValue(eventType, value)
}

/** The row one caught event is stored as, under the submission that caught it. */
internal fun ViewerEvent.toReviewEventAnswer(
    reviewEventId: String,
    ordinal: Int,
    assetId: String?
): ReviewEventAnswerEntity = ReviewEventAnswerEntity(
    reviewEventId = reviewEventId,
    ordinal = ordinal,
    assetId = assetId,
    type = type.key,
    value = encodeValue()
)

/** The row one structured field is stored as, under the submission that answered it. */
internal fun FieldAnswer.toReviewEventAnswer(
    reviewEventId: String,
    ordinal: Int
): ReviewEventAnswerEntity = ReviewEventAnswerEntity(
    reviewEventId = reviewEventId,
    ordinal = ordinal,
    assetId = null,
    type = type,
    value = value,
    fieldKey = fieldKey
)
