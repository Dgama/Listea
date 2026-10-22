package com.example.listea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.listea.data.AssetDecisions
import com.example.listea.data.DissolvedHistory
import com.example.listea.data.ImportOutcome
import com.example.listea.data.ImportResult
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.SharedFileState
import com.example.listea.data.SourceSummary
import com.example.listea.data.decodeActionIds
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.decodeExportChoice
import com.example.listea.domain.encodeExportChoice
import com.example.listea.domain.StoredAddOnVersion
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.ui.theme.ListeaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The scaffold lives in ListeaApp, which needs to know whether a nested screen is open
            // before it can decide what chrome to show.
            ListeaTheme { ListeaApp() }
        }
    }
}

/**
 * The app shell: three top-level destinations, and the nested screens reached from them.
 *
 * The nested flags live here rather than inside the screens that render them, because the shell
 * is what has to know when to take its chrome away. The *rendering* stays where it was: the file
 * viewer needs the folder listing FolderScreen holds, and Quick Review returns to the exact
 * browsing stack it was launched from, so both are still drawn by FolderScreen and both keep
 * working across rotation.
 */
@Composable
fun ListeaApp(
    modifier: Modifier = Modifier,
    listsViewModel: ListsViewModel = viewModel()
) {
    // Saved by name through [TopLevelDestinationSaver] rather than by the enum's own serialisation,
    // which would throw on restore from a bundle written before a destination was renamed.
    var destination by rememberSaveable(stateSaver = TopLevelDestinationSaver) {
        mutableStateOf(TopLevelDestination.Upstreams)
    }

    // Nested destinations, hoisted purely so the shell can tell whether one is open.
    var openListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var quickReview by rememberSaveable(stateSaver = QuickReviewTargetSaver) {
        mutableStateOf<QuickReviewTarget?>(null)
    }
    var viewingFileUri by rememberSaveable { mutableStateOf<String?>(null) }

    // Which page of Settings is up, as one saveable route string. It replaces five booleans and ids
    // that could describe only part of the tree and could disagree about which page was open; a
    // single word also lets the chrome rule below be one comparison instead of a growing `||`, and
    // a native "share" or a settings deep link would land here the same way a tab does.
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.Index) }

    // The one-time v27 address snapshot. Started here rather than in the ViewModel's constructor so
    // a test can build one against a database it arranged without the backfill writing to it; the
    // delivery paths await the same call, so a send cannot race it.
    LaunchedEffect(Unit) { listsViewModel.ensureWebhookAddressBackfill() }

    // The Source whose catalog is open. Held as saveable primitives rather than the SourceSummary
    // itself: the summary is a read of a row that an import can change, and a restored screen
    // should re-read it rather than show a snapshot.
    var catalogSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogSourceName by rememberSaveable { mutableStateOf("") }
    // The Source's path under its grant, which the catalog itself does not need — its rows are
    // already root-relative — but a round started from it does, as the provenance it reports.
    var catalogSourcePath by rememberSaveable { mutableStateOf("") }
    // The grant the open Source's files sit under, and the Task the catalog has opened a look at.
    // The Task is held by id rather than as a row: the row is a read of `task_imports`, and a
    // restored screen should re-read it rather than show a snapshot of a catalog that may have
    // been refreshed since.
    var catalogRootUri by rememberSaveable { mutableStateOf<String?>(null) }
    var previewTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    // The scope of the catalog being browsed, held here for the same reason the browser's stack is:
    // the catalog is composed only while nothing is open over it, so a scope remembered inside it
    // would not survive a look at a Task.
    //
    // Set when a Todo is opened, to that Todo's own root — see [openCatalog].
    var catalogScope by rememberSaveable { mutableStateOf("") }
    // Whether the open Todo is one flat list. A property of the Source, read once when it is opened
    // rather than re-read from the summary, because it is an answer the Create Tasks run gave and
    // the screen that shows it must not change shape under a refresh.
    var catalogFlat by rememberSaveable { mutableStateOf(false) }
    // Whether the open catalog was entered from the Lists tab to build one. It is the same screen
    // either way — the Tasks are chosen the same way, and the List is created by the same button —
    // so what differs is only where leaving goes and whether picking starts on. Held as two bits
    // rather than one mode, because the first is a fact about the entry and the second is the
    // initial value of something the user can turn off.
    var catalogFromLists by rememberSaveable { mutableStateOf(false) }
    var catalogSelecting by rememberSaveable { mutableStateOf(false) }

    // Everything the catalog remembers, dropped together. One function rather than the same
    // assignments at each way out, because a way out that forgot one of them would leave a stale
    // scope or root to be restored by the next Source that was opened.
    val closeCatalog: () -> Unit = {
        catalogSourceId = null
        catalogRootUri = null
        catalogSourcePath = ""
        catalogScope = ""
        catalogFlat = false
        catalogFromLists = false
        catalogSelecting = false
   }

    // Opening a Todo, from the card that names it. The name is the Source's own display name rather
    // than its path: it is what the card that was tapped said, and the catalog reads it back as its
    // title. The shape comes off the same summary — `keepTree` is already normalised against the
    // importer (§2), so a folder-as-Task Source is flat here by the same rule that made it flat when
    // it was imported.
    //
    // No "open for selection" flag any more. Create List used to be a button on the Todo's row that
    // opened this screen with picking already on; with that button gone, turning selection on is
    // something the user does here, over the Tasks they are actually looking at.
    //
    // Nor is there a round over the Todo as a whole. Quick Review reads one level and does not
    // descend (§7), so a round started from the row would open on the Todo's root — where there are
    // only folders — and review nothing; the catalog's own header offers it at each level instead.
    // The scope it opens at is the Todo's own root, which is `relativePath` and not the empty path.
    // A run started inside a subfolder prefixes every `groupPath` it writes with that subfolder, so
    // a Todo's Tasks live under its own root rather than at the grant's: opened at `""`, such a Todo
    // showed a folder named after itself, offered no Quick Review anywhere, and had no level whose
    // round was not empty.
    val openCatalog: (SourceSummary) -> Unit = { source ->
        // A Todo that follows its upstream is kept in step on the way in. Asked before the screen
        // is switched, because what it writes is what the catalog is about to read: a Task brought
        // in here is in the folder the user is opening rather than appearing a moment later.
        listsViewModel.syncTodoUpstream(source.id)
        catalogSourceId = source.id
        catalogSourceName = source.displayName
        catalogSourcePath = source.relativePath
        catalogRootUri = source.rootUri
        catalogScope = source.relativePath
        catalogFlat = !source.keepTree
        catalogFromLists = false
        catalogSelecting = false
    }

    // The other way into a catalog, and the same screen: a List is an order over Tasks, so building
    // one is picking a Todo to take Tasks from and then picking the Tasks. Everything after the
    // Todo is what the Todo tab already does, which is why this changes how the catalog is entered
    // rather than adding a second place to select Tasks — a second picker would be a second set of
    // rules about what a folder covers, and the two would drift.
    //
    // The switch to the Todo tab is not a detour the user has to notice: leaving puts them back on
    // Lists, with the list they made open.
    val buildListFrom: (SourceSummary) -> Unit = { source ->
        openCatalog(source)
        catalogFromLists = true
        catalogSelecting = true
        destination = TopLevelDestination.Todo
    }

    // The Upstream whose folder the browser is standing in, or null while the list of Upstreams is
    // the tab. Held here rather than inside the screen for the same reason the browsing stack is:
    // the screen leaves the composition when the tab is switched away from.
    var openUpstream by rememberSaveable(stateSaver = PinnedScopeSaver) {
        mutableStateOf<PinnedScope?>(null)
    }

    // Which folder the browser is standing in, owned here rather than by FolderScreen itself.
    // FolderScreen is composed only while Folder is the selected destination, so anything it
    // remembers depends on the destination switch handing that state back; ListeaApp is never
    // taken out of the composition, so state held here cannot be dropped by a trip through
    // Lists or Settings, however that switch is implemented.
    var browsedStack by rememberSaveable(stateSaver = DirStackSaver) {
        mutableStateOf(emptyList<DirRef>())
    }

    // A browser counts as nested, and that is the whole of point 4's fix. It was left out of this
    // list, so standing in an Upstream drew the bottom navigation and the tab title over it while
    // the screen itself had no way out but the system Back gesture — and because the tab bar was
    // still there, switching to Settings or Todo looked like a way back and was not: the tab
    // changed underneath while `openUpstream` stayed set, so returning to Upstreams landed in the
    // folder again with no list above it. Folding the browser in here takes the chrome away and
    // hands Back to the browser, which is where "up one folder, then out" now lives.
    // Settings counts only while it is the destination showing: the route is deliberately kept
    // across a tab switch so the user comes back where they were, and a page held by Settings must
    // not take the chrome off somebody else's tab.
    val settingsNested = destination == TopLevelDestination.Settings &&
        settingsRoute != SettingsRoute.Index
    val nested = openListId != null || reviewListId != null ||
        quickReview != null || viewingFileUri != null || settingsNested ||
        catalogSourceId != null || previewTaskId != null ||
        openUpstream != null

    // Every grant the app still holds a persisted permission for, which is what "available" means
    // for a card on either tab. Read from the platform rather than remembered in a store: the
    // permissions are the truth, the user can revoke one from Android's own settings while Listea
    // is closed, and a cached copy would keep offering folders that can no longer be opened.
    //
    // Keyed on the destination so it is re-read whenever the user comes back to a list, which is
    // when it can have changed — a folder added in Settings or revoked in the system UI.
    val appContext = LocalContext.current
    val heldRoots = remember(appContext, destination) { heldRootUris(appContext) }

    // Read here as well as in FolderScreen, which is where the setting is changed: whether Quick
    // Review may be offered at all is what the catalog's own entry point needs to know, and asking
    // the same store both places keeps one switch rather than two opinions about it.
    val settings by listsViewModel.settings.collectAsStateWithLifecycle()
    val quickReviewEnabled = settings.folderQuickReviewEnabled

    // Android convention for a bottom bar: back from a secondary destination returns to the first
    // one rather than leaving the app. Disabled while nested, so the nested screens keep their own,
    // and on the Upstreams tab, where [FolderScreen] answers it: an Upstream's browser is the one
    // screen the tab has that Back has somewhere to go from.
    BackHandler(enabled = !nested && destination != TopLevelDestination.Upstreams) {
        destination = TopLevelDestination.Upstreams
    }

    // A committed pick registers an Upstream and says so here, because the picker's callback has no
    // way to know which screen is up. Consumed rather than kept: it is an event about a pick, and
    // acting on it on every recomposition would re-open the same folder forever.
    //
    // Adding no longer opens anything. The picker is on Settings now, which lists what has been
    // added right below the button, so the answer to "did that work" is already on screen — and
    // jumping the user into a folder browser would take them off the page they were configuring.
    val justAdded by listsViewModel.newUpstream.collectAsStateWithLifecycle()
    LaunchedEffect(justAdded) {
        if (justAdded != null) listsViewModel.consumeNewUpstream()
    }

    // Each destination keeps its own saved state while the other two are off screen. The `when`
    // below only ever composes one of them, so without a holder the two that are not showing are
    // forgotten along with everything they remembered: switching to Lists and back landed on the
    // selected root instead of the folder being browsed, and a trip to Settings reset the Lists
    // filter to All. The holder hands each screen its state back when it returns.
    val destinationState = rememberSaveableStateHolder()

    ListeaTopLevelScaffold(
        destination = destination,
        onSelectDestination = { destination = it },
        showChrome = !nested,
        modifier = modifier
    ) { contentModifier ->
        destinationState.SaveableStateProvider(destination) {
            when (destination) {
                // The Upstreams tab has two surfaces, and which one is up is what `openUpstream`
                // says: the list of Upstreams, and the browser for the one that was opened. The
                // browser is the same screen the old Folder tab was, based on the Upstream's own
                // folder rather than on the grant.
                TopLevelDestination.Upstreams -> {
                    val opened = openUpstream
                    if (opened == null) {
                        UpstreamsScreen(
                            modifier = contentModifier,
                            viewModel = listsViewModel,
                            heldRoots = heldRoots,
                            onOpenBrowser = { upstream ->
                                openUpstream = PinnedScope(
                                    rootUri = upstream.rootUri,
                                    relativePath = upstream.relativePath,
                                    name = upstream.displayName
                                )
                            }
                        )
                    } else {
                        FolderScreen(
                            pinnedScope = opened,
                            onExitScope = {
                                openUpstream = null
                                browsedStack = emptyList()
                            },
                            modifier = contentModifier,
                            viewingFileUri = viewingFileUri,
                            onViewFileChange = { viewingFileUri = it },
                            onOpenList = { listId ->
                                openListId = listId
                                destination = TopLevelDestination.Lists
                            },
                            browsedStack = browsedStack,
                            onBrowsedStackChange = { browsedStack = it },
                            listsViewModel = listsViewModel
                        )
                    }
                }

                TopLevelDestination.Lists -> ListsScreen(
                    modifier = contentModifier,
                    viewModel = listsViewModel,
                    openListId = openListId,
                    onOpenListChange = { openListId = it },
                    reviewListId = reviewListId,
                    onReviewListChange = { reviewListId = it },
                    onBuildList = buildListFrom
                )

                // The three surfaces of a Source, in the order they nest: the browser's own list,
                // one Source's catalog, and a round started from a Source's row. Drawn inside this
                // destination rather than above the scaffold for the same reason FolderScreen draws
                // its own: leaving any of them has to land back on the tab it was entered from,
                // with that tab's remembered state intact.
                TopLevelDestination.Todo -> {
                    val reviewing = quickReview
                    val sourceId = catalogSourceId
                    val previewing = previewTaskId
                    val previewRoot = catalogRootUri
                    // The grant the open Todo's Tasks sit under, and the name it opens under. Read as
                    // locals so the round started from inside the catalog is built from the three
                    // values that identify it rather than from a summary the catalog does not hold.
                    val openRoot = catalogRootUri
                    val openPath = catalogSourcePath
                    val openName = catalogSourceName
                    when {
                        reviewing != null -> QuickReviewScreen(
                            modifier = contentModifier,
                            viewModel = listsViewModel,
                            target = reviewing,
                            onExit = { quickReview = null }
                        )

                        // A preview needs the grant the Source's files sit under, and it is opened
                        // only from a row that had one, so all three are always set together. Read
                        // as one condition rather than defaulting the missing root: a preview with
                        // no root could only show a screen of "source missing", which is not a look
                        // at anything.
                        sourceId != null && previewing != null && previewRoot != null ->
                            CatalogTaskPreview(
                                modifier = contentModifier,
                                viewModel = listsViewModel,
                                sourceId = sourceId,
                                taskId = previewing,
                                rootUri = previewRoot,
                                // The level the preview was opened from, so stepping sideways
                                // walks the Tasks the user was looking at rather than the Todo.
                                scope = catalogScope,
                                flat = catalogFlat,
                                onExit = { previewTaskId = null }
                            )

                        sourceId != null -> CatalogScreen(
                            modifier = contentModifier,
                            viewModel = listsViewModel,
                            sourceId = sourceId,
                            sourceName = openName,
                            // The Todo's own root: the floor the breadcrumb stops at and the level
                            // its title names, and not the same thing as the empty path.
                            origin = openPath,
                            rootUri = previewRoot,
                            scope = catalogScope,
                            onScopeChange = { catalogScope = it },
                            flat = catalogFlat,
                            startSelecting = catalogSelecting,
                            // No grant, nothing to write a decision against, and switched off means
                            // this build browses without offering review at all. Both leave the
                            // button out; the row it came from says why in the first case.
                            //
                            // The scope goes into the target, because a round run from here covers
                            // the folder the user is *standing in* and not the Todo: that is the
                            // level they picked out by walking to it, and the Tasks of a sibling
                            // folder are not what they were looking at when they pressed the
                            // button.
                            onQuickReview = if (quickReviewEnabled && openRoot != null) {
                                {
                                    quickReview = QuickReviewTarget(
                                        rootUri = openRoot,
                                        folderPath = openPath,
                                        sourceId = sourceId,
                                        scope = catalogScope,
                                        flat = catalogFlat,
                                        title = openName
                                    )
                                }
                            } else {
                                null
                            },
                            onExit = {
                                // Read before the close, which clears it. Backing out of a
                                // catalog entered to build a List lands on Lists, where the
                                // building started — not on the Todo tab, which the user never
                                // chose to be on and would have to leave again.
                                val fromLists = catalogFromLists
                                closeCatalog()
                                if (fromLists) destination = TopLevelDestination.Lists
                            },
                            onOpenList = { listId ->
                                closeCatalog()
                                openListId = listId
                                destination = TopLevelDestination.Lists
                            },
                            onOpenPreview = { row, _ -> previewTaskId = row.taskId }
                        )

                        else -> SourcesScreen(
                            modifier = contentModifier,
                            viewModel = listsViewModel,
                            heldRoots = heldRoots,
                            // The card itself, not a button on it: the catalog is behind the row,
                            // and the row is the thing that names what is behind it.
                            onOpenTodo = { source -> openCatalog(source) }
                        )
                    }
                }

                TopLevelDestination.Settings -> SettingsScreen(
                    modifier = contentModifier,
                    viewModel = listsViewModel,
                    route = settingsRoute,
                    onRoute = { settingsRoute = it }
                )
            }
        }
    }

    WebhookDialogs(listsViewModel)
    ImportFolderDialogs(listsViewModel)
    DeleteTodoDialogs(listsViewModel)
}

/**
 * Everything a delivery has to say, in the order it says it: may I, I am, and here is what
 * happened.
 *
 * One host rather than three, and strictly one at a time, because they are three moments of the
 * same event and stacking two of them would be a lie about what is going on. The order is the
 * order they occur in, so whichever is showing is always the latest thing that is true.
 *
 * Hosted at the top level and above the tabs on purpose. The delivery worth interrupting for is
 * the one the user was not watching for — a swipe that finished a round, a checkbox that
 * completed a list — and by the time it happens they may already have navigated somewhere else.
 *
 * The clean-up offer is a fourth moment and comes strictly last, after the result has been
 * acknowledged. Being asked whether to delete a round's files before being told whether the round
 * was delivered would be asking the question backwards.
 */
@Composable
private fun WebhookDialogs(viewModel: ListsViewModel) {
    val confirmation by viewModel.webhookConfirmation.collectAsStateWithLifecycle()
    val progress by viewModel.webhookProgress.collectAsStateWithLifecycle()
    val notice by viewModel.webhookNotice.collectAsStateWithLifecycle()
    val deletion by viewModel.postWebhookDeletion.collectAsStateWithLifecycle()

    // Read into locals first: the order below is the order these things happen in, and each arm
    // needs the value it matched on rather than a nullable field it has to assert about again.
    val pendingConfirmation = confirmation
    val pendingProgress = progress
    val pendingNotice = notice
    val pendingDeletion = deletion

    when {
        pendingConfirmation != null -> WebhookConfirmDialog(pendingConfirmation, viewModel)
        pendingProgress != null -> WebhookProgressDialog(pendingProgress)
        pendingNotice != null -> WebhookNoticeDialog(pendingNotice, viewModel)
        pendingDeletion != null -> PostWebhookDeleteDialog(pendingDeletion, viewModel)
    }
}

/**
 * Asks before an automatic delivery goes out.
 *
 * Only deliveries the app decided to make get here — a review being left, a queue running out, a
 * list going complete. A webhook fired off the back of a swipe was the one thing in Listea that
 * left the device without anyone asking, which is a lot to hang on a gesture whose whole job is
 * to be fast.
 *
 * Declining is deliberately cheap and the dialog says so: the payload is kept in the history
 * either way, and nothing about the items — least of all whether they are checked — depends on
 * the answer. That is what makes *Not now* a real option rather than a way to lose a round of
 * review.
 *
 * Dismissing by tapping outside is treated as declining, for the same reason: the safe reading of
 * "the user did not answer" is that nothing should leave the device.
 */
@Composable
private fun WebhookConfirmDialog(
    confirmation: WebhookConfirmation,
    viewModel: ListsViewModel
) {
    AlertDialog(
        onDismissRequest = { viewModel.declineWebhookSend() },
        title = { Text("Send this webhook?") },
        text = {
            Column {
                // What caused this, not just what it is about. The dialog can appear over any
                // screen, and the List it names is not always the one the user was looking at —
                // see `webhookTriggerLabel` for the pass that found the bare title unreadable.
                Text(confirmation.triggerLabel, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    confirmation.factsLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    "This would post to " + confirmation.destinationLabel +
                        ". If you do not send it now, it is kept in the export history and " +
                        "nothing about the items changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmWebhookSend() }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.declineWebhookSend() }) { Text("Not now") }
        }
    )
}

/**
 * The seconds a payload spends on the wire, which used to look exactly like nothing happening.
 *
 * A round can be hundreds of items and a receiver can be slow, so a delivery is not instant and
 * the user was left looking at a screen that had no way of telling them whether it had already
 * finished. Shown for every send there is — automatic, the Test button, a resend from the history.
 *
 * Deliberately not dismissable and with no buttons. There is nothing useful to do while a POST is
 * in flight, and a cancel here would leave the honest answer — whether the receiver got it —
 * unknowable. It goes away by itself the moment the result lands, whatever the result is.
 */
@Composable
private fun WebhookProgressDialog(progress: WebhookProgress) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Sending…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(ListeaDimens.IconSize))
                Spacer(Modifier.width(ListeaDimens.SectionGap))
                Column {
                    Text(progress.listTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        progress.summaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { }
    )
}

/**
 * Reports the outcome of every webhook delivery, whichever screen triggered it: a swipe that
 * completed a list in Review, the last checkbox on the detail screen, or the Test button.
 *
 * The result never has to be inferred from a status line on a screen the user would have to
 * navigate to. Dismissing or acknowledging clears it; the webhook history keeps the payload and
 * the verdict afterwards, and the stored per-list delivery record keeps the detail.
 *
 * A declined delivery deliberately produces nothing here. The user has just pressed *Not now*,
 * and answering that with a dialog saying it was not sent is telling them what they did.
 */
@Composable
private fun WebhookNoticeDialog(current: WebhookNotice, viewModel: ListsViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissWebhookNotice() },
        title = { Text(current.headline) },
        text = {
            Column {
                Text(current.listTitle, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    current.outcomeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    // A switched-off webhook, or one with nothing to carry, is information
                    // rather than failure: neither gets the success tint, nor the alarm of a
                    // delivery that actually went wrong.
                    //
                    // A round that exported nothing joins the quiet group rather than the alarmed
                    // one: nothing was attempted, nothing broke, and the app is only saying what
                    // it did not do. Colouring the absence of a configuration like a delivery that
                    // went wrong is how a user learns to ignore the colour.
                    color = when (current.outcome) {
                        NoticeOutcome.SENT,
                        NoticeOutcome.WRITTEN -> MaterialTheme.colorScheme.primary
                        NoticeOutcome.FAILED -> MaterialTheme.colorScheme.error
                        NoticeOutcome.DISABLED,
                        NoticeOutcome.EMPTY,
                        NoticeOutcome.DECLINED,
                        // Kept rather than sent, with nothing wrong: quiet for the same reason a
                        // decline is. It has no notice of its own — a round set aside because the
                        // user was elsewhere is never shown — but the outcome is listed here so
                        // that a future path which does show one cannot colour it as a failure.
                        NoticeOutcome.DEFERRED,
                        NoticeOutcome.UNCONFIGURED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    buildList {
                        add(current.eventLabel)
                        // Singular when it is one. The same helper the progress dialog and the
                        // history rows use, so a round of one item reads the same wherever it is
                        // reported.
                        current.itemCount?.let { add(countOfItems(it)) }
                        current.host?.let { add(it) }
                        add(current.timeLabel)
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismissWebhookNotice() }) { Text("OK") }
        }
    )
}

/**
 * The offer a delivered webhook makes: the files that round just reported as checked are still on
 * the device — should they stay there?
 *
 * Only ever seen with *Ask to delete after webhook* switched on, and only after the delivery's own
 * result has been acknowledged. What it names is exactly what the payload carried as checked:
 * whatever the review queued, and whatever "send checked items only" then kept, has already had
 * its say by the time this appears, and the offer is bounded by the delivery rather than by the
 * root folder. That is the difference between this and Settings' own *Delete checked files*,
 * which covers every checked file under the root however and whenever it was checked — so the
 * dialog says which it is rather than leaving the two to be confused.
 *
 * Keeping is the easy path, everywhere it can be: dismissing counts as keeping, the delete button
 * is the one that has to be aimed for, and nothing here can be reached at all unless a receiver
 * has already accepted the round. While the deletion runs there is no way out, because there is
 * nothing left to decide.
 *
 * A delivery that failed reaches [PostWebhookDeletion.NotSent] instead and offers nothing. The
 * files stay, and the dialog says where the round went — the webhook history, which is also where
 * it is resent from — rather than quietly dropping the subject.
 */
@Composable
private fun PostWebhookDeleteDialog(
    pending: PostWebhookDeletion,
    viewModel: ListsViewModel
) {
    val dismiss = { viewModel.dismissPostWebhookDeletion() }

    AlertDialog(
        onDismissRequest = { if (pending !is PostWebhookDeletion.Deleting) dismiss() },
        title = {
            Text(
                when (pending) {
                    is PostWebhookDeletion.Ask -> "Delete the files that were sent?"
                    is PostWebhookDeletion.NotSent -> "Nothing was deleted"
                    is PostWebhookDeletion.NeedsWriteAccess -> "Listea cannot delete these yet"
                    PostWebhookDeletion.Deleting -> "Deleting"
                    is PostWebhookDeletion.Done -> "Finished"
                    is PostWebhookDeletion.Error -> "Deleted, but not recorded"
                }
            )
        },
        text = {
            when (pending) {
                is PostWebhookDeletion.Ask -> Column {
                    Text(pending.listTitle, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "${pending.eventLabel} sent " +
                            "${countLabel(pending.fileCount, "checked file")}. Deleting removes " +
                            "exactly those files from the device, permanently — not every " +
                            "checked file under the root folder, and nothing this webhook did " +
                            "not carry."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    CheckedFileGroups(
                        groups = pending.groups,
                        rootLabel = "Selected folder"
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "The items stay in their Lists, marked as missing, so what you decided " +
                            "about them is kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is PostWebhookDeletion.NotSent -> Column {
                    Text(pending.listTitle, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        pending.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "${countLabel(pending.fileCount, "checked file")} stayed on the device, " +
                            "because nothing has received them yet. The round is kept in " +
                            "Settings › Exports › Every export — resend it from there, or delete " +
                            "the checked files yourself from Settings › File management."
                    )
                }

                is PostWebhookDeletion.NeedsWriteAccess -> Text(
                    "The webhook carried ${countLabel(pending.fileCount, "checked file")}, but " +
                        "Listea is only allowed to read the source folder, so it cannot offer to " +
                        "delete anything. Android hands over permission to delete through its " +
                        "own folder picker, which Settings › File management will take you to."
                )

                PostWebhookDeletion.Deleting -> Text("Removing the files from the device…")

                is PostWebhookDeletion.Done ->
                    Text(deleteOutcomeMessage(pending.deleted, pending.failed))

                is PostWebhookDeletion.Error -> Text(pending.message)
            }
        },
        confirmButton = {
            when (pending) {
                is PostWebhookDeletion.Ask -> TextButton(
                    onClick = { viewModel.confirmPostWebhookDeletion() }
                ) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
                // Nothing to offer mid-run: the files are already going.
                PostWebhookDeletion.Deleting -> Unit
                else -> TextButton(onClick = dismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (pending is PostWebhookDeletion.Ask) {
                TextButton(onClick = dismiss) { Text("Keep them") }
            }
        }
    )
}

/** Three strings, so the scope survives a rotation and a process death like everything else here. */
private val PinnedScopeSaver = listSaver<PinnedScope?, String>(
    save = { scope ->
        scope?.let { listOf(it.rootUri, it.relativePath, it.name) } ?: emptyList()
    },
    restore = { saved ->
        if (saved.size == 3) PinnedScope(saved[0], saved[1], saved[2]) else null
    }
)

/**
 * Keeps a browsing stack across rotation and process death, as flat uri/name pairs.
 *
 * Without this, turning the phone rebuilds the screen from scratch and drops the user back at the
 * selected root instead of the folder they were actually in.
 */
private val DirStackSaver = listSaver<List<DirRef>, String>(
    save = { stack -> stack.flatMap { listOf(it.uri.toString(), it.name) } },
    restore = { flat -> flat.chunked(2).map { DirRef(it[0].toUri(), it[1]) } }
)

/**
 * Same idea for an open Quick Review: rotating should not throw the user out of it.
 *
 * Every field, including the Source and the scope. Restoring only the folder would silently turn a
 * round over a Todo into a round over the Upstream's folder — the same screen, a different queue,
 * and a different set of decisions written — so the field that says which of the two this is has to
 * survive with it. An empty Source id is a folder round, which no Source can be, because a Source's
 * id is a UUID.
 *
 * [QuickReviewTarget.scope] survives for the same reason one level down: two levels of one Todo are
 * two different queues, and restoring "Holiday" as "" would hand back a round over a scope that has
 * no Tasks in it at all.
 *
 * Six entries, and the size check is what tells a bundle written by an older build apart from a real
 * target. Both older shapes are read back rather than dropped: a four-element list was a round over
 * a whole Todo, and a five-element one predates [QuickReviewTarget.flat], which only a flat Todo
 * ever sets. Their scopes were written when a Todo's root was spelled `""`; that is still right for
 * a Todo whose run started at the Upstream itself, which is what almost every restored round will
 * be, and a stale level on the others costs a re-browse rather than a wrong decision.
 */
private val QuickReviewTargetSaver = listSaver<QuickReviewTarget?, String>(
    save = { target ->
        target?.let {
            listOf(
                it.rootUri,
                it.folderPath,
                it.sourceId ?: "",
                it.scope,
                if (it.flat) "1" else "0",
                it.title
            )
        }.orEmpty()
    },
    restore = { saved ->
        when (saved.size) {
            6 -> QuickReviewTarget(
                rootUri = saved[0],
                folderPath = saved[1],
                sourceId = saved[2].ifEmpty { null },
                scope = saved[3],
                flat = saved[4] == "1",
                title = saved[5]
            )

            5 -> QuickReviewTarget(
                rootUri = saved[0],
                folderPath = saved[1],
                sourceId = saved[2].ifEmpty { null },
                scope = saved[3],
                title = saved[4]
            )

            4 -> QuickReviewTarget(
                rootUri = saved[0],
                folderPath = saved[1],
                sourceId = saved[2].ifEmpty { null },
                title = saved[3]
            )

            else -> null
        }
    }
)

/** Holds the in-flight folder read, so a newer navigation can replace it. */
private class LoadJob {
    var job: Job? = null
}

/**
 * An Upstream the browser was opened from, which is the whole of what it may show.
 *
 * The browsing stack is based on this folder instead of on the granted root, so "up" stops here.
 * That is the difference between an Upstream and the grant: the grant is where Listea is allowed to
 * stand, and an Upstream is where the user has said the material is.
 *
 * [rootUri] is the grant and [relativePath] is the folder inside it, which is the pair the rest of
 * the app identifies a Source by: the browser has to speak in both, because the folder it stands in
 * is the Source's and the grant is where every stored path is measured from.
 *
 * [name] is the Source's display name, which is what the card that opened this showed and therefore
 * what the breadcrumb and the title have to agree with.
 */
data class PinnedScope(val rootUri: String, val relativePath: String, val name: String)

/** What the browser says when the Upstream's own folder is not under the grant any more. */
private const val UPSTREAM_GONE =
    "This Upstream's folder is no longer accessible. Add it again to browse it."

private sealed interface FolderUiState {
    data class NoFolder(val message: String?) : FolderUiState
    data object Loading : FolderUiState

    /** [stack] runs from the persisted root (first) to the directory shown (last). */
    data class Browsing(val stack: List<DirRef>, val contents: FolderContents) : FolderUiState
}

/**
 * The **Upstream browser**: the folder an Upstream is, laid out as a folder.
 *
 * This is the old Folder tab with the Task-shaped parts taken out. Quick Review and Create List are
 * about Tasks, and they live in Todo now, where every row is a Task an import already wrote; a round
 * started here would run over files no import has claimed, and the List it built would hold
 * half-formed things. What is left is what belongs to material: look at it, sort it, open a file,
 * walk into a folder, Create Tasks, and Refresh a run that already happened.
 *
 * It is opened only from an Upstream's card, so [pinnedScope] is required rather than optional. The
 * stack is based on that folder instead of on the granted root, which is what makes "up" stop there
 * and makes every path below relative to the Upstream rather than to the grant.
 */
@Composable
fun FolderScreen(
    pinnedScope: PinnedScope,
    onExitScope: () -> Unit,
    modifier: Modifier = Modifier,
    viewingFileUri: String? = null,
    onViewFileChange: (String?) -> Unit = {},
    onOpenList: (Long) -> Unit = {},
    browsedStack: List<DirRef> = emptyList(),
    onBrowsedStackChange: (List<DirRef>) -> Unit = {},
    listsViewModel: ListsViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by listsViewModel.settings.collectAsStateWithLifecycle()
    val resolver by listsViewModel.templateResolver.collectAsStateWithLifecycle()
    val arrangements by listsViewModel.arrangements.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val load = remember { LoadJob() }
    var state by remember { mutableStateOf<FolderUiState>(FolderUiState.Loading) }

    /**
     * Shows the last directory of [stack]. If it can no longer be read (another app deleted it
     * while we were away) this walks up towards the base; if even the base is gone the screen says
     * the Upstream's folder is unreachable, which is the one thing it can still be sure of.
     * [quiet] keeps the current list on screen instead of flashing a spinner.
     */
    suspend fun show(stack: List<DirRef>, quiet: Boolean = false) {
        if (stack.isEmpty()) {
            // [startingStack] found nothing to stand in, and the grant it was looked for under is
            // not somewhere to stand instead: the Upstream's folder is the whole of what this
            // screen shows, so a missing one is reported rather than worked around.
            onBrowsedStackChange(emptyList())
            state = FolderUiState.NoFolder(UPSTREAM_GONE)
            return
        }
        if (!quiet) state = FolderUiState.Loading

        // Asked once, about the grant rather than about each directory: the persisted permission is
        // the tree the user picked, and a folder below it is never a permission of its own.
        val grant = pinnedScope.rootUri.toUri()
        var path = stack
        while (true) {
            val contents = withContext(Dispatchers.IO) {
                if (hasPersistedReadAccess(context, grant)) {
                    readFolder(context, path.last().uri)
                } else {
                    null
                }
            }
            if (contents != null) {
                // Keep the displayed name in sync with what the provider reports.
                val shown = path.dropLast(1) + path.last().copy(name = contents.folderName)
                state = FolderUiState.Browsing(stack = shown, contents = contents)
                // The contents are re-read on every entry, but *where* the user was is worth
                // keeping, so the position goes back to the shell that outlives this screen.
                onBrowsedStackChange(shown)
                return
            }
            if (path.size == 1) break
            path = path.dropLast(1)
        }
        onBrowsedStackChange(emptyList())
        // The unreachable folder is the Upstream's own, and the grant the user still holds is a
        // different thing that this screen must not throw away because one folder under it went
        // missing.
        state = FolderUiState.NoFolder(UPSTREAM_GONE)
    }

    /** Single entry point for every load, so a newer one always replaces an older one. */
    fun open(stack: List<DirRef>, quiet: Boolean = false) {
        load.job?.cancel()
        load.job = scope.launch { show(stack, quiet) }
    }

    /**
     * Where to open on entry: the folder the user was last in, or the Upstream's own folder.
     *
     * The base is the Upstream's folder and the granted root is never used: the stack keeps that
     * folder as its first entry, which is what makes "up" stop there. An Upstream is a folder inside
     * a grant, so this is the one place that walks the grant down to it — everywhere below speaks in
     * paths already anchored at the grant.
     *
     * Empty when the folder is not there any more, which [show] reports rather than substituting the
     * grant for it.
     */
    suspend fun startingStack(): List<DirRef> {
        val folder = withContext(Dispatchers.IO) {
            resolveLinkedFolder(context, pinnedScope.rootUri.toUri(), pinnedScope.relativePath)
        } ?: return emptyList()
        val base = DirRef(folder.uri, "")
        // A remembered path from an earlier visit to this same Upstream is worth keeping; anything
        // else is from another folder and starts over.
        val remembered = browsedStack
        return if (remembered.firstOrNull()?.uri == base.uri) remembered else listOf(base)
    }

    LaunchedEffect(Unit) { open(startingStack()) }

    // The file viewer is still drawn here, so the browsing stack survives and closing it lands back
    // on the same folder. Only its on/off state is owned by the shell, which needs it to hide the
    // bottom navigation.

    // Another app (FolderSync, a file manager, ...) may change the folder while we are backgrounded,
    // so re-read the directory on screen whenever we come back to the foreground. A load started by
    // the folder picker (which also triggers a resume) already has the newer target, so leave it be.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = state
        if (current is FolderUiState.Browsing && load.job?.isActive != true) {
            open(current.stack, quiet = true)
        }
    }

    val browsing = state as? FolderUiState.Browsing

    // Where the browser is standing, said twice because two vocabularies meet here. [herePath] is
    // relative to the Upstream — the stack's base is the Upstream's own folder, so its first
    // segment is the one below it — and [here] is the same folder written the way everything stored
    // is written: relative to the grant. A Source's `relativePath`, a List's scope and a saved
    // arrangement are all grant-relative, so joining the Upstream's path on the front is what keeps
    // a Todo and its browser talking about the same folder.
    val herePath = browsing?.let { relativePathOf(it.stack) }
    val here = listOf(pinnedScope.relativePath, herePath.orEmpty())
        .filter { it.isNotEmpty() }
        .joinToString("/")

    // ---------------------------------------------------------------------------------------
    // What the Files section is showing, derived once for the whole screen.
    //
    // Hoisted above the nested screens rather than left in the Browsing branch below, because
    // the file viewer is one of those nested screens and pages exactly this subset: tapping the
    // third of eight filtered pictures has to swipe through those eight and not through the
    // forty files the folder happens to hold. The branch below reuses these rather than deriving
    // a second, quietly different answer to the same question.
    //
    // All of it tolerates there being no folder open, because this runs before the state is
    // narrowed: with no folder there are no files, and nothing below it is ever consulted.
    // ---------------------------------------------------------------------------------------
    //
    // The root is the Upstream's grant rather than the folder the browser is standing in, because
    // ownership, List scopes and arrangements are all stored against the grant.
    val rootUri = pinnedScope.rootUri.takeIf { browsing != null }
    val ownershipFlow = remember(rootUri) {
        rootUri?.let { listsViewModel.observeFolderOwnership(it) } ?: flowOf(FolderOwnership())
    }
    val ownership by ownershipFlow.collectAsStateWithLifecycle(initialValue = FolderOwnership())
    val quickReviewEnabled = settings.folderQuickReviewEnabled

    // Split once per listing rather than per row, so the two sections below can be headed
    // separately without testing isDirectory on every item.
    val entries = browsing?.contents?.entries.orEmpty()
    val directories = remember(entries) { entries.filter { it.isDirectory } }
    val files = remember(entries) { entries.filterNot { it.isDirectory } }

    // A file's checked state comes from whichever list owns the folder it sits in, direct or
    // inherited. Resolved once here rather than per row, because the filter and the sort need it
    // as much as the tick does.
    val owner = owningScope(folderListStatus(ownership, here))

    /**
     * What a row's tick shows, or null for no tick at all.
     *
     * The file's own state first, so a file reviewed in a folder no List covers still shows its
     * tick. Falling back to list membership keeps a member that has never been reviewed showing
     * an empty tick rather than none. Null when nothing anywhere holds an opinion, because an
     * empty tick on every file of an uncovered folder would claim a review relationship that
     * does not exist.
     */
    fun shownCheckState(entry: FolderEntry): Boolean? {
        if (!quickReviewEnabled) return null
        val path = childRelativePath(here, entry.name)
        return ownership.fileCompletion[path]
            ?: owner?.let { ownership.itemCompletion[it.id to path] }
    }

    /**
     * What the filter and the sort read, which is not quite the same question.
     *
     * "Nothing recorded" is not an opinion worth drawing a tick for, but it is a perfectly good
     * answer to "is this checked?" — no. Collapsing it to false here rather than in
     * [shownCheckState] is what lets Unchecked mean every file still to be looked at, rather than
     * only the ones some list already knows about.
     */
    fun sortableCheckState(entry: FolderEntry): Boolean? =
        if (!quickReviewEnabled) null else shownCheckState(entry) ?: false

    // With no root the key names nothing, which costs nothing: there are no files to arrange, and
    // the shared mode does not consult it at all.
    val arrangement = resolveArrangement(
        arrangements,
        folderArrangementKey(rootUri.orEmpty(), here),
        settings.sharedFileArrangement
    )

    // Re-derived whenever the listing, the arrangement or any checked state changes, which is
    // exactly what makes this survive a refresh: a file deleted outside the app leaves the
    // listing and a file checked in Quick Review changes group, and both arrive here as a new
    // input rather than as something to invalidate by hand.
    val shownFiles = remember(files, arrangement, ownership, here, quickReviewEnabled, owner) {
        arrangeFiles(files, arrangement, System.currentTimeMillis()) { entry ->
            FileFacts(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
                isChecked = sortableCheckState(entry)
            )
        }
    }

    // Back walks up the folder stack, and at the base it leaves the Upstream. An open Upstream is
    // the whole of what this tab is showing, so Back has a level of the app to go to here and never
    // one to fall out of — which it had been doing, quitting from inside a folder the user had only
    // just opened. The shell leaves this destination's Back to the screen for the same reason.
    BackHandler(enabled = viewingFileUri == null) {
        val stack = browsing?.stack
        if (stack != null && stack.size > 1) open(stack.dropLast(1)) else onExitScope()
    }

    // Hosted inside this screen so closing the viewer returns to the same folder, exactly where it
    // was left. Resolved against the current listing, so a rotation reopens the same file once the
    // folder has been read again.
    val openFile = viewingFileUri?.let { uri -> shownFiles.firstOrNull { it.uri.toString() == uri } }

    // A file can be deleted by another app between opening it and the folder being re-read. Give
    // the viewer up rather than leave the shell believing a nested screen is still open, which
    // would strip the bottom navigation off a folder page that has no way back.
    LaunchedEffect(viewingFileUri, shownFiles) {
        if (viewingFileUri != null && browsing != null && openFile == null) {
            onViewFileChange(null)
        }
    }

    if (openFile != null) {
        FileViewerScreen(
            modifier = modifier,
            // Exactly what the browser is showing, in the order it shows it, so a swipe reaches
            // the next row on the page. A filter left on means eight pictures to page through,
            // not forty files with thirty-two of them hidden on the page behind. Directories are
            // not files to page through either way.
            files = shownFiles,
            initial = openFile,
            // Where these files sit, for the Info sheet. The viewer shows one folder's direct
            // files, so this is the same path for every card in it.
            folderPath = here,
            settings = settings,
            onClose = { onViewFileChange(null) }
        )
        return
    }

    // The browser draws its own header, because the shell gave its chrome up when this opened.
    //
    // It used to be drawn under the tab bar, which is where the "cannot get back" report came from:
    // the bar was the only navigation on screen and none of it led out of the folder, and Back —
    // which did — leaves no trace to look at. The header answers the question the tab bar cannot:
    // the arrow and the word "Upstreams" beside it say where leaving goes, and the title is the
    // Upstream being browsed, which is the name the card that opened it carried.
    ListeaNestedScaffold(
        title = pinnedScope.name,
        onBack = onExitScope,
        backLabel = "Upstreams",
        modifier = modifier
    ) { bodyModifier ->
        Column(bodyModifier.fillMaxSize()) {
            when (val current = state) {
                is FolderUiState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                // An Upstream that could not be read says so and offers the way back to the list it was
                // opened from, not a trip to Settings — the grant is still there, one folder under it
                // is not.
                is FolderUiState.NoFolder -> Column(
                    Modifier.padding(ListeaDimens.PagePadding)
                ) {
                    Text(
                        "Nothing can be read here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    current.message?.let {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(ListeaDimens.SectionGap))
                    Button(onClick = onExitScope) { Text("Back to Upstreams") }
                }

                is FolderUiState.Browsing -> {
                    // The Upstream's grant, which a run over this folder is recorded against: the two
                    // are one pair, and `here` below is where the folder sits inside it. Everything
                    // else this branch needs — the ownership, the arrangement, the files and the subset
                    // shown — is hoisted, because the file viewer above pages that same subset.
                    val root = pinnedScope.rootUri

                    // Ownership for the current folder and every visible directory, derived once per
                    // data change rather than per card recomposition, from one indexed lookup each.
                    val statuses = remember(ownership, here, current.contents.entries) {
                        buildMap {
                            put(here, folderListStatus(ownership, here))
                            current.contents.entries.forEach { entry ->
                                if (entry.isDirectory) {
                                    val path = childRelativePath(here, entry.name)
                                    put(path, folderListStatus(ownership, path))
                                }
                            }
                        }
                    }
                    fun statusOf(path: String) = statuses[path] ?: FolderListStatus.None

                    val folderKey = folderArrangementKey(root, here)
                    var sortOpen by remember { mutableStateOf(false) }
                    var filterOpen by remember { mutableStateOf(false) }
                    // The shared file being drilled into, grant-relative like everything else
                    // stored. Null is the ordinary case, and the dialog below is the only thing
                    // that reads it.
                    var sharedPath by remember(root) { mutableStateOf<String?>(null) }

                    // One scroll surface for the page. The breadcrumb, the folder summary and the
                    // action row scroll away with everything else rather than pinning most of the
                    // screen open above a small list viewport — which is what left almost nothing
                    // usable in landscape.
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = ListeaDimens.PagePadding,
                            vertical = ListeaDimens.RowGap
                        ),
                        verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        item {
                            Breadcrumb(
                                stack = current.stack,
                                onNavigate = { depth -> open(current.stack.take(depth)) },
                                baseLabel = pinnedScope.name
                            )
                        }
                        item {
                            CurrentFolderCard(
                                entries = current.contents.entries,
                                status = statusOf(here),
                                owners = ownersOf(ownership, here),
                                onRefresh = { open(current.stack) },
                                onOpenList = onOpenList,
                                onCreateTasks = {
                                    listsViewModel.requestCreateTasks(root, here)
                                }
                            )
                        }

                        if (current.contents.entries.isEmpty()) {
                            item {
                                Spacer(Modifier.height(ListeaDimens.SectionGap))
                                Text(
                                    "This folder is empty",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        if (directories.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    "Directories",
                                    Modifier.padding(top = ListeaDimens.RowGap)
                                )
                            }
                            items(directories, key = { it.uri }) { entry ->
                                val path = childRelativePath(here, entry.name)
                                FolderCard(
                                    entry = entry,
                                    status = statusOf(path),
                                    owners = ownersOf(ownership, path),
                                    onNavigate = {
                                        open(current.stack + DirRef(entry.uri, entry.name))
                                    }
                                )
                            }
                        }

                        if (files.isNotEmpty()) {
                            item {
                                // The header keeps the folder's whole file count behind it: the
                                // controls that decide what is shown must not themselves disappear
                                // when the filter they set happens to match nothing.
                                ArrangeableSectionHeader(
                                    title = "Files",
                                    arrangement = arrangement,
                                    onSort = { sortOpen = true },
                                    onFilter = { filterOpen = true },
                                    modifier = Modifier.padding(top = ListeaDimens.RowGap)
                                )
                                if (!arrangement.isFilterDefault) {
                                    FilterSummary(shown = shownFiles.size, total = files.size)
                                }
                            }
                            items(shownFiles, key = { it.uri }) { entry ->
                                val path = childRelativePath(here, entry.name)
                                val owners = ownersOf(ownership, path)
                                FileRow(
                                    entry = entry,
                                    // Hidden entirely while Folder review integration is off, which
                                    // changes what is shown and nothing that is stored. The *mark*
                                    // goes with it, because the mark opens a review drill-in. The
                                    // owner line does not: which Todo imported a file is not a
                                    // review fact, and the folder cards show it ungated already.
                                    isChecked = shownCheckState(entry),
                                    onOpen = { onViewFileChange(entry.uri.toString()) },
                                    owners = owners,
                                    ownerCount =
                                        if (quickReviewEnabled) fileOwnerMark(owners) else null,
                                    onOpenShared = { sharedPath = path }
                                )
                            }
                        }
                    }

                    fun applyArrangement(updated: FileArrangement) {
                        listsViewModel.setArrangement(folderKey, updated)
                        sortOpen = false
                        filterOpen = false
                    }

                    if (sortOpen) {
                        FileSortDialog(
                            arrangement = arrangement,
                            // Sorting by something the page is not showing would be an order with no
                            // visible reason for being what it is.
                            showCheckedSort = quickReviewEnabled,
                            onDismiss = { sortOpen = false },
                            onApply = ::applyArrangement
                        )
                    }
                    if (filterOpen) {
                        FileFilterDialog(
                            arrangement = arrangement,
                            showCheckedGroup = quickReviewEnabled,
                            onDismiss = { filterOpen = false },
                            onApply = ::applyArrangement
                        )
                    }
                    sharedPath?.let { path ->
                        SharedFileDialog(
                            path = path,
                            resolver = resolver,
                            load = { listsViewModel.sharedFileStates(root, path) },
                            onDismiss = { sharedPath = null }
                        )
                    }
                }
            }
    }
    }

}

/**
 * Create Tasks, and a Todo's Refresh Import.
 *
 * Hosted at the top level rather than inside either tab, because a refresh is raised from Todo and a
 * run that commits changes the catalog Upstreams reads. One host means one report, wherever the run
 * was started from.
 *
 * The whole configuration is chosen here and only here. file-as-Task and folder-as-Task read the
 * same folder into different Tasks, the name is what the Todo will be called, and whether the folder
 * tree survives is what the Todo looks like when opened — none of which the app can infer from the
 * material. It is the question this dialog exists to ask. A refresh is not asked, because the Source
 * already answered it.
 */
@Composable
private fun ImportFolderDialogs(viewModel: ListsViewModel) {
    val request by viewModel.importFolderRequest.collectAsStateWithLifecycle()
    // The live default, read here rather than inside the dialog so the dialog has one value to
    // draw and no subscription of its own: *Use default* is resolved at Confirm, and what it copies
    // is whatever Settings says at that moment.
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    when (val current = request) {
        null -> Unit

        is ImportFolderRequest.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading folder") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
                    Text("Finding what an import would cover.")
                }
            },
            confirmButton = {}
        )

        is ImportFolderRequest.Confirm -> ImportConfirmDialog(
            request = current,
            defaultWebhookUrl = settings.defaultWebhookUrl,
            onConfirm = { options, recoverFrom ->
                viewModel.confirmImportFolder(options, recoverFrom)
            },
            onDismiss = { viewModel.dismissImportFolderRequest() }
        )

        is ImportFolderRequest.Importing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
                    Text("Creating Tasks.")
                }
            },
            confirmButton = {}
        )

        is ImportFolderRequest.Report -> AlertDialog(
            onDismissRequest = { viewModel.dismissImportFolderRequest() },
            title = { Text(importReportTitle(current.result)) },
            text = { ImportReportBody(current) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportFolderRequest() }) { Text("OK") }
            }
        )

        is ImportFolderRequest.Error -> AlertDialog(
            onDismissRequest = { viewModel.dismissImportFolderRequest() },
            title = { Text("Could not import") },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportFolderRequest() }) { Text("OK") }
            }
        )
    }
}

/**
 * Create Tasks, and the refresh of one that already ran.
 *
 * The configuration is edited here and read back here: the dialog owns the draft, and the only thing
 * that crosses back to the view model is the [ImportOptions] the user confirmed. A refresh shows the
 * Source's stored values with nothing editable, because a refresh that changed the grouping would
 * not be re-running this Source, it would be describing a different one.
 *
 * The fields are seeded from `request.options` rather than from a default, so the dialog opens on
 * what the run is actually about — the Source's own name and importer when refreshing, the folder's
 * name and the default grouping when creating.
 *
 * **The draft is saved, so it needs an identity.** Compose restores `rememberSaveable` values by
 * composition position, and the flow that holds this request does not survive the process: if the
 * app dies with this dialog open — or is replaced by an update while its task is retained — the
 * draft stays in the Activity's saved state and would be consumed by the *next* dialog composed at
 * this position, whatever run that dialog describes. That is how a Refresh of a webhook Todo opened
 * on *Don't export*: the seed was right, but a draft saved for an earlier run was restored over it.
 * The run's seed is therefore part of the composition key, so a draft is only restored by the run
 * it was seeded from. Rotation keeps the same request, so it keeps the draft, which is the case
 * saved state exists for.
 */
@Composable
private fun ImportConfirmDialog(
    request: ImportFolderRequest.Confirm,
    defaultWebhookUrl: String,
    onConfirm: (ImportOptions, DissolvedHistory?) -> Unit,
    onDismiss: () -> Unit
) {
    key(importRunIdentity(request)) {
        ImportConfirmDialogBody(request, defaultWebhookUrl, onConfirm, onDismiss)
    }
}

/**
 * The words that identify one run to the dialog's saved state: everything the draft is seeded from.
 *
 * A fingerprint rather than an id because the request is not stored: after a process death there is
 * no previous object to compare with, only whatever the saved state kept and whatever the new run
 * says. Equal fingerprints mean the two runs would have opened the dialog identically, so restoring
 * a draft is safe; different fingerprints mean it is a different run.
 */
internal fun importRunIdentity(request: ImportFolderRequest.Confirm): String {
    val seed = request.options
    return listOf(
        request.refresh,
        request.folderLabel,
        seed.displayName,
        seed.keepTree,
        seed.importer.key,
        seed.contentPolicy.key,
        encodeExportChoice(seed.exportChoice),
        seed.webhookUrl,
        seed.webhookFromDefault,
        seed.followUpstream,
        seed.keepStateOnDelete,
        encodeTemplateAttachments(seed.templates),
        request.histories.joinToString(",") { it.sourceId }
    ).joinToString("\u0000")
}

/** The dialog itself, composed under [importRunIdentity]'s key. */
@Composable
private fun ImportConfirmDialogBody(
    request: ImportFolderRequest.Confirm,
    defaultWebhookUrl: String,
    onConfirm: (ImportOptions, DissolvedHistory?) -> Unit,
    onDismiss: () -> Unit
) {
    // The whole body is keyed by the run above, so this input no longer has to tell one run from
    // the next; it is kept because it is what a field's draft is bound to within a run, and the
    // dialog never describes two runs at once.
    val runKey = request.folderLabel
    val seed = request.options
    var name by rememberSaveable(runKey) { mutableStateOf(seed.displayName) }
    var keepTree by rememberSaveable(runKey) { mutableStateOf(seed.keepTree) }
    var importer by rememberSaveable(runKey) { mutableStateOf(seed.importer) }
    var policy by rememberSaveable(runKey) { mutableStateOf(seed.contentPolicy) }
    // Held as the stored word rather than as the [ExportChoice] itself, for the same reason the
    // attachment draft is held encoded: nothing in that sealed type is a shape the bundle knows,
    // and the word is the identical value the Todo will record.
    var exportWord by rememberSaveable(runKey) {
        mutableStateOf(encodeExportChoice(seed.exportChoice))
    }
    // The two standing answers this dialog writes back to the Todo. They are drafted here rather
    // than read live from the row so a refresh can change them in the same gesture as everything
    // else; a sync never reaches this dialog and keeps whatever the row holds.
    var followUpstream by rememberSaveable(runKey) { mutableStateOf(seed.followUpstream) }
    var keepStateOnDelete by rememberSaveable(runKey) { mutableStateOf(seed.keepStateOnDelete) }
    // Null is "start fresh" and is the seeded answer, so a user who does not read this far gets the
    // Todo they would have got before histories existed.
    var recoverFrom by rememberSaveable(runKey) { mutableStateOf<String?>(null) }

    // The address draft, held as one of three words for the same bundle-shaped reason as the export
    // word above. Refresh opens on *keep* because the Todo already has an answer; Create opens on
    // whichever radio Settings preselected.
    var addressMode by rememberSaveable(runKey) {
        mutableStateOf(
            when {
                request.refresh -> ADDRESS_KEEP
                seed.webhookFromDefault -> ADDRESS_DEFAULT
                else -> ADDRESS_CUSTOM
            }
        )
    }
    // The custom field starts on whatever the Todo holds, so a refresh that moves to *Custom* edits
    // the current address rather than retyping it, and a Create with the default radio off starts
    // blank — there is no address yet to show.
    var customUrl by rememberSaveable(runKey) {
        mutableStateOf(if (request.refresh || !seed.webhookFromDefault) seed.webhookUrl else "")
    }

    // One section is open at a time by default: Grouping and Review are the two a first run must
    // read, and the rest carry a summary line until asked. Every toggle survives rotation for the
    // same reason every draft does — a half-read dialog must come back as it was left.
    var groupingOpen by rememberSaveable(runKey) { mutableStateOf(true) }
    var reviewOpen by rememberSaveable(runKey) { mutableStateOf(true) }
    // Closed by default, because *None* is the ordinary answer and the summary line already says
    // so: an optional page must not make every Create read like a questionnaire.
    var addOnOpen by rememberSaveable(runKey) { mutableStateOf(false) }
    var syncOpen by rememberSaveable(runKey) { mutableStateOf(false) }
    var othersOpen by rememberSaveable(runKey) { mutableStateOf(false) }
    var historyOpen by rememberSaveable(runKey) { mutableStateOf(false) }

    // The attachment draft, held *encoded* rather than as a list of objects, and that is not a
    // workaround for `rememberSaveable` — it is the identical value the run will be given and the
    // Todo will record, so the draft and the write are one expression rather than two that agree.
    // It also survives a rotation without a custom saver, which a `List<TemplateVersionRef>` does
    // not: nothing in that type is a shape the bundle knows.
    var attachments by rememberSaveable(runKey) {
        mutableStateOf(encodeTemplateAttachments(seed.templates))
    }
    val attached = remember(attachments) { decodeTemplateAttachments(attachments) }

    /**
     * One template turned on or off, in the order the user attached them.
     *
     * **A lineage, not a version** — and turning one on *replaces* whatever this Todo held of it
     * rather than joining it. That is the whole of this step's rule: a Todo has a template or it
     * does not, and the version beside it says which one was current when the run happened, not a
     * choice the user is making here. Attaching `T@2` over a recorded `T@1` is how a template that
     * has been edited reaches a Todo that had the older version, and it is the only way to, because
     * the picker offers no version to choose between — see [ImportTemplateAttach].
     *
     * Order is preserved rather than sorted, and that is what decides which template governs a file
     * when two of them admit it — see [TemplateResolver.controlFor]. The dialog refuses to offer two
     * whose scopes overlap at all, so the case is meant to be unreachable from here; keeping the
     * user's order is what makes the rule *the order you attached them* rather than an alphabetical
     * accident, if it is ever reached.
     */
    fun setAttached(choice: StoredTemplateVersion, on: Boolean) {
        attachments = encodeTemplateAttachments(withAttachment(attached, choice, on))
    }

    // A placeholder for a button that will one day start a template from here. Tapping it says so
    // rather than doing nothing, because a control that silently does nothing reads as broken, and
    // what it says points at the page that can do it today.
    var newTemplateTapped by rememberSaveable(runKey) { mutableStateOf(false) }

    // The add-on draft, held encoded for the same bundle-shaped reason as the templates above and
    // for one more: a Todo has *at most one*, so this is a plain reference rather than a list, and
    // `""` is the answer *none*. Seeded from the run: Create starts at none, a refresh starts on
    // whatever the Todo holds.
    var addOnAttachment by rememberSaveable(runKey) {
        mutableStateOf(encodeAddOnAttachment(seed.addOn))
    }
    val attachedAddOn = remember(addOnAttachment) { decodeAddOnAttachment(addOnAttachment) }

    /**
     * One add-on chosen, or the chosen one chosen again to clear it.
     *
     * **A choice, not a set.** There is no scope to reconcile and nothing to order: one page follows
     * the Task, so two questionnaires would have to share it. Picking a second row replaces the
     * first, which is what a radio group says without any extra rule.
     */
    fun setAddOn(choice: StoredAddOnVersion, on: Boolean) {
        addOnAttachment = encodeAddOnAttachment(withAddOn(attachedAddOn, choice, on))
    }

    var newAddOnTapped by rememberSaveable(runKey) { mutableStateOf(false) }

    val method = decodeExportChoice(exportWord, inheritable = false)
    val address = when (addressMode) {
        ADDRESS_KEEP -> seed.webhookUrl.trim()
        ADDRESS_DEFAULT -> defaultWebhookUrl.trim()
        else -> customUrl.trim()
    }
    val addressFromDefault = when (addressMode) {
        ADDRESS_KEEP -> seed.webhookFromDefault
        ADDRESS_DEFAULT -> true
        else -> false
    }
    // Only a webhook method can refuse on the address: a Todo that writes txt or sends nothing has
    // no destination to be wrong, and the address is a standing answer it may carry unspent.
    val addressProblem = if (method == ExportChoice.By(ExportKind.WEBHOOK)) {
        webhookAddressProblem(ExportKind.WEBHOOK, address)
    } else {
        null
    }
    val versionNotice = if (request.refresh) {
        refreshVersionNotice(attached, request.templates)
    } else {
        null
    }
    // Read from the draft for the same reason the template notice is: unticking the add-on
    // withdraws the sentence along with the write it describes.
    val addOnNotice = if (request.refresh) {
        refreshAddOnNotice(attachedAddOn, request.addOns)
    } else {
        null
    }
    // Resolved here rather than in the ViewModel, because the id is what survives a configuration
    // change and the object is what the report needs. The selection cannot go stale: the histories
    // are re-read from the same flow that opened this dialog, and a history is never consumed.
    val selectedHistory = request.histories.firstOrNull { it.sourceId == recoverFrom }

    /**
     * Confirm, or open the section that is holding it back.
     *
     * The two gates used to be expressed as a disabled button. They are still gates — nothing is
     * submitted with no template, or with a webhook method and no usable address — but a disabled
     * button cannot say *why*, and its reason may live in a section the user folded shut. So the
     * button presses, the offending section opens, and the error is on screen beside the control
     * that can fix it.
     */
    fun confirm() {
        if (attached.isEmpty()) {
            reviewOpen = true
            return
        }
        if (addressProblem != null) {
            syncOpen = true
            return
        }
        onConfirm(
            ImportOptions(
                importer = importer,
                displayName = name,
                keepTree = keepTree,
                contentPolicy = policy,
                templates = attached,
                // Carried from the run the dialog was opened for: a refresh adopts the offered
                // version of what it still holds; Create has already picked current versions and
                // must not "upgrade" anything.
                upgradeTemplates = seed.upgradeTemplates,
                // Not part of the gate: an add-on is optional, so an empty page is a valid run.
                addOn = attachedAddOn,
                upgradeAddOn = seed.upgradeAddOn,
                followUpstream = followUpstream,
                keepStateOnDelete = keepStateOnDelete,
                webhookUrl = address,
                webhookFromDefault = addressFromDefault,
                exportChoice = method
            ),
            selectedHistory
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.refresh) "Refresh import?" else "Create tasks?") },
        text = {
            // Scrollable, and it has to be: five sections make this the tallest dialog in the app,
            // and an `AlertDialog` gives its text slot a bounded height. A plain `Column` that
            // overflows is not scrolled and not clipped — its children are *squeezed*, so the last
            // rows collapse to nothing and the picker offers an option with no words on it.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Scope first, folder second: what is about to be read is the number the user is
                // deciding on, and the folder names the thing it is being read out of. It also
                // settles the verb, which no longer has to be "register" — by the time this dialog
                // opens from an Upstream, that Upstream is already a row.
                Text(
                    (if (request.refresh) "Listea will re-read " else "Listea will read ") +
                        importScope(request) + " from " + request.folderLabel + "."
                )
                // A notice, not a warning. Running Create Tasks over a folder that already has a
                // Todo is how a second intention is expressed — the same folder supplying a second
                // dish — so the dialog says what is there and goes on to allow it. Saying it before
                // the button is pressed is the cheap moment; finding two cards on the Todo tab
                // afterwards is the expensive one.
                if (request.existingTodos.isNotEmpty()) {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "This folder already has " + todoCount(request.existingTodos.size) +
                            ": " + request.existingTodos.joinToString(", ") { "\"" + it + "\"" } +
                            ". Creating Tasks here makes another, with Tasks of its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(ListeaDimens.RowGap))
                if (request.refresh) {
                    // A refresh cannot change what the Source *is*: its name and its grouping are
                    // the row's identity, and changing them here would describe a different Source
                    // wearing this one's row. Everything that is a standing answer about what the
                    // Todo does — its policy, its method, its address, its follow-upstream — is
                    // still editable below, because those are what a refresh re-stamps.
                    Text(
                        "Re-importing under " + importer.key + ", the grouping this Source already " +
                            "has. Nothing is deleted; folders that lost files are flagged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    ImportSection(
                        title = "Grouping",
                        summary = groupingSummary(importer, keepTree),
                        expanded = groupingOpen,
                        onToggle = { groupingOpen = !groupingOpen }
                    ) {
                        ImportImporterChoice(
                            selected = importer,
                            onSelect = {
                                importer = it
                                // The box disappears for folder-as-Task, so it must not stay ticked
                                // behind it: the stored value goes through normalizeKeepTree either
                                // way, and this keeps what the user saw when they pressed Create the
                                // same as what the Source ends up with.
                                if (it == ImporterKind.FOLDER_AS_TASK) keepTree = false
                            },
                            // Nested directly under *One Task per file*, because it is the one
                            // importer with a tree to keep: folder-as-Task's Tasks are one per
                            // folder and its catalog is flat whatever this would say.
                            underFileChoice = {
                                if (importer == ImporterKind.FILE_AS_TASK) {
                                    ImportKeepTreeChoice(
                                        keepTree = keepTree,
                                        onToggle = { keepTree = !keepTree }
                                    )
                                }
                            }
                        )
                    }
                }
                // Drawn for a refresh as well, and it is the one control here that is. The rule
                // this dialog keeps on a refresh — the name and the grouping are not editable — is
                // about what the Source *is*: changing them would describe a different Source
                // wearing this one's row. What happens when a Task's content changes is not that;
                // it is a statement about re-importing, which is precisely what a refresh does, and
                // it is written back so the answer stands for the next run too.
                ImportSection(
                    title = "Review",
                    summary = reviewSummary(policy, attached.size),
                    expanded = reviewOpen,
                    onToggle = { reviewOpen = !reviewOpen }
                ) {
                    ContentPolicyChoice(
                        selected = policy,
                        onSelect = { policy = it }
                    )
                    // Drawn for a refresh too, and for the same reason the policy above is: a
                    // template is not a fact about the Source, it is what this run is to be given,
                    // and a refresh is exactly the run that re-stamps every Task it touches.
                    //
                    // It used to draw one tag editor per kind the folder held. It attaches whole
                    // templates now, which is the change v0.5.4 makes: a button's name and its wire
                    // value are edited on the template's own page, and what a run chooses is *which*
                    // template governs its files. So the step is the same one whatever the folder
                    // holds — a folder of PDFs is still offered the templates, and attaching one is
                    // how a user says so; whether any of them admits a PDF is the resolver's answer
                    // to give, not this dialog's to pre-empt.
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    ImportTemplateAttach(
                        choices = request.templates,
                        attached = attached,
                        newTemplateTapped = newTemplateTapped,
                        onToggle = ::setAttached,
                        onNewTemplate = { newTemplateTapped = true }
                    )
                    // What moving a held version does to marks that already exist, said before the
                    // button is pressed. Read from the draft rather than from the row, so unticking
                    // the moved template withdraws the warning together with the write it describes.
                    if (versionNotice != null) {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text(
                            versionNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // The Task-level question, beside the file-level ones rather than among them: an
                // add-on asks about the whole Task, so it gets its own section and its own page.
                // It is drawn for a refresh too, because a refresh re-stamps every Task it touches
                // and this is the run that may carry the Todo's add-on forward.
                ImportSection(
                    title = "Add an add-on",
                    summary = addOnSummary(request.addOns, attachedAddOn),
                    expanded = addOnOpen,
                    onToggle = { addOnOpen = !addOnOpen }
                ) {
                    ImportAddOnAttach(
                        choices = request.addOns,
                        attached = attachedAddOn,
                        newAddOnTapped = newAddOnTapped,
                        onToggle = ::setAddOn,
                        onNewAddOn = { newAddOnTapped = true }
                    )
                    if (addOnNotice != null) {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text(
                            addOnNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // What a round leaves behind, and where it goes. The method is the Todo's own
                // standing choice — a List may still override the address, and *Inherit* is a
                // List's word, not a Todo's — and the address beneath it is the value this run
                // snapshots onto the row.
                ImportSection(
                    title = "Sync",
                    summary = exportChoiceSummary(method, address),
                    expanded = syncOpen,
                    onToggle = { syncOpen = !syncOpen }
                ) {
                    ExportChoiceRow(
                        selected = method,
                        onSelect = { exportWord = encodeExportChoice(it) }
                    )
                    if (method == ExportChoice.By(ExportKind.WEBHOOK)) {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text("Webhook address", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "This Todo keeps the address it is given. A later change to the " +
                                "default in Settings does not move it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (request.refresh) {
                            ImportAddressRow(
                                label = "Keep this Todo's address",
                                detail = when {
                                    seed.webhookUrl.isNotBlank() ->
                                        webhookHost(seed.webhookUrl) ?: "address not readable"
                                    else -> "No address is set on this Todo"
                                },
                                selected = addressMode == ADDRESS_KEEP,
                                onSelect = { addressMode = ADDRESS_KEEP }
                            )
                        }
                        ImportAddressRow(
                            label = "Use default",
                            detail = if (defaultWebhookUrl.isBlank()) {
                                "No default address is set in Settings"
                            } else {
                                webhookHost(defaultWebhookUrl) ?: defaultWebhookUrl
                            },
                            selected = addressMode == ADDRESS_DEFAULT,
                            onSelect = { addressMode = ADDRESS_DEFAULT }
                        )
                        ImportAddressRow(
                            label = "Custom",
                            detail = "Posts to an address you type here",
                            selected = addressMode == ADDRESS_CUSTOM,
                            onSelect = { addressMode = ADDRESS_CUSTOM }
                        )
                        if (addressMode == ADDRESS_CUSTOM) {
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it },
                                label = { Text("Address") },
                                singleLine = true,
                                isError = addressProblem != null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // The same string `applyImport` would refuse on, shown before the button
                        // rather than after it in an error dialog that wrote nothing.
                        if (addressProblem != null) {
                            Spacer(Modifier.height(ListeaDimens.CompactGap))
                            Text(
                                addressProblem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                    }
                    SwitchRow(
                        label = "Follow the upstream",
                        checked = followUpstream,
                        onCheckedChange = { followUpstream = it }
                    )
                    Text(
                        "Scan this Todo's folder when it opens and import what appeared, without " +
                            "asking. This is a standing answer about the Todo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ImportSection(
                    title = "Others",
                    summary = othersSummary(keepStateOnDelete),
                    expanded = othersOpen,
                    onToggle = { othersOpen = !othersOpen }
                ) {
                    SwitchRow(
                        label = "Keep decisions when deleted",
                        checked = keepStateOnDelete,
                        onCheckedChange = { keepStateOnDelete = it }
                    )
                    Text(
                        "Deleting this Todo keeps its Tasks and their decisions instead of " +
                            "letting them go with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Read outside the grouping branch, because a history says nothing about grouping:
                // what it holds is matched against the new Todo's own `groupPath`s, and a history
                // whose grouping differs simply matches none of them. Only Create Tasks sets it —
                // see [ListsViewModel.requestSourceRefresh] — so this never draws for a refresh.
                if (request.histories.isNotEmpty()) {
                    ImportSection(
                        title = "History",
                        summary = selectedHistory?.sourceName?.ifBlank { "Untitled Todo" }
                            ?: "Start fresh",
                        expanded = historyOpen,
                        onToggle = { historyOpen = !historyOpen }
                    ) {
                        ImportHistoryChoice(
                            histories = request.histories,
                            selected = recoverFrom,
                            onSelect = { recoverFrom = it }
                        )
                    }
                }
                // Said once more beside the button, because it is a change to material that already
                // exists rather than a choice about new material. The section can be folded shut;
                // this line cannot.
                if (addOnNotice != null) {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        addOnNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }) {
                Text(if (request.refresh) "Refresh" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One foldable step of the Create Tasks dialog: a name, the answer it is currently holding, and its
 * controls when opened.
 *
 * The summary line is the point of folding. A section that collapsed to its title would make the
 * user open it to find out what it says; a header that reads *Webhook · n8n.example* answers the
 * question without a tap, and opening it is then only about changing the answer.
 */
@Composable
private fun ImportSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = ListeaDimens.CompactGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            content()
            Spacer(Modifier.height(ListeaDimens.CompactGap))
        }
        HorizontalDivider()
    }
}

/** One address radio in the Sync section: a word for the choice and what that choice resolves to. */
@Composable
private fun ImportAddressRow(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Which address the draft submits; stored as a word so `rememberSaveable` can keep it. */
private const val ADDRESS_KEEP = "keep"
private const val ADDRESS_DEFAULT = "default"
private const val ADDRESS_CUSTOM = "custom"

private fun groupingSummary(importer: ImporterKind, keepTree: Boolean): String = when (importer) {
    ImporterKind.FILE_AS_TASK ->
        if (keepTree) "One Task per file · keep the folder tree" else "One Task per file"
    ImporterKind.FOLDER_AS_TASK -> "One Task per folder"
}

private fun reviewSummary(policy: ContentPolicy, templates: Int): String {
    val templatesWord = when (templates) {
        0 -> "no template"
        1 -> "1 template"
        else -> "$templates templates"
    }
    return policy.label + " · " + templatesWord
}

private fun exportChoiceSummary(choice: ExportChoice, address: String): String = when (choice) {
    ExportChoice.Nothing -> "Don't export"
    ExportChoice.Inherit -> "Follow the List"
    is ExportChoice.By -> when (choice.kind) {
        ExportKind.WEBHOOK -> "Webhook · " + (
            webhookHost(address)
                ?: if (address.isEmpty()) "no address set" else "address not readable"
            )
        ExportKind.TXT -> "TXT · one file per round"
    }
}

private fun othersSummary(keepStateOnDelete: Boolean): String =
    if (keepStateOnDelete) "Keep decisions when deleted" else "Decisions go with the Todo"

/**
 * The attach step: which templates govern the files this run is about to create Tasks over.
 *
 * **The successor to the per-kind tag editors that stood here**, and the shape of the change
 * v0.5.4 makes. Those drew one editor per kind the scan found and let a user rename and re-wire a
 * tag inside the dialog; this draws the templates that exist and lets a user say which of them
 * apply. Editing a button moved to the template's own page, and what happens *here* is the choice
 * that page cannot make — this Todo, these files, this template.
 *
 * **The step is drawn whatever the folder holds.** The editors it replaced were drawn only for
 * kinds the folder actually had, because a tag edited for a kind that is not there would be a
 * question about nothing. That rule belonged to a per-kind control, not to this one: a template
 * governs a *set* of kinds, so "is any of this folder's material admitted" is a question about the
 * templates and their scopes, and a dialog that hid the step would be answering it silently. A
 * folder of PDFs is offered them and the user decides.
 *
 * **Two whose scopes overlap cannot both be chosen**, and the second one is shown disabled with the
 * reason rather than hidden. *First attached wins* is the resolver's rule and is the right one for
 * a model; it is not one to hand a user, who would get two chips for one file and no way to tell
 * which template drew which. Disabled rather than absent, because a template that vanishes from a
 * list is a template the user thinks was deleted — the same reason this codebase says why a control
 * is unavailable instead of leaving it out.
 *
 * **A row is a template, not a version of one.** The line under a name says what it applies to and
 * how many buttons it has, and never offers a version to pick between, because a version is not a
 * usage: it is how a template was when a run happened, kept so a Task can still be read back. A
 * Todo has *this template* or it does not. Attaching is one per lineage by construction — see
 * [setAttached] — so the overlap rule above, which reads its answer off what is ticked, can no
 * longer be shown a conflict it cannot see.
 *
 * **A template this Todo holds at an older version is ticked, and says so.** That is the one case
 * where the version is on screen, and it is there because the alternative is a lie: the row would
 * read as *not attached* for a template the Todo is attached to, and ticking it to fix that would
 * write a second reference rather than move the first. The line names both versions, so a refresh
 * that is about to carry a Todo forward says so before it does rather than after.
 *
 * **`New template` points at Settings, and the picker is a snapshot.** Since v0.6.0 a template can
 * be started under Settings → Templates, so the button says where rather than pretending to create
 * one here. The list it is drawn from was read when the folder was, and this dialog does not observe
 * the tables: a template created while it is open appears only when it is closed and reopened. That
 * is stated rather than hidden, because a picker that silently omitted a template the user just
 * made would read as the create having failed.
 */
@Composable
private fun ImportTemplateAttach(
    choices: List<StoredTemplateVersion>,
    attached: List<TemplateVersionRef>,
    newTemplateTapped: Boolean,
    onToggle: (StoredTemplateVersion, Boolean) -> Unit,
    onNewTemplate: () -> Unit
) {
    // By lineage, not by reference — see [heldVersionOf], which is where that reading lives so that
    // it can be tested. A Todo holding version 1 *is* attached to a picker offering version 2.
    val attachedLineages = attached.mapTo(mutableSetOf()) { it.templateId }
    // Read off the ticked rows rather than off `attached`, and the two are the same set by different
    // routes: a ticked row is a lineage the Todo holds, and [choices] carries its *current* version —
    // which is the version a run writes, so it is the scope that governs the files. A stored
    // reference to an older version deliberately contributes nothing here, because leaving the row
    // ticked replaces it.
    val attachedScopes = choices.filter { it.ref.templateId in attachedLineages }.map { it.scope }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Add a template",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNewTemplate) { Text("New template") }
        }
        Text(
            "A template decides which buttons this Todo's files offer. Its buttons are edited on " +
                "the template's own page under Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (newTemplateTapped) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "Templates are created under Settings → Templates. This picker was read when the " +
                    "folder was, so close and reopen this dialog after creating one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (choices.isEmpty()) {
            // Unreachable on a real install — every database has the built-in — and said rather than
            // left blank, because a step that draws nothing is a step the user cannot tell from one
            // that failed to load.
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "There are no templates to attach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        choices.forEach { choice ->
            val held = heldVersionOf(attached, choice.ref.templateId)
            val isAttached = held != null
            val clash = !isAttached &&
                attachedScopes.any { scopesOverlap(it, choice.scope) }
            ImportTemplateRow(
                choice = choice,
                isAttached = isAttached,
                clash = clash,
                // The version this Todo holds of the template this row is, when it is not the one
                // the row would attach. Null in the two ordinary cases, and in the clash case, which
                // is not attached to anything.
                heldVersion = held?.takeIf { it != choice.ref.version },
                onToggle = { onToggle(choice, it) }
            )
        }
    }
}

/** One template, as a tick and what ticking it would mean. */
@Composable
private fun ImportTemplateRow(
    choice: StoredTemplateVersion,
    isAttached: Boolean,
    clash: Boolean,
    heldVersion: Int?,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isAttached,
            // A clashing template is refused rather than unluckily resolved. See
            // [ImportTemplateAttach] for why it is shown at all.
            enabled = !clash,
            onCheckedChange = onToggle
        )
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (clash) {
                    "Governs the same files as one already attached"
                } else {
                    templateScopeLabel(choice.scope) + " · " +
                        countLabel(choice.buttons.size, "button")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The one place a version is said out loud, and it is said because the run is about to
            // move the Todo and the user should know before it does rather than after. The row is
            // already ticked, so there is nothing to press: leaving it alone *is* the instruction,
            // and unticking it is the way to refuse.
            if (heldVersion != null) {
                Text(
                    "Held at version $heldVersion. This run will attach version " +
                        choice.ref.version + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * What the add-on section says while folded: the name it would attach, the name the Todo holds, or
 * *None*.
 *
 * The held reference can name an add-on this build cannot read — a Delete never happens, but a
 * version can be written by a newer build. Then there is no row to tick and no name to show, and the
 * summary says *Attached* rather than *None*, because the Todo does have one and reading it is a
 * failure rather than an absence.
 */
private fun addOnSummary(
    choices: List<StoredAddOnVersion>,
    attached: AddOnVersionRef?
): String {
    if (attached == null) return "None"
    return choices.firstOrNull { it.ref.addOnId == attached.addOnId }?.name ?: "Attached"
}

/**
 * The add-on step: which questionnaire, if any, follows every Task this run creates.
 *
 * **A radio group, not a tick list**, because a Task has one page after its last file and two
 * questionnaires would have to share it. Choosing the selected row again clears the answer, which is
 * how *None* is reached — there is no separate None row, so the same gesture that chose an add-on
 * takes it back.
 *
 * **A row is an add-on, not a version of one.** *N questions* is the line under the name; a version is
 * how the add-on was when a run happened, not a usage. The one place a version is said out loud is
 * the held line on a refresh, because that is the run about to move the Todo, and the user should
 * read it before pressing rather than find it in the Tasks afterwards.
 *
 * **`New add-on` points at Settings, and the picker is a snapshot.** Add-ons are created under
 * Settings → Templates & Add-ons, and the rows here were read when the folder was: one created while
 * this dialog is open appears when it is closed and reopened. That is said rather than hidden, because
 * a picker that silently omitted what the user just made would read as the create having failed.
 */
@Composable
private fun ImportAddOnAttach(
    choices: List<StoredAddOnVersion>,
    attached: AddOnVersionRef?,
    newAddOnTapped: Boolean,
    onToggle: (StoredAddOnVersion, Boolean) -> Unit,
    onNewAddOn: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Add an add-on",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNewAddOn) { Text("New add-on") }
        }
        Text(
            "An add-on asks about the whole Task rather than any one file, and appears as one " +
                "page after the last file. It is never required: the Task completes with it " +
                "answered, partly answered or empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (newAddOnTapped) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "Add-ons are created under Settings → Templates & Add-ons. This picker was read " +
                    "when the folder was, so close and reopen this dialog after creating one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (choices.isEmpty()) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "There are no add-ons to attach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        choices.forEach { choice ->
            val isAttached = attached?.addOnId == choice.ref.addOnId
            ImportAddOnRow(
                choice = choice,
                isAttached = isAttached,
                // The version this Todo holds of the add-on this row is, when the row would move
                // it. Null for every ordinary row and for the one that is not held at all.
                heldVersion = attached
                    ?.takeIf { it.addOnId == choice.ref.addOnId && it.version != choice.ref.version }
                    ?.version,
                onToggle = { onToggle(choice, it) }
            )
        }
        // The held reference names a lineage no row offers — an add-on this build cannot read.
        // Picking any row replaces it; there is nothing to keep, and nothing here can show its name.
        if (attached != null && choices.none { it.ref.addOnId == attached.addOnId }) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "The add-on this Todo currently holds is not in this list, so this build cannot " +
                    "read it. Choosing another replaces it; leaving the section alone keeps it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One add-on, as a radio and what choosing it would give every Task. */
@Composable
private fun ImportAddOnRow(
    choice: StoredAddOnVersion,
    isAttached: Boolean,
    heldVersion: Int?,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isAttached) }
            .padding(vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Re-tapping the selected radio clears it, which is the gesture that reaches *None*.
        RadioButton(selected = isAttached, onClick = { onToggle(!isAttached) })
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Add-on · " + countLabel(choice.questions.size, "question"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (heldVersion != null) {
                Text(
                    "Held at version $heldVersion. This run will attach version " +
                        choice.ref.version + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "one Todo" / "3 Todos", so the notice above reads as a sentence rather than as a form field. */
/** What a run would cover, as a phrase. Says both counts because either can be the surprising one. */
internal fun importScope(request: ImportFolderRequest.Confirm): String =
    countLabel(request.fileCount, "file") + " in " + countLabel(request.folderCount, "folder")

/**
 * The one choice this release turns on, said as plainly as it can be.
 *
 * Two choices rather than a dropdown: there are exactly two rules, they are not settings, and the
 * difference between them — one Task per file, or one Task per child folder — is the sentence the
 * user has to read in order to answer.
 */
@Composable
private fun ImportImporterChoice(
    selected: ImporterKind,
    onSelect: (ImporterKind) -> Unit,
    underFileChoice: @Composable () -> Unit = {}
) {
    Column {
        ImportImporterRow(
            label = "One Task per file",
            detail = "file-as-task · every file the scan finds becomes its own Task",
            selected = selected == ImporterKind.FILE_AS_TASK,
            onSelect = { onSelect(ImporterKind.FILE_AS_TASK) }
        )
        // Anything that *only* this rule has to say sits between the two rows. A setting that
        // belongs to one of two choices, drawn after both, reads as a third option belonging to
        // neither: keep-the-tree was the last line of the section and nobody could tell it was
        // about *One Task per file*.
        underFileChoice()
        ImportImporterRow(
            label = "One Task per folder",
            detail = "folder-as-task · each child folder becomes one Task of its pictures, " +
                "videos and PDFs",
            selected = selected == ImporterKind.FOLDER_AS_TASK,
            onSelect = { onSelect(ImporterKind.FOLDER_AS_TASK) }
        )
    }
}

/**
 * What a Todo does when one of its Tasks' content changes — the tier chooser.
 *
 * Shared by the two places the question is asked: the import dialog, where the answer is about the
 * run in front of the user and is written back to the Todo, and Settings, where it is the answer
 * new Todos start with. The three sentences are the same in both because the question is.
 *
 * Three rows rather than a dropdown, for the same reason [ImportImporterChoice] is two: the
 * difference between them is what the user has to read in order to answer, and it is not a word
 * they already know. The order is the order of consequence — keep changes nothing, reset clears a
 * judgment, drop removes a Task from the Lists that hold it — so a user who reads only the first
 * row is a user who chose the safe answer.
 *
 * The three sentences are written in terms of what the user will *see*, not in terms of the
 * mechanics: a policy named "drop" with a description about Task identities would be this app
 * talking to itself.
 */
@Composable
internal fun ContentPolicyChoice(
    selected: ContentPolicy,
    onSelect: (ContentPolicy) -> Unit
) {
    Column {
        Text(
            "When a Task's content changes",
            style = MaterialTheme.typography.bodyMedium
        )
        ImportImporterRow(
            label = ContentPolicy.KEEP.label,
            detail = "keep · the Task and its ticks stay; material that has gone is flagged",
            selected = selected == ContentPolicy.KEEP,
            onSelect = { onSelect(ContentPolicy.KEEP) }
        )
        ImportImporterRow(
            label = ContentPolicy.RESET.label,
            detail = "reset · the Task stays and its ticks clear, so moved content is judged again",
            selected = selected == ContentPolicy.RESET,
            onSelect = { onSelect(ContentPolicy.RESET) }
        )
        ImportImporterRow(
            label = ContentPolicy.DROP.label,
            detail = "drop · a Task that changed is replaced, and Lists that held it lose it",
            selected = selected == ContentPolicy.DROP,
            onSelect = { onSelect(ContentPolicy.DROP) }
        )
    }
}

/**
 * Where this Todo's decisions go when a round over it ends.
 *
 * **A single choice, and that is the model rather than a simplification.** The plural case is real
 * — a List holding Tasks from several Todos exports more than once, because each Todo answers for
 * its own — but it is a sum over the Todos a round touched, computed when the round ends, and not
 * something any one row holds.
 *
 * The same rows-in-a-column shape as [ContentPolicyChoice], and for its reason: the difference
 * between the answers is what a user has to read in order to give one, and *webhook* and *txt* are
 * not words that explain themselves. Each detail line says what will actually appear, because a
 * user choosing between two mechanisms is really choosing between a POST somewhere and a file in
 * their album.
 *
 * **Three answers where the spec named two.** *Don't export* is not decoration: a two-option group
 * has no way to say *this Todo exports nothing*, and *nothing* has to be sayable — it is the
 * default for a new Todo, the answer for a stored word this build cannot read, and the only way to
 * correct a choice made at import. Every other control in this dialog carries its neutral answer,
 * so a group without one would be the only thing here a user cannot unset.
 */
@Composable
internal fun ExportChoiceRow(
    selected: ExportChoice,
    onSelect: (ExportChoice) -> Unit
) {
    Column {
        Text(
            "When a round over this Todo finishes",
            style = MaterialTheme.typography.bodyMedium
        )
        ImportImporterRow(
            label = "Don't export",
            detail = "nothing leaves the app from this Todo",
            selected = selected == ExportChoice.Nothing,
            onSelect = { onSelect(ExportChoice.Nothing) }
        )
        ExportKind.entries.forEach { kind ->
            ImportImporterRow(
                label = kind.label,
                detail = exportKindDetail(kind),
                selected = selected == ExportChoice.By(kind),
                onSelect = { onSelect(ExportChoice.By(kind)) }
            )
        }
    }
}

/**
 * What choosing one mechanism will actually look like, in the user's terms rather than the wire's.
 *
 * The txt line spells the folder out of the constant it is written to rather than repeating it, so
 * a user reading this is told where their files really go. It said `DCIM/Listea/…` while the
 * writer had already moved — which is how a screen can be wrong about a destination nobody can
 * reach.
 */
private fun exportKindDetail(kind: ExportKind): String = when (kind) {
    ExportKind.WEBHOOK -> "webhook · POSTs the round to the address set in Settings › Exports"
    ExportKind.TXT -> "txt · writes one file per round into $ListeaExportRelativePath/"
}

/**
 * Whether the Todo keeps the folder tree, offered only where the importer can honour it.
 *
 * Shown for file-as-Task alone, because folder-as-Task has no tree to keep: its Tasks are one per
 * child folder and its catalog is flat whatever this says. Hiding the box is a courtesy, not the
 * rule — [normalizeKeepTree] is the rule, and it holds however the box was left.
 */
@Composable
private fun ImportKeepTreeChoice(keepTree: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Aligned under the *One Task per file* row's own text, not under its radio button:
            // this is that rule's sub-option, and an option drawn flush with its parents is a
            // third rule.
            .padding(start = 48.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = keepTree, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Column {
            Text("Keep the folder tree", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Off flattens this Todo: every Task in one list, no folders to open. " +
                    "The Tasks themselves are the same either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The offer to start this Todo from what a deleted one already knew.
 *
 * Shown only when there is something to offer, so a folder that has never had a Todo dissolved
 * opens exactly the dialog it opened before. "Start fresh" is a row rather than the absence of one:
 * the choice is between histories and not having one, and a list of alternatives with no way to
 * decline would read as a decision already made.
 *
 * One history at a time, and never a mixture — you can only load what one dissolution recorded,
 * because a recovery is about a *place* rather than about individual files, and two histories at one
 * folder are two different times at that place. See the step 2 plan's Recovery section.
 */
@Composable
private fun ImportHistoryChoice(
    histories: List<DissolvedHistory>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Column {
        Text("Decisions left behind", style = MaterialTheme.typography.bodyMedium)
        Text(
            "A Todo deleted at this folder kept what it had judged. Loading one gives this Todo's " +
                "Tasks those decisions — matched by path, so a history recorded under a different " +
                "grouping has nothing to say to this one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ImportImporterRow(
            label = "Start fresh",
            detail = "no decisions loaded; every Task begins unjudged",
            selected = selected == null,
            onSelect = { onSelect(null) }
        )
        for (history in histories) {
            ImportImporterRow(
                label = history.sourceName.ifBlank { "Untitled Todo" },
                detail = countLabel(history.taskCount, "Task") + " judged · deleted " +
                    formatTimestamp(history.dissolvedAt),
                selected = selected == history.sourceId,
                onSelect = { onSelect(history.sourceId) }
            )
        }
    }
}

@Composable
private fun ImportImporterRow(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The one-line verdict, in the vocabulary §5.5 defines. */
internal fun importReportTitle(result: ImportResult): String = when (result.outcome) {
    ImportOutcome.COMPLETE -> "Import complete"
    ImportOutcome.PARTIAL -> "Import finished with conflicts"
    ImportOutcome.FAILED -> "Import failed"
}

/**
 * What the run did.
 *
 * Every line is something the run actually counted, and the two omissions are deliberate: a zero is
 * not printed, because "0 adopted" is noise on every run that adopts nothing; and notices are
 * counted rather than listed, because a folder of unsupported files is one fact about the importer's
 * policy rather than one fact per file. Conflicts are the exception and are named — they are the one
 * outcome here the user has to do something about.
 *
 * A recovery is the other exception, and for the same reason in reverse: it is never silent, because
 * it is never automatic. Nobody gets a recovery they did not choose, so a chosen one that copied
 * nothing is an answer owed rather than a zero worth suppressing.
 */
@Composable
private fun ImportReportBody(current: ImportFolderRequest.Report) {
    val result = current.result
    // Scrollable for the reason `ImportConfirmDialog`'s text slot is: this is the other dialog that
    // can outgrow `AlertDialog`'s bounded slot, and an overflowing `Column` there is squeezed rather
    // than clipped — the last lines collapse and the report loses exactly the part a long run has
    // most to say with: the conflicts it is naming, and the notices underneath them.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        val lines = buildList {
            if (result.tasksCreated > 0) add(countLabel(result.tasksCreated, "Task") + " created")
            if (result.tasksAdopted > 0) add(countLabel(result.tasksAdopted, "Task") + " adopted")
            // A reset is counted in the reconciled total as well, because the Task really was
            // brought up to date and really did keep its identity — so it is said *inside* that
            // line rather than beside it. Two counters reading "2 Tasks updated" and "1 Task
            // started over" would leave the user to work out whether the one was one of the two.
            if (result.tasksReconciled > 0) {
                add(
                    countLabel(result.tasksReconciled, "Task") + " updated" +
                        if (result.tasksReset > 0) {
                            " · " + countLabel(result.tasksReset, "of them") + " started over"
                        } else {
                            ""
                        }
                )
            } else if (result.tasksReset > 0) {
                // The same outcome with nothing to reconcile: a Task whose content has all gone is
                // in no plan, so there is no "them" for it to have been one of.
                add(countLabel(result.tasksReset, "Task") + " started over — their content changed")
            }
            // Not folded into anything: a drop is the one outcome here that removes a Task and
            // every List membership it held, and a count that reads like bookkeeping would hide it.
            if (result.tasksDropped > 0) {
                add(countLabel(result.tasksDropped, "Task") + " replaced — their content changed")
            }
            // Only the non-zero half belongs among the counts. A recovery that reached nothing is
            // a sentence rather than a number, and it is said below.
            if (result.tasksRecovered > 0) {
                add(countLabel(result.tasksRecovered, "Task") + " recovered")
            }
            if (result.assetsCreated > 0) add(countLabel(result.assetsCreated, "Asset") + " added")
            if (result.assetsUpdated > 0) add(countLabel(result.assetsUpdated, "Asset") + " updated")
            if (result.assetsMarkedMissing > 0) {
                add(countLabel(result.assetsMarkedMissing, "Asset") + " missing from the source")
            }
        }
        if (lines.isEmpty()) {
            Text("Nothing changed.")
        } else {
            lines.forEach { Text(it) }
        }

        // The recovery's other answer, and the reason it is not one of the counts above: "nothing"
        // is not a number. A chosen history can reach nothing at all — its grouping rule may have no
        // path in common with this run's — and without this sentence that outcome would be
        // indistinguishable from a run that was never offered a history.
        if (result.tasksRecovered == 0 && current.recoveredFrom != null) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                "Nothing was recovered from \"" + current.recoveredFrom + "\": no Task here " +
                    "matches a path it recorded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (result.conflicts.isNotEmpty()) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                countLabel(result.conflicts.size, "file") + " already belong to another Task:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            result.conflicts.take(REPORT_CONFLICT_PREVIEW).forEach { conflict ->
                Text("- " + conflict.relativePath + " (" + conflict.ownerLabel + ")")
            }
            if (result.conflicts.size > REPORT_CONFLICT_PREVIEW) {
                Text(
                    "and " + (result.conflicts.size - REPORT_CONFLICT_PREVIEW) + " more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val unsupported = result.notices.filterIsInstance<ImportNotice.UnsupportedFile>().size
        val emptyFolders = result.notices.filterIsInstance<ImportNotice.EmptyFolder>().size
        if (unsupported > 0 || emptyFolders > 0) {
            Spacer(Modifier.height(ListeaDimens.RowGap))
            if (unsupported > 0) {
                Text(
                    countLabel(unsupported, "file") + " this grouping does not import were skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (emptyFolders > 0) {
                Text(
                    countLabel(emptyFolders, "folder") + " held nothing this grouping imports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val REPORT_CONFLICT_PREVIEW = 5

/**
 * The path as a row of tappable ancestors. This is how you go up: every segment above the one you
 * are in navigates straight to it, reusing the same browsing stack the rest of the screen uses,
 * so nothing can drift out of the Upstream's folder. The current folder is the end of the line
 * and is not a link.
 */
@Composable
private fun Breadcrumb(
    stack: List<DirRef>,
    onNavigate: (Int) -> Unit,
    /**
     * What the first entry is called: the Upstream's own name, which is the same word the card that
     * opened this used, so the screen and the card agree about what this place is.
     */
    baseLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stack.forEachIndexed { index, dir ->
            val isCurrent = index == stack.lastIndex
            if (index > 0) {
                Text(
                    " / ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                dir.name.ifEmpty { if (index == 0) baseLabel else "Root" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = if (isCurrent) {
                    Modifier
                } else {
                    Modifier.clickable { onNavigate(index + 1) }
                }
            )
        }
    }
}

/**
 * What this folder is on the left, and what can be done with it on the right.
 *
 * The information is strictly display: nothing in it is tappable, so a status can never be
 * mistaken for a button. The counts are the folder's direct children only — the listing already
 * in memory — and never a recursive walk.
 *
 * Refresh re-reads the folder and Create Tasks is how what is in it becomes Tasks at all, so both
 * belong to the material. Quick Review and Create List are about Tasks and live in Todo now, where
 * every row is one an import already wrote. Open List stays because it makes nothing: it is a way
 * back to a List this folder is already part of, and a status line saying a folder belongs to
 * something that could not then be opened would be the card telling half a story.
 */
@Composable
private fun CurrentFolderCard(
    entries: List<FolderEntry>,
    status: FolderListStatus,
    owners: List<RowOwner>,
    onRefresh: () -> Unit,
    onOpenList: (Long) -> Unit,
    onCreateTasks: () -> Unit
) {
    val owner = owningScope(status)

    SectionCard {
        InfoActionsRow(
            info = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    FolderTypeLabel(folderTypeOf(status))
                    folderProgressLabel(status)?.let { progress ->
                        StatusLine(
                            progress,
                            tone = progressTone(status),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Which list is involved, so a Sublist says whose subtree it is part of.
                folderOwnerTitle(status)?.let { StatusLine(it) }
                // Only a folder that owns its list outright has an export to report.
                statusExportLabel(status)?.let { StatusLine(it) }
                // The Todo that made a Task of this folder. This is the one surface where a List
                // title and a Todo are named on the same card, which is what the prefix is for.
                folderOwnerLabel(owners)?.let { StatusLine(it) }
                Text(
                    folderCountsLabel(entries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            actions = {
                FolderAction("Refresh", Icons.Filled.Refresh, onClick = onRefresh)
                // Only when a List owns this folder outright. A folder under one is part of a
                // subtree, and "Open List" would have to guess which List the user means.
                owner?.let { scope ->
                    FolderAction(
                        "Open List",
                        Icons.AutoMirrored.Filled.List,
                        onClick = { onOpenList(scope.id) }
                    )
                }
                // Registration, unlike everything above it, is not about this folder's lists: it
                // is where the folder stops being raw material and becomes a Source, which is what
                // the catalog and the Upstreams tab are reads of. It asks before it runs, and it
                // asks which grouping — those are the two things only the user can answer.
                FolderAction("Create Tasks", CatalogIcon, onClick = onCreateTasks)
            }
        )
    }
}

/**
 * One folder action, sized to its label so the column's icons line up down the left.
 *
 * Borderless on purpose: four bordered buttons stacked in a narrow column read as a wall, and the
 * card is information first.
 */
@Composable
private fun FolderAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ListeaDimens.IconSize))
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Text(label, maxLines = 1)
    }
}

/**
 * A directory card: what it is, and a tap that goes into it. No list action of its own — those
 * belong to the folder you are actually standing in.
 *
 * Says exactly as much as the current-folder card says about the same folder, from the same
 * [FolderType] and the same helpers, so a folder can never read as one thing here and another
 * once you are inside it.
 */
@Composable
private fun FolderCard(
    entry: FolderEntry,
    status: FolderListStatus,
    owners: List<RowOwner>,
    onNavigate: () -> Unit
) {
    OutlinedCard(
        onClick = onNavigate,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ListeaDimens.CardPadding,
                    vertical = ListeaDimens.RowGap
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // A directory's size and modified time say nothing dependable about its
                // contents, so neither is shown or reasoned about.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    FolderTypeLabel(folderTypeOf(status))
                    folderProgressLabel(status)?.let { progress ->
                        StatusLine(
                            progress,
                            tone = progressTone(status),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Null for a Sublist by construction: the export belongs to the ancestor list,
                // and repeating it on every folder underneath would suggest each can deliver
                // something of its own.
                statusExportLabel(status)?.let { StatusLine(it) }
                // Which Todo made a Task of this folder — and, because a directory card has no List
                // title on it, this is the only ownership line a directory ever shows.
                folderOwnerLabel(owners)?.let { StatusLine(it) }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A file row. Tapping it only ever opens the file for viewing: no checking, no actions, no
 * workflow. The checked state is shown when the owning list tracks the file, but it is reporting,
 * not a control. [isChecked] is null when nothing is known about the file — never reviewed and
 * in no list — or when Folder review integration is switched off.
 */
@Composable
private fun FileRow(
    entry: FolderEntry,
    isChecked: Boolean?,
    onOpen: () -> Unit,
    owners: List<RowOwner> = emptyList(),
    ownerCount: Int? = null,
    onOpenShared: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = ListeaDimens.RowGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                fileDetail(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Which Todo holds this file, and whether it holds it as the Task itself or as one of
            // the files a Task over a folder covers. Only drawn when there is something to say, so
            // an unheld file keeps the two lines it has always had and the height is paid only by
            // the rows the reader is being told about.
            //
            // Inside the weighted column rather than beside it: this row already mixes a flexible
            // child with a fixed-width button and icon, and text in the fixed children would
            // compete with the filename for the same width on every row of a long list.
            fileOwnerLabel(owners)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // The tappable caveat on the tick beside it, for a file more than one Todo holds — the row
        // that cannot say its own answer in a line. Drawn before the tick so the two never read as
        // one control.
        if (ownerCount != null && onOpenShared != null) {
            TextButton(
                onClick = onOpenShared,
                contentPadding = PaddingValues(horizontal = ListeaDimens.RowGap)
            ) {
                Text(
                    todoCount(ownerCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        isChecked?.let { checked ->
            Icon(
                if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (checked) "Checked" else "Not checked",
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
    }
}

/**
 * Which Todos a shared file belongs to, and what each of them decided about it.
 *
 * The mark on the row says a disagreement is possible. This is where the disagreement — or the
 * agreement, which is also worth knowing — is actually readable, so the collapsed tick stops being
 * the only thing the browser says about a file that is two intentions at once.
 *
 * One line per Todo, because the Todo is the thing the user would act on: changing what this file
 * means means going into one of them. It also makes agreement legible without a second read — two
 * lines that say the same thing are the answer to the question the mark raises, and no line has to
 * claim to be the file's own state, which is exactly the claim the collapsed tick cannot make.
 *
 * Read on entry rather than observed, because the browser has no reason to carry a row per Task
 * over every shared file it lists, and this is opened for one file at a time.
 */
@Composable
private fun SharedFileDialog(
    path: String,
    resolver: TemplateResolver,
    load: suspend () -> List<SharedFileState>,
    onDismiss: () -> Unit
) {
    val states by produceState<List<SharedFileState>?>(initialValue = null, path) { value = load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path.substringAfterLast('/')) },
        text = {
            val rows = states
            when {
                rows == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
                    Text("Reading what each Todo decided.")
                }

                rows.isEmpty() -> Text("No Task holds this file any more.")

                else -> Column(
                    verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    Text(
                        fileOwnersNote(rows),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    rows.forEach { row -> SharedFileStateRow(row, resolver) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** One Todo's line: which Todo, what its Task over this file is called, and what it decided. */
@Composable
private fun SharedFileStateRow(state: SharedFileState, resolver: TemplateResolver) {
    // Resolved over the whole Task, not one file: this row names what a Task decided, and the
    // drill-in is reached from a file that several Tasks may be over. The Task's own attachments are
    // what name the marks, so a button set on a video reads as its label here while a photograph of
    // the same Task is what is on screen behind the dialog.
    val actions = itemActionLabel(
        AssetDecisions(selections = decodeActionIds(state.selections)),
        resolver.controlForTask(state.assetControls)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        Icon(
            if (state.isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (state.isCompleted) "Checked" else "Not checked",
            tint = if (state.isCompleted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(ListeaDimens.IconSize)
        )
        Column(Modifier.weight(1f)) {
            // The same function the row's own label uses, so the headline here and the line that
            // opened this dialog can never disagree about the capacity.
            Text(
                ownerTitle(state.sourceName, state.isOwnTask),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildList {
                    if (state.taskName.isNotBlank()) add(state.taskName)
                    actions?.let { add(it) }
                    if (isEmpty()) add("Nothing decided yet")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A folder's own contents, one level deep: "3 folders · 5 files · 4.2 GB".
 *
 * Derived from the listing already on screen, so it costs nothing. The size covers the direct
 * files only and is left out when none of them report one — nothing here walks subfolders.
 */
private fun folderCountsLabel(entries: List<FolderEntry>): String {
    val folders = entries.count { it.isDirectory }
    val files = entries.size - folders
    val bytes = entries.filterNot { it.isDirectory }.sumOf { it.sizeBytes ?: 0L }
    return buildList {
        add(countLabel(folders, "folder"))
        add(countLabel(files, "file"))
        if (bytes > 0) add(formatSize(bytes))
    }.joinToString(" · ")
}

/** "JPG · 4.2 MB · 2026-07-11 14:22". Type first, because it is what identifies the file. */
private fun fileDetail(entry: FolderEntry): String = buildList {
    fileTypeLabel(entry.name)?.let { add(it) }
    entry.sizeBytes?.let { add(formatSize(it)) }
    entry.lastModified?.let { add(formatTimestamp(it)) }
}.joinToString(" · ")

/** The extension, upper-cased. Null for a file that has none to show. */
private fun fileTypeLabel(name: String): String? =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()

/**
 * Whether a folder's progress reads as finished.
 *
 * A finished subtree gets the same tint a finished direct list gets, and 0 / 0 is never
 * "complete" — that rule lives on [FolderListStatus.Inherited] and is simply read here.
 */
private fun progressTone(status: FolderListStatus): StatusTone = when (status) {
    is FolderListStatus.None -> StatusTone.Neutral
    is FolderListStatus.Direct ->
        if (status.scope.isComplete) StatusTone.Positive else StatusTone.Neutral
    is FolderListStatus.Inherited ->
        if (status.subtreeIsComplete) StatusTone.Positive else StatusTone.Neutral
}
