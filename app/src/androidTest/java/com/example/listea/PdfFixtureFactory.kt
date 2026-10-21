package com.example.listea

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import dev.nucleusframework.pdfium.PdfReaderState
import java.io.File

/**
 * PDFs generated on the device for the viewer and selection tests.
 *
 * The plan calls for a two-column page and an RTL page without committing a binary per case: they
 * are drawn here with Android's own `PdfDocument`, written to the app's cache, and handed to the
 * viewer through the same FileProvider the rest of the app uses.
 *
 * **Generation is not the thing under test.** A generator can draw text that nothing can extract —
 * a font without a usable ToUnicode map looks perfect and yields no selectable characters — so
 * [openReader] and [readPageText] read every fixture back through the renderer, and the tests
 * assert the expected words before they assert a gesture. If extraction ever regresses, the
 * failure names the fixture rather than a mysterious empty selection.
 */
internal object PdfFixtureFactory {

    /** A4 at 72 dpi, so one page point is one pixel in the generated document. */
    const val PageWidth = 595
    const val PageHeight = 842

    /** Baselines of the two text lines the selection tests drag between, in canvas pixels. */
    const val FirstBaseline = 60f
    const val SecondBaseline = 100f

    /** The word on the first line of [prose], and the one on the second. */
    const val FirstWord = "alphaone"
    const val SecondWord = "betatwo"

    /** The two lines of [twoColumn], left column then right, as extraction is expected to read. */
    val LeftColumn = listOf("ALPHAONE", "ALPHATWO", "ALPHATHREE")
    val RightColumn = listOf("BETAONE", "BETATWO", "BETATHREE")

    /** Logical Hebrew text of [hebrew]; it is drawn in visual order, rightmost character first. */
    const val HebrewText = "שלום עולם"

    /** The characters of [HebrewText] as they are written into the PDF, left to right. */
    val HebrewDrawn: List<Char> = HebrewText.reversed().toList()

    /** Two text lines on page 1, one on page 2 — enough to scroll and to select across lines. */
    fun prose(context: Context): PdfFixture = write(context, "prose.pdf", pages = 2) { canvas, index ->
        val paint = textPaint()
        if (index == 1) {
            canvas.drawText(FirstWord, 40f, FirstBaseline, paint)
            canvas.drawText(SecondWord, 40f, SecondBaseline, paint)
        } else {
            canvas.drawText("secondpage", 40f, FirstBaseline, paint)
        }
    }

    /**
     * Two pages with no text at all: filled bands and rules, the shape of a scan.
     *
     * Nothing is drawn with [Canvas.drawText], so the page has no glyphs for a text layer to find;
     * that is the whole property under test.
     */
    fun scanned(context: Context): PdfFixture = write(context, "scanned.pdf", pages = 2) { canvas, _ ->
        val ink = Paint().apply { color = Color.rgb(70, 70, 70) }
        canvas.drawRect(40f, 40f, PageWidth - 40f, 160f, ink)
        for (line in 0..12) {
            val top = 220f + line * 40f
            if (line % 4 == 3) {
                canvas.drawRect(40f, top, PageWidth - 40f, top + 4f, ink)
            } else {
                canvas.drawRect(40f, top, PageWidth - 70f - line * 8f, top + 14f, ink)
            }
        }
    }

    /** Two columns on one page, each line sharing a baseline with its neighbour across the gutter. */
    fun twoColumn(context: Context): PdfFixture = write(context, "two-column.pdf", pages = 1) { canvas, _ ->
        val paint = textPaint()
        LeftColumn.forEachIndexed { row, text ->
            canvas.drawText(text, 40f, FirstBaseline + row * 40f, paint)
        }
        RightColumn.forEachIndexed { row, text ->
            canvas.drawText(text, 330f, FirstBaseline + row * 40f, paint)
        }
    }

    /**
     * One Hebrew line in visual order.
     *
     * The line is drawn **one character at a time**, advancing by the measured width, rather than
     * as one string. A single string is shaped by the platform, which bidi-reorders a Hebrew run
     * before it is written — a convenience for screen text and a trap for a fixture, because then
     * no one can say from the bytes which character was meant to be on the left. One glyph per
     * `drawText` call has no run to reorder: the characters land exactly in string order, and the
     * string is [HebrewText] reversed, so the first logical character sits at the right as a
     * Hebrew producer would place it. The reading order is what turns those boxes back into
     * [HebrewText]; [HebrewDrawn] is what the extractor is expected to report first.
     */
    fun hebrew(context: Context): PdfFixture = write(context, "hebrew.pdf", pages = 1) { canvas, _ ->
        val paint = textPaint()
        var x = 40f
        for (character in HebrewDrawn) {
            val glyph = character.toString()
            canvas.drawText(glyph, x, FirstBaseline, paint)
            x += paint.measureText(glyph)
        }
    }

    // --- reading a fixture back -----------------------------------------------------------------

    /**
     * Opens [fixture] with the real renderer, outside a composition.
     *
     * `rememberPdfReaderState` is the supported entry point but is composable; the constructor is
     * Kotlin-internal, a compile-time marker only, so reflection reaches the same state the viewer
     * would use. The caller owns [PdfReaderState.dispose].
     */
    suspend fun openReader(fixture: PdfFixture): PdfReaderState {
        val long = Long::class.javaPrimitiveType
        val constructor = PdfReaderState::class.java
            .getDeclaredConstructor(long, long)
        constructor.isAccessible = true
        val state = constructor.newInstance(
            PdfReaderState.DEFAULT_CACHE_BYTES,
            12L * 1024 * 1024
        ) as PdfReaderState
        state.open(fixture.file.readBytes())
        return state
    }

    // --- writing --------------------------------------------------------------------------------

    private fun textPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private fun write(
        context: Context,
        name: String,
        pages: Int,
        draw: (Canvas, Int) -> Unit
    ): PdfFixture {
        val document = PdfDocument()
        try {
            for (index in 1..pages) {
                val info = PdfDocument.PageInfo.Builder(PageWidth, PageHeight, index).create()
                val page = document.startPage(info)
                draw(page.canvas, index)
                document.finishPage(page)
            }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { output -> document.writeTo(output) }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            return PdfFixture(file, uri)
        } finally {
            document.close()
        }
    }
}

internal class PdfFixture(val file: File, val uri: Uri) {
    val name: String get() = file.name
}

/**
 * One page's text in the app's neutral shape, exactly as [NucleusPdfDocumentSession] builds it.
 *
 * The mapping is duplicated rather than calling the private adapter, so an adapter change that
 * drops characters shows up as a fixture failure as well.
 */
internal suspend fun PdfReaderState.readPageText(index: Int): PdfPageText? {
    val layout = pageTextLayout(index) ?: return null
    val chars = ArrayList<PdfChar>(layout.charCount)
    for (char in 0 until layout.charCount) {
        chars.add(
            PdfChar(
                id = char,
                codepoint = layout.codepoint(char),
                left = layout.charLeft(char),
                bottom = layout.charBottom(char),
                right = layout.charRight(char),
                top = layout.charTop(char)
            )
        )
    }
    return PdfPageText(
        pageIndex = index,
        widthPoints = layout.pageSize.widthPoints,
        heightPoints = layout.pageSize.heightPoints,
        chars = chars
    )
}
