package com.example.listea

import android.content.Context
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.ReviewItem
import com.example.listea.data.assetDecisions
import com.example.listea.data.decisions
import com.example.listea.data.encodeActionIds
import com.example.listea.data.escapeField
import com.example.listea.data.taskDecisions
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What an export *is*, and the two ways this build knows how to perform one.
 *
 * ## The split this file exists to make
 *
 * Before v0.5.6 the app had one way out and it was named after its mechanism: every table, switch
 * and screen said *webhook*, and each of those names was answering a question about *when* or
 * *what* with a word about *how*. [ExportKind] splits those questions apart, and this file is the
 * seam they are split along:
 *
 * - [ExportPayload] is **what leaves** — one round's items, narrowed to the group being exported,
 *   described in terms every mechanism can use.
 * - [Exporter] is **how it leaves** — a renderer and a destination, and neither is data.
 *
 * The JSON body the webhook sends is produced from the same payload by the same builder as before
 * this release ([buildWebhookPayload]), which is what makes the text file and the POST two
 * renderings of one thing rather than two implementations of a similar idea.
 *
 * ## Everything here is one round's
 *
 * [ExportPayload.at] is read once, by the dispatcher, and used by the record, the header and the
 * file name. One clock read for one round, so a file named `…-143022` cannot contain a header that
 * says a different second — and so the two are assertable in a test rather than being a thing that
 * is usually right.
 */

/** The version line every text export starts with, in the shape `DissolvedRecords` and the template encoders already use. */
const val EXPORT_TEXT_VERSION = 1

/**
 * One item, as every mechanism sees it.
 *
 * The item's **folder** rather than its whole path, matching the wire body: the file name is already
 * here as [title], so a reader that wants the pair joins them and neither is sent twice.
 * [relativePath] is null for a manual item, which has no source file at all — a fake path would be
 * worse than none for a downstream tool, and it is the same answer the body gives.
 */
data class ExportItemView(
    val publicId: String,
    val title: String,
    val isCompleted: Boolean,
    val relativePath: String?,
    /** Action wire values, resolved from the Task's control as it stands now, never as it stood when tapped. */
    val actions: List<String>
)

/**
 * One item as the payload sees it.
 *
 * The single place an item's wire facts are assembled, so the body and the text file cannot drift:
 * both renderers walk the same [ExportItemView]s, and a field added to one is a field added to both
 * or to neither. **One entry per Task, resolved over every kind the Task is configured for** — the
 * union, exactly as the badge row resolves it, for the reason [buildWebhookPayload] gives.
 */
fun exportItemView(
    list: ListEntity,
    item: ListItemEntity,
    assetControls: String,
    resolver: TemplateResolver
): ExportItemView = ExportItemView(
    publicId = item.publicId,
    title = item.title,
    isCompleted = item.isCompleted,
    relativePath = webhookRelativePath(list, item),
    actions = itemActionNames(item.decisions.assetDecisions, resolver.controlForTask(assetControls))
)

/**
 * A review item as a row, purely so the one payload builder can serialise it. Never inserted: a
 * folder-resolved file is not a member of any list and must not become one by accident.
 *
 * **This row is a folder round's only chance to say which Task a card was.** A List round's rows come out of
 * `list_items`, where the Task id is a stored column; a folder round resolves files, so its rows are
 * minted here, and [taskId] is the one field of this row that is *read back* rather than merely
 * carried into the body. Two readers, both of them the round's routing:
 *
 * - `planExports` groups a round by the Todo its items came from, and it looks each item up by Task
 *   id. A row that hard-coded `""` puts **every** item of **every** folder round in one group with
 *   no source — which is [ExportChoice.Inherit]'s *nobody owns this* branch, and it is why a folder
 *   round over a *Txt* Todo posted to the webhook instead of writing a file.
 * - the round's `assetControls` map is keyed the same way, so the same field is what lets an item's
 *   `actions` be resolved from its Task's control rather than standing empty.
 *
 * The row is still never inserted, and [listId] and [createdAt] are still zero for that reason —
 * they are the fields nothing reads back.
 */
internal fun ReviewItem.toPayloadRow(sortOrder: Int) = ListItemEntity(
    id = id,
    publicId = publicId,
    listId = 0,
    taskId = taskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder,
    createdAt = 0,
    sourceUri = sourceUri,
    sourceRelativePath = relativePath,
    rootRelativePath = (target as? DecisionTarget.File)?.relativePath,
    sourceMissing = sourceMissing,
    // The fold, and not any one page's. A payload row is one entry for the whole Task, so the
    // marks it reports are every mark the Task carries — the same rule by which the payload has
    // always been able to name a tag set on a file that is not the one on screen. The pair the
    // tag was set on is not reconstructed here and is not sent: a receiver is told what the
    // Task says, once.
    selections = encodeActionIds(taskDecisions.selections)
)

/**
 * One round's payload for **one effective mechanism**.
 *
 * A round over a List that has not chosen its own kind produces several of these — one per source
 * Todo — and each is delivered by that Todo's kind. The split is a sum computed when the round ends,
 * never a list stored on a row, which is why [sourceId] and [sourceTitle] are here and on no entity:
 * they name *this payload's* origin, and a payload exists for the length of one delivery.
 *
 * [sourceTitle] is null when the List chose for the whole round, and that is not a gap. The header
 * already carries a `list` line, so an empty `source` says unambiguously *this came from the List's
 * own choice and not from any Todo's* — which is a different fact from "this came from a Todo that
 * has no name", and the two must not be spelled the same way.
 */
data class ExportPayload(
    val event: String,
    val listId: Long,
    val listTitle: String,
    val completedAt: Long?,
    /** The Todo this group came from, or null when the List chose or no Todo owns the items. */
    val sourceId: String?,
    val sourceTitle: String?,
    /** When this delivery was attempted. One clock read for the round. */
    val at: Long,
    val items: List<ExportItemView>
) {
    /** The `source` field of a text export, empty when the List chose. */
    val sourceField: String get() = sourceTitle.orEmpty()

    /**
     * The origin said in one word, for a file name: the Todo's name, or the List's when the List is
     * the one that chose. A file name has no room for both, and *which round is this* is the
     * question it answers — never *which Todo*, which the header inside answers properly.
     */
    val originLabel: String get() = sourceTitle ?: listTitle
}

/** How one attempt ended, in the three states a caller has to tell apart. */
sealed interface ExportAttempt {
    /**
     * It left. Where it went is said in exactly one of two ways, and never both.
     *
     * [httpCode] for anything that went over the wire, [location] for anything that went to a
     * place — a file has a name rather than a status, and a notice that had to guess which of the
     * two it was holding would say the wrong thing about one of them.
     */
    data class Delivered(val httpCode: Int? = null, val location: String? = null) : ExportAttempt

    /** It was attempted and did not get there. Recorded as a failure, and the record can be resent. */
    data class Failed(val detail: String?) : ExportAttempt

    /**
     * Nothing was attempted — no URL configured, or a switch that is off.
     *
     * Distinct from [Failed] because nothing failed and there is nothing to retry: a user who has
     * not set a URL has not had a delivery go wrong.
     */
    data object Skipped : ExportAttempt
}

/**
 * One way of getting decisions out of the app.
 *
 * Two functions rather than one, and the split is on what can be tested: [render] is **pure** — no
 * context, no network, no clock — so the bytes a kind produces are assertable in a plain JVM test,
 * and a format is a thing the suite holds rather than a thing the user finds out about. [deliver]
 * touches the outside world and reports rather than throws.
 *
 * Configuration is taken at construction, so [deliver] takes only the payload. That is what lets a
 * caller build the right exporter for a group and hand it one thing.
 */
interface Exporter {
    val kind: ExportKind

    /**
     * Whether this kind asks the user before it acts.
     *
     * True for anything that leaves the device, where an accidental round is hard to take back.
     * False for a write into the user's own album, which is neither outward-facing nor difficult to
     * undo — and asking every round would train the user to tap through the one dialog that matters.
     */
    val asksBeforeDelivering: Boolean get() = true

    /**
     * What finishing looks like for this kind, for the notice the user reads and the outcome the
     * history stores.
     *
     * On the kind rather than decided by the dispatcher from a list of the kinds it knows, so that
     * adding one stays a class and a `when` branch: a second mechanism whose success is not a POST
     * should not have to be taught to the code that loops over mechanisms.
     */
    val deliveredOutcome: NoticeOutcome get() = NoticeOutcome.SENT

    /**
     * The bytes this kind writes. Pure: same payload, same answer.
     *
     * No resolver, because there is nothing left to look up: the payload's items are already
     * [ExportItemView]s, assembled by [exportItemView] *before* the payload was built, so an action
     * that is on the wire is on the wire for both mechanisms by construction. A renderer that could
     * still reach the tables would be a second place for the two to disagree.
     */
    fun render(payload: ExportPayload): String

    /**
     * Does whatever this kind does with those bytes. Never throws; a failure is a result.
     *
     * **Runs its own blocking work off the caller's thread.** A delivery is a socket or a file,
     * and the callers here are a checkbox tap and a swipe: `deliver` is reached from the main
     * thread, and an implementation that opened its connection there would die with
     * `NetworkOnMainThreadException` rather than deliver anything. Stated on the interface because
     * it is not otherwise discoverable — `suspend` where a caller is already on a coroutine
     * suggests the opposite — and because it is cheaper than every future kind remembering.
     */
    suspend fun deliver(payload: ExportPayload, body: String): ExportAttempt
}

/**
 * The exporter for one kind, or null when there is nothing for it to deliver to.
 *
 * A `when` over the enum rather than a map, so the compiler's exhaustiveness check is what keeps a
 * third kind from being added without a branch here. Null rather than an exporter that fails: "there
 * is no URL" is [ExportAttempt.Skipped], and a caller should not have to ask an exporter whether it
 * is in a position to do anything.
 */
fun exporterFor(kind: ExportKind, url: String, context: Context): Exporter? = when (kind) {
    ExportKind.WEBHOOK -> url.trim().takeIf { it.isNotEmpty() }?.let { WebhookExporter(it) }
    ExportKind.TXT -> TxtExporter(context)
}

// --- which payloads a round becomes ------------------------------------------------------------

/** A Todo, as the group it owns needs it. */
data class ExportSource(val id: String, val title: String, val kind: ExportKind?)

/**
 * One group of a round: the items that will leave together, and the mechanism they will leave by.
 *
 * The mechanism is always a kind and never an [ExportChoice]: a group only exists because something
 * was decided for it, so a group whose answer was *nothing* was never planned. That is what makes
 * the dispatcher's loop total — it has no branch for *do not export*, because there is nothing to
 * loop over.
 */
data class PlannedExport(
    val kind: ExportKind,
    val sourceId: String?,
    val sourceTitle: String?,
    val items: List<ListItemEntity>
)

/**
 * The payloads one round becomes. **The release's headline rule, and the only place it is decided.**
 *
 * ```
 * a List that chose a kind → one group, its kind, no source
 * a List that chose nothing→ no groups at all
 * a List that did not      → one group per source Todo, that Todo's kind
 * a Todo that said nothing → its group is absent, not empty
 * an item no Todo owns     → its own group, by WEBHOOK
 * ```
 *
 * Pure, and takes `sourceOf` as a function rather than a map so that a test can decide what a round
 * looks like without a database — the same reason [Exporter.render] takes no context. The caller
 * hands it the lookup it already has; nothing here reads a table.
 *
 * **An unreadable kind is an absent group too**, and that is the failing-closed rule rather than a
 * convenience: [ExportChoice.Nothing] is what an unrecognised stored word decodes to, and a group
 * nobody could decide for must not be exported by a default nobody chose.
 *
 * Order is the order the items arrived in, and groups come out in first-appearance order, so a round
 * delivers its payloads in a sequence a test can assert rather than in whatever a hash map feels
 * like.
 *
 * **`Nothing` has its own branch, and it is not the same as falling through to inheritance.** The
 * dispatcher already returns before calling this for a List that chose nothing, so the branch is
 * never reached in the app today — but a function that silently treated *export nothing* as *ask my
 * Todos* would export exactly what the user said not to, and it would do it to a caller that made
 * one omission. This file's rule is that an answer nobody can evaluate cannot widen what leaves the
 * app; an answer the user *did* give must not be quietly widened either.
 */
fun planExports(
    items: List<ListItemEntity>,
    listChoice: ExportChoice,
    sourceOf: (ListItemEntity) -> ExportSource?
): List<PlannedExport> {
    if (listChoice is ExportChoice.Nothing) return emptyList()

    if (listChoice is ExportChoice.By) {
        return listOf(
            PlannedExport(
                kind = listChoice.kind,
                sourceId = null,
                sourceTitle = null,
                items = items
            )
        )
    }

    val ordered = LinkedHashMap<String?, PlannedExport>()
    items.forEach { item ->
        val source = sourceOf(item)
        val kind = source?.kind ?: ExportKind.WEBHOOK
        if (source != null && source.kind == null) return@forEach
        val key = source?.id
        val existing = ordered[key]
        ordered[key] = if (existing == null) {
            PlannedExport(
                kind = kind,
                sourceId = source?.id,
                sourceTitle = source?.title,
                items = listOf(item)
            )
        } else {
            existing.copy(items = existing.items + item)
        }
    }
    return ordered.values.toList()
}

// --- the webhook kind --------------------------------------------------------------------------

/**
 * The mechanism the app had before it had a choice, now named as one.
 *
 * A move and not a rewrite: [render] is [buildWebhookPayload] and [deliver] is [postWebhook], both
 * unchanged, and the tests that pin the body's shape are untouched by this release — which is how
 * "the refactor was a refactor" is held rather than merely asserted.
 */
class WebhookExporter(
    private val url: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Exporter {

    override val kind = ExportKind.WEBHOOK

    override fun render(payload: ExportPayload): String = buildWebhookPayload(payload)

    /**
     * The POST, on a thread that may block.
     *
     * **This is where v0.5.6 broke the webhook's whole automatic path.** [postWebhook] is a
     * blocking HTTP call, and before this release the one place that reached it wrapped it in
     * `withContext(ioDispatcher)` at the call site. Moving the call behind this interface moved it
     * out of that wrapper, and a delivery off the back of a checkbox tap — which runs on the main
     * thread — died with `NetworkOnMainThreadException` on every round. The text kind never showed
     * it: [TxtExporter] does its own `withContext`, so the two halves of the same release were
     * differently safe, and only the device could tell.
     *
     * The dispatcher is a parameter rather than a bare `Dispatchers.IO` so a test can run a
     * delivery on its own scheduler, which is the same reason the view model injects one.
     */
    override suspend fun deliver(payload: ExportPayload, body: String): ExportAttempt =
        withContext(ioDispatcher) {
            val result = postWebhook(url, body)
            if (result.status == DeliveryStatus.SUCCESS) {
                ExportAttempt.Delivered(httpCode = result.httpCode)
            } else {
                ExportAttempt.Failed(result.error ?: "the delivery did not finish")
            }
        }
}

// --- the txt kind ------------------------------------------------------------------------------

/** How many names one export tries before giving up. Bounded: a loop over a filesystem is not a queue. */
private const val MAX_EXPORT_NAME_ATTEMPTS = 50

/**
 * A text file per round, under the album the media save already writes to.
 *
 * **One file per round, and not one growing log.** A payload is a snapshot of a round; a file that
 * keeps growing cannot afterwards be attributed to the round that wrote a line, and *which round
 * wrote this* is the first question anyone would ask of it.
 *
 * Deliberately the least ambitious export there could be: no configuration, no network, and one
 * failure mode — the file did not get written — reported rather than thrown.
 */
class TxtExporter(
    private val context: Context,
    private val relativePath: String = ListeaExportRelativePath
) : Exporter {

    override val kind = ExportKind.TXT
    override val asksBeforeDelivering = false
    override val deliveredOutcome = NoticeOutcome.WRITTEN

    override fun render(payload: ExportPayload): String = renderExportText(payload)

    override suspend fun deliver(payload: ExportPayload, body: String): ExportAttempt {
        val stem = exportNameStem(payload)
        for (attempt in 1..MAX_EXPORT_NAME_ATTEMPTS) {
            val name = if (attempt == 1) "$stem.txt" else "$stem-$attempt.txt"
            if (exportFileExists(context, relativePath, name)) continue
            val problem = writeTextToPublicStorage(context, relativePath, name, body)
            if (problem == null) return ExportAttempt.Delivered(location = name)
            return ExportAttempt.Failed(problem)
        }
        return ExportAttempt.Failed("every name up to $MAX_EXPORT_NAME_ATTEMPTS was taken")
    }
}

/**
 * The text body. The same facts as [buildWebhookPayload], in the same order, separated by tabs.
 *
 * Version line first, in the shape [com.example.listea.data.encodeDissolvedRecords] and the template
 * encoders already use: a payload is never read without knowing what wrote it, and this one will
 * gain a field the first time an action carries a value rather than a presence.
 *
 * **Fields are escaped with the rule `DissolvedRecords` uses**, for the same reason — a title is a
 * user's filename and may contain a tab or a newline, and a renderer that lets one through produces
 * a file that reads as two records. One escaper lifted to `internal`, rather than a second rule for
 * one problem.
 *
 * Every line has the same field count, so a reader can split on tabs without counting; the last
 * field is empty rather than absent when an item has no folder and no actions.
 */
fun renderExportText(payload: ExportPayload): String = buildString {
    append("listea-export\t").append(EXPORT_TEXT_VERSION).append('\n')
    append("event\t").append(payload.event).append('\n')
    append("list\t").append(escapeField(payload.listTitle)).append('\n')
    append("source\t").append(escapeField(payload.sourceField)).append('\n')
    append("at\t").append(payload.at).append('\n')
    append("items\t").append(payload.items.size).append('\n')
    payload.items.forEach { item ->
        append("item")
        append('\t').append(escapeField(item.publicId))
        append('\t').append(escapeField(item.title))
        append('\t').append(if (item.isCompleted) "1" else "0")
        append('\t').append(escapeField(item.relativePath.orEmpty()))
        append('\t').append(item.actions.joinToString(","))
        append('\n')
    }
}

/**
 * The file name for a round: `<yyyyMMdd-HHmmss>-<event>-<origin>.txt`.
 *
 * Local time and second precision, because a name a human reads is a name that has to say when, and
 * a round takes longer than a second. Separate from the writer so the collision suffix does not have
 * to be re-derived from a finished name.
 */
fun exportNameStem(payload: ExportPayload): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(payload.at))
    return "$stamp-${safeNameSegment(payload.event)}-${safeNameSegment(payload.originLabel)}"
}

/**
 * A name segment with everything a filesystem might argue about replaced by `-`.
 *
 * A Todo's display name is the user's — it may hold a slash, a colon, or something an SD card will
 * refuse — and a name that cannot be written is an export that silently does not happen. The
 * replacement is deliberately lossy: the file name is a label, and the header inside carries the
 * name in full.
 */
private fun safeNameSegment(value: String): String {
    val cleaned = value.map { if (it.isLetterOrDigit() || it in "-_.") it else '-' }.joinToString("")
    return cleaned.trim('-', '.').ifEmpty { "unnamed" }
}
