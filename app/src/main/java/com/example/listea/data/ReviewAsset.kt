package com.example.listea.data

/**
 * One piece of content a Task is presented through, reduced to what the presentation needs.
 *
 * The Asset-shaped value the viewer boundary is handed. It carries the source location, a display
 * name, whether the file is still there, and the cached size/modified measurements the Info sheet
 * and the arrangement already read — and nothing else. There is deliberately no persisted kind: the
 * media classifier stays the one that decides image/GIF/video behaviour, and a stored kind arrives
 * only when a second viewer actually needs one.
 */
data class ReviewAsset(
    val sourceUri: String,
    val name: String,
    val sourceMissing: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** An [AssetEntity] reduced to what the presentation needs; the name is the file's own. */
fun AssetEntity.toReviewAsset(): ReviewAsset = ReviewAsset(
    sourceUri = sourceUri.orEmpty(),
    name = relativePath?.substringAfterLast('/').orEmpty(),
    sourceMissing = sourceMissing,
    sizeBytes = sizeBytes,
    lastModified = lastModified
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
