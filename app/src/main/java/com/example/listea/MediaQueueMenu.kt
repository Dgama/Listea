package com.example.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.domain.StoredAddOnVersion

/**
 * One row of the queue guide: an item's name, where it sits, and the pages inside it.
 *
 * [pages] is the level below this row — a Task's files and its add-on page — and is empty when the
 * row has nothing below it. A level with one entry is not a level, so callers collapse a one-page
 * Task to a leaf rather than open a list of one on top of it.
 */
@Immutable
data class MediaQueueEntry(
    val label: String,
    val detail: String? = null,
    val pages: List<MediaQueueEntry> = emptyList()
)

/**
 * The queue behind the navigator's counter, and the way to travel it.
 *
 * [entries] is the outer level the counter names, in that level's order. [current] is the index on
 * screen, or -1 when the counter names only a page level — the queue holds one item, so the
 * position is drawn from its pages and [entries] *are* those pages.
 *
 * [currentPage] is where the screen is inside [entries[current]], or -1 when it is not on a page
 * of that entry. A row with [MediaQueueEntry.pages] carries an arrow into them; the row itself goes
 * to the entry's **first** page, which is where the outer arrows land too.
 *
 * [onJump] receives a row index and a page index, or null for that row's first page. A one-level
 * list passes null always. It is navigation only — the same move the arrows make, writing no
 * completion and, for a page, no position.
 */
@Immutable
data class MediaQueueJump(
    val entries: List<MediaQueueEntry>,
    val current: Int,
    val currentPage: Int = -1,
    val onJump: (entry: Int, page: Int?) -> Unit
)

/**
 * Places the queue list under the position control, its trailing edge aligned to the control's.
 *
 * The anchor is the position's own box, so the list opens exactly where the finger was. It is
 * clamped into the window rather than allowed to hang off either side: the counter sits at the
 * right edge of the bar by definition, and an unclamped menu would put the item names off screen.
 */
internal class MediaQueuePopupPositionProvider(
    private val gapPx: Int
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // Trailing edge to trailing edge. In RTL the trailing edge is the left one.
        val alignedX = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val x = alignedX.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.bottom + gapPx)
            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

/**
 * The navigator's position label, and the queue guide it opens.
 *
 * The label itself is the control: no chevron, because the position is the one number on the bar
 * that already means *where you are in a sequence*, and a marker on it only repeated that. Its
 * height is the same as the neighbouring arrows, so the target a thumb lands on is the control.
 *
 * It is the counter's own composable rather than a few lines in [MediaTopBar] because the list must
 * be a child of the same box it is anchored to: a [PopupPositionProvider] is given its direct
 * parent's bounds, and a [Popup] emitted anywhere else in the bar would open under the wrong
 * control.
 *
 * With no [queue] the function draws the label it has always drawn: not every queue has somewhere
 * to jump, and a surface that cannot answer should not start looking like a button.
 */
@Composable
internal fun MediaQueueCounter(
    counter: String,
    queue: MediaQueueJump?,
    maxWidth: Dp,
    height: Dp
) {
    val jump = queue?.takeIf { it.entries.size > 1 && it.current in it.entries.indices }
    if (jump == null) {
        Text(
            counter,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        return
    }

    var open by remember { mutableStateOf(false) }
    val popupWidth = minOf(QueuePopupMaxWidth, (maxWidth - QueuePopupMargin).coerceAtLeast(0.dp))

    Box {
        Box(
            modifier = Modifier
                .height(height)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Show queue"
                ) { open = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                counter,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        if (open) {
            QueueMenuPopup(
                queue = jump,
                width = popupWidth,
                onDismiss = { open = false }
            )
        }
    }
}

/**
 * The guide itself, floating under the position control.
 *
 * Two levels in one popup: the queue's entries, and inside an entry the pages it holds. A row with
 * an arrow opens the second level; the row's own tap goes to its first page and closes, which is
 * the same place the outer arrows would take it. The page level is headed by the entry's own name
 * and can be stepped back out of without closing the guide.
 *
 * A focusable popup rather than a pass-through one, and that is load-bearing: only a focusable popup
 * takes Back, and in Review the Back press behind it would otherwise leave the round. Outside taps
 * and Back close this and nothing else.
 *
 * Its own background is a concrete dark neutral rather than `surface`: the media chrome scheme
 * deliberately makes `surface` transparent so bars sit on the picture, and a menu with that colour
 * would show the photograph through its rows.
 */
@Composable
private fun QueueMenuPopup(
    queue: MediaQueueJump,
    width: Dp,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val gapPx = with(density) { QueuePopupGap.roundToPx() }
    val positionProvider = remember(gapPx) { MediaQueuePopupPositionProvider(gapPx) }
    val shape = RoundedCornerShape(ListeaDimens.CardCorner)
    // Which entry's pages are open, by index; -1 is the queue itself. Not saved: the popup leaves
    // the composition when it closes, so every open starts at the queue, which is the level the
    // counter names.
    var openPagesOf by remember { mutableIntStateOf(-1) }
    val openEntry = queue.entries.getOrNull(openPagesOf)
    val pages = openEntry?.pages.orEmpty()
    val onPageLevel = openEntry != null && pages.isNotEmpty()

    // The current row is kept a little below the top so the list opens with the items before it in
    // view too; the control says *where am I*, and a context-free row is a worse answer than that.
    // On a page level the same courtesy applies only where the pages are the ones on screen, and
    // the header sits above index 0.
    val listState = remember(openPagesOf) {
        val currentPage = if (openPagesOf == queue.current) queue.currentPage else -1
        val first = if (onPageLevel) {
            1 + (currentPage - 2).coerceAtLeast(0)
        } else {
            (queue.current - 2).coerceAtLeast(0)
        }
        LazyListState(firstVisibleItemIndex = first)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .width(width)
                .clip(shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = Color.White,
            shadowElevation = 8.dp
        ) {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = QueuePopupMaxHeight)
                    .selectableGroup(),
                state = listState
            ) {
                if (openEntry != null && pages.isNotEmpty()) {
                    item(key = "back") {
                        PageLevelHeader(
                            entry = openEntry,
                            onBack = { openPagesOf = -1 }
                        )
                    }
                    itemsIndexed(
                        items = pages,
                        key = { index, _ -> index }
                    ) { index, page ->
                        val selected = openPagesOf == queue.current && index == queue.currentPage
                        QueueRow(
                            entry = page,
                            selected = selected,
                            onOpen = {
                                onDismiss()
                                queue.onJump(openPagesOf, index)
                            }
                        )
                    }
                } else {
                    itemsIndexed(
                        items = queue.entries,
                        key = { index, _ -> index }
                    ) { index, entry ->
                        QueueRow(
                            entry = entry,
                            selected = index == queue.current,
                            onOpen = {
                                // The row goes to the entry's first page, whichever row it is —
                                // including the one on screen, where it is a way back to page one.
                                onDismiss()
                                queue.onJump(index, null)
                            },
                            onOpenPages = if (entry.pages.isNotEmpty()) {
                                { openPagesOf = index }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The way back out of a page level: the entry whose pages are open, with a left arrow.
 *
 * It exists because Back belongs to the whole guide — a focusable popup takes it before the screen
 * behind can — so stepping out of a page level has to have a control of its own.
 */
@Composable
private fun PageLevelHeader(
    entry: MediaQueueEntry,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Back to the queue"
            ) { onBack() }
            .heightIn(min = QueueRowMinHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One row of the guide.
 *
 * [onOpenPages] is present only when the row has a level below it; it sits in its own control at
 * the trailing edge so that opening the pages and travelling to the row cannot be mistaken for one
 * another. The row's own tap is the second: the entry's first page.
 */
@Composable
private fun QueueRow(
    entry: MediaQueueEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onOpenPages: (() -> Unit)? = null
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val labelColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        Color.White
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = QueueRowMinHeight)
            .selectable(selected = selected, onClick = onOpen)
            .background(background)
            .padding(start = 12.dp, end = if (onOpenPages != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            // Decorative: the row's own selected state is what assistive technology announces, and
            // a second announcement of the same fact is noise.
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(if (onOpenPages != null) 4.dp else 8.dp))
        }
        if (onOpenPages != null) {
            IconButton(
                onClick = onOpenPages,
                modifier = Modifier.size(QueueArrowSize)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Show pages of ${entry.label}",
                    tint = labelColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * A queue of Tasks as the guide names them, with each Task's pages under it.
 *
 * The name is the item's title. The second line is its path relative to the queue, unless that is
 * the title itself — a folder review often names a file by its own name, and repeating it would
 * spend a line saying nothing. The Task's position in the source tree is the fallback, which is
 * what tells two same-named files apart inside one folder Task.
 *
 * [pagesOf] answers a Task's page level. A Task holding one page is a leaf: its row still goes
 * there, and an arrow opening a list of one would be a level that is not a level.
 */
internal fun reviewQueueEntries(
    items: List<ReviewItem>,
    pagesOf: (ReviewItem) -> List<MediaQueueEntry>
): List<MediaQueueEntry> = items.map { item ->
    val detail = item.provenance()
    MediaQueueEntry(
        label = item.title,
        detail = detail,
        pages = pagesOf(item).takeIf { it.size > 1 }.orEmpty()
    )
}

/**
 * A Task's pages as the guide names them: its files, then the add-on page.
 *
 * The files come first, in the Task's own order, because that is the order the inner arrows walk.
 * The add-on page follows them because it is a page of the Task like any other, but it is not one
 * of the folder's files, and a questionnaire sitting in the list under a file's name would read as
 * something the source holds. Its row says "add-on" first for exactly that reason.
 */
internal fun reviewPageEntries(
    assets: List<ReviewAsset>,
    addOn: StoredAddOnVersion?,
    content: Boolean = false
): List<MediaQueueEntry> = buildList {
    // The Content page is first because it is what the Prompt is; its row says so rather than
    // borrowing a file's name, exactly as the add-on's does.
    if (content) add(MediaQueueEntry("Prompt"))
    assets.forEach { add(MediaQueueEntry(it.name)) }
    addOn?.let { add(MediaQueueEntry("add-on ${it.name}")) }
}

private val QueuePopupMaxWidth = 280.dp
private val QueuePopupMargin = 24.dp
private val QueuePopupMaxHeight = 360.dp
private val QueuePopupGap = 8.dp
private val QueueRowMinHeight = 48.dp
private val QueueArrowSize = 40.dp
