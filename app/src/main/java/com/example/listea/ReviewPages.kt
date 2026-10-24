package com.example.listea

import com.example.listea.data.ReviewItem

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
