package com.example.listea

import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewBlock

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
 */
fun directChildOf(groupPath: String, scope: String): Boolean =
    groupPath.substringBeforeLast('/', "") == scope

/**
 * Whether a file of this name has a viewer in this build: the picture and video extensions
 * [MediaPreview] renders, and nothing else — PDF and unknown types included. Eligibility and the
 * catalog badge both call this; it is filename-based because a Task nobody is looking at must still
 * be answerable, and because `MediaPreview` classifies mime-first at render time, which a stored
 * fact would not.
 */
fun assetViewerAvailable(name: String): Boolean {
    val kind = mediaKindOf(name) ?: return false
    return kind == MediaKind.PICTURE || kind == MediaKind.VIDEO
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
