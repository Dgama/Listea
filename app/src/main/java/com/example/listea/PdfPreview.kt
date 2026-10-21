package com.example.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.listea.domain.AssetMark
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import dev.nucleusframework.pdfium.rememberPdfReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A PDF in the viewer: its pages, scrolling vertically, with selection and marks on every page that
 * has text.
 *
 * **Browsing records nothing.** This is the same rule the image and video viewers follow, and it is
 * what lets a PDF be opened from the Folder browser without a Todo, a template or an export in
 * sight. A selection becomes a mark only when a template is in force and a tool is chosen — the
 * seam is [onCreateMark], which is null on the read-only surfaces and empty of tools when nothing
 * governs the file.
 *
 * **The document is read through [PdfDocumentSession].** The only renderer-specific lines are in
 * [PdfSession.kt]; this file loads bytes, opens them, and draws what the session exposes. A renderer
 * that exposes no text layer (a scanned document) still renders its pages — there is simply nothing
 * to select on them.
 *
 * **The size limit and the fingerprint happen before a page exists.** [readPdf] reads at most
 * [PdfSizeLimitBytes], hashes what it read, and maps every failure to one sentence the placeholder
 * can show. Reading on `Dispatchers.IO` keeps a large file off the main thread; opening happens
 * back on composition's thread, where the renderer's state is used.
 *
 * **Stored marks are resolved once per open.** [resolveMarks] turns each visible mark's anchor into
 * page geometry; a mark it had to move is handed back through [onRelocateMark] so the correction is
 * persisted and the next open takes the fast path. A mark it could not place is never drawn over
 * text it does not quote, and the viewer says nothing about it on its own: the resolution is
 * reported through [onMarksResolved], and the file's Info sheet lists the marks — placed and
 * missing alike — when the reviewer asks.
 */
@Composable
internal fun PdfPreview(
    uri: String,
    name: String,
    modifier: Modifier,
    zoom: MediaZoomState?,
    tools: List<TemplateButton> = emptyList(),
    marks: List<AssetMark> = emptyList(),
    onCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? = null,
    onEditMark: ((String, String) -> Unit)? = null,
    onDeleteMark: ((String) -> Unit)? = null,
    onRelocateMark: ((String, QuoteAnchor) -> Unit)? = null,
    onMarksResolved: ((List<PdfMarkInfo>) -> Unit)? = null,
    onQuote: ((PdfQuote) -> Unit)? = null
) {
    val context = LocalContext.current
    val state = rememberPdfReaderState()
    var session by remember(uri) { mutableStateOf<PdfDocumentSession?>(null) }
    var outcome by remember(uri) { mutableStateOf<PdfOutcome>(PdfOutcome.Loading) }

    LaunchedEffect(uri, state) {
        // The page list fills the card and has no single shape to report; pan limits come from the
        // viewport, as they do for a placeholder.
        zoom?.contentAspect = null
        session = null
        outcome = PdfOutcome.Loading

        when (val loaded = withContext(Dispatchers.IO) { readPdf(context, uri) }) {
            is PdfLoadResult.Failed -> outcome = PdfOutcome.Failed(loaded.failure.headline)

            is PdfLoadResult.Loaded -> {
                state.open(loaded.pdf.bytes)
                val failure = pdfFailureOf(state.error)
                if (failure != null) {
                    outcome = PdfOutcome.Failed(failure.headline)
                } else {
                    session = NucleusPdfDocumentSession(state, loaded.pdf.fingerprint)
                    outcome = PdfOutcome.Ready
                }
            }
        }
    }

    when (val current = outcome) {
        PdfOutcome.Loading ->
            Box(modifier.mediaZoom(zoom).background(MaterialTheme.colorScheme.surfaceVariant))

        is PdfOutcome.Failed ->
            PreviewPlaceholder(modifier.mediaZoom(zoom), current.headline, name)

        PdfOutcome.Ready -> {
            val open = session
            if (open == null) {
                Box(modifier.mediaZoom(zoom).background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                PdfPages(
                    session = open,
                    modifier = modifier,
                    zoom = zoom,
                    tools = tools,
                    marks = marks,
                    onCreateMark = onCreateMark,
                    onEditMark = onEditMark,
                    onDeleteMark = onDeleteMark,
                    onRelocateMark = onRelocateMark,
                    onMarksResolved = onMarksResolved,
                    onQuote = onQuote
                )
            }
        }
    }
}

/**
 * A page list that scrolls vertically, one sheet per page, each with its marks and a selection
 * layer.
 *
 * [resolveMarks] is called with the whole document once, rather than per page: a quote may have
 * moved to another page, and only a search over every page can say so. The result is then handed
 * down by page index, so each page draws only its own marks and the layers do not have to know how
 * to skip.
 */
@Composable
private fun PdfPages(
    session: PdfDocumentSession,
    modifier: Modifier,
    zoom: MediaZoomState?,
    tools: List<TemplateButton>,
    marks: List<AssetMark>,
    onCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)?,
    onEditMark: ((String, String) -> Unit)?,
    onDeleteMark: ((String) -> Unit)?,
    onRelocateMark: ((String, QuoteAnchor) -> Unit)?,
    onMarksResolved: ((List<PdfMarkInfo>) -> Unit)?,
    onQuote: ((PdfQuote) -> Unit)?
) {
    val selectableTools = remember(tools) { tools.pdfTools() }
    var resolved by remember(session) { mutableStateOf<List<ResolvedMark>>(emptyList()) }
    // The ids of the marks the sheet is showing, not the resolutions themselves: an edit or a
    // template change arrives through the Flow, and the sheet re-reads the live list below rather
    // than painting the snapshot it was opened with.
    var sheetIds by remember(session) { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(session, selectableTools, marks) {
        val next = if (marks.isEmpty() || selectableTools.isEmpty()) {
            emptyList()
        } else {
            resolveMarks(marks, session, selectableTools)
        }
        resolved = next
        next.forEach { resolvedMark ->
            resolvedMark.relocated?.let { anchor ->
                onRelocateMark?.invoke(resolvedMark.mark.id, anchor)
            }
        }
    }

    val sheetMarks = sheetIds?.let { ids ->
        resolved.filter { it.mark.id in ids }
    }.orEmpty()
    // A mark deleted here, or hidden by a template edit, leaves the sheet rather than staying as a
    // row about something that no longer exists.
    LaunchedEffect(sheetIds, resolved) {
        if (sheetIds != null && sheetMarks.isEmpty()) sheetIds = null
    }

    // Reported after resolution rather than during composition, and only when it changes — the
    // callback is captured as the latest so a recomposition of the caller does not re-run the
    // search. An empty result is reported too, because a template edit can remove every visible
    // mark and the sheet must stop listing them.
    val reportMarks by rememberUpdatedState(onMarksResolved)
    LaunchedEffect(resolved) {
        reportMarks?.invoke(resolved.map { it.info() })
    }

    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().mediaZoom(zoom),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(session.pageCount) { index ->
                PdfPageWithSelection(
                    session = session,
                    index = index,
                    tools = selectableTools,
                    marks = resolved.filter { it.pageIndex == index },
                    onCreateMark = onCreateMark,
                    onMarkTap = { tapped ->
                        sheetIds = tapped.mapTo(mutableSetOf()) { it.mark.id }
                    },
                    onQuote = onQuote
                )
            }
        }
    }

    if (sheetIds != null) {
        PdfMarkSheet(
            marks = sheetMarks,
            onDismiss = { sheetIds = null },
            onEdit = onEditMark,
            onDelete = onDeleteMark ?: {}
        )
    }
}

/**
 * One page: the rendered sheet, its highlights, and a selection layer of our own once the page's
 * text has been read. A page with no text layer — a scan — simply gets neither layer, and still
 * scrolls and zooms.
 */
@Composable
private fun PdfPageWithSelection(
    session: PdfDocumentSession,
    index: Int,
    tools: List<TemplateButton>,
    marks: List<ResolvedMark>,
    onCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)?,
    onMarkTap: (List<ResolvedMark>) -> Unit,
    onQuote: ((PdfQuote) -> Unit)?
) {
    var pageText by remember(session, index) { mutableStateOf<PdfPageText?>(null) }
    LaunchedEffect(session, index) {
        pageText = session.pageText(index)
    }

    // The tag is what lets a test say "this page reached the screen" independently of whether it
    // has text: a scan has no selection layer to appear, and waiting for one would never finish.
    Box(Modifier.fillMaxWidth().testTag("pdf-page-$index")) {
        PdfPageCanvas(
            session = session,
            pageIndex = index,
            modifier = Modifier.fillMaxWidth()
        )
        val text = pageText
        if (text != null && !text.isEmpty) {
            val order = remember(text) { PdfReadingOrder.of(text) }
            if (order.size > 0) {
                // Under the selection, so a drag over a highlighted passage still looks like a
                // selection rather than like two competing highlights.
                if (marks.isNotEmpty()) {
                    PdfMarkHighlights(
                        marks = marks,
                        page = text,
                        modifier = Modifier.matchParentSize()
                    )
                }
                TouchTextSelection(
                    page = text,
                    order = order,
                    modifier = Modifier.matchParentSize(),
                    tools = tools,
                    marks = marks,
                    fingerprint = session.fingerprint,
                    onQuote = onQuote,
                    onCreateMark = onCreateMark,
                    onMarkTap = onMarkTap
                )
            }
        }
    }
}

private sealed interface PdfOutcome {
    data object Loading : PdfOutcome
    data object Ready : PdfOutcome
    data class Failed(val headline: String) : PdfOutcome
}
