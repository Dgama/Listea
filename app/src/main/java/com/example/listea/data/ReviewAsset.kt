package com.example.listea.data

/**
 * One piece of content a Task is presented through, reduced to what the presentation needs.
 *
 * The Asset-shaped value the viewer boundary is handed. It carries the source location, a display
 * name, whether the file is still there, and the cached size/modified measurements the Info sheet
 * and the arrangement already read. There is deliberately no persisted kind: the media classifier
 * stays the one that decides image/GIF/video behaviour, and a stored kind arrives only when a
 * second viewer actually needs one.
 *
 * Since v0.5.1 it also carries [assetId] and [decisions], which is the whole of the Task–Asset pair
 * this value *is*: the Asset's identity, and what the Task's control records about this one file.
 * The pair is the unit a chip row draws and a chip tap writes, so the value the review surface
 * walks has to be the pair — a page that knew its file but not which record belonged to it is
 * exactly what put one photograph's marks on another.
 */
data class ReviewAsset(
    val assetId: String = "",
    val sourceUri: String,
    val name: String,
    val sourceMissing: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,

    /**
     * The instance of the Task's asset control on this file.
     *
     * Defaulted to nothing recorded, which is what a caller holding no state row actually knows —
     * and never a licence to inherit the Task's: a Task-level answer handed to a page is the bug
     * this field exists to close.
     */
    val decisions: AssetDecisions = AssetDecisions()
)

/** An [AssetEntity] reduced to what the presentation needs; the name is the file's own. */
fun AssetEntity.toReviewAsset(
    decisions: AssetDecisions = AssetDecisions()
): ReviewAsset = ReviewAsset(
    assetId = id,
    sourceUri = sourceUri.orEmpty(),
    name = relativePath?.substringAfterLast('/').orEmpty(),
    sourceMissing = sourceMissing,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    decisions = decisions
)

/**
 * Why a Task cannot be review-submitted in the scope a queue was built for.
 *
 * Carried on [ReviewItem] rather than re-derived on the surface, so a list screen and a review
 * surface agree about what is blocked and why. It is derived, never stored: eligibility is not a
 * column, not a TaskState field, and not something an import run writes down.
 */
enum class ReviewBlock(val label: String) {
    /** An Asset the Task needs is unresolvable — its source has gone. */
    MISSING("Source missing"),

    /** An Asset the Task needs has no viewer in this build — e.g. a PDF. */
    NO_VIEWER("No viewer in this build")
}
