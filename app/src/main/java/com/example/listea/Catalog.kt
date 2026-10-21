package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewBlock
import com.example.listea.domain.AssetKind

/**
 * The catalog's containment algebra and the one place content viewability is classified.
 *
 * A Task's position in the source tree is a single string — `task_imports.groupPath` — and these
 * three functions are the whole of how a scope is compared against it. They are path-segment
 * arithmetic on top of [isAncestorOrSame], never `LIKE` in SQL, because the existing helper already
 * gets the boundary right (`bilibili2/x` is not under `bilibili`) and a `LIKE 'x/%'` is exactly the
 * bug that helper exists to prevent.
 *
 * Review eligibility (§6.4) is answered from three facts: containment here, whether every required
 * Asset resolves, and whether every required Asset has a viewer in this build — [assetViewerAvailable],
 * which is the one place content is classified and nothing else may.
 */

/** True when [scope] contains the whole Task: the Task's group lies at or under the scope. */
fun contained(groupPath: String, scope: String): Boolean = isAncestorOrSame(scope, groupPath)

/** True when [scope] sits strictly inside the Task: the whole of it is not in scope. */
fun partial(groupPath: String, scope: String): Boolean =
    isAncestorOrSame(groupPath, scope) && groupPath != scope

/**
 * True when [groupPath] is a direct child of [scope]: the parent of the Task's group is the scope.
 *
 * This is the deliberate narrowing that keeps Quick Review what it has always been — browsing a
 * folder queues its own files, not everything beneath it — and it is what gives a folder-as-Task
 * scope its Tasks one level down and none below.
 *
 * The empty [scope] is the Source's own root, and a `groupPath` with no `/` in it is a Task sitting
 * there: a file directly in the picked folder, or one Task per child folder when a folder-as-Task
 * run was given the root itself. The missing-delimiter value is `""` and not the usual `this`,
 * which is what makes those Tasks the root's direct children rather than their own parents.
 */
fun directChildOf(groupPath: String, scope: String): Boolean =
    groupPath.substringBeforeLast('/', "") == scope

/**
 * Whether a file of this name has a viewer in this build: the picture, video and PDF extensions
 * [MediaPreview] renders, and nothing else. Eligibility and the catalog badge both call this; it is
 * filename-based because a Task nobody is looking at must still be answerable, and because
 * `MediaPreview` classifies mime-first at render time, which a stored fact would not.
 *
 * Written in terms of [assetKindOf] rather than of `mediaKindOf` directly, which is what makes this
 * and the asset-control lookup the *same* opinion rather than two that agree. A file that has a tag
 * row is exactly a file whose Task can be submitted, and that has to be true by construction: if
 * the two were separate, a file could be judged by no control or governed by a control that never
 * appears, and the user would find it before a test did.
 */
fun assetViewerAvailable(name: String): Boolean = when (assetKindOf(name)) {
    AssetKind.IMAGE, AssetKind.VIDEO, AssetKind.PDF -> true
    AssetKind.OTHER -> false
}

/**
 * Why a Task cannot be review-submitted, from its Assets, or null when it can be.
 *
 * The content-presentation half of eligibility, for a Task already inside a review context: a
 * missing Asset blocks, an Asset no viewer can render blocks, and a Task with no Assets is eligible.
 */
fun reviewBlockFor(assets: List<ReviewAsset>): ReviewBlock? = when {
    assets.any { it.sourceMissing } -> ReviewBlock.MISSING
    assets.any { !assetViewerAvailable(it.name) } -> ReviewBlock.NO_VIEWER
    else -> null
}

/**
 * The same answer for a catalog row, which has already counted its own Assets.
 *
 * Two entry points into one classification rather than two classifications: [reviewBlockFor] asks
 * a Task's resolved Assets, this asks the stored measurement a catalog read produced, and they are
 * the same question because [CatalogRow.missingAssets] and `hasUnviewableAsset` are exactly those
 * two facts counted at read time.
 */
fun catalogBlock(row: CatalogRow): ReviewBlock? = when {
    row.missingAssets > 0 -> ReviewBlock.MISSING
    row.hasUnviewableAsset -> ReviewBlock.NO_VIEWER
    else -> null
}

/**
 * The order catalog rows are read in: by group path, case-insensitively, exact path breaking ties.
 *
 * The same order `scanFolderTree` sorts a directory by, so a catalog reads the way the folder it
 * was imported from reads. Shared rather than repeated, because an order that differed between the
 * queue, the notice and the browser would be three answers to one question.
 */
internal val catalogOrder: Comparator<CatalogRow> =
    compareBy(String.CASE_INSENSITIVE_ORDER, CatalogRow::groupPath).thenBy(CatalogRow::groupPath)

/** One row of a Source's catalog at a scope: a folder to open, or a Task to pick. */
sealed interface CatalogEntry {
    val path: String

    /** A child scope, holding [taskCount] Tasks somewhere below it — not just under it. */
    data class Scope(override val path: String, val name: String, val taskCount: Int) : CatalogEntry

    /** A Task whose whole lies exactly at this scope. */
    data class Task(val row: CatalogRow) : CatalogEntry {
        override val path: String get() = row.groupPath
    }
}

/**
 * The Tasks one level of a catalog holds, in catalog order: what a scope shows and nothing below it.
 *
 * The same set [catalogAtScope] lists under "Tasks", named on its own because three things need to
 * agree about it and only one of them is the screen: the catalog draws these rows, Quick Review
 * queues them, and the preview steps between them. A level that meant one thing to the list and
 * another to the round started from it is the defect this exists to make impossible.
 *
 * A flat Todo has exactly one level, so [scope] is ignored and every Task it holds is the level —
 * which is what "shows no folders" means when there is nothing to descend into.
 */
fun catalogLevel(rows: List<CatalogRow>, scope: String, flat: Boolean = false): List<CatalogRow> =
    catalogAtScope(rows, scope, flat).tasks

/** Everything one scope of a Source's catalog shows. */
data class CatalogView(
    /**
     * The Task this scope sits inside, when the user has browsed into the middle of one. Carried
     * so the scope can say so rather than presenting a subtree of a Task as if it were free
     * standing material — the same fact §7's Quick Review notice reports.
     */
    val enclosing: CatalogRow?,
    val scopes: List<CatalogEntry.Scope>,
    val tasks: List<CatalogRow>
)

/**
 * Whether this scope offers a way into picking — the question the Select button is drawn from.
 *
 * A Task here is something to pick, and so is a folder below it, because a folder's box takes the
 * whole folder. A scope made of folders is therefore where a selection is *most* useful rather than
 * where it is unavailable, which is what the defect this function exists to fix had backwards.
 *
 * [catalogLevel]'s question is the narrower one and is deliberately not this function. A Quick
 * Review round queues the Tasks that lie **exactly** here and does not descend (§7), so a
 * folders-only root honestly has nothing for it — the two buttons look alike, sit side by side, and
 * answer different questions, and collapsing them is what made a tree Todo impossible to build a
 * List from.
 *
 * False only where a scope holds neither — a folder with nothing under it, where every selection
 * would be empty and the button would offer a List of nothing.
 */
fun CatalogView.offersSelection(): Boolean = tasks.isNotEmpty() || scopes.isNotEmpty()

/**
 * A Source's catalog as [scope] shows it: the folder scopes below it, and the Tasks that lie
 * exactly here.
 *
 * "Exactly here" is the same [directChildOf] narrowing Quick Review uses, so browsing a folder in
 * the catalog and Quick Reviewing it queue the same Tasks. The scope's own Tasks are never
 * re-presented as scopes and its scopes never as Tasks, so descending can never show the same row
 * twice.
 *
 * **[flat] is the whole catalog at once, with nothing to descend into.** A Todo whose Create Tasks
 * run was told not to keep the folder tree shows every Task it holds in one list — the folders are
 * not hidden, they are simply not made into places, which is what the answer meant when it was
 * given. It is a *presentation* rule and only that: `groupPath` still names each Task's file, so a
 * row's preview, its decisions and its place in a webhook payload are the same ones it would have
 * had as a tree. Nothing about the import, the queries or the stored rows differs.
 *
 * Read as a property of the whole catalog rather than of a scope, because a flat Todo has exactly
 * one scope and it is the root: `scope` is ignored, so a stale scope left over from a tree cannot
 * show a filtered subset of a list that has no subsets in it.
 */
fun catalogAtScope(rows: List<CatalogRow>, scope: String, flat: Boolean = false): CatalogView {
    if (flat) {
        return CatalogView(
            enclosing = null,
            scopes = emptyList(),
            tasks = rows.sortedWith(catalogOrder)
        )
    }

    val prefix = if (scope.isEmpty()) "" else "$scope/"

    val scopes = rows
        .filter { it.groupPath != scope && it.groupPath.startsWith(prefix) && !directChildOf(it.groupPath, scope) }
        .groupBy { it.groupPath.removePrefix(prefix).substringBefore('/') }
        .map { (name, below) -> CatalogEntry.Scope(prefix + name, name, below.size) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CatalogEntry.Scope::name).thenBy(CatalogEntry.Scope::path))

    return CatalogView(
        enclosing = rows.filter { partial(it.groupPath, scope) }.minWithOrNull(catalogOrder),
        scopes = scopes,
        tasks = rows.filter { directChildOf(it.groupPath, scope) }.sortedWith(catalogOrder)
    )
}
