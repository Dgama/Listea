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
    val assetControls: String,
    val assetCount: Int,
    val missingAssets: Int,
    val sizeBytes: Long?,
    val lastModified: Long?
)

/**
 * One Task's recorded asset control, for the queries that need it beside something else rather than
 * on a row of its own. Query result, not a table — see `ListsDao.observeAssetControlsForList`.
 */
data class TaskAssetControls(
    val taskId: String,
    val assetControls: String
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

    /**
     * The asset control this Task was imported under, straight off `task_imports`.
     *
     * Undecoded and per Task, which is what every reader here needs it as: a catalog row is a Task
     * with no file on screen, so the kind to resolve for is not yet known — see
     * [ReviewItem.assetControls]. `""` is a Task imported before v0.5.0 and reads as this app's
     * current default.
     */
    val assetControls: String = "",

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
     * what the next import dialog opens on. The card *reports* that one without offering it, and
     * the division is deliberate rather than an omission: the tier is chosen where its three
     * consequences are spelled out, in the run's own dialog, so the card says which one is in
     * force and leaves changing it to the question that can explain it. This paragraph used to say
     * the card did not show it either, on the grounds that it is a word about a run; walking the
     * device found the cost of that — a Todo under `reset` looks exactly like one under `drop`
     * until the next import, which is the run you cannot see coming.
     */
    val contentPolicy: String = ContentPolicy.Default.key,
    val followUpstream: Boolean = false,
    val keepStateOnDelete: Boolean = false,

    /**
     * The asset control the Todo was last imported under, as `sources.assetControls` holds it.
     *
     * Read for the same reason [contentPolicy] is: the card states the Todo's standing answers, and
     * this one is what the *next* run's dialog opens on. It is not, however, what the card's label
     * reports — see [taskAssetControls], which is, and the two exist side by side so the difference
     * is visible in the type rather than only in a comment.
     */
    val assetControls: String = "",

    /**
     * The same, as the Todo's **Tasks** hold it: what a user will actually see on opening one.
     *
     * This is what the card's label reports, and it is deliberately not [assetControls]. A run
     * stamps every Task it makes, and editing Settings afterwards changes what the *next* run
     * produces while leaving every Task already imported exactly as it was — so the two values drift,
     * and a card reporting the Todo's own would say "the next run will use these" at the one moment
     * the user is asking "what am I about to look at". Decision 8's whole point is that the drift is
     * legible where it matters, which is here.
     *
     * `MAX` over the Tasks, which is a stable pick rather than a meaningful one, and stable in the
     * direction that helps: `''` sorts below every record, so a Todo holding one Task with a record
     * and one without shows the record — the answer a reader would have reached anyway — instead of
     * the empty string that means *never chosen*. Tasks under one Todo are stamped by one run and
     * normally agree; when they do not, this names one of them and the Tasks remain the authority.
     * `''` for a Todo with no Tasks at all, which is a Todo whose next run has not happened yet.
     */
    val taskAssetControls: String = "",

    val lastImportAt: Long?,
    val lastImportStatus: String?,
    val taskCount: Int
)
