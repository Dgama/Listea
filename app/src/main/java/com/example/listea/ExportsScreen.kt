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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Every other management surface in Settings opens a dialog. This one does not, because a method's
 * configuration is a paragraph of explanation with controls in it, and the history it used to hold
 * has moved out to a page of its own at the bottom of the Settings index. The shape here is the one
 * Templates uses: a route in `SettingsScreen` with two levels, the list of methods and the method
 * opened out of it.
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
 * The Settings row's one line: how many methods there are, and where the webhook method points.
 *
 * History used to share this line. It is a page of its own now, reached from the bottom of the
 * Settings index, so this summary answers only what the Exports page is about. The webhook host is
 * the one fact worth knowing before opening it, and *not set* is the fact the user has to act on.
 */
internal fun exportMethodsSummary(defaultUrl: String): String {
    val methods = ExportKind.entries.size
    val host = webhookHost(defaultUrl.trim())
    val webhook = if (host == null) "webhook not set" else "webhook at $host"
    return "$methods methods · $webhook"
}

/** The list of methods, and the export-wide settings that are not about any one of them. */
@Composable
fun ExportListScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit,
    onOpenKind: (ExportKind) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
                    "Finishing a Quick Review exports the folder's Tasks. A folder belongs to no " +
                        "List, so each Task leaves by the method its own Todo chose."
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
                    label = "Export checked Tasks only",
                    icon = Icons.Filled.FilterList,
                    checked = settings.webhookCompletedItemsOnly,
                    onCheckedChange = { viewModel.setWebhookCompletedItemsOnly(it) }
                )
                HelperText(
                    "Every export carries only the Tasks that are checked. With \"Review " +
                        "unchecked Tasks only\" on, that is exactly the decisions of the round you " +
                        "just did. Nothing checked means nothing is exported, and Listea says so."
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
    onBack: () -> Unit
) {
    when (kind) {
        ExportKind.WEBHOOK -> WebhookExportScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = onBack
        )
        ExportKind.TXT -> TxtExportScreen(modifier = modifier, onBack = onBack)
    }
}

/**
 * The webhook method: where it goes by default, and the test button.
 *
 * **The default address is a value a Todo copies; it is not the only destination.** A Todo holds
 * the address it was configured with, and a List may override both. This page configures the
 * address offered as *Use default* and the one unowned Tasks fall back to — which is why changing it
 * here never moves an existing Todo.
 *
 * **Preselect the default address** is a preference about the Create Tasks dialog, not a delivery
 * gate. The gate was removed in v0.6.2: with an address set on the Todo, a round posts whether this
 * is on or off. The switch only decides which address radio a new Todo starts on.
 */
@Composable
private fun WebhookExportScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // What the field currently holds, committed or not. Kept here so the Test button can send to
    // the address the user is looking at: the field commits on Done or on leaving, and a tap does
    // not leave it, so the stored value is still the old one at the moment the button is pressed.
    // Seeded from (and reset by) the stored value, so an external change still lands.
    var draftUrl by rememberSaveable(settings.defaultWebhookUrl) {
        mutableStateOf(settings.defaultWebhookUrl)
    }

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
            SectionCard(title = "Default address") {
                SwitchRow(
                    label = "Preselect the default address",
                    checked = settings.defaultWebhookEnabled,
                    onCheckedChange = { viewModel.setDefaultWebhookEnabled(it) }
                )
                HelperText(
                    "When a Todo is created, start its webhook address on Use default. Off " +
                        "starts it on Custom. This does not enable delivery: a Todo posts to " +
                        "the address it holds, whatever this switch says."
                )
                SettingsTextField(
                    label = "Default URL",
                    value = settings.defaultWebhookUrl,
                    onCommit = { viewModel.setDefaultWebhookUrl(it) },
                    allowEmpty = true,
                    onDraft = { draftUrl = it }
                )
                HelperText(
                    "The value a Todo copies when it is created on Use default. A Todo keeps " +
                        "its own copy, so changing this later does not move an existing Todo; edit " +
                        "the Todo to change where it posts. Tasks no Todo owns fall back to it."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))

            SectionCard(title = "Test") {
                Button(
                    // The field's own text, not the stored default: a URL typed a moment ago is
                    // not committed until the field is left, and this button is pressed precisely
                    // to find out whether *that* address receives.
                    onClick = { viewModel.testWebhook(draftUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send a test payload", maxLines = 1)
                }
                HelperText(
                    "Posts one small payload to the default URL above, so a receiver can be " +
                        "checked without waiting for a round. It asks nothing first, because " +
                        "pressing a button called Test is the confirmation."
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
                    "A header naming the round, then one line per Task: its id, its name, " +
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
        }
    }
}
