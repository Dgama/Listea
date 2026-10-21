package com.example.listea.domain

/**
 * How a passage mark is read: a comment carries the reviewer's own words, a tag carries the
 * template's.
 *
 * The key is what storage holds, spelled out for the reason [ButtonType] spells its own: an ordinal
 * is a position in this file, and a member inserted above another would silently re-read every
 * stored row as its neighbour.
 *
 * [BOOLEAN] has no member here on purpose. A boolean is a presence on a file, not a statement about
 * a passage, so it is never a PDF tool and can never author or govern a mark.
 */
enum class MarkKind(val key: String) {
    COMMENT("comment"),
    TAG("tag");

    /** The button type a mark of this kind is created and governed by. */
    fun buttonType(): ButtonType = when (this) {
        COMMENT -> ButtonType.COMMENT
        TAG -> ButtonType.TAG
    }

    companion object {
        /** The kind a stored [key] names, or null for one this build has never written. */
        fun fromKey(key: String?): MarkKind? = entries.firstOrNull { it.key == key }
    }
}

/** The reverse of [MarkKind.buttonType], or null for a type that cannot carry a passage mark. */
fun ButtonType.markKind(): MarkKind? = when (this) {
    ButtonType.COMMENT -> MarkKind.COMMENT
    ButtonType.TAG -> MarkKind.TAG
    ButtonType.BOOLEAN -> null
}

/**
 * The anchor format this build writes.
 *
 * Separate from the app's schema version, and for the same reason a payload has one: the anchor is
 * stored on a customer's device and read back by a later build, so a change to what its fields mean
 * has to be a number a reader can branch on rather than a silent reinterpretation. The first version
 * describes one page's quote; a later round that supports a selection spanning pages extends this
 * number and adds the fields to read.
 */
const val ANCHOR_FORMAT_VERSION = 1

/**
 * The parsers whose character indices [QuoteAnchor.charStart] and [QuoteAnchor.charEnd] describe.
 *
 * **The indices are a hint and this constant is why.** PDFium's index is an artefact of one parser
 * reading one byte stream: the same characters in a regenerated file have different numbers, and a
 * different PDF library starts counting differently altogether. An anchor is stored with the name of
 * the parser that produced its hint, and a reader that does not recognise the name does not use the
 * hint — it searches the text, which is the anchor's authority.
 */
const val PDF_CHAR_PARSER = "nucleus-pdfium/158.0.8111.0"

/**
 * Where one mark's passage was, in a form that survives the document changing underneath it.
 *
 * **The text is authoritative; every number is a hint.** [exactText] with [prefix] and [suffix] is
 * what a reader matches against a freshly extracted page; [pageIndex], [charStart] and [charEnd] only
 * let a reader that is confident in its parser skip the search when [documentFingerprint] proves the
 * bytes have not moved. A mark whose quote cannot be found uniquely is shown as detached rather than
 * drawn over whatever now sits at the old coordinates.
 *
 * [pageIndex] counts from zero, the renderer's own numbering. [charStart] and [charEnd] are inclusive
 * renderer indices, meaningful only when [charParser] is a parser this build knows.
 */
data class QuoteAnchor(
    val version: Int,
    val documentFingerprint: String,
    val pageIndex: Int,
    val exactText: String,
    val prefix: String,
    val suffix: String,
    val charStart: Int,
    val charEnd: Int,
    val charParser: String
)

/**
 * One passage mark on one PDF Asset.
 *
 * **It belongs to the Asset and never to a template.** [authoredTemplateId] and
 * [authoredTemplateVersion] record which template and version offered the tool when the mark was
 * made — history, not ownership. Visibility is decided at read time from the template that governs
 * the Asset *now*: remove the tool from it and the mark disappears from the screen and from the
 * payload, without a row being deleted, and restoring the same tool id brings it back. That is why
 * there is no foreign key to a template and why the authored reference is never consulted for
 * visibility.
 *
 * [value] is the reviewer's text for a [MarkKind.COMMENT] and the tool's record value for a
 * [MarkKind.TAG] at the moment of creation. A tag's *exported* value follows the tool as it is now,
 * for the same reason a chip's label does: the template is the vocabulary, and a mark is a choice
 * made in it.
 */
data class AssetMark(
    val id: String,
    val assetId: String,
    val toolId: String,
    val authoredTemplateId: String,
    val authoredTemplateVersion: Int,
    val kind: MarkKind,
    val value: String,
    val anchor: QuoteAnchor,
    val createdAt: Long,
    val updatedAt: Long
)
