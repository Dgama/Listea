package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.LIST_EXPORT_CHOICES
import com.example.listea.domain.decodeExportChoice
import com.example.listea.domain.listExportChoiceLabel
import com.example.listea.data.DissolutionReason
import com.example.listea.data.FolderDiff
import com.example.listea.data.ItemAction
import com.example.listea.data.ListDetail
import com.example.listea.data.ListEntity
import com.example.listea.data.ReviewItem
import com.example.listea.data.taskDecisions

@Composable
fun ListDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit,
    onReview: () -> Unit = {}
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    // Held out here, above every early return, rather than left to the LazyColumn to remember.
    // Opening an item replaces this whole page with the viewer, so a state created down inside
    // the list would leave the composition with it and the user would come back to the top of a
    // list they had scrolled a long way down.
    val itemsScroll = rememberLazyListState()
    // Which item is being looked at, if any. An id rather than the item itself, so the viewer
    // keeps following the same row as the list around it is re-read.
    var viewingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    // The viewer owns Back while it is up, and says so explicitly rather than relying on which
    // handler happened to be registered last.
    BackHandler(enabled = viewingItemId == null) { onBack() }

    // One check per page entry, plus one whenever the app returns to the foreground while this
    // page is the one on screen: another app (a file manager, FolderSync, ...) may have added or
    // deleted source files while we were away.
    //
    // Adding the observer dispatches ON_RESUME immediately to bring it up to the current state,
    // and that first dispatch is the entry check — so this single effect covers both triggers
    // without checking twice. Keyed on listId as well as the owner, which LifecycleEventEffect
    // cannot do, so switching lists re-registers and re-checks. Ordinary recomposition —
    // checkboxes, actions, database emissions — does neither. Placed before the loading return
    // so the check is tied to entering the page, not to data arriving.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, listId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.checkSourceFreshness(listId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val freshness by viewModel.sourceFreshness.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resolver by viewModel.templateResolver.collectAsStateWithLifecycle()
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    // The same two sources the Folder browser reads, under this list's own key, so "same filter
    // and sort everywhere" really does mean everywhere and a list is not a third place to set it.
    val arrangementKey = listArrangementKey(listId)
    val arrangement = resolveArrangement(arrangements, arrangementKey, settings.sharedFileArrangement)

    val current = detail
    if (current == null) {
        ListeaNestedScaffold(
            modifier = modifier,
            title = "",
            backLabel = "Lists",
            onBack = onBack
        ) { bodyModifier ->
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    // What the filter and the sort leave on screen. Re-derived whenever the items, the
    // arrangement or any checked state changes — all three arrive here as a new input rather than
    // as something to invalidate by hand, which is what makes checking an item off move it
    // straight into its new group.
    //
    // Deliberately not used for anything but display and the queue a Review walks. The counts
    // above, the progress bar and the completion this list reports all read `current` — see the
    // second rule at the top of ReviewArrange.kt.
    val shownItems = remember(current.items, arrangement) {
        arrangeFiles(current.items, arrangement, System.currentTimeMillis(), ::reviewFacts)
    }

    // Opened over the page rather than beside it: the viewer is the whole screen, and returning
    // from it has to land on the same list, scrolled where it was.
    viewingItemId?.let { openedId ->
        ListItemViewerScreen(
            modifier = modifier,
            // The items the page is showing, in the order it shows them, so paging through the
            // viewer and scrolling the page walk the same sequence. A filtered page pages its
            // subset: the viewer opened from a row that is on screen, and reaching one that is
            // not by swiping would be the filter failing to mean anything.
            items = shownItems,
            initialItemId = openedId,
            listTitle = current.list.title,
            settings = settings,
            resolver = resolver,
            onClose = { viewingItemId = null }
        )
        return
    }

    ListeaNestedScaffold(
        modifier = modifier,
        title = current.list.title,
        backLabel = "Lists",
        onBack = onBack,
        trailing = {
            Text(
                progressLabel(current.completedCount, current.items.size, current.isComplete),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.isComplete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    ) { bodyModifier ->
        // One scroll surface for the whole page. Expanding the webhook panel pushes the items
        // down instead of squeezing them into a fixed viewport, and the add-item row sits at the
        // end of what it adds to rather than permanently reserving the bottom of the screen.
        LazyColumn(
            state = itemsScroll,
            modifier = bodyModifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ListeaDimens.PagePadding,
                vertical = ListeaDimens.RowGap
            )
        ) {
            // 0. Why this List is shorter than the user remembers, when a Todo it held Tasks from
            // was deleted. Above the Source card because it explains an absence, and everything
            // under it explains a presence — a user who opened this page to find out where their
            // items went should not have to read past the folder first.
            current.list.lastDissolvedCount?.let { count ->
                item {
                    StatusLine(
                        dissolutionNote(count, current.list.lastDissolvedAt, DissolutionReason.fromKey(current.list.lastDissolvedReason)),
                        tone = StatusTone.Warning,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                }
            }

            // 1. Source — only folder-backed lists have one to reconcile against.
            val rootUri = current.list.sourceRootUri
            val relativePath = current.list.sourceRelativePath
            if (rootUri != null && relativePath != null) {
                item {
                    SourceCard(
                        rootUri = rootUri,
                        relativePath = relativePath,
                        missingCount = current.items.count { it.sourceMissing },
                        // Only this list's own verdict: another list's result is ignored outright.
                        freshness = freshness?.takeIf { it.listId == listId }?.state,
                        onResync = { viewModel.requestResync(listId) },
                        onClearMissing = { viewModel.requestMissingCleanup(listId) }
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                }
            }

            // 2. Progress — the count and the way into Review, and nothing else.
            item {
                ProgressCard(detail = current, onReview = onReview)
                Spacer(Modifier.height(ListeaDimens.RowGap))
            }

            // 3. Export — what this List does about exporting, which is its own question and its
            // own card. It used to share Progress's, and the two are different kinds of statement:
            // how far along the List is, versus where its decisions go when the round ends.
            item {
                ExportCard(
                    list = current.list,
                    onChoiceChange = { viewModel.setListExportKind(listId, it) },
                    onCompletionChange = { viewModel.setExportOnCompletion(listId, it) }
                )
                Spacer(Modifier.height(ListeaDimens.SectionGap))
            }

            // 4. Items — ListItems only. A folder-backed list holds its source files, however
            // deep they sit; the folders they came from are not items and never appear here.
            item {
                // The header keeps the list's whole item count behind it: the controls that
                // decide what is shown must not themselves disappear when the filter they set
                // happens to match nothing.
                ArrangeableSectionHeader(
                    title = "Items",
                    arrangement = arrangement,
                    onSort = { sortOpen = true },
                    onFilter = { filterOpen = true },
                    noun = "items"
                )
                if (!arrangement.isFilterDefault) {
                    FilterSummary(
                        shown = shownItems.size,
                        total = current.items.size,
                        noun = "item"
                    )
                }
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                HorizontalDivider()
            }
            if (shownItems.isEmpty()) {
                item {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        if (current.items.isEmpty()) "No items yet" else "No items match the filter",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(shownItems, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        settings = settings,
                        // Resolved here rather than inside the row, because [ReviewItem] carries the
                        // record and not the templates it is attached to: the row would have to be
                        // handed both the resolver and the item anyway, and a row that resolved for
                        // itself would be a second place the union rule was written down.
                        control = resolver.controlForTask(item.assetControls),
                        onToggle = { viewModel.setItemCompleted(item, it) },
                        onOpen = { viewingItemId = item.id },
                        onDelete = { item.rowId?.let { viewModel.deleteItem(listId, it) } }
                    )
                    HorizontalDivider()
                }
            }

            // Items are not added here any more. A List's items come from the folder it is over:
            // re-sync brings new files in, and the folder's own page is where that is driven from.
            // Typed entries are gone, and the ones an older database already holds are still shown
            // and still reviewable above — they simply cannot be added to.
        }
    }

    fun applyArrangement(updated: FileArrangement) {
        viewModel.setArrangement(arrangementKey, updated)
        sortOpen = false
        filterOpen = false
    }

    // Both groups are always offered here, unlike on the Folder screen where they follow the
    // review integration switch. A List has had checkboxes since before any of this existed, so
    // filtering and sorting on them can never be a control with invisible consequences.
    if (sortOpen) {
        FileSortDialog(
            arrangement = arrangement,
            showCheckedSort = true,
            onDismiss = { sortOpen = false },
            onApply = ::applyArrangement,
            noun = "items"
        )
    }
    if (filterOpen) {
        FileFilterDialog(
            arrangement = arrangement,
            showCheckedGroup = true,
            onDismiss = { filterOpen = false },
            onApply = ::applyArrangement,
            noun = "items"
        )
    }

    ResyncDialogs(viewModel)
}

@Composable
private fun ResyncDialogs(viewModel: ListsViewModel) {
    val request by viewModel.resyncRequest.collectAsStateWithLifecycle()

    when (val current = request) {
        null -> Unit

        is ResyncRequest.Scanning -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking folder") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
                    Text("Comparing this list with its source folder.")
                }
            },
            confirmButton = {}
        )

        is ResyncRequest.UpToDate -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("List is up to date") },
            text = { Text("Nothing has changed in the source folder.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )

        is ResyncRequest.Confirm -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Folder changes detected") },
            text = {
                val diff = current.diff
                Column {
                    Text(countLine(diff.addedPaths.size, "new file"))
                    Text(countLine(diff.missingPaths.size, "missing file"))
                    if (diff.restoredPaths.isNotEmpty()) {
                        Text(countLine(diff.restoredPaths.size, "restored file"))
                    }
                    Text("${diff.unchangedCount} unchanged")
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    diff.addedPaths.take(PREVIEW_PATHS).forEach { Text("+ $it") }
                    diff.missingPaths.take(PREVIEW_PATHS).forEach { Text("- $it") }
                    Text(
                        "Missing files stay in the list and keep their checked state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmResync() }) { Text("Update list") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Cancel") }
            }
        )

        is ResyncRequest.ImportConsent -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Import folder?") },
            text = {
                Column {
                    Text(
                        "Updating this list means importing a file that is not imported yet. " +
                            "Listea will register " + current.sourceRoot + " as " +
                            current.importer + " and create the Task for it."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(countLine(current.diff.addedPaths.size, "new file"))
                    Text(countLine(current.diff.missingPaths.size, "missing file"))
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Nothing is written until you import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmResyncImport() }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Not now") }
            }
        )

        is ResyncRequest.CleanupMissing -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Remove missing items?") },
            text = {
                Column {
                    Text(
                        countLine(current.missingCount, "item") +
                            if (current.missingCount == 1) {
                                " no longer exists in the source folder."
                            } else {
                                " no longer exist in the source folder."
                            }
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Keeping them preserves their checked state. Removing them cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRemoveMissingItems() }) {
                    Text("Remove ${countLine(current.missingCount, "item")}")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Keep items") }
            }
        )

        is ResyncRequest.Error -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Could not refresh") },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )
    }
}

private const val PREVIEW_PATHS = 5

private fun countLine(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/**
 * How far along the list is, and the one thing that acts on that: Review. The count speaks for
 * itself — no "in progress" restating what "12 / 20" already says.
 *
 * It carried the export controls until v0.5.6, at the bottom of the same card. They are now
 * [ExportCard], and the split is not cosmetic: this card says *how far along*, and the other says
 * *where the decisions go when the round ends*. Two questions, two cards.
 */
@Composable
private fun ProgressCard(
    detail: ListDetail,
    onReview: () -> Unit
) {
    SectionCard(title = "Progress") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Text(
                progressLabel(detail.completedCount, detail.items.size, detail.isComplete),
                style = MaterialTheme.typography.titleMedium,
                color = if (detail.isComplete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (detail.items.isNotEmpty()) {
                Button(onClick = onReview) { Text("Review", maxLines = 1) }
            }
        }
        // An empty list has nothing to be a fraction of, so it gets no bar rather than an
        // ambiguous empty one.
        if (detail.items.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { detail.completedCount.toFloat() / detail.items.size },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * What this List does about exporting, which is a statement about **this List** and never about
 * HTTP: the mechanism is one answer among several, and the next kind will not be a webhook at all.
 *
 * Three controls, and each answers one of the three questions the model has to answer here:
 *
 * - **Where** — the dropdown. *Same as the Todos* is the state a List is in until the user says
 *   otherwise, and it is what makes a List holding Tasks from several Todos export more than once,
 *   once per Todo's own answer. The options come from [LIST_EXPORT_CHOICES], so the set a user can
 *   pick from and the set the dispatcher can act on are one list.
 * - **When** — *Export when this list is completed*, which is the List's own round switch and the
 *   only thing on this card the dispatcher consults before splitting a round.
 * - **How it went** — *Last export*, one line, because a List whose exports are failing should say
 *   so here rather than only in a history three screens away.
 *
 * The URL field and the Test button that used to live here are gone, and deliberately: a List that
 * held its own URL was a copy of a setting that lives elsewhere, seeded once and never updated, so
 * changing the default reached no existing List. Every webhook now reads the app default, and the
 * Test button moved to where that default is configured.
 */
@Composable
private fun ExportCard(
    list: ListEntity,
    onChoiceChange: (ExportChoice) -> Unit,
    onCompletionChange: (Boolean) -> Unit
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val choice = decodeExportChoice(list.exportKind, inheritable = true)

    SectionCard(title = "Export") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = ListeaDimens.RowGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Text("Method", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusLine(listExportChoiceLabel(choice))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(listExportChoiceLabel(choice), maxLines = 1)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    LIST_EXPORT_CHOICES.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(listExportChoiceLabel(option)) },
                            onClick = {
                                menuOpen = false
                                onChoiceChange(option)
                            }
                        )
                    }
                }
            }
            Text(
                exportChoiceHelp(choice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ListeaDimens.CompactGap)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ListeaDimens.RowGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Export when this list is completed",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = list.exportOnCompletion,
                    onCheckedChange = onCompletionChange
                )
            }
            HorizontalDivider(Modifier.padding(vertical = ListeaDimens.CompactGap))
            StatusLine(exportLabel(list))
        }
    }
}

/** One sentence under the dropdown, saying what the answer it is showing actually does. */
private fun exportChoiceHelp(choice: ExportChoice): String = when (choice) {
    ExportChoice.Inherit ->
        "Each Todo this list holds decides for itself, so one round can export more than once."
    ExportChoice.Nothing -> "Nothing leaves this list, whatever its Todos are set to."
    is ExportChoice.By -> "Every export from this list goes by " + choice.kind.label.lowercase() + "."
}

/**
 * Where a folder-backed list came from, its source freshness, the manual update action, and —
 * whenever any source is missing — a standing cleanup action. The cleanup offer shown right
 * after an update can be dismissed, and a later update will report "up to date" because the items
 * are already flagged, so this button is the way back to it.
 *
 * Freshness is reported here and nowhere else: detecting changes never opens a dialog by itself.
 * The user is told, and decides when to act. An up-to-date source is three short lines, because
 * the ordinary case is the one that should cost the least room.
 */
@Composable
private fun SourceCard(
    rootUri: String,
    relativePath: String,
    missingCount: Int,
    freshness: SourceFreshness?,
    onResync: () -> Unit,
    onClearMissing: () -> Unit
) {
    val context = LocalContext.current
    val label by produceState(relativePath, rootUri, relativePath) {
        value = withContext(Dispatchers.IO) {
            sourceFolderLabel(context, rootUri.toUri(), relativePath)
        }
    }

    SectionCard(title = "Source") {
        InfoActionsRow(
            // Wider than the folder card's column: "Update from folder" is the widest label in
            // the app, and shortening it would make it say something else.
            sideBySideMinWidth = 340.dp,
            actionColumnWidth = 200.dp,
            info = {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            },
            actions = {
                // Unacknowledged changes promote the action to a filled button: hard to miss,
                // but still just a button. "Update", not "check": checking already happened on
                // entry.
                if (freshness is SourceFreshness.ChangesAvailable) {
                    Button(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                        Text("Update from folder", maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                        Text("Update from folder", maxLines = 1)
                    }
                }
                // A standing offer, not a warning: it is a button like its neighbour, tinted
                // because removing items is the one thing on this card that cannot be undone.
                if (missingCount > 0) {
                    OutlinedButton(
                        onClick = onClearMissing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear $missingCount missing", maxLines = 1)
                    }
                }
            }
        )
        // Full width below both columns: the change summary names everything that actually
        // changed, and is the one line here that must not be squeezed into half a card.
        freshness?.let { FreshnessLine(it) }
    }
}

/** Compact, inline, never modal: the page stays usable while a scan is running. */
@Composable
private fun FreshnessLine(state: SourceFreshness) {
    val text = when (state) {
        SourceFreshness.Checking -> "Checking source…"
        SourceFreshness.NotChecked -> "Not checked — automatic checking is off"
        SourceFreshness.UpToDate -> "Up to date"
        SourceFreshness.SourceUnavailable -> "Source unavailable"
        is SourceFreshness.ChangesAvailable -> "Changes detected · " + changesSummary(state.diff)
    }
    val tone = when (state) {
        SourceFreshness.Checking, SourceFreshness.NotChecked -> StatusTone.Neutral
        SourceFreshness.UpToDate -> StatusTone.Positive
        else -> StatusTone.Warning
    }
    val icon = when (state) {
        SourceFreshness.Checking -> null
        SourceFreshness.NotChecked -> Icons.AutoMirrored.Filled.HelpOutline
        SourceFreshness.UpToDate -> Icons.Filled.CheckCircle
        SourceFreshness.SourceUnavailable -> Icons.Filled.ErrorOutline
        is SourceFreshness.ChangesAvailable -> Icons.Filled.Warning
    }
    // Two lines, because the change summary names everything that actually changed and must not
    // be cut off at the point where it stops being actionable.
    StatusLine(text, tone = tone, icon = icon, maxLines = 2)
}

/**
 * "3 new files · 1 missing file", naming only what actually changed.
 *
 * Counts newly missing files rather than every missing path: files already acknowledged as
 * missing are not news, and reporting them here would contradict the up-to-date verdict a list
 * holding them is entitled to.
 */
private fun changesSummary(diff: FolderDiff): String = buildList {
    if (diff.addedPaths.isNotEmpty()) add(countLine(diff.addedPaths.size, "new file"))
    if (diff.newlyMissingCount > 0) add(countLine(diff.newlyMissingCount, "missing file"))
    if (diff.restoredPaths.isNotEmpty()) add(countLine(diff.restoredPaths.size, "restored file"))
}.joinToString(" · ")

/**
 * One item: check it, look at it, or remove it. Opening it is a tap on the text.
 *
 * The tap used to open a rename box and now opens the item itself — see [ListItemViewerScreen]
 * for why renaming a folder-backed item was never really an edit. The tappable area is the text
 * column alone, so the checkbox and the delete button keep their own meanings and a tap aimed at
 * either cannot fall through to opening the viewer.
 *
 * The tags are read-only here — actions are set in Review — and carry whatever names the Task's own
 * control currently gives them, which is why [control] is passed in rather than the labels.
 *
 * This row and the review surface's badge are two renderings of one answer and read it from one
 * place: [ItemActionTags] takes the names, the tints and the order from the resolved control, so a
 * rename relabels both and a starred item is starred wherever it is looked at. It is resolved over
 * every kind the Task is configured for, because a list row is drawn beside a Task's title with no
 * one Asset on screen — see [ItemActionTags].
 */
@Composable
private fun ItemRow(
    item: ReviewItem,
    settings: AppSettings,
    control: ResolvedAssetControl,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = ListeaDimens.RowGap),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration =
                    if (item.isCompleted) TextDecoration.LineThrough else null,
                color = if (item.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Folder-backed items keep their path so bilibili/a.jpg stays distinct from
            // danbooru/a.jpg.
            item.relativePath?.takeIf { it != item.title }?.let { path ->
                StatusLine(path)
            }
            ItemActionTags(item.taskDecisions, control)
            // Subtle: the item stays checkable and is never hidden or removed.
            if (item.sourceMissing) {
                StatusLine(
                    "Source missing",
                    tone = StatusTone.Warning,
                    icon = Icons.Filled.ErrorOutline
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete item",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
    }
}

