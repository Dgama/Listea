package com.example.listea.domain

/**
 * What a [TemplateButton] records, which decides how its mark is read.
 *
 * Two ship and they store identically, which is deliberate rather than a duplicate: a tag *is* a
 * boolean whose meaning is decided downstream. What separates them is not on/off, it is the shape of
 * the types that come next — an `option` holds one of several, a `field` or a `note` holds text, and
 * both want a *value* where these two want a presence. Naming the distinction now is what lets those
 * arrive as a member here plus a column of their own, rather than as a rewrite of what a mark is.
 *
 * The key is what goes to storage, spelled out rather than taken from the ordinal, for the reason
 * [AssetKind] spells its own: an ordinal is a position in this file, and inserting a member above
 * another would silently re-read every stored value as its neighbour.
 */
enum class ButtonType(val key: String) {

    /** On or off and nothing else: `★`. */
    BOOLEAN("boolean"),

    /** On or off, with a meaning the export decides: a tag a person configured. */
    TAG("tag");

    companion object {
        /** The type a stored [key] names, or null for one this build has never written. */
        fun fromKey(key: String?): ButtonType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One control on one template version's bar.
 *
 * [id] is opaque, minted rather than typed, and **unique across the whole app** — not merely within
 * one template. The record an Asset carries is a set of ids (`encodeActionIds`, and the columns and
 * payloads built on it), so two templates that both defined `cust1` would make one template's marks
 * appear under the other's chip the moment a file matched both, with nothing in the stored set able
 * to tell them apart afterwards. Scoping the record by template instead would rewrite the form of
 * every mark already stored and every payload that carries one; refusing the collision where ids are
 * minted costs nothing at all.
 *
 * [name] is what the chip says. [recordValue] is what a payload sends. Neither is identity — [id] is
 * — which is what lets a button be renamed without invalidating what every Task that used it was
 * judged with.
 */
data class TemplateButton(
    val id: String,
    val name: String,
    val type: ButtonType,
    val recordValue: String
)

/**
 * The version of the encoding below, stored beside every payload that uses it.
 *
 * The move `dissolved_task_state` makes with its `recordVersion` column and `review_events` makes
 * with `templateId`: a payload is never read without knowing what wrote it. This one earns the
 * column rather than an argument that it will never change — [ButtonType] exists precisely so that
 * a type holding a *value* can arrive, and a type holding a value needs a field this format does
 * not have.
 */
const val TEMPLATE_BUTTONS_VERSION = 1

/**
 * A template version's buttons, as one string: one line per button, four tab-separated fields.
 *
 * ```text
 * custom1	C1	tag	cust1
 * favorite	★	boolean	favorite
 * ```
 *
 * **Order is the bar's order**, so nothing is sorted here — unlike the asset-control configuration
 * this replaces, which sorts because a map has no order of its own to preserve. A control row whose
 * chips shuffled between two reads would be a row that looks like it had changed.
 *
 * The name and the record value are written as they are, unsanitised: keeping typed text to one line
 * is the editor's job at the moment of typing (`sanitizeActionText`), which is where it already
 * happens for every other record in this codebase. An encoder that also stripped would be a second
 * place for the same rule to live.
 */
fun encodeTemplateButtons(buttons: List<TemplateButton>): String =
    buttons.joinToString("\n") {
        "${it.id}\t${it.name}\t${it.type.key}\t${it.recordValue}"
    }

/**
 * The buttons a payload holds, or `null` when this build cannot read it.
 *
 * A version it does not know is `null` and never a guess — the answer `dissolved_task_state`'s
 * decoder gives, for the same reason. Reading an unknown version as version 1 would put whatever a
 * later release meant by a field onto today's meaning of it, silently, and a template whose bar is
 * misread is a template whose marks mean something nobody chose.
 *
 * Below that the tolerance is this codebase's usual one: a line this build cannot read costs its own
 * line and never the whole payload. A button with no id, an id carrying a comma — the separator the
 * *record* stores ids with — or a type this build has never heard of is dropped, and the buttons
 * around it survive. `''` is an empty bar and not `null`: a template that offers nothing is a fact,
 * and the two must not be conflated.
 */
fun decodeTemplateButtons(text: String, version: Int): List<TemplateButton>? {
    if (version != TEMPLATE_BUTTONS_VERSION) return null
    if (text.isEmpty()) return emptyList()

    return text.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = line.split('\t')
        val id = fields.getOrNull(0)?.trim().orEmpty()
        if (id.isEmpty() || id.contains(',')) return@mapNotNull null
        val type = ButtonType.fromKey(fields.getOrNull(2)?.trim()) ?: return@mapNotNull null
        TemplateButton(
            id = id,
            name = fields.getOrNull(1).orEmpty(),
            type = type,
            recordValue = fields.getOrNull(3).orEmpty()
        )
    }
}
