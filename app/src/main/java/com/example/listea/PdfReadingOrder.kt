package com.example.listea

import androidx.compose.runtime.Immutable
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/**
 * A page's characters in the order a person reads them, with the geometry to draw and hit-test
 * against.
 *
 * **PDFium does not expose reading order.** Its glyph order is the content stream's, which is
 * arbitrary for two-column documents and for text a producer has repositioned; selecting a
 * contiguous range of indices would then select a zig-zag across the page. The order here is
 * re-derived from the boxes by the same three passes Nucleus's own selection layer uses, ported
 * from its published source at the pinned version (MIT — see the third-party notices):
 *
 *  1. group characters into horizontal bands by vertical overlap;
 *  2. split a band into sub-lines wherever a horizontal gap is much wider than a character;
 *  3. when those sub-lines cluster around one page gutter, read the left column entirely before
 *     the right; otherwise read band by band.
 *
 * RTL is a per-line decision: a line whose characters are mostly Hebrew or Arabic is emitted in
 * logical (memory) order by walking its boxes right-to-left.
 *
 * **This is pure.** It takes [PdfPageText] and returns data; the tests build pages by hand, which
 * is the only way to pin a heuristic without a binary fixture per case.
 *
 * Positions in this class are *reading* positions, never renderer char ids. The id is reachable as
 * `chars[pos].id` and is used exactly once on the way out, as an anchor hint.
 */
@Immutable
internal class PdfReadingOrder private constructor(
    val chars: List<PdfChar>,
    val lines: List<PdfLine>,
    private val words: List<List<IntRange>>
) {

    val size: Int get() = chars.size

    /** The line a reading position belongs to, or -1. */
    fun lineOf(position: Int): Int {
        var lo = 0
        var hi = lines.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val line = lines[mid]
            when {
                position < line.start -> hi = mid - 1
                position >= line.endExclusive -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * The selected text in reading order, one `\n` between lines.
     *
     * Lines rather than spaces, because a quote is shown back to a user as the document was laid
     * out, and a paragraph reflowed onto one line would no longer match what they dragged across.
     */
    fun text(lo: Int, hi: Int): String {
        if (size == 0 || lo > hi) return ""
        val from = lo.coerceAtLeast(0)
        val to = hi.coerceAtMost(size - 1)
        if (from > to) return ""
        val text = StringBuilder()
        var previousLine = -1
        for (position in from..to) {
            val line = lineOf(position)
            if (previousLine >= 0 && line != previousLine) text.append('\n')
            appendCodepoint(text, chars[position].codepoint)
            previousLine = line
        }
        return text.toString()
    }

    /**
     * The word around [position], as reading positions, for snapping a handle or a long press.
     *
     * Unicode word boundaries rather than spaces: a quote should not begin halfway through
     * "don't", and a CJK run has no spaces at all. A position on whitespace yields itself alone,
     * so pressing a gap selects one character rather than jumping to a word on either side.
     */
    fun wordRangeAt(position: Int): IntRange {
        if (position !in 0 until size) return position..position
        val lineIndex = lineOf(position)
        if (lineIndex < 0) return position..position
        val lineWords = words[lineIndex]
        for (range in lineWords) {
            if (position <= range.last) {
                return if (position >= range.first) range else position..position
            }
        }
        return position..position
    }

    companion object {
        val Empty = PdfReadingOrder(emptyList(), emptyList(), emptyList())

        fun of(page: PdfPageText): PdfReadingOrder {
            val glyphs = ArrayList<Glyph>(page.chars.size)
            for (char in page.chars) {
                if (char.codepoint <= 0) continue
                // PDFium's layout reports the line break itself as a carriage-return and a
                // newline character, each with a point-sized box at the end of the line. They
                // are structure, not text: kept, every line break would arrive as two extra
                // zero-height lines and text() would join lines with three newlines.
                if (Character.isISOControl(char.codepoint)) continue
                // Zero-height boxes are kept: Hebrew/Arabic producers emit them for glyphs that
                // render tall, and the line's hit box is widened below to cover that case.
                if (char.top < char.bottom) continue
                // Zero-width boxes are normally degenerate glyphs and are dropped — but a space
                // is not: a producer that writes words as separate runs can leave the space
                // between them boxless while still reporting it. Dropping that one does not lose
                // a glyph, it loses the boundary, and every quote spanning two words comes out
                // concatenated ("שלוםעולם", "alphaonebetatwo").
                if (char.right <= char.left && !Character.isWhitespace(char.codepoint)) continue
                glyphs.add(Glyph(char))
            }
            if (glyphs.isEmpty()) return Empty

            val orderedLines = orderedLines(glyphs, page.widthPoints)
            if (orderedLines.isEmpty()) return Empty

            val ordered = ArrayList<PdfChar>(glyphs.size)
            val lines = ArrayList<PdfLine>(orderedLines.size)
            for (line in orderedLines) {
                // A line that is mostly Hebrew or Arabic is emitted by walking its boxes right to
                // left, so the selection is contiguous in memory order rather than in screen
                // order. Latin lines keep the left-to-right walk that made them.
                val rtl = line.count { isRtlCodepoint(it.char.codepoint) } * 2 > line.size
                val inReadingOrder = if (rtl) line.sortedByDescending { it.left } else line
                val start = ordered.size
                for (glyph in inReadingOrder) ordered.add(glyph.char)
                val bounds = line.fold(LineBounds()) { acc, glyph -> acc.plus(glyph) }
                lines.add(
                    PdfLine(
                        start = start,
                        endExclusive = ordered.size,
                        left = bounds.left,
                        bottom = bounds.bottom,
                        right = bounds.right,
                        top = bounds.top,
                        hitTop = bounds.top,
                        hitBottom = bounds.bottom
                    )
                )
            }

            val expanded = expandLineHitBounds(lines)
            val words = expanded.mapIndexed { index, line ->
                wordsIn(ordered.subList(line.start, line.endExclusive))
                    .map { local ->
                        (line.start + local.first)..(line.start + local.last)
                    }
            }
            return PdfReadingOrder(ordered, expanded, words)
        }

        // --- reading order construction ---------------------------------------------------------

        private fun orderedLines(glyphs: List<Glyph>, pageWidth: Float): List<List<Glyph>> {
            // Seed top-to-bottom, then left-to-right; the bands below are what actually decide.
            val sorted = glyphs.sortedWith(
                compareByDescending<Glyph> { it.centerY }.thenBy { it.left }
            )

            val bands = ArrayList<ArrayList<Glyph>>()
            var band = arrayListOf(sorted[0])
            var bandTop = sorted[0].top
            var bandBottom = sorted[0].bottom
            for (index in 1 until sorted.size) {
                val glyph = sorted[index]
                val overlap = min(bandTop, glyph.top) - max(bandBottom, glyph.bottom)
                val minHeight = min(bandTop - bandBottom, glyph.height)
                // A space can be a point rather than a box — zero-width, and with no height for
                // the overlap rule to measure. It is still a word boundary on the line it sits
                // in, so a degenerate space whose position falls inside the current band joins
                // that band without shrinking it. Left to start a band of its own it would add a
                // line break, and "hello world" would quote as "hello\n \nworld".
                val pointSpace = glyph.height <= 0f &&
                    Character.isWhitespace(glyph.char.codepoint) &&
                    glyph.centerY in bandBottom..bandTop
                if (pointSpace) {
                    band.add(glyph)
                    continue
                }
                if (minHeight > 0f && overlap > minHeight * 0.5f) {
                    band.add(glyph)
                    bandTop = max(bandTop, glyph.top)
                    bandBottom = min(bandBottom, glyph.bottom)
                } else {
                    bands.add(band)
                    band = arrayListOf(glyph)
                    bandTop = glyph.top
                    bandBottom = glyph.bottom
                }
            }
            bands.add(band)

            // Split each band at gaps far wider than a character. A wide word space in justified
            // text must not become a column, so the threshold is three average character widths.
            val subLines = ArrayList<ArrayList<Glyph>>()
            // A page whose bands split into three or more runs is a grid — a table, a form — not
            // a two-column page. Pairing its first two runs would read it column by column, which
            // is exactly wrong for a table, so the gutter rule below is not offered to it.
            var widestBand = 0
            for (candidate in bands) {
                // At equal x a space belongs before the glyph it separates: a synthetic space
                // sits exactly at the start of the next word, and an arbitrary tie-break would
                // read "alphab eta".
                candidate.sortWith(
                    compareBy<Glyph> { it.left }
                        .thenByDescending { Character.isWhitespace(it.char.codepoint) }
                )
                val averageWidth = (candidate.sumOf { it.width.toDouble() } / candidate.size)
                    .toFloat()
                    .coerceAtLeast(1f)
                val threshold = averageWidth * ColumnGapMultiple
                var current = arrayListOf(candidate[0])
                var previousRight = candidate[0].right
                var runs = 1
                for (index in 1 until candidate.size) {
                    val glyph = candidate[index]
                    if (glyph.left - previousRight > threshold) {
                        subLines.add(current)
                        current = arrayListOf(glyph)
                        runs++
                    } else {
                        current.add(glyph)
                    }
                    previousRight = max(previousRight, glyph.right)
                }
                subLines.add(current)
                widestBand = max(widestBand, runs)
            }

            val gutter = if (widestBand <= 2) detectGutter(subLines, pageWidth) else null
            if (gutter == null) {
                return filterNoiseLines(subLines.sortedWith(readingOrderOfLines()))
            }

            val left = ArrayList<ArrayList<Glyph>>()
            val right = ArrayList<ArrayList<Glyph>>()
            for (line in subLines) {
                val centerX = line.sumOf { it.centerX.toDouble() }.toFloat() / line.size
                if (centerX < gutter) left.add(line) else right.add(line)
            }
            left.sortWith(readingOrderOfLines())
            right.sortWith(readingOrderOfLines())
            return filterNoiseLines(left + right)
        }

        private fun readingOrderOfLines() =
            compareByDescending<ArrayList<Glyph>> { it.first().centerY }
                .thenBy { it.first().left }

        /**
         * Drop very short runs surrounded by much longer ones.
         *
         * They are decorative sub-glyphs and CID fallback runs, common in older Hebrew and Arabic
         * PDFs and in scan-rebuilt documents. Keeping them pollutes copied text and makes the
         * highlight flicker through bands of meaningless characters. A page made entirely of short
         * lines — a title page, a table of contents — is left alone.
         */
        private fun filterNoiseLines(lines: List<ArrayList<Glyph>>): List<ArrayList<Glyph>> {
            if (lines.isEmpty()) return lines
            val longest = lines.maxOf { it.size }
            if (longest < NoiseBaseline) return lines
            val threshold = max(NoiseCutoff, longest / 6)
            val kept = lines.filter { it.size >= threshold }
            return kept.ifEmpty { lines }
        }

        /**
         * The page gutter, when the page looks like two columns.
         *
         * A gutter is trusted only when most rows contribute a gap at the same x and that x sits
         * in the middle half of the page — otherwise a single marginal note would reorder the
         * whole document.
         */
        private fun detectGutter(lines: List<List<Glyph>>, pageWidth: Float): Float? {
            if (pageWidth <= 0f) return null
            val midpoints = ArrayList<Float>()
            var pairs = 0
            var index = 0
            while (index < lines.size) {
                val a = lines[index]
                val aTop = a.maxOf { it.top }
                val aBottom = a.minOf { it.bottom }
                var next = index + 1
                var bestGapMidpoint = Float.NaN
                var bestGap = 0f
                while (next < lines.size) {
                    val b = lines[next]
                    val bTop = b.maxOf { it.top }
                    val bBottom = b.minOf { it.bottom }
                    val overlap = min(aTop, bTop) - max(aBottom, bBottom)
                    val minHeight = min(aTop - aBottom, bTop - bBottom)
                    if (minHeight <= 0f || overlap <= minHeight * 0.5f) break
                    val aRight = a.maxOf { it.right }
                    val bLeft = b.minOf { it.left }
                    if (bLeft > aRight) {
                        val gap = bLeft - aRight
                        if (gap > bestGap) {
                            bestGap = gap
                            bestGapMidpoint = (aRight + bLeft) * 0.5f
                        }
                    }
                    next++
                }
                if (!bestGapMidpoint.isNaN()) {
                    midpoints.add(bestGapMidpoint)
                    pairs++
                }
                index = if (next > index + 1) next - 1 else index + 1
            }
            if (pairs < 3) return null

            val bucketSize = (pageWidth * 0.02f).coerceAtLeast(1f)
            val buckets = HashMap<Int, Int>()
            for (midpoint in midpoints) {
                val key = (midpoint / bucketSize).toInt()
                buckets[key] = (buckets[key] ?: 0) + 1
            }
            val best = buckets.entries.maxByOrNull { it.value } ?: return null
            val ratio = best.value.toFloat() / midpoints.size
            if (ratio < 0.6f) return null
            val gutter = (best.key + 0.5f) * bucketSize
            if (gutter < pageWidth * 0.25f || gutter > pageWidth * 0.75f) return null
            return gutter
        }

        /**
         * Widen each line's vertical hit box halfway to its nearest neighbour in the same column.
         *
         * A press between two rows then lands on one of them, and a zero-height line still has a
         * target. Lines in the other column are excluded by requiring horizontal overlap.
         */
        private fun expandLineHitBounds(lines: List<PdfLine>): List<PdfLine> {
            if (lines.isEmpty()) return lines
            return lines.mapIndexed { index, self ->
                val selfCenter = self.centerY
                var aboveBottom = Float.POSITIVE_INFINITY
                var belowTop = Float.NEGATIVE_INFINITY
                for (otherIndex in lines.indices) {
                    if (otherIndex == index) continue
                    val other = lines[otherIndex]
                    if (min(self.right, other.right) <= max(self.left, other.left)) continue
                    val otherCenter = other.centerY
                    if (otherCenter > selfCenter) {
                        aboveBottom = min(aboveBottom, other.bottom)
                    } else if (otherCenter < selfCenter) {
                        belowTop = max(belowTop, other.top)
                    }
                }
                val gapAbove = if (aboveBottom.isFinite()) {
                    (aboveBottom - self.top).coerceAtLeast(0f)
                } else {
                    DefaultHitExpand
                }
                val gapBelow = if (belowTop.isFinite()) {
                    (self.bottom - belowTop).coerceAtLeast(0f)
                } else {
                    DefaultHitExpand
                }
                self.copy(
                    hitTop = self.top + gapAbove * 0.5f,
                    hitBottom = self.bottom - gapBelow * 0.5f
                )
            }
        }

        // --- words ------------------------------------------------------------------------------

        /**
         * Word boundaries inside one line, as local positions (index into [lineChars]).
         *
         * The line's text is built once and a boundary index over it is mapped back through the
         * codepoint offsets, because BreakIterator counts UTF-16 units and a position counts
         * codepoints. Surviving a surrogate pair is the whole reason for the map.
         */
        private fun wordsIn(lineChars: List<PdfChar>): List<IntRange> {
            if (lineChars.isEmpty()) return emptyList()
            val text = StringBuilder()
            val offsets = IntArray(lineChars.size + 1)
            for ((index, char) in lineChars.withIndex()) {
                offsets[index] = text.length
                appendCodepoint(text, char.codepoint)
            }
            offsets[lineChars.size] = text.length
            val lineText = text.toString()

            fun positionAt(offset: Int): Int {
                val found = offsets.binarySearch(offset)
                if (found >= 0) return found.coerceAtMost(lineChars.lastIndex)
                return (-found - 2).coerceIn(0, lineChars.lastIndex)
            }

            val iterator = BreakIterator.getWordInstance()
            iterator.setText(lineText)
            val ranges = ArrayList<IntRange>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                var trimmedStart = start
                var trimmedEnd = end
                while (trimmedStart < trimmedEnd && lineText[trimmedStart].isWhitespace()) trimmedStart++
                while (trimmedEnd > trimmedStart && lineText[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                if (trimmedStart < trimmedEnd) {
                    ranges.add(positionAt(trimmedStart)..positionAt(trimmedEnd - 1))
                } else {
                    val position = positionAt(start)
                    ranges.add(position..position)
                }
                start = end
                end = iterator.next()
            }
            return ranges
        }
    }
}

/** One line's position range and geometry, all in PDF points. */
@Immutable
internal data class PdfLine(
    val start: Int,
    val endExclusive: Int,
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    val hitTop: Float,
    val hitBottom: Float
) {
    val centerY: Float get() = (top + bottom) * 0.5f
}

private class Glyph(val char: PdfChar) {
    val left: Float get() = char.left
    val right: Float get() = char.right
    val top: Float get() = char.top
    val bottom: Float get() = char.bottom
    val width: Float get() = right - left
    val height: Float get() = top - bottom
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

private class LineBounds {
    var left: Float = Float.POSITIVE_INFINITY
    var bottom: Float = Float.POSITIVE_INFINITY
    var right: Float = Float.NEGATIVE_INFINITY
    var top: Float = Float.NEGATIVE_INFINITY

    fun plus(glyph: Glyph): LineBounds = apply {
        left = min(left, glyph.left)
        bottom = min(bottom, glyph.bottom)
        right = max(right, glyph.right)
        top = max(top, glyph.top)
    }
}

private fun isRtlCodepoint(codepoint: Int): Boolean = when (codepoint) {
    in 0x0590..0x05FF -> true                // Hebrew
    in 0x0600..0x06FF -> true                // Arabic
    in 0x0700..0x074F -> true                // Syriac
    in 0x0750..0x077F -> true                // Arabic Supplement
    in 0x0780..0x07BF -> true                // Thaana
    in 0x07C0..0x07FF -> true                // NKo
    in 0x0800..0x083F -> true                // Samaritan
    in 0x0840..0x085F -> true                // Mandaic
    in 0xFB1D..0xFB4F -> true                // Hebrew Presentation Forms
    in 0xFB50..0xFDFF -> true                // Arabic Presentation Forms-A
    in 0xFE70..0xFEFF -> true                // Arabic Presentation Forms-B
    else -> false
}

internal fun appendCodepoint(text: StringBuilder, codepoint: Int) {
    if (codepoint <= 0xFFFF) {
        text.append(codepoint.toChar())
    } else {
        val adjusted = codepoint - 0x10000
        text.append((0xD800 or (adjusted shr 10)).toChar())
        text.append((0xDC00 or (adjusted and 0x3FF)).toChar())
    }
}

/** A gap wider than three average character widths is a column boundary, not a word space. */
private const val ColumnGapMultiple = 3f

/** Half-height of an isolated line's hit target, in PDF points. */
private const val DefaultHitExpand = 6f

/** A page whose longest line holds this many characters is assumed to carry real text. */
private const val NoiseBaseline = 20
private const val NoiseCutoff = 6
