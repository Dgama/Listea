package com.example.listea

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nucleusframework.pdfium.PdfReaderState
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPIKE probe — what the text layer of the real PDFs on this phone actually contains.
 *
 * Opens every file of the `pdftest` Upstream through the app's persisted folder grant and logs,
 * per page: extracted-text length, a sample, and how many character boxes carry a real codepoint
 * (zero-codepoint glyphs are what make a page unselectable). No assertions on content: this is a
 * diagnosis, not a product test. Skips when the folder or the grant is absent.
 */
@RunWith(AndroidJUnit4::class)
class PdfTextProbeTest {

    private val tree =
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FListea%2Fpdftest".toUri()

    @Test
    fun logsTextLayerOfRealDocuments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val documents = listPdfs(context)
        assumeTrue("pdftest Upstream is not available or not granted", documents.isNotEmpty())

        for ((name, uri) in documents) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                Log.d(TAG, "$name: unreadable")
                continue
            }
            val state = newReaderState()
            try {
                state.open(bytes)
                val error = state.error
                if (error != null) {
                    Log.d(TAG, "$name: open failed: $error")
                    continue
                }
                Log.d(TAG, "$name: pages=${state.pageCount} bytes=${bytes.size}")
                for (page in 0 until minOf(state.pageCount, 3)) {
                    val text = state.pageText(page)
                    val layout = state.pageTextLayout(page)
                    var coded = 0
                    var boxes = 0
                    if (layout != null) {
                        for (i in 0 until layout.charCount) {
                            if (layout.codepoint(i) > 0) coded++
                            if (layout.charRight(i) > layout.charLeft(i)) boxes++
                        }
                    }
                    val sample = text.replace('\n', ' ').take(80)
                    Log.d(
                        TAG,
                        "  p$page text=${text.length} chars=${layout?.charCount ?: -1} " +
                            "coded=$coded boxes=$boxes sample=[$sample]"
                    )
                }
            } finally {
                state.dispose()
            }
        }
    }

    /** Name/URI of each PDF in the folder, directory order. */
    private fun listPdfs(context: Context): List<Pair<String, Uri>> {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val result = ArrayList<Pair<String, Uri>>()
        runCatching {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                        result += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }.onFailure { Log.d(TAG, "folder query failed: $it") }
        return result
    }

    /**
     * `PdfReaderState`'s constructor is Kotlin-internal, which is a compile-time marker only;
     * the JVM constructor is reachable, which lets the probe run outside a composition.
     */
    private fun newReaderState(): PdfReaderState {
        val type = java.lang.Long.TYPE
        val constructor = PdfReaderState::class.java.getDeclaredConstructor(type, type)
        constructor.isAccessible = true
        return constructor.newInstance(PdfReaderState.DEFAULT_CACHE_BYTES, 12L * 1024 * 1024)
    }

    private companion object {
        const val TAG = "PdfProbe"
    }
}
