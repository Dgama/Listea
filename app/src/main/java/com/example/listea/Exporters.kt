package com.example.listea

import android.content.Context
import com.example.listea.data.AssetDecisions
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAnswerRow
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadMarkRow
import com.example.listea.data.PayloadPromptRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.data.ReviewItem
import com.example.listea.data.decodeActionIds
import com.example.listea.data.toAddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.MarkKind
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.StoredAddOnVersion
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.markKind
import com.example.listea.domain.parse
import com.example.listea.domain.resolveAddOnAnswers
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
 * The JSON body the webhook sends is produced from the same payload by one builder
 * ([buildWebhookPayload]), and since v0.6.2 the text file is **that same body, byte for byte**:
 * the only difference between the two outputs is where they go. The payload carries no address,
 * so the file needs none. This is deliberately temporary — it makes the two outputs diffable while
 * the user checks a round — and a later release may give the file a shape of its own again.
 *
 * ## Everything here is one round's
 *
 * [ExportPayload.at] is read once, by the dispatcher, and used by the record, the header and the
 * file name. One clock read for one round, so a file named `…-143022` cannot contain a header that
 * says a different second — and so the two are assertable in a test rather than being a thing that
 * is usually right.
 */

/**
 * The version both renderings carry, as the JSON body's `version` — and, since v0.6.2, the text
 * file carries the same body, so it is the version line there too.
 *
 * v0.6.0's hierarchy is 2; the flat, unversioned v0.1 body is 1. The cut is hard by design — a body
 * that said *2* while meaning the old shape would be the one mistake a receiver cannot recover
 * from.
 */
const val EXPORT_VERSION = 2

/**
 * The version a body carries **only when it actually contains a PDF mark**.
 *
 * Version 3 adds a `marks` array beside an Asset's `actions`; an Asset without marks does not write
 * the key, and a payload without one does not claim the version. That is what keeps every image and
 * video body byte-for-byte the one it was under v0.6.2 — a receiver sees the contract change only on
 * the rounds that contain the new kind of fact.
 */
const val EXPORT_VERSION_MARKS = 3

/**
 * The version a body carries **only when at least one Task carries add-on answers**.
 *
 * Version 4 adds a Task-level `addOn` object after the Task's `assets`: one questionnaire identity
 * and the answers its attached version still asks. It is a superset of 3 and 2, so a round without
 * answers stays byte-for-byte the body v0.7.0 sent — the same rule marks follow one layer down.
 * `EXPORT_VERSION_MARKS` keeps its meaning: a body can be *4* because it carries answers and marks
 * both, and the marks array is still written only where there are marks.
 */
const val EXPORT_VERSION_ADD_ON = 4

/**
 * The version a body carries **only when at least one Task is a structured Prompt**.
 *
 * Version 5 adds two Task-level keys beside `addOn`: `content`, the Prompt's identity, and `result`,
 * the completion and the one option chosen. It is a superset of 4 in the same way 4 is a superset of
 * 3, so a media-only round stays byte-for-byte the body v0.7.1 sent.
 *
 * **The profile body is not exported.** `content` names kind, schema, revision and Prompt key; it
 * does not carry the stem, the fields, the photo, or the Resource bytes. Those are personal content
 * whose only home is the Package, and an export is a record of decisions — D6 in
 * `docs/iterations/v0.8.2-plan.md`. A receiver that needs to read the Prompt asks the Package.
 */
const val EXPORT_VERSION_CONTENT = 5

/** One template a Task was imported under: identity only, never its controls. */
data class ExportTemplateView(
    val id: String,
    /** Null when this build cannot read that version — the reference is still worth sending. */
    val name: String?,
    val version: Int
)

/**
 * One file under a Task.
 *
 * [relativePath] is the material's own folder, relative to the Upstream's linked root: `cos`,
 * `cos/2026`, `""` for a file directly under it, and null only when the Asset has no path at all.
 * It is derived from the Asset and from nothing else, which is what makes it independent of how the
 * Task was packaged or which folder a round happened to open.
 *
 * [actions] are the wire values recorded on **this pair**, resolved through the control that governs
 * this file's kind — the same resolution the chip row draws.
 *
 * [marks] are the PDF's stored passages that still resolve against the document being sent. It is
 * empty for every image and video, and for a PDF no mark survives. `actions` stays empty for a PDF
 * rather than carrying an overloaded form: a passage is not a file-level selection.
 */
data class ExportAssetView(
    val publicId: String,
    val title: String,
    val relativePath: String?,
    val sourceMissing: Boolean,
    val actions: List<String>,
    val marks: List<ExportMarkView> = emptyList()
)

/**
 * One passage as the receiver sees it.
 *
 * [pageIndex] and [text] are the stored anchor — the same quote the viewer verified when it drew
 * the mark. The character range and the parser hint stay on the device: they make the anchor cheap
 * to re-find locally and say nothing a receiver can use. [value] is a comment's stored text or a
 * tag's **current** configured value, which is why it is resolved here rather than copied from the
 * mark's row.
 */
data class ExportMarkView(
    val id: String,
    val kind: MarkKind,
    val toolId: String,
    val pageIndex: Int,
    val text: String,
    val value: String
)

/**
 * One answer as the receiver sees it: which question, what type it was given in, the prompt the
 * reviewer saw, and the typed value.
 *
 * [prompt] is the **current** wording of the attached version, not the one stored on the answer —
 * an answer names a question id, and the prompt lives in the immutable version that asked it. When
 * the attached version no longer offers the id, the answer is hidden and never reaches here.
 */
data class ExportAnswerView(
    val questionId: String,
    val type: AddOnQuestionType,
    val prompt: String,
    val value: AddOnAnswerValue
)

/**
 * A Task's questionnaire and the answers it still carries, as one object after `assets`.
 *
 * A sibling of `assets` rather than a member inside it: the answers are about the whole Task, and
 * `assets[]` means files. [answers] is in the questionnaire's order and contains only answers the
 * attached version still asks; a Task with no visible answers sends no `addOn` object at all.
 */
data class ExportAddOnView(
    val id: String,
    val name: String,
    val version: Int,
    val answers: List<ExportAnswerView>
)

/**
 * One Task, with its completion, its template identity, its files and its questionnaire.
 *
 * Completion is here and not on an Asset because checking off is one judgment about one Task however
 * many files it presents. [assets] is empty for a Task with no file — a manual item — and the shape
 * says so rather than inventing one. [addOn] is null for the ordinary Task: no attachment, an
 * attachment this build cannot read, or one with nothing answered.
 */
data class ExportTaskView(
    val publicId: String,
    val title: String,
    val isCompleted: Boolean,
    val templates: List<ExportTemplateView>,
    val assets: List<ExportAssetView>,
    val addOn: ExportAddOnView? = null,
    /** The Prompt's identity when this Task is structured content, else null. */
    val content: ExportContentView? = null,
    /** The structured answer, present exactly when [content] is. */
    val result: ExportResultView? = null
)

/**
 * What kind of Prompt a Task is, without being the Prompt.
 *
 * [promptKey] is the Package's own name for the question; null on a payload Task that is not linked
 * to a manifest Prompt. [revision] is the revision the body was read at, so a receiver can tell two
 * answers apart when a Package republished the question with different labels.
 */
data class ExportContentView(
    val kind: String,
    val schema: Int,
    val revision: Int,
    val promptKey: String?
)

/**
 * The judgment recorded against a structured Task.
 *
 * [completed] is the Task's own state, the same fact `isCompleted` carries; it repeats here because
 * a v5 receiver reads `result` as the answer object and should not have to join two levels to know
 * whether the Task was finished. [choice] is the newest `choice.select` on record, with the label
 * as it was when the person chose — the id is identity, the label is the snapshot.
 *
 * A freeform or multiple-choice Prompt would extend this object; with one Viewer shipping, a result
 * without a choice is a Task completed without a structured event.
 */
data class ExportResultView(
    val completed: Boolean,
    val choice: ExportChoiceView?
)

data class ExportChoiceView(val id: String, val label: String)

/**
 * A round after the version has been decided: every writer's only input.
 *
 * Pure and explicit, so the webhook body and the text file are not two assemblies that happen to
 * agree today. [version] is computed once from the tasks, and both renderers walk the same values in
 * the same order.
 */
data class ExportMapping(
    val version: Int,
    val event: String,
    val listId: Long,
    val listTitle: String,
    val completedAt: Long?,
    val tasks: List<ExportTaskView>
)

/** The round's payload as the wire sees it, with the version its contents earn. */
fun mapExport(payload: ExportPayload): ExportMapping {
    val carriesContent = payload.tasks.any { it.content != null }
    val carriesAddOn = payload.tasks.any { it.addOn != null }
    val carriesMarks = payload.tasks.any { task -> task.assets.any { it.marks.isNotEmpty() } }
    return ExportMapping(
        version = exportVersion(carriesMarks, carriesAddOn, carriesContent),
        event = payload.event,
        listId = payload.listId,
        listTitle = payload.listTitle,
        completedAt = payload.completedAt,
        tasks = payload.tasks
    )
}

/**
 * The folder a stored Asset path sits in, relative to the Upstream root.
 *
 * `cos/x.jpg` → `cos`, `cos/2026/x.jpg` → `cos/2026`, `x.jpg` → `""`, null → null. A split-and-drop
 * rather than a substring search, so a path with a leading slash still describes the same folder.
 */
fun assetFolderOf(relativePath: String?): String? {
    if (relativePath == null) return null
    return relativePath.split('/').filter { it.isNotEmpty() }.dropLast(1).joinToString("/")
}

/**
 * The round's Tasks and Assets as the wire sees them.
 *
 * The one place a Task becomes a payload object, so the POST and the file cannot drift; both
 * renderers walk these values, and both delivery paths call this function. Rows are deduplicated by
 * Task id in first-appearance order: a List holds one membership per Task, but a folder round's
 * bridge is not the place to rely on that, and a duplicated Task in a payload would be a duplicated
 * judgment.
 *
 * A Task's title is the row's title — what the user saw in this List or review — falling back to the
 * name it was imported under when the row has none. The two can differ because a List membership is
 * allowed its own name; the snapshot describes the round that was actually shown.
 */
fun exportTaskViews(
    rows: List<ListItemEntity>,
    tasks: Map<String, PayloadTaskRow>,
    assetsByTask: Map<String, List<PayloadAssetRow>>,
    resolver: TemplateResolver,
    marksByAsset: Map<String, List<PayloadMarkRow>> = emptyMap(),
    fingerprintByAsset: Map<String, String> = emptyMap(),
    addOnResolver: AddOnResolver = AddOnResolver.Empty,
    answersByTask: Map<String, List<PayloadAnswerRow>> = emptyMap(),
    promptsByTask: Map<String, PayloadPromptRow> = emptyMap(),
    responseByTask: Map<String, String> = emptyMap()
): List<ExportTaskView> = rows.distinctBy { it.taskId }.map { row ->
    val task = tasks[row.taskId]
    val attachments = task?.assetControls.orEmpty()
    val templates = decodeTemplateAttachments(attachments).map { ref ->
        ExportTemplateView(
            id = ref.templateId.value,
            name = resolver.versionOf(ref)?.name,
            version = ref.version
        )
    }
    val content = exportContentView(promptsByTask[row.taskId])
    ExportTaskView(
        publicId = task?.publicId.orEmpty(),
        title = row.title.ifBlank { task?.displayName.orEmpty() },
        isCompleted = row.isCompleted,
        templates = templates,
        assets = assetsByTask[row.taskId].orEmpty().map { asset ->
            exportAssetView(
                asset = asset,
                attachments = attachments,
                resolver = resolver,
                markRows = marksByAsset[asset.assetId].orEmpty(),
                documentFingerprint = fingerprintByAsset[asset.assetId]
            )
        },
        addOn = exportAddOnView(
            addOn = addOnResolver.versionFor(task?.addOn.orEmpty()),
            answerRows = answersByTask[row.taskId].orEmpty()
        ),
        content = content,
        result = content?.let { view ->
            ExportResultView(
                completed = row.isCompleted,
                choice = exportChoiceView(responseByTask[row.taskId])
            )
        }
    )
}

/**
 * A Task's Prompt identity, or null when it has no readable envelope.
 *
 * Kind, schema and revision are all required together: an envelope missing one of them is a row
 * this build cannot describe, and a half-written `content` object would make the body claim v5
 * while saying nothing a receiver can use. The Prompt key is optional because a payload Task need
 * not come from a Package Prompt link.
 */
private fun exportContentView(prompt: PayloadPromptRow?): ExportContentView? {
    if (prompt == null) return null
    val kind = prompt.kind ?: return null
    val schema = prompt.schemaVersion ?: return null
    val revision = prompt.revision ?: return null
    return ExportContentView(
        kind = kind,
        schema = schema,
        revision = revision,
        promptKey = prompt.promptKey
    )
}

/**
 * The newest choice on record for a Task, or null.
 *
 * The response is read with the same fail-closed reader the review surface uses: a row from a later
 * build is unreadable rather than guessed at, and a legacy media review carries no choice. History
 * is append-only, so the *last* choice event in the latest primary submission is the current answer.
 */
private fun exportChoiceView(response: String?): ExportChoiceView? {
    val primary = response?.let { ReviewResponse.parse(it) } as? ReviewResponse.PrimaryReview
        ?: return null
    val choice = primary.events.filterIsInstance<ViewerEvent.Choice>().lastOrNull() ?: return null
    return ExportChoiceView(id = choice.optionId, label = choice.label)
}

/**
 * A Task's questionnaire as the payload sees it, or null when there is nothing to send.
 *
 * Three conditions, each fail-closed: the attached reference must resolve to a readable version
 * (otherwise there is no questionnaire to name), the answers must be ones that version still asks
 * (the same visibility rule the page draws — lineage, question id and type), and at least one must
 * remain. An unreadable row among them is skipped rather than guessed at, exactly as
 * [toAddOnAnswer] says.
 *
 * No answers means no object, and therefore no version-4 claim: a Task somebody attached a
 * questionnaire to but never answered sends the shape v0.7.0 sent.
 */
private fun exportAddOnView(
    addOn: StoredAddOnVersion?,
    answerRows: List<PayloadAnswerRow>
): ExportAddOnView? {
    if (addOn == null) return null
    val visible = resolveAddOnAnswers(
        addOn = addOn,
        answers = answerRows.mapNotNull { it.toAddOnAnswer() }
    ).visible
    if (visible.isEmpty()) return null
    return ExportAddOnView(
        id = addOn.ref.addOnId.value,
        name = addOn.name,
        version = addOn.ref.version,
        answers = visible.map { held ->
            ExportAnswerView(
                questionId = held.question.id,
                type = held.question.type,
                prompt = held.question.prompt,
                value = held.answer.value
            )
        }
    )
}

/** One Asset's wire facts, resolved against the control that governs its own kind. */
private fun exportAssetView(
    asset: PayloadAssetRow,
    attachments: String,
    resolver: TemplateResolver,
    markRows: List<PayloadMarkRow>,
    documentFingerprint: String?
): ExportAssetView {
    val path = asset.relativePath
    val title = path?.substringAfterLast('/').orEmpty()
    val control = if (path == null) {
        ResolvedAssetControl.Empty
    } else {
        resolver.controlFor(attachments, assetKindOf(title)) ?: ResolvedAssetControl.Empty
    }
    return ExportAssetView(
        publicId = asset.publicId,
        title = title,
        relativePath = assetFolderOf(path),
        sourceMissing = asset.sourceMissing,
        actions = itemActionNames(
            AssetDecisions(decodeActionIds(asset.selections.orEmpty())),
            control
        ),
        marks = exportMarkViews(markRows, control, documentFingerprint)
    )
}

/**
 * The marks a receiver may be told about, or none.
 *
 * Three independent facts must all still hold, and each is asked of the thing that knows it:
 *
 * - **The document is the one the anchor was verified against.** A file whose hash differs from the
 *   stored one may no longer contain the quoted sentence — or may contain it somewhere else. Sending
 *   the old quote anyway would be a false statement about the file, so the mark is omitted, exactly
 *   as a detached mark is. A file that could not be hashed at all answers null and omits everything.
 * - **The current template still offers the tool, as the same kind.** Editing the template is how a
 *   mark is hidden; it is never how it is deleted. This is the export half of that rule.
 * - **The kind is one this build can read**, so a row written by a later build is skipped rather
 *   than sent under a guess.
 *
 * A tag's value is read from the current tool, not from the row: a tag means *the passage is funny*,
 * and what "funny" is configured to send is current configuration — the same rule file-level tags
 * have always followed. A comment's value is its stored text, because that text is what the
 * reviewer wrote.
 *
 * Order is creation order, tie-broken by id for a stable array, exactly as the marks were made.
 */
private fun exportMarkViews(
    rows: List<PayloadMarkRow>,
    control: ResolvedAssetControl,
    documentFingerprint: String?
): List<ExportMarkView> {
    if (rows.isEmpty() || documentFingerprint == null) return emptyList()
    return rows
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
        .mapNotNull { row ->
            if (row.documentFingerprint != documentFingerprint) return@mapNotNull null
            val kind = MarkKind.fromKey(row.kind) ?: return@mapNotNull null
            val tool = control.buttons.firstOrNull {
                it.id == row.toolId && it.type.markKind() == kind
            } ?: return@mapNotNull null
            ExportMarkView(
                id = row.id,
                kind = kind,
                toolId = row.toolId,
                pageIndex = row.pageIndex,
                text = row.exactText,
                value = if (kind == MarkKind.COMMENT) row.value else tool.recordValue
            )
        }
}

/**
 * A review item as a row, purely so the one payload builder can serialise it. Never inserted: a
 * folder-resolved file is not a member of any list and must not become one by accident.
 *
 * **This row is a folder round's only chance to say which Task a card was.** A List round's rows come out of
 * `list_items`, where the Task id is a stored column; a folder round resolves files, so its rows are
 * minted here, and [taskId] is the one field of this row that is *read back* rather than merely
 * carried into the body: `planExports` groups a round by the Todo its items came from, and it looks
 * each item up by Task id. A row that hard-coded `""` puts **every** item of **every** folder round
 * in one group with no source — which is [ExportChoice.Inherit]'s *nobody owns this* branch, and it
 * is why a folder round over a *Txt* Todo posted to the webhook instead of writing a file.
 *
 * The wire facts themselves are read from the database by [exportTaskViews]; v0.6.0's hierarchy means
 * `actions` belong to a pair and `templates` to the Task, neither of which this bridge can supply.
 * [publicId] and `selections` are therefore carried but not read — the row is never inserted, and
 * [listId] and [createdAt] are zero for the same reason.
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
    selections = ""
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
    val tasks: List<ExportTaskView>
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
     * No resolver, because there is nothing left to look up: the payload's Tasks are already
     * [ExportTaskView]s, assembled by [exportTaskViews] *before* the payload was built, so an action
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

/**
 * A Todo, as the group it owns needs it.
 *
 * [webhookUrl] is the address this Todo holds for the webhook kind, trimmed by the caller. Empty
 * means it holds none — either the install never had one (the upgrade left every Todo blank), or
 * the user cleared it — and a webhook group with no address is planned anyway: it fails delivery
 * and says why, which is more honest than a round that silently shrinks.
 */
data class ExportSource(
    val id: String,
    val title: String,
    val kind: ExportKind?,
    val webhookUrl: String = ""
)

/**
 * One group of a round: the items that will leave together, the mechanism they will leave by, and
 * the address that mechanism will use.
 *
 * The mechanism is always a kind and never an [ExportChoice]: a group only exists because something
 * was decided for it, so a group whose answer was *nothing* was never planned. That is what makes
 * the dispatcher's loop total — it has no branch for *do not export*, because there is nothing to
 * loop over.
 *
 * [webhookUrl] is meaningful only for [ExportKind.WEBHOOK] and is where that group posts. It is
 * resolved by [planExports] and never re-derived by the dispatcher, so the confirmation, the POST
 * and the history row all name the same destination. Empty is a legitimate value that the
 * dispatcher refuses to post.
 */
data class PlannedExport(
    val kind: ExportKind,
    val sourceId: String?,
    val sourceTitle: String?,
    val items: List<ListItemEntity>,
    val webhookUrl: String = ""
)

/**
 * The payloads one round becomes. **The release's headline rule, and the only place it is decided.**
 *
 * ```
 * a List that chose a kind  → one group, its kind, no source
 * a List that chose nothing → no groups at all
 * a List that did not       → one group per source Todo, that Todo's kind
 * a Todo that said nothing  → its group is absent, not empty
 * an item no Todo owns      → its own group, by WEBHOOK
 * ```
 *
 * **Every group carries the address it will post to**, resolved here rather than at send time, for
 * the same reason the mechanism is: the confirmation, the POST and the history row must name one
 * destination. The rules:
 *
 * - a non-empty [listWebhookUrl] overrides the address only when the List chose WEBHOOK itself.
 *   That is the one case the List page shows the field for, and an address the page does not show
 *   must not change what a round does — *same as the Todos* means exactly that, each Todo's own
 *   address, including on a List that once held an override;
 * - an inheriting webhook group posts to the address of the Todo its items came from;
 * - items no Todo owns fall back to [defaultWebhookUrl], the live Settings value;
 * - a Todo that holds no address keeps an empty one. It is not quietly given the default: an
 *   address snapshot is a snapshot, and the upgrade deliberately left old Todos blank.
 *
 * **`By(WEBHOOK)` without a List address splits by address**, not by Todo: two Todos configured with
 * the same receiver are one payload to that receiver, and their first appearance fixes the order.
 * A blank address forms a group of its own and fails at delivery, which is visible; dropping it
 * would make the round quietly smaller than the List it reports.
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
    sourceOf: (ListItemEntity) -> ExportSource?,
    listWebhookUrl: String = "",
    defaultWebhookUrl: String = ""
): List<PlannedExport> {
    if (listChoice is ExportChoice.Nothing) return emptyList()

    val listUrl = listWebhookUrl.trim()
    val defaultUrl = defaultWebhookUrl.trim()

    if (listChoice is ExportChoice.By) {
        if (listChoice.kind != ExportKind.WEBHOOK) {
            // A text file has no destination to resolve; the address columns are not read at all.
            return listOf(
                PlannedExport(
                    kind = listChoice.kind,
                    sourceId = null,
                    sourceTitle = null,
                    items = items
                )
            )
        }
        if (listUrl.isNotEmpty()) {
            return listOf(
                PlannedExport(
                    kind = ExportKind.WEBHOOK,
                    sourceId = null,
                    sourceTitle = null,
                    items = items,
                    webhookUrl = listUrl
                )
            )
        }
        // Split by address, not by Todo: one payload per receiver, first appearance fixing the order.
        val ordered = LinkedHashMap<String, PlannedExport>()
        items.forEach { item ->
            val source = sourceOf(item)
            val address = source?.webhookUrl?.trim().orEmpty()
                .ifEmpty { if (source == null) defaultUrl else "" }
            val existing = ordered[address]
            ordered[address] = if (existing == null) {
                PlannedExport(
                    kind = ExportKind.WEBHOOK,
                    sourceId = null,
                    sourceTitle = null,
                    items = listOf(item),
                    webhookUrl = address
                )
            } else {
                existing.copy(items = existing.items + item)
            }
        }
        return ordered.values.toList()
    }

    val ordered = LinkedHashMap<String?, PlannedExport>()
    items.forEach { item ->
        val source = sourceOf(item)
        val kind = source?.kind ?: ExportKind.WEBHOOK
        if (source != null && source.kind == null) return@forEach
        val key = source?.id
        val existing = ordered[key]
        if (existing == null) {
            // The List's own address is an override for a List that chose webhook, and only for
            // one: *same as the Todos* is the List saying it has no destination of its own. The
            // List page hides the field in this case, and a hidden setting that still redirected
            // a round would be the page lying about what it shows.
            val address = if (kind == ExportKind.WEBHOOK) {
                if (source == null) defaultUrl else source.webhookUrl.trim()
            } else {
                ""
            }
            ordered[key] = PlannedExport(
                kind = kind,
                sourceId = source?.id,
                sourceTitle = source?.title,
                items = listOf(item),
                webhookUrl = address
            )
        } else {
            ordered[key] = existing.copy(items = existing.items + item)
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

    override fun render(payload: ExportPayload): String = buildWebhookPayload(mapExport(payload))

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
 *
 * The body is [buildWebhookPayload] — the POST's own bytes, minus nothing, because an address is
 * not part of a body. See the file header for why that is temporary.
 */
class TxtExporter(
    private val context: Context,
    private val relativePath: String = ListeaExportRelativePath
) : Exporter {

    override val kind = ExportKind.TXT
    override val asksBeforeDelivering = false
    override val deliveredOutcome = NoticeOutcome.WRITTEN

    override fun render(payload: ExportPayload): String = buildWebhookPayload(mapExport(payload))

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
