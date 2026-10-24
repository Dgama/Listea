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
 * **One member in v0.8.1.** The image viewer's long press is the only intent a shipped viewer
 * produces, and it is enough to prove the path: an event that comes from the content rather than
 * from a button somebody configured. A choice event arrives with the structured page in v0.8.2.
 */
enum class ViewerEventType(val key: String) {

    /** A point on an image, with the colour of the image at that point. */
    IMAGE_POINT("image.point");

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
}

private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

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
 * When a Task Review commits.
 *
 * **Explicit, and owned by the template.** The submit control is part of what the template
 * declares, not something the viewer draws for every review. That is what makes a Headless
 * template and a template with a custom Submit button the same thing: both name a control, and the
 * surface draws exactly the control the definition names.
 *
 * [controlId] is either an id in the version's own button list — an [ButtonType.ACTION] button —
 * or [TASK_REVIEW_COMPLETE_CONTROL], the built-in completion checkbox every review surface has
 * always had. The two are deliberately one namespace: a template that names `complete` gets the
 * checkbox, and a template that names anything else gets no checkbox at all.
 */
sealed interface CommitPolicy {

    data class Explicit(val controlId: String) : CommitPolicy
}

/** The built-in completion control's id; see [CommitPolicy.Explicit.controlId]. */
const val TASK_REVIEW_COMPLETE_CONTROL = "complete"

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
 */
data class TaskReviewDefinition(
    val viewer: TemplateVersionRef,
    val accepts: Set<ViewerEventType>,
    val commit: CommitPolicy,
    val completesTask: Boolean,
    val advanceAfterCommit: Boolean
)

/** The encoding below's version, written as the first line of every stored definition. */
const val TASK_REVIEW_DEFINITION_VERSION = 1

private const val DEFINITION_TAG = "v"
private const val VIEWER_TAG = "viewer"
private const val ACCEPTS_TAG = "accepts"
private const val COMMIT_TAG = "commit"
private const val COMPLETES_TAG = "completes"
private const val ADVANCE_TAG = "advance"
private const val COMMIT_EXPLICIT = "explicit:"

/**
 * A Task Review definition as one string, one fact per line.
 *
 * ```text
 * v=1
 * viewer=builtin.media.viewer@1
 * accepts=image.point
 * commit=explicit:submit
 * completes=true
 * advance=true
 * ```
 *
 * Canonical: accepted event keys are sorted, and the line order is fixed, so the same definition
 * always produces the same bytes. Hand-written for the reason [ReviewResponse.encode] gives — the
 * domain package does not depend on Android's `org.json`.
 */
fun encodeTaskReviewDefinition(definition: TaskReviewDefinition): String = listOf(
    "$DEFINITION_TAG=$TASK_REVIEW_DEFINITION_VERSION",
    "$VIEWER_TAG=${definition.viewer}",
    "$ACCEPTS_TAG=${definition.accepts.map { it.key }.sorted().joinToString(",")}",
    "$COMMIT_TAG=$COMMIT_EXPLICIT${(definition.commit as CommitPolicy.Explicit).controlId}",
    "$COMPLETES_TAG=${definition.completesTask}",
    "$ADVANCE_TAG=${definition.advanceAfterCommit}"
).joinToString("\n")

/**
 * The definition a stored payload holds, or null when this build cannot read it.
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
    if (version != TASK_REVIEW_DEFINITION_VERSION) return null
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
    if (fields.keys.any { it !in KNOWN_TAGS }) return null
    if (fields[DEFINITION_TAG] != TASK_REVIEW_DEFINITION_VERSION.toString()) return null

    val viewer = parseTemplateVersionRef(fields[VIEWER_TAG] ?: return null) ?: return null
    val accepts = (fields[ACCEPTS_TAG] ?: return null)
        .split(',')
        .filter { it.isNotBlank() }
        .map { ViewerEventType.fromKey(it.trim()) ?: return null }
        .toSet()
    val commit = when {
        fields[COMMIT_TAG].orEmpty().startsWith(COMMIT_EXPLICIT) ->
            fields.getValue(COMMIT_TAG).removePrefix(COMMIT_EXPLICIT).trim()

        else -> null
    }?.takeIf { it.isNotEmpty() } ?: return null
    val completes = booleanField(fields[COMPLETES_TAG]) ?: return null
    val advance = booleanField(fields[ADVANCE_TAG]) ?: return null

    return TaskReviewDefinition(
        viewer = viewer,
        accepts = accepts,
        commit = CommitPolicy.Explicit(commit),
        completesTask = completes,
        advanceAfterCommit = advance
    )
}

private val KNOWN_TAGS = setOf(
    DEFINITION_TAG, VIEWER_TAG, ACCEPTS_TAG, COMMIT_TAG, COMPLETES_TAG, ADVANCE_TAG
)

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
