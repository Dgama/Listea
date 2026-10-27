package com.example.listea.domain

import java.util.Locale

/**
 * What a viewer can report to the review that composed it, named so it can be stored.
 *
 * A viewer renders content and emits named intents; it does not write anything, decide completion,
 * or know what a template will do with the intent. The type is the vocabulary between the two, and
 * the key is what a stored definition writes down — so a later release may add a member without
 * moving the ones already on disk.
 *
 * **Two members in v0.8.2.** The image viewer's long press reports a point on content; the
 * structured profile viewer reports the option the person chose. Both come from the content rather
 * than from a button somebody configured, which is what makes them events and not controls.
 */
enum class ViewerEventType(val key: String) {

    /** A point on an image, with the colour of the image at that point. */
    IMAGE_POINT("image.point"),

    /** One declared option chosen in a Prompt. */
    CHOICE_SELECT("choice.select");

    companion object {
        /** The type a stored [key] names, or null for one this build has never written. */
        fun fromKey(key: String?): ViewerEventType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One thing a viewer reported during a review, in the form a response records.
 *
 * Events are facts about the content, not controls: nothing in the UI toggles one after the fact,
 * and a committed event is never edited. The coordinates are normalized to the image's own
 * dimensions — 0..1 from its top-left — so a different screen size or a zoom level cannot change
 * what was recorded. [assetPath] names the Asset the point was taken from within its Task; it is
 * provenance for a human reading the response, not an identity.
 */
sealed interface ViewerEvent {

    val type: ViewerEventType

    /**
     * A long press on an image and the colour of the image there.
     *
     * [color] is an uppercase `#RRGGBB` sampled from a small neighbourhood around the point, not
     * necessarily the one pixel under the finger: a single pixel is noise on a photograph, and the
     * synthetic fixture's blocks are uniform either way.
     */
    data class ImagePoint(
        val x: Double,
        val y: Double,
        val color: String,
        val assetPath: String = ""
    ) : ViewerEvent {
        override val type = ViewerEventType.IMAGE_POINT
    }

    /**
     * An option the person chose in a Prompt.
     *
     * [optionId] is the machine identity declared by the Package; [label] is what they read when
     * they chose it. Both are kept because a later revision may rename the label or remove the
     * option, and the record should still say what the person saw. The id is not an index: options
     * may be reordered between revisions without changing the answer.
     */
    data class Choice(
        val optionId: String,
        val label: String
    ) : ViewerEvent {
        override val type = ViewerEventType.CHOICE_SELECT
    }
}

/**
 * An event's value half, as both the response line and its stored row write it.
 *
 * **One grammar, two readers.** `review_events.response` keeps the event whole — type and value on
 * one line — because a response is the permanent record; `review_event_answers` splits the same two
 * halves into columns so the newest submission's events can be queried and listed without decoding
 * every history row. Sharing this pair of functions is what stops the two spellings drifting: the
 * canonical response and the table row are guaranteed to hold the same bytes for the same event.
 *
 * Canonical for the reasons [ReviewResponse.encode] gives: fixed decimals and an uppercase colour,
 * so two identical points are not distinguishable by a diff. A path is provenance for a human
 * reader, not identity, so a tab or newline in a file name is flattened rather than escaped.
 */
internal fun ViewerEvent.encodeValue(): String = when (this) {
    is ViewerEvent.ImagePoint -> listOf(
        formatPoint(x),
        formatPoint(y),
        color.uppercase(),
        assetPath.replace('\t', ' ').replace('\n', ' ')
    ).joinToString("\t")

    // The label is human interpretation, not identity: flatten whitespace that would break the
    // line grammar and bound it to the protocol's option-label limit. The id stays exact.
    is ViewerEvent.Choice -> listOf(
        optionId,
        label.replace('\t', ' ').replace('\n', ' ').take(MAX_CHOICE_LABEL)
    ).joinToString("\t")
}

/**
 * The event a stored `(type, value)` pair names, or null when this build cannot read it.
 *
 * Fail closed per type, exactly as the response reader is: a coordinate outside 0..1 or a colour
 * that is not `#RRGGBB` makes the row unreadable rather than something to round or repair.
 */
internal fun parseEventValue(type: ViewerEventType, value: String): ViewerEvent? = when (type) {
    ViewerEventType.IMAGE_POINT -> {
        // Limit 4 so the asset path is the whole of the rest, tabs already flattened above.
        val fields = value.split('\t', limit = 4)
        if (fields.size != 4) return null
        val x = fields[0].toDoubleOrNull() ?: return null
        val y = fields[1].toDoubleOrNull() ?: return null
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        if (!COLOR_PATTERN.matches(fields[2])) return null
        ViewerEvent.ImagePoint(
            x = x,
            y = y,
            color = fields[2].uppercase(),
            assetPath = fields[3]
        )
    }

    ViewerEventType.CHOICE_SELECT -> {
        val fields = value.split('\t', limit = 2)
        if (fields.size != 2) return null
        if (!PackageProtocol.isKey(fields[0])) return null
        val label = fields[1]
        if (label.isEmpty() || label.length > MAX_CHOICE_LABEL) return null
        ViewerEvent.Choice(optionId = fields[0], label = label)
    }
}

/**
 * The Asset an event was taken from, as a source URI this build can resolve, or null.
 *
 * Written as an exhaustive `when` for the reason [ReviewResponse.completesTask] is: a new event
 * type that arrives without saying which file it belongs to would silently store no `assetId`, and
 * the row would look like an event about the Task rather than about content. Naming every member
 * here is what makes adding one stop the build instead.
 */
internal fun ViewerEvent.assetUri(): String? = when (this) {
    is ViewerEvent.ImagePoint -> assetPath.ifEmpty { null }
    is ViewerEvent.Choice -> null
}

private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

/** The protocol's option-label bound; see `docs/package-protocol.md`. */
private const val MAX_CHOICE_LABEL = 200

/**
 * Four fixed decimals, locale-independent.
 *
 * Fixed rather than shortest-round-trip so the same sampled point always produces the same bytes:
 * the sampler's value is a `Double`, and a canonical record of it cannot depend on the device's
 * default locale or on an output format that changed between releases.
 */
private fun formatPoint(value: Double): String =
    String.format(Locale.ROOT, "%.4f", value)

/**
 * How an answer becomes a judgment, owned by the template.
 *
 * Commit modality and revisability are independent, and both are template properties. The three
 * forms cover the products this app has to serve:
 *
 * - [Immediate] — the intent that carries the answer is itself the commit. Tapping Like commits a
 *   dating judgment at once.
 * - [ConfirmControl] — the intent selects; a control named [controlId] commits. This is the
 *   v0.8.1 behaviour, kept as one member of a wider vocabulary rather than the only one.
 * - [ConfirmIntent] — the intent selects; another declared intent confirms. A later viewer may
 *   offer "select a card, then swipe left to accept" without Core learning a new mode.
 *
 * [ConfirmControl.controlId] is either an id in the version's own button list — an
 * [ButtonType.ACTION] button — or [TASK_REVIEW_COMPLETE_CONTROL], the built-in completion checkbox
 * every review surface has always had. The two are deliberately one namespace: a template that
 * names `complete` gets the checkbox, and a template that names anything else gets no checkbox.
 */
sealed interface CommitPolicy {

    data class Immediate(val intent: ViewerEventType) : CommitPolicy

    data class ConfirmControl(val controlId: String) : CommitPolicy

    data class ConfirmIntent(val intent: ViewerEventType) : CommitPolicy
}

/** The built-in completion control's id; see [CommitPolicy.ConfirmControl.controlId]. */
const val TASK_REVIEW_COMPLETE_CONTROL = "complete"

/**
 * When a Task counts as complete once its parts are answered.
 *
 * A Task is 1..N parts since v0.8.4, so "this submission completes the Task" stopped being one
 * rule. The policy is the template's answer, stored with it and read at the write boundary:
 *
 * - [SinglePart] — a commit completes the Task if the template says it does. Every template
 *   written before v0.8.4 reads as this, which is what they meant: the Task was one decision.
 * - [AllRequiredParts] — the Task completes when every required, present part has an answer.
 *   Unavailable required parts block, exactly as an unviewable file does; a part the Package
 *   detached does not block, because it is no longer part of the judgment.
 * - [AnyPart] — one required answer is enough. A checklist whose items are alternatives.
 * - [ExplicitOnly] — answering never completes the Task; only the completion control does. The
 *   questionnaire a person must finish and then sign off on.
 *
 * The policy decides **automatic** completion. An explicit completion is recorded as such and is
 * never silently cleared by a later part change — see `task_state.completionSource`.
 */
enum class CompletionPolicy(val key: String) {
    SinglePart("single-part"),
    AllRequiredParts("all-required"),
    AnyPart("any-part"),
    ExplicitOnly("explicit-only");

    companion object {
        val Default = SinglePart

        /** The policy a stored word names, or null when this build has never written it. */
        fun fromKey(key: String?): CompletionPolicy? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * The stored half of a Task Review: what it catches, when it commits, and what a commit does.
 *
 * A separate type from [TemplateDefinition.TaskReview] on purpose. The code-defined definitions
 * carry pinned content hashes and say what a template of this kind *can* be; this says what one
 * stored version *is doing*, and it names only the policies this build enforces. [viewer] is a
 * reference rather than a copy, exactly as [TemplateDefinition.TaskReview.viewerTemplate] is, so a
 * review version stays meaningful after the viewer moves on.
 *
 * [accepts] empty means this review catches no viewer events: it is the rapid media template, and
 * an empty commit is its ordinary answer. A non-empty set means a commit must carry at least one
 * event — checked at the write boundary, not only by a disabled control.
 *
 * [completion] is how a Task made of several parts becomes complete; a definition written before
 * v0.8.4 defaults to [CompletionPolicy.SinglePart], which is what those definitions did.
 */
data class TaskReviewDefinition(
    val viewer: TemplateVersionRef,
    val accepts: Set<ViewerEventType>,
    val commit: CommitPolicy,
    val revisable: Boolean,
    val completesTask: Boolean,
    val advanceAfterCommit: Boolean,
    val completion: CompletionPolicy = CompletionPolicy.Default
)

/** The encoding below's version, written as the first line of every newly stored definition. */
const val TASK_REVIEW_DEFINITION_VERSION = 3

private const val DEFINITION_TAG = "v"
private const val VIEWER_TAG = "viewer"
private const val ACCEPTS_TAG = "accepts"
private const val COMMIT_TAG = "commit"
private const val REVISABLE_TAG = "revisable"
private const val COMPLETES_TAG = "completes"
private const val ADVANCE_TAG = "advance"
private const val COMPLETION_TAG = "completion"
private const val COMMIT_IMMEDIATE = "immediate:"
private const val COMMIT_CONFIRM_CONTROL = "confirm:control:"
private const val COMMIT_CONFIRM_INTENT = "confirm:intent:"
private const val COMMIT_LEGACY_EXPLICIT = "explicit:"

/**
 * A Task Review definition as one string, one fact per line.
 *
 * ```text
 * v=3
 * viewer=builtin.media.viewer@1
 * accepts=image.point
 * commit=confirm:control:submit
 * revisable=true
 * completes=true
 * advance=true
 * completion=single-part
 * ```
 *
 * Canonical: accepted event keys are sorted, and the line order is fixed, so the same definition
 * always produces the same bytes. Hand-written for the reason [ReviewResponse.encode] gives — the
 * domain package does not depend on Android's `org.json`.
 *
 * The `completion` line arrived in v3. A v1 or v2 payload has none and reads as
 * [CompletionPolicy.SinglePart], which is what those definitions meant: one Task, one decision.
 */
fun encodeTaskReviewDefinition(definition: TaskReviewDefinition): String = listOf(
    "$DEFINITION_TAG=$TASK_REVIEW_DEFINITION_VERSION",
    "$VIEWER_TAG=${definition.viewer}",
    "$ACCEPTS_TAG=${definition.accepts.map { it.key }.sorted().joinToString(",")}",
    "$COMMIT_TAG=${encodeCommit(definition.commit)}",
    "$REVISABLE_TAG=${definition.revisable}",
    "$COMPLETES_TAG=${definition.completesTask}",
    "$ADVANCE_TAG=${definition.advanceAfterCommit}",
    "$COMPLETION_TAG=${definition.completion.key}"
).joinToString("\n")

private fun encodeCommit(commit: CommitPolicy): String = when (commit) {
    is CommitPolicy.Immediate -> "$COMMIT_IMMEDIATE${commit.intent.key}"
    is CommitPolicy.ConfirmControl -> "$COMMIT_CONFIRM_CONTROL${commit.controlId}"
    is CommitPolicy.ConfirmIntent -> "$COMMIT_CONFIRM_INTENT${commit.intent.key}"
}

/**
 * The definition a stored payload holds, or null when this build cannot read it.
 *
 * Version 1 wrote `commit=explicit:<control>` and had no revisability line; it is still readable,
 * with [CommitPolicy.ConfirmControl] and `revisable=true`, because that was what those definitions
 * did: a second commit was accepted. Version 2 requires every line.
 *
 * Fail closed, like every other stored vocabulary in the app: an unknown version, an unknown event
 * key, a commit mode this build has never written, a duplicate or unknown line, a missing required
 * line, or a malformed reference all make the whole definition unreadable. A review surface that
 * guessed would be recording judgments under a definition nobody chose.
 *
 * An empty `accepts=` line is legal and means exactly that: this review catches no viewer events.
 */
fun decodeTaskReviewDefinition(
    text: String,
    version: Int
): TaskReviewDefinition? {
    if (version !in 1..TASK_REVIEW_DEFINITION_VERSION) return null
    if (text.isEmpty()) return null

    val fields = mutableMapOf<String, String>()
    for (line in text.split('\n')) {
        if (line.isBlank()) continue
        val index = line.indexOf('=')
        if (index <= 0) return null
        val key = line.substring(0, index)
        val value = line.substring(index + 1)
        if (key in fields) return null
        fields[key] = value
    }
    val knownTags = when (version) {
        1 -> KNOWN_TAGS_V1
        2 -> KNOWN_TAGS_V2
        else -> KNOWN_TAGS_V3
    }
    if (fields.keys.any { it !in knownTags }) return null
    if (fields[DEFINITION_TAG] != version.toString()) return null

    val viewer = parseTemplateVersionRef(fields[VIEWER_TAG] ?: return null) ?: return null
    val accepts = (fields[ACCEPTS_TAG] ?: return null)
        .split(',')
        .filter { it.isNotBlank() }
        .map { ViewerEventType.fromKey(it.trim()) ?: return null }
        .toSet()
    val commit = if (version == 1) {
        val id = fields[COMMIT_TAG]?.removePrefix(COMMIT_LEGACY_EXPLICIT)
            ?.takeIf { it != fields[COMMIT_TAG] && it.isNotEmpty() }
            ?: return null
        CommitPolicy.ConfirmControl(id)
    } else {
        parseCommit(fields[COMMIT_TAG] ?: return null) ?: return null
    }
    val revisable = if (version == 1) true else booleanField(fields[REVISABLE_TAG]) ?: return null
    val completes = booleanField(fields[COMPLETES_TAG]) ?: return null
    val advance = booleanField(fields[ADVANCE_TAG]) ?: return null
    // v1 and v2 predate multiple parts, so their only reading is the one they had: a commit that
    // completes three parts' worth of nothing is a commit that completes the Task.
    val completion = if (version < 3) {
        CompletionPolicy.SinglePart
    } else {
        CompletionPolicy.fromKey(fields[COMPLETION_TAG] ?: return null) ?: return null
    }

    return TaskReviewDefinition(
        viewer = viewer,
        accepts = accepts,
        commit = commit,
        revisable = revisable,
        completesTask = completes,
        advanceAfterCommit = advance,
        completion = completion
    )
}

private fun parseCommit(value: String): CommitPolicy? = when {
    value.startsWith(COMMIT_IMMEDIATE) ->
        ViewerEventType.fromKey(value.removePrefix(COMMIT_IMMEDIATE))
            ?.let(CommitPolicy::Immediate)

    value.startsWith(COMMIT_CONFIRM_CONTROL) ->
        value.removePrefix(COMMIT_CONFIRM_CONTROL)
            .takeIf { it.isNotEmpty() }
            ?.let(CommitPolicy::ConfirmControl)

    value.startsWith(COMMIT_CONFIRM_INTENT) ->
        ViewerEventType.fromKey(value.removePrefix(COMMIT_CONFIRM_INTENT))
            ?.let(CommitPolicy::ConfirmIntent)

    else -> null
}

private val KNOWN_TAGS_V1 = setOf(
    DEFINITION_TAG, VIEWER_TAG, ACCEPTS_TAG, COMMIT_TAG, COMPLETES_TAG, ADVANCE_TAG
)
private val KNOWN_TAGS_V2 = KNOWN_TAGS_V1 + REVISABLE_TAG
private val KNOWN_TAGS_V3 = KNOWN_TAGS_V2 + COMPLETION_TAG

private fun booleanField(value: String?): Boolean? = when (value) {
    "true" -> true
    "false" -> false
    else -> null
}

/**
 * `builtin.media.viewer@1` back into its two parts, or null when the token is not that shape.
 *
 * The version is required and positive: `id@0` is not a version this app ever writes, and an
 * empty id names no lineage. The last `@` separates the two, so an id may itself contain one —
 * nothing mints such ids today, but refusing to guess which `@` was meant is cheaper than a
 * pointer that silently points somewhere else.
 */
fun parseTemplateVersionRef(text: String): TemplateVersionRef? {
    val at = text.lastIndexOf('@')
    if (at <= 0 || at == text.lastIndex) return null
    val id = text.substring(0, at)
    val version = text.substring(at + 1).toIntOrNull() ?: return null
    if (version < 1) return null
    return TemplateVersionRef(TemplateId(id), version)
}
