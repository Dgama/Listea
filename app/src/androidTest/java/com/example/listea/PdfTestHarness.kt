package com.example.listea

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.example.listea.domain.AssetMark
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton

/**
 * Hosts the real viewer the way a screen does: inside the card that owns zoom and paging.
 *
 * The viewer is never hosted bare, because the gestures under test are exactly the ones that have
 * to coexist with [SwipeCard] — a vertical drag belongs to the page list, a pinch to the card, a
 * horizontal drag to the pager, and a long press to text selection. A harness that left the card
 * out would prove nothing about any of those agreements.
 *
 * [tools] and [marks] are providers rather than values so a test can hold them in Compose state and
 * let the viewer recompose the way it does against the database: a mark created by [onCreateMark]
 * appears through the same argument a stored mark arrives by, and taking a tool out of the template
 * hides marks through the same path a template edit does.
 */
internal fun ComposeContentTestRule.showPdf(
    fixture: PdfFixture,
    tools: () -> List<TemplateButton> = { emptyList() },
    marks: () -> List<AssetMark> = { emptyList() },
    onCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? = null,
    onEditMark: ((String, String) -> Unit)? = null,
    onDeleteMark: ((String) -> Unit)? = null,
    onRelocateMark: ((String, QuoteAnchor) -> Unit)? = null,
    onQuote: ((PdfQuote) -> Unit)? = null,
    canSwipeForward: Boolean = false,
    canSwipeBack: Boolean = false,
    onSwipeForward: () -> Unit = {},
    onSwipeBack: () -> Unit = {},
    onZoom: (MediaZoomState) -> Unit = {}
) {
    setContent {
        Box(Modifier.fillMaxSize().testTag("card")) {
            SwipeCard(
                key = fixture.uri.toString(),
                canSwipeForward = canSwipeForward,
                canSwipeBack = canSwipeBack,
                onSwipeForward = onSwipeForward,
                onSwipeBack = onSwipeBack,
                modifier = Modifier.fillMaxSize()
            ) { state ->
                onZoom(state)
                PdfPreview(
                    uri = fixture.uri.toString(),
                    name = fixture.name,
                    modifier = Modifier.fillMaxSize(),
                    zoom = state,
                    tools = tools(),
                    marks = marks(),
                    onCreateMark = onCreateMark,
                    onEditMark = onEditMark,
                    onDeleteMark = onDeleteMark,
                    onRelocateMark = onRelocateMark,
                    onQuote = onQuote
                )
            }
        }
    }
}

/** Waits until the given page's selection layer has appeared, i.e. its text layer was read. */
internal fun ComposeContentTestRule.awaitSelectionLayer(pageIndex: Int) {
    waitUntil(10_000) {
        onAllNodesWithTag("pdf-selection-$pageIndex").fetchSemanticsNodes().isNotEmpty()
    }
}

/** Waits until the given page's highlight layer has appeared, i.e. a mark resolved there. */
internal fun ComposeContentTestRule.awaitHighlights(pageIndex: Int) {
    waitUntil(10_000) {
        onAllNodesWithTag("pdf-marks-$pageIndex").fetchSemanticsNodes().isNotEmpty()
    }
}
