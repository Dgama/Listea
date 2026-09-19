package com.example.listea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.domain.ExportKind

/**
 * The export manager, and the two screens it is made of.
 *
 * ## Why a page and not a dialog
 *
 * Every other management surface in Settings opens a dialog. This one does not, and the user gave
 * the reason in as many words: a webhook's own configuration contains a history, and a history is
 * itself a dialog — so configuring a webhook inside a dialog would be a dialog inside a dialog.
 * The shape here is the one Templates already uses: an **early-return branch** in `SettingsScreen`
 * with two levels, the list of methods and the method opened out of it.
 *
 * ## What lives where
 *
 * The three questions an export has are answered on three different surfaces, and the split is on
 * what the answer is about:
 *
 * | Question | Surface |
 * |---|---|
 * | **When** a round exports, and **what** goes into it | the list screen, because they are true of every method |
 * | **Where** one method sends | that method's own page |
 * | **Which** method a Todo or a List uses | the Todo's import dialog, and the List's card |
 *
 * ## The three switches are export-wide, and that is a decision
 *
 * *Send from Quick Review*, *Send when leaving a review* and *Send checked items only* were drawn
 * inside a section headed **Webhook**, which made them look like statements about HTTP. They are
 * not: the two send switches are about *when a round exports*, and the third is about *what a
 * payload carries*. Every one of them applies to whatever method ends up delivering, so they sit
 * one level above the methods rather than inside one of them.
 *
 * Their DataStore keys are unchanged. A key is a stored literal in the same sense a migration is —
 * renaming `quick_review_webhook_enabled` would silently reset it for every install that has it —
 * so the field names and the labels change and the strings on disk do not.
 */

/**
 * The Settings row's one line: how many methods there are, and how the history stands.
 *
 * Both facts, because they are what the page behind the button is for — the things a round can go
 * to, and the record of everything that ever did. "not delivered" counts across every method, since
 * an unfinished export is unfinished whatever was supposed to carry it.
 */
internal fun exportMethodsSummary(historyCount: Int, pendingCount: Int): String {
    val methods = ExportKind.entries.size
    val history = when {
        historyCount == 0 -> "nothing exported yet"
        pendingCount == 0 -> "$historyCount record" + (if (historyCount == 1) "" else "s")
        else -> "$historyCount records · $pendingCount not delivered"
    }
    return "$methods methods · $history"
}

/** The list of methods, and the export-wide settings that are not about any one of them. */
@Composable
fun ExportListScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit,
    onOpenKind: (ExportKind) -> Unit,
    onHistoryOpenChange: (Boolean) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val historyCount by viewModel.webhookRecordCount.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingWebhookCount.collectAsStateWithLifecycle()

    ListeaNestedScaffold(
        title = "Exports",
        backLabel = "Settings",
        onBack = onBack,
        modifier = modifier
    ) { bodyModifier ->
        Column(
            modifier = bodyModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ListeaDimens.PagePadding)
        ) {
            SectionCard(title = "Methods") {
                Text(
                    "Where a round's decisions can go. A Todo picks one when it is created, and a " +
                        "List can override it for everything it holds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExportKind.entries.forEach { kind ->
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    ExportKindRow(
                        kind = kind,
                        status = exportKindStatus(kind, settings.defaultWebhookUrl),
                        onOpen = { onOpenKind(kind) }
                    )
                }
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            // The three switches, which every method obeys and none of them owns.
            SectionCard(title = "Every export") {
                SwitchRow(
                    label = "Export from Quick Review",
                    icon = Icons.Filled.Visibility,
                    checked = settings.quickReviewWebhookEnabled,
                    onCheckedChange = { viewModel.setQuickReviewWebhookEnabled(it) }
                )
                HelperText(
                    "Finishing a Quick Review exports the folder's items. A folder belongs to no " +
                        "List, so each item leaves by the method its own Todo chose."
                )

                SwitchRow(
                    label = "Export when leaving a review",
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    checked = settings.webhookOnReviewExit,
                    onCheckedChange = { viewModel.setWebhookOnReviewExit(it) }
                )
                HelperText(
                    "Leaving Review or Quick Review exports what that round covered, by whatever " +
                        "method the List and its Todos say. A queue you finished on screen does " +
                        "not export twice, and a round with nothing in it is reported instead of " +
                        "exported."
                )

                SwitchRow(
                    label = "Export checked items only",
                    icon = Icons.Filled.FilterList,
                    checked = settings.webhookCompletedItemsOnly,
                    onCheckedChange = { viewModel.setWebhookCompletedItemsOnly(it) }
                )
                HelperText(
                    "Every export carries only the items that are checked. With \"Review " +
                        "unchecked items only\" on, that is exactly the decisions of the round you " +
                        "just did. Nothing checked means nothing is exported, and Listea says so."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "History") {
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
                            Text("Every export", style = MaterialTheme.typography.bodyMedium)
                        }
                        // Two numbers, because they answer different questions: how much has ever
                        // been built, and how much of it a receiver has still not accepted. Only
                        // the second is worth a warning, and only when it is not zero.
                        StatusLine(
                            when {
                                historyCount == 0 -> "Nothing yet"
                                pendingCount == 0 ->
                                    "$historyCount record" + (if (historyCount == 1) "" else "s") +
                                        " · all delivered"

                                else ->
                                    "$historyCount record" + (if (historyCount == 1) "" else "s") +
                                        " · $pendingCount not delivered"
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
                    "Every export Listea performs is kept here with its body and what became of " +
                        "it — delivered, failed, or not attempted at all — so a round of review is " +
                        "never lost and you can always check whether one went out. A text export " +
                        "is recorded too, so a file that was written is as visible as a POST that " +
                        "was sent."
                )
            }
        }
    }
}

/** One method, as a row that leads to its own page. */
@Composable
private fun ExportKindRow(
    kind: ExportKind,
    status: String,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        Icon(
            when (kind) {
                ExportKind.WEBHOOK -> Icons.AutoMirrored.Filled.Send
                ExportKind.TXT -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ListeaDimens.IconSize)
        )
        Column(Modifier.weight(1f)) {
            Text(kind.label, style = MaterialTheme.typography.bodyMedium)
            StatusLine(status)
        }
        Button(onClick = onOpen) { Text("Open", maxLines = 1) }
    }
}

/**
 * What one method is currently pointed at, in a line.
 *
 * Where a method sends is the first thing a reader wants and the one thing that is different
 * between them, so it is the whole of the status. A method that is configured but has nowhere to
 * go says so rather than staying silent — *Not set* is the fact the user has to act on.
 */
private fun exportKindStatus(kind: ExportKind, defaultUrl: String): String = when (kind) {
    ExportKind.WEBHOOK -> {
        val host = webhookHost(defaultUrl.trim())
        if (host == null) "Not set" else host
    }
    ExportKind.TXT -> ListeaExportRelativePath + "/"
}

/** One method's page. Dispatches on the kind, because a method's configuration is its own. */
@Composable
fun ExportDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    kind: ExportKind,
    onBack: () -> Unit,
    onHistoryOpenChange: (Boolean) -> Unit
) {
    when (kind) {
        ExportKind.WEBHOOK -> WebhookExportScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = onBack,
            onHistoryOpenChange = onHistoryOpenChange
        )
        ExportKind.TXT -> TxtExportScreen(modifier = modifier, onBack = onBack)
    }
}

/**
 * The webhook method: everywhere it sends, and nothing about when or what.
 *
 * **Default URL is the only URL there is**, as of v0.5.6. A List used to carry its own, seeded
 * from this value when the List was created and never updated afterwards — so changing the default
 * reached no existing List, and a List's copy was a stale answer wearing a setting's clothes. That
 * field is gone and this one is now read by every webhook the app performs, which is what makes
 * the helper text below true rather than aspirational.
 *
 * **Default enabled** survives. It is the webhook method's own switch, asked per webhook group
 * rather than per round: a List whose Todos export by webhook and by txt will write its file
 * whether or not this is on, and will only go quiet about the POSTs.
 */
@Composable
private fun WebhookExportScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit,
    onHistoryOpenChange: (Boolean) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    ListeaNestedScaffold(
        title = "Webhook",
        backLabel = "Exports",
        onBack = onBack,
        modifier = modifier
    ) { bodyModifier ->
        Column(
            modifier = bodyModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ListeaDimens.PagePadding)
        ) {
            SectionCard(title = "Delivery") {
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
                    "The address every webhook in this app posts to. No List keeps one of its " +
                        "own — a Todo or a List chooses *webhook* as its method, and the POST " +
                        "goes here."
                )
                HelperText(
                    "With Default enabled off, webhook exports are not attempted and a round " +
                        "that would have posted says so. Method choices are not changed by this " +
                        "switch: turning it back on restores them exactly as they were."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "Test") {
                Button(
                    onClick = { viewModel.testWebhook() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send a test payload", maxLines = 1)
                }
                HelperText(
                    "Posts one small payload to the URL above, so a receiver can be checked " +
                        "without waiting for a round. It asks nothing first, because pressing a " +
                        "button called *Test* is the confirmation."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "History") {
                Button(
                    onClick = { onHistoryOpenChange(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open history", maxLines = 1)
                }
                HelperText(
                    "The same history the Exports screen leads to — every method's exports and " +
                        "every method's failures, in one place, because a round that split " +
                        "across two methods is one round."
                )
            }
        }
    }
}

/**
 * The txt method.
 *
 * **Nothing to configure, and that is the shape of it for now.** The destination is stated and
 * not editable — a file per round, in the same album the media save already writes to — and there
 * is deliberately nothing else: no name template, no format, no schedule. The release's job is to
 * prove that *webhook* was one kind among several, and a second kind with a settings page of its
 * own would have been a second thing to get wrong rather than a smaller first one.
 */
@Composable
private fun TxtExportScreen(modifier: Modifier, onBack: () -> Unit) {
    ListeaNestedScaffold(
        title = "Txt",
        backLabel = "Exports",
        onBack = onBack,
        modifier = modifier
    ) { bodyModifier ->
        Column(
            modifier = bodyModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ListeaDimens.PagePadding)
        ) {
            SectionCard(title = "Destination") {
                Text(
                    "One file per round, written to",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    ListeaExportRelativePath + "/",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                HelperText(
                    "The folder sits beside the images Listea saves, so it is in the phone's " +
                        "gallery folder and reachable by any file manager, cable or sync app — " +
                        "without Listea needing a second permission for it."
                )
                HelperText(
                    "Each file is named for the moment the round ended, the event, and where it " +
                        "came from, so two rounds a second apart do not overwrite each other."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "What is in the file") {
                Text(
                    "A header naming the round, then one line per item: its id, its name, " +
                        "whether it is checked, its folder, and the actions it carries. The same " +
                        "facts a webhook POST carries, in a plain tab-separated form.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HelperText(
                    "It is written, not sent: there is no URL, no network, and nothing to " +
                        "configure. A file that could not be written is reported the same way a " +
                        "failed POST is."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "History") {
                Text(
                    "Every text export is recorded in the same history the webhook method uses, " +
                        "with the file's name against it, so *did that round write a file?* is a " +
                        "question with an answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
