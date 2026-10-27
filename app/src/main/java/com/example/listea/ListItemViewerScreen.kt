package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.example.listea.data.AssetDecisions
import com.example.listea.data.ReviewItem
import com.example.listea.data.TaskPartPayloadEntity
import com.example.listea.data.TaskResourceView
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.decodePrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Looking at what a list item actually is, opened by tapping it on the list page.
 *
 * Tapping an item used to open a rename box. Renaming a folder-backed item was a trap: the title
 * is how the item is recognised on the next source scan and what a webhook payload carries, so a
 * rename either came back undone or quietly detached the row from the file it stood for. The
 * gesture is now the one people expect from a list of pictures — it opens the picture.
 *
 * Deliberately read-only, and that is the whole distinction between this and Review. It has no
 * checkbox and no action chips, so a look cannot become a decision by accident. What it reads
 * arrives as flows; there is no call it could make.
 *
 * What it *is* identical to is the folder File Viewer — same shell, same swipe, same pinch, same
 * lone Info button — because they are the same activity done from two directions. The difference
 * is only in what is being paged through: this walks the list's own items in the list's own
 * order, so a swipe reaches the next item on the page rather than the next file in a folder, and
 * an item with no file behind it still gets its place in the sequence rather than being skipped.
 *
 * An item standing for several files is one item and several pages, which is the same shape Review
 * gives it: swiping looks at all of its files before it reaches the next item. Reading a multi-file
 * Task by its first file and then moving on would make the rest of it unreachable from here, which
 * is what this screen used to do. **The add-on is one of those pages too**, drawn read-only after
 * the last file, because a Task is one shape wherever it is browsed; only a separate file, which is
 * not a Task at all, is previewed without one.
 */
@Composable
fun ListItemViewerScreen(
    modifier: Modifier,
    items: List<ReviewItem>,
    initialItemId: Long,
    listTitle: String,
    settings: AppSettings,
    resolver: TemplateResolver,
    // The add-on is resolved from the rows every item column names, exactly as Review resolves it,
    // so this screen and Review cannot disagree about whether a Task has a questionnaire page.
    addOnResolver: AddOnResolver,
    // The Prompt body is read by Task and part rather than carried on every item, and the same is
    // true here: a list of a thousand prompts must not hold a thousand bodies in memory to show
    // one. These are reads only — the screen still has no write it could make.
    observePayload: (String, String) -> Flow<TaskPartPayloadEntity?>,
    observeResources: (String, String) -> Flow<List<TaskResourceView>>,
    observeAnswers: (String) -> Flow<List<AddOnAnswer>>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    // Keyed on what was opened, so tapping a different item starts there rather than resuming
    // wherever the last look happened to end.
    var currentItemId by rememberSaveable(initialItemId) { mutableStateOf(initialItemId) }
    // Which page of the opened item is on screen, keyed on the item so looking at another one
    // starts at its first page. The page list is the typed one: a Prompt's Content page comes
    // first, then its Assets. Held here rather than in [AssetRun] because the navigator states the
    // item and the page at once, and a selection the bar has to draw cannot live below it.
    var pageIndex by rememberSaveable(currentItemId) { mutableStateOf(0) }
    // Not saved: rotating should return to the item, not to a sheet about it.
    var infoOpen by remember { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    val index = items.indexOfFirst { it.id == currentItemId }

    // A page the guide asked for on an item that is not on screen yet. The page index is keyed on
    // the item, so the item arrives at its first page and this moves it once that state exists.
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(currentItemId) {
        pendingPage?.let { requested ->
            pageIndex = requested
            pendingPage = null
        }
    }

    // A resync or a cleanup can take the item away while it is open. Closing is better than
    // sliding to a neighbour, which would leave the user looking at something they never asked
    // for and had no way to tell they were now looking at.
    LaunchedEffect(items, currentItemId) {
        if (items.none { it.id == currentItemId }) onClose()
    }
    val item = items.getOrNull(index) ?: return
    // The list's items in the list's order, as the guide names them. Each row carries the item's
    // pages, so the guide can be walked a level at a time: the items, then one item's pages. A
    // Prompt's Content page is one of those pages and keeps its place at the front.
    val queueEntries = remember(items, addOnResolver) {
        reviewQueueEntries(items) { entry ->
            reviewPageEntries(entry.parts, addOn = addOnResolver.readableVersion(entry.addOn))
        }
    }

    MediaSystemBars(chromeVisible)
    BackHandler { onClose() }

    // The page's place inside the item, clamped rather than repaired: a resync can take a page
    // away under a selection that was already made, and showing what is still there beats writing
    // state from a composition. The one shared model says which pages there are and which is
    // showing: a Prompt's Content page first, its Assets after, the add-on last.
    val assets = assetsOf(item)
    val storedAddOn = addOnResolver.readableVersion(item.addOn)
    val pageState = taskPageState(item, storedAddOn, pageIndex)
    val pageCount = pageState.pageCount
    val currentPage = pageState.pageIndex
    val currentAsset = pageState.assetIndex
    val onContentPage = pageState.onContentPage
    // A level with nothing to move between is not a level, so a one-page item keeps the bar it has
    // always had: no page name beside the title, no `(1 / 1)`, no inner pair of arrows.
    val hasPageLevel = pageState.hasPageLevel
    // A list holding one item still has a queue worth opening when that item holds several pages:
    // the counter then names the pages, and this is that list.
    val pageEntries = remember(item.parts, storedAddOn) {
        reviewPageEntries(item.parts, addOn = storedAddOn)
    }

    // The Prompt body, for the Content page only. Read by Task and part rather than carried, and
    // read here rather than in the card so the card stays a pure renderer with no database behind
    // it. The part is the one the page on screen names, so a Task presenting several Prompts draws
    // each body under its own heading rather than the first one's on every page.
    val contentPartKey = (pageState.current as? ReviewPage.Content)?.partKey
    val payloadRow by remember(item.taskId, contentPartKey) {
        contentPartKey?.let { observePayload(item.taskId, it) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val taskResources by remember(item.taskId, contentPartKey) {
        contentPartKey?.let { observeResources(item.taskId, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // The answers the read-only questionnaire page and the Info sheet show. The same flow Review
    // reads; this screen has no control that could write to it.
    val answers by remember(item.taskId) { observeAnswers(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // The Viewer table owns the body; this surface only knows it has content to draw.
    val content = remember(payloadRow) {
        payloadRow?.let { decodePrompt(it.kind, it.schemaVersion, it.payload) }
    }

    MediaShell(
        modifier = modifier,
        navigator = MediaNavigator(
            title = item.title,
            onBack = onClose,
            subtitle = when {
                !hasPageLevel -> null
                onContentPage -> "Question"
                pageState.onAddOnPage -> "Add-on"
                else -> assets.getOrNull(currentAsset)?.name
            },
            // Where in the list this is, which is the same number the page it was opened from
            // shows — and inside it, the item's own page position.
            counter = if (hasPageLevel) {
                "${index + 1}(${currentPage + 1}/${pageCount})/${items.size}"
            } else {
                "${index + 1} / ${items.size}"
            },
            // The outer level is the list's items; inside a row are that item's pages. A row's own
            // tap opens another item at its first page, exactly as the outer arrows do, and a page
            // picked under a row goes to that page on that item. When the list holds one item the
            // counter names its pages instead, and the guide's one level is those pages.
            queueJump = when {
                items.size > 1 && index >= 0 -> MediaQueueJump(
                    entries = queueEntries,
                    current = index,
                    currentPage = currentPage,
                    onJump = { entry, page ->
                        if (entry == index) {
                            pageIndex = page ?: 0
                        } else {
                            pendingPage = page
                            currentItemId = items[entry].id
                        }
                    }
                )

                hasPageLevel -> MediaQueueJump(
                    entries = pageEntries,
                    current = currentPage,
                    onJump = { page, _ -> pageIndex = page }
                )

                else -> null
            },
            onPrevious = if (index > 0) {
                { currentItemId = items[index - 1].id }
            } else {
                null
            },
            onNext = if (index < items.lastIndex) {
                { currentItemId = items[index + 1].id }
            } else {
                null
            },
            onPreviousAsset = pageStep(pageCount, currentPage, forward = false)?.let { page ->
                { pageIndex = page }
            },
            onNextAsset = pageStep(pageCount, currentPage, forward = true)?.let { page ->
                { pageIndex = page }
            },
            hasAssetSteps = hasPageLevel
        ),
        chromeVisible = chromeVisible,
        media = {
            if (onContentPage) {
                // A page turn, not a judgment: this surface has nothing it could decide. The
                // gesture was the one thing missing on device — the card scrolls vertically and
                // the arrows work, but a swipe did nothing. Inside a multi-page Task it turns to
                // the next page — the first file, or the add-on after the last one; at the Task's
                // edge it moves to the next item, as the arrows do.
                ContentPageSwipe(
                    canSwipeForward = currentPage < pageCount - 1 || index < items.lastIndex,
                    canSwipeBack = index > 0,
                    onSwipeForward = {
                        val next = pageStep(pageCount, currentPage, forward = true)
                        if (next != null) {
                            pageIndex = next
                        } else if (index < items.lastIndex) {
                            currentItemId = items[index + 1].id
                        }
                    },
                    onSwipeBack = {
                        if (index > 0) currentItemId = items[index - 1].id
                    },
                    onTap = { chromeVisible = !chromeVisible },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (content != null) {
                        PromptContentPage(
                            content = content,
                            resources = taskResources,
                            // A look says nothing: no option is selected and none can be taken.
                            // The same card as Review, so Preview and Review cannot disagree about
                            // what the Prompt says. A drill may reveal its meaning here, because a
                            // look is not an attempt.
                            selectedOptionId = null,
                            interactive = false,
                            reveal = true,
                            player = player,
                            onSelect = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PromptNoticePage(
                            "This question cannot be shown.",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                // Back off the first file of a part returns to that part's Content page; the page
                // list is where the answer lives now that a Task may present more than one Prompt.
                val backToContentPage = (pageState.current as? ReviewPage.Asset)?.let { asset ->
                    (pageState.pages.getOrNull(currentPage - 1) as? ReviewPage.Content)?.partKey ==
                        asset.partKey
                } == true
                AssetRun(
                    item = item,
                    selectedIndex = currentAsset,
                    player = player,
                    imageLoader = imageLoader,
                    settings = settings,
                    // The outer ends simply stop. There is no queue being worked through and
                    // nothing to complete, so running off the last item is not an event — it is
                    // just the end. Back off the **first page of a part** goes to that part's
                    // Content page rather than skipping it for the previous item, which makes the
                    // two ends of the run symmetric.
                    canLeaveForward = index < items.lastIndex,
                    canLeaveBack = backToContentPage || index > 0,
                    onLeaveForward = { currentItemId = items[index + 1].id },
                    onLeaveBack = {
                        if (backToContentPage) {
                            pageIndex = currentPage - 1
                        } else if (index > 0) {
                            currentItemId = items[index - 1].id
                        }
                    },
                    onSelectAsset = { pageIndex = pageState.pageOfAssetIndex(it) },
                    onTap = { chromeVisible = !chromeVisible },
                    // The last page, drawn read-only: the questions the attached version still asks
                    // and the answers on record. No chip and no field, so a look cannot become an
                    // answer — but the Task is the shape Review shows, add-on included.
                    addOnPage = storedAddOn?.let { addOn ->
                        { AddOnAnswersPage(addOn = addOn, answers = answers) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        // The Task's state is the frame's, so it never leaves with the file's controls. A look
        // cannot change it — this surface has never had a control that writes — so it is drawn
        // read-only, beside the same checkbox Review shows.
        framePersists = true,
        taskBar = { TaskStatusBar(item = item, reviewBlocked = item.reviewBlocked) },
        // Info belongs to the page, not to the Task. The band is the page's and goes away on a
        // tap; the task band above stays.
        assetControls = {
            MediaInfoBar(
                contentDescription = when {
                    onContentPage -> "Question info"
                    pageState.onAddOnPage -> "Add-on info"
                    else -> "File info"
                }
            ) { infoOpen = true }
        }
    )

    if (infoOpen) {
        // The same sheet Review shows, scoped to the page it was opened from. Reading it is not a
        // way to set anything: the sheet has never had a control on it. A file's sheet describes
        // the file; the add-on's sheet describes the questionnaire. The actions are the page's own
        // pair record, taken from the same file the sheet is handed beside them, so the two halves
        // describe one file rather than one file and a neighbour's tags.
        val page = (pageState.current as? ReviewPage.Asset)?.asset
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, page?.decisions ?: AssetDecisions(), resolver, listTitle),
            asset = page,
            level = when {
                pageState.onAddOnPage -> ReviewInfoLevel.AddOn
                pageState.onContentPage -> ReviewInfoLevel.Content
                else -> ReviewInfoLevel.Asset
            },
            addOnInfo = addOnInfoOf(storedAddOn, answers, addOnResolver),
            onDismiss = { infoOpen = false }
        )
    }
}
