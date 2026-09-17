package com.example.listea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.listea.data.DissolutionReason
import com.example.listea.data.ListSummary
import com.example.listea.data.SourceSummary

/**
 * The Lists destination and the two screens nested under it.
 *
 * Which nested screen is open is owned by the shell — it decides whether the bottom navigation
 * belongs on screen — but the switching itself stays here, next to the screens it switches
 * between.
 */
@Composable
fun ListsScreen(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = viewModel(),
    openListId: Long? = null,
    onOpenListChange: (Long?) -> Unit = {},
    reviewListId: Long? = null,
    onReviewListChange: (Long?) -> Unit = {},
    /**
     * Starting a List from here, given the Todo its Tasks should come from.
     *
     * The index's own way in, and it has to be the index's: the Tasks are browsed in the Todo tab,
     * which this screen has no way to reach. Null where the shell is not offering one, so the
     * screen can still be previewed without it.
     */
    onBuildList: ((SourceSummary) -> Unit)? = null
) {
    when {
        reviewListId != null -> ReviewScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = reviewListId,
            onBack = { onReviewListChange(null) }
        )

        openListId == null -> ListsIndex(
            modifier = modifier,
            viewModel = viewModel,
            onOpenList = { onOpenListChange(it) },
            onBuildList = onBuildList
        )

        else -> ListDetailScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = openListId,
            onBack = { onOpenListChange(null) },
            onReview = { onReviewListChange(openListId) }
        )
    }
}

@Composable
private fun ListsIndex(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onOpenList: (Long) -> Unit,
    onBuildList: ((SourceSummary) -> Unit)?
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val sources by viewModel.sourceSummaries.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ListSummary?>(null) }
    var deleting by remember { mutableStateOf<ListSummary?>(null) }
    var pickingSource by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ListFilter.All) }

    val visible = remember(summaries, filter) { summaries.filter(filter::accepts) }

    // The screen title lives in the shell's app bar now; this group is the filters, and a way to
    // start a list. It is not a List over a folder any more — a List is an order over Tasks, and
    // which folder those Tasks came from is a fact about them rather than about it. So making one
    // is picking a Todo to take Tasks from and then picking the Tasks, which is why this button
    // asks a question and hands the rest to the Todo tab rather than opening a form here.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ListeaDimens.PagePadding)
    ) {
        ActionGroup(Modifier.padding(vertical = ListeaDimens.RowGap)) {
            ListFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label, maxLines = 1) }
                )
            }
            if (onBuildList != null) {
                TextButton(onClick = { pickingSource = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.height(ListeaDimens.IconSize)
                    )
                    Spacer(Modifier.width(ListeaDimens.RowGap))
                    Text("New list", maxLines = 1)
                }
            }
        }
        HorizontalDivider()

        if (visible.isEmpty()) {
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            Text(
                if (summaries.isEmpty()) "No lists yet" else "No lists in this filter",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = ListeaDimens.RowGap),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
            ) {
                items(visible, key = { it.id }) { summary ->
                    ListCard(
                        summary = summary,
                        onOpen = { onOpenList(summary.id) },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary }
                    )
                }
            }
        }
    }

    renaming?.let { target ->
        TextPromptDialog(
            title = "Rename list",
            label = "List name",
            initialText = target.title,
            confirmLabel = "Rename",
            onConfirm = { viewModel.renameList(target.id, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }

    if (pickingSource && onBuildList != null) {
        SourcePickerDialog(
            sources = sources,
            onPick = { pickingSource = false; onBuildList(it) },
            onDismiss = { pickingSource = false }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete list") },
            text = { Text("Delete \"${target.title}\" and its ${target.totalItems} items?") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteList(target.id); deleting = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Which Todo the new List takes its Tasks from.
 *
 * One Todo per List, at the moment of starting. A List may hold Tasks from any number of Todos —
 * that is the whole point of a List being an order over Tasks — but a selection has to be made
 * somewhere, and the somewhere is a Todo's catalog. Starting from two at once would need a screen
 * that browses every Todo together, and the Tasks of one Todo are not reachable from another's.
 *
 * So this is the first half of a two-step gesture, not a constraint on the result: build from this
 * Todo, then add the other Todo's Tasks to the same List from that Todo's own catalog.
 */
@Composable
private fun SourcePickerDialog(
    sources: List<SourceSummary>,
    onPick: (SourceSummary) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take Tasks from which Todo?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
                if (sources.isEmpty()) {
                    Text(
                        "There are no Todos yet. A Todo is made by running Create Tasks on a " +
                            "folder, in the Upstreams tab."
                    )
                } else {
                    Text(
                        "You pick the Tasks on the next screen. Tasks from any other Todo can be " +
                            "added to the same list afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sources.forEach { source ->
                        OutlinedCard(
                            onClick = { onPick(source) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(ListeaDimens.CardCorner)
                        ) {
                            Column(Modifier.padding(ListeaDimens.RowGap)) {
                                Text(
                                    source.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    countLabel(source.taskCount, "Task"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One list, as little as the index needs to say about it: what it is called, how far along it is,
 * and a way to rename or delete it.
 *
 * Nothing here reports source freshness or offers Review — an index is for choosing which list to
 * work on, and both of those belong to the list you have chosen. The whole card is the way in.
 *
 * Every list reports its webhook, including one that has none configured. "Off" is a real answer
 * to "will finishing this send anything?", and leaving the line out entirely made the index look
 * as though it had failed to load rather than as though the answer were no.
 */
@Composable
private fun ListCard(
    summary: ListSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ListeaDimens.CardPadding,
                    top = ListeaDimens.RowGap,
                    bottom = ListeaDimens.RowGap
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLine(
                    progressLabel(summary.completedItems, summary.totalItems, summary.isComplete),
                    tone = if (summary.isComplete) StatusTone.Positive else StatusTone.Neutral
                )
                StatusLine(
                    webhookStatusLabel(
                        enabled = summary.webhookEnabled,
                        lastDeliveryStatus = summary.lastDeliveryStatus,
                        lastDeliveryCode = summary.lastDeliveryCode
                    )
                )
                // Only when there is something to explain. A List that lost rows is otherwise
                // indistinguishable from one that never had them, and this line is the whole of
                // what the app knows about why it is shorter than the user remembers.
                summary.lastDissolvedCount?.let { count ->
                    StatusLine(
                        dissolutionNote(count, summary.lastDissolvedAt, DissolutionReason.fromKey(summary.lastDissolvedReason)),
                        tone = StatusTone.Warning
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/**
 * Completion as a filter, which is the only place the words belong: the rows themselves just
 * carry numbers.
 */
enum class ListFilter(val label: String) {
    All("All"),
    InProgress("In progress"),
    Completed("Completed");

    fun accepts(summary: ListSummary): Boolean = when (this) {
        All -> true
        InProgress -> !summary.isComplete
        Completed -> summary.isComplete
    }
}

/**
 * "3 / 5", with a tick once the whole thing is done.
 *
 * Just the numbers: "complete" after every count reads as a status when it is only ever a unit,
 * and completion already has its own marker and its own filter.
 */
fun progressLabel(completed: Int, total: Int, isComplete: Boolean): String =
    "$completed / $total" + if (isComplete) " ✓" else ""

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initialText: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) })
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Why a List is shorter than the user remembers, in one line.
 *
 * Shared by the index card and the detail screen so the two can never disagree about the same
 * event. [at] is nullable only because the two columns are written together and read together;
 * a note without a time is not a state this app produces.
 *
 * Two operations remove memberships and they are not the same thing to a user: a Todo they deleted
 * themselves, and a Task a Todo replaced because its content moved. The sentence says which, from
 * [DissolutionReason] — a note that named the wrong one would be worse than no note, because a user
 * who never deleted anything would go looking for the delete.
 */
internal fun dissolutionNote(count: Int, at: Long?, reason: DissolutionReason): String {
    val cause = when (reason) {
        DissolutionReason.Dissolved -> "when a Todo was deleted"
        DissolutionReason.Dropped -> "when their content moved"
    }
    return countLabel(count, "item") + " removed " + cause +
        (at?.let { " · " + formatTimestamp(it) } ?: "")
}
