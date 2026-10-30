package com.example.listea.domain

/**
 * A `media`/`1` Prompt body: a stem and the files the Prompt brings with it.
 *
 * A media Prompt is the part that makes a conditional chain able to end in a PDF, an image or a
 * video. The body says only what the part's Content page reads; the files are declared on the
 * manifest entry and become the part's Asset pages, reviewed by the existing media surfaces with
 * their marks and actions. There is no option list and no typed answer: pressing the part
 * judgment's Done control commits the part with an answered-empty result.
 *
 * Deliberately minimal, and fail closed like every other Viewer. The answer sheet does not need a
 * schema richer than a stem, and a Viewer that accepted keys it does not draw would be storing
 * content nobody wrote.
 */
data class MediaPrompt(
    override val stem: String
) : PromptContent {

    /** A media Prompt is freeform; its options belong to the files, not to a choice list. */
    override val options: List<PromptOption> get() = emptyList()
}

/**
 * Parse a stored `media`/`1` body, or null when this Viewer cannot read it.
 *
 * Only the `freeform` contract is meaningful: a media body answered by a choice or a structured
 * record is a Prompt this Viewer cannot answer. The stem is required and bounded by the protocol's
 * title limit; no other key is accepted.
 */
fun parseMediaPrompt(
    payload: String,
    response: ResponseContract = ResponseContract.FREEFORM
): MediaPrompt? {
    if (response != ResponseContract.FREEFORM) return null
    val root = runCatching { Json.parse(payload) }.getOrNull()?.asObjOrNull() ?: return null
    if (root.keys.any { it != "stem" }) return null

    val stem = root["stem"]?.asStringOrNull()
        ?.takeIf { it.isNotEmpty() && it.length <= PackageProtocol.MAX_TITLE }
        ?: return null

    return MediaPrompt(stem = stem)
}
