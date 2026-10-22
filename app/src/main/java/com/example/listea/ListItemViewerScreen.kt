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
import androidx.media3.exoplayer.ExoPlayer
import com.example.listea.data.AssetDecisions
import com.example.listea.data.ReviewItem

/**
 * Looking at what a list item actually is, opened by tapping it on the list page.
 *
 * Tapping an item used to open a rename box. Renaming a folder-backed item was a trap: the title
 * is how the item is recognised on the next source scan and what a webhook payload carries, so a
 * rename either came back undone or quietly detached the row from the file it stood for. The
 * gesture is now the one people expect from a list of pictures — it opens the picture.
 *
 * Deliberately read-only, and that is the whole distinction between this and Review. It has no
 * checkbox and no action chips, so a look cannot become a decision by accident. Nothing here
 * touches the ViewModel at all: there is no call it could make.
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
 * is what this screen used to do.
 */
@Composable
fun ListItemViewerScreen(
    modifier: Modifier,
    items: List<ReviewItem>,
    initialItemId: Long,
    listTitle: String,
    settings: AppSettings,
    resolver: TemplateResolver,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    // Keyed on what was opened, so tapping a different item starts there rather than resuming
    // wherever the last look happened to end.
    var currentItemId by rememberSaveable(initialItemId) { mutableStateOf(initialItemId) }
    // Which file of the opened item is on screen, keyed on the item so looking at another one
    // starts at its first file. Held here rather than in [AssetRun] because the navigator states
    // the item and the file at once, and a selection the bar has to draw cannot live below it.
    var assetIndex by rememberSaveable(currentItemId) { mutableStateOf(0) }
    // Not saved: rotating should return to the item, not to a sheet about it.
    var infoOpen by remember { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    val index = items.indexOfFirst { it.id == currentItemId }

    // A resync or a cleanup can take the item away while it is open. Closing is better than
    // sliding to a neighbour, which would leave the user looking at something they never asked
    // for and had no way to tell they were now looking at.
    LaunchedEffect(items, currentItemId) {
        if (items.none { it.id == currentItemId }) onClose()
    }
    val item = items.getOrNull(index) ?: return
    // The list's items in the list's order, as the jump list names them.
    val queueEntries = remember(items) { reviewQueueEntries(items) }

    MediaSystemBars(chromeVisible)
    BackHandler { onClose() }

    // The file's place inside the item, clamped rather than repaired: a resync can take a file
    // away under a selection that was already made, and showing what is still there beats writing
    // state from a composition.
    val assets = assetsOf(item)
    val currentAsset = assetIndex.coerceIn(0, maxOf(assets.lastIndex, 0))
    // A level with nothing to move between is not a level, so a one-file item keeps the bar it has
    // always had: no file name beside the title, no `(1 / 1)`, no inner pair of arrows.
    val hasAssetLevel = assets.size > 1
    // A list holding one item still has a queue worth opening when that item holds several files:
    // the counter then names the files, and this is that list.
    val assetEntries = remember(assets) { reviewPageEntries(assets, addOn = null) }

    MediaShell(
        modifier = modifier,
        navigator = MediaNavigator(
            title = item.title,
            onBack = onClose,
            subtitle = assets.getOrNull(currentAsset)?.name?.takeIf { hasAssetLevel },
            // Where in the list this is, which is the same number the page it was opened from
            // shows — and inside it, the item's own file position.
            counter = if (hasAssetLevel) {
                "${index + 1}(${currentAsset + 1}/${assets.size})/${items.size}"
            } else {
                "${index + 1} / ${items.size}"
            },
            // A jump on the outer level opens another item at its first file, exactly as the outer
            // arrows do, because assetIndex is keyed on the item. When the list holds one item, the
            // counter names that item's files instead, and the jump moves between them the way the
            // inner arrows do.
            queueJump = when {
                items.size > 1 && index >= 0 -> MediaQueueJump(
                    entries = queueEntries,
                    current = index,
                    onJump = { currentItemId = items[it].id }
                )

                hasAssetLevel -> MediaQueueJump(
                    entries = assetEntries,
                    current = currentAsset,
                    onJump = { assetIndex = it }
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
            onPreviousAsset = assetStep(assets.size, currentAsset, forward = false)?.let { page ->
                { assetIndex = page }
            },
            onNextAsset = assetStep(assets.size, currentAsset, forward = true)?.let { page ->
                { assetIndex = page }
            },
            hasAssetSteps = hasAssetLevel
        ),
        chromeVisible = chromeVisible,
        media = {
            AssetRun(
                item = item,
                selectedIndex = currentAsset,
                player = player,
                imageLoader = imageLoader,
                settings = settings,
                // Both ends simply stop. There is no queue being worked through and nothing to
                // complete, so running off the end is not an event — it is just the end.
                canLeaveForward = index < items.lastIndex,
                canLeaveBack = index > 0,
                onLeaveForward = { currentItemId = items[index + 1].id },
                onLeaveBack = { currentItemId = items[index - 1].id },
                onSelectAsset = { assetIndex = it },
                onTap = { chromeVisible = !chromeVisible },
                modifier = Modifier.fillMaxSize()
            )
        },
        taskBar = { MediaInfoBar("Item info") { infoOpen = true } }
    )

    if (infoOpen) {
        // The same sheet Review shows, including the item's completion and its actions. Reading
        // them here is not a way to set them: the sheet has never had a control on it.
        //
        // The actions are the page's own pair record, taken from the same file the sheet is handed
        // beside them, so the two halves of the sheet describe one file rather than one file and a
        // neighbour's tags — see [ReviewItemInfoSheet].
        val page = assets.getOrNull(currentAsset)
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, page?.decisions ?: AssetDecisions(), resolver, listTitle),
            asset = page,
            onDismiss = { infoOpen = false }
        )
    }
}
