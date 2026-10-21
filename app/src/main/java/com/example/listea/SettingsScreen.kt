package com.example.listea

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import com.example.listea.domain.ExportKind
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.listea.data.UpstreamEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Settings: an index of subjects, and the pages behind them.
 *
 * **Why an index.** Settings had grown into one scrolling page holding every switch, every folder,
 * the template count and the file-cleanup tool, with no summary of any of it. A user looking for
 * *where do I change how videos play* had to scroll past everything else to find out, and a user
 * checking *what is this app currently doing* had no single screen that answered it. The index
 * names each subject with its one-line state; the controls live one tap in, on a page titled with
 * the same words.
 *
 * **Why a route string.** The five booleans and ids this used to hoist (`exportsOpen`,
 * `templatesOpen`, `openTemplateId`, `historyOpen`, `openExportKind`) could describe only some of
 * the pages and disagreed about which one was up. One string describes every page, survives
 * rotation through `rememberSaveable`, and lets the app bar decide *is a subpage showing* with a
 * single comparison rather than a growing `||`. An unknown route falls back to the index rather
 * than to a blank screen.
 *
 * Pages that need a back arrow use [ListeaNestedScaffold] with **Settings** as the label, so the
 * hierarchy is always one gesture from the screen that names it.
 */
object SettingsRoute {
    const val Index = "index"
    const val Upstreams = "upstreams"
    const val Review = "review"
    const val Media = "media"
    const val Todos = "todos"
    const val Templates = "templates"
    const val Exports = "exports"
    const val File = "file"
    const val History = "history"

    private const val TemplatePrefix = "template/"
    private const val ExportPrefix = "export/"

    fun template(templateId: String): String = TemplatePrefix + templateId

    fun templateId(route: String): String? =
        if (route.startsWith(TemplatePrefix)) {
            route.removePrefix(TemplatePrefix).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    fun export(kind: ExportKind): String = ExportPrefix + kind.key

    fun exportKind(route: String): ExportKind? =
        if (route.startsWith(ExportPrefix)) {
            ExportKind.fromKey(route.removePrefix(ExportPrefix))
        } else {
            null
        }
}

/** The index, or whichever page [route] names. One string, one branch. */
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: ListsViewModel = viewModel(),
    route: String = SettingsRoute.Index,
    onRoute: (String) -> Unit = {}
) {
    val toIndex = { onRoute(SettingsRoute.Index) }
    val openTemplate = SettingsRoute.templateId(route)
    val openExport = SettingsRoute.exportKind(route)

    when {
        route == SettingsRoute.History -> ExportHistoryScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        route == SettingsRoute.Templates -> TemplateListScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex,
            onOpen = { onRoute(SettingsRoute.template(it)) }
        )

        openTemplate != null -> TemplateDetailScreen(
            modifier = modifier,
            viewModel = viewModel,
            templateId = openTemplate,
            onBack = { onRoute(SettingsRoute.Templates) }
        )

        route == SettingsRoute.Exports -> ExportListScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex,
            onOpenKind = { onRoute(SettingsRoute.export(it)) }
        )

        openExport != null -> ExportDetailScreen(
            modifier = modifier,
            viewModel = viewModel,
            kind = openExport,
            onBack = { onRoute(SettingsRoute.Exports) }
        )

        route == SettingsRoute.Upstreams -> SettingsUpstreamsPage(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        route == SettingsRoute.Review -> SettingsReviewPage(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        route == SettingsRoute.Media -> SettingsMediaPage(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        route == SettingsRoute.Todos -> SettingsTodosPage(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        route == SettingsRoute.File -> SettingsFilePage(
            modifier = modifier,
            viewModel = viewModel,
            onBack = toIndex
        )

        else -> SettingsIndexScreen(
            modifier = modifier,
            viewModel = viewModel,
            onRoute = onRoute
        )
    }
}

/**
 * The index: one card, one row per subject, each naming what it currently does.
 *
 * A single card rather than eight, because these rows are one list — the subjects of one Settings —
 * and eight cards would be the old scrolling page with borders between its sections. The row's
 * state line is the point of the design: it answers *what is this set to* without opening anything,
 * which is the question the old page could not answer from one screen either.
 */
@Composable
private fun SettingsIndexScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onRoute: (String) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val upstreams by viewModel.upstreams.collectAsStateWithLifecycle()
    val historyCount by viewModel.webhookRecordCount.collectAsStateWithLifecycle()
    val roundCount by viewModel.webhookRoundCount.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingWebhookCount.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
    ) {
        Spacer(Modifier.height(ListeaDimens.CompactGap))

        SectionCard {
            SettingsIndexRow(
                icon = Icons.Filled.Folder,
                title = "Upstreams",
                state = if (upstreams.isEmpty()) {
                    "No folders yet"
                } else {
                    countLabel(upstreams.size, "folder")
                },
                onOpen = { onRoute(SettingsRoute.Upstreams) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.Filled.Visibility,
                title = "Review",
                state = if (settings.folderQuickReviewEnabled) {
                    "Quick Review is available in Todo"
                } else {
                    "Quick Review is off in Todo"
                },
                onOpen = { onRoute(SettingsRoute.Review) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.Filled.PlayArrow,
                title = "Media and review behaviour",
                state = listOf(
                    if (settings.videoAutoplay) "Videos autoplay" else "Videos play on tap",
                    if (settings.rememberReviewPosition) "Review resumes" else "Review starts over"
                ).joinToString(" · "),
                onOpen = { onRoute(SettingsRoute.Media) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.Outlined.CheckCircle,
                title = "Todos",
                state = settings.defaultContentPolicy.label + " · " +
                    if (settings.followUpstreamNewTodos) "follows its upstream" else "no auto-update",
                onOpen = { onRoute(SettingsRoute.Todos) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.Filled.Widgets,
                title = "Templates",
                state = templates?.let { countLabel(it.size, "template") },
                onOpen = { onRoute(SettingsRoute.Templates) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Exports",
                state = exportMethodsSummary(settings.defaultWebhookUrl),
                onOpen = { onRoute(SettingsRoute.Exports) }
            )
            IndexDivider()

            SettingsIndexRow(
                icon = Icons.Filled.FilterAlt,
                title = "File management",
                state = if (settings.sharedFileArrangement) {
                    "One filter and sort everywhere"
                } else {
                    "Each folder and list keeps its own"
                },
                onOpen = { onRoute(SettingsRoute.File) }
            )
            IndexDivider()

            // At the bottom because it is a record of what already happened rather than a thing
            // to configure, and because it is the one row whose state is worth a glance.
            SettingsIndexRow(
                icon = Icons.Filled.History,
                title = "History",
                state = if (historyCount == 0) {
                    "Nothing yet"
                } else {
                    historyCountLabel(historyCount, roundCount)
                },
                tone = if (pendingCount == 0) StatusTone.Neutral else StatusTone.Warning,
                onOpen = { onRoute(SettingsRoute.History) }
            )
        }

        Spacer(Modifier.height(ListeaDimens.SectionGap))
    }
}

/** Name, current state, and the way in — the index's one row shape. */
@Composable
private fun SettingsIndexRow(
    icon: ImageVector,
    title: String,
    state: String?,
    onOpen: () -> Unit,
    tone: StatusTone = StatusTone.Neutral
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ListeaDimens.IconSize)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            // Nothing while a count is being read: a count that has not been counted is not one.
            state?.let { StatusLine(it, tone = tone) }
        }
        Button(onClick = onOpen) { Text("Open", maxLines = 1) }
    }
}

@Composable
private fun IndexDivider() {
    HorizontalDivider(Modifier.padding(vertical = ListeaDimens.CompactGap))
}

/** The shell every category page shares: title, Settings back, one scroll surface. */
@Composable
private fun SettingsPage(
    title: String,
    modifier: Modifier,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ListeaNestedScaffold(
        modifier = modifier,
        title = title,
        backLabel = "Settings",
        onBack = onBack
    ) { bodyModifier ->
        Column(
            modifier = bodyModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ListeaDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
        ) {
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            content()
            Spacer(Modifier.height(ListeaDimens.SectionGap))
        }
    }
}

/**
 * The folders Listea has been given: the list, and the one place a new one is added.
 *
 * Adding lives here rather than on the Upstreams tab because it configures what the app holds, not
 * what it is showing: the Upstreams tab is a list you open things from, and a button that changed
 * that list from inside it made the tab both the index and the editor.
 */
@Composable
private fun SettingsUpstreamsPage(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val upstreams by viewModel.upstreams.collectAsStateWithLifecycle()

    // The folders the app still holds a grant for. Keyed on the list of Upstreams as well as on the
    // context so that adding one re-reads it: the grant is taken in the picker's callback below,
    // and the row it just made would otherwise sit there saying it had no access.
    val heldRoots = remember(context, upstreams) { heldRootUris(context) }

    // Why the message survives the trip: the picker is another activity, and coming back to be
    // told nothing about what just happened would be the worst part of the flow.
    var addNotice by rememberSaveable { mutableStateOf<String?>(null) }

    // The Upstream whose removal is being confirmed; the dialog is shared with the Upstreams tab.
    var removing by remember { mutableStateOf<UpstreamEntity?>(null) }

    /**
     * Adding an Upstream: the folder picker, and what a committed pick means.
     *
     * This is the only place an Upstream is ever added. The grant has to be taken in the callback,
     * because a persisted permission can only be claimed at the moment Android hands it over, and
     * the row is written in the same breath — one folder, one Upstream.
     *
     * Nothing is replaced and nothing is deleted. An Upstream is a folder the user has pointed at,
     * and pointing at a second one says nothing about the first: the Lists and Todos that came out
     * of that one are about Tasks that still exist, and the app used to answer this gesture by
     * asking whether to delete them, which was the old single-root model showing through.
     */
    val addUpstream = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        // Write is asked for alongside read because file management deletes through this grant. A
        // tree offered read-only is still a perfectly good Upstream, so it is taken rather than
        // refused; what becomes unavailable is deleting, and the File management page says so.
        fun take(flags: Int) = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        val granted = take(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) || take(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (granted) {
            addNotice = null
            viewModel.registerUpstream(uri.toString(), "")
        } else {
            addNotice = "Android did not allow access to that folder. Somewhere in internal " +
                "storage, or a folder you picked yourself, will work."
        }
    }

    SettingsPage("Upstreams", modifier, onBack) {
        SectionCard {
            if (upstreams.isEmpty()) {
                Text(
                    "No Upstreams yet",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            upstreams.forEach { upstream ->
                UpstreamRow(
                    upstream = upstream,
                    available = upstream.rootUri in heldRoots,
                    onRemove = { removing = upstream }
                )
            }

            Spacer(Modifier.height(ListeaDimens.CompactGap))

            Button(onClick = { addUpstream.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ListeaDimens.IconSize)
                )
                Spacer(Modifier.width(ListeaDimens.CompactGap))
                Text("Add Upstream", maxLines = 1)
            }

            addNotice?.let {
                HelperText(it, tone = StatusTone.Warning)
            }

            // Both sentences answer a question the old single-root Settings raised and could not
            // answer: whether adding a folder costs the one already there. It did, and it no
            // longer does, and neither fact is visible from a screen that only shows a list.
            HelperText(
                "An Upstream is a folder Listea has been given. Adding one only grants access " +
                    "to it — nothing is read and no Tasks are made until you open it and run " +
                    "Create Tasks on a folder inside it."
            )
            HelperText(
                "Adding a second Upstream changes nothing about the first. Its Todos and Lists " +
                    "are about Tasks that still exist, and they stay exactly as they are."
            )
        }
    }

    removing?.let { upstream ->
        RemoveUpstreamDialog(
            upstream = upstream,
            onDismiss = { removing = null },
            onConfirm = {
                viewModel.deleteUpstream(upstream.id)
                removing = null
            }
        )
    }
}

/**
 * Quick Review's one switch.
 *
 * It is alone on its page because that is the whole of what this subject is; grouping it with
 * media behaviour would suggest it acts on the review in front of the user, which it does not.
 */
@Composable
private fun SettingsReviewPage(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage("Review", modifier, onBack) {
        SectionCard {
            SwitchRow(
                label = "Enable Quick Review in Todo",
                icon = Icons.Filled.Visibility,
                checked = settings.folderQuickReviewEnabled,
                onCheckedChange = { viewModel.setFolderQuickReviewEnabled(it) }
            )
            // Said in the two places the switch reaches, because it reaches two: the Todo tab,
            // where it takes the action away, and the Upstreams browser, where it stops drawing
            // what has already been decided.
            HelperText(
                "Off takes Quick Review off the Todo tab, and stops the Upstreams browser " +
                    "showing which files are already checked. Lists keep their items, " +
                    "completion and actions."
            )
        }
    }
}

/** How the apps presents media, and how a review starts. */
@Composable
private fun SettingsMediaPage(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage("Media and review behaviour", modifier, onBack) {
        SectionCard {
            SwitchRow(
                label = "Play videos automatically",
                icon = Icons.Filled.PlayArrow,
                checked = settings.videoAutoplay,
                onCheckedChange = { viewModel.setVideoAutoplay(it) }
            )
            SwitchRow(
                label = "Start videos muted",
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                checked = settings.videoStartMuted,
                onCheckedChange = { viewModel.setVideoStartMuted(it) }
            )
            SwitchRow(
                label = "Remember review position",
                icon = Icons.Outlined.Bookmark,
                checked = settings.rememberReviewPosition,
                onCheckedChange = { viewModel.setRememberReviewPosition(it) }
            )
            SwitchRow(
                label = "Auto-check list freshness on entry",
                icon = Icons.Filled.Refresh,
                checked = settings.autoCheckSourceFreshness,
                onCheckedChange = { viewModel.setAutoCheckSourceFreshness(it) }
            )
            HelperText(
                "Resuming applies to full Review only — Quick Review always starts at the first " +
                    "unchecked item. With auto-checking off, a List says \"Not checked\" until " +
                    "you use Update from folder."
            )

            SwitchRow(
                label = "Review unchecked items only",
                icon = Icons.Outlined.CheckCircle,
                checked = settings.reviewUncheckedOnly,
                onCheckedChange = { viewModel.setReviewUncheckedOnly(it) }
            )
            HelperText(
                "Review and Quick Review queue only the items that were unchecked when you " +
                    "opened them. The queue is fixed for that round, so an item you check stays " +
                    "in front of you until you leave."
            )
        }
    }
}

/**
 * What a *new* Todo is created with. The Todo's own card is where they are changed once it exists.
 *
 * A subject of its own rather than two more rows on the media page, because neither is about how
 * a screen behaves: they are standing answers a future Todo starts from.
 */
@Composable
private fun SettingsTodosPage(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage("Todos", modifier, onBack) {
        SectionCard {
            Text(
                "What new Todos start with",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            ContentPolicyChoice(
                selected = settings.defaultContentPolicy,
                onSelect = { viewModel.setDefaultContentPolicy(it) }
            )
            HelperText(
                "A new Todo is created with this answer, and remembers whatever the Create Tasks " +
                    "dialog is told instead. It decides what happens to a Task whose files have " +
                    "changed outside Listea — keeping it flags what has gone, starting it over " +
                    "clears its ticks, and replacing it takes the Task out of every List holding " +
                    "it. Only the last of the three removes anything."
            )
            SwitchRow(
                label = "Follow the upstream on new Todos",
                icon = Icons.Filled.Refresh,
                checked = settings.followUpstreamNewTodos,
                onCheckedChange = { viewModel.setFollowUpstreamNewTodos(it) }
            )
            HelperText(
                "On, a Todo is created following its upstream: opening it scans the folder and " +
                    "brings in material that appeared since, without asking. Off, opening a Todo " +
                    "never writes — which is what every version before this one did. Either way a " +
                    "Todo's own card can switch it on or off for that Todo alone."
            )
        }
    }
}

/**
 * Filter and sort sharing, the post-webhook deletion offer, and the full checked-file cleanup.
 *
 * These three are one subject because all three are about the files themselves rather than about
 * what is shown of them. The cleanup tool is the only thing here that can destroy anything outside
 * Listea, so it keeps its full listing dialog.
 */
@Composable
private fun SettingsFilePage(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteCheckedRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val upstreams by viewModel.upstreams.collectAsStateWithLifecycle()

    // The root the checked-file tools work on. A List can only have one root and this app can now
    // have several, so "delete checked files under the root" has to say *which* root or it is
    // answering a different question than the one the user is asking.
    var cleanupRoot by rememberSaveable { mutableStateOf<String?>(null) }
    val rootChoices = upstreams.map { it.rootUri }.distinct()
    // Falls back to the first when nothing is chosen or the chosen one is gone, so the section is
    // never stuck pointing at a folder that was removed.
    val activeRoot = cleanupRoot?.takeIf { it in rootChoices } ?: rootChoices.firstOrNull()
    val activeLabel = upstreams.firstOrNull { it.rootUri == activeRoot }?.displayName
        ?: "the selected folder"

    // Nothing is checked until a root is chosen, and there is nothing to observe either.
    val checkedFileCount by remember(activeRoot) {
        activeRoot?.let { viewModel.observeCheckedFileCount(it) } ?: flowOf(0)
    }.collectAsStateWithLifecycle(0)

    var grantNotice by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Asking for permission to change a folder the app can already read, which is its own trip
     * through the picker and so gets its own launcher.
     *
     * Deliberately not the add launcher: that one registers whatever comes back as a new Upstream.
     * Here the answer has to be a folder already in use, so anything else is refused rather than
     * acted on, and nothing at all is written unless the two match.
     */
    val grantWriteAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val root = activeRoot
        // Backing out is a real answer: the dialog is still there to try again or cancel.
        if (uri == null || root == null) return@rememberLauncherForActivityResult

        if (uri.toString() != root) {
            grantNotice = "That is a different folder, so nothing was changed. Choose " +
                "$activeLabel itself — the folder Listea is already using — to allow deleting " +
                "from it."
            return@rememberLauncherForActivityResult
        }

        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (granted) {
            grantNotice = null
            // Straight back to where the user was, rather than making them find the button again.
            viewModel.requestCheckedFileDeletion(root)
        } else {
            grantNotice = "Android did not allow changes to this folder. Somewhere in internal " +
                "storage, or a folder you picked yourself, will work."
        }
    }

    SettingsPage("File management", modifier, onBack) {
        SectionCard {
            SwitchRow(
                label = "Same filter and sort everywhere",
                checked = settings.sharedFileArrangement,
                onCheckedChange = { viewModel.setSharedFileArrangement(it) },
                icon = Icons.Filled.FilterAlt
            )
            HelperText(
                "On, one filter and one sort order are shared by every folder and every list: " +
                    "set them once from a folder's Files header or a list's Items header and " +
                    "everywhere else follows. Off, each folder and each list keeps its own."
            )
            HelperText(
                "Filtering and sorting only change what is shown, and which items a review then " +
                    "walks. Progress, completion and webhooks always count the whole folder or " +
                    "the whole list."
            )
            HelperText(
                "Switching this discards nothing. Both the shared setting and every folder's and " +
                    "list's own are kept, so turning it on to sweep through a root and turning " +
                    "it back off returns each of them to what it had."
            )

            HorizontalDivider(Modifier.padding(top = ListeaDimens.CompactGap))

            SwitchRow(
                label = "Ask to delete after webhook",
                icon = Icons.Outlined.DeleteOutline,
                checked = settings.askDeleteAfterWebhook,
                onCheckedChange = { viewModel.setAskDeleteAfterWebhook(it) }
            )
            HelperText(
                "Once a webhook has been sent, Listea offers to delete the files that delivery " +
                    "carried as checked. Every one of them is named first and nothing is " +
                    "deleted until you confirm."
            )
            HelperText(
                "The offer follows the webhook, not the review. \"Review unchecked items only\" " +
                    "decides what the round walked and \"Send checked items only\" decides what " +
                    "the payload carried — both have already had their say by the time you are " +
                    "asked, and what is offered is exactly the checked items in the payload " +
                    "that went out. Nothing else under the root is touched."
            )
            HelperText(
                "A webhook that did not go out deletes nothing. You are told the files are " +
                    "still there, and the round stays in History to be resent — or you can " +
                    "clear the checked files here instead."
            )

            Spacer(Modifier.height(ListeaDimens.RowGap))

            // Which Upstream these two controls are about, and nothing at all while there is only
            // one: a choice with a single answer is a control that cannot be used, and it would
            // push the thing it labels off a narrow card to say nothing. With two or more, "the
            // root" is no longer an answer and the chips are what makes the count below mean
            // something — a bare "84 checked files" over three folders would be a number with no
            // subject.
            if (rootChoices.size > 1) {
                Text("Upstream", style = MaterialTheme.typography.bodyMedium)
                ActionGroup {
                    rootChoices.forEach { root ->
                        val name = upstreams.firstOrNull { it.rootUri == root }?.displayName ?: root
                        FilterChip(
                            selected = root == activeRoot,
                            onClick = { cleanupRoot = root },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text(
                            // Named when there is more than one, so the count below and the chip
                            // above are unmistakably about the same folder.
                            if (rootChoices.size > 1) "Checked files in $activeLabel"
                            else "Checked files",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    StatusLine(
                        // Says "under this root" because that is genuinely what it counts: every
                        // checked file anywhere beneath the selected folder, whenever and however
                        // it was checked. It is not a list's progress and not a review round's
                        // tally, and reading it as either is the easiest mistake this number
                        // invites — the two will disagree the moment anything was checked
                        // elsewhere.
                        if (checkedFileCount == 0) {
                            "Nothing checked under this root"
                        } else {
                            countLabel(checkedFileCount, "checked file") +
                                " under this root, still on the device"
                        },
                        tone = if (checkedFileCount == 0) StatusTone.Neutral else StatusTone.Warning
                    )
                },
                actions = {
                    Button(
                        onClick = { viewModel.requestCheckedFileDeletion(activeRoot) },
                        enabled = activeRoot != null && checkedFileCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete checked files", maxLines = 1)
                    }
                },
                // Wider than the other cards' action columns, because this button says what it
                // deletes rather than just "Delete" and a clipped destructive label is no label.
                sideBySideMinWidth = 380.dp,
                actionColumnWidth = 210.dp
            )
            HelperText(
                "Deletes every checked file in the current root folder from the device itself, " +
                    "permanently — however and whenever it was checked, which is what makes it " +
                    "wider than the offer above. You are shown the full list first and nothing " +
                    "happens until you confirm. The items stay in their Lists, marked as " +
                    "missing, so what you decided about them is kept."
            )
            HelperText(
                "Files are deleted where Listea last recorded them, so update a List from its " +
                    "folder first if things have been moved around outside the app."
            )
        }
    }

    DeleteCheckedFilesDialog(
        request = deleteRequest,
        rootLabel = activeLabel,
        grantNotice = grantNotice,
        onDismiss = {
            grantNotice = null
            viewModel.dismissDeleteCheckedRequest()
        },
        onGrantAccess = {
            grantNotice = null
            // Opens on the folder already in use, so granting is one tap rather than a hunt.
            grantWriteAccess.launch(activeRoot?.let { initialPickerUri(it.toUri()) })
        },
        onConfirm = { viewModel.confirmDeleteCheckedFiles(activeRoot) }
    )
}

/**
 * One Upstream in Settings: which folder it is, and what is in it.
 *
 * The row says what the Upstream *is* — a name, where it sits, how much material is under it — and
 * nothing about what has been done with it. A Task count would be the same mistake the Upstreams
 * tab avoids: this table holds folders, not the Tasks imports later made from them, and a number
 * here would be a second, quieter claim about a relationship that lives somewhere else.
 *
 * The size is a full walk of the tree, so it runs off the main thread and reports itself as
 * measuring rather than showing zeroes it is about to replace. An unreadable folder is a state of
 * its own and not an empty one: a grant the user revoked and a folder that is genuinely empty look
 * identical at a glance and mean opposite things.
 */
@Composable
private fun UpstreamRow(
    upstream: UpstreamEntity,
    available: Boolean,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val storage by produceState<StorageInfo>(
        StorageInfo.Scanning,
        upstream.id,
        upstream.relativePath,
        available
    ) {
        value = StorageInfo.Scanning
        // Resolved and walked in one go, so a folder that has been deleted since it was added is
        // reported by the same null the missing grant produces: either way there is nothing here
        // to count, and counting is all this row does with it.
        value = withContext(Dispatchers.IO) {
            resolveLinkedFolder(context, upstream.rootUri.toUri(), upstream.relativePath)
                ?.let { scanFolderStats(context, it.uri) }
        }?.let { StorageInfo.Ready(it) } ?: StorageInfo.Unavailable
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    upstream.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (upstream.relativePath.isNotEmpty()) {
                    Text(
                        "Inside " + upstream.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Reachable whatever the grant says: a folder Listea can no longer read is exactly one
            // a user may want to stop listing.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More actions for ${upstream.displayName}"
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove Upstream…") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
        }
        // What is actually in there, counted the same way a List's scan counts it, so this and a
        // folder-backed List can never disagree about what the folder holds.
        StatusLine(
            storageSummary(storage),
            tone = if (storage is StorageInfo.Unavailable) StatusTone.Warning else StatusTone.Neutral,
            maxLines = 2
        )
        if (!available) {
            // The row stays. An Upstream whose grant is gone is not deleted material — the Todos
            // and Lists that came out of it are about Tasks that still exist — so this says what
            // is wrong with the folder rather than removing the folder from the list.
            Text(
                "Listea no longer has access to this folder. Add it again to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        HorizontalDivider(Modifier.padding(vertical = ListeaDimens.CompactGap))
    }
}

/**
 * What an Upstream's row knows about its folder while it is finding out.
 *
 * Measuring a tree means walking all of it, which is not instant on a folder worth measuring, and
 * an unreadable folder is not an empty one. Both are states of their own so the row can say which
 * it is in rather than showing a plausible-looking zero.
 */
private sealed interface StorageInfo {
    data object Scanning : StorageInfo
    data object Unavailable : StorageInfo
    data class Ready(val stats: FolderStats) : StorageInfo
}

private fun storageSummary(info: StorageInfo): String = when (info) {
    StorageInfo.Scanning -> "Measuring…"
    StorageInfo.Unavailable -> "Could not be read"
    is StorageInfo.Ready -> info.stats.summary
}

/**
 * The one destructive thing Listea does outside its own database, made as slow and as explicit as
 * it deserves to be.
 *
 * The listing is the point of the dialog. "Delete 84 files" is not something anyone can agree to
 * honestly, so every file is named, under the folder that holds it, and the whole thing scrolls —
 * nothing is summarised away with "and 79 more", because the 79 are exactly what is at stake.
 * Grouping by folder keeps that readable at the length it can reach.
 *
 * Cancel is the easy path: it is the dismiss button, tapping outside works, and the delete button
 * is the one that has to be aimed for. While the deletion is running there is no way out at all,
 * because there is nothing left to decide and a half-finished run should not look like a choice.
 */
@Composable
private fun DeleteCheckedFilesDialog(
    request: DeleteCheckedRequest?,
    rootLabel: String,
    grantNotice: String?,
    onDismiss: () -> Unit,
    onGrantAccess: () -> Unit,
    onConfirm: () -> Unit
) {
    val pending = request ?: return

    AlertDialog(
        onDismissRequest = { if (pending !is DeleteCheckedRequest.Deleting) onDismiss() },
        title = {
            Text(
                when (pending) {
                    DeleteCheckedRequest.Scanning -> "Checked files"
                    DeleteCheckedRequest.NothingChecked -> "Nothing to delete"
                    DeleteCheckedRequest.NeedsWriteAccess -> "Allow Listea to delete files?"
                    is DeleteCheckedRequest.Confirm -> "Delete checked files?"
                    DeleteCheckedRequest.Deleting -> "Deleting"
                    is DeleteCheckedRequest.Done -> "Finished"
                    is DeleteCheckedRequest.Error -> "Nothing was deleted"
                }
            )
        },
        text = {
            when (pending) {
                DeleteCheckedRequest.Scanning -> Text("Looking for checked files…")

                DeleteCheckedRequest.NothingChecked -> Text(
                    "No checked item in this folder still has a file behind it, so there is " +
                        "nothing to delete."
                )

                DeleteCheckedRequest.NeedsWriteAccess -> Column {
                    Text(
                        "Listea can read $rootLabel but is not allowed to change it yet. " +
                            "Android only hands that over through its own folder picker, so tap " +
                            "Grant access and confirm $rootLabel again — the picker opens on it " +
                            "already on most devices."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Nothing is deleted by granting this, and your Lists, items and settings " +
                            "are not touched. You are brought straight back here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    grantNotice?.let {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        StatusLine(it, tone = StatusTone.Warning, maxLines = 4)
                    }
                }

                is DeleteCheckedRequest.Confirm ->
                    CheckedFileListing(pending, rootLabel)

                DeleteCheckedRequest.Deleting -> Text("Removing the files from the device…")

                is DeleteCheckedRequest.Done -> Column {
                    Text(deleteOutcomeMessage(pending.deleted, pending.failed))
                    if (pending.deleted > 0) {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text(
                            "Their items are kept and marked as missing, so what you decided " +
                                "about them is not lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is DeleteCheckedRequest.Error -> Text(pending.message)
            }
        },
        confirmButton = {
            when (pending) {
                is DeleteCheckedRequest.Confirm -> TextButton(onClick = onConfirm) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
                DeleteCheckedRequest.NeedsWriteAccess -> TextButton(onClick = onGrantAccess) {
                    Text("Grant access")
                }
                // Nothing to offer mid-run: the files are already going.
                DeleteCheckedRequest.Deleting -> Unit
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            val cancellable = pending is DeleteCheckedRequest.Confirm ||
                pending is DeleteCheckedRequest.NeedsWriteAccess
            if (cancellable) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * What the whole-root deletion is about to take, said once and then listed in full.
 *
 * The sentence is this dialog's own — it is the one that deletes every checked file under the
 * root, whenever it was checked — and the listing under it is [CheckedFileGroups], shared with
 * the offer a delivered webhook makes.
 */
@Composable
private fun CheckedFileListing(pending: DeleteCheckedRequest.Confirm, rootLabel: String) {
    Column {
        Text(
            "${countLabel(pending.fileCount, "file")} will be deleted from this device " +
                "permanently. This cannot be undone."
        )
        Spacer(Modifier.height(ListeaDimens.RowGap))
        CheckedFileGroups(groups = pending.groups, rootLabel = rootLabel)
    }
}

/**
 * One custom action's two fields: what it is called, and what it sends.
 *
 * Side by side where both stay readable, stacked where they would not. The threshold is about the
 * fields rather than the device: a text field narrower than this stops showing enough of its own
 * value to edit it, and two cramped fields are worse than two full-width ones.
 *
 * `internal` rather than private because it is drawn on a screen that is not this one — a template's
 * page, over the buttons a version offers. Since v0.5.4 that page is the only caller: Settings used
 * to draw this over the tags an install starts with, and the import dialog over the tags one run was
 * to be given, and both of those surfaces are gone because a button now lives in the template it
 * belongs to rather than in two places that could disagree.
 *
 * It takes an action and callbacks rather than reading or writing anything, which is what the
 * template page needs from it: what the page holds is a *draft* — an edit nothing has written yet —
 * so the fields must not be the thing that decides when it lands.
 *
 * **[buttonType] is what a template has and the old configured tag did not.** A [CustomAction] is
 * the projection the two shapes shared — an id, a name, a wire value — and a button adds what it
 * *records*, so the type row is drawn only when a caller says there is one. **Defaulting the
 * callbacks and not the type is deliberate**: a caller that has a type to show always has something
 * to call, and a caller that has none cannot accidentally be given the row.
 *
 * **[commitAsYouType] is the difference between an edit that is stored and one that is not.** A
 * field whose value is already in the store must not write on every keystroke — that is why
 * [SettingsTextField] waits for Done or for focus to leave — but a draft field has nothing to
 * protect, and waiting would lose the last thing typed to a tap that does not move focus on touch.
 */
@Composable
internal fun CustomActionFields(
    title: String,
    action: CustomAction,
    onDisplayNameCommit: (String) -> Unit,
    onWebhookValueCommit: (String) -> Unit,
    onRemove: () -> Unit,
    buttonType: ButtonType? = null,
    onButtonTypeChange: (ButtonType) -> Unit = {},
    commitAsYouType: Boolean = false
) {
    Spacer(Modifier.height(ListeaDimens.RowGap))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove $title")
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= CustomActionSideBySideMinWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
                SettingsTextField(
                    label = "Display name",
                    value = action.displayName,
                    onCommit = onDisplayNameCommit,
                    modifier = Modifier.weight(1f),
                    commitAsYouType = commitAsYouType
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = action.webhookValue,
                    onCommit = onWebhookValueCommit,
                    modifier = Modifier.weight(1f),
                    commitAsYouType = commitAsYouType
                )
            }
        } else {
            Column {
                SettingsTextField(
                    label = "Display name",
                    value = action.displayName,
                    onCommit = onDisplayNameCommit,
                    commitAsYouType = commitAsYouType
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = action.webhookValue,
                    onCommit = onWebhookValueCommit,
                    commitAsYouType = commitAsYouType
                )
            }
        }
    }

    // Drawn only where something has a type to choose, which is a button on a stored template. A
    // configured tag is not a button — it has no type and never had, because there is nothing in
    // this app that reads one — so Settings' tag rows and the Create Tasks dialog's are unchanged
    // by the row below appearing here.
    buttonType?.let { selected ->
        Spacer(Modifier.height(ListeaDimens.RowGap))
        Text("Records", style = MaterialTheme.typography.bodySmall)
        ActionGroup {
            ButtonType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onButtonTypeChange(type) },
                    label = { Text(buttonTypeLabel(type), maxLines = 1) }
                )
            }
        }
        HelperText(buttonTypeHelp(selected))
    }
}

/**
 * What a [ButtonType] is called where a user picks one.
 *
 * Both types store the same thing today — the button's id being in an Asset's selection set — so
 * the names describe what they *mean* rather than what they hold, and are not allowed to pretend to
 * a difference in storage. `boolean` is the `★` kind; a tag is a label whose meaning the export
 * decides. The line under the chips says the rest, because a user choosing between two things that
 * behave identically deserves to be told why the choice exists at all.
 */
private fun buttonTypeLabel(type: ButtonType): String = when (type) {
    ButtonType.BOOLEAN -> "On / off"
    ButtonType.TAG -> "Tag"
}

private fun buttonTypeHelp(type: ButtonType): String = when (type) {
    ButtonType.BOOLEAN ->
        "A button that means itself, like ★. What it records is that it was pressed."
    ButtonType.TAG ->
        "A button whose meaning is decided by whatever reads the export — the app records that " +
            "it is on and sends the record value above, and does not decide what that means."
}

/** Two 150dp fields and the gap between them: below this a field stops showing enough to edit. */
private val CustomActionSideBySideMinWidth = 308.dp

/**
 * The quiet line under a control that explains it, in one place so they all match.
 *
 * `internal` since v0.5.4, because the template pages explain as much as this screen does and a
 * second copy of "bodySmall in onSurfaceVariant" is how two pages come to look like two apps.
 */
@Composable
internal fun HelperText(text: String, tone: StatusTone = StatusTone.Neutral) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (tone == StatusTone.Warning) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/**
 * One labelled switch.
 *
 * `internal` rather than private as of v0.5.6, because the three export-wide switches moved to a
 * page of their own in `ExportsScreen.kt` and a switch is a switch wherever it is drawn. It is the
 * same control and the same callbacks; only the file it is written in stopped being the only one
 * that could call it.
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A settings text field that commits on Done, on losing focus, or on leaving the screen — never
 * per keystroke.
 *
 * Leaving counts as finishing the edit, and has to: tapping a button does not move focus away
 * from a text field on touch, so typing a URL and going straight to another page used to discard
 * it without a word, and the next delivery would quietly still use the old one. What the user
 * typed is what gets stored; what it means is decided when it is used.
 *
 * Blank input is refused rather than stored: an empty action name would leave an unlabelled chip
 * and an empty wire value would put `""` in a webhook array. The field says so and the stored
 * value is left alone. [allowEmpty] is for the default URL, where empty legitimately means "none".
 *
 * `internal` since v0.5.4: a template's page edits buttons with the same two fields, and a button's
 * display name is a display name. [commitAsYouType] is for the one caller whose [value] is a draft
 * rather than a stored row — see [CustomActionFields].
 *
 * [onDraft] reports the text on every keystroke **without committing it**, for the one caller that
 * has to act on what is in the field before the field is left. The webhook page's Test button is
 * that caller: a tap does not take focus from a text field on touch, so a URL typed and then tested
 * was still a draft when the button read the stored value — and the test posted to the address that
 * was there before it. Listening does not change when the value is stored.
 */
@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false,
    commitAsYouType: Boolean = false,
    onDraft: ((String) -> Unit)? = null
) {
    // Seeded per stored value so an external change lands, without fighting the keyboard.
    var text by remember(value) { mutableStateOf(value) }
    val error = if (allowEmpty) null else settingsTextError(text)

    fun commit() {
        if (error == null && text.trim() != value) onCommit(text)
    }

    /**
     * Every keystroke is the answer, for a field whose value nothing has stored yet.
     *
     * The same guard as [commit], reached from the other side: a caller that re-seeds this field
     * from what it commits will hold `text` and `value` equal, so the commit is skipped rather than
     * written back at the user. Text that differs only by whitespace is not committed either, which
     * is what lets a trailing space be typed without the value moving under it.
     */
    fun commitNow(candidate: String) {
        if (error == null && candidate.trim() != value) onCommit(candidate)
    }

    // Held through [rememberUpdatedState] so disposal writes what is in the field at that moment,
    // not whatever it held when the effect was set up. Committing something already stored is a
    // no-op, so a field that lost focus first is not written twice.
    val commitOnLeave by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitOnLeave() } }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onDraft?.invoke(it)
            if (commitAsYouType) commitNow(it)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}
