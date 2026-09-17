package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.example.listea.data.CatalogRow
import com.example.listea.data.ListSummary
import com.example.listea.data.ReviewBlock

/**
 * One Source's catalog: its scopes and its Tasks, browsed as a tree.
 *
 * This is the surface the release exists for. Nothing here imports, resolves or writes: the catalog
 * is a read of `task_imports`, and a row is a Task some run already created. Browsing it can
 * therefore never make one, which is the whole of §2 — and the reason a scope that is empty of
 * Tasks says so rather than minting a placeholder to have something to show.
 *
 * Selection is how a List gets made (§8.1). It is local to this screen and deliberately not part of
 * any stored state: picking four Tasks and then leaving without pressing Create leaves no trace,
 * which is what "a List is membership" has to mean if it is to mean anything.
 *
 * The same screen serves both shapes a Todo can have: a tree of folders, and — for a Create Tasks
 * run that was told not to keep one — the one flat list of every Task it holds ([flat]). Quick
 * Review is offered here rather than only on the Todo's row because this is where the Tasks worth
 * judging are found, and the two acts belong in one place (§7).
 */
@Composable
fun CatalogScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    sourceId: String,
    sourceName: String,
    /**
     * The grant this Source's files sit under, for the preview the catalog opens.
     *
     * Not every Source has one: a Source from a grant the device no longer holds is browseable and
     * its rows are real, but nothing under it can be read. That is the same "unavailable" state the
     * Sources screen reports, and it carries through here as a null root, which turns the preview
     * off rather than opening one that could only say "source missing".
     */
    rootUri: String?,
    /**
     * The Todo's own root: the Source's `relativePath`, which is where its Create Tasks run started.
     *
     * Not the empty path, and the difference is the whole reason this is a parameter. A run started
     * inside a subfolder writes every `groupPath` prefixed with it, so a Todo's material begins at
     * its own root and the grant's root is a level *above* the Todo — a place this screen must not
     * be able to reach, because there is nothing of this Todo there.
     *
     * It is the floor for Back, the name on the title when the scope is there, and the point the
     * breadcrumb calls Root and stops at.
     */
    origin: String,
    /**
     * The scope being browsed, grant-relative like [origin], owned by the caller rather than
     * remembered here.
     *
     * This screen is composed only while nothing is open over it, so a scope held internally would
     * not survive a look at a Task: opening a preview and coming back landed on the Source's root.
     * Held by [MainActivity] for the same reason the browser's own folder stack is — the screen is
     * allowed to leave the composition, and what the user was looking at is not.
     */
    scope: String,
    onScopeChange: (String) -> Unit,
    onExit: () -> Unit,
    onOpenList: (Long) -> Unit,
    onOpenPreview: (CatalogRow, String) -> Unit,
    /**
     * The whole Todo in one list, with no folders to open, because its Create Tasks run said not to
     * keep the tree.
     *
     * [catalogAtScope] is what this means; all that matters here is that a flat catalog has no
     * scopes, so the breadcrumb, the "Part of a Task" card and the folder rows all fall away on
     * their own, and Select can only ever pick whole Tasks — which is the honest granularity of a
     * list that has no parts.
     */
    flat: Boolean = false,
    /**
     * Starting a round from here, or null where there is nothing to start one on.
     *
     * Offered while browsing as well as on the Todo's own row, because reviewing is what browsing a
     * Todo is *for*: finding the Tasks worth judging and then judging them should not mean going
     * back a screen to say so. Null when the Todo's folder is not held or Quick Review is off, and
     * the button is left out rather than shown disabled — a control that can never be pressed is
     * noise, and both reasons are said elsewhere.
     */
    onQuickReview: (() -> Unit)? = null,
    /**
     * Whether the screen opens with picking already on.
     *
     * False for every way of arriving from the Todo it belongs to, because browsing is what the
     * user came to do and a screen that opens in a mode is a screen that has decided something for
     * them. True for the one way in that *is* a selection — the top level's New list, which asked
     * which Todo to take Tasks from and so has already had that question answered.
     */
    startSelecting: Boolean = false
) {
    val importVersion by viewModel.importVersion.collectAsStateWithLifecycle()
    val error by viewModel.listCreationError.collectAsStateWithLifecycle()
    val lists by viewModel.summaries.collectAsStateWithLifecycle()

    // Cold, and re-collected on every committed import: a Task selection made from a stale read
    // would create a List of Tasks that are not the ones the user was shown.
    val rowsFlow = remember(sourceId, importVersion) { viewModel.observeCatalog(sourceId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)

    // Selection is turned on from the header below, over the Tasks the user walked to. Opening
    // with it already on is reserved for the top level's New list, which arrived here *because*
    // of a selection and has nothing left to ask — see [startSelecting]. It is part of the key
    // rather than only the initial value, because a Source opened one way and then the other has
    // to come back the second way rather than restore the first way's answer.
    var selecting by rememberSaveable(sourceId, startSelecting) { mutableStateOf(startSelecting) }
    var title by rememberSaveable(sourceId) { mutableStateOf("") }
    var confirmOpen by rememberSaveable(sourceId) { mutableStateOf(false) }
    var addToListOpen by rememberSaveable(sourceId) { mutableStateOf(false) }
    var selection by rememberSaveable(sourceId) { mutableStateOf(emptyList<String>()) }
    // Folders are picked, not descended into, while selecting - the row's tap still opens them.
    // Three saveable lists rather than one [CatalogSelection], because a Set is not saveable and
    // this screen is the only thing that has to remember a selection across a rotation.
    var pickedScopes by rememberSaveable(sourceId) { mutableStateOf(emptyList<String>()) }
    var excludedTasks by rememberSaveable(sourceId) { mutableStateOf(emptyList<String>()) }

    val back = {
        // A descent inside a Task is a descent inside the Source either way, so the way out of a
        // scope is always one level up and never "leave the catalog" until the Todo's own root is
        // reached. Clamped to [origin] rather than to the empty path: above the Todo is the grant,
        // and the Tasks of a sibling Todo are not something Back should be able to hand anyone.
        if (scope == origin) onExit() else onScopeChange(scope.substringBeforeLast('/', ""))
    }
    BackHandler { back() }

    val current = rows
    if (current == null) {
        ListeaNestedScaffold(title = sourceName, onBack = onExit, modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
        }
        return
    }

    val view = remember(current, scope, flat) { catalogAtScope(current, scope, flat) }

    // The picked folders and their exclusions, as the rules in CatalogSelection.kt want them. The
    // covered Task ids are resolved against the Source's whole catalog rather than the open scope,
    // so a folder ticked at the root still covers everything under it after descending into a
    // sibling. Recomputed only when the selection or the catalog actually moves.
    val picked = remember(selection, pickedScopes, excludedTasks, current) {
        CatalogSelection(
            scopes = pickedScopes.toSet(),
            tasks = selection.toSet(),
            excluded = excludedTasks.toSet()
        )
    }
    val covered = remember(picked, current) { picked.taskIds(current) }

    ListeaNestedScaffold(
        title = catalogScopeTitle(sourceName, scope, origin),
        onBack = back,
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hidden while selecting: the bar at the bottom is already the one thing to do with
                // a selection, and a second offer beside it would be a way to leave a half-made
                // selection behind without being asked about it.
                //
                // Also hidden where this scope holds no Tasks of its own. Quick Review queues the
                // Tasks that lie *exactly* here and does not descend (§7), so at a Todo's root —
                // where there are only folders — a round would open on an empty queue every time.
                // A button whose only outcome is "there is nothing to review here" is the screen
                // asking a question it already knows the answer to; the folders below are the
                // answer, and they are already on screen.
                if (onQuickReview != null && !selecting && view.tasks.isNotEmpty()) {
                    TextButton(onClick = onQuickReview) { Text("Quick Review") }
                }
                // Offering a List of nothing is the same mistake one step further on, so Select
                // follows the same rule — except while a selection is already on, where it is the
                // only way to abandon one. Descending into an empty folder mid-selection must not
                // strand the user in a mode with no way out of it.
                if (selecting || view.tasks.isNotEmpty()) {
                    TextButton(onClick = {
                        selecting = !selecting
                        if (!selecting) {
                            selection = emptyList()
                            pickedScopes = emptyList()
                            excludedTasks = emptyList()
                        }
                    }) {
                        Text(if (selecting) "Cancel" else "Select")
                    }
                }
            }
        }
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(ListeaDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
            ) {
                if (scope != origin) {
                    item {
                        CatalogBreadcrumb(origin, scope, onNavigate = onScopeChange)
                    }
                }

                // The Task this scope sits inside. Said rather than hidden, because a subtree of a
                // Task presented as free-standing material would invite a List of half a Task —
                // exactly what §6.2's containment rule exists to refuse.
                view.enclosing?.let { enclosing ->
                    item {
                        SectionCard {
                            Text(
                                "Part of \"" + enclosing.displayName + "\"",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "The whole Task lives at " +
                                    catalogScopeTitle(sourceName, enclosing.groupPath, origin) +
                                    ". Open it there to pick or review all of it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (view.scopes.isNotEmpty()) {
                    item {
                        SectionHeader("Scopes", Modifier.padding(top = ListeaDimens.RowGap))
                    }
                    items(view.scopes, key = { it.path }) { entry ->
                        CatalogScopeCard(
                            entry = entry,
                            selecting = selecting,
                            picked = picked.isScopePicked(entry.path),
                            onNavigate = { onScopeChange(entry.path) },
                            onToggle = {
                                val next = picked.toggleScope(entry.path, current)
                                pickedScopes = next.scopes.toList()
                                selection = next.tasks.toList()
                                excludedTasks = next.excluded.toList()
                            }
                        )
                    }
                }

                if (view.tasks.isNotEmpty()) {
                    item {
                        SectionHeader("Tasks", Modifier.padding(top = ListeaDimens.RowGap))
                    }
                    items(view.tasks, key = { it.taskId }) { row ->
                        CatalogTaskCard(
                            row = row,
                            selecting = selecting,
                            selected = row.taskId in covered,
                            // No root, no preview: the row is still listed and still selectable,
                            // because a List over a Source the device has lost is a List the user
                            // can still make and re-attach later. What it cannot do is show files
                            // it has no way to read.
                            onOpen = rootUri?.let { root -> { onOpenPreview(row, root) } },
                            onToggle = {
                                val next = picked.toggleTask(row.taskId, current)
                                pickedScopes = next.scopes.toList()
                                selection = next.tasks.toList()
                                excludedTasks = next.excluded.toList()
                            }
                        )
                    }
                }

                if (view.scopes.isEmpty() && view.tasks.isEmpty() && view.enclosing == null) {
                    item {
                        Text(
                            "No Tasks in this scope",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(ListeaDimens.CompactGap))
                        Text(
                            "An import run is the only thing that creates Tasks. Run Create Tasks " +
                                "on this Todo's Upstream if the folder has gained material.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (selecting) {
                SelectionBar(
                    count = covered.size,
                    enabled = covered.isNotEmpty(),
                    onCreate = {
                        title = defaultListTitle(sourceName, scope, origin)
                        confirmOpen = true
                    },
                    onAddToList = { addToListOpen = true }
                )
            }
        }
    }

    if (confirmOpen) {
        // The selection is resolved back to rows here, at the moment of naming, so the dialog lists
        // exactly the Tasks the List will hold rather than a count that could have drifted from it.
        val chosen = current.filter { it.taskId in covered }
        CreateListDialog(
            title = title,
            onTitleChange = { title = it },
            tasks = chosen,
            onDismiss = { confirmOpen = false },
            onConfirm = {
                confirmOpen = false
                val ids = chosen.map { it.taskId }
                viewModel.createListFromCatalog(title, ids) { listId ->
                    selection = emptyList()
                    pickedScopes = emptyList()
                    excludedTasks = emptyList()
                    selecting = false
                    onOpenList(listId)
                }
            }
        )
    }

    if (addToListOpen) {
        AddToListDialog(
            lists = lists,
            taskCount = covered.size,
            onPick = { listId ->
                addToListOpen = false
                // The source of the ids is the same resolution the dialog counted with, taken at
                // the moment of the commit rather than kept from when the bar was drawn.
                viewModel.addTasksToCatalogList(listId, current.filter { it.taskId in covered }
                    .map { it.taskId }) {
                    // Nothing to open and nothing to report: the Tasks are in the List, and the
                    // screen behind is left as the user would want it for a second addition —
                    // selection cleared, mode off. Landing in the List instead would make adding
                    // to two Lists in a row a walk back here each time.
                    selection = emptyList()
                    pickedScopes = emptyList()
                    excludedTasks = emptyList()
                    selecting = false
                }
            },
            onDismiss = { addToListOpen = false }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissListCreationError() },
            title = { Text("Could not save the list") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissListCreationError() }) { Text("OK") }
            }
        )
    }
}

/**
 * The Source's name, and the scope being browsed beneath it. The root is [origin] — the Todo's own
 * root, not the grant's — so a Todo opens under its own name however deep in the grant it sits.
 */
internal fun catalogScopeTitle(sourceName: String, scope: String, origin: String = ""): String =
    if (scope == origin) sourceName else scope.substringAfterLast('/')

/** Where a List made here gets its name from, before the user changes it. */
internal fun defaultListTitle(sourceName: String, scope: String, origin: String = ""): String =
    catalogScopeTitle(sourceName, scope, origin)

/**
 * The path from the Todo's root to where the user is, one tappable segment per level.
 *
 * Built from [scope] with [origin] taken off the front, so the segments are the folders the user
 * walked through and not the grant they happen to live under. The paths it navigates to are put
 * back together with [origin] in front, because a scope is grant-relative wherever it is stored —
 * only the reading is relative to the Todo.
 */
@Composable
private fun CatalogBreadcrumb(origin: String, scope: String, onNavigate: (String) -> Unit) {
    val prefix = if (origin.isEmpty()) "" else "$origin/"
    val segments = scope.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        // Every other part of this row is a plain [Text], which has no minimum height and sits at
        // its own baseline; "Root" is a [TextButton], which Material gives a 40dp touch target. A
        // Row aligns its children to the top by default, so the button's extra height pushed the
        // label up out of line with the rest of the path instead of extending symmetrically around
        // it — which read as "Root" being floated above the folder names it belongs beside.
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onNavigate(origin) }) { Text("Root", maxLines = 1) }
        segments.forEachIndexed { index, name ->
            Text(
                "/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val path = prefix + segments.take(index + 1).joinToString("/")
            if (index == segments.lastIndex) {
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            } else {
                TextButton(onClick = { onNavigate(path) }) { Text(name, maxLines = 1) }
            }
        }
    }
}

/**
 * One folder in the catalog: a scope to open, and — while selecting — a box that picks all of it.
 *
 * The two jobs are kept on separate targets rather than folded into one. Opening a folder is how
 * the user finds the Tasks they want, so it cannot be what ticking means; and a single tap that
 * both opened a folder and selected it would make browsing to check something a way to commit to
 * it. The box takes the tick, the rest of the card still opens.
 *
 * Deselecting and re-descending puts the box back to unchecked even though no Task changed, which
 * is honest: the folder is not held any more. What it does not do is lose the choices made inside
 * it — a Task ticked under a folder stays ticked, and the folder simply does not claim it.
 */
@Composable
private fun CatalogScopeCard(
    entry: CatalogEntry.Scope,
    selecting: Boolean,
    picked: Boolean,
    onNavigate: () -> Unit,
    onToggle: () -> Unit
) {
    OutlinedCard(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            if (selecting) {
                // A trailing-state box inside a card that is itself clickable, and deliberately not
                // a `toggleable` row: one surface cannot both open a folder and pick it, and the
                // row here already opens it. The tick is a control in its own right, so it takes
                // its own tap and its own accessibility node.
                Checkbox(
                    checked = picked,
                    onCheckedChange = { onToggle() }
                )
            }
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    countLabel(entry.taskCount, "Task"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One Task in the catalog: what it is, and whether it could be reviewed.
 *
 * The blocked reason is said here rather than left for Review to refuse, because this screen is
 * where a Task is chosen. A List may hold a blocked Task — membership is not judgment — so the row
 * is still selectable; what is not allowed is being surprised by it later.
 */
@Composable
private fun CatalogTaskCard(
    row: CatalogRow,
    selecting: Boolean,
    selected: Boolean,
    onOpen: (() -> Unit)?,
    onToggle: () -> Unit
) {
    val block = catalogBlock(row)
    // One card, two jobs, and only ever one of them at a time: picking while a List is being
    // assembled, opening a look at it otherwise. They cannot collide because the mode is what
    // decides which tap means what, and the mode is the user's own button.
    val onClick = if (selecting) onToggle else onOpen
    OutlinedCard(
        onClick = onClick ?: { },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
            Column(Modifier.weight(1f)) {
                Text(row.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    catalogTaskDetail(row, block),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (block == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

/**
 * What a Task row says under its name: how much content it has, and why it cannot be reviewed when
 * it cannot. One function so the line and its tone cannot disagree.
 */
internal fun catalogTaskDetail(row: CatalogRow, block: ReviewBlock?): String {
    val content = countLabel(row.assetCount, "Asset") +
        if (row.missingAssets > 0) ", " + row.missingAssets + " missing" else ""
    return if (block == null) content else content + " · " + block.label
}

/**
 * The bar a selection shows: how many Tasks are ticked, and the two ways they can reach a List.
 *
 * The count gets a line to itself rather than sharing one with the buttons. In a row it is the only
 * thing without an intrinsic width, so two labelled buttons take what they need and it is left with
 * whatever is over — which on a phone is narrower than a single word, and "Pick some Tasks" sets
 * one letter per line. On its own line it has the whole width at every font scale.
 */
@Composable
private fun SelectionBar(
    count: Int,
    enabled: Boolean,
    onCreate: () -> Unit,
    onAddToList: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Text(
                if (count == 0) "Pick some Tasks" else countLabel(count, "Task") + " selected",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap, Alignment.End)
            ) {
                // Both ways a selection can reach a List, side by side, because which one the user
                // wants is not something this screen can tell: shopping for two dishes is one List
                // made of both Todos' Tasks, and adding to a List already begun is the other, and
                // the same selection can be either. Neither is a shortcut for the other — a List
                // made here starts empty of everything except what is ticked.
                TextButton(onClick = onAddToList, enabled = enabled) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.height(ListeaDimens.IconSize)
                    )
                    Spacer(Modifier.width(ListeaDimens.RowGap))
                    Text("Add to list…", maxLines = 1)
                }
                TextButton(onClick = onCreate, enabled = enabled) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.height(ListeaDimens.IconSize)
                    )
                    Spacer(Modifier.width(ListeaDimens.RowGap))
                    Text("Create List", maxLines = 1)
                }
            }
        }
    }
}

/**
 * Which List these Tasks go into.
 *
 * The mirror of [CreateListDialog], and deliberately not merged with it: one names a List that does
 * not exist yet, the other picks one that does, and the two have different ways of going wrong.
 * Creating cannot fail from a choice; adding can fail from the choice being wrong.
 *
 * Picking commits, with no second confirmation. The Tasks are already decided — they were ticked
 * on purpose in the screen behind this dialog — and asking twice about an addition that removes
 * nothing would make the gesture feel heavier than the thing it does.
 */
@Composable
private fun AddToListDialog(
    lists: List<ListSummary>,
    taskCount: Int,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to which list?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
                Text(
                    countLabel(taskCount, "Task") + " will be added to the list you pick. A Task " +
                        "the list already holds is left as it is, not added twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lists.isEmpty()) {
                    Text("There are no lists yet. Create one instead.")
                } else {
                    lists.forEach { summary ->
                        OutlinedCard(
                            onClick = { onPick(summary.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(ListeaDimens.CardCorner)
                        ) {
                            Column(Modifier.padding(ListeaDimens.RowGap)) {
                                Text(
                                    summary.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    countLabel(summary.totalItems, "Task"),
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
 * Naming a List before it exists.
 *
 * A List is a judgment context someone will come back to, so it is named at the moment it is made
 * rather than inheriting a folder's name and being renamed later. The Tasks it will hold are listed
 * in full: a List created from a selection must say what it selected.
 */
@Composable
private fun CreateListDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    tasks: List<CatalogRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create list?") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    singleLine = true
                )
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    countLabel(tasks.size, "Task") + ":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tasks.take(DIALOG_TASK_PREVIEW).forEach { row -> Text("- " + row.displayName) }
                if (tasks.size > DIALOG_TASK_PREVIEW) {
                    Text(
                        "and " + (tasks.size - DIALOG_TASK_PREVIEW) + " more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    "Nothing new is imported: these Tasks already exist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private const val DIALOG_TASK_PREVIEW = 6

/** The catalog's icon, for the surfaces that open it. */
internal val CatalogIcon = Icons.AutoMirrored.Filled.List
