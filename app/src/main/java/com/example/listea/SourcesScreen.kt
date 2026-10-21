package com.example.listea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.DissolutionReport
import com.example.listea.data.ListLosingItems
import com.example.listea.data.SourceSummary
import com.example.listea.domain.ExportKind

/**
 * The **Todo** tab: one row per Todo, each with the name it was given and the state of its last run.
 *
 * A Todo is what a Create Tasks run wrote — the Tasks an Upstream's folder was read into. It is the
 * app's unit of work rather than of material, which is why the organising things live here and
 * nothing lives on the Upstreams tab: a round, a List and a selection are all about Tasks, and a
 * folder has none until an import has run over it.
 *
 * The row is the way in. Tapping it opens the Todo, which is where its Tasks are actually looked at;
 * the row itself shows what the Todo *is* and offers the two things that are about the Todo as a
 * whole rather than about anything inside it — re-reading it, and ending it. Catalog was a button to the place the row itself now goes,
 * Quick Review was a round over a scope that may hold only folders, and Create List was a List built
 * from Tasks nobody had looked at yet — all three were the row pretending to be the screen behind
 * it, and all three are gone. Refresh Import stays a button, because re-running the import that
 * produced this Todo is a decision about the Todo and not a place to go.
 *
 * The name is the user's own, set when Create Tasks ran, rather than the folder's: this row is what
 * they will come back to, and it is the same name the Todo's own screen opens under.
 *
 * Unavailable is a state, not a deletion (§14.4): a Todo whose Upstream folder is no longer under a
 * grant the app holds cannot be refreshed or browsed, and says so on the row rather than
 * disappearing. Its counts are still true — those Tasks exist, and a List built from them still
 * works.
 *
 * The name is `SourcesScreen` and the label is Todo for the reason `docs/iterations/v0.3.0.md`
 * gives: the model keeps the word Source for the row a run writes, and only the tab moves.
 */
@Composable
fun SourcesScreen(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel,
    /** The roots whose SAF grant the app still holds, so a row can say when one is gone. */
    heldRoots: Set<String>,
    onOpenTodo: (SourceSummary) -> Unit
) {
    val sources by viewModel.sourceSummaries.collectAsStateWithLifecycle()
    // Read for the template line on a card, which names what the Tasks' record attaches and so
    // cannot be answered from the row alone.
    val resolver by viewModel.templateResolver.collectAsStateWithLifecycle()
    // The live default, for the address dialog's *Use default* radio. Read here rather than in the
    // dialog so the dialog is given one value to draw and takes no subscription of its own.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // The Todo whose address is being edited, held by value: the dialog closes when the card it was
    // opened from leaves the list, which is what deleting that Todo should do anyway.
    var editingAddress by remember { mutableStateOf<SourceSummary?>(null) }

    if (sources.isEmpty()) {
        Column(modifier.fillMaxSize().padding(ListeaDimens.PagePadding)) {
            Text("Nothing to do yet", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Text(
                "Add an Upstream in Settings, open it, and run Create Tasks on a folder inside " +
                    "it. The Tasks that produces appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        items(sources, key = { it.id }) { source ->
            TodoCard(
                source = source,
                resolver = resolver,
                available = source.rootUri in heldRoots,
                onOpen = { onOpenTodo(source) },
                onRefresh = { viewModel.requestSourceRefresh(source.id) },
                onDelete = { viewModel.requestDeleteTodo(source.id) },
                onFollowUpstream = { viewModel.setTodoFollowUpstream(source.id, it) },
                onKeepStateOnDelete = { viewModel.setTodoKeepStateOnDelete(source.id, it) },
                onEditAddress = { editingAddress = source }
            )
        }
    }

    editingAddress?.let { source ->
        TodoWebhookAddressDialog(
            source = source,
            defaultWebhookUrl = settings.defaultWebhookUrl,
            onDismiss = { editingAddress = null },
            onSave = { url, fromDefault ->
                viewModel.setTodoWebhookAddress(source.id, url, fromDefault)
                editingAddress = null
            }
        )
    }
}

@Composable
private fun TodoCard(
    source: SourceSummary,
    resolver: TemplateResolver,
    available: Boolean,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onFollowUpstream: (Boolean) -> Unit,
    onKeepStateOnDelete: (Boolean) -> Unit,
    onEditAddress: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // The whole card opens the Todo and the one button acts on it, which is the same split the
    // Upstreams card makes: looking at what a row holds is the row, and the controls left on it are
    // the things that are about the row rather than about its contents.
    OutlinedCard(onClick = onOpen, enabled = available, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ListeaDimens.CardPadding)) {
            // The name the Create Tasks dialog was given, not the folder's own. One folder can
            // carry any number of Todos — under different importers, or under the same one twice —
            // and two rows both reading "2026-09-11" would be indistinguishable exactly when
            // telling them apart matters.
            Text(
                source.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2
            )
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Text(
                todoDetail(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                sourceStatusLabel(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The third of the standing answers this card holds, and the only one it does not offer
            // as a switch: the tier is chosen where its three consequences are spelled out, in the
            // run's own dialog, so the card's job is to say which one is in force rather than to
            // change it a word at a time. Shown in the dialog's words, and under the dialog's own
            // heading, because the row and the question are the same question.
            Text(
                todoContentPolicyLabel(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Beside the tier, because the two are the Todo's standing answers and read as a pair:
            // what a change to the material means, and what the material is judged with.
            Text(
                todoAssetControlLabel(source, resolver),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!available) {
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    "Listea no longer has access to the Upstream this came from. Nothing here is " +
                        "deleted; refreshing and browsing it need that folder added again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(ListeaDimens.RowGap))
            ActionGroup {
                TodoAction(
                    label = "Refresh Import",
                    icon = Icons.Filled.Refresh,
                    enabled = available,
                    onClick = onRefresh
                )
                // Behind an overflow rather than beside Refresh: the two are a word apart on the
                // row and a whole backup apart in consequence, and a mis-tap between them is the
                // one this card must not afford. Deleting stays reachable without a long-press,
                // which nothing else on this screen teaches.
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More actions for ${source.displayName}"
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // The two policies a Todo holds, above the one action that destroys it. Each
                        // row carries its consequence as well as its name, because neither switch
                        // shows one when it is thrown: following the upstream writes on a later day
                        // without being asked, and keeping decisions on delete only matters at a
                        // delete that has not happened yet. A label alone would be a control whose
                        // effect the user has to remember having read somewhere else.
                        TodoPolicyItem(
                            label = "Follow the upstream",
                            detail = "opening this Todo syncs new material in on its own",
                            checked = source.followUpstream,
                            onToggle = onFollowUpstream
                        )
                        TodoPolicyItem(
                            label = "Keep decisions when deleted",
                            detail = "a later Todo at this folder can load them back",
                            checked = source.keepStateOnDelete,
                            onToggle = onKeepStateOnDelete
                        )
                        // A menu item rather than a card line: the address matters at the moment an
                        // export leaves, and adding a fourth grey line to every card would make the
                        // row longer for something most Todos never look at. The menu names the host
                        // so the answer is still readable without opening the dialog.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Webhook address · " +
                                        (webhookHost(source.webhookUrl) ?: "not set")
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onEditAddress()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Todo…") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One of a Todo's own policies, as a menu row that is a switch.
 *
 * The switch does not take the tap itself — the row does, through `onClick` — because a two-line
 * `DropdownMenuItem` is what the user sees and the words are as much the target as the thumb is.
 * Tapping it closes nothing: a policy is set and the menu stays where it was, so both can be read
 * and changed in one visit rather than one visit each.
 */
@Composable
private fun TodoPolicyItem(
    label: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingIcon = {
            Switch(checked = checked, onCheckedChange = { onToggle(it) })
        },
        onClick = { onToggle(!checked) }
    )
}

/**
 * What is in a Todo, in one line: how it was built, and how much came out of it.
 *
 * The importer is named because it is the difference between a Todo that mirrors the folder tree
 * and one that is a single flat run of Tasks, and nothing else on the row says which. The count is
 * of Tasks and not of files: a folder-as-task Todo holds one Task per folder and a file-as-task one
 * holds one per file, so the number means different things under the two and is only readable
 * beside the word that says which.
 */
internal fun todoDetail(source: SourceSummary): String = buildString {
    append(
        when (source.importerKind) {
            ImporterKind.FOLDER_AS_TASK.key -> "Folder as Task"
            else -> "File as Task"
        }
    )
    if (source.importerKind == ImporterKind.FILE_AS_TASK.key && source.keepTree) {
        append(" · keeps folders")
    }
    if (source.relativePath.isNotEmpty()) append(" · in ").append(source.relativePath)
    append(" · ").append(countLabel(source.taskCount, "Task"))
}

/**
 * What this Todo does when one of its Tasks' content changes, said as the dialog that asked.
 *
 * The heading is [ContentPolicyChoice]'s own, so a user who answered that question recognises the
 * answer here without having to learn a second name for it, and the tier is named by its [label]
 * rather than by the stored key: `reset` is not a word anybody chose.
 */
internal fun todoContentPolicyLabel(source: SourceSummary): String =
    "When a Task's content changes: " + ContentPolicy.fromKey(source.contentPolicy).label

/**
 * What a Todo's Tasks are judged with: the templates they were stamped with, by name.
 *
 * **A list of templates now, where it used to be a tag list per file kind**, and the change is the
 * one v0.5.4 makes everywhere. It used to spell out each kind's tags — *Image asset control: C1, C2*
 * — reading them off the app-wide configuration through the record, because that configuration was
 * what a kind with nothing recorded would resolve to. Nothing resolves to a default any more: a
 * Task's marks are named by the templates its own record attaches, so the card names those.
 *
 * **It reports the Tasks' attachment, not the Todo's** — [SourceSummary.taskAssetControls], never
 * [SourceSummary.assetControls]. Those are two different facts and the difference is the point: a
 * run stamps the Tasks it touches and leaves every Task it does not alone, so a card reporting the
 * Todo's own would answer *what will the next run use* when the user is asking *what am I about to
 * open*. Editing a template's buttons moves both at once — a Task holds a reference — but changing
 * which templates are attached moves only the Todo, and the drift is legible only here.
 *
 * One button count per template rather than the names of its buttons: a card that listed every
 * button of every attached template would be longer than the rest of the row put together, and
 * *three buttons* is the fact a user checks it against. The names are one tap away, on the
 * template's own page.
 *
 * A Todo with nothing recorded says so rather than showing nothing: an empty row is
 * indistinguishable from a card that failed to render it, and *never chosen* is a real answer about
 * a Todo whose next run has not happened yet. So is *this build cannot read it* — an attachment
 * written by a later release is not a missing one, and printing its id at a user would be the app
 * showing them the storage format.
 */
internal fun todoAssetControlLabel(source: SourceSummary, resolver: TemplateResolver): String {
    if (source.taskAssetControls.isEmpty()) return "Templates: not chosen yet"

    return decodeTemplateAttachments(source.taskAssetControls).joinToString(" · ") { ref ->
        val version = resolver.versionOf(ref)
        if (version == null) {
            "A template this build cannot read"
        } else {
            version.name + " (" + countLabel(version.buttons.size, "button") + ")"
        }
    }
}

/**
 * What a Todo's last run did, or that it has never run.
 *
 * "never run" is its own answer rather than "0 Tasks": a Todo whose import failed and one that has
 * not run yet are the same row with different stories, and the count already says the Tasks either
 * way.
 */
internal fun sourceStatusLabel(source: SourceSummary): String = when (source.lastImportAt) {
    null -> "never run"
    else -> "last run " + (source.lastImportStatus ?: "unknown")
}

@Composable
private fun TodoAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(ListeaDimens.IconSize))
        Spacer(Modifier.width(ListeaDimens.CompactGap))
        Text(label, maxLines = 1)
    }
}

/**
 * Ending a Todo, asked once and answered in full.
 *
 * A dissolution is the one thing Listea destroys that it made itself, so the dialog is built like the
 * one that deletes files: the consequence first, then the listing, then a button the user has to aim
 * for. What is at stake here is not a file but an intention — the Tasks go, and with them every
 * judgment about them — so the sentence says the part that reads as loss before the part that does
 * not: nothing on the device is touched.
 *
 * The Lists are named rather than counted. A List that quietly gets shorter is the failure
 * `docs/file-management.md` already refuses for files, and it is worse here, because the rows do not
 * even go missing visibly: the List's own count would keep including a member nothing can draw.
 *
 * The checkbox is the whole reason this is a dialog rather than a menu item. A dissolution can be
 * undone in the only sense that matters — a later Todo at this folder can be born knowing what this
 * one had decided — and that is a thought a user can only have while looking at the thing they are
 * ending, not weeks earlier when they created it.
 */
@Composable
fun DeleteTodoDialogs(viewModel: ListsViewModel) {
    val request by viewModel.deleteTodoRequest.collectAsStateWithLifecycle()
    val pending = request ?: return

    // The dialog owns the draft, exactly as the import dialog does: the [Confirm] state carries what
    // the Todo's own default is, and the answer travels back as an argument rather than being written
    // into the flow on every tap. Keyed on the Todo, so a second deletion does not open on the first
    // one's answer.
    val confirm = pending as? DeleteTodoRequest.Confirm
    var keepState by rememberSaveable(confirm?.sourceId) {
        mutableStateOf(confirm?.keepState ?: false)
    }

    AlertDialog(
        onDismissRequest = {
            if (pending !is DeleteTodoRequest.Deleting) viewModel.cancelDeleteTodo()
        },
        title = {
            Text(
                when (pending) {
                    DeleteTodoRequest.Reading -> "Reading this Todo"
                    is DeleteTodoRequest.Confirm -> "Delete \"" + pending.sourceName + "\"?"
                    DeleteTodoRequest.Deleting -> "Deleting"
                    is DeleteTodoRequest.Done -> "Todo deleted"
                    is DeleteTodoRequest.Error -> "Nothing was deleted"
                }
            )
        },
        text = {
            when (pending) {
                DeleteTodoRequest.Reading -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(ListeaDimens.SectionGap))
                        Text("Finding what this Todo holds.")
                    }
                }

                is DeleteTodoRequest.Confirm -> DeleteTodoConfirmation(
                    pending = pending,
                    keepState = keepState,
                    onKeepStateChange = { keepState = it }
                )

                DeleteTodoRequest.Deleting -> Text("Removing the Todo and its Tasks…")

                is DeleteTodoRequest.Done -> Text(deleteTodoOutcome(pending.report))

                is DeleteTodoRequest.Error -> Text(pending.message)
            }
        },
        confirmButton = {
            when (pending) {
                is DeleteTodoRequest.Confirm -> TextButton(
                    onClick = { viewModel.confirmDeleteTodo(keepState) }
                ) { Text("Delete Todo", color = MaterialTheme.colorScheme.error) }
                // Nothing to offer mid-run: the rows are already going.
                DeleteTodoRequest.Deleting -> Unit
                else -> TextButton(onClick = { viewModel.cancelDeleteTodo() }) { Text("Close") }
            }
        },
        dismissButton = {
            val cancellable = pending is DeleteTodoRequest.Confirm ||
                pending is DeleteTodoRequest.Reading
            if (cancellable) {
                TextButton(onClick = { viewModel.cancelDeleteTodo() }) { Text("Cancel") }
            }
        }
    )
}

/**
 * What goes, who notices, and what can be kept.
 *
 * The Tasks are described as carrying "everything decided about them" rather than as a number of
 * rows, because that is the loss: `task_state`, the favourites, the custom actions and the review
 * history all go with the Tasks, and none of it is on screen anywhere for the user to have counted.
 */
@Composable
private fun DeleteTodoConfirmation(
    pending: DeleteTodoRequest.Confirm,
    keepState: Boolean,
    onKeepStateChange: (Boolean) -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            if (pending.taskCount == 0) {
                "This Todo holds no Tasks yet. Deleting it removes the Todo itself and nothing else."
            } else {
                "Its " + countLabel(pending.taskCount, "Task") + " go, and everything decided " +
                    "about them — checks, favourites and custom actions."
            }
        )
        Spacer(Modifier.height(ListeaDimens.RowGap))
        Text(
            "The files themselves are not touched: this removes what Listea made out of them, not " +
                "the material.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pending.lists.isNotEmpty()) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                if (pending.lists.size == 1) {
                    "One List holds these Tasks and loses them:"
                } else {
                    countLabel(pending.lists.size, "List") + " hold these Tasks and lose them:"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            LosingListGroups(pending.lists)
        }
        Spacer(Modifier.height(ListeaDimens.RowGap))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onKeepStateChange(!keepState) }
                .padding(vertical = ListeaDimens.CompactGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = keepState, onCheckedChange = { onKeepStateChange(it) })
            Spacer(Modifier.width(ListeaDimens.RowGap))
            Column {
                Text("Keep the decisions", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Records what these Tasks were judged to be, so a Todo created at this folder " +
                        "later can start from it. Nothing changes until such a Todo is made and you " +
                        "choose to load it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The Lists that will lose memberships, each named with how many rows it loses.
 *
 * Named rather than totalled, for the reason the file-deletion dialog lists every file it is about to
 * remove: a List the user has to notice afterwards is a List nobody agreed to change.
 */
@Composable
private fun LosingListGroups(lists: List<ListLosingItems>) {
    Column(Modifier.padding(top = ListeaDimens.CompactGap)) {
        for (list in lists) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = ListeaDimens.CompactGap)
            ) {
                Text(
                    list.title.ifBlank { "Untitled List" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(ListeaDimens.CompactGap))
                Text(
                    countLabel(list.itemCount, "item"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * What a dissolution did, in the two facts the user cannot see for themselves.
 *
 * The Lists are named from the report rather than from the listing the dialog showed, because the two
 * are read at different moments and the report is the one that is true: a List can gain a row between
 * the question and the answer.
 */
internal fun deleteTodoOutcome(report: DissolutionReport): String = buildString {
    append("Deleted \"").append(report.sourceName).append("\"")
    if (report.deletedTaskCount > 0) {
        append(" and ").append(countLabel(report.deletedTaskCount, "Task"))
    }
    append(".")
    if (report.lists.isNotEmpty()) {
        append(" ")
        append(
            report.lists.joinToString(", ") {
                "\"" + it.title + "\" lost " + countLabel(it.itemCount, "item")
            }
        )
        append(".")
    }
    append(
        if (report.keptState) {
            " Its decisions were kept: a Todo created at this folder later can start from them."
        } else {
            " Its decisions went with it."
        }
    )
}

/**
 * The address this Todo's exports post to, edited where the Todo is rather than in Settings.
 *
 * **What is stored is a copy, not a subscription.** *Use default* takes the value Settings holds at
 * the moment Save is pressed; a later edit to the default moves no existing Todo. That is the whole
 * reason this dialog exists: the default is where a new Todo starts, and this is where one stops
 * following it.
 *
 * **An address is required only while the delivery method is Webhook.** A Todo that writes txt or
 * sends nothing may hold no address at all, and may be cleared back to none — the method can be
 * changed later, and refusing to clear an unused value would make an export setting outlive the
 * export. An address that is present is validated whichever method is chosen, so an invalid one is
 * never written into a row a later delivery would trust.
 */
@Composable
private fun TodoWebhookAddressDialog(
    source: SourceSummary,
    defaultWebhookUrl: String,
    onDismiss: () -> Unit,
    onSave: (url: String, fromDefault: Boolean) -> Unit
) {
    val requiresAddress = source.exportKind == ExportKind.WEBHOOK.key
    // A snapshot that no longer matches the live default *is* this Todo's current address. Only a
    // Todo still holding exactly the default starts on Use default; anything else starts on Custom
    // holding the stored value, so the dialog can never show the new default as if the Todo
    // already posted there.
    var mode by rememberSaveable(source.id) {
        mutableStateOf(
            if (source.webhookFromDefault &&
                source.webhookUrl.isNotEmpty() &&
                source.webhookUrl.trim() == defaultWebhookUrl.trim()
            ) {
                "default"
            } else {
                "custom"
            }
        )
    }
    // Starts on what the Todo holds, so moving to Custom edits the current address rather than
    // asking for it again.
    var customUrl by rememberSaveable(source.id) { mutableStateOf(source.webhookUrl) }
    val url = if (mode == "default") defaultWebhookUrl.trim() else customUrl.trim()
    val fromDefault = mode == "default"
    val problem = when {
        requiresAddress -> webhookAddressProblem(ExportKind.WEBHOOK, url)
        url.isNotEmpty() -> webhookUrlError(url)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Webhook address") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Exports through this Todo post to the address it holds. Copying the default " +
                        "takes a snapshot: changing the default in Settings afterwards does not " +
                        "move this Todo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = "default" }
                        .padding(vertical = ListeaDimens.CompactGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == "default", onClick = { mode = "default" })
                    Spacer(Modifier.width(ListeaDimens.RowGap))
                    Column {
                        Text("Use default", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (defaultWebhookUrl.isBlank()) {
                                "No default address is set in Settings"
                            } else {
                                webhookHost(defaultWebhookUrl) ?: defaultWebhookUrl
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = "custom" }
                        .padding(vertical = ListeaDimens.CompactGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == "custom", onClick = { mode = "custom" })
                    Spacer(Modifier.width(ListeaDimens.RowGap))
                    Column {
                        Text("Custom", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Posts to an address you type here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (mode == "custom") {
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Address") },
                        singleLine = true,
                        isError = problem != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (problem != null) {
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (requiresAddress) {
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    Text(
                        "This Todo exports through a webhook, so it needs an address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    Text(
                        "This Todo does not export through a webhook. The address is kept for " +
                            "when it does, and may be cleared.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!requiresAddress && source.webhookUrl.isNotEmpty()) {
                    TextButton(onClick = { onSave("", false) }) {
                        Text("Clear the address")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(url, fromDefault) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
