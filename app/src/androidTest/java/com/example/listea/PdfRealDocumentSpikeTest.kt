package com.example.listea

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SPIKE — selects Chinese text in a real document on this phone, through the same gestures the
 * spike test uses on its fixture. adb cannot inject a long-press-drag reliably, so this is the
 * only honest check on real documents.
 *
 * The document is copied out of the `pdftest` Upstream through the app's persisted grant; if that
 * grant or folder is absent (another install, another phone) the test skips.
 */
@RunWith(AndroidJUnit4::class)
class PdfRealDocumentSpikeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectsChineseFromRealDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = findDocument(context, "d7c00a96d6b41b0225c311a358e16ed0.pdf")
        assumeTrue("pdftest Upstream is not available or not granted", source != null)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val pdf = File(dir, "real-cn.pdf")
        context.contentResolver.openInputStream(source!!)?.use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)

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
                    PdfPreview(
                        uri = uri.toString(),
                        name = "real-cn.pdf",
                        modifier = Modifier.fillMaxSize(),
                        zoom = state,
                        onQuote = { quote = it }
                    )
                }
            }
        }

        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("pdf-selection-0").fetchSemanticsNodes().isNotEmpty()
        }

        // The title row sits at the very top of page 1; drag across it.
        rule.onNodeWithTag("pdf-selection-0").performTouchInput {
            val lineY = height * 0.03f
            down(Offset(width * 0.30f, lineY))
            advanceEventTime(700)
            moveTo(Offset(width * 0.50f, lineY))
            advanceEventTime(50)
            moveTo(Offset(width * 0.70f, lineY))
            advanceEventTime(50)
            up()
        }
        rule.waitForIdle()

        val selected = quote
        assertNotNull("long-press drag should produce a quote", selected)
        val text = selected!!.quote
        assertTrue("the quote should carry Chinese text: [$text]", text.any { it in '一'..'鿿' })
    }

    private fun findDocument(context: Context, name: String): Uri? {
        val tree = TREE.toUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        return runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1).equals(name, ignoreCase = true)) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    private companion object {
        const val TREE =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FListea%2Fpdftest"
    }
}
