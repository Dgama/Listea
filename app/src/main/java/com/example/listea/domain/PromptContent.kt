package com.example.listea.domain

/**
 * What a Viewer's body parser returns: drawable content, not the stored envelope.
 *
 * Every structured Prompt has a stem and an ordered option list, whatever else its card adds. A
 * surface therefore never needs to know which Viewer drew a Task — it asks [decodePrompt] for this,
 * and the card chooses its own layout. [options] is in the order the body declared them; nothing in
 * the reader, the store or the card sorts or shuffles a Prompt's choices.
 */
sealed interface PromptContent {
    val stem: String
    val options: List<PromptOption>
}

/**
 * The one call a surface makes to turn a stored envelope into drawable content.
 *
 * The compatibility decision stays in [Viewers]: a kind/schema this build does not declare is
 * unreadable even when its body happens to look familiar. Null is the honest answer, and the
 * surfaces draw their notice page for it.
 */
fun decodePrompt(kind: String?, schema: Int?, payload: String?): PromptContent? {
    if (kind == null || schema == null || payload == null) return null
    return Viewers.forPayload(kind, schema)?.parse(payload)
}

/**
 * The option grammar every structured Viewer shares, read once.
 *
 * The protocol planner enforces the same shape at import; this is the Viewer's read of what was
 * stored, so a body that was written by another build is still checked before it is drawn. The
 * order of the array is preserved exactly — it is the card's contract, not an implementation
 * detail.
 */
internal fun parsePromptOptions(value: JsonValue?): List<PromptOption>? {
    val values = value?.asArrOrNull()?.items ?: return null
    if (values.isEmpty() || values.size > PackageProtocol.MAX_OPTIONS) return null
    val options = values.map { optionValue ->
        val option = optionValue.asObjOrNull() ?: return null
        if (option.keys != setOf("id", "label")) return null
        val id = option["id"]?.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
        val label = option["label"]?.asStringOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= PackageProtocol.MAX_OPTION_LABEL }
            ?: return null
        PromptOption(id, label)
    }
    if (options.map { it.id }.toSet().size != options.size) return null
    return options
}
