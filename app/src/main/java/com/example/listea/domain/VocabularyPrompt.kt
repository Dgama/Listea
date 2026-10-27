package com.example.listea.domain

/**
 * A `vocabulary`/`1` Prompt body, read by the vocabulary Viewer.
 *
 * This is a drill card, not a new Task kind: [stem] and [options] are the same properties every
 * structured card has, and [word] is the one field the vocabulary renderer needs beyond them. The
 * pronunciation is a Package Resource key, not an Asset, so it does not become a page or a media
 * item of its own; [answerOptionId] is optional content that lets a read-only card reveal the
 * meaning without inventing a second event.
 *
 * The reader is as strict as the profile reader. Unknown fields are refused, every string is
 * bounded, the audio key must be protocol-shaped, and a declared answer must name a declared
 * option. A body it cannot fully understand is a Prompt this build cannot draw, never a guessed
 * card.
 *
 * **Order is the contract.** [options] keeps the array order exactly as the body declared it; no
 * layer above this one sorts or shuffles it. A later "shuffle" feature must be a declared body or
 * template option, never a silent default.
 */
data class VocabularyPrompt(
    override val stem: String,
    val word: String,
    val phonetic: String?,
    val definition: String?,
    val example: String?,
    val audioResource: String?,
    val answerOptionId: String?,
    override val options: List<PromptOption>
) : PromptContent

private const val MAX_WORD = 200
private const val MAX_PHONETIC = 200
private const val MAX_DEFINITION = 1_000
private const val MAX_EXAMPLE = 1_000

private val VOCABULARY_FIELDS = setOf(
    "stem", "word", "phonetic", "definition", "example", "audio", "answer", "options"
)

/**
 * Parse a stored `vocabulary`/`1` body, or null when this Viewer cannot read it.
 *
 * [response] narrows the readable set exactly as the profile reader does: this Viewer declares
 * `choice.single`, and a body asked for by another contract would be a Prompt it cannot answer.
 */
fun parseVocabularyPrompt(
    payload: String,
    response: ResponseContract = ResponseContract.SINGLE
): VocabularyPrompt? {
    if (response != ResponseContract.SINGLE) return null
    val root = runCatching { Json.parse(payload) }.getOrNull()?.asObjOrNull() ?: return null
    if (root.keys.any { it !in VOCABULARY_FIELDS }) return null

    val stem = root["stem"]?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val word = root["word"]?.asStringOrNull()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_WORD }
        ?: return null

    // An optional line is absent when the field is absent or blank; a non-string value is a body
    // this reader does not understand and is refused rather than ignored.
    val phonetic = root["phonetic"]?.let { value ->
        value.asStringOrNull()?.takeIf { it.length <= MAX_PHONETIC } ?: return null
    }?.takeIf { it.isNotEmpty() }
    val definition = root["definition"]?.let { value ->
        value.asStringOrNull()?.takeIf { it.length <= MAX_DEFINITION } ?: return null
    }?.takeIf { it.isNotEmpty() }
    val example = root["example"]?.let { value ->
        value.asStringOrNull()?.takeIf { it.length <= MAX_EXAMPLE } ?: return null
    }?.takeIf { it.isNotEmpty() }

    val audio = root["audio"]?.let { value ->
        value.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
    }
    val answer = root["answer"]?.let { value ->
        value.asStringOrNull()?.takeIf { PackageProtocol.isKey(it) } ?: return null
    }

    val options = parsePromptOptions(root["options"]) ?: return null
    if (answer != null && options.none { it.id == answer }) return null

    return VocabularyPrompt(
        stem = stem,
        word = word,
        phonetic = phonetic,
        definition = definition,
        example = example,
        audioResource = audio,
        answerOptionId = answer,
        options = options
    )
}
