package com.example.listea.domain

/**
 * What a person committed when they submitted a Task review.
 *
 * Held separately from [TaskState] because it is a different kind of fact. TaskState says what is
 * true now and can be edited by anyone from anywhere; a response is what was submitted, at one
 * moment, under one template version, and is never edited. The two often agree — a completed Task
 * whose latest response says `completed = true` — and the model must not depend on that, because
 * re-reviewing a Task that is already complete appends a second response while the state does not
 * move at all.
 *
 * **Explicitly not a webhook body.** §8 freezes the wire format and this is not it. The payload
 * carries *wire values* — what a settings page currently calls each action — while a response
 * carries the *ids* the Task is marked with, so renaming an action changes every future payload and
 * no stored response. Modelled on the domain rather than on the wire so that a second template can
 * answer with something a webhook has never had a field for.
 */
sealed interface ReviewResponse {

    /**
     * The rapid-media answer: dealt with or not, and what it was marked with.
     *
     * A snapshot, not a set of changes — it says what was true at submission, which is why it
     * carries the mark set even though toggling a button is not itself a submission. Its latest
     * value rides along with the next judgment about the Task.
     *
     * **One set of button ids, and no favourite flag beside it**, which is the change v0.5.4 makes.
     * `favorite` and `customActions` were two fields describing one thing — `★` is the button with
     * the id `favorite`, and the two could disagree about whether it was on. A snapshot that can
     * contradict itself about one button is a snapshot whose readers have to pick a field to
     * believe, which is exactly what a record of what somebody decided must not be.
     *
     * The reset of [com.example.listea.data.AssetDecisions] and [ReviewDecisions] reaches here
     * because it is the same fact at a third layer: the row says what is marked now, this says what
     * was marked when somebody said so, and both are a set of ids.
     */
    data class MediaReview(
        val completed: Boolean,
        val selections: Set<String>
    ) : ReviewResponse

    /**
     * A review committed under a template that catches viewer events.
     *
     * The Task-level counterpart of [MediaReview]: it says whether the Task is complete and carries
     * the events the viewer reported before the commit. It is a separate response rather than a
     * field on [MediaReview] because the two have different vocabularies and different readers —
     * a media review's selections are button ids, an event is a fact about content — and because a
     * legacy row must keep decoding as exactly the thing it was written as.
     *
     * Empty [events] is a legal value of the type and is refused by a template whose definition
     * accepts events; the refusal lives at the write boundary, so a caller that bypassed the UI
     * cannot record an empty commit under a template that promised otherwise.
     */
    data class PrimaryReview(
        val completed: Boolean,
        val events: List<ViewerEvent>
    ) : ReviewResponse

    /**
     * A structured answer: the visible fields of one `form` Prompt, as one TaskPart's judgment.
     *
     * Fields are not viewer events — there is no point or option a card reported, only the record a
     * questionnaire produced — so this is its own response shape rather than a [PrimaryReview]
     * carrying invented events. [fields] holds only the fields that were visible and answered at
     * commit time; a hidden field is not part of the answer and changing the branch does not leave
     * one behind.
     */
    data class StructuredReview(
        val completed: Boolean,
        val fields: List<FieldAnswer>
    ) : ReviewResponse

    companion object
}

/**
 * The form a response is stored in — `review_events.response`, one text column.
 *
 * Canonical, meaning the same response always produces the same bytes. Two submissions a person
 * would call identical must not be distinguishable by a diff, or every reader of this column ends
 * up normalising before it can compare, and a test that pins the bytes is worthless.
 *
 * Button ids are sorted, and blanks are dropped going in, for the same reasons
 * [com.example.listea.data.encodeActionIds] does it on the item rows: a set that varies with the
 * order somebody tapped things
 * looks changed when nothing has. Sorting by plain string order is enough — the ids are generated,
 * not typed, so nothing here depends on a locale's idea of alphabetical.
 *
 * **The field names changed in v0.5.4 and no migration was written for them.** This column holds
 * `completed=…;selections=…` now, and older rows hold `completed=…;favorite=…;actions=…`. The
 * reader added in v0.8.1 tells them apart by which keys are present and recovers only what the
 * two spellings agree on; the older mark keys are not guessed back into a selection set.
 *
 * Hand-written rather than JSON, for two reasons that both matter. `org.json` is an Android class
 * and this package does not depend on Android; and the wire format's own encoder escapes a solidus
 * and orders its fields, neither of which has anything to say about what a stored response is.
 * Formatting this like the payload would be the first step back toward the two being the same
 * thing.
 *
 * **v0.8.1 adds a reader.** A review surface now shows the latest committed result beside the
 * control that can replace it, so [parse] exists and has a caller. The legacy grammar is read as
 * far as it goes — `completed` is required, `selections` is read when present, and any other key
 * an older writer used is ignored rather than treated as corruption, because its meaning is not
 * recoverable as selections. The primary grammar is read fail-closed; see [parse].
 */
fun ReviewResponse.encode(): String = when (this) {
    is ReviewResponse.MediaReview -> listOf(
        "completed=$completed",
        "selections=${selections.filter { it.isNotBlank() }.sorted().joinToString(",")}"
    ).joinToString(";")

    is ReviewResponse.PrimaryReview -> buildList {
        add("primary=$PRIMARY_RESPONSE_VERSION")
        add("completed=$completed")
        for (event in events) add("event=${event.encodeLine()}")
    }.joinToString("\n")

    is ReviewResponse.StructuredReview -> listOf(
        "$STRUCTURED_TAG=$STRUCTURED_RESPONSE_VERSION",
        "completed=$completed",
        // One canonical JSON array, ordered by field key. The answer is free text as well as ids,
        // so the event grammar's tab-separated line cannot hold it: a text field may contain tabs,
        // newlines and anything else a person typed.
        "$ANSWERS_TAG=${encodeFieldAnswers(fields)}"
    ).joinToString("\n")
}

/** The envelope version [ReviewResponse.PrimaryReview] writes. */
const val PRIMARY_RESPONSE_VERSION = 2

/** The envelope version [ReviewResponse.StructuredReview] writes. */
const val STRUCTURED_RESPONSE_VERSION = 1

private const val PRIMARY_TAG = "primary"
private const val STRUCTURED_TAG = "structured"
private const val COMPLETED_TAG = "completed"
private const val SELECTIONS_TAG = "selections"
private const val EVENT_TAG = "event"
private const val ANSWERS_TAG = "answers"

private fun encodeFieldAnswers(fields: List<FieldAnswer>): String =
    JsonValue.Arr(
        fields.sortedBy { it.fieldKey }.map { answer ->
            JsonValue.Obj(
                listOf(
                    "key" to JsonValue.Str(answer.fieldKey),
                    "type" to JsonValue.Str(answer.type),
                    "value" to JsonValue.Str(answer.value)
                )
            )
        }
    ).canonical()

/**
 * A stored response read back, or null when this build cannot read it.
 *
 * The first line decides the grammar: `primary=…` is a [ReviewResponse.PrimaryReview], anything
 * else is the legacy semicolon form. A response whose grammar is primary and whose version is not
 * [PRIMARY_RESPONSE_VERSION] is *unreadable* rather than read as an older shape — reading a later
 * envelope with today's keys is exactly the guess the version gate exists to refuse, and a
 * response is a permanent record of somebody's judgment.
 *
 * The primary reader is strict: an unknown line, a duplicate flag, an unknown event type, a point
 * outside 0..1, or a malformed colour makes the whole response null. Dropping an event would
 * silently change what a person recorded. The legacy reader is deliberately more forgiving —
 * unknown keys are ignored — because that grammar was never versioned and the keys beside
 * `completed` are older spellings of marks that this reader does not claim to recover.
 */
fun ReviewResponse.Companion.parse(text: String): ReviewResponse? {
    val first = text.substringBefore('\n')
    return when {
        first.startsWith("$PRIMARY_TAG=") -> parsePrimary(text)
        first.startsWith("$STRUCTURED_TAG=") -> parseStructured(text)
        else -> parseLegacy(text)
    }
}

private fun parseStructured(text: String): ReviewResponse.StructuredReview? {
    val lines = text.split('\n')
    if (lines.firstOrNull() != "$STRUCTURED_TAG=$STRUCTURED_RESPONSE_VERSION") return null

    var completed: Boolean? = null
    var answers: List<FieldAnswer>? = null
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val index = line.indexOf('=')
        if (index <= 0) return null
        when (line.substring(0, index)) {
            COMPLETED_TAG -> {
                if (completed != null) return null
                completed = when (line.substring(index + 1)) {
                    "true" -> true
                    "false" -> false
                    else -> return null
                }
            }

            ANSWERS_TAG -> {
                if (answers != null) return null
                answers = parseFieldAnswers(line.substring(index + 1)) ?: return null
            }

            else -> return null
        }
    }
    return completed?.let { ReviewResponse.StructuredReview(it, answers ?: emptyList()) }
}

private fun parseFieldAnswers(text: String): List<FieldAnswer>? {
    val array = runCatching { Json.parse(text) }.getOrNull()?.asArrOrNull() ?: return null
    val seen = mutableSetOf<String>()
    val fields = array.items.map { value ->
        val obj = value.asObjOrNull() ?: return null
        if (obj.keys != setOf("key", "type", "value")) return null
        val key = obj["key"]?.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
        if (!seen.add(key)) return null
        val type = obj["type"]?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val answer = obj["value"]?.asStringOrNull() ?: return null
        FieldAnswer(fieldKey = key, type = type, value = answer)
    }
    return fields
}

private fun parsePrimary(text: String): ReviewResponse.PrimaryReview? {
    val lines = text.split('\n')
    if (lines.firstOrNull() != "$PRIMARY_TAG=$PRIMARY_RESPONSE_VERSION") return null

    var completed: Boolean? = null
    val events = mutableListOf<ViewerEvent>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val index = line.indexOf('=')
        if (index <= 0) return null
        when (line.substring(0, index)) {
            COMPLETED_TAG -> {
                if (completed != null) return null
                completed = when (line.substring(index + 1)) {
                    "true" -> true
                    "false" -> false
                    else -> return null
                }
            }

            EVENT_TAG -> events += parseEvent(line.substring(index + 1)) ?: return null

            else -> return null
        }
    }
    return completed?.let { ReviewResponse.PrimaryReview(it, events) }
}

private fun parseLegacy(text: String): ReviewResponse.MediaReview? {
    var completed: Boolean? = null
    var selections: Set<String>? = null
    for (field in text.split(';')) {
        if (field.isBlank()) continue
        val index = field.indexOf('=')
        if (index <= 0) return null
        val value = field.substring(index + 1)
        when (field.substring(0, index)) {
            COMPLETED_TAG -> completed = when (value) {
                "true" -> true
                "false" -> false
                else -> return null
            }

            SELECTIONS_TAG -> selections =
                value.split(',').filter { it.isNotBlank() }.toSet()
        }
    }
    return completed?.let {
        ReviewResponse.MediaReview(
            completed = it,
            selections = selections.orEmpty()
        )
    }
}

/**
 * One event on its response line: its type key, a tab, and [ViewerEvent.encodeValue].
 *
 * The value grammar is shared with `review_event_answers` rather than restated here, so the
 * permanent record and the queryable row cannot spell the same event two ways.
 */
private fun ViewerEvent.encodeLine(): String = "${type.key}\t${encodeValue()}"

private fun parseEvent(value: String): ViewerEvent? {
    val tab = value.indexOf('\t')
    if (tab <= 0) return null
    val type = ViewerEventType.fromKey(value.substring(0, tab)) ?: return null
    return parseEventValue(type, value.substring(tab + 1))
}

/**
 * The viewer events a response carries, in the order they were reported.
 *
 * Empty for a [ReviewResponse.MediaReview], which is not a failure to read: the legacy answer is a
 * judgment with no events, and a writer that stores a submission's events as rows uses this to
 * decide there is nothing to store. Exhaustive for the reason [completesTask] is — a response type
 * that arrives without being classified would silently drop its events from the table.
 */
val ReviewResponse.viewerEvents: List<ViewerEvent> get() = when (this) {
    is ReviewResponse.MediaReview -> emptyList()
    is ReviewResponse.PrimaryReview -> events
    // A structured answer has fields, not events. Its rows are projected separately, with the
    // field identity the event table's `fieldKey` column exists for.
    is ReviewResponse.StructuredReview -> emptyList()
}

/**
 * The structured fields a response carries, or nothing.
 *
 * The projection boundary's input: [viewerEvents] names what goes to `review_event_answers` as
 * events, this names what goes there as field rows. Keeping the two accessors exhaustive means a
 * response shape that arrives without saying which family it belongs to stops the build.
 */
val ReviewResponse.structuredFields: List<FieldAnswer> get() = when (this) {
    is ReviewResponse.MediaReview -> emptyList()
    is ReviewResponse.PrimaryReview -> emptyList()
    is ReviewResponse.StructuredReview -> fields
}

/**
 * The one thing a committed response moves in [TaskState]: whether the Task is complete.
 *
 * The rest of a response is a snapshot — [ReviewResponse.MediaReview.selections] records what the
 * Task was marked with at the moment somebody judged it, and toggling a button is not itself a
 * submission (§5). Writing it back from a response would quietly turn a record of a judgment into a
 * second way to make one.
 *
 * Spelled as an exhaustive `when` rather than read off a member, for the reason
 * [TaskAction.isSubmission] is: a response type that arrives without being classified would append
 * its event and leave the state exactly as it was, and a review that is on record but did not
 * complete anything is the kind of disagreement the state/event split exists to make impossible.
 * Naming the answer here means adding one stops the build instead.
 */
val ReviewResponse.completesTask: Boolean get() = when (this) {
    is ReviewResponse.MediaReview -> completed
    is ReviewResponse.PrimaryReview -> completed
    is ReviewResponse.StructuredReview -> completed
}
