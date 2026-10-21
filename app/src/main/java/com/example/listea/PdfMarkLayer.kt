package com.example.listea

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.example.listea.domain.MarkKind

/**
 * The marks that land on one page, drawn over the rendered sheet.
 *
 * **One translucent colour per kind, and every overlapping mark drawn.** A comment and a tag on the
 * same sentence are two facts about that sentence, so both rectangles are painted — the later one
 * does not hide the earlier. The colours are the only place the two kinds differ visually; the
 * sheet a tap opens names them in words.
 *
 * Geometry arrives in PDF points with the origin at the bottom-left, which is how the renderer and
 * the reading order report it. The page canvas fills the card's width and keeps the page's aspect
 * ratio, so the two scales are computed from the canvas rather than assumed equal: a lossy or
 * unusual producer whose reported size disagrees with the drawn sheet must not shift a highlight.
 */
@Composable
internal fun PdfMarkHighlights(
    marks: List<ResolvedMark>,
    page: PdfPageText,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.testTag("pdf-marks-${page.pageIndex}")) {
        if (page.widthPoints <= 0f || page.heightPoints <= 0f) return@Canvas
        val scaleX = size.width / page.widthPoints
        val scaleY = size.height / page.heightPoints
        marks.forEach { resolved ->
            val color = when (resolved.mark.kind) {
                MarkKind.COMMENT -> CommentHighlight
                MarkKind.TAG -> TagHighlight
            }
            resolved.rects.forEach { rect ->
                val width = (rect.right - rect.left) * scaleX
                val height = (rect.top - rect.bottom) * scaleY
                if (width > 0f && height > 0f) {
                    drawRect(
                        color = color,
                        topLeft = Offset(rect.left * scaleX, size.height - rect.top * scaleY),
                        size = Size(width, height)
                    )
                }
            }
        }
    }
}

/**
 * A mark whose passage is not in this version of the document is not drawn, because drawing it over
 * text it does not quote is the failure the anchor exists to prevent. It is not announced over the
 * page either: the file's Info sheet lists it among the marks, which is where a reviewer looks when
 * they ask what this file carries. See [PdfMarkInfo].
 */

/** Comment highlights are warm; tag highlights are green. Both let the page show through. */
private val CommentHighlight = Color(0x66FFB300)
private val TagHighlight = Color(0x6643A047)
