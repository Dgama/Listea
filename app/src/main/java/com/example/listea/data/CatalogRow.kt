package com.example.listea.data

import com.example.listea.ContentPolicy

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
    val hasUnviewableAsset: Boolean,

    /** The Assets themselves, resolved for the viewer boundary rather than re-read per card. */
    val assets: List<ReviewAsset> = emptyList()
)

/**
 * One registered Source as a row a screen reads: where it is, what imports it, how its last run
 * went, and how many Tasks it currently owns.
 *
 * [taskCount] is a count of `task_imports` rows, which is a count of Tasks in this Source's
 * catalog — the membership the Sources surface is offering to browse. It is not a count of Tasks
 * that exist, and deliberately so: a Task can exist without belonging to any Source, and that is
 * exactly the state §2.3 describes for material nothing has imported yet.
 *
 * [available] is resolved by the caller against the grant the app currently holds, because "this
 * Source's root is not the root you have selected" is a fact about the session and not about the
 * row (§14.4: such a Source is unavailable, never deleted).
 *
 * [keepTree] is the import option the Source was created with, and it is read here rather than
 * recomputed because it is the difference between one Todo opening as a tree and opening as one
 * flat list of Tasks. It is already normalised against [importerKind] when it was stored, so a
 * folder-as-Task row is always `false` and nothing reading it has to ask what the importer does.
 */
data class SourceSummary(
    val id: String,
    val rootUri: String,
    val relativePath: String,
    val displayName: String,
    val importerKind: String,
    val keepTree: Boolean,

    /**
     * The three standings a Todo holds: what a change to its Tasks' content means, whether new
     * material flows into it on its own, and whether deleting it writes its decisions down first.
     *
     * All three are read here because the card is where two of them are *changed* — a switch that
     * did not know its own position would be a control that lies — and because [contentPolicy] is
     * what the next import dialog opens on. The card does not offer that one: it is a word about a
     * run, and the run's own dialog is where it is legible.
     */
    val contentPolicy: String = ContentPolicy.Default.key,
    val followUpstream: Boolean = false,
    val keepStateOnDelete: Boolean = false,

    val lastImportAt: Long?,
    val lastImportStatus: String?,
    val taskCount: Int
)
