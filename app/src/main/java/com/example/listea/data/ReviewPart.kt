package com.example.listea.data

import com.example.listea.decodeTemplateAttachments
import com.example.listea.domain.PartApplicability
import com.example.listea.domain.PartCondition
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.decodePartCondition

/**
 * One part row as the read queries return it: the part, its stored envelope and its current answer,
 * without Assets.
 *
 * A query projection rather than the entity, because every reader wants the envelope beside the
 * part and none wants the Asset join multiplied into it. [payloadKind] is null for a part with no
 * body — a local file or folder part — which the page model reads as *no Content page*.
 *
 * [condition] and [partJudgment] are the raw stored columns: decoding is the builder's job, because
 * a text the domain reader refuses must cost the part its applicability or its judgment rather than
 * surprise a query. [stale] is coalesced to `false` for a part with no state row yet, which is the
 * same thing a fresh state row says.
 */
data class TaskPartRow(
    val taskId: String,
    val partKey: String,
    val position: Int,
    val promptKey: String?,
    val required: Boolean,
    val present: Boolean,
    val availability: String?,
    val payloadKind: String?,
    val payloadSchema: Int?,
    val payloadRevision: Int?,
    val condition: String? = null,
    val partJudgment: String? = null,
    val response: String? = null,
    val answeredAt: Long? = null,
    val stale: Boolean = false
)

/**
 * One decision unit of a Task, resolved for the surfaces that show or judge it.
 *
 * A Task is 1..N parts; each part is one judgment a person makes. A Package Prompt part has a
 * Viewer body ([payloadKind]) and may also present files; a local file or folder part has no body
 * and is exactly the Assets the importer synthesized it from. The part is the unit that owns its
 * Assets and its Resources — never the Task, which is the container.
 *
 * [partKey] is the part's identity **within the Task**, not a global id: a Package Prompt key, the
 * local importer's `local:0`, or a migrated Task's `legacy:<taskId>`. Two Tasks may use the same
 * key for their own parts, and that is not a collision.
 *
 * [present] is the Package's current claim. A part the latest revision no longer names keeps its
 * row and its history but is marked absent, and the page list skips it. [availability] is a runtime
 * code — null means the part can be presented; a code means this build cannot present it, which is
 * a fact about the viewer rather than a deletion.
 *
 * [payloadKind], [payloadSchema] and [payloadRevision] are the stored envelope, read together: a
 * part with a body but no readable envelope is a part with no Content page, not a half-drawn one.
 * The body itself is deliberately absent — the Content page reads it by `(taskId, partKey)` when
 * it is composed, so a long queue does not carry every description.
 *
 * [condition] is the branch this part sits on; [applicability] is what that branch says now. A
 * part without a condition is always [PartApplicability.Active]. [judgment] is the part's own Task
 * Review, or null when the Task's applies — a Task whose parts are all the same kind keeps the one
 * Task-level definition. [stale] marks an answer the current branch no longer asks for; it stays on
 * record but does not complete the part.
 */
data class ReviewPart(
    val partKey: String,
    val position: Int,
    val promptKey: String? = null,
    val payloadKind: String? = null,
    val payloadSchema: Int? = null,
    val payloadRevision: Int? = null,
    val required: Boolean = true,
    val present: Boolean = true,
    val availability: String? = null,
    val assets: List<ReviewAsset> = emptyList(),
    val condition: PartCondition? = null,
    val judgment: TemplateVersionRef? = null,
    val applicability: PartApplicability = PartApplicability.Active,
    val stale: Boolean = false
) {
    /** Whether this part has a Viewer body and therefore a Content page. */
    val hasContent: Boolean get() = payloadKind != null
}

/**
 * One stored part's branch facts, decoded once for every read surface.
 *
 * [availability] is the part's first cause of unavailability: a code the import wrote, or — when the
 * part has none — the code this build assigns to a condition or judgment it cannot read. The other
 * two are null exactly when the column was null.
 */
internal data class PartBranch(
    val condition: PartCondition?,
    val judgment: TemplateVersionRef?,
    val availability: String?
)

/** The code a stored `condition` this build cannot read costs its part. */
internal const val CONDITION_UNREADABLE = "condition-invalid"

/** The code a stored `partJudgment` this build cannot read costs its part. */
internal const val JUDGMENT_UNREADABLE = "unsupported-judgment"

/**
 * Decode the two branch columns fail-closed.
 *
 * A condition that was written and cannot be read is **not** permission to treat the part as
 * unconditional, and a judgment that cannot be read is not permission to inherit the Task's. Both
 * make the part unavailable, and an earlier code already on the part is kept: the report names one
 * cause, exactly as the import does for a Prompt that failed twice over.
 */
internal fun partBranch(
    availability: String?,
    condition: String?,
    partJudgment: String?
): PartBranch {
    val decodedCondition = condition?.let(::decodePartCondition)
    val decodedJudgment = partJudgment?.let { decodeTemplateAttachments(it).firstOrNull() }
    val conditionCode = if (condition != null && decodedCondition == null) CONDITION_UNREADABLE else null
    val judgmentCode =
        if (partJudgment != null && decodedJudgment == null) JUDGMENT_UNREADABLE else null
    return PartBranch(
        condition = decodedCondition,
        judgment = decodedJudgment,
        availability = availability ?: conditionCode ?: judgmentCode
    )
}
