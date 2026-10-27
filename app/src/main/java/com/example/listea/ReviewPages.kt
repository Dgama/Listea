package com.example.listea

import com.example.listea.data.ReviewItem
import com.example.listea.domain.StoredAddOnVersion

/**
 * The pages one Task presents, in the order the inner arrows and the queue guide walk them.
 *
 * Before v0.8.2 this was arithmetic — one page per Asset, plus one for the add-on — repeated by the
 * navigator, the guide, the resume cursor and three viewing surfaces. A Package Prompt has content
 * that is not a file at all, so the arithmetic stopped being true: a Prompt with a photo has a
 * Content page *and* no Asset page. This list is now the one source for the count, the step, the
 * entries and which page is showing.
 *
 * The order is fixed: Content first, then the Task's Assets in declared order, then the Add-on.
 * A media Task has no Content page and is exactly as many pages as it always was.
 */
sealed interface ReviewPage {

    /** The Prompt body: the description a Viewer draws. Never a file, never an Asset. */
    data class Content(val taskId: String) : ReviewPage

    /** One of the Task's files. */
    data class Asset(val asset: com.example.listea.data.ReviewAsset) : ReviewPage

    /** The questionnaire attached to the Task, after everything else. */
    data object AddOn : ReviewPage
}

/**
 * This Task's pages.
 *
 * [hasAddOn] is the caller's resolved answer — this build can read the questionnaire the Task was
 * stamped with — and not a re-resolution: the session already resolves it once to draw the page.
 */
internal fun reviewPages(item: ReviewItem, hasAddOn: Boolean): List<ReviewPage> = buildList {
    if (item.payloadKind != null) add(ReviewPage.Content(item.taskId))
    item.assets.forEach { add(ReviewPage.Asset(it)) }
    if (hasAddOn) add(ReviewPage.AddOn)
}

/** Whether a queue item has a page that is content rather than a file. */
internal fun ReviewItem.hasContentPage(): Boolean = payloadKind != null

/**
 * The Asset index a unified page index names.
 *
 * [ReviewPage]'s order puts Content first, so a Prompt Task carrying both a body and files has its
 * Assets one page to the right of the indices an Asset-only surface expects. Surfaces that hand the
 * unified index to [AssetRun] — whose `selectedIndex` counts Assets — must convert, or a Prompt
 * opens on its second file. The Content page names no Asset and converts to `-1`, which [AssetRun]
 * reads as *show the first asset without claiming a position*; the add-on page converts to
 * `assets.size`, the sentinel that page is recognised by. (Before v0.8.2f the queue was Asset-only
 * whenever a file was involved, so the bug was latent rather than visible.)
 */
internal fun assetIndexOf(page: Int, hasContent: Boolean): Int =
    if (hasContent) page - 1 else page

/**
 * The unified page index an Asset index names, the inverse of [assetIndexOf].
 *
 * Used when a surface reports a file chosen within [AssetRun] and the caller has to write it back
 * as a page.
 */
internal fun pageOfAsset(assetIndex: Int, hasContent: Boolean): Int =
    if (hasContent) assetIndex + 1 else assetIndex

/**
 * One Task's page list and where on it a surface is standing.
 *
 * Every surface that shows a Task — Review, Quick Review, the List item viewer and the Todo
 * preview — asks this instead of computing a count and an Asset index of its own. v0.8.2f's fix
 * round found what duplicated arithmetic does: two readers silently dropped the add-on page and
 * one guide row was off by the Content page, while Review was right. The derived properties exist so
 * the count, the current page and the Asset index cannot drift apart again.
 *
 * [pageIndex] is already clamped, because a resync can take a page away under a selection that was
 * already made. [assetIndex] is the value [AssetRun] expects: [assetIndexOf], so `-1` on the
 * Content page and `assets.size` on the add-on page — the sentinel that page is recognised by.
 */
internal data class TaskPageState(
    val pages: List<ReviewPage>,
    val pageIndex: Int
) {
    val pageCount: Int get() = pages.size
    val current: ReviewPage? get() = pages.getOrNull(pageIndex)
    val hasContentPage: Boolean get() = pages.firstOrNull() is ReviewPage.Content
    val hasAddOnPage: Boolean get() = pages.lastOrNull() is ReviewPage.AddOn
    val hasPageLevel: Boolean get() = pages.size > 1
    val onContentPage: Boolean get() = current is ReviewPage.Content
    val onAddOnPage: Boolean get() = current is ReviewPage.AddOn
    val assetIndex: Int get() = assetIndexOf(pageIndex, hasContentPage)
}

/**
 * [item]'s pages, given the questionnaire this build resolved for it, with [requestedPage] clamped.
 *
 * A null [addOn] is both "the Task was never stamped" and "this build cannot read the stamp" — the
 * fail-closed answer `AddOnResolver.readableVersion` gives — and either way the Task simply has no
 * extra page rather than a page it cannot fill.
 */
internal fun taskPageState(
    item: ReviewItem,
    addOn: StoredAddOnVersion?,
    requestedPage: Int
): TaskPageState {
    val pages = reviewPages(item, hasAddOn = addOn != null)
    return TaskPageState(
        pages = pages,
        pageIndex = requestedPage.coerceIn(0, maxOf(pages.lastIndex, 0))
    )
}

/** Where an accepted commit goes, or null when the template keeps the reviewer on the page. */
internal enum class CommitArrival { AddOnPage, NextTask }

/**
 * Where a commit lands when the template advances.
 *
 * The add-on page is the Task's **last** page, and the documented place a Task is left from: the
 * add-on contract says a forward move off the questionnaire completes the Task, so a commit made
 * anywhere before it — a Prompt's Like/Dislike, a sampled point's Submit — must arrive there
 * rather than advance the queue. Before v0.8.2f it advanced directly, which silently skipped the
 * questionnaire: a Todo with an add-on could be reviewed end to end without the page ever being
 * shown, and no answer was recorded. v0.8.2f records the fix.
 *
 * A commit made while already on the add-on page advances, exactly as it always did, so the page
 * never holds the queue up. A template that declares `advanceAfterCommit = false` stays put: that
 * is the template's choice about its own confirmation step, and the add-on is still reachable
 * with the inner arrow.
 */
internal fun commitArrival(
    advanceAfterCommit: Boolean,
    hasAddOnPage: Boolean,
    currentPage: Int,
    pageCount: Int
): CommitArrival? = when {
    !advanceAfterCommit -> null
    !hasAddOnPage -> CommitArrival.NextTask
    currentPage != pageCount - 1 -> CommitArrival.AddOnPage
    else -> CommitArrival.NextTask
}
