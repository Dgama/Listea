package com.example.listea.data

/**
 * One part row as the read queries return it: the part and its stored envelope, without Assets.
 *
 * A query projection rather than the entity, because every reader wants the envelope beside the
 * part and none wants the Asset join multiplied into it. [payloadKind] is null for a part with no
 * body — a local file or folder part — which the page model reads as *no Content page*.
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
    val payloadRevision: Int?
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
    val assets: List<ReviewAsset> = emptyList()
) {
    /** Whether this part has a Viewer body and therefore a Content page. */
    val hasContent: Boolean get() = payloadKind != null
}
