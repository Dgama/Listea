package com.example.listea

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Stable, long-lived configuration, gathered out of the business screens.
 *
 * This is where the app is told what it may look at, and that now includes the folders themselves:
 * adding an Upstream is the old "select folder", and it lives here because it is a fact about the
 * app rather than a place in it. The Upstreams tab lists what has been added and opens it — an
 * index, not an editor.
 *
 * Switches persist on the spot. Text fields persist when they lose focus or the user presses Done,
 * so a half-typed URL is never written and DataStore is not hit on every keystroke.
 *
 * V4.3 puts each group in the same [SectionCard] the management screens use, so this reads as a
 * page of the app rather than the settings form it used to be. One scroll surface, as everywhere.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: ListsViewModel = viewModel(),
    historyOpen: Boolean = false,
    onHistoryOpenChange: (Boolean) -> Unit = {}
) {
    // A page of its own rather than a section of this one: the payloads it holds are the app's,
    // not any one list's, and Settings is where the webhook they were headed for is configured.
    if (historyOpen) {
        WebhookHistoryScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = { onHistoryOpenChange(false) }
        )
        return
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val historyCount by viewModel.webhookRecordCount.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingWebhookCount.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteCheckedRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val upstreams by viewModel.upstreams.collectAsStateWithLifecycle()

    // Bumped by anything that changes what a walk of an Upstream would find, so the sizes on
    // screen are not left describing files that are no longer there.
    var storageGeneration by remember { mutableStateOf(0) }

    // The folders the app still holds a grant for. Keyed on the list of Upstreams as well as on the
    // context so that adding one re-reads it: the grant is taken in the picker's callback below,
    // and the row it just made would otherwise sit there saying it had no access.
    val heldRoots = remember(context, upstreams) { heldRootUris(context) }

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

    // Why the messages survive the trip: the picker is another activity, and coming back to be
    // told nothing about what just happened would be the worst part of either flow.
    var grantNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var addNotice by rememberSaveable { mutableStateOf<String?>(null) }

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
        // refused; what becomes unavailable is deleting, and the File management card says so.
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

    /**
     * Asking for permission to change a folder the app can already read, which is its own trip
     * through the picker and so gets its own launcher.
     *
     * Deliberately not the one above: that one registers whatever comes back as a new Upstream.
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

    // One scroll surface for the whole page; no subsection scrolls on its own.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
    ) {
        Spacer(Modifier.height(ListeaDimens.CompactGap))

        // The one place a folder is added to Listea, and the list of the ones already added.
        //
        // It is here rather than on the Upstreams tab because it configures what the app holds,
        // not what it is showing: the Upstreams tab is a list you open things from, and a button
        // that changed that list from inside it made the tab both the index and the editor. The
        // old Folder tab had no "add folder" for the same reason, and this is what the old
        // "select folder" was — one folder, granted once, listed here.
        SectionCard(title = "Upstreams") {
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
                    generation = storageGeneration
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

        SectionCard(title = "Review") {
            SwitchRow(
                label = "Enable Quick Review in Todo",
                icon = Icons.Filled.Visibility,
                checked = settings.folderQuickReviewEnabled,
                onCheckedChange = { viewModel.setFolderQuickReviewEnabled(it) }
            )
            // Said in the two places the switch reaches, because it reaches two: the Todo tab,
            // where it takes the action away, and the Upstreams browser, where it stops drawing
            // what has already been decided. The sentence this replaces described a Folder tab
            // that no longer exists.
            HelperText(
                "Off takes Quick Review off the Todo tab, and stops the Upstreams browser " +
                    "showing which files are already checked. Lists keep their items, " +
                    "completion and actions."
            )

        }

        // The template manager. One section per kind the asset-control definition accepts, generated
        // from it rather than listed here, so what a user may configure and what the control actually
        // governs cannot drift apart — the definition is the one answer to both.
        SectionCard(title = "Asset controls") {
            HelperText(
                "An image and a video are controlled separately, so a tag set for one is not " +
                    "offered on the other. These are the tags a new Todo starts with; a Todo " +
                    "remembers what it was actually given, and Tasks already imported keep theirs."
            )
            configurableAssetKinds().forEach { kind ->
                AssetControlSection(kind = kind, settings = settings, viewModel = viewModel)
            }
        }

        SectionCard(title = "Media and review behaviour") {
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

        // A section of its own rather than two more rows on the card above, because neither of these
        // is about how a screen behaves: they are what a *new* Todo is created with, and the Todo's
        // own card is where they are changed once it exists. Grouping them with the review switches
        // would suggest they act on the review in front of the user, which is exactly what they do
        // not do.
        SectionCard(title = "Todos") {
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

        SectionCard(title = "Webhook") {
            SwitchRow(
                label = "Default enabled",
                checked = settings.defaultWebhookEnabled,
                onCheckedChange = { viewModel.setDefaultWebhookEnabled(it) }
            )
            SettingsTextField(
                label = "Default URL",
                value = settings.defaultWebhookUrl,
                onCommit = { viewModel.setDefaultWebhookUrl(it) },
                allowEmpty = true
            )
            HelperText(
                "Copied into new Lists when they are created — existing Lists are never changed " +
                    "— and used directly by anything that posts through the default webhook, " +
                    "which the switch above turns on and off."
            )

            SwitchRow(
                label = "Send from Quick Review",
                icon = Icons.Filled.Visibility,
                checked = settings.quickReviewWebhookEnabled,
                onCheckedChange = { viewModel.setQuickReviewWebhookEnabled(it) }
            )
            HelperText(
                "Finishing a Quick Review posts the folder's items through the default webhook " +
                    "above — Quick Review has no webhook of its own — so it needs Default " +
                    "enabled on and a URL filled in. Anything missing is reported rather than " +
                    "passing silently."
            )

            SwitchRow(
                label = "Send when leaving a review",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                checked = settings.webhookOnReviewExit,
                onCheckedChange = { viewModel.setWebhookOnReviewExit(it) }
            )
            HelperText(
                "Leaving Review or Quick Review sends what that round covered. Review uses the " +
                    "List's own webhook; Quick Review uses the default webhook and also needs " +
                    "the switch above. A queue you finished on screen does not send twice, and a " +
                    "round with nothing in it is reported instead of sent."
            )

            SwitchRow(
                label = "Send checked items only",
                icon = Icons.Filled.FilterList,
                checked = settings.webhookCompletedItemsOnly,
                onCheckedChange = { viewModel.setWebhookCompletedItemsOnly(it) }
            )
            HelperText(
                "Every payload carries only the items that are checked, the test webhook " +
                    "included. With \"Review unchecked items only\" on, that is exactly the " +
                    "decisions of the round you just did. Nothing checked means nothing is sent, " +
                    "and Listea says so."
            )

            HorizontalDivider(Modifier.padding(top = ListeaDimens.CompactGap))

            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text("Webhook history", style = MaterialTheme.typography.bodyMedium)
                    }
                    // Two numbers, because they answer different questions: how much has ever
                    // been built, and how much of it a receiver has still not accepted. Only the
                    // second is worth a warning, and it is mentioned only when it is not zero.
                    StatusLine(
                        when {
                            historyCount == 0 -> "Nothing yet"
                            pendingCount == 0 ->
                                "$historyCount record" + (if (historyCount == 1) "" else "s") +
                                    " · all sent"

                            else ->
                                "$historyCount record" + (if (historyCount == 1) "" else "s") +
                                    " · $pendingCount not sent"
                        },
                        tone = if (pendingCount == 0) StatusTone.Neutral else StatusTone.Warning
                    )
                },
                actions = {
                    Button(
                        onClick = { onHistoryOpenChange(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open", maxLines = 1)
                    }
                }
            )
            HelperText(
                "Every webhook Listea builds is kept here with its payload and what became of " +
                    "it — sent, failed, or not sent at all — so a round of review is never lost " +
                    "and you can always check whether one went out. Resending uses the default " +
                    "URL above and needs nothing else switched on."
            )
        }

        SectionCard(title = "File management") {
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
                    "still there, and the round stays in Webhook history above to be resent — " +
                    "or you can clear the checked files here instead."
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

        Spacer(Modifier.height(ListeaDimens.SectionGap))
    }

    // The sizes in the Upstreams card describe files that have just stopped existing, so a
    // finished run sends the walks round again rather than leaving them to go quietly stale.
    LaunchedEffect(deleteRequest) {
        if (deleteRequest is DeleteCheckedRequest.Done) storageGeneration++
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
    generation: Int
) {
    val context = LocalContext.current
    val storage by produceState<StorageInfo>(
        StorageInfo.Scanning,
        upstream.id,
        upstream.relativePath,
        available,
        generation
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
 * One kind's tags: the heading, the rows, and the button that adds another.
 *
 * Stateless, and that is the point of it existing separately from its two callers, because they own
 * their tags in two different places. Settings edits what an install starts with and writes each
 * keystroke straight to the store; the Create Tasks dialog edits *one run's* answer, held in the
 * dialog's own draft and written only on Create. Neither difference reaches this function — it is
 * given a list and four callbacks — which is what keeps the tag's shape decided once rather than
 * once per surface.
 *
 * The rows are [CustomActionFields] unchanged: the same two fields, the same remove button, the
 * same layout. The only thing a kind adds to a screen is its heading and its Add button.
 *
 * **[keyPrefix] is not decoration.** Two kinds' sections draw rows with the same ids — both start
 * from `custom1` and `custom2` — so a key of the id alone would let the video section's second row
 * be reused as the image section's, and the text state inside [SettingsTextField] is seeded per
 * stored value. The key has to name the list the row belongs to, not just the row.
 */
@Composable
internal fun AssetControlTagEditor(
    heading: String,
    tags: List<CustomAction>,
    keyPrefix: Any,
    onRename: (id: String, displayName: String) -> Unit,
    onRewire: (id: String, webhookValue: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onAdd: () -> Unit
) {
    Spacer(Modifier.height(ListeaDimens.RowGap))
    Text(heading, style = MaterialTheme.typography.titleSmall)

    tags.forEachIndexed { index, action ->
        key(keyPrefix, action.id) {
            CustomActionFields(
                title = "Custom action ${index + 1}",
                action = action,
                onDisplayNameCommit = { onRename(action.id, it) },
                onWebhookValueCommit = { onRewire(action.id, it) },
                onRemove = { onRemove(action.id) }
            )
        }
    }

    Spacer(Modifier.height(ListeaDimens.RowGap))
    TextButton(onClick = onAdd) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(ListeaDimens.RowGap))
        Text("Add custom action")
    }
    HelperText(
        "The display name is what you see on the chips. The webhook value is what future payloads " +
            "send. Items already marked keep their selection, and removing an action only takes it " +
            "off the bar — nothing marked with it is changed."
    )
}

/** One kind's tags, as the Settings template manager draws them: the editor over stored state. */
@Composable
private fun AssetControlSection(
    kind: AssetKind,
    settings: AppSettings,
    viewModel: ListsViewModel
) {
    AssetControlTagEditor(
        heading = assetControlHeading(kind),
        tags = settings.assetControlTags[kind].orEmpty(),
        keyPrefix = kind,
        onRename = { id, name -> viewModel.setCustomActionDisplayName(kind, id, name) },
        onRewire = { id, value -> viewModel.setCustomActionWebhookValue(kind, id, value) },
        onRemove = { id -> viewModel.removeCustomAction(kind, id) },
        onAdd = { viewModel.addCustomAction(kind) }
    )
}

/**
 * One custom action's two fields: what it is called, and what it sends.
 *
 * Side by side where both stay readable, stacked where they would not. The threshold is about the
 * fields rather than the device: a text field narrower than this stops showing enough of its own
 * value to edit it, and two cramped fields are worse than two full-width ones.
 *
 * `internal` rather than private because there are two callers since v0.5.0 and they are not both on
 * this screen: Settings edits the tags an install starts with, and the Create Tasks dialog edits the
 * tags one run is to be given. The second is a *draft* — its own local state, committed only on
 * Create — which is why this takes an action and three callbacks rather than reading or writing
 * anything, and why both callers can share it without either owning it.
 */
@Composable
internal fun CustomActionFields(
    title: String,
    action: CustomAction,
    onDisplayNameCommit: (String) -> Unit,
    onWebhookValueCommit: (String) -> Unit,
    onRemove: () -> Unit
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
                    modifier = Modifier.weight(1f)
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = action.webhookValue,
                    onCommit = onWebhookValueCommit,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column {
                SettingsTextField(
                    label = "Display name",
                    value = action.displayName,
                    onCommit = onDisplayNameCommit
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = action.webhookValue,
                    onCommit = onWebhookValueCommit
                )
            }
        }
    }
}

/** Two 150dp fields and the gap between them: below this a field stops showing enough to edit. */
private val CustomActionSideBySideMinWidth = 308.dp

/** The quiet line under a control that explains it, in one place so they all match. */
@Composable
private fun HelperText(text: String, tone: StatusTone = StatusTone.Neutral) {
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

@Composable
private fun SwitchRow(
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
 */
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false
) {
    // Seeded per stored value so an external change lands, without fighting the keyboard.
    var text by remember(value) { mutableStateOf(value) }
    val error = if (allowEmpty) null else settingsTextError(text)

    fun commit() {
        if (error == null && text.trim() != value) onCommit(text)
    }

    // Held through [rememberUpdatedState] so disposal writes what is in the field at that moment,
    // not whatever it held when the effect was set up. Committing something already stored is a
    // no-op, so a field that lost focus first is not written twice.
    val commitOnLeave by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitOnLeave() } }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
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
