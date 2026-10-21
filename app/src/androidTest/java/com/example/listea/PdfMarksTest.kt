package com.example.listea

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.listea.domain.AssetMark
import com.example.listea.domain.ButtonType
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.markKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review surface's mark loop, on a real renderer: a selection becomes a mark, the mark is drawn
 * where its anchor resolved, tapping the drawing opens every mark under the finger, and the sheet's
 * edit and delete reach the same facts the database would hold.
 *
 * The test owns the marks as Compose state and hands them in through the viewer's `marks` argument,
 * so the flow is exactly the one a stored mark takes: the viewer never mutates its own list. A
 * callback is the only way a mark appears, which is what keeps browsing from recording anything.
 */
@RunWith(AndroidJUnit4::class)
class PdfMarksTest {

    @get:Rule
    val rule = createComposeRule()

    private val funny = TemplateButton("tag-1", "Funny", ButtonType.TAG, "funny_sentence")
    private val important = TemplateButton("tag-2", "Important", ButtonType.TAG, "important")
    private val comment = TemplateButton("comment-1", "Comment", ButtonType.COMMENT, "")

    @Test
    fun aTagTapDrawsTheMarkAndTheDrawingOpensItsSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var marks by mutableStateOf<List<AssetMark>>(emptyList())

        rule.showPdf(
            fixture,
            tools = { listOf(funny) },
            marks = { marks },
            onCreateMark = { tool, text, anchor -> marks = marks + mark(tool, text, anchor) }
        )
        rule.selectFirstWord()
        rule.onNodeWithText("Funny").performClick()
        rule.waitUntil(5_000) { marks.size == 1 }

        rule.awaitHighlights(0)
        assertEquals("a tag stores the tool's configured value", "funny_sentence", marks.single().value)

        rule.tapFirstWord()
        rule.awaitSheet()
        rule.onNodeWithText("Mark").assertExists()
        rule.onNodeWithText("Page 1").assertExists()
        rule.onNodeWithText("“${PdfFixtureFactory.FirstWord}”").assertExists()
        rule.onNodeWithText("Funny").assertExists()
        rule.onNodeWithText("funny_sentence").assertExists()
    }

    @Test
    fun aFingerOnTwoMarksIsToldAboutBoth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var marks by mutableStateOf<List<AssetMark>>(emptyList())

        rule.showPdf(
            fixture,
            tools = { listOf(funny, important) },
            marks = { marks },
            onCreateMark = { tool, text, anchor -> marks = marks + mark(tool, text, anchor) }
        )
        rule.selectFirstWord()
        rule.onNodeWithText("Funny").performClick()
        rule.waitUntil(5_000) { marks.size == 1 }

        rule.selectFirstWord()
        rule.onNodeWithText("Important").performClick()
        rule.waitUntil(5_000) { marks.size == 2 }

        rule.tapFirstWord()
        rule.awaitSheet()
        rule.onNodeWithText("2 marks").assertExists()
        rule.onNodeWithText("Funny").assertExists()
        rule.onNodeWithText("Important").assertExists()
    }

    @Test
    fun aCommentIsWrittenEditedAndDeletedInTheSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var marks by mutableStateOf<List<AssetMark>>(emptyList())

        rule.showPdf(
            fixture,
            tools = { listOf(comment) },
            marks = { marks },
            onCreateMark = { tool, text, anchor -> marks = marks + mark(tool, text, anchor) },
            onEditMark = { id, text ->
                marks = marks.map { if (it.id == id) it.copy(value = text) else it }
            },
            onDeleteMark = { id -> marks = marks.filterNot { it.id == id } }
        )
        rule.selectFirstWord()
        rule.onNodeWithText("Comment").performClick()
        rule.onNodeWithTag("pdf-comment-field").performTextInput("I think so")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { marks.size == 1 }
        assertEquals("a comment stores the reviewer's words", "I think so", marks.single().value)

        rule.tapFirstWord()
        rule.awaitSheet()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithTag("pdf-mark-edit-field").performTextClearance()
        rule.onNodeWithTag("pdf-mark-edit-field").performTextInput("Changed")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { marks.single().value == "Changed" }
        rule.onNodeWithText("Changed").assertExists()

        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("It is removed from Listea. The PDF file is not changed.").assertExists()
        rule.onAllNodesWithText("Delete").onLast().performClick()
        rule.waitUntil(5_000) { marks.isEmpty() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("pdf-mark-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun aToolLeavingTheTemplateHidesMarksWithoutDeletingThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = PdfFixtureFactory.prose(context)
        var marks by mutableStateOf<List<AssetMark>>(emptyList())
        var tools by mutableStateOf(listOf(funny))

        rule.showPdf(
            fixture,
            tools = { tools },
            marks = { marks },
            onCreateMark = { tool, text, anchor -> marks = marks + mark(tool, text, anchor) }
        )
        rule.selectFirstWord()
        rule.onNodeWithText("Funny").performClick()
        rule.waitUntil(5_000) { marks.size == 1 }
        rule.awaitHighlights(0)

        // The template edit a review screen sees: the same governing control, one tool fewer.
        tools = emptyList()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("pdf-marks-0").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("hiding a mark is never deleting it", 1, marks.size)

        // Restoring the same tool id brings the same mark back, because neither the row nor its
        // anchor was touched.
        tools = listOf(funny)
        rule.awaitHighlights(0)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun mark(tool: TemplateButton, text: String?, anchor: QuoteAnchor): AssetMark {
        val now = System.currentTimeMillis()
        return AssetMark(
            id = "mark-${now}",
            assetId = "asset-1",
            toolId = tool.id,
            authoredTemplateId = "tpl-1",
            authoredTemplateVersion = 1,
            kind = tool.type.markKind()!!,
            value = if (tool.type == ButtonType.COMMENT) text.orEmpty() else tool.recordValue,
            anchor = anchor,
            createdAt = now,
            updatedAt = now
        )
    }

    /** A long press inside the first word, as the selection tests do. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.selectFirstWord() {
        awaitSelectionLayer(0)
        onNodeWithTag("pdf-selection-0").performTouchInput {
            down(Offset(width * 0.10f, height * 0.065f))
            advanceEventTime(700)
            up()
        }
        waitForIdle()
    }

    /** A plain tap on the passage, where the mark's rectangle is drawn. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.tapFirstWord() {
        onNodeWithTag("pdf-selection-0").performTouchInput {
            click(Offset(width * 0.10f, height * 0.065f))
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.awaitSheet() {
        waitUntil(5_000) {
            onAllNodesWithTag("pdf-mark-sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
