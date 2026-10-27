package com.example.listea.data

import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.parseAddOnAnswer

/**
 * The Task-level facts the v0.6.0 payload needs, read in one query.
 *
 * [displayName] is null for a Task no Todo owns — a manual item, or a legacy Task that predates the
 * catalog. The payload falls back to the title on the row that reached the Task; an empty string
 * here would be a name the user never gave.
 *
 * [assetControls] and [addOn] are null for the same reason: with no import there is no attachment,
 * which the payload renders as an empty `templates` array and no `addOn` object rather than as an
 * error. They are separate columns because they are separate layers: `assetControls` is a list of
 * file templates, `addOn` is one Task-level questionnaire reference.
 */
data class PayloadTaskRow(
    val taskId: String,
    val publicId: String,
    val displayName: String?,
    val assetControls: String?,
    val addOn: String? = null
)

/**
 * One Task's Prompt envelope, as the payload sees it.
 *
 * Null [kind] means no payload row: a media Task, or a manual item. Such a Task is never a v5
 * `content` object and never makes a round call itself v5.
 *
 * [promptKey] is null for a payload Task that is not linked to a Package Prompt — the envelope is
 * enough to say what kind of content it is, and the key is the Package's own name for it when there
 * is one. [revision] is the revision the stored body was read at, which is the fact a receiver
 * needs to interpret a choice whose labels may have moved since.
 */
data class PayloadPromptRow(
    val taskId: String,
    val packageKey: String?,
    val partKey: String = "local:0",
    val promptKey: String?,
    val kind: String?,
    val schemaVersion: Int?,
    val revision: Int?
)

/**
 * One TaskPart as the v6 payload sees it: its identity, its optional body, and its current answer.
 *
 * The successor to [PayloadPromptRow] for the v6 writer, and deliberately a superset of it: a part
 * with no payload is still a part (the degenerate one a local file or folder import creates), and a
 * part nobody answered still has to be exported as *pending* rather than omitted. The join is
 * `LEFT` on both tables for exactly that reason — absence of a body is a fact, not a missing row.
 *
 * [response] is the part's current canonical answer, or null/empty while pending. It is read as
 * text for the same reason [PayloadReviewRow.response] is: only `ReviewResponse.parse` may decide
 * whether this build can read it.
 */
data class PayloadPartRow(
    val taskId: String,
    val partKey: String,
    val position: Int,
    val promptKey: String?,
    val required: Boolean,
    val present: Boolean,
    val kind: String?,
    val schemaVersion: Int?,
    val revision: Int?,
    val response: String? = null
)

/**
 * The latest stored response for a Task, as text.
 *
 * Read as text for the same reason `observeLatestResponse` is: the column is a permanent record
 * written by a build that may be newer than this one, and only `ReviewResponse.parse` may decide it
 * is unreadable. The export reader does not guess at a row it cannot parse.
 */
data class PayloadReviewRow(
    val taskId: String,
    val response: String
)

/**
 * One Asset as the payload sees it: its name, where its file sits, and the pair's decisions.
 *
 * [relativePath] is the Asset's own stored path, relative to the Upstream's linked root. The payload
 * derives its folder from this and never from the List or the round, which is what makes the value
 * independent of how the Task was packaged.
 *
 * [selections] is null only for an Asset whose state row is missing, which the write paths do not
 * produce; the assembly treats it as no decisions rather than inventing some.
 */
data class PayloadAssetRow(
    val taskId: String,
    val assetId: String,
    val publicId: String,
    val relativePath: String?,
    val sourceMissing: Boolean,
    val sortOrder: Int,
    val selections: String?,
    /**
     * Where the file is, read only so the exporter can hash it when the Asset carries marks.
     *
     * The fingerprint decides whether a stored passage still describes the document that will be
     * sent with it; a row that cannot be read is therefore a row whose marks are omitted rather
     * than sent against unknown bytes.
     */
    val sourceUri: String? = null,

    /** The part the Asset is presented for; v6 groups a Task's Assets by it. */
    val partKey: String? = null
)

/**
 * One stored PDF passage as the export sees it.
 *
 * [documentFingerprint] is the hash the anchor was last verified against. The exporter compares it
 * with the file's fingerprint at send time: a mark is only a true statement about *this* document
 * when the two agree. [exactText] and [pageIndex] are the quote the receiver shows; the character
 * range is a local hint and never leaves the device.
 */
data class PayloadMarkRow(
    val id: String,
    val assetId: String,
    val toolId: String,
    val kind: String,
    val value: String,
    val pageIndex: Int,
    val exactText: String,
    val documentFingerprint: String,
    val createdAt: Long
)

/**
 * One stored add-on answer as the export sees it.
 *
 * [authoredAddOnId] and [authoredAddOnVersion] are read because visibility is a question about them:
 * an answer belongs to the questionnaire that asked, so a Task whose add-on was replaced does not
 * inherit the previous one's answers even if a question id collided. [kind] and [value] are the raw
 * stored pair; the exporter parses them and omits a row it cannot read rather than sending a guess.
 */
data class PayloadAnswerRow(
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
 * The domain answer a payload row holds, or null when this build's vocabulary cannot read it.
 *
 * The same fail-closed rule the entity conversion applies — a kind or value written by a later
 * build is skipped rather than sent under a guess — kept beside the row because the payload reads
 * these columns directly rather than through [TaskReviewAnswerEntity].
 */
fun PayloadAnswerRow.toAddOnAnswer(): AddOnAnswer? {
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
