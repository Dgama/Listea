package com.example.listea

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike checks for the three things adb cannot inject on a PDF: a pinch, a vertical drag that
 * must reach the page list, and a long-press-drag that must select text instead.
 *
 * This is not a product test — it hosts the viewer directly, on a generated three-page fixture,
 * with no Todo, template or export anywhere near it.
 */
@RunWith(AndroidJUnit4::class)
class PdfPreviewSpikeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectsQuoteScrollsAndPinches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val pdf = File(dir, "pdf-spike.pdf")
        // The fixture lives in the test APK, not in the app under test.
        instrumentation.context.assets.open("pdf-spike.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)

        lateinit var zoom: MediaZoomState
        var quote: PdfQuote? = null
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag("card")) {
                SwipeCard(
                    key = uri.toString(),
                    canSwipeForward = false,
                    canSwipeBack = false,
                    onSwipeForward = {},
                    onSwipeBack = {},
                    modifier = Modifier.fillMaxSize()
                ) { state ->
                    zoom = state
                    PdfPreview(
                        uri = uri.toString(),
                        name = "pdf-spike.pdf",
                        modifier = Modifier.fillMaxSize(),
                        zoom = state,
                        onQuote = { quote = it }
                    )
                }
            }
        }

        // The selection layer appears only once the page's text layout has been read.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("pdf-selection-0").fetchSemanticsNodes().isNotEmpty()
        }

        // 1. Select across the heading line: long press, then drag, then release.
        val selectionLayer = rule.onNodeWithTag("pdf-selection-0")
        selectionLayer.performTouchInput {
            val lineY = height * 0.09f
            down(Offset(width * 0.15f, lineY))
            advanceEventTime(700)
            moveTo(Offset(width * 0.70f, lineY))
            advanceEventTime(50)
            up()
        }
        rule.waitForIdle()
        val selected = quote
        assertNotNull("long-press drag should produce a quote", selected)
        assertTrue(
            "the quote should be the heading: ${selected?.quote}",
            selected?.quote?.contains("Listea", ignoreCase = true) == true
        )

        // 2. A short vertical drag must still reach the page list.
        val before = selectionLayer.getBoundsInRoot()
        rule.onNodeWithTag("card").performTouchInput {
            swipe(center, center - Offset(0f, 240f), durationMillis = 300)
        }
        rule.waitForIdle()
        val after = selectionLayer.getBoundsInRoot()
        assertTrue("a vertical drag should scroll the pages: $before -> $after", after.top < before.top - 1.dp)

        // 3. A pinch must still zoom the document.
        rule.onNodeWithTag("card").performTouchInput {
            pinch(
                start0 = Offset(center.x - 40f, center.y),
                end0 = Offset(center.x - 240f, center.y),
                start1 = Offset(center.x + 40f, center.y),
                end1 = Offset(center.x + 240f, center.y)
            )
        }
        rule.waitForIdle()
        assertTrue("a pinch should zoom the document: scale=${zoom.scale}", zoom.scale > 1f)
    }
}
