package com.example.listea

import androidx.compose.runtime.Immutable
import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import com.example.listea.domain.decodeAddOnQuestions

/**
 * What a Todo or a Task is attached to, as one line: `addOnId \t version`, or `""` for none.
 *
 * **At most one reference**, which is why this is a single value rather than the list
 * [encodeTemplateAttachments] writes. An add-on is a questionnaire about the Task as a whole; two of
 * them would need a position or an ordering model, and v0.7.1 deliberately has neither. A column
 * that held two would be a model promising a page layout the reviewer cannot choose.
 *
 * `""` is not "the default add-on". There is no default — an add-on is a choice somebody made — and
 * a Todo that has never chosen one has a Task whose pages are its files and nothing more.
 */
fun encodeAddOnAttachment(ref: AddOnVersionRef?): String =
    ref?.let { "${it.addOnId.value}\t${it.version}" }.orEmpty()

/**
 * The reference a stored column holds, or null when it holds none this build can read.
 *
 * Tolerant in the way the rest of this codebase's decoders are, but collapsing to one: any
 * well-formed line may supply the answer, the first lineage written wins, and a later mention of the
 * same lineage only raises its version. The first-line-wins rule is not an ordering the user chose —
 * there is only supposed to be one line — it is the repair of a contradiction somebody wrote, and
 * keeping the first makes the result stable rather than dependent on how a writer scrambled it.
 *
 * A line this build cannot read costs its own line and never the column. `""` and a column of
 * nonsense both answer null, which the resolver turns into *no page*.
 */
fun decodeAddOnAttachment(text: String): AddOnVersionRef? {
    val held = LinkedHashMap<AddOnId, Int>()
    text.split('\n').forEach { raw ->
        if (raw.isBlank()) return@forEach
        val fields = raw.split('\t')
        if (fields.size != 2) return@forEach
        val id = fields[0].trim()
        val version = fields[1].trim().toIntOrNull()
        if (id.isEmpty() || version == null || version < 1) return@forEach
        val lineage = AddOnId(id)
        val current = held[lineage]
        if (current == null || version > current) held[lineage] = version
    }
    val (lineage, version) = held.entries.firstOrNull() ?: return null
    return AddOnVersionRef(lineage, version)
}

/**
 * The reference after one add-on was turned on or off in the picker.
 *
 * **Turning one on replaces whatever was held.** There is one slot, so choosing an add-on is not an
 * addition; the version beside the id records how that add-on stood when the run happened, not a
 * choice being made here. Turning off the held add-on clears it; turning off some other row leaves
 * the held one alone, because a picker whose rows are single-choice still has to answer a stale tap
 * honestly.
 */
fun withAddOn(
    held: AddOnVersionRef?,
    choice: StoredAddOnVersion,
    on: Boolean
): AddOnVersionRef? = when {
    on -> choice.ref
    held?.addOnId == choice.ref.addOnId -> null
    else -> held
}

/**
 * The reference a run should write when it is carrying a Todo forward.
 *
 * **The held lineage moves to the offered version; nothing else changes.** A refresh is a refresh of
 * what the Todo holds, not a re-choosing of it, so this never adds an add-on and never removes one.
 * A lineage the offered list no longer knows keeps the reference it had: a run that cannot name the
 * current version has no better answer than the one already recorded, and silently dropping it would
 * make an unreadable add-on look like a decision to remove one.
 */
fun upgradeAddOn(
    held: AddOnVersionRef?,
    offered: List<StoredAddOnVersion>
): AddOnVersionRef? {
    if (held == null) return null
    return offered.firstOrNull { it.ref.addOnId == held.addOnId }?.ref ?: held
}

/**
 * What a refresh that moves the add-on must say before it is confirmed, or null when nothing moves.
 *
 * The template notice's sibling, and it has more to explain because an answer is not a mark: the
 * questionnaire is re-stamped on every Task the run touches, so answers already given will be read
 * under the new version. A question the new version removed keeps its answer on the Task but stops
 * being shown or sent; a question it added starts unanswered. Unlike a mark, an answer is *the*
 * current answer rather than one of many, so restoring the question restores the same words.
 *
 * Computed from the reference the run will actually submit: a refresh that clears the add-on has
 * nothing to warn about, and a refresh that adopts an unchanged version has nothing to say.
 */
fun refreshAddOnNotice(
    held: AddOnVersionRef?,
    offered: List<StoredAddOnVersion>
): String? {
    if (held == null) return null
    val current = offered.firstOrNull { it.ref.addOnId == held.addOnId } ?: return null
    if (current.ref.version == held.version) return null
    return "Tasks already answered will be read under the new version of ${current.name}. " +
        "An answer whose question the new version removed is kept on the Task but no longer shown " +
        "or sent; a question it added starts unanswered."
}

/**
 * The add-on versions this build can read, and what there is to attach.
 *
 * **Nothing here falls back and nothing is guessed at.** A version whose question payload this build
 * cannot decode, or that belongs to no lineage, is left out of the lookup. An attached reference
 * that names one of those resolves to null, which the review surface reads as *no page* — the same
 * fail-closed rule [TemplateResolver] applies to a template whose buttons it cannot read. A page of
 * half a questionnaire would be worse than no page, because it would invite an answer to a question
 * the version did not actually ask.
 *
 * Immutable, and built from rows rather than holding a database: a resolver is a value a screen can
 * be given and recompose over. [ListsViewModel] builds one whenever the two tables change.
 */
@Immutable
class AddOnResolver(
    private val versions: Map<AddOnVersionRef, StoredAddOnVersion> = emptyMap(),

    /**
     * Every lineage's current version, by name: what the import picker offers.
     *
     * One entry per lineage, because attaching is naming a questionnaire to follow and asking *which
     * of its four versions* would ask the user to answer a question this model already answers for
     * them. Current means the highest version this build can read; a lineage whose every version is
     * unreadable is absent, so it cannot be attached and cannot draw a blank page.
     */
    val attachable: List<StoredAddOnVersion> = emptyList()
) {

    /** One stored version, or null for a reference this build cannot read. */
    fun versionOf(ref: AddOnVersionRef?): StoredAddOnVersion? =
        ref?.let { versions[it] }

    /**
     * The version a stored column names, or null when it names none.
     *
     * The only thing that should interpret `sources.addOn` and `task_imports.addOn`; a caller that
     * compared raw strings would answer *attached* for a reference whose version this build dropped
     * and then draw nothing at all.
     */
    fun versionFor(encoded: String): StoredAddOnVersion? =
        versionOf(decodeAddOnAttachment(encoded))

    /**
     * The version a stored column names, when it is worth a page.
     *
     * A version with no questions draws nothing in Review, so every surface that decides whether a
     * Task has an add-on **page** asks this rather than [versionFor]. The distinction is deliberate
     * and belongs here: export and write paths keep reading [versionFor], because an attached
     * questionnaire with no questions is still an attachment they must report; only the page model
     * treats it as absent.
     */
    fun readableVersion(encoded: String): StoredAddOnVersion? =
        versionFor(encoded)?.takeIf { it.questions.isNotEmpty() }

    /**
     * A question as one authored version asked it, for explaining an answer the current version no
     * longer offers.
     *
     * Reads the authored reference rather than the Task's current one: the whole reason an answer
     * stores it is that the current questionnaire may have moved past the question, and Info should
     * show the words the reviewer actually saw when it can.
     */
    fun questionOf(answer: com.example.listea.domain.AddOnAnswer): AddOnQuestion? =
        versions[AddOnVersionRef(answer.authoredAddOnId, answer.authoredAddOnVersion)]
            ?.questions
            ?.firstOrNull { it.id == answer.questionId }

    companion object {
        /** No add-ons at all, for a surface that has to be given something before the rows land. */
        val Empty = AddOnResolver()

        /**
         * The resolver for the two tables as they stand.
         *
         * A version with no lineage is skipped because the name is the lineage's and there is no
         * add-on to list; the foreign key makes that unreachable rather than merely unlikely. A
         * version this build cannot decode is skipped for the sharper reason in the class note.
         */
        fun of(
            addOns: List<AddOnEntity>,
            versions: List<AddOnVersionEntity>
        ): AddOnResolver {
            val names = addOns.associate { it.id to it.name }
            val readable = versions.mapNotNull { row ->
                val name = names[row.addOnId] ?: return@mapNotNull null
                val questions = decodeAddOnQuestions(row.questions, row.questionsVersion)
                    ?: return@mapNotNull null
                StoredAddOnVersion(
                    ref = AddOnVersionRef(AddOnId(row.addOnId), row.version),
                    name = name,
                    questions = questions
                )
            }

            return AddOnResolver(
                versions = readable.associateBy { it.ref },
                // Highest version per lineage, read off the numbers rather than trusted from the
                // caller's order — the same rule TemplateResolver.of follows.
                attachable = readable
                    .groupBy { it.ref.addOnId }
                    .values
                    .mapNotNull { lineage -> lineage.maxByOrNull { it.ref.version } }
                    .sortedWith(compareBy({ it.name }, { it.ref.addOnId.value }))
            )
        }
    }
}
