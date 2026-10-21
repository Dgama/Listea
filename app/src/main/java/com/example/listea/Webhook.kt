package com.example.listea

import android.os.SystemClock
import android.util.Log
import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.assetDecisions
import com.example.listea.data.decisions
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/** Fired when a list transitions from incomplete to complete. */
const val EVENT_LIST_COMPLETED = "list.completed"

/** Fired by the "Test webhook" button. Never changes completion state. */
const val EVENT_EXPORT_TEST = "export.test"

/**
 * Fired when a Quick Review reaches the end of its queue, if the user has switched Quick Review
 * webhooks on. Its own event rather than [EVENT_LIST_COMPLETED] because the owning list is
 * usually *not* complete: one folder of it has been reviewed. The `items` are that folder's
 * queue, and `list` is the list they belong to.
 */
const val EVENT_QUICK_REVIEW_COMPLETED = "quickreview.completed"

/**
 * Fired when the user leaves a review, if the user has switched the on-exit webhook on. Carries
 * the queue that was being reviewed — one folder for Quick Review, the whole list for Review —
 * which is what makes "send the decisions of this round" possible when the queue was itself
 * narrowed to unchecked items.
 */
const val EVENT_REVIEW_EXITED = "review.exited"

private const val TIMEOUT_MS = 10_000

// --- TEMPORARY V3 DELIVERY DIAGNOSTICS -------------------------------------------------------
// Logcat only: nothing here changes what is sent, what is stored, or what the UI shows.
// Filter with:  adb logcat -s ListeaWebhook
// Remove this block (and the Log calls in postWebhook) once the n8n endpoint is understood.
private const val TAG = "ListeaWebhook"
private const val DIAGNOSTICS = true
private const val SNIPPET_BYTES = 512
// ---------------------------------------------------------------------------------------------

/**
 * How an attempt to leave the device ended, as a `lists` row records it.
 *
 * [NOT_SENT] is not a transport result and no POST can produce it: it is the answer *not now*,
 * recorded so that a List can say the last time its export came up, nothing left. Which of the two
 * *not now*s it was — the user's, from the dialog, or the app's, on behalf of a user who was busy
 * with another List — is the history's business rather than the column's; both mean the same thing
 * to the card that reads it and to the resend that can still send it.
 */
enum class DeliveryStatus { SUCCESS, FAILED, NOT_SENT }

data class DeliveryResult(
    val status: DeliveryStatus,
    val httpCode: Int?,
    val error: String?
)

/**
 * How a completion ended up, from the user's point of view. Distinct from [DeliveryStatus], which
 * is only ever about a delivery that was actually attempted and is what gets persisted; [DISABLED]
 * and [EMPTY] never reach the database, because in neither case was anything sent.
 */
enum class NoticeOutcome {
    SENT,
    FAILED,

    /**
     * A text export landed in the user's own storage.
     *
     * The second terminal outcome, and it exists because without one a txt export is
     * **unfalsifiable**: the user cannot tell whether the file was written, and the *Last export*
     * line on the List card would have nothing to say exactly when the effective kind is txt.
     * Terminal in the same sense [SENT] is — `observePendingWebhookCount` asks *is this still
     * unfinished*, and there are now two ways to be finished.
     *
     * Not a flavour of [SENT]: that one names an HTTP result the record carries a code for, and a
     * file has no code. What it has is a name, which [WebhookNotice.location] carries.
     */
    WRITTEN,

    DISABLED,

    /**
     * The webhook was usable and there was simply nothing to put in it: no items at all, or none
     * left once "send checked items only" had its say. Posting `"items": []` would tell a
     * receiver a round happened when none did, so the user is told instead.
     */
    EMPTY,

    /**
     * Everything was ready and the user said no.
     *
     * Only an *automatic* delivery can end this way — one the app decided to make, off the back
     * of a swipe or a completion, rather than one the user pressed a button for. Those now ask
     * first, and this is the answer. The payload is kept exactly as a failure's is, so declining
     * costs nothing: the round is in the history and can be sent from there whenever.
     */
    DECLINED,

    /**
     * The app answered *Not now* for the user, because they were somewhere else.
     *
     * A List completed while a round was open on another List, or on a folder: an event about one
     * thing happening under a screen that is about another. Sending would put a payload on the wire
     * that nobody asked for at a moment nobody chose; asking would put a dialog about one List over
     * the review of a different one, which is what the user reported — a *Demo* completion asking
     * for permission in the middle of reviewing *export test*.
     *
     * So the app makes the safe choice on the user's behalf, and it is the same choice the dialog
     * offers: nothing is sent, the payload is built and kept, and it can be sent from the history
     * whenever. The List records [DeliveryStatus.NOT_SENT], which is what *Last export* reads.
     *
     * Never shown. There is no notice for it because there is no moment at which showing one would
     * be welcome — the whole point is that this List's business does not interrupt the other one's
     * — and the record in the history is the whole of the report. Read from there, it names its own
     * reason rather than borrowing [DECLINED]'s, which would tell the user they chose something
     * they were never asked.
     */
    DEFERRED,

    /**
     * The round finished and **nothing was configured to carry it**: every Todo it covered is set
     * to export nothing, so there was no mechanism to hand the items to and not even a payload to
     * build.
     *
     * This is the case v0.5.6 shipped silently, and the silence was the defect. Quick Review's
     * whole promise is that finishing a round *produces a result* — that is what "send when leaving
     * a review" is for — so a round that ends with no file, no POST and no word to the user is
     * indistinguishable from one that failed quietly, and the user has no way to learn which. The
     * outcome is not a failure and must not be dressed as one: nothing was attempted and nothing
     * is broken. It is the app saying what it did not do and why, which is the one thing it owed
     * the user here.
     *
     * Nothing is recorded in the history. There is no payload whose kind means anything, and a row
     * saying *a round happened, exported by nothing, to nowhere* would be a record of an attempt
     * that was never made — the same argument [EMPTY] is not recorded under.
     */
    UNCONFIGURED
}

/**
 * What one completion should tell the user, so a result never has to be hunted for on a folder
 * card or a list screen.
 *
 * Raised for every attempt that reached a verdict, including one abandoned over an unusable URL
 * (which still records a failed delivery), and for a list that completed with its webhook switched
 * off — otherwise that case is the one the user hears nothing about at all.
 * [itemCount] is null when no payload was ever built.
 */
data class WebhookNotice(
    val event: String,
    val listTitle: String,
    /**
     * The mechanism this round was going to leave by, so the sentences below can name the right
     * one.
     *
     * Required rather than defaulted, because a notice that guessed *webhook* would tell a user
     * whose text export failed that their webhook had — which is exactly what this release's
     * device pass caught it doing. The mechanism is a fact about the round, known before any
     * attempt is made, so a caller always has it to hand.
     */
    val kind: ExportKind,
    val host: String?,
    val itemCount: Int?,
    val outcome: NoticeOutcome,
    val httpCode: Int?,
    val error: String?,
    /**
     * Whether this round was split across more than one webhook address.
     *
     * A round no longer has one destination to name — a List's items may come from Todos with
     * different addresses — so the notice names [host] when the round had one and says *several
     * webhook addresses* when it had more. Per-group notices still carry their own host, which is
     * what keeps a failure specific.
     */
    val severalDestinations: Boolean,
    val at: Long,

    /**
     * Where a [NoticeOutcome.WRITTEN] export went — the file's name, which is the only thing that
     * can tell two rounds' reports apart once they are both in the album.
     *
     * Null for every other outcome: a POST's destination is [host], and an attempt that got
     * nowhere has nowhere to name. A nullable field rather than a widening of [host] to mean *the
     * place it went, however it got there*, because the two are read in different sentences.
     */
    val location: String? = null
) {
    val succeeded: Boolean
        get() = outcome == NoticeOutcome.SENT || outcome == NoticeOutcome.WRITTEN

    /**
     * Whether anything left the device, or was going to. False means nothing was even tried, and
     * a second notice saying the same is worth collapsing rather than showing twice.
     */
    val attempted: Boolean
        get() = outcome == NoticeOutcome.SENT ||
            outcome == NoticeOutcome.WRITTEN ||
            outcome == NoticeOutcome.FAILED

    /**
     * The one line at the top of the notice, naming both what happened and which mechanism it
     * happened to.
     *
     * A failure is the case that made [kind] necessary: *Webhook not sent* over a text export that
     * failed to be written is a sentence about a request the round was never going to make, and it
     * sends the user to look at a URL that is not involved. Success and failure both name the
     * mechanism; the two outcomes that have no mechanism to name — nothing to send, and nothing
     * configured to send it — say so without one.
     */
    val headline: String
        get() = when (outcome) {
            NoticeOutcome.SENT -> "Webhook sent"
            NoticeOutcome.WRITTEN -> "Txt written"
            NoticeOutcome.FAILED ->
                if (kind == ExportKind.TXT) "Txt not written" else "Webhook not sent"

            // A List completion whose switch is off. The app-wide default is no longer a delivery
            // gate, so a round can no longer be *disabled* by a setting above it — the only
            // switch that stops one now is the List's own. Legacy history rows read the same way.
            NoticeOutcome.DISABLED -> "Export is off"
            NoticeOutcome.EMPTY -> "Nothing to send"
            NoticeOutcome.DECLINED -> "Not sent"
            // The same words as a decline, and deliberately: to the List and to the user reading
            // the history, both say the one thing that matters about the record — this payload
            // exists and did not go. Who made the choice is the line below.
            NoticeOutcome.DEFERRED -> "Not sent"
            NoticeOutcome.UNCONFIGURED -> "Nothing exported"
        }

    val eventLabel: String get() = webhookEventLabel(event)

    /** "Success · HTTP 200", "Failed · HTTP 500", "Failed · Timeout". */
    val outcomeLabel: String
        get() = when (outcome) {
            NoticeOutcome.SENT -> "Success" + (httpCode?.let { " · HTTP $it" } ?: "")
            NoticeOutcome.WRITTEN -> "Success" + (location?.let { " · $it" } ?: "")
            NoticeOutcome.FAILED -> "Failed · " + (httpCode?.let { "HTTP $it" } ?: error ?: "Failed")
            // One switch, so one sentence. This is a List's own completion switch, off; the app
            // default is a preference now, not a gate, and a Todo's address is an address rather
            // than a switch. A round with no address at all fails as a failure, and says so above.
            NoticeOutcome.DISABLED -> "Nothing was sent · this export is switched off"
            NoticeOutcome.EMPTY -> "Nothing was sent · there were no items to send"
            NoticeOutcome.DECLINED -> "Nothing was sent · you chose not to send it"
            // Not *you chose*: the user was in the middle of another review and was never asked.
            // Saying it was their decision would be telling them they did something they did not.
            NoticeOutcome.DEFERRED -> "Nothing was sent · you were in another review"
            NoticeOutcome.UNCONFIGURED ->
                "Nothing was sent · no Todo this round covers is set to export"
        }

    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}

/**
 * A delivery that is on the wire right now, for the dialog that says so.
 *
 * Exists because a round can be hundreds of items and a receiver can be slow, and the seconds in
 * between used to look exactly like nothing happening. Everything on it is already known before
 * the POST starts, so the dialog is complete from the moment it appears rather than filling in.
 *
 * Doubles as what an automatic delivery is described by when it asks permission - see
 * [WebhookConfirmation] - because the question and the progress report are about the same thing
 * and must not be able to describe it differently.
 */
data class WebhookProgress(
    val event: String,
    val listTitle: String,
    val host: String?,
    val itemCount: Int,
    val severalDestinations: Boolean
) {
    val eventLabel: String get() = webhookEventLabel(event)

    /** "List completed - 42 items - example.com", the one line under the spinner. */
    val summaryLabel: String
        get() = buildList {
            add(eventLabel)
            add(countOfItems(itemCount))
            destinationSummary(host, severalDestinations)?.let { add(it) }
        }.joinToString(" - ")
}

/**
 * An automatic delivery waiting to be allowed.
 *
 * Only deliveries the *app* decided to make are ever described by one of these. A webhook fired
 * off the back of a swipe used to be the one thing in Listea that left the device without anyone
 * asking, which is a strange amount of trust to place in a gesture whose whole job is to be fast.
 *
 * The same fields as [WebhookProgress], so the dialog that asks and the dialog that reports cannot
 * disagree about the delivery they are describing. There are deliberately **not** a source URL or a
 * per-group Todo among them: a round asks once for all of its groups, so a field naming one group's
 * origin would be a sentence about one payload shown over another's.
 */
data class WebhookConfirmation(
    val event: String,
    val listTitle: String,
    val host: String?,
    val itemCount: Int,
    val severalDestinations: Boolean
) {
    val eventLabel: String get() = webhookEventLabel(event)

    /**
     * What caused this export, as the line at the top of the dialog.
     *
     * [listTitle] alone was half a sentence. A dialog can be raised over *any* screen — a List's
     * page, the review that just ended, the folder a Quick Review walked — and the device pass
     * found the other half missing in exactly the case that had two Lists in it: a completion of
     * one List asking for permission over the review of another, with a bare *Demo* at the top and
     * nothing saying what Demo had to do with what the user was doing. The event is a fact the
     * delivery already carries, so joining it to the subject costs nothing and every dialog gets it.
     */
    val triggerLabel: String get() = webhookTriggerLabel(event, listTitle)

    /**
     * The sizes and the destination, under the sentence that says what this is.
     *
     * Deliberately without the event: the line above has already named it, and a dialog that said
     * *List completed* twice would read as two facts where there is one.
     */
    val factsLabel: String
        get() = buildList {
            add(countOfItems(itemCount))
            destinationSummary(host, severalDestinations)?.let { add(it) }
        }.joinToString(" - ")

    /**
     * Which webhook this would go to.
     *
     * **A round can have more than one destination since v0.6.2.** Each group posts to the address
     * of the Todo it came from, or to the List's override, so a dialog raised over a round with two
     * addresses names neither as *the* destination: it says there are several, and the per-group
     * notices afterwards name the host that failed. One address is still named by its host, which
     * is what a user recognizes; a round with none says so rather than showing a blank.
     */
    val destinationLabel: String
        get() = destinationSummary(host, severalDestinations) ?: "no webhook address"
}

/**
 * A round's destination in the one phrase a summary line has room for: its host, *several webhook
 * addresses* when it split, or null when there is no address to name.
 *
 * Shared by the progress and confirmation dialogs so the question and the report cannot describe
 * the same round differently.
 */
private fun destinationSummary(host: String?, severalDestinations: Boolean): String? = when {
    severalDestinations -> "several webhook addresses"
    else -> host
}

/** "1 item" / "42 items", used wherever a payload is sized in front of the user. */
fun countOfItems(count: Int): String = "$count item" + if (count == 1) "" else "s"

/**
 * What an event is called in front of the user. One mapping, so a live result and a record of an
 * old one can never name the same event differently. An unrecognised event answers with itself
 * rather than with nothing.
 */
fun webhookEventLabel(event: String): String = when (event) {
    EVENT_LIST_COMPLETED -> "List completed"
    EVENT_EXPORT_TEST -> "Test export"
    EVENT_QUICK_REVIEW_COMPLETED -> "Quick Review completed"
    EVENT_REVIEW_EXITED -> "Review left"
    else -> event
}

/**
 * The same events as [webhookEventLabel], said as a sentence about the thing they happened to.
 *
 * A second vocabulary, and it is worth the cost for one reason: a label is a *category*, read
 * beside a name — *Demo · List completed* — while this is read as the reason a dialog is on screen,
 * and the reader of that dialog is standing somewhere else entirely. It says what happened, to
 * what, in the words a person would use, so a delivery that reached them mid-round explains itself
 * instead of naming a List they were not looking at.
 *
 * Every sentence is true both of the moment a round is reported from and of the moment it ends:
 * *Review left* covers a queue finished on screen and one backed out of, and this must not claim
 * which — a sentence that guessed *you left* would be wrong at the completion screen the dialog is
 * most often raised over. [subject] is a List's title or, for a folder round, the name of the
 * folder, which is the subject that round has.
 */
fun webhookTriggerLabel(event: String, subject: String): String = when (event) {
    EVENT_LIST_COMPLETED -> "The List $subject just completed"
    EVENT_REVIEW_EXITED -> "The review of $subject ended"
    EVENT_QUICK_REVIEW_COMPLETED -> "The Quick Review of $subject finished"
    EVENT_EXPORT_TEST -> "The Test export button in Settings"
    else -> webhookEventLabel(event) + ": " + subject
}

/**
 * A full "2026-09-01 16:40:05" stamp, for a record that has to be told apart from the others
 * rather than placed in the last few minutes. Live notices use the shorter [WebhookNotice.timeLabel].
 */
fun webhookTimestampLabel(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

/**
 * Whether this notice tells the user anything the ones already waiting have not.
 *
 * A delivery that was attempted always does: two attempts are two events, and hiding one would
 * misrepresent what left the device. A result where nothing was attempted does not, if the same
 * list has already produced that same result — one swipe can finish a review and complete its
 * list at the same moment, and being told twice over that a webhook is switched off is noise.
 */
fun WebhookNotice.addsTo(queued: List<WebhookNotice>): Boolean =
    attempted || queued.none { it.outcome == outcome && it.listTitle == listTitle }

/** Host of a webhook URL, for showing where something went without exposing the full URL. */
fun webhookHost(url: String): String? =
    runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

/** Returns a user-readable problem with [url], or null when it is usable. */
fun webhookUrlError(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "Enter a webhook URL"
    val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return "Not a valid URL"
    if (parsed.protocol !in setOf("http", "https")) return "URL must start with http:// or https://"
    if (parsed.host.isNullOrBlank()) return "URL is missing a host"
    return null
}

/**
 * Why an export of [kind] cannot use [url], or null when it can.
 *
 * The blank case is the one this differs from [webhookUrlError] on, and it is the case v0.6.2
 * makes ordinary: a Todo may hold no address at all — the upgrade left old Todos blank rather than
 * post on their behalf, and *Use default* over an empty default is a tap away. Telling that user
 * *Enter a webhook URL* over a field they did not leave empty asks them to type an address when the
 * real answer may be that the default they chose has none. A text export has no address and is
 * never refused.
 */
fun webhookAddressProblem(kind: ExportKind, url: String): String? = when (kind) {
    ExportKind.TXT -> null
    ExportKind.WEBHOOK ->
        if (url.trim().isEmpty()) "No webhook address is set for this export"
        else webhookUrlError(url)
}

/**
 * The Tasks a payload should actually carry, out of the ones the event covers.
 *
 * The one place the "only send checked items" setting is applied, so it holds for every event
 * including the test one: what the test button sends stays an honest preview of a real delivery.
 * An event whose Tasks are all unchecked legitimately sends an empty `tasks` array — a receiver
 * being told "nothing was checked" is not the same as it being told nothing. The filter is on the
 * Task's completion, never on whether it has Assets: a checked manual Task belongs in the body with
 * `assets: []`.
 */
fun webhookItems(items: List<ListItemEntity>, settings: AppSettings): List<ListItemEntity> =
    if (settings.webhookCompletedItemsOnly) items.filter { it.isCompleted } else items

/**
 * The webhook body, version 2. Timestamps are epoch milliseconds.
 *
 * The hierarchy is **event → list → task → asset**, and each level says only what belongs to it:
 * completion and template identity on the Task, the file's path and its decisions on the Asset.
 * This is a deliberately fixed shape; a receiver built on it may reconstruct a round without
 * consulting any other source.
 *
 * The facts are assembled once, by [exportTaskViews], and this function only decides how they are
 * spelled. Key order is part of the contract and is pinned by `WebhookBodyTest`. Null is a value,
 * never an omission: `completedAt`, an Asset with no path, and a template version this build cannot
 * read all appear as `null`.
 */
fun buildWebhookPayload(payload: ExportPayload): String = JSONObject()
    .put("version", EXPORT_VERSION)
    .put("event", payload.event)
    .put(
        "list",
        JSONObject()
            .put("id", payload.listId)
            .put("title", payload.listTitle)
            .put("completedAt", payload.completedAt ?: JSONObject.NULL)
    )
    .put("tasks", JSONArray().apply { payload.tasks.forEach { put(taskJson(it)) } })
    .toString()

/**
 * One Task as the receiver sees it. `isCompleted` is here rather than on an Asset because checking
 * off is one judgment about one Task however many files it presents; `templates` is here because
 * the attachment is stored on the Task.
 *
 * `id` is the Task's public id and never its row id. The Task's `title` is the name the round
 * showed, which is allowed to differ between the Lists a Task appears in.
 */
private fun taskJson(task: ExportTaskView): JSONObject = JSONObject()
    .put("id", task.publicId)
    .put("title", task.title)
    .put("isCompleted", task.isCompleted)
    .put(
        "templates",
        JSONArray().apply {
            task.templates.forEach { template ->
                put(
                    JSONObject()
                        .put("id", template.id)
                        .put("name", template.name ?: JSONObject.NULL)
                        .put("version", template.version)
                )
            }
        }
    )
    .put(
        "assets",
        JSONArray().apply {
            task.assets.forEach { asset ->
                put(
                    JSONObject()
                        .put("id", asset.publicId)
                        .put("title", asset.title)
                        .put("relativePath", asset.relativePath ?: JSONObject.NULL)
                        .put("sourceMissing", asset.sourceMissing)
                        .put(
                            "actions",
                            JSONArray().apply { asset.actions.forEach { put(it) } }
                        )
                )
            }
        }
    )

/**
 * POSTs [jsonBody] to [url]. Blocking: call on an IO dispatcher.
 * Any 2xx counts as success; every other status code and every network failure is a failure.
 * Never throws.
 */
fun postWebhook(url: String, jsonBody: String): DeliveryResult {
    var connection: HttpURLConnection? = null
    // Kept outside the try so a failure can still say where it was going.
    var target: URL? = null
    val startedAt = SystemClock.elapsedRealtime()
    fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    var connectedMs = -1L
    var sentMs = -1L

    return try {
        val destination = URL(url)
        target = destination
        connection = (destination.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        if (DIAGNOSTICS) {
            Log.d(
                TAG,
                "-> POST $destination host=${destination.host} port=${destination.port} " +
                    "followRedirects=${connection.instanceFollowRedirects} " +
                    "connectTimeout=${TIMEOUT_MS}ms readTimeout=${TIMEOUT_MS}ms " +
                    "bodyBytes=${bodyBytes.size}"
            )
        }

        // Explicit connect so DNS+TCP+TLS can be timed separately from sending the body.
        // Behaviourally identical: getOutputStream() would trigger the same connect.
        connection.connect()
        connectedMs = elapsed()

        connection.outputStream.use { it.write(bodyBytes) }
        sentMs = elapsed()

        val code = connection.responseCode
        val headersMs = elapsed()
        val success = code in 200..299

        // Drain the body so the connection can be released; for failures keep a short snippet
        // for the log only. The content is still never stored or shown in the UI.
        val snippet = drainBody(
            stream = if (success) connection.inputStream else connection.errorStream,
            keepSnippet = !success
        )

        if (DIAGNOSTICS) {
            val line = "<- status=$code finalUrl=${connection.url} " +
                "redirected=${connection.url.toString() != destination.toString()} " +
                "contentType=${connection.getHeaderField("Content-Type")} " +
                "connect=${connectedMs}ms sent=${sentMs}ms headers=${headersMs}ms total=${elapsed()}ms"
            if (success) Log.d(TAG, line) else Log.w(TAG, "$line body=$snippet")
        }

        if (success) {
            DeliveryResult(DeliveryStatus.SUCCESS, code, null)
        } else {
            DeliveryResult(DeliveryStatus.FAILED, code, "HTTP $code")
        }
    } catch (e: Exception) {
        if (DIAGNOSTICS) {
            Log.w(
                TAG,
                "!! ${e.javaClass.name}: ${e.message} " +
                    "cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message} " +
                    "connect=${connectedMs}ms sent=${sentMs}ms failedAt=${elapsed()}ms"
            )
        }
        DeliveryResult(DeliveryStatus.FAILED, null, webhookErrorLabel(e, target))
    } finally {
        connection?.disconnect()
    }
}

/** Reads and discards the response, optionally keeping a capped, single-line snippet. */
private fun drainBody(stream: InputStream?, keepSnippet: Boolean): String? = runCatching {
    stream?.use { input ->
        val head = ByteArray(SNIPPET_BYTES)
        var filled = 0
        while (filled < head.size) {
            val n = input.read(head, filled, head.size - filled)
            if (n <= 0) break
            filled += n
        }
        val scratch = ByteArray(4096)
        while (input.read(scratch) > 0) { /* discard the rest */ }
        if (keepSnippet && filled > 0) {
            String(head, 0, filled, Charsets.UTF_8).replace(Regex("""\s+"""), " ").trim()
        } else {
            null
        }
    }
}.getOrNull()

/**
 * A network failure in words that describe the delivery, not the JDK.
 *
 * The platform's own wording for these is shaped like `Failed to connect to
 * example.com/93.184.216.34:443` — hostname, slash, *resolved address*, port. That reads exactly
 * like a URL with a path, so a reader compares it against the URL they configured, finds a
 * stretch of it replaced by numbers, and reasonably concludes the app mangled their address.
 * Nothing was rewritten: the numbers are what DNS answered with.
 *
 * So the target is named from the [URL] Listea actually opened, and the resolved address is left
 * to the logcat diagnostics, which is where an address worth arguing with belongs. [target] is
 * null only when the URL itself would not parse, and then the message simply says less.
 */
internal fun webhookErrorLabel(e: Throwable, target: URL?): String {
    val where = target?.let { url ->
        // The effective port, so https says 443 rather than the -1 an unstated port parses to.
        url.host + ":" + (if (url.port != -1) url.port else url.defaultPort)
    }
    fun at(prefix: String) = if (where == null) prefix else "$prefix $where"
    return when (e) {
        is SocketTimeoutException -> at("Timed out reaching")
        is UnknownHostException -> at("Host not found:")
        is ConnectException -> at("Could not connect to")
        is SSLException -> at("TLS failed for")
        else -> (e.message ?: e::class.java.simpleName).take(120)
    }
}

/**
 * Compact export state for a browse row, e.g. "Webhook · On · Failed · HTTP 500".
 * Deliberately omits the URL, timestamp and error text so folder cards and list rows stay
 * glanceable; the List detail screen shows the fuller [exportLabel] instead.
 *
 * **The kind is named**, because a List may export by either mechanism and a badge that said only
 * "Export" would leave the reader to go and find out which. [ExportChoice.Inherit] is the one
 * answer with no single kind to name — it exports by whatever each of its Todos said — so it is
 * called *Automatic* rather than named after one of the two, which would be a lie about the other.
 *
 * Two independent facts, and both are always reported: whether the export will fire from here on,
 * and what happened the last time it did. Switching an export off says nothing about what it
 * already delivered, so a List that exported and was then switched off reads "Off · Success"
 * rather than losing its history to a toggle. An attempt always records its timestamp and status
 * together, so a null [lastDeliveryStatus] means nothing has ever left.
 */
fun exportStatusLabel(
    choice: ExportChoice,
    enabled: Boolean,
    lastDeliveryStatus: String?,
    lastDeliveryCode: Int?
): String {
    val delivery = when (lastDeliveryStatus) {
        null -> "Never exported"
        DeliveryStatus.SUCCESS.name -> if (choice == ExportChoice.By(ExportKind.TXT)) {
            "Written"
        } else {
            "Success"
        }
        // A round that was kept rather than sent. Distinct from *Failed*, because nothing went
        // wrong: there is a payload, it did not leave, and it can still be sent from the history.
        DeliveryStatus.NOT_SENT.name -> "Not sent"
        else -> "Failed" + (lastDeliveryCode?.let { " · HTTP $it" } ?: "")
    }
    val name = when (choice) {
        ExportChoice.Inherit -> "Automatic"
        ExportChoice.Nothing -> "Export off"
        is ExportChoice.By -> choice.kind.label
    }
    return name + " · " + (if (enabled) "On" else "Off") + " · " + delivery
}

/**
 * "Last export: Success / 18:42", "Last export: Not sent / 18:42", or a short failure reason.
 *
 * Three answers and not two, because *nothing left* has two causes that must not be confused: a
 * round that was attempted and failed, which is a problem to go and fix, and a round that was kept
 * unsent — declined in the dialog, or set aside because the user was in another review — which is a
 * payload sitting in the history waiting for someone. Saying *Failed* over the second would send the
 * user looking for a broken URL that is not broken.
 */
fun exportLabel(list: ListEntity): String {
    val at = list.lastDeliveryAt ?: return "Last export: never exported"
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    return when (list.lastDeliveryStatus) {
        DeliveryStatus.SUCCESS.name -> "Last export: Success · $time"
        DeliveryStatus.NOT_SENT.name -> "Last export: Not sent · $time"
        else -> {
            val reason = list.lastDeliveryCode?.let { "HTTP $it" } ?: list.lastDeliveryError ?: "Failed"
            "Last export: Failed · $reason · $time"
        }
    }
}
