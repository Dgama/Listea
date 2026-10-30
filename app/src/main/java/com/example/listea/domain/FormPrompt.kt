package com.example.listea.domain

/**
 * A `form`/`1` Prompt body: a small questionnaire a person fills in and submits as one part.
 *
 * A conditional chain is **one** Prompt and therefore one TaskPart. The fields are internal answers,
 * not parts, Sections or Tasks: hiding `what` because `ate` is `no` changes what the person can
 * answer, never how many decisions the Task holds. That is the boundary the v0.8.4 design draws
 * around structured content, and this reader enforces it by keeping every field inside one body.
 *
 * Fail closed, like every other Viewer: unknown keys are refused rather than ignored, keys and
 * labels are bounded by the limits the protocol already publishes, and a condition may reference
 * only an **earlier** field, because a later one has not been answered when visibility is decided.
 */
data class FormPrompt(
    /** The body's title, which is the card's heading. */
    override val stem: String,
    val fields: List<FormField>
) : PromptContent {

    /** A form is not a multiple-choice Prompt; the option list belongs to its fields. */
    override val options: List<PromptOption> get() = emptyList()
}

/** One question in a [FormPrompt], in the order the body declared it. */
data class FormField(
    val key: String,
    val type: FormFieldType,
    val label: String,
    /** Whether a visible instance must be answered before the form can be submitted. */
    val required: Boolean,
    /** The declared options; empty for [FormFieldType.Text]. */
    val options: List<PromptOption> = emptyList(),
    /** Shown only when this condition holds against an earlier field's answer. */
    val condition: FormCondition? = null
)

/** The field kinds schema 1 declares. */
enum class FormFieldType(val wire: String) {
    ChoiceSingle("choice.single"),
    Text("text");

    companion object {
        fun fromWire(value: String?): FormFieldType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One field's visibility condition: an earlier field's answer is one of [inValues].
 *
 * The two spellings share this shape. `equals` is the one-value case and `inValues` the many-value
 * one, and a reader that treated them as different rules would be two chances to disagree.
 */
data class FormCondition(
    val fieldKey: String,
    val inValues: List<String>
)

/**
 * A visible, answered subset of the form, as a submission carries it.
 *
 * [type] is the field's declared type at the moment of the answer, kept in the record so a body
 * that later renames or retypes a field cannot reinterpret what somebody wrote. Values are the
 * option id for a choice and the text itself for a text field.
 */
data class FieldAnswer(
    val fieldKey: String,
    val type: String,
    val value: String
)

private const val MAX_FORM_FIELDS = 20
private const val MAX_FORM_LABEL = 200

/** The text a `text` field may hold; the protocol's option-label limit is the nearest bound. */
const val MAX_FORM_TEXT = 1_000

/**
 * Parse a stored `form`/`1` body, or null when this Viewer cannot read it.
 *
 * Only the `structured` contract is meaningful: a body asking for a single choice is a Prompt this
 * Viewer cannot answer, and returning null is the fail-closed answer every Viewer gives.
 */
fun parseFormPrompt(
    payload: String,
    response: ResponseContract = ResponseContract.STRUCTURED
): FormPrompt? {
    if (response != ResponseContract.STRUCTURED) return null
    val root = runCatching { Json.parse(payload) }.getOrNull()?.asObjOrNull() ?: return null
    if (root.keys.any { it !in setOf("title", "fields") }) return null

    val title = root["title"]?.asStringOrNull()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_FORM_LABEL }
        ?: return null

    val fieldValues = root["fields"]?.asArrOrNull()?.items ?: return null
    if (fieldValues.isEmpty() || fieldValues.size > MAX_FORM_FIELDS) return null

    val seen = mutableSetOf<String>()
    val fields = mutableListOf<FormField>()
    for (value in fieldValues) {
        val field = value.asObjOrNull() ?: return null
        if (field.keys.any { it !in setOf("key", "type", "label", "options", "required", "when") }) {
            return null
        }
        val key = field["key"]?.asStringOrNull()
            ?.takeIf { PackageProtocol.isKey(it) && seen.add(it) }
            ?: return null
        val type = FormFieldType.fromWire(field["type"]?.asStringOrNull()) ?: return null
        val label = field["label"]?.asStringOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_FORM_LABEL }
            ?: return null
        val required = field["required"]?.let { it.asBooleanOrNull() ?: return null } ?: true

        val options = when (type) {
            FormFieldType.ChoiceSingle -> parsePromptOptions(field["options"]) ?: return null
            FormFieldType.Text -> {
                if (field.has("options")) return null
                emptyList()
            }
        }

        val condition = field["when"]?.let { conditionValue ->
            parseFormCondition(conditionValue, earlier = seen.toList().dropLast(1)) ?: return null
        }

        fields += FormField(
            key = key,
            type = type,
            label = label,
            required = required,
            options = options,
            condition = condition
        )
    }

    return FormPrompt(stem = title, fields = fields)
}

private fun parseFormCondition(value: JsonValue, earlier: List<String>): FormCondition? {
    val obj = value.asObjOrNull() ?: return null
    if (obj.keys.any { it !in setOf("field", "equals", "in") }) return null
    val fieldKey = obj["field"]?.asStringOrNull() ?: return null
    if (fieldKey !in earlier) return null

    val equals = obj["equals"]?.asStringOrNull()
    val inValues = obj["in"]?.asArrOrNull()?.items?.map { item ->
        item.asStringOrNull() ?: return null
    }
    // Exactly one spelling. Both present would be two conditions the reader has to choose between.
    if ((equals == null) == (inValues == null)) return null
    val values = if (equals != null) listOf(equals) else inValues ?: return null
    if (values.isEmpty() || values.size > MAX_CONDITION_VALUES) return null
    return FormCondition(fieldKey = fieldKey, inValues = values)
}

/** Whether this field is shown against a set of answers so far. */
fun FormField.isVisible(answers: Map<String, String>): Boolean {
    val gate = condition ?: return true
    val answer = answers[gate.fieldKey] ?: return false
    return answer in gate.inValues
}

/** The fields a person can see and answer, in the body's order. */
fun FormPrompt.visibleFields(answers: Map<String, String>): List<FormField> =
    fields.filter { it.isVisible(answers) }

/**
 * The answers a submission carries: visible fields only, and only those actually answered.
 *
 * A hidden field is not sent even when an earlier answer left a value in the draft — changing the
 * branch must not record an answer to a question the person can no longer see. A visible required
 * field with no answer is reported by [missingRequired], not silently dropped.
 */
fun FormPrompt.answeredFields(answers: Map<String, String>): List<FieldAnswer> =
    visibleFields(answers).mapNotNull { field ->
        val value = answers[field.key].orEmpty()
        if (value.isBlank()) null else FieldAnswer(field.key, field.type.wire, value)
    }

/** Whether every visible required field carries an answer. */
fun FormPrompt.isComplete(answers: Map<String, String>): Boolean =
    visibleFields(answers).filter { it.required }.all { field ->
        !answers[field.key].orEmpty().isBlank()
    }
