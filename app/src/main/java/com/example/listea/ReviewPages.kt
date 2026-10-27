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
 * The order is fixed per part, and parts keep composition order: each present part contributes its
 * Content page (when it has a Viewer body) followed by its own Assets. The Add-on is last. A media
 * Task is one part with no body and is exactly as many pages as it always was.
 */
sealed interface ReviewPage {

    /**
     * One part's Viewer body: the description a Viewer draws. Never a file, never an Asset.
     *
     * [partKey] is carried because the body is read per part; a Task may have several Content
     * pages and they are not the same document.
     */
    data class Content(val taskId: String, val partKey: String) : ReviewPage

    /**
     * One of the Task's files, belonging to [partKey].
     *
     * [assetIndex] is its position among the Task's Asset pages, which is the index [AssetRun]
     * expects and the value a surface writes back when the user chooses a file within it.
     */
    data class Asset(
        val partKey: String,
        val asset: com.example.listea.data.ReviewAsset,
        val assetIndex: Int
    ) : ReviewPage

    /** The questionnaire attached to the Task, after everything else. */
    data object AddOn : ReviewPage
}

/**
 * This Task's pages.
 *
 * [hasAddOn] is the caller's resolved answer — this build can read the questionnaire the Task was
 * stamped with — and not a re-resolution: the session already resolves it once to draw the page.
 *
 * Absent parts are skipped: a part the latest Package revision no longer names keeps its history
 * but is not a page. A Task no read resolved parts for has no pages, which is the honest shape of a
 * manual item.
 */
internal fun reviewPages(item: ReviewItem, hasAddOn: Boolean): List<ReviewPage> = buildList {
    var assetIndex = 0
    for (part in item.parts.filter { it.present }) {
        if (part.payloadKind != null) add(ReviewPage.Content(item.taskId, part.partKey))
        for (asset in part.assets) {
            add(ReviewPage.Asset(part.partKey, asset, assetIndex))
            assetIndex++
        }
    }
    if (hasAddOn) add(ReviewPage.AddOn)
}

/** Whether a queue item has any page that is content rather than a file. */
internal fun ReviewItem.hasContentPage(): Boolean = parts.any { it.payloadKind != null }

/**
 * One Task's page list and where on it a surface is standing.
 *
 * Every surface that shows a Task — Review, Quick Review, the List item viewer and the Todo
 * preview — asks this instead of computing a count and an Asset index of its own. v0.8.2f's fix
 * round found what duplicated arithmetic does: two readers silently dropped the add-on page and
 * one guide row was off by the Content page, while Review was right. v0.8.4 added a second Content
 * page class, which is why the Asset index is now read off the page list rather than converted from
 * a boolean: with several parts there is no single offset a boolean could name.
 *
 * [pageIndex] is already clamped, because a resync can take a page away under a selection that was
 * already made. [assetIndex] is the value [AssetRun] expects: the Asset page's own index, `-1` on
 * a Content page, and the Asset page count on the add-on page — the sentinel that page is
 * recognised by.
 */
internal data class TaskPageState(
    val pages: List<ReviewPage>,
    val pageIndex: Int
) {
    val pageCount: Int get() = pages.size
    val current: ReviewPage? get() = pages.getOrNull(pageIndex)
    val hasContentPage: Boolean get() = pages.any { it is ReviewPage.Content }
    val hasAddOnPage: Boolean get() = pages.lastOrNull() is ReviewPage.AddOn
    val hasPageLevel: Boolean get() = pages.size > 1
    val onContentPage: Boolean get() = current is ReviewPage.Content
    val onAddOnPage: Boolean get() = current is ReviewPage.AddOn
    val assetPageCount: Int get() = pages.count { it is ReviewPage.Asset }
    val assetIndex: Int get() = when (current) {
        is ReviewPage.Asset -> (current as ReviewPage.Asset).assetIndex
        is ReviewPage.Content -> -1
        ReviewPage.AddOn -> assetPageCount
        null -> -1
    }

    /** The page an Asset index names, or the current page when no page holds it. */
    fun pageOfAssetIndex(assetIndex: Int): Int =
        pages.indexOfFirst { it is ReviewPage.Asset && it.assetIndex == assetIndex }
            .takeIf { it >= 0 } ?: pageIndex

    /**
     * Whether [AssetRun]'s own step may move off the current page in [forward]'s direction.
     *
     * The run counts Assets and [pageOfAssetIndex] resolves only Asset pages, so its step is
     * meaningful only when the neighbouring page is another Asset. Across a part's Content page,
     * onto the Add-on, or off the end of the list the run must stay put — there is no Asset index
     * to land on — and the caller turns the page by page index instead. Derived here, beside the
     * list, so the three surfaces that draw a Task cannot disagree about where the run ends.
     */
    fun assetRunSteps(forward: Boolean): Boolean =
        pages.getOrNull(pageStep(pageCount, pageIndex, forward) ?: -1) is ReviewPage.Asset
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
internal sealed interface CommitArrival {

    /** A page of this Task: the next part's Content page, or the Add-on. */
    data class Page(val index: Int) : CommitArrival

    /** The next Task in the queue. */
    data object NextTask : CommitArrival
}

/**
 * Where a commit lands when the template advances.
 *
 * Three destinations, in order: **the next part's first page**, then **the add-on**, then the next
 * Task. Answering one Prompt of a Task that presents three must show the second question rather
 * than skip the whole Task; and the add-on remains the Task's last page and the documented place a
 * Task is left from. Before v0.8.2f a commit advanced straight past the questionnaire; v0.8.4
 * extends the same arrival rule across parts.
 *
 * A commit made while already on the add-on page advances, exactly as it always did, so the page
 * never holds the queue up. A template that declares `advanceAfterCommit = false` stays put: that is
 * the template's choice about its own confirmation step, and the rest is still reachable with the
 * inner arrow.
 */
internal fun commitArrival(
    advanceAfterCommit: Boolean,
    pages: List<ReviewPage>,
    currentPage: Int
): CommitArrival? {
    if (!advanceAfterCommit) return null
    if (pages.getOrNull(currentPage) is ReviewPage.AddOn) return CommitArrival.NextTask

    // The next Content page after this one is another part's first page. Assets between them
    // belong to the part just answered, and the judgment has already been made about them.
    val nextContent = pages.withIndex()
        .firstOrNull { (index, page) -> index > currentPage && page is ReviewPage.Content }
    if (nextContent != null) return CommitArrival.Page(nextContent.index)

    val addOn = pages.indexOfFirst { it is ReviewPage.AddOn }
    if (addOn > currentPage) return CommitArrival.Page(addOn)
    return CommitArrival.NextTask
}
