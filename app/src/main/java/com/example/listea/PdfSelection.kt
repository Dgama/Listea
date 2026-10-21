package com.example.listea

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.listea.domain.ButtonType
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.min

/**
 * A selected passage: its text, its context, and the renderer hint an anchor would carry.
 *
 * **Text before indices.** The two character offsets are the renderer's own indexing and are kept
 * only so a mark opened on the same file, read by the same parser, can skip a search. They are not
 * what the mark is about: that is [quote] with the words either side of it, and Phase 3 turns this
 * into the stored anchor.
 */
data class PdfQuote(
    val pageIndex: Int,
    val quote: String,
    /** Up to [AnchorContextChars] characters before the quote, in reading order. */
    val prefix: String,
    /** Up to [AnchorContextChars] characters after it. */
    val suffix: String,
    /** Renderer char index of the first and last selected character, inclusive. */
    val startChar: Int,
    val endChar: Int
)

/** How much surrounding text an anchor keeps to tell two identical quotes apart. */
internal const val AnchorContextChars = 24

/**
 * Touch text selection over one rendered page.
 *
 * **The gesture is ours because the library's is mouse-only.** Nucleus explicitly lets a scroll
 * win on touch, and a reviewer's finger is not a mouse: long-press begins a selection, dragging
 * extends it, releasing leaves two handles to adjust, and a tap elsewhere clears it. A drag before
 * the long-press threshold is not consumed, so the page list still scrolls and pinch still zooms —
 * the one rule that decides whether a viewer feels like a viewer or a text box.
 *
 * **Positions are reading positions.** Everything is expressed through [PdfReadingOrder], so a
 * quote dragged across a two-column page reads across that column rather than zig-zagging through
 * PDFium's glyph order. Handle drags snap to word boundaries through the same order.
 *
 * Selection draws one rectangle per line, spanning the selected characters' boxes — the span rather
 * than one rectangle per glyph, so the gaps between letters are highlighted too.
 */
@Composable
internal fun TouchTextSelection(
    page: PdfPageText,
    order: PdfReadingOrder,
    modifier: Modifier,
    tools: List<TemplateButton> = emptyList(),
    marks: List<ResolvedMark> = emptyList(),
    fingerprint: String = "",
    onQuote: ((PdfQuote) -> Unit)? = null,
    onCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? = null,
    onMarkTap: ((List<ResolvedMark>) -> Unit)? = null
) {
    val context = LocalContext.current
    var selection by remember(order) { mutableStateOf<IntRange?>(null) }
    var menuVisible by remember(order) { mutableStateOf(false) }
    // A comment's text is asked for before the mark exists; a tag takes its value from the tool and
    // is created on the tap. Both are held here rather than higher because this is where the
    // selection is, and closing it is part of the same gesture.
    var pendingComment by remember(order) {
        mutableStateOf<Pair<TemplateButton, PdfQuote>?>(null)
    }

    BoxWithConstraints(modifier.testTag("pdf-selection-${page.pageIndex}")) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val pageW = page.widthPoints
        val pageH = page.heightPoints
        if (pageW <= 0f || pageH <= 0f || containerW <= 0f || containerH <= 0f) {
            return@BoxWithConstraints
        }
        val scaleX = containerW / pageW
        val scaleY = containerH / pageH
        val handleRadius = with(density) { HandleTouchRadius.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(order, scaleX, scaleY, containerH, marks) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // A press on a handle adjusts the selection it belongs to, before any
                        // long-press logic can start a new one.
                        val held = selection
                        val handle = held?.let {
                            handleAt(down.position, it, order, scaleX, scaleY, containerH, handleRadius)
                        }
                        if (held != null && handle != null) {
                            down.consume()
                            var low = held.first
                            var high = held.last
                            drag(down.id) { change ->
                                val position = hitTest(change.position, order, scaleX, scaleY, containerH)
                                if (position != null) {
                                    val word = order.wordRangeAt(position)
                                    if (handle == SelectionHandle.Start) {
                                        low = word.first.coerceAtMost(high)
                                    } else {
                                        high = word.last.coerceAtLeast(low)
                                    }
                                    selection = low..high
                                }
                                change.consume()
                            }
                            currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                            menuVisible = true
                            return@awaitEachGesture
                        }

                        // The long-press window is waited out here, on this layer's own terms,
                        // rather than through awaitLongPressOrCancellation. That helper finishes
                        // on the Final pass, by which time the card's tap detector — an ancestor,
                        // later in the same Main pass — has already consumed the up to fire its
                        // chrome toggle, and a tap on a mark would arrive looking cancelled.
                        // Waiting on the Main pass means the leaf sees the up first and can claim
                        // it before the card does.
                        var longPressed = false
                        val up: PointerInputChange? = try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation()
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            longPressed = true
                            null
                        }

                        if (!longPressed) {
                            // Not a selection: a tap clears one, a scroll or pinch does not (the
                            // gesture was consumed, so there is no up to answer).
                            if (up != null) {
                                selection = null
                                menuVisible = false
                                // A mark under the finger opens its sheet instead of merely
                                // clearing. The up is consumed so the same tap does not also
                                // reach the card and toggle the chrome underneath.
                                val tapped = marksAtPointer(
                                    up.position, marks, scaleX, scaleY, containerH
                                )
                                if (tapped.isNotEmpty() && onMarkTap != null) {
                                    up.consume()
                                    onMarkTap(tapped)
                                }
                            }
                            return@awaitEachGesture
                        }

                        val start = hitTest(down.position, order, scaleX, scaleY, containerH)
                            ?: return@awaitEachGesture
                        val anchorWord = order.wordRangeAt(start)
                        selection = anchorWord
                        menuVisible = false
                        drag(down.id) { change ->
                            val position = hitTest(change.position, order, scaleX, scaleY, containerH)
                            if (position != null) {
                                val word = order.wordRangeAt(position)
                                selection = min(anchorWord.first, word.first)..
                                    max(anchorWord.last, word.last)
                            }
                            change.consume()
                        }
                        // The lift that ends a selection is claimed too; otherwise the card's tap
                        // detector reads it as a tap and toggles the chrome under the menu.
                        currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                        menuVisible = selection != null
                        if (menuVisible) {
                            selection?.let { onQuote?.invoke(quoteFor(order, page.pageIndex, it)) }
                        }
                    }
                }
                .drawBehind {
                    val selected = selection ?: return@drawBehind
                    drawSelection(selected, order, scaleX, scaleY, containerH)
                    drawHandle(
                        position = selected.first,
                        atEnd = false,
                        order = order,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        containerH = containerH
                    )
                    drawHandle(
                        position = selected.last,
                        atEnd = true,
                        order = order,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        containerH = containerH
                    )
                }
        )

        val selected = selection
        if (menuVisible && selected != null) {
            val bounds = selectionBounds(selected, order, scaleX, scaleY, containerH)
            val margin = with(density) { 8.dp.toPx() }
            val height = with(density) { 48.dp.toPx() }
            val x = bounds.left.toInt().coerceIn(0, max(0, (containerW - margin).toInt()))
            val above = bounds.top - height
            val y = if (above >= 0f) above.toInt() else (bounds.bottom + margin).toInt()

            Surface(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .testTag("pdf-selection-menu"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface
            ) {
                Row {
                    // One quote for the whole menu: it describes the selection on screen, and
                    // every action on that selection is about the same passage.
                    val quote = quoteFor(order, page.pageIndex, selected)
                    TextButton(onClick = {
                        copyToClipboard(context, quote.quote)
                        selection = null
                        menuVisible = false
                    }) {
                        Text("Copy")
                    }
                    // Only when there is somewhere for a mark to go. A tool button that wrote
                    // nothing would look functional while being inert, which is the same reason
                    // the no-template viewer is handed no tools at all. The other "somewhere" is
                    // text: a long press that landed on a space selects that space (wordRangeAt
                    // yields whitespace alone), and a mark anchored to it could never be drawn —
                    // the resolver normalises its quote to nothing and reports it not found on
                    // the very document it was made in. Copy stays: copying a gap is legitimate.
                    if (onCreateMark != null && quote.canCarryMark()) {
                        tools.forEach { tool ->
                            TextButton(onClick = {
                                if (tool.type == ButtonType.COMMENT) {
                                    pendingComment = tool to quote
                                } else {
                                    onCreateMark(
                                        tool,
                                        null,
                                        anchorFrom(quote, page, order, fingerprint)
                                    )
                                }
                                selection = null
                                menuVisible = false
                            }) {
                                Text(tool.name)
                            }
                        }
                    }
                }
            }
        }

        // The comment may be written after the menu has closed; the quote it is about was taken
        // when the tool was chosen, so a finger that wandered before Save cannot move the anchor.
        pendingComment?.let { (tool, quote) ->
            MarkCommentDialog(
                toolName = tool.name,
                onDismiss = { pendingComment = null },
                onSave = { text ->
                    onCreateMark?.invoke(
                        tool,
                        text,
                        anchorFrom(quote, page, order, fingerprint)
                    )
                    pendingComment = null
                }
            )
        }
    }
}

/**
 * A comment's text, written before its mark exists.
 *
 * Save is the only way a comment mark comes into being, and it stays disabled while the field is
 * blank: a comment mark with no comment would be a highlight with nothing behind it, and exporting
 * one would put an empty `input` in a payload. Cancelling creates nothing and leaves the selection
 * behind with the menu — the passage is still where the finger left it.
 */
@Composable
private fun MarkCommentDialog(
    toolName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(toolName) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pdf-comment-field")
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim()) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The two draggable ends of a selection. */
private enum class SelectionHandle { Start, End }

/**
 * The text behind [selection], with the words either side of it.
 *
 * Prefix and suffix are taken from the page's reading order, not from the renderer's stream: they
 * exist to tell one "the" from another, and a neighbour that reads differently from the glyph
 * order would not do that. The join between text before and after is the same `\n` [PdfReadingOrder.text]
 * puts between lines, so the offsets are the ones the quote was built from.
 */
internal fun quoteFor(order: PdfReadingOrder, pageIndex: Int, selection: IntRange): PdfQuote {
    val before = order.text(0, selection.first - 1)
    val selected = order.text(selection.first, selection.last)
    val after = order.text(selection.last + 1, order.size - 1)
    // text() puts a newline between two lines but never at the start or end of what it returns,
    // so the newlines at the selection's own boundaries have to be re-inserted here. Without them
    // a suffix from the next line reads "betatwo" where the page says "alphaone\nbetatwo", and
    // every later position the anchor records is one character short.
    val newlineBefore = if (before.isNotEmpty() &&
        order.lineOf(selection.first) != order.lineOf(selection.first - 1)
    ) 1 else 0
    val newlineAfter = if (after.isNotEmpty() &&
        order.lineOf(selection.last + 1) != order.lineOf(selection.last)
    ) 1 else 0
    val start = before.length + newlineBefore
    val end = start + selected.length
    val pageText = before + "\n".repeat(newlineBefore) + selected +
        "\n".repeat(newlineAfter) + after
    return PdfQuote(
        pageIndex = pageIndex,
        quote = selected,
        prefix = safeSlice(pageText, max(0, start - AnchorContextChars), start, atStart = true),
        suffix = safeSlice(
            pageText,
            end,
            min(pageText.length, end + AnchorContextChars),
            atStart = false
        ),
        startChar = order.chars.getOrNull(selection.first)?.id ?: -1,
        endChar = order.chars.getOrNull(selection.last)?.id ?: -1
    )
}

/**
 * Whether a mark can be anchored to this selection at all.
 *
 * A mark's identity is its quote: everything the viewer draws is found by searching for the
 * quote's [normalizeQuote|normalised] text, and relocation after the file changes searches for
 * the same string. A selection of whitespace alone normalises to the empty string, so a mark made
 * on it could never be found — not even in the document it was made in — and the viewer would
 * report it missing forever. The tool chips are withheld instead, which is the same choice the
 * no-template viewer makes: an action that cannot do its job is not offered.
 *
 * This does not narrow ordinary selection. A press on a gap still selects that gap and can still
 * be copied; it is only the anchoring that is refused.
 */
internal fun PdfQuote.canCarryMark(): Boolean = normalizeQuote(quote).isNotEmpty()

/** A slice that never ends between the two halves of a surrogate pair. */
private fun safeSlice(text: String, from: Int, to: Int, atStart: Boolean): String {
    var start = from.coerceIn(0, text.length)
    var end = to.coerceIn(start, text.length)
    if (atStart && start > 0 && text[start].isLowSurrogate()) start++
    if (!atStart && end > start && end < text.length && text[end].isLowSurrogate()) end--
    return text.substring(start, end)
}

/**
 * The marks under a touch point, converted from the container's pixels back to page points.
 *
 * The page is drawn bottom-up and the screen is top-down, so the y conversion is the same one
 * [hitTest] makes for text. Delegating the containment itself to [marksAt] keeps a tap and the
 * drawing from disagreeing about what is under the finger.
 */
private fun marksAtPointer(
    pointer: Offset,
    marks: List<ResolvedMark>,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
): List<ResolvedMark> {
    if (marks.isEmpty() || scaleX <= 0f || scaleY <= 0f) return emptyList()
    return marksAt(marks, pointer.x / scaleX, (containerH - pointer.y) / scaleY)
}

/** The reading position nearest [pointer] (page-local px), snapped to no word. */
private fun hitTest(
    pointer: Offset,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
): Int? {
    if (order.size == 0) return null
    val pdfX = pointer.x / scaleX
    val pdfY = (containerH - pointer.y) / scaleY

    var bestLine = -1
    var bestVDistance = Float.MAX_VALUE
    var bestContainsX = false
    for (index in order.lines.indices) {
        val line = order.lines[index]
        val distance = when {
            pdfY in line.hitBottom..line.hitTop -> 0f
            pdfY > line.hitTop -> pdfY - line.hitTop
            else -> line.hitBottom - pdfY
        }
        val containsX = pdfX in line.left..line.right
        if (distance < bestVDistance ||
            (distance == bestVDistance && containsX && !bestContainsX)
        ) {
            bestVDistance = distance
            bestLine = index
            bestContainsX = containsX
            if (distance == 0f && containsX) break
        }
    }
    if (bestLine < 0) return null

    val line = order.lines[bestLine]
    if (line.endExclusive <= line.start) return null
    var bestPosition = line.start
    var bestHDistance = Float.MAX_VALUE
    for (position in line.start until line.endExclusive) {
        val char = order.chars[position]
        val distance = when {
            pdfX in char.left..char.right -> 0f
            pdfX > char.right -> pdfX - char.right
            else -> char.left - pdfX
        }
        if (distance < bestHDistance) {
            bestHDistance = distance
            bestPosition = position
            if (distance == 0f) break
        }
    }
    return bestPosition
}

/** Which handle, if either, a press lands on. */
private fun handleAt(
    pointer: Offset,
    selection: IntRange,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float,
    radius: Float
): SelectionHandle? {
    val start = handleOffset(selection.first, atEnd = false, order, scaleX, scaleY, containerH)
        ?: return null
    val end = handleOffset(selection.last, atEnd = true, order, scaleX, scaleY, containerH)
        ?: return null
    val toStart = (pointer - start).getDistance()
    val toEnd = (pointer - end).getDistance()
    return when {
        toStart <= radius && toStart <= toEnd -> SelectionHandle.Start
        toEnd <= radius -> SelectionHandle.End
        else -> null
    }
}

/** The handle's centre in container px: below the line, at the selection's edge. */
private fun handleOffset(
    position: Int,
    atEnd: Boolean,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
): Offset? {
    val char = order.chars.getOrNull(position) ?: return null
    val line = order.lines.getOrNull(order.lineOf(position)) ?: return null
    val x = (if (atEnd) char.right else char.left) * scaleX
    val y = containerH - line.bottom * scaleY
    return Offset(x, y)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelection(
    selection: IntRange,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
) {
    var currentLine = -1
    var line: PdfLine? = null
    var minLeft = Float.POSITIVE_INFINITY
    var maxRight = Float.NEGATIVE_INFINITY

    fun flush() {
        val bounds = line ?: return
        if (minLeft > maxRight) return
        val leftPx = minLeft * scaleX
        val rightPx = maxRight * scaleX
        val topPx = containerH - bounds.hitTop * scaleY
        val bottomPx = containerH - bounds.hitBottom * scaleY
        if (rightPx > leftPx && bottomPx > topPx) {
            drawRect(
                color = SelectionColor,
                topLeft = Offset(leftPx, topPx),
                size = Size(rightPx - leftPx, bottomPx - topPx)
            )
        }
    }

    for (position in selection) {
        val lineIndex = order.lineOf(position)
        if (lineIndex != currentLine) {
            flush()
            currentLine = lineIndex
            line = order.lines.getOrNull(lineIndex)
            minLeft = Float.POSITIVE_INFINITY
            maxRight = Float.NEGATIVE_INFINITY
        }
        val char = order.chars.getOrNull(position) ?: continue
        minLeft = min(minLeft, char.left)
        maxRight = max(maxRight, char.right)
    }
    flush()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    position: Int,
    atEnd: Boolean,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
) {
    val center = handleOffset(position, atEnd, order, scaleX, scaleY, containerH) ?: return
    drawCircle(color = SelectionColor, radius = HandleRadius.toPx(), center = center)
    drawCircle(color = Color.White, radius = HandleRadius.toPx() * 0.45f, center = center)
}

/** The container-space box the selection covers, for placing the menu. */
private fun selectionBounds(
    selection: IntRange,
    order: PdfReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float
): androidx.compose.ui.geometry.Rect {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (position in selection) {
        val line = order.lines.getOrNull(order.lineOf(position)) ?: continue
        left = min(left, line.left)
        right = max(right, line.right)
        top = min(top, containerH - line.hitTop * scaleY)
        bottom = max(bottom, containerH - line.hitBottom * scaleY)
    }
    if (left > right) return androidx.compose.ui.geometry.Rect.Zero
    return androidx.compose.ui.geometry.Rect(left * scaleX, top, right * scaleX, bottom)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Listea", text))
}

private val SelectionColor = Color(0x664A7CFF)

/** Drawn handle radius, in dp. */
private val HandleRadius = 7.dp

/** How near a handle a press must land to grab it, in dp. */
private val HandleTouchRadius = 24.dp

/** Buttons a PDF selection menu offers, in template order. */
internal fun List<TemplateButton>.pdfTools(): List<TemplateButton> =
    filter { it.type == ButtonType.COMMENT || it.type == ButtonType.TAG }
