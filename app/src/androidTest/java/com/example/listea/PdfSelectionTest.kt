package com.example.listea

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a finger actually gets when it selects text in a real document: whole words, both lines of
 * a drag, and a quote in reading order — plus the reading order itself on a two-column page and a
 * Hebrew page, where a wrong answer is invisible in a fixture and obvious to a reader.
 *
 * The two reading-order tests read the *generated* page back through the renderer. That is
 * deliberate: [PdfReadingOrderTest] pins the heuristic on hand-built boxes, while these pin the
 * whole chain — renderer extraction, the neutral text layer, the heuristic — on bytes this device
 * produced, which is the part a generator can silently get wrong.
 */
@RunWith(AndroidJUnit4::class)
class PdfSelectionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aLongPressSelectsTheWholeWordUnderTheFinger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var quote: PdfQuote? = null
        rule.showPdf(fixture, onQuote = { quote = it })
        rule.awaitSelectionLayer(0)

        // Inside the first word, not at its edge: a character-level selection would return a
        // single letter here, which is the failure this pins.
        rule.onNodeWithTag("pdf-selection-0").performTouchInput {
            down(Offset(width * 0.10f, height * 0.065f))
            advanceEventTime(700)
            up()
        }
        rule.waitForIdle()

        val selected = quote
        assertNotNull("a long press should leave a selection behind", selected)
        assertEquals("the selection should be the whole word", PdfFixtureFactory.FirstWord, selected!!.quote)
        assertEquals("the quote starts at the top of the page", "", selected.prefix)
        assertTrue(
            "the quote should carry the text that follows it: [${selected.suffix}]",
            selected.suffix.startsWith("\n")
        )
        assertTrue("the quote should carry the renderer's hint", selected.startChar >= 0)
        assertTrue("the hint should be a forward range", selected.endChar >= selected.startChar)
        rule.onNodeWithTag("pdf-selection-menu").assertExists()
    }

    @Test
    fun aTapOutsideTheSelectionClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var quote: PdfQuote? = null
        rule.showPdf(fixture, onQuote = { quote = it })
        rule.awaitSelectionLayer(0)

        rule.onNodeWithTag("pdf-selection-0").performTouchInput {
            down(Offset(width * 0.10f, height * 0.065f))
            advanceEventTime(700)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pdf-selection-menu").assertExists()

        // A tap away from the passage clears it. The up this tap sends is the one the old
        // long-press helper had already read, so this pins the tap being answered at all.
        rule.onNodeWithTag("pdf-selection-0").performTouchInput {
            click(Offset(width * 0.90f, height * 0.90f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("pdf-selection-menu").assertDoesNotExist()
    }

    @Test
    fun draggingAcrossTwoLinesQuotesThemInReadingOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var quote: PdfQuote? = null
        rule.showPdf(fixture, onQuote = { quote = it })
        rule.awaitSelectionLayer(0)

        rule.onNodeWithTag("pdf-selection-0").performTouchInput {
            val x = width * 0.10f
            down(Offset(x, height * 0.065f))
            advanceEventTime(700)
            moveTo(Offset(x, height * 0.115f))
            advanceEventTime(50)
            up()
        }
        rule.waitForIdle()

        val selected = quote
        assertNotNull("a long-press drag should select across both lines", selected)
        assertEquals(
            "the two lines should quote top to bottom, joined by a newline",
            "${PdfFixtureFactory.FirstWord}\n${PdfFixtureFactory.SecondWord}",
            selected!!.quote
        )
        assertEquals("nothing precedes the first character", "", selected.prefix)
        assertEquals("nothing follows the last character", "", selected.suffix)
    }

    @Test
    fun aTwoColumnPageReadsDownTheLeftColumnThenTheRight() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.twoColumn(context)
        val state = PdfFixtureFactory.openReader(fixture)
        try {
            val page = state.readPageText(0)!!
            val order = PdfReadingOrder.of(page)

            // The words first: extraction must see both columns, or the order below is vacuous.
            val text = order.text(0, order.size - 1)
            assertTrue("the left column should be extracted: [$text]", text.contains("ALPHAONE"))
            assertTrue("the right column should be extracted: [$text]", text.contains("BETAONE"))
            assertEquals(
                "a two-column page should read down the left column, then the right\n" +
                    dump(page),
                (PdfFixtureFactory.LeftColumn + PdfFixtureFactory.RightColumn).joinToString("\n"),
                text
            )
        } finally {
            state.dispose()
        }
    }

    /** Every extracted character with its box, for a failure message that can be acted on. */
    private fun dump(page: PdfPageText): String = page.chars.joinToString("\n") {
        "${it.codepoint} [${it.left}, ${it.right}] x [${it.bottom}, ${it.top}]"
    }

    @Test
    fun aHebrewLineReadsInLogicalOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.hebrew(context)
        val state = PdfFixtureFactory.openReader(fixture)
        try {
            val page = state.readPageText(0)!!
            // Assert the extractor first, because the interesting thing about it is not obvious:
            // PDFium's text layout reports an RTL run in *logical* order even though the glyphs
            // were written right to left. The boxes are what say where each character sits, and
            // that is why the reading order must undo physical order rather than trust the index.
            val extracted = buildString {
                page.chars.forEach { appendCodepoint(this, it.codepoint) }
            }
            assertEquals(
                "the extractor should report the RTL run in logical order",
                PdfFixtureFactory.HebrewText,
                extracted
            )

            val order = PdfReadingOrder.of(page)
            assertEquals(
                "a Hebrew line should quote in logical order, not as it is laid out\n" +
                    dump(page),
                PdfFixtureFactory.HebrewText,
                order.text(0, order.size - 1)
            )
            // And the boxes really do run the other way, so this is not a fixture that agrees by
            // luck: the logical first character is drawn at the right.
            assertTrue(
                "the first logical character should be the rightmost box",
                order.chars.first().left > order.chars.last().left
            )
        } finally {
            state.dispose()
        }
    }
}
