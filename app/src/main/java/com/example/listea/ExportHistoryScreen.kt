package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.listea.data.ExportRecordSummary
import com.example.listea.domain.ExportKind
import org.json.JSONObject

/**
 * Every export Listea has performed, grouped by the round that produced it.
 *
 * **One history and not one per method**, which is the shape v0.5.6 chose (*两种都记*). A round
 * over a List whose Todos disagree splits into several exports, and a reader asking *what did that
 * review do?* wants one answer rather than a page per mechanism. The storage keeps one row per
 * export — one row has one method, one address and one verdict — so this screen folds those rows
 * back into rounds with [historyRounds]: the parent card is the round, its children are the
 * exports, and a single-export round simply opens with its one child already showing.
 *
 * **The round is the unit because that is what the user did.** A row's [ExportRecordSummary.at] is
 * when that row last reached a verdict; resending one child moves it, so that round rises to the
 * top of the page, where the user just acted.
 *
 * Records are written by the delivery path, never here; this screen only replays them or throws
 * them away. Resend replays the stored body byte for byte — what arrives is what failed — to an
 * address the dialog prefills with the one the record used (the live default for a pre-27 row) and
 * lets the user change for this attempt only. It deliberately does not ask whether any switch is
 * on: a record may exist precisely because something was misconfigured, and the address is one of
 * the things it might have been. Both the progress and the result land in the same top-level
 * dialogs every other delivery uses.
 */
@Composable
fun ExportHistoryScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val records by viewModel.webhookHistory.collectAsStateWithLifecycle()
    val roundCount by viewModel.webhookRoundCount.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Survives rotation, and is only an id: the payload behind it is read when the dialog opens.
    var openRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var resendRecordId by rememberSaveable { mutableStateOf<Long?>(null) }

    BackHandler { onBack() }

    val rounds = historyRounds(records)

    ListeaNestedScaffold(
        modifier = modifier,
        title = "History",
        backLabel = "Settings",
        onBack = onBack,
        trailing = {
            Text(
                historyCountLabel(records.size, roundCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) { bodyModifier ->
        if (rounds.isEmpty()) {
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(ListeaDimens.PagePadding)
                ) {
                    Text(
                        "Nothing yet",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Every export Listea performs is kept here with its body — delivered or " +
                            "not — until you delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@ListeaNestedScaffold
        }

        LazyColumn(
            modifier = bodyModifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ListeaDimens.PagePadding,
                vertical = ListeaDimens.RowGap
            ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            items(rounds, key = { it.key }) { round ->
                HistoryRoundCard(
                    round = round,
                    onOpen = { openRecordId = it },
                    onDelete = { viewModel.deleteWebhookRecord(it) },
                    onResend = { resendRecordId = it }
                )
            }
        }
    }

    openRecordId?.let { id ->
        ExportBodyDialog(
            viewModel = viewModel,
            recordId = id,
            onDismiss = { openRecordId = null }
        )
    }

    val resendRecord = resendRecordId?.let { id -> records.firstOrNull { it.id == id } }
    if (resendRecord != null) {
        ResendDialog(
            record = resendRecord,
            defaultUrl = settings.defaultWebhookUrl,
            onDismiss = { resendRecordId = null },
            onResend = { address ->
                viewModel.resendWebhookRecord(resendRecord.id, address)
                resendRecordId = null
            }
        )
    }
}

/**
 * One round: when it last reached a verdict, what it was, and what the round did as a whole.
 *
 * The card is the way in and out of its children, the same way a list card is the way into its
 * list. A round with a single export opens expanded, because there is nothing to fold: the child is
 * the card's whole content and hiding it behind a tap would be a button whose only job is to make
 * the page emptier. A split round starts folded and says how many exports it holds.
 */
@Composable
private fun HistoryRoundCard(
    round: HistoryRound,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onResend: (Long) -> Unit
) {
    var expanded by rememberSaveable(round.key) {
        mutableStateOf(round.exportCount == 1)
    }

    OutlinedCard(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ListeaDimens.CardPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
                ) {
                    Text(
                        webhookTimestampLabel(round.at),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusLine(
                        listOfNotNull(
                            webhookEventLabel(round.event),
                            round.listTitle.ifEmpty { null },
                            countOfExports(round.exportCount)
                        ).joinToString(" · ")
                    )
                    StatusLine(round.outcome.label, tone = roundTone(round.outcome))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", maxLines = 1)
                }
            }

            if (expanded) {
                round.records.forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider()
                    ExportRecordRow(
                        record = record,
                        onOpen = { onOpen(record.id) },
                        onDelete = { onDelete(record.id) },
                        onResend = { onResend(record.id) }
                    )
                }
            }
        }
    }
}

/**
 * One export inside a round: which method carried it, to which address, how much, and what became
 * of it.
 *
 * Delete is immediate: what it removes is a record of something that already happened, not anything
 * the user made, and needing two taps each to clear a run of them would make the page tiring in
 * exactly the situation that produces a run. Resend is offered on a successful record too — sending
 * the same round twice is a thing people legitimately want, and the alternative is a button that
 * disappears exactly when the payload is known-good.
 *
 * *Resend* means *post this again*, which only says anything about a kind whose delivery is a POST.
 * A written text file has no receiver to send it back to, and an unreadable kind is not one to guess
 * a mechanism for, so both hide it.
 */
@Composable
private fun ExportRecordRow(
    record: ExportRecordSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onResend: () -> Unit
) {
    val kind = ExportKind.fromKey(record.kind)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ListeaDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
    ) {
        StatusLine(
            listOfNotNull(
                kind?.label ?: "Unknown export",
                countOfTasks(record.itemCount),
                recordAddressLabel(record)
            ).joinToString(" · ")
        )
        StatusLine(record.detail, tone = recordTone(record.outcome))
        Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
            TextButton(onClick = onOpen) { Text("Open payload", maxLines = 1) }
            if (kind == ExportKind.WEBHOOK) {
                TextButton(onClick = onResend) { Text("Resend", maxLines = 1) }
            }
            TextButton(onClick = onDelete) { Text("Delete", maxLines = 1) }
        }
    }
}

/**
 * Asks where this one replayed body should go, prefilled with where it went before.
 *
 * The prefill is the record's stored address; a row written before addresses were recorded has
 * none, so the live default stands in and the helper says the original was not recorded. Whatever
 * the user types is used for this attempt only — the ViewModel writes the outcome, never the
 * address — and the helper says that too, so an edited address does not read like a correction of
 * the record.
 */
@Composable
private fun ResendDialog(
    record: ExportRecordSummary,
    defaultUrl: String,
    onDismiss: () -> Unit,
    onResend: (String) -> Unit
) {
    val recorded = record.webhookUrl.trim()
    val prefill = recorded.ifEmpty { defaultUrl.trim() }
    var url by rememberSaveable(record.id) { mutableStateOf(prefill) }
    val problem = webhookUrlError(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resend export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
                Text(
                    "Posts the kept body again, exactly as it was.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Webhook address") },
                    singleLine = true,
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    when {
                        recorded.isEmpty() ->
                            "The original address was not recorded, so this is the current default."
                        url.trim() != prefill ->
                            "Used for this attempt only; the record keeps the address it first used."
                        else -> "The address this export used."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onResend(url.trim()) }
            ) { Text("Resend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Where a webhook row was posted, for a child line. Null for a text export, which has no address;
 * *address not recorded* for a row written before v0.6.2 kept one. The full URL is not shown, for
 * the same reason Settings shows a host: a page of addresses is not a page anyone reads.
 */
private fun recordAddressLabel(record: ExportRecordSummary): String? {
    if (ExportKind.fromKey(record.kind) != ExportKind.WEBHOOK) return null
    val stored = record.webhookUrl.trim()
    if (stored.isEmpty()) return "address not recorded"
    return webhookHost(stored) ?: "address not readable"
}

/**
 * How loudly a round's aggregate reads. Delivered is a success; a partial round and a failed one
 * are warnings; nothing attempted — declined, deferred, switched off, empty — is neutral. Nothing
 * went wrong in those cases: the user or the configuration meant it not to go, and colouring that
 * like a failure would make an ordinary choice look like a problem. An outcome this build cannot
 * name reads neutrally too.
 */
private fun roundTone(outcome: RoundOutcome): StatusTone = when (outcome) {
    RoundOutcome.Delivered -> StatusTone.Positive
    RoundOutcome.Partial, RoundOutcome.Failed -> StatusTone.Warning
    RoundOutcome.NotSent, RoundOutcome.Unknown -> StatusTone.Neutral
}

/**
 * How loudly a row's own line reads.
 *
 * Written counts as success for the same reason SENT does: the round left the app. The two are
 * separate outcomes because a POST has a code and a file has a name, not because one of them went
 * less well than the other. Everything else — switched off, declined — is neither.
 */
private fun recordTone(outcome: String): StatusTone = when (outcome) {
    NoticeOutcome.SENT.name, NoticeOutcome.WRITTEN.name -> StatusTone.Positive
    NoticeOutcome.FAILED.name -> StatusTone.Warning
    else -> StatusTone.Neutral
}

/**
 * The body itself, exactly as it was posted or would have been.
 *
 * Read only now, and only for this one record, because a payload is as big as the round it
 * covers. Nothing in here acts on anything: it scrolls, and it closes. Indented for reading, but
 * the stored text is what gets sent, so a body this cannot parse is shown raw rather than hidden.
 */
@Composable
private fun ExportBodyDialog(
    viewModel: ListsViewModel,
    recordId: Long,
    onDismiss: () -> Unit
) {
    val payload by produceState<String?>(null, recordId) {
        val stored = viewModel.webhookRecordPayload(recordId)
        value = stored?.let { withContext(Dispatchers.Default) { prettyJson(it) } } ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export body") },
        text = {
            Box(Modifier.heightIn(max = PayloadMaxHeight)) {
                Text(
                    payload ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Tall enough to be worth scrolling, short enough that the dialog stays a dialog. */
private val PayloadMaxHeight = 420.dp

/** Indented JSON, or the original text when it is not JSON this can re-read. */
private fun prettyJson(payload: String): String =
    runCatching { JSONObject(payload).toString(2) }.getOrDefault(payload)

/** "N exports · R rounds" when a round can hold more than one; just the exports otherwise. */
internal fun historyCountLabel(exports: Int, rounds: Int): String =
    buildList {
        add(countOfExports(exports))
        if (rounds != exports) add("$rounds round" + if (rounds == 1) "" else "s")
    }.joinToString(" · ")

private fun countOfExports(count: Int): String = "$count export" + if (count == 1) "" else "s"
