package com.example.listea.domain

/**
 * How decisions leave the app, and who said so.
 *
 * Until v0.5.6 there was one way out and it was named after its mechanism: the webhook. Every
 * surface said *webhook* — a List's switch, a payload filter, a settings page, a history table —
 * and every one of those names was answering a question about *when* or *what* with a word about
 * *how*. This file is the correction. An **export** is the act; an [ExportKind] is how it happens;
 * and what a Todo and a List each name is **one** [ExportChoice], never a set.
 *
 * The plural case is real and it is not here. A List may hold Tasks from several Todos with several
 * different choices, and a round over such a List exports more than once — but that is a **sum over
 * the Todos a round touched**, computed when the round ends, and not a list stored on any row. That
 * distinction is why this release is small: nothing has to be kept in sync, because nothing is held.
 *
 * ## Why the choice is a word and not an empty string
 *
 * [ExportChoice] encodes as a literal key — `none`, `inherit`, `webhook`, `txt` — rather than as a
 * nullable or empty column. A List has a state a Todo does not: **having said nothing**, which is
 * what lets it defer to its Todos one group at a time. So three states have to be tellable apart —
 * *said nothing*, *said nothing exports*, *said this kind* — and `""` is already spoken for by
 * *nothing was ever chosen* in `sources.assetControls` (`domain/Templates.kt`). Three states need
 * three words, and a reader should not have to know which column it is holding to tell them apart.
 *
 * ## Why an unreadable key exports nothing
 *
 * [decodeExportChoice] answers [ExportChoice.Nothing] for a key this build cannot read, and that is
 * the same rule [Templates] applies to an unrecognised template id and `ReviewResponse` to an
 * unrecognised action. The alternative — guessing, or falling back to the default — would let a
 * choice nobody could evaluate **widen** what the app does on the user's behalf, and here it would
 * widen it all the way to the network. A rule this build cannot read is not a rule it may ignore.
 */

/**
 * One way of getting decisions out of the app.
 *
 * Closed on purpose, and it cannot be a table: a kind is **code** — a renderer plus a destination,
 * and neither is data. The list of kinds is the list of exporters this build ships, so adding one
 * costs a class and a `when` branch, and there is no state in which the database names a kind the
 * app has never heard of except a database written by a *different* build — which is the case
 * [decodeExportChoice] fails closed on rather than the case it serves.
 *
 * [key] is what is stored on `sources.exportKind`, `lists.exportKind` and `webhook_history.kind`;
 * [label] is what a user reads, named once here so the import dialog, the List card's dropdown and
 * the Settings list cannot call the same kind three things.
 */
enum class ExportKind(val key: String, val label: String) {
    /** An HTTP POST of the round's payload. The mechanism the app had before it had a choice. */
    WEBHOOK("webhook", "Webhook"),

    /**
     * A text file per completed round, under the album the media save already writes to.
     *
     * The second kind exists to prove the first one was a kind and not the model. It is deliberately
     * the least ambitious thing an export could be: no configuration, no network, no failure mode
     * beyond *the file did not get written*.
     */
    TXT("txt", "Txt");

    companion object {
        /**
         * The kind this key names, or `null` for a key this build cannot read.
         *
         * Nullable rather than defaulted, in the shape [ImporterKind.fromKey] already uses: the two
         * callers want different things from an unreadable key — a *row* becomes
         * [ExportChoice.Nothing], and a *user-typed* value has no meaning at all — and only the
         * caller knows which it is holding.
         */
        fun fromKey(key: String?): ExportKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * *Absent* — the only stored word that is not a kind.
 *
 * A Todo never holds it; a List holds it until the user chooses, and holding it is what makes the
 * List defer to the Todos its Tasks came from.
 */
const val EXPORT_CHOICE_INHERIT = "inherit"

/**
 * *Nothing* — a deliberate answer, and the one [ExportChoice.Nothing] writes.
 *
 * Distinct from [EXPORT_CHOICE_INHERIT] even where both end in silence. A Todo that is `none` and a
 * Todo whose List is `inherit` produce the same empty result for one round and mean opposite things:
 * the first has decided, the second has not.
 */
const val EXPORT_CHOICE_NONE = "none"

/**
 * What a Todo or a List does about exporting. **One value, never a set.**
 *
 * The user's own statement of the rule, which this type exists to make unrepresentable any other
 * way: *"todo创建（import的时候）和list里的export方式都是单选。都是和todo或者list绑定的属性。"*
 */
sealed interface ExportChoice {
    /**
     * Exports nothing.
     *
     * The default for a **new** Todo — a choice the user has not made should not send anything
     * anywhere — the answer for a key this build cannot read, and a List's way of overriding its
     * Todos into silence.
     */
    data object Nothing : ExportChoice

    /**
     * Follows the source Todos, one group at a time. **Lists only.**
     *
     * A Todo has nothing above it to follow, so [decodeExportChoice] answers [Nothing] for this key
     * on a Todo rather than inventing a fallback. The asymmetry is the point: a List is the only
     * thing in the model that can be *between* a round and the Todos that produced it.
     */
    data object Inherit : ExportChoice

    /** Exports by one named mechanism. */
    data class By(val kind: ExportKind) : ExportChoice
}

/** The stored word for [choice]. Total: every [ExportChoice] has one, and none of them is empty. */
fun encodeExportChoice(choice: ExportChoice): String = when (choice) {
    ExportChoice.Nothing -> EXPORT_CHOICE_NONE
    ExportChoice.Inherit -> EXPORT_CHOICE_INHERIT
    is ExportChoice.By -> choice.kind.key
}

/**
 * The choice a stored word names.
 *
 * [inheritable] says which column is being read, and it is the whole reason this is one function
 * and not two: `sources.exportKind` and `lists.exportKind` hold the same vocabulary, and the only
 * word whose meaning depends on the column is [EXPORT_CHOICE_INHERIT]. On a List it is the choice
 * the column exists for; on a Todo it decodes to [ExportChoice.Nothing], because a Todo has nothing
 * to inherit from and the answer that fails closed is the one that exports nothing.
 *
 * An unrecognised word is [ExportChoice.Nothing] for the same reason — see this file's header.
 */
fun decodeExportChoice(encoded: String?, inheritable: Boolean): ExportChoice {
    if (encoded == EXPORT_CHOICE_INHERIT) return if (inheritable) ExportChoice.Inherit else ExportChoice.Nothing
    if (encoded == EXPORT_CHOICE_NONE) return ExportChoice.Nothing
    return ExportKind.fromKey(encoded)?.let { ExportChoice.By(it) } ?: ExportChoice.Nothing
}

/** What a List's dropdown shows, in the order it shows it. A Todo's chooser names its own words. */
val LIST_EXPORT_CHOICES: List<ExportChoice> = listOf(
    ExportChoice.Inherit,
    ExportChoice.Nothing
) + ExportKind.entries.map { ExportChoice.By(it) }

/** The words beside [LIST_EXPORT_CHOICES], in the same order. */
fun listExportChoiceLabel(choice: ExportChoice): String = when (choice) {
    ExportChoice.Inherit -> "Same as the Todos"
    ExportChoice.Nothing -> "Export nothing"
    is ExportChoice.By -> choice.kind.label
}
