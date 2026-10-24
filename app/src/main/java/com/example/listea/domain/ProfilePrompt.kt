package com.example.listea.domain

/**
 * A `profile`/`1` Prompt body, read by the only Viewer that declares that kind.
 *
 * Core never interprets a body — it stores canonical text and hands it to the Viewer named by the
 * envelope. This reader is that Viewer's half: it decides what schema 1 means, so a body that does
 * not fit is *unreadable by this Viewer* rather than a broken Task. The Task keeps its payload and
 * its history; the surface says it cannot draw it.
 *
 * Fail closed, exactly as the definition decoder does. Unknown fields are refused rather than
 * ignored, option ids must be unique and protocol-shaped, and every value is bounded by the limits
 * the protocol already publishes. A Viewer that guessed at a malformed body would be drawing a
 * Prompt nobody wrote.
 *
 * [fields] is ordered for a person: the three schema-1 names first, then anything else in code-point
 * order. The stored body is canonical and therefore key-sorted, which is right for hashing and wrong
 * for reading — `age`, `bio`, `name` would put the age above the name.
 */
data class ProfilePrompt(
    val stem: String,
    val fields: List<ProfileField>,
    val photoResource: String?,
    val options: List<PromptOption>
)

data class ProfileField(val label: String, val value: String)

private const val MAX_PROFILE_FIELDS = 20
private const val MAX_FIELD_VALUE = 1_000

/** The order schema 1's own fields read in; anything else follows alphabetically. */
private val PREFERRED_FIELDS = listOf("name", "age", "bio")

/**
 * Parse a stored `profile`/`1` body, or null when this Viewer cannot read it.
 *
 * [response] narrows the readable set: the Viewer declares `choice.single`, and a body whose option
 * list is asked for by a different contract would be a Prompt the Viewer cannot answer.
 */
fun parseProfilePrompt(
    payload: String,
    response: ResponseContract = ResponseContract.SINGLE
): ProfilePrompt? {
    if (response != ResponseContract.SINGLE) return null
    val root = runCatching { Json.parse(payload) }.getOrNull()?.asObjOrNull() ?: return null
    if (root.keys.any { it !in setOf("stem", "profile", "photo", "options") }) return null

    val stem = root["stem"]?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val profile = root["profile"]?.asObjOrNull() ?: return null
    if (profile.entries.isEmpty() || profile.entries.size > MAX_PROFILE_FIELDS) return null
    val fields = profile.entries.map { (key, value) ->
        if (!PackageProtocol.isKey(key)) return null
        val text = when (value) {
            is JsonValue.Str -> value.value
            is JsonValue.Num -> value.raw
            else -> return null
        }
        if (text.length > MAX_FIELD_VALUE) return null
        ProfileField(label = key.replaceFirstChar { it.uppercaseChar() }, value = text)
    }.sortedWith(
        compareBy<ProfileField> { field ->
            PREFERRED_FIELDS.indexOf(field.label.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
        }.thenBy { it.label }
    )

    val photo = root["photo"]?.let { value ->
        value.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
    }

    val optionValues = root["options"]?.asArrOrNull()?.items ?: return null
    if (optionValues.isEmpty() || optionValues.size > 100) return null
    val options = optionValues.map { value ->
        val option = value.asObjOrNull() ?: return null
        if (option.keys != setOf("id", "label")) return null
        val id = option["id"]?.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
        val label = option["label"]?.asStringOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: return null
        PromptOption(id, label)
    }
    if (options.map { it.id }.toSet().size != options.size) return null

    return ProfilePrompt(stem = stem, fields = fields, photoResource = photo, options = options)
}

/**
 * The Viewer capabilities this build declares, and the only compatibility table there is.
 *
 * A capability is a pure statement — kind, schema, response contracts, emitted intents — and every
 * compatibility question is asked against it: the catalog asks whether a stored envelope can be
 * drawn, the submission boundary asks whether the template accepts what the Viewer emits, and the
 * planner's `supportsKind` is this predicate. There is deliberately no 1:1 map from a Prompt's kind
 * to a template: a questionnaire Viewer may draw a single-choice Prompt, and a template may govern
 * more than one Viewer, so the table is a list to search rather than a lookup to match.
 *
 * [ref] is the identity an authored template names, not a renderer selector. The renderer is chosen
 * from the payload; the reference records what the template was written against.
 */
data class ViewerCapability(
    val ref: TemplateVersionRef,
    val kind: String,
    val schema: Int,
    val responses: Set<ResponseContract>,
    val intents: Set<ViewerEventType>
)

object Viewers {

    /** The dating/profile Viewer: one card, one photo, one choice. */
    val Profile = ViewerCapability(
        ref = TemplateVersionRef(TemplateId("builtin.viewer.profile"), 1),
        kind = "profile",
        schema = 1,
        responses = setOf(ResponseContract.SINGLE),
        intents = setOf(ViewerEventType.CHOICE_SELECT)
    )

    val all: List<ViewerCapability> = listOf(Profile)

    /** The Viewer that can draw this envelope, or null when none in this build can. */
    fun forPayload(kind: String?, schema: Int?): ViewerCapability? =
        if (kind == null || schema == null) null
        else all.firstOrNull { it.kind == kind && it.schema == schema }

    fun supports(kind: String, schema: Int): Boolean = forPayload(kind, schema) != null

    /**
     * The most specific Viewer for a payload and the response it asks for.
     *
     * One structured Viewer ships, so "most specific" is the only candidate today; the contract is
     * written now so a second Viewer is additive rather than a change to this function's callers.
     */
    fun forPrompt(kind: String, schema: Int, response: ResponseContract): ViewerCapability? =
        all.firstOrNull { it.kind == kind && it.schema == schema && response in it.responses }
}
