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
    val assetControls: String,
    val addOn: String,
    val taskReview: String,
    val assetCount: Int,
    val missingAssets: Int,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val packageKey: String? = null,
    val packageTitle: String? = null,
    val promptKey: String? = null,
    val payloadKind: String? = null,
    val payloadSchema: Int? = null,
    val payloadRevision: Int? = null
)

/**
 * One Task's recorded attachments, for the queries that need them beside something else rather than
 * on a row of its own. Query result, not a table — see `ListsDao.observeAssetControlsForList`.
 *
 * [addOn] and [taskReview] are the Task's stamped questionnaire and review references, raw as
 * `task_imports` holds them. They travel with the asset controls because all three are facts about
 * the same import and are read by the same review surface; they are still separate columns, because
 * one is a list of file templates, one is a single Task-level add-on and one is the Task Review the
 * Task was judged under.
 */
data class TaskAssetControls(
    val taskId: String,
    val assetControls: String,
    val addOn: String = "",
    val taskReview: String = "",

    /**
     * The Prompt envelope, for a Task that came from a Package. Null on a media Task and on a Task
     * with no payload row, which a List resolves as *no Content page*.
     *
     * Read beside the other import facts because a List review builds its queue from membership
     * rows that cannot answer them; the payload body is not here for the reason
     * [com.example.listea.data.ReviewItem.payloadKind] gives.
     */
    val packageKey: String? = null,

    /**
     * The Package's manifest title, when the source still has the checkpoint it was imported from.
     *
     * Carried beside [packageKey] because the key is an internal name — `<packageKey>/<promptKey>`
     * is also the synthetic catalog key written into the Task's paths — and the Catalog already
     * names a Package scope by this title. A List row and a Review Info sheet must name it the
     * same way, or the Task reads as coming from a folder nobody can find. Null when the
     * checkpoint is gone; the surfaces then show no Package line rather than the raw key.
     */
    val packageTitle: String? = null,
    val promptKey: String? = null,
    val payloadKind: String? = null,
    val payloadSchema: Int? = null,
    val payloadRevision: Int? = null
)

/**
 * One Resource as a Prompt uses it: where the file is, whether the import found it, and whether
 * *this* Prompt requires it.
 *
 * [relativePath] is the member's path inside the Package, kept for a human reading the Info sheet;
 * [uri] is the document URI the scan saw and is what a Viewer opens.
 */
data class TaskResourceView(
    val resourceKey: String,
    val kind: String,
    val uri: String,
    val relativePath: String,
    val missing: Boolean,
    val required: Boolean
)

/**
 * The Todo one Task came from, and how that Todo exports.
 *
 * `exportKind` is the raw stored word and not a decoded choice: this is the `data` package and
 * `domain/Exports.kt` is where a word becomes a meaning, so the caller decodes. A `data` type that
 * had already decided what an unrecognised word means would be the failing-closed rule living in
 * two places, and this codebase has exactly one of those on purpose.
 */
data class TaskExportSource(
    val taskId: String,
    val sourceId: String,
    val sourceName: String,
    val exportKind: String,

    /**
     * The address the Todo holds, copied when it was configured. Empty means it has none; the
     * planner decides whether that is a failing group or a fallback to the live default.
     */
    val webhookUrl: String = ""
)

/**
 * One Task in the catalog: what it is, where it sits, and whether any of its content cannot render.
 *
 * It carries `isCompleted` and no other decision, since v0.5.1. The tags a Task's files were given
 * live on the pairs, in [assets], because a tag is said about one file under one Task's control and
 * a catalog row is a Task with no file on screen. It used to carry the Task's own `isFavorite` and
 * `customActions`; both were read by nobody once the pair record arrived, and the columns under them
 * are dead.
 */
data class CatalogRow(
    val taskId: String,
    val groupPath: String,
    val displayName: String,
    val isCompleted: Boolean,
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

    /**
     * The add-on this Task was imported with, as `task_imports.addOn` holds it.
     *
     * Here for the same reason [assetControls] is, and it took a device walk to learn it: a queue
     * built from catalog rows is a review surface, and a review surface that does not carry the
     * stamp cannot draw the add-on page — swiping off the last Asset went straight to the next Task
     * while the Task's own row said it had a questionnaire attached. Raw and per Task, resolved by
     * [AddOnResolver] where the page is drawn. `""` means no add-on and therefore no extra page.
     */
    val addOn: String = "",

    /**
     * The Task Review this Task was imported under, as `task_imports.taskReview` holds it.
     *
     * Here for the same reason [addOn] is, and the same device walk made it plain: a queue built
     * from catalog rows is a review surface, and a queue that dropped this stamp resolved every
     * Task through the List's legacy fallback — so a Task imported under a review with its own
     * Submit control opened with the built-in's completion checkbox instead. Raw and per Task,
     * resolved where the session draws its commit control. `""` means a Task imported before
     * v0.8.1, which is read as the built-in rapid review.
     */
    val taskReview: String = "",

    /** True when at least one required Asset has no viewer in this build — e.g. a text file. */
    val hasUnviewableAsset: Boolean,

    /** The Assets themselves, resolved for the viewer boundary rather than re-read per card. */
    val assets: List<ReviewAsset> = emptyList(),

    /**
     * The Prompt this Task answers, if it came from a Package; all null for a media Task.
     *
     * [packageTitle] is the manifest's title, carried so a catalog scope can be named by what the
     * producer called the Package rather than by its key. The three `payload*` fields are the
     * stored envelope; the body itself is deliberately not read on this path.
     */
    val packageKey: String? = null,
    val packageTitle: String? = null,
    val promptKey: String? = null,
    val payloadKind: String? = null,
    val payloadSchema: Int? = null,
    val payloadRevision: Int? = null
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

    /**
     * The add-on the Todo was last imported under, as `sources.addOn` holds it.
     *
     * Read for the card's overflow, which offers changing it: a control that did not know the
     * current add-on could not say whether it was selected or offer to clear it. Only the Source's
     * own value is needed — unlike [assetControls], the card never reports what the Tasks hold,
     * because an add-on is stamped onto every Task by the same write that changes the Source, so
     * the two cannot drift through Settings the way template attachments can.
     */
    val addOn: String = "",

    /**
     * How this Todo exports, and the address it holds.
     *
     * Read for the same reason as the policies above: the Todo card and the next run's dialog have
     * to open on what this Todo will actually do, and the address is the one setting whose value
     * is easy to get wrong — a copied default and a typed one look identical and behave
     * differently when Settings changes.
     */
    val exportKind: String = com.example.listea.domain.EXPORT_CHOICE_NONE,
    val webhookUrl: String = "",
    val webhookFromDefault: Boolean = false,

    val lastImportAt: Long?,
    val lastImportStatus: String?,
    val taskCount: Int
)
