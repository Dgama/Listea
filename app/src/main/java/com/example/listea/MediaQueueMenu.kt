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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
 * One row of the queue list: the item's name, and where it sits under the name.
 *
 * [detail] is optional because some queues have nothing more to say — the folder viewer's files all
 * share one path, and a path repeated on every row is noise rather than orientation.
 */
@Immutable
data class MediaQueueEntry(
    val label: String,
    val detail: String? = null
)

/**
 * The queue behind the navigator's counter, and the way to jump through it.
 *
 * [entries] is the level the counter names, in that level's order: the queue behind the outer
 * number, or — when the queue has nothing to jump through — the files and pages behind the inner
 * one. [current] is the index into [entries] that the screen is on. [onJump] receives another index
 * and is expected to move the screen's own cursor, exactly as its arrows do — navigation only, with
 * no completion or record.
 *
 * A surface with at most one entry on both levels passes null instead: the counter then stays the
 * plain label it has always been.
 */
@Immutable
data class MediaQueueJump(
    val entries: List<MediaQueueEntry>,
    val current: Int,
    val onJump: (Int) -> Unit
)

/**
 * Places the queue list under the position control, its trailing edge aligned to the control's.
 *
 * The anchor is the position control's own box, so the list opens exactly where the finger was. It
 * is clamped into the window rather than allowed to hang off either side: the counter sits at the
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
 * The navigator's position label, and the queue list it opens.
 *
 * It is the counter's own composable rather than a few lines in [MediaTopBar] because the list must
 * be a child of the same box it is anchored to: a [PopupPositionProvider] is given its direct
 * parent's bounds, and a [Popup] emitted anywhere else in the bar would open under the wrong
 * control. The box is the whole tappable cluster — number and chevron — and is exactly what the
 * list aligns to.
 *
 * With no [queue] the function draws the label it has always drawn, to the pixel: not every queue
 * has somewhere to jump, and a surface that cannot answer should not start looking like a button.
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
        Row(
            modifier = Modifier
                .height(height)
                .clickable(
                    onClickLabel = "Show queue",
                    onClick = { open = true }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                counter,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(QueueChevronSize)
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
 * The list itself, floating under the position control.
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
    // The current row is kept a little below the top so the list opens with the items before it in
    // view too; the control says *where am I*, and a context-free row is a worse answer than that.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (queue.current - 2).coerceAtLeast(0)
    )

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
                itemsIndexed(
                    items = queue.entries,
                    key = { index, _ -> index }
                ) { index, entry ->
                    val selected = index == queue.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected) {
                                // Closing first keeps the list from being drawn over a screen that
                                // has already moved. Tapping the current row only closes it: in
                                // List Review a jump writes the resume cursor, and there is nothing
                                // to write when the queue has not moved.
                                onDismiss()
                                if (!selected) queue.onJump(index)
                            }
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    Color.White
                                },
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
                            Spacer(Modifier.width(ListeaDimens.RowGap))
                            // Decorative: the row's own selected state is what assistive technology
                            // announces, and a second announcement of the same fact is noise.
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A queue of Tasks as the jump list names them.
 *
 * The name is the item's title. The second line is its path relative to the queue, unless that is
 * the title itself — a folder review often names a file by its own name, and repeating it would
 * spend a line saying nothing. The Task's position in the source tree is the fallback, which is
 * what tells two same-named files apart inside one folder Task.
 */
internal fun reviewQueueEntries(items: List<ReviewItem>): List<MediaQueueEntry> = items.map { item ->
    val detail = listOf(item.relativePath, item.rootRelativePath)
        .firstOrNull { !it.isNullOrBlank() && it != item.title }
    MediaQueueEntry(label = item.title, detail = detail)
}

/**
 * A Task's pages as the jump list names them: its files, then the add-on page.
 *
 * This is the list a counter names when there is no queue behind the outer number — a round with a
 * single Task, or a preview of a Task whose level holds one row. The files come first, in the Task's
 * own order. The add-on page follows them because it is a page of the Task like any other, but it is
 * not one of the folder's files, and a questionnaire sitting in the list under a file's name would
 * read as something the source holds. Its row says "add-on" first for exactly that reason.
 */
internal fun reviewPageEntries(
    assets: List<ReviewAsset>,
    addOn: StoredAddOnVersion?
): List<MediaQueueEntry> = buildList {
    assets.forEach { add(MediaQueueEntry(it.name)) }
    addOn?.let { add(MediaQueueEntry("add-on ${it.name}")) }
}

private val QueuePopupMaxWidth = 280.dp
private val QueuePopupMargin = 24.dp
private val QueuePopupMaxHeight = 360.dp
private val QueuePopupGap = 8.dp
private val QueueChevronSize = 12.dp
