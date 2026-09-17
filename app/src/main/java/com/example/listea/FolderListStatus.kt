package com.example.listea

import com.example.listea.data.FileClaim
import com.example.listea.data.FileCompletion
import com.example.listea.data.FolderListScope
import com.example.listea.data.SharedFileState
import com.example.listea.data.SourceItemRef

/**
 * What a folder in the browser should say about its List relationship.
 *
 * V3.1 guarantees folder-backed scopes never overlap, so a folder either owns a list, sits inside
 * exactly one ancestor's scope, or has no relationship at all.
 */
sealed interface FolderListStatus {
    data object None : FolderListStatus

    /** This exact folder owns [scope]. */
    data class Direct(val scope: FolderListScope) : FolderListStatus

    /**
     * An ancestor folder owns [scope]; this folder's files are already part of it.
     * Progress is this folder's own subtree, not the owning list's overall total — but the
     * webhook shown alongside it still belongs to [scope].
     */
    data class Inherited(
        val scope: FolderListScope,
        val subtreeCompleted: Int,
        val subtreeTotal: Int
    ) : FolderListStatus {
        /** 0 / 0 is never "complete", so an empty subtree gets no tick. */
        val subtreeIsComplete: Boolean
            get() = subtreeTotal > 0 && subtreeCompleted == subtreeTotal
    }
}

data class SubtreeProgress(val completed: Int, val total: Int)

/**
 * Scopes plus derived per-folder progress and per-file checked state, observed once for the whole
 * Folder screen.
 *
 * Two different keyings, on purpose. [subtreeProgress] and [itemCompletion] are about a *list* —
 * how far through its subtree it is, and which of its members are done — so they are keyed by
 * owning list id and root-relative path. [fileCompletion] is about a *file*, keyed by path alone,
 * and covers every file under the root that anything has ever been decided about, whether or not
 * a list has heard of it. The browser's tick reads the second one first: a file reviewed in a
 * folder no list covers is checked, and saying otherwise would be wrong.
 *
 * [owners] is the third keying and the newest: which Todos hold a path, and in which of the two
 * capacities. It is not a fourth kind of state — it is what [fileCompletion] has to collapse, and
 * what the tick is a caveat on. Every held path appears, not only the contended ones, because a
 * file being one Todo's Task is as much a thing to say as its being two Todos'.
 */
data class FolderOwnership(
    val scopes: List<FolderListScope> = emptyList(),
    val subtreeProgress: Map<Pair<Long, String>, SubtreeProgress> = emptyMap(),
    val itemCompletion: Map<Pair<Long, String>, Boolean> = emptyMap(),
    val fileCompletion: Map<String, Boolean> = emptyMap(),
    val owners: Map<String, List<RowOwner>> = emptyMap()
)

/**
 * One Todo's hold on one path, as a browser row shows it.
 *
 * [isOwnTask] is the whole reason this type exists beside [FileClaim]: true when the row *is* the
 * Task that Todo made over this path. Two Todos over one file are two intentions and both are
 * Tasks; one Todo reaching a folder's file as content is the same intention arriving from above,
 * and only the row can say which of those the reader is looking at.
 *
 * [taskName] is carried for the drill-in and for a future "open the Task", not for the row, which
 * names the Todo — that is the thing the user acts on.
 */
data class RowOwner(
    val sourceId: String,
    val sourceName: String,
    val taskId: String,
    val taskName: String,
    val isOwnTask: Boolean
)

/**
 * Indexes every source-backed item once: into each folder it sits under, and into a direct
 * per-file lookup so a file row can show whether it is checked.
 *
 * Item paths are relative to their list's linked folder, so they are rebased onto the SAF root
 * first. Folder attribution walks whole path segments upwards, which is why `bilibili2/file.jpg`
 * can never be counted under `bilibili`.
 */
fun buildFolderOwnership(
    scopes: List<FolderListScope>,
    items: List<SourceItemRef>,
    files: List<FileCompletion> = emptyList(),
    claims: List<FileClaim> = emptyList()
): FolderOwnership {
    val scopePathById = scopes.associate { it.id to (it.relativePath ?: "") }
    // [total, completed] per (listId, folder path)
    val counts = HashMap<Pair<Long, String>, IntArray>()
    val completion = HashMap<Pair<Long, String>, Boolean>()

    for (item in items) {
        val itemPath = item.relativePath ?: continue
        val scopePath = scopePathById[item.listId] ?: continue
        val rootRelative = rootRelativeItemPath(scopePath, itemPath)
        completion[item.listId to rootRelative] = item.isCompleted

        var folder = rootRelative.substringBeforeLast('/', "")
        while (true) {
            val bucket = counts.getOrPut(item.listId to folder) { IntArray(2) }
            bucket[0]++
            if (item.isCompleted) bucket[1]++
            if (folder == scopePath || folder.isEmpty()) break
            folder = folder.substringBeforeLast('/', "")
        }
    }

    return FolderOwnership(
        scopes = scopes,
        subtreeProgress = counts.mapValues { (_, bucket) ->
            SubtreeProgress(completed = bucket[1], total = bucket[0])
        },
        itemCompletion = completion,
        fileCompletion = files.associate { it.relativePath to it.isCompleted },
        owners = indexOwners(claims)
    )
}

/**
 * Groups claims into one owner per path and Todo.
 *
 * Keyed by the pair rather than by the path, because one Todo can hold one path through two Tasks —
 * a file-as-Task run and a folder-as-Task run over the same folder are two intentions even when the
 * Todo is one — and the Todo is the only thing the user can act on.
 *
 * Where a path is held both ways the Task wins. The read already drops the redundant row, so this is
 * a second line of defence rather than the mechanism; it is kept and tested because it is what the
 * model promises, not what one query happens to do.
 */
private fun indexOwners(claims: List<FileClaim>): Map<String, List<RowOwner>> {
    val byPath = LinkedHashMap<String, LinkedHashMap<String, RowOwner>>()

    for (claim in claims) {
        if (claim.relativePath.isEmpty()) continue
        val candidate = RowOwner(
            sourceId = claim.sourceId,
            sourceName = claim.sourceName,
            taskId = claim.taskId,
            taskName = claim.taskName,
            isOwnTask = claim.relativePath == claim.groupPath
        )
        val bySource = byPath.getOrPut(claim.relativePath) { LinkedHashMap() }
        val held = bySource[claim.sourceId]
        if (held == null || (!held.isOwnTask && candidate.isOwnTask)) {
            bySource[claim.sourceId] = candidate
        }
    }

    return byPath.mapValues { (_, bySource) -> bySource.values.toList() }
}

/** The Todos holding [path]. Empty is the ordinary case and means there is nothing to say. */
fun ownersOf(ownership: FolderOwnership, path: String): List<RowOwner> =
    ownership.owners[path].orEmpty()

/**
 * How many Todos hold this file, or null for the unremarkable one-or-none.
 *
 * Null rather than 1, so a caller writes `?.let { ... }` and gets no mark at all: one Todo is the
 * ordinary state of a held file, and the line under the name already says which one it is.
 */
fun fileOwnerMark(owners: List<RowOwner>): Int? = owners.size.takeIf { it > 1 }

/** "Demo" / "Demo · asset": a Todo's name, and the capacity it holds the thing in. */
fun ownerTitle(sourceName: String, isOwnTask: Boolean): String =
    sourceName.ifBlank { "Untitled Todo" } + if (isOwnTask) "" else " · asset"

fun ownerLabel(owner: RowOwner): String = ownerTitle(owner.sourceName, owner.isOwnTask)

/**
 * Which Todos hold this file, in one line, or null when nothing does.
 *
 * A single holder is named outright, and for a file-as-Task Todo that name *is* the row saying the
 * file is one of its Tasks — the case the browser used to be silent about. Several holders are
 * summarised rather than listed, because a list of names at a fixed width ellipsizes away its own
 * tail, and the tail — how many of them cover the file rather than being it — is the one thing this
 * line exists to say.
 */
fun fileOwnerLabel(owners: List<RowOwner>): String? {
    if (owners.isEmpty()) return null
    if (owners.size == 1) return OWNER_PREFIX + ownerLabel(owners.single())

    val covering = owners.count { !it.isOwnTask }
    // Every holder is the file's own Task, so the mark's count is the whole of it and a line
    // repeating that count would be noise on every shared row.
    if (covering == 0) return null
    return OWNER_PREFIX + todoCount(owners.size) + " · " + countLabel(covering, "asset")
}

/**
 * The Todo whose Task *is* this folder, or null.
 *
 * Marked on the path itself and never on the folders beneath it: a folder that merely contains
 * somebody's Task stands where a folder with only Lists below it stands, and [FolderType.NONE]
 * already refuses to call that a relationship the folder has. Folders are few and on screen, so
 * several holders are listed rather than summarised the way a file row's are.
 */
fun folderOwnerLabel(owners: List<RowOwner>): String? = when {
    owners.isEmpty() -> null
    owners.size == 1 -> OWNER_PREFIX + ownerLabel(owners.single())
    else -> OWNER_PREFIX + owners.joinToString(" · ") { ownerTitle(it.sourceName, it.isOwnTask) }
}

/**
 * What the drill-in says above its rows.
 *
 * Three sentences because there are two capacities. A Task over a folder *covers* the file; a Task
 * of its own *is* it. One sentence claimed the second for both, which sends the user looking for a
 * Task that is not there — and the count is of Todos, so it cannot simply be the number of rows
 * either.
 */
fun fileOwnersNote(states: List<SharedFileState>): String {
    val own = states.count { it.isOwnTask }
    val covers = states.size - own
    return "This file is held by " + todoCount(states.size) + ". " + when {
        covers == 0 ->
            "Each has a Task of its own, so what one decides does not reach the others."
        own == 0 ->
            "Each covers it as one of a folder's files, so what one decides does not reach the others."
        else ->
            "Some have a Task of their own and some cover it as one of a folder's files, so what " +
                "one decides does not reach the others."
    }
}

/** "one Todo" / "3 Todos", so a count reads as a sentence rather than as a form field. */
internal fun todoCount(count: Int): String = if (count == 1) "one Todo" else "$count Todos"

/**
 * So a Todo never reads as the List named on the line above it: `CurrentFolderCard` prints an
 * owning List's title and a Todo's in the same shape, and they are different kinds of thing.
 */
private const val OWNER_PREFIX = "Todo · "

/**
 * Resolves ownership for [folderPath] against every scope under the current root. Pure and
 * in-memory: [scopes] comes from a single query, so this costs nothing per visible row.
 * Picks the nearest ancestor (longest matching path) defensively, though non-overlap means
 * there can only ever be one.
 */
fun folderListStatus(ownership: FolderOwnership, folderPath: String): FolderListStatus {
    var nearest: FolderListScope? = null
    var nearestLength = -1

    for (scope in ownership.scopes) {
        val scopePath = scope.relativePath ?: continue
        if (scopePath == folderPath) return FolderListStatus.Direct(scope)
        if (isAncestorOrSame(scopePath, folderPath) && scopePath.length > nearestLength) {
            nearest = scope
            nearestLength = scopePath.length
        }
    }

    return nearest?.let { scope ->
        val progress = ownership.subtreeProgress[scope.id to folderPath] ?: SubtreeProgress(0, 0)
        FolderListStatus.Inherited(scope, progress.completed, progress.total)
    } ?: FolderListStatus.None
}

/**
 * The three relationships the interface ever talks about.
 *
 * The internal model keeps saying Direct and Inherited, which describe *how* a list covers a
 * folder; these are what the user is told, and they describe *what the folder is*. One mapping,
 * used everywhere, so a folder can never read as one thing on a card and another in a header.
 *
 * A folder with lists only *below* it is [NONE]: being an ancestor of a list is not a
 * relationship the folder itself has.
 */
enum class FolderType(val label: String) {
    NONE("None"),
    LIST("List"),
    SUBLIST("Sublist")
}

fun folderTypeOf(status: FolderListStatus): FolderType = when (status) {
    is FolderListStatus.None -> FolderType.NONE
    is FolderListStatus.Direct -> FolderType.LIST
    is FolderListStatus.Inherited -> FolderType.SUBLIST
}

/**
 * Progress for a folder, or null when it has none to show.
 *
 * A List reports its own list's overall progress; a Sublist reports just its own subtree, which is
 * the part of the ancestor list that actually lives here.
 */
fun folderProgressLabel(status: FolderListStatus): String? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> scopeProgressLabel(status.scope)
    is FolderListStatus.Inherited ->
        progressLabel(status.subtreeCompleted, status.subtreeTotal, status.subtreeIsComplete)
}

/** Which list is involved, for a folder that has one. Display only. */
fun folderOwnerTitle(status: FolderListStatus): String? = owningScope(status)?.title

/** The list owning this folder, whether it owns it directly or through an ancestor. */
fun owningScope(status: FolderListStatus): FolderListScope? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> status.scope
    is FolderListStatus.Inherited -> status.scope
}

/** "7 / 18", with a tick once the whole list is done. */
fun scopeProgressLabel(scope: FolderListScope): String =
    progressLabel(scope.completedItems, scope.totalItems, scope.isComplete)

/**
 * Webhook state of a folder that owns its list outright, or null.
 *
 * Deliberately null for a Sublist: the webhook belongs to the ancestor list, and repeating it on
 * every folder underneath would suggest each one can deliver something of its own. One folder,
 * one webhook, shown where it is actually configured.
 */
fun statusWebhookLabel(status: FolderListStatus): String? {
    val scope = (status as? FolderListStatus.Direct)?.scope ?: return null
    return webhookStatusLabel(
        enabled = scope.webhookEnabled,
        lastDeliveryStatus = scope.lastDeliveryStatus,
        lastDeliveryCode = scope.lastDeliveryCode
    )
}
