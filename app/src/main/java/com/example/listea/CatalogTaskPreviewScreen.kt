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
import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ReviewItem
import com.example.listea.data.TaskPayloadEntity
import com.example.listea.data.TaskResourceView
import com.example.listea.data.decodeActionIds
import com.example.listea.domain.parseProfilePrompt
import kotlinx.coroutines.flow.Flow

/**
 * The catalog's preview, wired to the Source and the level it was opened from.
 *
 * The Task is looked up by id in the Source's own catalog rather than carried in as a row, so what
 * is on screen is the Task as the database currently has it. A refresh that re-imports the material
 * therefore updates a preview that is already open, and a Task that has gone away closes the screen
 * instead of leaving the user looking at a file that is no longer in the catalog — the answer the
 * List viewer gives to an item taken out from under it.
 *
 * What it hands the screen is the whole level rather than one row: the Tasks the catalog was
 * showing, in the order it was showing them, which is what makes stepping sideways walk the folder
 * the user was standing in. Read from the catalog rather than captured at the tap, so the sequence
 * a refresh changes is the sequence the arrows follow.
 */
@Composable
fun CatalogTaskPreview(
    modifier: Modifier,
    viewModel: ListsViewModel,
    sourceId: String,
    taskId: String,
    rootUri: String,
    scope: String,
    flat: Boolean,
    onExit: () -> Unit
) {
    val importVersion by viewModel.importVersion.collectAsStateWithLifecycle()
    val rowsFlow = remember(sourceId, importVersion) { viewModel.observeCatalog(sourceId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Still loading is not the same as gone, and only a read that has come back may close the
    // screen: closing on the first frame would shut every preview before its data arrived. The
    // level is read after that check for the same reason — there is nothing to read it from yet.
    val catalog = rows ?: return
    val level = remember(catalog, scope, flat) { catalogLevel(catalog, scope, flat) }
    if (level.none { it.taskId == taskId }) {
        LaunchedEffect(taskId) { onExit() }
        return
    }

    CatalogTaskPreviewScreen(
        modifier = modifier,
        tasks = level,
        initialTaskId = taskId,
        rootUri = rootUri,
        settings = settings,
        observePayload = viewModel::observeTaskPayload,
        observeResources = viewModel::observeTaskResources,
        onClose = onExit
    )
}

/**
 * Looking at a Source's Task without deciding anything about it.
 *
 * This is the catalog's read gesture, and it is deliberately not a review. There is no checkbox
 * and no action chip, so opening a Task to see what it is cannot become a judgement about it by
 * accident — the same distinction the List's item viewer draws, and drawn for the same reason. It
 * touches no ViewModel: there is no call it could make.
 *
 * What it keeps is the navigator, because a Task standing for a folder is several files and the
 * only way to see the rest of them is to page. What it drops is everything below it — the file's
 * chips, the completion box, the Info bar. The band that remains is the one that belongs to the
 * Task rather than to the file, which is exactly the boundary the frame already draws.
 *
 * **It pages between Tasks as well as within one.** [tasks] is the level the user was standing in
 * when they tapped — the catalog's own list, in the catalog's own order — so the outer pair of
 * arrows walks the folder they were looking at, and swiping at either end of a Task's files moves
 * to the next Task rather than stopping. The two levels are the same two the List's item viewer
 * has, and for the same reason: a folder is a sequence of Tasks, and a Task is a sequence of files.
 *
 * Still nothing is decided: stepping sideways is a look at the next one, not a move on it. A Task
 * is reached this way only if it was already in the level, so the sequence can never lead anywhere
 * the user could not have tapped.
 */
@Composable
fun CatalogTaskPreviewScreen(
    modifier: Modifier,
    tasks: List<CatalogRow>,
    initialTaskId: String,
    rootUri: String,
    settings: AppSettings,
    // The Prompt body is read by Task id when its page is composed, not carried on every row: a
    // catalog of a thousand prompts must not hold a thousand bodies to show one. Reads only — this
    // screen still has no write it could make.
    observePayload: (String) -> Flow<TaskPayloadEntity?>,
    observeResources: (String) -> Flow<List<TaskResourceView>>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    // Keyed on what was opened, so tapping a different Task starts there rather than resuming
    // wherever the last look happened to end.
    var currentTaskId by rememberSaveable(initialTaskId) { mutableStateOf(initialTaskId) }
    // Which page of the Task is on screen, keyed on the Task so stepping to the next one starts at
    // its first page. Saved, so a rotation returns to the same page rather than to the first one,
    // which for a folder Task is a different picture entirely. A Prompt's Content page is page 0.
    var pageIndex by rememberSaveable(currentTaskId) { mutableStateOf(0) }
    // A page the guide asked for on a Task that is not on screen yet. The page index is keyed on
    // the Task, so the Task arrives at its first page and this moves it once that state exists.
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(currentTaskId) {
        pendingPage?.let { requested ->
            pageIndex = requested
            pendingPage = null
        }
    }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    val taskIndex = tasks.indexOfFirst { it.taskId == currentTaskId }
    // A refresh can take the Task away while it is open. Closing is better than sliding to a
    // neighbour, which would leave the user looking at something they never asked for and had no
    // way to tell they were now looking at.
    LaunchedEffect(tasks, currentTaskId) {
        if (tasks.none { it.taskId == currentTaskId }) onClose()
    }
    val row = tasks.getOrNull(taskIndex) ?: return

    val item = remember(row, rootUri) { row.asPreviewItem(rootUri) }
    val assets = item.assets
    val contentPage = item.hasContentPage()
    val contentOffset = if (contentPage) 1 else 0
    val pageCount = assets.size + contentOffset
    val currentPage = pageIndex.coerceIn(0, maxOf(pageCount - 1, 0))
    val currentAsset = (currentPage - contentOffset).coerceAtLeast(0)
    val onContentPage = contentPage && currentPage == 0
    val hasPageLevel = pageCount > 1
    // A level with nothing to move between is not a level: a folder holding one Task draws no
    // outer arrows, and a Task holding one file draws no inner pair.
    val hasTaskLevel = tasks.size > 1 && taskIndex >= 0
    // The level's Tasks in the level's order, as the guide names them, each carrying its pages as
    // the level below. A folder Task's name is often its path's last segment already, so the path
    // is only carried when it adds something.
    val queueEntries = remember(tasks) {
        tasks.map { task ->
            MediaQueueEntry(
                label = task.displayName,
                detail = task.groupPath.takeIf { it.isNotBlank() && it != task.displayName },
                pages = reviewPageEntries(
                    task.assets,
                    addOn = null,
                    content = task.payloadKind != null
                ).takeIf { it.size > 1 }.orEmpty()
            )
        }
    }
    // A level holding one Task still has a counter worth opening when the Task holds several
    // pages: the counter then names the pages, and this is that list. Nothing here can be marked,
    // so an entry is only a page's name.
    val pageEntries = remember(assets, contentPage) {
        reviewPageEntries(assets, addOn = null, content = contentPage)
    }

    // The Prompt body, for the Content page only. Read by Task id rather than carried, and read
    // here rather than in the card so the card stays a pure renderer with no database behind it.
    val payloadRow by remember(item.taskId) { observePayload(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val taskResources by remember(item.taskId) { observeResources(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val prompt = remember(payloadRow) { payloadRow?.let { parseProfilePrompt(it.payload) } }
    val photo = remember(prompt, taskResources) {
        prompt?.photoResource?.let { key -> taskResources.firstOrNull { it.resourceKey == key } }
    }
    val photoMissing = prompt?.photoResource != null && (photo == null || photo.missing)

    MediaSystemBars(chromeVisible)
    BackHandler { onClose() }

    MediaShell(
        modifier = modifier,
        navigator = MediaNavigator(
            title = row.displayName,
            onBack = onClose,
            subtitle = when {
                !hasPageLevel -> null
                onContentPage -> "Prompt"
                else -> assets.getOrNull(currentAsset)?.name
            },
            // Where in the folder this is, and — inside it — which page of the Task. The same
            // reading the List's item viewer gives, because it is the same two levels.
            counter = when {
                hasPageLevel && hasTaskLevel ->
                    "${taskIndex + 1}(${currentPage + 1}/${pageCount})/${tasks.size}"
                hasPageLevel -> "${currentPage + 1} / ${pageCount}"
                hasTaskLevel -> "${taskIndex + 1} / ${tasks.size}"
                else -> null
            },
            // The guide follows the level the counter names: Tasks while there is a Task level, and
            // the Task's pages when the counter has fallen back to the page-only form. Inside a
            // Task row are its pages, and a row's own tap lands on the first of them. A Task level
            // holding one row leaves the outer number with nothing to jump through, but its pages
            // are still a level, and a position that cannot be opened there is the same dead label
            // this control exists to replace.
            queueJump = when {
                hasTaskLevel -> MediaQueueJump(
                    entries = queueEntries,
                    current = taskIndex,
                    currentPage = currentPage,
                    onJump = { entry, page ->
                        if (entry == taskIndex) {
                            pageIndex = page ?: 0
                        } else {
                            pendingPage = page
                            currentTaskId = tasks[entry].taskId
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
            onPrevious = if (taskIndex > 0) {
                { currentTaskId = tasks[taskIndex - 1].taskId }
            } else {
                null
            },
            onNext = if (taskIndex in 0 until tasks.lastIndex) {
                { currentTaskId = tasks[taskIndex + 1].taskId }
            } else {
                null
            },
            onPreviousAsset = assetStep(pageCount, currentPage, forward = false)?.let { page ->
                { pageIndex = page }
            },
            onNextAsset = assetStep(pageCount, currentPage, forward = true)?.let { page ->
                { pageIndex = page }
            },
            hasAssetSteps = hasPageLevel
        ),
        chromeVisible = chromeVisible,
        media = {
            if (onContentPage) {
                // The same page turn the List's item viewer gives a Prompt: read-only, so a swipe
                // decides nothing. A multi-page Task turns to its first file; at the Task's edge
                // the swipe moves to the next Task in the level.
                ContentPageSwipe(
                    canSwipeForward = currentPage < pageCount - 1 ||
                        taskIndex < tasks.lastIndex,
                    canSwipeBack = taskIndex > 0,
                    onSwipeForward = {
                        if (currentPage < pageCount - 1) {
                            pageIndex = currentPage + 1
                        } else if (taskIndex < tasks.lastIndex) {
                            currentTaskId = tasks[taskIndex + 1].taskId
                        }
                    },
                    onSwipeBack = {
                        if (taskIndex > 0) currentTaskId = tasks[taskIndex - 1].taskId
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (prompt != null) {
                        ProfilePromptPage(
                            prompt = prompt,
                            photoUri = photo?.takeIf { !it.missing }?.uri,
                            photoMissing = photoMissing,
                            // A look says nothing: no pill is selected and none can be taken. The
                            // same card as Review, so Preview and Review cannot disagree about what
                            // the Prompt says.
                            selectedOptionId = null,
                            interactive = false,
                            onSelect = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PromptNoticePage(
                            "This Prompt cannot be shown.",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                AssetRun(
                    item = item,
                    selectedIndex = currentAsset,
                    player = player,
                    imageLoader = imageLoader,
                    settings = settings,
                    // Running off either end of a Task moves to the next Task rather than stopping,
                    // which is what makes a swipe the same gesture here as the arrows. At the ends of
                    // the *level* it simply stops: there is no Task to leave for and nothing to
                    // complete, because nothing here is being decided.
                    canLeaveForward = taskIndex < tasks.lastIndex,
                    canLeaveBack = taskIndex > 0,
                    onLeaveForward = { currentTaskId = tasks[taskIndex + 1].taskId },
                    onLeaveBack = { currentTaskId = tasks[taskIndex - 1].taskId },
                    onSelectAsset = { pageIndex = it + contentOffset },
                    onTap = { chromeVisible = !chromeVisible },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        // The one band the frame reserves for the item, left empty rather than removed: the
        // navigator still sits at the top and the media still stops above it.
        taskBar = { }
    )
}

/**
 * A catalog row as the viewer's own shape.
 *
 * The completion and the Assets — which carry their own pair records — are carried across rather
 * than dropped so that the renderer is given a whole [ReviewItem] — but nothing on this screen
 * reads them, sets them or shows them. They are here because [AssetRun] renders an item and not a
 * row, and inventing a second, thinner item type to avoid passing them would put a second renderer
 * behind the same picture.
 *
 * [ReviewItem.id] is zero on purpose. It is a queue handle, and this screen has no queue: the
 * identity that matters here is [ReviewItem.taskId], which is the Task the assets belong to.
 */
internal fun CatalogRow.asPreviewItem(rootUri: String): ReviewItem = ReviewItem(
    id = 0L,
    taskId = taskId,
    // A folder Task has no public id of its own — an import run assigns those to the files it
    // registers, and nothing here is being sent anywhere.
    publicId = "",
    title = displayName,
    sourceUri = null,
    relativePath = groupPath,
    sourceMissing = false,
    target = DecisionTarget.File(rootUri, groupPath),
    isCompleted = isCompleted,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    assets = assets,
    rootRelativePath = groupPath,
    // Carried for the same reason the decisions are: this screen draws no chip row today, but the
    // item it hands the renderer is the same shape every other surface hands it, and a control left
    // off here would be a second kind of item the moment this screen grows one.
    assetControls = assetControls,
    // The Prompt envelope, carried for the same reason: this screen renders a whole item, and an
    // item with a body is one whose first page is its Content page.
    packageKey = packageKey,
    promptKey = promptKey,
    payloadKind = payloadKind,
    payloadSchema = payloadSchema,
    payloadRevision = payloadRevision
)
