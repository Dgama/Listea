package com.example.listea.domain

/**
 * What a [TemplateButton] records, which decides how its mark is read.
 *
 * [BOOLEAN] and [TAG] store identically and deliberately: a tag *is* a boolean whose meaning is
 * decided downstream. [COMMENT] is the first type to hold a value of its own — the reviewer's
 * text — and it is why the button payload has a version 2. The distinction is worth naming because
 * a mark's *shape* follows it: the first two are a presence on a file, a comment is a statement
 * attached to a passage.
 *
 * The key is what goes to storage, spelled out rather than taken from the ordinal, for the reason
 * [AssetKind] spells its own: an ordinal is a position in this file, and inserting a member above
 * another would silently re-read every stored value as its neighbour.
 */
enum class ButtonType(val key: String) {

    /** On or off and nothing else: `★`. */
    BOOLEAN("boolean"),

    /** On or off, with a meaning the export decides: a tag a person configured. */
    TAG("tag"),

    /**
     * Free text written by the reviewer and attached to the selection.
     *
     * The button itself carries no record value: what leaves is the text the reviewer typed, not a
     * word configured on the template. On a PDF it is a passage tool; a file-level chip row has no
     * use for it, which is why the review surface does not draw one for a PDF.
     */
    COMMENT("comment"),

    /**
     * A control that invokes a Task Review action rather than recording a mark.
     *
     * The first type that belongs to the *Task* rather than to a file: `Submit` is the review
     * template's commit control, drawn on the review surface and named by the definition's
     * `commitOn`. It records no value of its own — [TemplateButton.recordValue] is empty — and it
     * never enters an [com.example.listea.data.AssetDecisions] set.
     */
    ACTION("action");

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
 * The first version of the encoding below: four fields, `boolean` and `tag`.
 *
 * Still written for a list that contains no comment — a template edited in this build but holding
 * only tags stays readable by a build that has never heard of version 2. That is a compatibility
 * choice, not a claim that this is the newest version.
 */
const val TEMPLATE_BUTTONS_VERSION = 1

/**
 * The version that can name a [ButtonType.COMMENT].
 *
 * The row shape did not change — a comment has no record value, so the fourth field is empty — but
 * the *vocabulary* did, and a build that predates it must not offer a button it cannot honour. A
 * version line is how the payload says which vocabulary it uses.
 */
const val TEMPLATE_BUTTONS_COMMENT_VERSION = 2

/**
 * The version that can name a [ButtonType.ACTION].
 *
 * A build that predates this vocabulary must not draw a Submit control it has no commit path for.
 * The row shape is unchanged, as it was for a comment, so only the vocabulary moves.
 */
const val TEMPLATE_BUTTONS_ACTION_VERSION = 3

/**
 * Every version this build can decode and edit.
 *
 * `TemplateStore` refuses to append a version after an unreadable one, so this is also the set of
 * versions it is allowed to extend. Reading version 1 is not a courtesy: every template that has
 * ever been stored without a comment is still version 1, and an editor that could not open it would
 * strand it.
 */
val SUPPORTED_TEMPLATE_BUTTON_VERSIONS: IntRange =
    TEMPLATE_BUTTONS_VERSION..TEMPLATE_BUTTONS_ACTION_VERSION

/**
 * The encoding version [buttons] needs: 3 for an action, 2 for a comment, 1 otherwise.
 *
 * Chosen from the content rather than from a build-wide constant so the two promises hold at once:
 * a new build can write a comment, and appending a version to a comment-free template does not make
 * every earlier build treat the whole template as unreadable.
 */
fun buttonsVersionFor(buttons: List<TemplateButton>): Int = when {
    buttons.any { it.type == ButtonType.ACTION } -> TEMPLATE_BUTTONS_ACTION_VERSION
    buttons.any { it.type == ButtonType.COMMENT } -> TEMPLATE_BUTTONS_COMMENT_VERSION
    else -> TEMPLATE_BUTTONS_VERSION
}

/**
 * The vocabulary an editor must read its **draft** with, which is not always the one on the row.
 *
 * A draft is encoded by this build, so an edited draft can name a comment even when the stored
 * version is 1 — that is exactly what adding a Comment button does. Reading it back at the stored
 * version would drop the row the edit just added, because version 1 cannot read `comment`: the
 * button would disappear between the tap that made it a comment and the next keystroke, and an
 * Update from such a draft would write the template without it.
 *
 * An **untouched** draft is still the stored row, and is read at the stored version. A version-1
 * row that somehow carries a comment line therefore stays dropped, exactly as it is when the
 * template is first opened — the tolerance rule for a corrupt line is not relaxed just because the
 * page can now also write the newer vocabulary.
 */
fun templateDraftVersion(storedText: String, storedVersion: Int, draftText: String): Int =
    if (draftText == storedText) storedVersion else SUPPORTED_TEMPLATE_BUTTON_VERSIONS.last

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
 * A `comment` line inside version 1 is dropped as well, because version 1 could not have written
 * one: a row like that was hand-edited or corrupted, and offering free-text collection from a
 * payload that never promised it is the same guess the version gate exists to refuse. Version 2
 * reads comments, version 3 reads actions, and each gate refuses the vocabulary of a later
 * version.
 *
 * Below that the tolerance is this codebase's usual one: a line this build cannot read costs its own
 * line and never the whole payload. A button with no id, an id carrying a comma — the separator the
 * *record* stores ids with — or a type this build has never heard of is dropped, and the buttons
 * around it survive. `''` is an empty bar and not `null`: a template that offers nothing is a fact,
 * and the two must not be conflated.
 */
fun decodeTemplateButtons(text: String, version: Int): List<TemplateButton>? {
    if (version !in SUPPORTED_TEMPLATE_BUTTON_VERSIONS) return null
    if (text.isEmpty()) return emptyList()

    return text.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = line.split('\t')
        val id = fields.getOrNull(0)?.trim().orEmpty()
        if (id.isEmpty() || id.contains(',')) return@mapNotNull null
        val type = ButtonType.fromKey(fields.getOrNull(2)?.trim()) ?: return@mapNotNull null
        if (type == ButtonType.COMMENT && version < TEMPLATE_BUTTONS_COMMENT_VERSION) {
            return@mapNotNull null
        }
        if (type == ButtonType.ACTION && version < TEMPLATE_BUTTONS_ACTION_VERSION) {
            return@mapNotNull null
        }
        TemplateButton(
            id = id,
            name = fields.getOrNull(1).orEmpty(),
            type = type,
            recordValue = fields.getOrNull(3).orEmpty()
        )
    }
}
