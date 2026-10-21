package com.example.listea

import com.example.listea.domain.ANCHOR_FORMAT_VERSION
import com.example.listea.domain.AssetMark
import com.example.listea.domain.MarkKind
import com.example.listea.domain.PDF_CHAR_PARSER
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.markKind
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Where a stored mark lands on the document as it is *now*, or why it lands nowhere.
 *
 * [rects] are page points with the origin at the bottom-left, one rectangle per line the passage
 * covers — the same geometry [PdfSelection] draws its selection with, so a mark and the selection
 * that made it look alike. [relocated] is set when the anchor had to move: the caller persists it,
 * so the next open takes the fast path and a history of relocations is not written on every frame.
 * [detached] is a mark whose quote this document no longer contains, or contains more than once
 * without enough context to choose — it is shown in a list and never drawn over text it does not
 * match.
 */
internal data class ResolvedMark(
    val mark: AssetMark,
    val tool: TemplateButton,
    val pageIndex: Int?,
    val rects: List<PdfMarkRect>,
    val relocated: QuoteAnchor? = null
) {
    val detached: Boolean get() = pageIndex == null
}

/**
 * What the file's Info sheet says about one stored mark: the tool that made it, what it says, and
 * whether this document still contains the passage it quotes.
 *
 * A summary rather than a [ResolvedMark], deliberately. The sheet describes marks in words and needs
 * none of the geometry; keeping the rectangles out of it is what lets the details sheet be built
 * above the viewer without the viewer's resolution leaking into a screen that never draws anything.
 *
 * [pageIndex] is null for a mark this document could not place — the mark is still listed, which is
 * the whole of how a missing mark is reported now. It is shown and not drawn, and nothing in the
 * review surface says so unbidden.
 */
data class PdfMarkInfo(
    val toolName: String,
    val kind: MarkKind,
    val quote: String,
    val value: String,
    val pageIndex: Int?
) {
    val detached: Boolean get() = pageIndex == null
}

internal fun ResolvedMark.info(): PdfMarkInfo = PdfMarkInfo(
    toolName = tool.name,
    kind = mark.kind,
    quote = mark.anchor.exactText,
    value = mark.value,
    pageIndex = pageIndex
)

/** One highlight rectangle in PDF points. [top] is greater than [bottom], as the renderer reports. */
internal data class PdfMarkRect(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in bottom..top
}

/**
 * Every mark whose highlight covers the point, in the order the marks were stored.
 *
 * A point can carry more than one mark — a comment and a tag over the same sentence is the ordinary
 * case — and the sheet it opens lists all of them rather than choosing. Choosing would be a guess
 * about which one the finger meant, and the *reason* two marks overlap is that the reviewer cared
 * about one passage twice.
 */
internal fun marksAt(marks: List<ResolvedMark>, x: Float, y: Float): List<ResolvedMark> =
    marks.filter { mark -> mark.rects.any { it.contains(x, y) } }

/**
 * A mark is visible only where the document's *current* governing control still offers its tool, as
 * the same kind of tool.
 *
 * **Nothing here reads the authored reference.** A mark says which template offered the tool when it
 * was made, but visibility is a question about now: the template that governs this Asset today must
 * still have a button with this id, and that button must still be a comment or a tag matching the
 * mark. That is what makes removing a tool hide its marks without deleting them, and restoring the
 * same id bring them back — across a version upgrade of the same template just as across a removal.
 *
 * A mark whose tool is gone is not detached: detached means *the passage* could not be found. Both
 * are hidden from the page. A detached mark is still listed in the file's Info sheet, because it is
 * a fact about the document; a mark whose tool is gone is not listed anywhere, because the template
 * that would name it is what removed it — and restoring the tool brings it straight back.
 */
internal fun visibleMarks(
    marks: List<AssetMark>,
    tools: List<TemplateButton>
): List<Pair<AssetMark, TemplateButton>> =
    marks.mapNotNull { mark -> PdfTools.toolFor(tools, mark.toolId, mark.kind)?.let { mark to it } }

/**
 * The passage tools of one control, and the lookup that keeps creation and rendering in step.
 *
 * A control's buttons are offered in the template's own order, and only comment and tag can point
 * at a passage. [toolFor] is the single answer to "is this mark's tool still here, as this kind" —
 * both the read path and the write path ask it, so a mark can never be created under a tool the
 * next frame would refuse to draw.
 */
internal object PdfTools {

    fun of(control: ResolvedAssetControl?): List<TemplateButton> =
        control?.buttons.orEmpty().pdfTools()

    fun toolFor(tools: List<TemplateButton>, id: String, kind: MarkKind): TemplateButton? =
        tools.firstOrNull { it.id == id && it.type.markKind() == kind }
}

/**
 * The anchor a new mark is stored with: the quote, its neighbours and a parser hint.
 *
 * [selection] comes from [quoteFor], so its text is already in reading order and its prefix and
 * suffix are already capped at [AnchorContextChars]. [order] is used once more to re-read the exact
 * text from the page the selection claims to be on, which keeps the stored quote the document's
 * words rather than a value passed alongside them. [fingerprint] is the SHA-256 of the bytes the
 * renderer opened; everything derived from a different file will be relocated by [resolveMarks].
 */
internal fun anchorFrom(
    selection: PdfQuote,
    pageText: PdfPageText,
    order: PdfReadingOrder,
    fingerprint: String
): QuoteAnchor {
    val exact = if (pageText.pageIndex == selection.pageIndex) {
        positionsOfIds(order, selection.startChar, selection.endChar)
            ?.let { order.text(it.first, it.last) }
            ?: selection.quote
    } else {
        selection.quote
    }
    return QuoteAnchor(
        version = ANCHOR_FORMAT_VERSION,
        documentFingerprint = fingerprint,
        pageIndex = selection.pageIndex,
        exactText = exact,
        prefix = selection.prefix,
        suffix = selection.suffix,
        charStart = selection.startChar,
        charEnd = selection.endChar,
        charParser = PDF_CHAR_PARSER
    )
}

/**
 * Where each visible mark sits on [session], reading every page at most once.
 *
 * **The fingerprint is a fast path, never a guarantee.** When the bytes are the ones the anchor was
 * written against and the parser is the same, the stored character range is checked against the
 * stored quote and used if it agrees — that is the ordinary reopen of an untouched file, and it
 * costs no search. Anything else searches: every page's text, in reading order, for the quote, with
 * the prefix and suffix deciding between repeated words. The search is also what a changed
 * fingerprint goes through, so a paragraph that moved to another page is found where it moved to
 * and its anchor rewritten, rather than drawn over the old coordinates.
 *
 * A quote with no unique match — or one whose parse the reader cannot interpret — is detached. The
 * alternative, drawing it over the hint's coordinates, is exactly the failure the anchor exists to
 * prevent: a mark over text it was never about.
 */
internal suspend fun resolveMarks(
    marks: List<AssetMark>,
    session: PdfDocumentSession,
    tools: List<TemplateButton>
): List<ResolvedMark> {
    val visible = visibleMarks(marks, tools)
    if (visible.isEmpty()) return emptyList()

    val pages = HashMap<Int, PageIndex?>()

    suspend fun page(index: Int): PageIndex? {
        if (index !in 0 until session.pageCount) return null
        return pages.getOrPut(index) {
            session.pageText(index)?.let { text -> PageIndex.of(text) }
        }
    }

    return visible.map { (mark, tool) -> resolveOne(mark, tool, session, ::page) }
}

/**
 * One mark, with the fast path tried before the search.
 *
 * [page] memoizes extraction because a document with many marks on one page would otherwise be
 * extracted once per mark, and extraction is the expensive half of opening a PDF.
 */
private suspend fun resolveOne(
    mark: AssetMark,
    tool: TemplateButton,
    session: PdfDocumentSession,
    page: suspend (Int) -> PageIndex?
): ResolvedMark {
    val anchor = mark.anchor
    val exact = normalizeQuote(anchor.exactText)
    if (anchor.version != ANCHOR_FORMAT_VERSION || exact.isEmpty()) {
        return ResolvedMark(mark, tool, pageIndex = null, rects = emptyList())
    }

    if (anchor.documentFingerprint == session.fingerprint &&
        anchor.charParser == PDF_CHAR_PARSER
    ) {
        val hinted = page(anchor.pageIndex)
        val range = hinted?.let { positionsOfIds(it.order, anchor.charStart, anchor.charEnd) }
        if (hinted != null && range != null &&
            normalizeQuote(hinted.order.text(range.first, range.last)) == exact
        ) {
            return ResolvedMark(
                mark = mark,
                tool = tool,
                pageIndex = hinted.pageIndex,
                rects = rectsOf(hinted.order, range)
            )
        }
    }

    val match = search(session, anchor, exact, page)
        ?: return ResolvedMark(mark, tool, pageIndex = null, rects = emptyList())

    val quote = quoteFor(match.page.order, match.page.pageIndex, match.range)
    val relocated = anchorFrom(quote, match.page.pageText, match.page.order, session.fingerprint)
    return ResolvedMark(
        mark = mark,
        tool = tool,
        pageIndex = match.page.pageIndex,
        rects = rectsOf(match.page.order, match.range),
        relocated = relocated.takeIf { it != anchor }
    )
}

/**
 * The one place [exact] occurs, after the context has chosen between repeats.
 *
 * Every occurrence is collected first; then the candidates whose stored neighbours agree are kept.
 * A match that agrees on neither is only used when it is the only occurrence — an uncontextualised
 * mark is still a mark when the word appears once. Two or more equally good candidates are not a
 * reason to pick the first: either could be right, and drawing the wrong one is worse than saying
 * the passage could not be found.
 */
private suspend fun search(
    session: PdfDocumentSession,
    anchor: QuoteAnchor,
    exact: String,
    page: suspend (Int) -> PageIndex?
): Match? {
    val prefix = normalizeQuote(anchor.prefix)
    val suffix = normalizeQuote(anchor.suffix)
    val candidates = mutableListOf<Match>()

    for (index in 0 until session.pageCount) {
        val indexed = page(index) ?: continue
        var from = 0
        while (true) {
            val at = indexed.text.indexOf(exact, from)
            if (at < 0) break
            val before = indexed.text.substring(0, at)
            val after = indexed.text.substring(at + exact.length)
            val prefixMatches = prefix.isEmpty() || before.endsWith(prefix)
            val suffixMatches = suffix.isEmpty() || after.startsWith(suffix)
            candidates += Match(
                page = indexed,
                range = indexed.rangeAt(at, exact.length),
                score = (if (prefixMatches) 1 else 0) + (if (suffixMatches) 1 else 0)
            )
            from = at + 1
        }
    }

    if (candidates.isEmpty()) return null
    val best = candidates.maxOf { it.score }
    val winners = candidates.filter { it.score == best }
    return winners.singleOrNull()
}

private data class Match(val page: PageIndex, val range: IntRange, val score: Int)

/**
 * A page's reading order plus the same text normalized, with every normalized offset mapped back to
 * the reading position it came from.
 *
 * The mapping is what lets a search answer in *reading positions*: a match is found as a string
 * offset, but a rect needs the character's box, and an anchor needs renderer ids. Whitespace runs —
 * including the newline between lines — collapse to one space, and the space is mapped to the
 * position that follows it, so a match can never start between two lines.
 */
private class PageIndex private constructor(
    val pageText: PdfPageText,
    val order: PdfReadingOrder,
    val text: String,
    private val positions: IntArray
) {
    val pageIndex: Int get() = pageText.pageIndex

    /** Reading positions covered by [length] normalized characters starting at [offset]. */
    fun rangeAt(offset: Int, length: Int): IntRange {
        val first = positions[offset.coerceIn(0, positions.lastIndex)]
        val last = positions[(offset + length - 1).coerceIn(0, positions.lastIndex)]
        return min(first, last)..max(first, last)
    }

    companion object {
        fun of(pageText: PdfPageText): PageIndex? {
            val order = PdfReadingOrder.of(pageText)
            if (order.size == 0) return null

            val text = StringBuilder()
            val positions = ArrayList<Int>(order.size)
            var pendingSpace = false
            var previousLine = -1

            for (position in 0 until order.size) {
                val line = order.lineOf(position)
                if (previousLine >= 0 && line != previousLine) pendingSpace = true
                previousLine = line

                val codepoint = order.chars[position].codepoint
                if (Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint)) {
                    pendingSpace = true
                    continue
                }
                val normalized = Normalizer.normalize(
                    String(Character.toChars(codepoint)),
                    Normalizer.Form.NFKC
                )
                if (pendingSpace && text.isNotEmpty()) {
                    text.append(' ')
                    positions.add(position)
                }
                pendingSpace = false
                for (character in normalized) {
                    text.append(character)
                    positions.add(position)
                }
            }
            if (text.isEmpty()) return null
            return PageIndex(pageText, order, text.toString(), positions.toIntArray())
        }
    }
}

/** The text a search compares in: NFKC, whitespace runs collapsed, edges trimmed of spaces. */
internal fun normalizeQuote(text: String): String {
    val out = StringBuilder(text.length)
    var pendingSpace = false
    var index = 0
    while (index < text.length) {
        val codepoint = text.codePointAt(index)
        index += Character.charCount(codepoint)
        if (Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint)) {
            pendingSpace = out.isNotEmpty()
            continue
        }
        if (pendingSpace) {
            out.append(' ')
            pendingSpace = false
        }
        out.append(Normalizer.normalize(String(Character.toChars(codepoint)), Normalizer.Form.NFKC))
    }
    return out.toString()
}

/**
 * The renderer positions of the two hinted ids, or null when either is not on the page.
 *
 * The reading order is searched rather than indexed because the hint is a *pair*: PDFium's ids run
 * in stream order and the reading order deliberately disagrees, so the two ends are two lookups.
 * A page can hold a repeated id only if its producer is malformed; the last match wins, which keeps
 * the range forward and therefore drawable.
 */
private fun positionsOfIds(order: PdfReadingOrder, startId: Int, endId: Int): IntRange? {
    if (startId < 0 || endId < startId) return null
    var first = -1
    var last = -1
    for (position in order.chars.indices) {
        val id = order.chars[position].id
        if (id == startId) first = position
        if (id == endId) last = position
    }
    return if (first in 0..last) first..last else null
}

/**
 * One rectangle per line the passage covers: the span of its characters, and the line's own height.
 *
 * A zero-height glyph — a boxless space or a flattened Hebrew run — would otherwise draw an
 * invisible highlight; the line's widened hit box is the height a person sees, so it stands in.
 */
private fun rectsOf(order: PdfReadingOrder, range: IntRange): List<PdfMarkRect> {
    val rects = ArrayList<PdfMarkRect>()
    var currentLine = -1
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var top = Float.NEGATIVE_INFINITY
    var bottom = Float.POSITIVE_INFINITY

    fun flush() {
        if (left > right) return
        val line = order.lines.getOrNull(currentLine) ?: return
        val lineTop = if (top > bottom) top else line.hitTop
        val lineBottom = if (top > bottom) bottom else line.hitBottom
        rects += PdfMarkRect(left = left, bottom = lineBottom, right = right, top = lineTop)
    }

    for (position in range) {
        val lineIndex = order.lineOf(position)
        if (lineIndex != currentLine) {
            flush()
            currentLine = lineIndex
            left = Float.POSITIVE_INFINITY
            right = Float.NEGATIVE_INFINITY
            top = Float.NEGATIVE_INFINITY
            bottom = Float.POSITIVE_INFINITY
        }
        val character = order.chars.getOrNull(position) ?: continue
        left = min(left, character.left)
        right = max(right, character.right)
        top = max(top, character.top)
        bottom = min(bottom, character.bottom)
    }
    flush()
    return rects
}
