package com.example.listea

import com.example.listea.data.AssetStateEntity
import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ReviewItem
import com.example.listea.data.TaskPartStateEntity
import com.example.listea.data.TaskStateEntity
import com.example.listea.data.decodeActionIds
import com.example.listea.data.decisions
import com.example.listea.data.rootRelativePathOf
import com.example.listea.domain.ApplicabilityPart
import com.example.listea.domain.evaluateApplicability

/**
 * Why a scope has no Task to start a round on, in the words §7 asks for.
 *
 * Built for a scope whose queue came back empty and for no other: when there is something to
 * review the screen has nothing to explain, and a notice would be a second answer to a question
 * the queue already answered. The third case §7 names — *nothing imported here* — is not a notice
 * at all; it is [FolderReview.NotImported], because the answer there is a dialog and not a
 * sentence.
 */
sealed interface ScopeNotice {
    /**
     * Every Task touching this scope straddles its boundary: the whole of each lies above it, so
     * queueing one here would be queueing half a Task. Deliberately about *this* scope — the Task
     * is neither broken nor ineligible, it is simply not here.
     */
    data class OnlyPartial(val taskName: String, val parentScope: String) : ScopeNotice

    /**
     * Tasks sit exactly in this scope and none of them can be judged: some of their content is
     * missing from the source, some of it has no viewer in this build, or both at once.
     */
    data class NothingEligible(val missingContent: Boolean, val noViewer: Boolean) : ScopeNotice
}

/**
 * Why [scope] has nothing to queue, or null when it does.
 *
 * A pure read of the same catalog rows [folderReviewQueue] queues from, so a scope cannot be told
 * it is empty by one function and non-empty by the other. Order matters here and is the plan's:
 * a Task that fits the scope but cannot be judged is the more specific answer than one that does
 * not fit at all is, and neither is worth saying when there is something to review.
 *
 * The level is [catalogLevel] and not [directChildOf], for the reason it is there: a flat Todo
 * shows every Task it holds at one level, so that level — not the empty prefix of the files' paths
 * — is what an empty round has to have an answer about.
 */
fun scopeNoticeOf(rows: List<CatalogRow>, scope: String, flat: Boolean = false): ScopeNotice? {
    val direct = catalogLevel(rows, scope, flat)
    if (direct.any { it.missingAssets == 0 && !it.hasUnviewableAsset }) return null

    if (direct.isNotEmpty()) {
        return ScopeNotice.NothingEligible(
            missingContent = direct.any { it.missingAssets > 0 },
            noViewer = direct.any { it.hasUnviewableAsset }
        )
    }

    // Nothing of the scope's own. The only thing left to explain is a Task whose whole lies above
    // it, which is what browsing into the middle of one looks like. Sorted the way the queue
    // sorts, so the Task named is the first one a person would meet going back up.
    return rows.filter { partial(it.groupPath, scope) }
        .minWithOrNull(catalogOrder)
        ?.let { ScopeNotice.OnlyPartial(it.displayName, it.groupPath.substringBeforeLast('/', "")) }
}

/**
 * An item path, which is stored relative to its list's linked folder, rebased onto the SAF root
 * so it can be compared against browsed folder paths.
 */
fun rootRelativeItemPath(scopePath: String, itemPath: String): String =
    rootRelativePathOf(scopePath, itemPath)

/**
 * One direct file of a browsed folder, before the decisions held about it are attached.
 *
 * [sizeBytes] and [lastModified] are carried rather than dropped because the folder's filter and
 * sort are read off them, and Quick Review now reviews exactly what that filter and sort left on
 * screen. Null means the provider did not say, which every filter treats as "does not exclude" —
 * see the unknown rule in FileArrange.kt.
 */
data class FolderFile(
    val name: String,
    val uri: String,
    val rootRelativePath: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** The direct files of [folderPath], named the way a decision about them is named. */
fun folderFilesOf(folderPath: String, contents: FolderContents): List<FolderFile> =
    contents.entries
        .filter { !it.isDirectory }
        .map {
            FolderFile(
                name = it.name,
                uri = it.uri.toString(),
                rootRelativePath = rootRelativePathOf(folderPath, it.name),
                sizeBytes = it.sizeBytes,
                lastModified = it.lastModified
            )
        }

/** The two lines an empty round shows: what it is, and the one thing to do about it. */
data class EmptyRoundCopy(val headline: String, val detail: String)

/**
 * What an empty round says, given everything that could have emptied it.
 *
 * Pure, and the only place these sentences exist, because an empty round is the one screen a user
 * has no way to interrogate: there is nothing on it to tap, so the words are the whole of the
 * answer. [scopeNotice] comes first because it is a fact about the material that no filter, sort or
 * checked state can change; the rest are about the view the user is holding over material that does
 * have something in it.
 *
 * The last four say "here" and let [name] say where, because a round can be over a folder or over a
 * whole Todo, and the reasons it can come back empty are the same ones either way. Only
 * [ScopeNotice]'s own two sentences name a folder, and only a folder round can produce them.
 */
fun emptyRoundCopy(
    scopeNotice: ScopeNotice?,
    name: String,
    fileCount: Int,
    matched: Int,
    filterIsDefault: Boolean
): EmptyRoundCopy = when {
    scopeNotice is ScopeNotice.OnlyPartial -> EmptyRoundCopy(
        headline = "This folder contains only part of \"" + scopeNotice.taskName + "\"",
        detail = "Open " + scopeNameOf(scopeNotice.parentScope) + " to review the complete Task."
    )

    scopeNotice is ScopeNotice.NothingEligible -> EmptyRoundCopy(
        headline = "Nothing here can be reviewed",
        detail = when {
            scopeNotice.missingContent && scopeNotice.noViewer ->
                "Some of its content is missing from the source folder, and some of it has no " +
                    "viewer in this build."
            scopeNotice.missingContent -> "Its content is missing from the source folder."
            else -> "PDF - viewer available in v0.3."
        }
    )

    fileCount == 0 -> EmptyRoundCopy("Nothing to review here yet", name)
    matched == 0 -> EmptyRoundCopy("Nothing here matches the filter", name)
    filterIsDefault -> EmptyRoundCopy("Everything here is already checked", name)
    else -> EmptyRoundCopy("Everything the filter keeps here is already checked", name)
}

/** How a scope path is named in a sentence. `''` is the root the user picked, which has no name. */
private fun scopeNameOf(path: String): String =
    if (path.isEmpty()) "the selected folder" else "\"" + path + "\""

/**
 * A browsed scope's catalog Tasks turned into a review queue.
 *
 * The whole of what Quick Review resolves, and deliberately pure: the catalog decides which Tasks
 * exist, the scope decides which of them are reviewable, and the folder listing — when there is one
 * — decides the order. Nothing about any List takes part, and nothing here creates or imports a
 * Task: a scope with no imported material resolves to an empty queue, which is the honest answer
 * rather than a mint.
 *
 * A Task is queued only when the whole of it is a direct child of the scope, every Asset it needs
 * resolves, and every Asset it needs has a viewer — eligibility §6.4, narrowed to a scope's own
 * direct children by §6.2. A partial Task and a Task whose content cannot be presented are
 * excluded, never shown as a smaller Task.
 *
 * [states] are the registrations the browse still writes, and supply the folder round's wire id and
 * queue handle for file-backed Tasks; a folder-as-Task scope has no registrations and no listing,
 * so its Tasks are ordered by `groupPath` with the same case-insensitive comparator the scan uses.
 */
fun folderReviewQueue(
    rootUri: String,
    scope: String,
    files: List<FolderFile>,
    rows: List<CatalogRow>,
    states: List<FileReviewStateEntity> = emptyList()
): List<ReviewItem> {
    val eligible = rows
        .filter { row ->
            directChildOf(row.groupPath, scope) && reviewable(row)
        }
        .associateBy { it.groupPath }
    val byPath = states.associateBy { it.relativePath }

    fun itemFor(row: CatalogRow, file: FolderFile?): ReviewItem =
        reviewItemFor(rootUri, row, file, file?.let { byPath[it.rootRelativePath] })

    return if (files.isNotEmpty()) {
        // The listing is the source of order, exactly as v0.1: walk it, drop files with no Task.
        files.mapNotNull { file -> eligible[file.rootRelativePath]?.let { itemFor(it, file) } }
    } else {
        eligible.values.sortedWith(catalogOrder).map { itemFor(it, null) }
    }
}

/**
 * A Todo's whole catalog as a review queue: every Task it holds that can be judged, in catalog order.
 *
 * This is the round a Todo starts from, and it is the same [ReviewItem] a folder round makes — same
 * eligibility, same decisions, same write path — over a different set of rows. What it drops is the
 * *scope*: a Todo is not a folder, and asking whether each Task is a direct child of anything would
 * be asking a question with no answer for a flat one, where every Task's `groupPath` names a file
 * that no folder row sits above.
 *
 * Nothing is registered and nothing is read off disk: every Task here came from an import run that
 * already happened, which is the whole difference §7 describes between reviewing a folder and
 * reviewing a Todo.
 */
fun taskReviewQueue(rootUri: String, rows: List<CatalogRow>): List<ReviewItem> =
    rows.filter(::reviewable)
        .sortedWith(catalogOrder)
        .map { reviewItemFor(rootUri, it, null, null) }

/**
 * Whether a catalog Task may be put in a queue at all: §6.4, the content-presentation half.
 *
 * Asked of every part rather than of the first Prompt: a Task presenting three Prompts is one
 * judgment short if this build can draw two of them, so one unviewable body — or one part this
 * build cannot present at all — keeps the whole Task out, exactly as one unviewable file always
 * has.
 */
private fun reviewable(row: CatalogRow): Boolean =
    row.missingAssets == 0 && !row.hasUnviewableAsset &&
        row.parts.none { part ->
            part.present && (part.availability != null ||
                (part.payloadKind != null &&
                    !payloadViewerAvailable(part.payloadKind, part.payloadSchema)))
        }

/**
 * A queue with each item's decisions taken from the state as it stands now, rather than from
 * whatever the queue was resolved with.
 *
 * A round freezes *which* Tasks it walks and must not freeze what has been decided about them.
 * Checking a Task or tagging it is the normal way through a round, and a surface drawing its
 * controls from the queue it built on entry would write every tap to the database and show nothing:
 * it emitted once, and every later write landed behind a value nobody re-read. So the membership
 * and the order come from the freeze, and the decisions come from here.
 *
 * Only the decisions, and that is the whole of the rule. A Task's name, its measurements and why it
 * is blocked are answers about the *material*, which is what an import wrote and what a catalog row
 * read; re-resolving them per emission would let a row change shape under a round that had already
 * fixed its membership. A Task the read does not name keeps what the queue gave it, which is what a
 * Task that has gone looks like from the review that is still holding it.
 *
 * The decisions a queue carries are only meaningful where a query joined `task_state` and its
 * pairs' states in, so this is the same value re-read and never a second opinion about it — the
 * same re-read [ListsViewModel.deliverFolderReview] already does before building a payload.
 *
 * Both halves, since v0.5.1: completion arrives by Task and the marks arrive by pair, and a round
 * that re-read one and not the other would show a tick that follows the write and a chip that does
 * not. A Task the read does not name, or a pair it does not carry, keeps what the queue gave it —
 * the same treatment a Task that has gone already gets.
 *
 * The answers in [partStates] are the third half, and the one this round adds: applicability is
 * derived from a conditional part's source answer and never stored, so a round that kept the
 * branch facts it resolved on entry would keep showing *waiting* after the gate was answered, and
 * keep offering a page after the branch closed. The condition, the judgment and the envelope are
 * material and stay frozen; only the answer-dependent half — applicability and the stale flag — is
 * re-derived from the rows read now, by the same evaluator the write path and the export call.
 */
fun withLiveDecisions(
    queue: List<ReviewItem>,
    states: List<TaskStateEntity>,
    assetStates: List<AssetStateEntity>,
    partStates: List<TaskPartStateEntity>
): List<ReviewItem> {
    if (queue.isEmpty()) return queue
    val byTask = states.associateBy { it.taskId }
    val byAsset = assetStates.associateBy { it.assetId }
    val byPart = partStates.associateBy { it.taskId to it.partKey }
    return queue.map { item ->
        val live = byTask[item.taskId]?.isCompleted
        // The pair records live on the Assets, and the Assets live on the parts, so the re-read
        // walks the parts and leaves every other part field — its key, its envelope, its
        // availability — exactly as the queue resolved it.
        val answered = item.parts.associate { part ->
            part.partKey to (byPart[item.taskId to part.partKey]?.response.orEmpty())
        }
        val applicability = evaluateApplicability(
            item.parts.map { part ->
                ApplicabilityPart(
                    partKey = part.partKey,
                    position = part.position,
                    required = part.required,
                    present = part.present,
                    availability = part.availability,
                    condition = part.condition,
                    kind = part.payloadKind,
                    response = answered[part.partKey]
                )
            }
        )
        val parts = item.parts.map { part ->
            part.copy(
                assets = part.assets.map {
                    it.copy(decisions = byAsset[it.assetId]?.decisions ?: it.decisions)
                },
                applicability = applicability[part.partKey] ?: part.applicability,
                stale = byPart[item.taskId to part.partKey]?.stale ?: part.stale
            )
        }
        if (live == null && parts == item.parts) {
            item
        } else {
            item.copy(isCompleted = live ?: item.isCompleted, parts = parts)
        }
    }
}

/**
 * One catalog row as a queue item.
 *
 * [file] is the browsed file the row was matched to, when a folder round had a listing to match it
 * against, and [state] is that file's registration. A Todo round has neither, and the row alone
 * answers everything the item needs — which is why the two queues can share this.
 */
private fun reviewItemFor(
    rootUri: String,
    row: CatalogRow,
    file: FolderFile?,
    state: FileReviewStateEntity?
): ReviewItem = ReviewItem(
    id = state?.id ?: 0L,
    taskId = row.taskId,
    // Off the registration row, not made here: a folder round's payload has to name a file-backed
    // Task the same way every time it reports on it.
    publicId = state?.publicId.orEmpty(),
    title = row.displayName,
    sourceUri = file?.uri,
    // Relative to the scope being reviewed: a direct file's own name, a folder Task's name.
    relativePath = file?.name ?: row.displayName,
    sourceMissing = false,
    target = DecisionTarget.File(rootUri, file?.rootRelativePath ?: row.groupPath),
    isCompleted = row.isCompleted,
    sizeBytes = file?.sizeBytes ?: row.sizeBytes,
    lastModified = file?.lastModified ?: row.lastModified,
    parts = row.parts,
    rootRelativePath = row.groupPath,
    // Off the catalog row, which is where an import's own answers live — the same place the name,
    // the measurements and the block come from, and frozen for the same reason as those: a round
    // fixes what the *material* is and re-reads only what has been decided about it.
    assetControls = row.assetControls,
    // And the Task's own add-on beside them, because the page that draws it is built from this item
    // and not from the catalog: a queue that dropped the stamp reviewed Tasks as if no questionnaire
    // were attached, while their rows said otherwise.
    addOn = row.addOn,
    // And the Task's own Task Review, for the same reason and with a sharper consequence: a queue
    // that dropped it would judge every Task by the List's legacy fallback, and a Task imported
    // under *Sample a point* would open with the built-in's completion checkbox instead of the
    // template's own Submit.
    taskReview = row.taskReview,
    // And the Package the composition came from, for the same reason: the Info sheet names it.
    // Each part's own Prompt envelope travels inside [parts], so a queue item that dropped the
    // parts would present a Package Prompt as a Task with no pages at all.
    packageKey = row.packageKey,
    packageTitle = row.packageTitle
)
