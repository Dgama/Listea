package com.example.listea

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.nucleusframework.pdfium.PageTextLayout
import dev.nucleusframework.pdfium.PdfPage
import dev.nucleusframework.pdfium.PdfReaderState

/**
 * The renderer-neutral view of an open PDF: pages, their text, and the file they came from.
 *
 * **This is the seam the renderer decision rests on.** v0.7.0 adopts Nucleus PDFium, and the gate
 * for ever reconsidering that decision is written in the iteration document — but a gate nobody can
 * walk through is not a gate. Everything above this file speaks [PdfDocumentSession], [PdfPageText]
 * and [PdfChar]; only [NucleusPdfDocumentSession] and [PdfPageCanvas] know the library exists. A
 * later renderer replaces those two, not the selection, the reading order or the anchors.
 *
 * The neutral types are also what makes the reading order testable as ordinary JVM code: a page's
 * text is a list of codepoints with boxes in PDF points, which is exactly the fixture a unit test
 * can build by hand.
 */

/** One glyph's codepoint and box, in PDF page points with the origin at the bottom-left. */
@Immutable
internal data class PdfChar(
    /**
     * The renderer's own character index.
     *
     * Non-authoritative and stored only as a hint: see the anchor's KDoc in the iteration document.
     * A real PDF's glyph order is arbitrary, and PDFium's index is stable only for the same bytes
     * read by the same parser — which is why an anchor also carries the text and its neighbours.
     */
    val id: Int,
    val codepoint: Int,
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
)

/** One page's text layer. [chars] is in the renderer's order, not reading order. */
@Immutable
internal data class PdfPageText(
    val pageIndex: Int,
    val widthPoints: Float,
    val heightPoints: Float,
    val chars: List<PdfChar>
) {
    val isEmpty: Boolean get() = chars.isEmpty()

    companion object {
        val Empty = PdfPageText(-1, 0f, 0f, emptyList())
    }
}

/** A page's size in PDF points, free of any renderer's own type. */
@Immutable
internal data class PdfPageSize(val widthPoints: Float, val heightPoints: Float) {
    val aspectRatio: Float get() = if (heightPoints > 0f) widthPoints / heightPoints else 1f
}

/**
 * An open document.
 *
 * Owned by [PdfPreview] for the life of one URI. The fingerprint is the SHA-256 of the bytes that
 * were opened, computed once by [readPdf] — it is what tells an anchor written against one version
 * of a file from the same file later edited, and it is why the stored marks can be relocated rather
 * than trusted.
 */
internal interface PdfDocumentSession {
    val pageCount: Int
    val fingerprint: String

    suspend fun pageSize(pageIndex: Int): PdfPageSize?

    /** The page's text layer, or null when the renderer has no page or no text. */
    suspend fun pageText(pageIndex: Int): PdfPageText?
}

/**
 * The one implementation, wrapping Nucleus's [PdfReaderState].
 *
 * `dispose()` is deliberately not exposed: the state is created by `rememberPdfReaderState`, which
 * already closes it when the preview leaves composition. Calling it twice races two closers against
 * the same document, and the second one is the one that would run after a new URI had opened.
 *
 * `@OptIn` is not needed for `PdfPage`: `selectableText = false` keeps Nucleus's own selection
 * layer out of the tree, because its layer is mouse-only on touch. Selection is ours — see
 * [PdfSelection.kt] — and the reading order it uses lives in [PdfReadingOrder.kt].
 */
internal class NucleusPdfDocumentSession(
    internal val reader: PdfReaderState,
    override val fingerprint: String
) : PdfDocumentSession {

    override val pageCount: Int get() = reader.pageCount

    override suspend fun pageSize(pageIndex: Int): PdfPageSize? =
        reader.pageSize(pageIndex)?.let { PdfPageSize(it.widthPoints, it.heightPoints) }

    override suspend fun pageText(pageIndex: Int): PdfPageText? =
        reader.pageTextLayout(pageIndex)?.toPageText()
}

/**
 * One page's rendered sheet.
 *
 * The only other place this app names Nucleus. A session it does not know — a fake in a test, a
 * replacement renderer that has not finished being written — draws nothing rather than crashing:
 * the viewer's job is to show the document, and a layer that cannot draw is a blank page, not a
 * reason to take the review surface down.
 */
@Composable
internal fun PdfPageCanvas(
    session: PdfDocumentSession,
    pageIndex: Int,
    modifier: Modifier
) {
    val nucleus = session as? NucleusPdfDocumentSession ?: return
    PdfPage(
        state = nucleus.reader,
        pageIndex = pageIndex,
        modifier = modifier,
        selectableText = false,
        linksEnabled = false
    )
}

/**
 * Nucleus's layout as the app's own text layer.
 *
 * Every character is kept, including ones with a zero-height box: some producers report them for
 * glyphs that are visibly tall, and the reading order widens the *line's* hit box to cover them.
 * Dropping them here would lose selectable text for an entire class of documents.
 */
private fun PageTextLayout.toPageText(): PdfPageText {
    val chars = ArrayList<PdfChar>(charCount)
    for (index in 0 until charCount) {
        chars.add(
            PdfChar(
                id = index,
                codepoint = codepoint(index),
                left = charLeft(index),
                bottom = charBottom(index),
                right = charRight(index),
                top = charTop(index)
            )
        )
    }
    return PdfPageText(
        pageIndex = pageIndex,
        widthPoints = pageSize.widthPoints,
        heightPoints = pageSize.heightPoints,
        chars = chars
    )
}
