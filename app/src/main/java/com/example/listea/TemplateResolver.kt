package com.example.listea

import androidx.compose.runtime.Immutable
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope

/**
 * What a Todo or a Task is *attached to*: the template versions it names, in the order it names them.
 *
 * One line per attach, `templateId \t version`:
 *
 * ```text
 * builtin.media.asset-control	1
 * ```
 *
 * **A reference and never a copy**, which is the whole of what this release changed about the two
 * columns that hold it (`sources.assetControls`, `task_imports.assetControls`). They used to carry
 * the *answer* — a template id, a version, and the tags, per file kind, spelled out — so a Todo and
 * the template it named could disagree, and did: editing a button in one place left every Todo that
 * had chosen it still holding the old label. A reference cannot drift from what it points at.
 *
 * It is also what makes an attachment *re-resolvable*. A Task judged under `…asset-control@1` goes
 * on meaning version 1 after the template is edited to version 2, because the number is written down
 * with the id; and a reader that wants what version 1 said can still look it up.
 *
 * **Order is significant.** Several templates may be attached and their scopes may overlap, and the
 * first one that admits a file is the one whose chips it gets — see [TemplateResolver.controlFor].
 * The order is the user's, kept as they made it, rather than sorted, because sorting would decide
 * that question by alphabet.
 */
fun encodeTemplateAttachments(refs: List<TemplateVersionRef>): String =
    refs.joinToString("\n") { "${it.templateId.value}\t${it.version}" }

/**
 * The attachments a stored column holds, in the order it holds them.
 *
 * **Two shapes are on real devices, and both are read.** This release writes `id \t version`. The
 * per-kind configuration it replaces wrote `kind \t id \t version \t tags`, and a Todo imported
 * before the upgrade still has to resolve to the template it named — the tags it also carried are
 * dropped, because the template is where tags live now and the version it names has its own.
 *
 * The third field is what tells them apart, and that is a rule rather than a guess: the old shape
 * has one and the new shape does not. Reading them by position instead would take a kind key for a
 * template id on every pre-upgrade row.
 *
 * Tolerant in the way the rest of this codebase's decoders are: a line this build cannot read costs
 * its own line and never the column, and duplicates are collapsed because attaching the same
 * template twice is one attachment said twice.
 *
 * **One version per lineage, and the highest one wins.** A column holding both `T@1` and `T@2` is
 * not two attachments, it is one attachment written twice — a lineage is a thing a Todo has or does
 * not have, and [TemplateResolver.controlFor]'s first-match rule would otherwise hand the file the
 * *older* of the two, silently, which is how a button added to a template came to be missing from
 * the Todo that had just been re-imported to pick it up.
 *
 * The version on a stored reference is for reading back what a Task was judged with, not a thing to
 * hold two of — so this collapses the contradiction rather than trusting a writer never to have made
 * one. A row that is well-formed is untouched by the rule; a row that is not is repaired on the way
 * in, without a migration, and the next run writes it back in the shape the dialog now produces.
 */
fun decodeTemplateAttachments(text: String): List<TemplateVersionRef> {
    // Insertion-ordered, so the first mention of a lineage keeps its place in the list and only the
    // version it holds can move — the user's order is what decides a conflict, and re-sorting here
    // would decide it by something else.
    val held = LinkedHashMap<TemplateId, TemplateVersionRef>()

    text.split('\n').forEach { raw ->
        if (raw.isBlank()) return@forEach
        val fields = raw.split('\t')
        val (id, version) = when {
            fields.size >= 3 -> fields[1].trim() to fields[2].trim().toIntOrNull()
            fields.size == 2 -> fields[0].trim() to fields[1].trim().toIntOrNull()
            else -> return@forEach
        }
        if (id.isEmpty() || version == null || version < 1) return@forEach
        val lineage = TemplateId(id)
        val current = held[lineage]
        if (current == null || version > current.version) {
            held[lineage] = TemplateVersionRef(lineage, version)
        }
    }

    return held.values.toList()
}

/**
 * Which version of a template a Todo holds, or null when it holds none.
 *
 * **Asked by lineage, and that is the whole point of it being a function.** A caller that compared
 * whole references would answer *holds none* for a Todo sitting on version 1 while the picker offered
 * version 2 — and the two answers are not near-misses: the first is what made a second reference get
 * written and the new button become unreachable.
 */
fun heldVersionOf(attached: List<TemplateVersionRef>, lineage: TemplateId): Int? =
    attached.firstOrNull { it.templateId == lineage }?.version

/**
 * The attachment list with one template turned on or off.
 *
 * **Turning one on replaces whatever the list held of it.** A Todo has a template or it does not, and
 * the version beside it records how that template stood when a run happened — not a choice being made
 * here. So this is the only way a template that has been edited reaches a Todo that was on the older
 * version, and it is what makes the attach step's rows one per template rather than one per version.
 *
 * The lineage's place in the list is kept when it is replaced and taken when it is turned off; a new
 * one goes last. Order decides which template governs a file when two admit it, so it is the user's
 * and not this function's to sort — see [TemplateResolver.controlFor].
 *
 * Lives here rather than in the dialog that calls it, because it is one half of the rule the decoder
 * above enforces on the way in, and it was in the dialog — untested, and matching whole references —
 * on the day it wrote a second version of one template onto a single Todo.
 */
fun withAttachment(
    attached: List<TemplateVersionRef>,
    choice: StoredTemplateVersion,
    on: Boolean
): List<TemplateVersionRef> {
    val held = attached.indexOfFirst { it.templateId == choice.ref.templateId }
    if (held < 0) return if (on) attached + choice.ref else attached
    if (!on) return attached.filterIndexed { index, _ -> index != held }
    // Replaced where it stands rather than moved to the end, because the list's order decides which
    // template governs a file and bringing one up to date says nothing about that.
    return attached.toMutableList().also { it[held] = choice.ref }
}

/**
 * The attachments a run should write when it is carrying a Todo forward.
 *
 * **Every held reference moves to the offered version of the same lineage, and nothing else
 * changes.** Order is kept because it decides which template governs a file; a lineage the offered
 * list no longer knows keeps the reference it had, because a run that cannot name the current
 * version has no better answer than the one already recorded; and no lineage is ever added, because
 * this is a refresh of what a Todo holds rather than a re-choosing of it.
 *
 * This is the write half of Refresh Import's version promise. A Todo holding `T@1` opens the dialog
 * ticked — the row is the same lineage — and a user who touches nothing would otherwise confirm a
 * run that re-stamps every Task with the version it already had, leaving the template edit stuck at
 * the old version forever. See [refreshVersionNotice] for what the dialog says before that happens.
 */
fun upgradeAttachments(
    held: List<TemplateVersionRef>,
    offered: List<StoredTemplateVersion>
): List<TemplateVersionRef> {
    val current = offered.associateBy { it.ref.templateId }
    return held.map { ref -> current[ref.templateId]?.ref ?: ref }
}

/**
 * What a refresh that moves a held template must say before it is confirmed, or null when nothing
 * moves.
 *
 * The sentence is entry 12's, and it is a copy requirement rather than a delivery change: one run
 * re-stamps every Task it touches, so Tasks marked under the old version will be read under the new
 * one. A removed button's marks stay on the file and are neither shown nor sent — never rewritten —
 * and a new button starts unmarked. Renaming a button changes neither its id nor its wire value, so
 * it moves nothing material; the notice still names the version because the version is what is
 * changing.
 *
 * Computed from the refs the run will actually submit, not from the row: a refresh where the user
 * unticks the moved template has nothing to warn about, and saying it anyway would describe a write
 * that will not happen.
 */
fun refreshVersionNotice(
    held: List<TemplateVersionRef>,
    offered: List<StoredTemplateVersion>
): String? {
    val moved = held.mapNotNull { ref ->
        val current = offered.firstOrNull { it.ref.templateId == ref.templateId }
        current?.takeIf { it.ref.version != ref.version }
    }
    if (moved.isEmpty()) return null
    val names = moved.map { it.name }.distinct().joinToString(", ")
    return "Tasks already marked will be read under the new version of $names. " +
        "A button the new version removed keeps its marks on the file, but they are no longer " +
        "shown or sent; a button it added starts unmarked."
}

/**
 * The templates a Task or a Todo is attached to, as the buttons they offer.
 *
 * **The successor to `AppSettings.controlFor` and `AppSettings.controlForTask`, and the whole of
 * what this release moved.** Those two answered from DataStore: an app-wide tag list, keyed by kind,
 * with a fallback to a shipped default whenever a row said nothing. That fallback was the right
 * answer while there was one template and no way to name it — and it is the wrong one the moment
 * there are several, because *this file matched nothing* and *this file matched something nobody
 * picked* are different facts, and only the first is true.
 *
 * **Nothing here falls back.** An attachment that names a version this build has never heard of, a
 * version whose buttons it cannot read, or a scope that admits nothing all resolve to no chips at
 * all rather than to the built-in. That is [ScopeRule][com.example.listea.domain.ScopeRule]'s
 * failing-closed rule reaching the surface it was written for: a chip row nobody chose is worse than
 * a chip row that is empty, because an empty one is legible and the other is not.
 *
 * **Immutable, and built from rows rather than holding a database.** The lookup is a map, so a
 * resolver is a value a screen can be given, compare and recompose over — which is what keeps the
 * template tables out of every surface that draws a chip. [ListsViewModel] builds one whenever the
 * two tables change, which is the only time any of this can move.
 */
@Immutable
class TemplateResolver(
    private val versions: Map<TemplateVersionRef, StoredTemplateVersion> = emptyMap(),

    /**
     * Every lineage's **current** version, by name: what there is to attach.
     *
     * The one list of templates a choice is made from, and it is deliberately one entry per lineage
     * rather than one per version. Attaching is naming a template to be governed by — *use the media
     * control* — and a dialog that also asked *and which of its four versions* would be asking the
     * user to answer the question this release exists to answer for them. A Task's record keeps the
     * number, so that it stays meaningful after the template moves on; the user never picks it.
     *
     * Current means the highest version this build can read, which is not always the last row: a
     * version written by a later build is skipped rather than offered, the same failing-closed rule
     * [of] applies. A lineage whose every version is unreadable is absent, so it cannot be attached
     * and cannot draw an empty bar on somebody's files.
     *
     * Ordered by name, then id, so the picker reads alphabetically and does not reshuffle.
     */
    val attachable: List<StoredTemplateVersion> = emptyList()
) {

    /**
     * What governs one file: the buttons of the first attached template whose scope admits its kind.
     *
     * Null when nothing matches — see the class note, which is where the reason is. A caller that
     * has to draw something draws [ResolvedAssetControl.Empty] and gets a control row with no chips
     * on it, which is what a file no template governs should look like.
     *
     * **First match wins, and that is a rule rather than an accident.** Two templates may both admit
     * an image, and this release does not offer to resolve that — the import dialog refuses to
     * attach two whose scopes overlap, and what is left is the order the user attached them in. The
     * question a conflict raises is a real one and it is deferred, not answered here; a rule that
     * picked by, say, scope size would be an answer nobody asked for and could not see.
     */
    fun controlFor(attachments: String, kind: AssetKind): ResolvedAssetControl? =
        governingFor(attachments, kind)?.let { ResolvedAssetControl(it.buttons) }

    /**
     * The first attached template version whose scope admits [kind], or null when none does.
     *
     * The same first-match rule [controlFor] draws its buttons from, exposed as the version itself
     * for the two facts that are not a button list: which template a new PDF mark was authored
     * under, and which version's tool a stored mark must still find. Exposing it here rather than
     * repeating the walk keeps visibility and creation from disagreeing about *which* template
     * governs a file.
     */
    fun governingFor(attachments: String, kind: AssetKind): StoredTemplateVersion? =
        decodeTemplateAttachments(attachments)
            .firstNotNullOfOrNull { ref -> stored(ref)?.takeIf { it.scope.admits(kind) } }

    /**
     * What governs a whole Task: every attached template's buttons, together.
     *
     * For the two surfaces that speak about the Task rather than about the file in front of the
     * user — the badge row under the filename, and the webhook payload, which carries one entry per
     * Task. A button set on a video has to be nameable while a photograph is on screen, or the badge
     * would report an unknown id for a button the user can see.
     *
     * Unlike [controlFor] this is never null: a Task with nothing attached has nothing to offer, and
     * "nothing" is an empty button list rather than an absent answer. Ordered by attachment, then by
     * each version's own button order, first occurrence winning — stable, because it decides the
     * order of a webhook's `actions` array, and an array that reshuffles itself is one a receiver
     * cannot diff.
     */
    fun controlForTask(attachments: String): ResolvedAssetControl {
        val seen = mutableSetOf<String>()
        val buttons = decodeTemplateAttachments(attachments).flatMap { ref ->
            stored(ref)?.buttons.orEmpty()
        }.filter { seen.add(it.id) }

        return ResolvedAssetControl(buttons)
    }

    /** One stored version, or null for a reference this build's tables do not hold. */
    private fun stored(ref: TemplateVersionRef): StoredTemplateVersion? = versions[ref]

    /**
     * The same lookup, for the callers that have to *name* a template rather than draw its chips.
     *
     * A Todo's card says which templates its Tasks were judged with, and that is a question about a
     * reference — so the card asks for the row the reference names and says what it is called. Null
     * for a reference this build cannot read, which the card reports as such rather than printing an
     * id at a user.
     *
     * Public where [stored] is not, because the difference between the two is who is asking: this
     * class's own resolution, or a surface saying what a Task is attached to.
     */
    fun versionOf(ref: TemplateVersionRef): StoredTemplateVersion? = stored(ref)

    companion object {
        /** No templates at all, for a surface that has to be given something before the rows land. */
        val Empty = TemplateResolver()

        /**
         * The resolver for the two tables as they stand.
         *
         * A version whose payload this build cannot read is **left out rather than guessed at**,
         * which is [decodeTemplateButtons]' own answer reaching the lookup: an id it cannot decode
         * is not an id it can match, and a template silently offering part of its bar would be worse
         * than one offering none of it. The version stays in the table and on its own page, where a
         * reader is told what is wrong with it.
         *
         * A version with no lineage is skipped for the same kind of reason: the name is the
         * lineage's and there is no template to list, which the foreign key makes unreachable rather
         * than merely unlikely.
         */
        fun of(
            templates: List<TemplateEntity>,
            versions: List<TemplateVersionEntity>
        ): TemplateResolver {
            val names = templates.associate { it.id to it.name }
            val readable = versions.mapNotNull { row ->
                val name = names[row.templateId] ?: return@mapNotNull null
                val buttons = decodeTemplateButtons(row.buttons, row.buttonsVersion)
                    ?: return@mapNotNull null
                StoredTemplateVersion(
                    ref = TemplateVersionRef(TemplateId(row.templateId), row.version),
                    name = name,
                    scope = decodeTemplateScope(row.scope),
                    buttons = buttons
                )
            }

            return TemplateResolver(
                versions = readable.associateBy { it.ref },
                // Highest version per lineage. `maxByOrNull` on the version number and not
                // `lastOrNull` on the list, because the caller's order is the query's and a
                // `lastOrNull` would quietly depend on it; the answer here is a number and should be
                // read off the numbers.
                attachable = readable
                    .groupBy { it.ref.templateId }
                    .values
                    .mapNotNull { lineage -> lineage.maxByOrNull { it.ref.version } }
                    .sortedWith(compareBy({ it.name }, { it.ref.templateId.value }))
            )
        }
    }
}

/**
 * Whether two scopes can both govern one file.
 *
 * The question the import dialog asks before it offers a second template, and it is asked of scopes
 * rather than of templates because that is what the answer turns on. Two templates that both admit
 * images are two answers to *which buttons does this photograph offer*, and while
 * [TemplateResolver.controlFor] has a rule for that — first attached wins — a rule is what a model
 * needs and not what a user should be offered.
 *
 * Computed by trying every kind rather than by comparing rules, and deliberately: a scope's rules
 * are a conjunction whose members may have any meaning, and there is no general way to intersect
 * two of them. Enumerating the kinds is exact for a finite set, and the set is three.
 *
 * A scope that admits nothing overlaps nothing. That falls out of the definition rather than being
 * special-cased: a template that governs no file cannot be the second answer to anything.
 */
fun scopesOverlap(a: TemplateScope, b: TemplateScope): Boolean =
    AssetKind.entries.any { a.admits(it) && b.admits(it) }

/**
 * The buttons of one attached version, as the ids an item stores, in the version's own order.
 *
 * Here rather than on the resolver because it is a statement about a *row* rather than about a
 * lookup — the import dialog asks it of a version it is about to attach, before any Task exists to
 * resolve against.
 */
fun StoredTemplateVersion.selectionIds(): Set<String> = buttons.mapTo(mutableSetOf()) { it.id }

/**
 * The ids in a selection set that no button of this control defines.
 *
 * Not an error and not cleaned up: an id survives the button it named, so a template edited while a
 * Task still carries an old mark yields exactly this. Naming them is what lets a surface say *this
 * file carries something nothing offers any more* rather than showing an id as if it were a label.
 */
fun ResolvedAssetControl.unmatched(selections: Set<String>): Set<String> =
    selections - buttons.mapTo(mutableSetOf()) { it.id }
