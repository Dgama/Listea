package com.example.listea.domain

/**
 * A condition between two TaskParts of one Task, and the applicability it derives.
 *
 * v0.8.4 kept a conditional chain inside one `form` body: every question in the chain had to be a
 * field of one Prompt, and therefore a kind that form Viewer could draw. v0.8.5 puts the same
 * `equals`/`in` spelling between **parts**, so a yes/no gate can make a PDF, an image, a vocabulary
 * card, a dating card or another questionnaire apply. The part is still the decision unit; the
 * condition only says when it applies.
 *
 * [sourcePartKey] is a **part key within the Task**, not a Package Prompt key globally: the
 * composition decides which Prompts share a Task, and a condition that crosses that boundary is an
 * import diagnostic. [field] is an answer field the source Viewer itself declares (see
 * [ViewerCapability.conditionFields]); Core never parses a body to guess one.
 *
 * The condition is stored on the **target** part as canonical text. Applicability is never stored
 * — it is derived from the current answers by [evaluateApplicability], so there is one truth rather
 * than a column that can disagree with the response it was computed from.
 */
data class PartCondition(
    val sourcePartKey: String,
    val field: String,
    val match: PartConditionMatch,
    val values: List<String>
) {

    /** Whether an answer to [field] satisfies this condition. A missing answer never does. */
    fun matches(value: String?): Boolean = value != null && value in values
}

/** The two spellings a condition may use; exactly one is present in the stored object. */
enum class PartConditionMatch {
    Equals,
    In
}

/**
 * What a present part's condition currently says about it.
 *
 * Derived, never stored. A part with no condition is always [Active]; the other two states are
 * computed from the source's answer (and the source's own state) at read time and around every
 * write that can move an answer. "Masked" is a drawing decision; the page list does not change.
 */
enum class PartApplicability(val wire: String) {
    Pending("pending"),
    Active("active"),
    Inactive("inactive");

    companion object {
        /** The state a stored word names, or null when this build has never written it. */
        fun fromWire(value: String?): PartApplicability? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * The field name a `choice.single` Viewer exposes to a downstream condition.
 *
 * One name for every option-tap Viewer: the answer is the option the person chose, and its id is
 * the value. Profile and vocabulary differ in what they show, not in what they answer.
 */
const val CONDITION_FIELD_CHOICE = "choice"

/** The number of values one `in` condition may hold; shared with the form Viewer's grammar. */
const val MAX_CONDITION_VALUES = 50

/**
 * One condition value's bound.
 *
 * A value is compared against an answer, and an answer is a text field's content as well as an
 * option id; the form Viewer's text bound is therefore the one natural limit. Option ids are
 * already shorter by their own rule.
 */
private const val MAX_CONDITION_VALUE = MAX_FORM_TEXT

/**
 * The condition as the canonical object the database and the export hold.
 *
 * Key order is **fixed** — `prompt`, `field`, then `equals` or `in` — rather than sorted as
 * [JsonValue.canonical] sorts objects. The order is part of the document a receiver reads: the
 * source, then the field, then the test, which is how the design states it. Canonical means the
 * same condition always produces the same bytes, so a stored condition can be compared and hashed
 * without normalising first.
 *
 * The caller owns the value-count invariant ([PartConditionMatch.Equals] has exactly one value,
 * [PartConditionMatch.In] has one to [MAX_CONDITION_VALUES]); [decodePartCondition] enforces it on
 * everything read back.
 */
fun encodePartCondition(condition: PartCondition): String = buildString {
    append('{')
    append("\"prompt\":").append(jsonString(condition.sourcePartKey))
    append(",\"field\":").append(jsonString(condition.field))
    when (condition.match) {
        PartConditionMatch.Equals ->
            append(",\"equals\":").append(jsonString(condition.values.first()))

        PartConditionMatch.In -> {
            append(",\"in\":[")
            condition.values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append(jsonString(value))
            }
            append(']')
        }
    }
    append('}')
}

/**
 * A stored condition read back, or null when this build cannot read it.
 *
 * Fail closed, like every stored vocabulary in the app. Unknown keys, a missing or malformed
 * `prompt`/`field`, both or neither of `equals`/`in`, an empty `in`, more than
 * [MAX_CONDITION_VALUES] values, or a value outside the text bound all make the whole condition
 * unreadable. The caller then treats the part as unavailable rather than as unconditional: a
 * condition that was written and cannot be read is not permission to ignore it.
 */
fun decodePartCondition(text: String): PartCondition? {
    val root = runCatching { Json.parse(text) }.getOrNull()?.asObjOrNull() ?: return null
    if ((root.keys - setOf("prompt", "field", "equals", "in")).isNotEmpty()) return null

    val source = root["prompt"]?.asStringOrNull()
        ?.takeIf { PackageProtocol.isKey(it) }
        ?: return null
    val field = root["field"]?.asStringOrNull()
        ?.takeIf { PackageProtocol.isKey(it) }
        ?: return null

    val hasEquals = root.has("equals")
    val hasIn = root.has("in")
    if (hasEquals == hasIn) return null

    if (hasEquals) {
        val value = root["equals"]?.asStringOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_CONDITION_VALUE }
            ?: return null
        return PartCondition(source, field, PartConditionMatch.Equals, listOf(value))
    }

    val values = (root["in"] as? JsonValue.Arr)?.items?.map { item ->
        item.asStringOrNull()?.takeIf { it.isNotEmpty() && it.length <= MAX_CONDITION_VALUE }
            ?: return null
    } ?: return null
    if (values.isEmpty() || values.size > MAX_CONDITION_VALUES) return null
    return PartCondition(source, field, PartConditionMatch.In, values)
}

/**
 * One part as applicability evaluation needs to see it.
 *
 * A projection, not the entity: the evaluator is pure and knows nothing about Room. [response] is
 * the part's canonical current answer, or null when it has none — an empty string is treated as
 * none, because that is how a part row with no state reads. [kind] is the payload kind, which is
 * what decides how an answer field is read; [availability] is carried for callers that show both
 * facts, and deliberately does not change applicability — the two are orthogonal.
 *
 * [required] and [present] are part of the contract even though the evaluator only reads presence:
 * a detached source row is not in the list at all, and a present one is evaluated.
 */
data class ApplicabilityPart(
    val partKey: String,
    val position: Int,
    val required: Boolean = true,
    val present: Boolean = true,
    val availability: String? = null,
    val condition: PartCondition? = null,
    val kind: String? = null,
    val response: String? = null
)

/**
 * Every present part's applicability, derived from the parts' conditions and current answers.
 *
 * The one implementation of D2, called by reads, writes, import and export. Ordered by [position],
 * so a source is always decided before the parts that depend on it:
 *
 * - no condition → [PartApplicability.Active];
 * - source missing, detached or not earlier → [PartApplicability.Inactive] (fail closed: the
 *   import has already marked the part unavailable, and an orphaned condition is not a licence to
 *   activate it);
 * - source inactive → inactive (propagation; a stale answer in a closed branch cannot reopen it);
 * - source pending → pending;
 * - source active but unanswered → pending;
 * - source active and answered but without the named field → inactive (an answer that does not
 *   expose the field cannot satisfy a downstream condition);
 * - otherwise active when the value is one of the condition's values, inactive when it is not.
 *
 * Absent parts are not in the result. A response that is present but unreadable is treated as an
 * answer without the named field, not as no answer at all.
 */
fun evaluateApplicability(parts: List<ApplicabilityPart>): Map<String, PartApplicability> {
    val byKey = parts.associateBy { it.partKey }
    val result = LinkedHashMap<String, PartApplicability>(parts.size)

    for (part in parts.sortedBy { it.position }) {
        if (!part.present) continue
        val condition = part.condition
        if (condition == null) {
            result[part.partKey] = PartApplicability.Active
            continue
        }

        val source = byKey[condition.sourcePartKey]
        if (source == null || !source.present || source.position >= part.position) {
            result[part.partKey] = PartApplicability.Inactive
            continue
        }

        result[part.partKey] = when (result[source.partKey] ?: PartApplicability.Active) {
            PartApplicability.Inactive -> PartApplicability.Inactive
            PartApplicability.Pending -> PartApplicability.Pending
            PartApplicability.Active -> when (val read = condition.readField(source)) {
                FieldRead.Unanswered -> PartApplicability.Pending
                FieldRead.Missing -> PartApplicability.Inactive
                is FieldRead.Value ->
                    if (condition.matches(read.value)) PartApplicability.Active
                    else PartApplicability.Inactive
            }
        }
    }

    return result
}

/** What reading one condition field out of an answer produced. */
private sealed interface FieldRead {
    /** The source has no answer yet. */
    data object Unanswered : FieldRead

    /** The source answered, but its answer does not carry the named field. */
    data object Missing : FieldRead

    data class Value(val value: String) : FieldRead
}

/**
 * The value [field] holds in one part's answer.
 *
 * The same canonical answer the export reads. A `choice.single` part exposes the newest
 * `choice.select` event as [CONDITION_FIELD_CHOICE]; a `form` part exposes its visible field keys;
 * every other kind exposes nothing, which is why a media part can never be a condition source.
 * The field-name rule itself lives on the Viewer ([ViewerCapability.conditionFields]); this
 * function only says where each family keeps the value.
 */
private fun PartCondition.readField(part: ApplicabilityPart): FieldRead {
    val text = part.response?.takeIf { it.isNotEmpty() } ?: return FieldRead.Unanswered
    val response = ReviewResponse.parse(text) ?: return FieldRead.Missing

    return when (part.kind) {
        "profile", "vocabulary" -> {
            if (field != CONDITION_FIELD_CHOICE) return FieldRead.Missing
            val choice = response.viewerEvents.filterIsInstance<ViewerEvent.Choice>().lastOrNull()
                ?: return FieldRead.Missing
            FieldRead.Value(choice.optionId)
        }

        "form" -> response.structuredFields
            .firstOrNull { it.fieldKey == field }
            ?.let { FieldRead.Value(it.value) }
            ?: FieldRead.Missing

        else -> FieldRead.Missing
    }
}
