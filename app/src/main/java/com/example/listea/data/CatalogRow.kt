package com.example.listea.data

/**
 * The catalog projection: a Task as a browse-and-select row, over `task_imports` plus its state and
 * Asset measurements.
 *
 * The catalog is a read projection, not an entity and not an owner: it has no table, no lifecycle
 * and deletes nothing. [CatalogRow] is what one row of it looks like, in the same style as
 * [FolderListScope]. [hasUnviewableAsset] is resolved when the row is built rather than re-derived
 * per row on a list screen, so a badge is a stored answer to one classification, not a per-cell
 * recomputation.
 */

/** The SQL aggregation of [CatalogRow], before the viewability flag is resolved in Kotlin. */
data class CatalogProjection(
    val taskId: String,
    val groupPath: String,
    val displayName: String,
    val isCompleted: Boolean,
    val isFavorite: Boolean,
    val customActions: String,
    val assetCount: Int,
    val missingAssets: Int,
    val sizeBytes: Long?,
    val lastModified: Long?
)

/** One Task in the catalog: what it is, where it sits, and whether any of its content cannot render. */
data class CatalogRow(
    val taskId: String,
    val groupPath: String,
    val displayName: String,
    val isCompleted: Boolean,
    val isFavorite: Boolean,
    val customActions: String,
    val assetCount: Int,
    val missingAssets: Int,
    val sizeBytes: Long?,
    val lastModified: Long?,

    /** True when at least one required Asset has no viewer in this build — e.g. a PDF. */
    val hasUnviewableAsset: Boolean
)
