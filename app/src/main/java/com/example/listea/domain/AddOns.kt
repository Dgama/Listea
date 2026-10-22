package com.example.listea.domain

/**
 * Identity of an add-on *lineage*: the questionnaire, across every edit it has had.
 *
 * Minted, never derived from the name, for the same reason [TemplateId] is: a person may rename an
 * add-on without making it a different thing, and a Task that was reviewed under it must still be
 * able to say which questionnaire asked.
 *
 * It is deliberately its own type rather than a reused [TemplateId]. The two entities share a shape
 * — lineage, immutable versions, an edit that appends — and share nothing else: a template has a
 * scope and buttons, an add-on has questions and no scope. A type that could stand for either would
 * make *this column is a template id* a comment rather than a fact the compiler keeps.
 */
@JvmInline
value class AddOnId(val value: String)

/**
 * A reference to one immutable add-on *version*.
 *
 * The pair is the identity. A Task stores the reference it was imported under, the same way it
 * stores a template reference, and goes on meaning that version after the add-on is edited. See
 * [StoredAddOnVersion].
 */
data class AddOnVersionRef(val addOnId: AddOnId, val version: Int) {

    /** `8f0…@2`, for logs and for anywhere a single token is easier. */
    override fun toString(): String = "${addOnId.value}@$version"
}

/**
 * What one question asks and how it is answered.
 *
 * [RATING] is a score from 1 to 5, [YES_NO] is a pair of answers, [TEXT] is free text. The keys are
 * stored and exported, spelled out rather than taken from the ordinal for the reason every other
 * enum in this package spells its own: an ordinal is a position in this file, and inserting a member
 * above another would silently re-read every stored question as its neighbour.
 *
 * **The set is closed in v0.7.1.** A category type was considered for the round's own scenario and
 * deliberately not added: *belongs to life photographs* is a Yes / No question today, and a new type
 * is a new payload vocabulary rather than a new row in a picker.
 */
enum class AddOnQuestionType(val key: String) {
    RATING("rating"),
    YES_NO("yes-no"),
    TEXT("text");

    companion object {
        /** The type a stored [key] names, or null for one this build has never written. */
        fun fromKey(key: String?): AddOnQuestionType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One question in one add-on version.
 *
 * [id] is opaque, minted when the question is added, and is what an answer names. It is never shown,
 * and it survives an edit to the prompt or the type — which is what lets an answer be recognised as
 * *the same question, asked differently* rather than as a new one. A type change is still a hiding
 * event, because the stored answer is only meaningful in the vocabulary it was given in; see
 * [resolveAddOnAnswers].
 *
 * [prompt] is one line, sanitised by the editor. It is not copied into an answer row: a prompt lives
 * in an immutable version, so editing it changes what is displayed and sent without rewriting what
 * somebody said.
 */
data class AddOnQuestion(
    val id: String,
    val type: AddOnQuestionType,
    val prompt: String
)

/**
 * One add-on version in the shape the store holds it: what it is called and what it asks.
 *
 * The same split [StoredTemplateVersion] makes: the name belongs to the lineage and an edit appends
 * a version rather than revising this one. [ref] carries the version number, so a newly created
 * add-on and a stored row are the same shape.
 */
data class StoredAddOnVersion(
    val ref: AddOnVersionRef,
    val name: String,
    val questions: List<AddOnQuestion>
)

/**
 * A typed answer.
 *
 * Three members for three question types, and no fourth for "no answer": the absence of a row *is*
 * the unanswered state. A rating is constrained to 1–5, a Yes / No is a boolean, and text is the
 * reviewer's words. The type is carried beside the value rather than inferred from it, because a
 * one-word Yes / No text answer and a text answer are different facts.
 */
sealed interface AddOnAnswerValue {
    data class Rating(val score: Int) : AddOnAnswerValue

    data class YesNo(val yes: Boolean) : AddOnAnswerValue

    data class Text(val text: String) : AddOnAnswerValue
}

/**
 * One stored answer, as the Task layer holds it.
 *
 * [authoredAddOnId] and [authoredAddOnVersion] are history and never move: they say which
 * questionnaire asked, so an answer can still be explained after the add-on has been edited,
 * detached or restored. They are deliberately not foreign keys — the same reason a PDF mark's
 * authored reference is not one — because deleting an add-on must never delete the record of what
 * somebody said.
 *
 * [type] is the question's type at the moment the answer was given. An answer whose question has
 * since changed type is hidden rather than reinterpreted: `3` is a rating, not the third Yes / No.
 */
data class AddOnAnswer(
    val questionId: String,
    val type: AddOnQuestionType,
    val value: AddOnAnswerValue,
    val authoredAddOnId: AddOnId,
    val authoredAddOnVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * The first version of the question encoding.
 *
 * A payload is never read without the number that wrote it, and the number is the thing that lets a
 * later build add a question type without this build guessing at it. Only version 1 exists in
 * v0.7.1; the constant and the support range exist so the reading rule is in place before there is
 * anything to read with it.
 */
const val ADD_ON_QUESTIONS_VERSION = 1

/** Every version this build can decode. A version outside it is unreadable, never guessed at. */
val SUPPORTED_ADD_ON_QUESTION_VERSIONS: IntRange = ADD_ON_QUESTIONS_VERSION..ADD_ON_QUESTIONS_VERSION

/**
 * A version's questions, as one string: one line per question, three tab-separated fields.
 *
 * ```text
 * 7c1e…	rating	How good are these?
 * 9a42…	yes-no	Is there green?
 * ```
 *
 * Order is the questionnaire's order, so nothing is sorted: a page whose questions shuffled between
 * two reads would be a page that looks like it had changed. The prompt is written as it is stored;
 * keeping typed text to one line is the editor's job at the moment of typing, exactly as it is for a
 * template button's name.
 */
fun encodeAddOnQuestions(questions: List<AddOnQuestion>): String =
    questions.joinToString("\n") {
        "${it.id}\t${it.type.key}\t${it.prompt}"
    }

/**
 * The questions a payload holds, or `null` when this build cannot read the version as a whole.
 *
 * **Strict where the button decoder is tolerant, and the difference is the point.** A dropped button
 * costs a chip; a dropped question costs the *explanation* of an answer somebody gave, and the page
 * would quietly show a questionnaire that is not the one on record. So a line this build cannot read
 * — no id, empty prompt, unknown type — makes the version unreadable rather than being skipped. The
 * same answer covers a duplicate id, which would make two questions claim one answer row.
 *
 * `null` is not `emptyList()`. An add-on version with no questions is a questionnaire with nothing
 * to ask, which the store refuses to create; a payload that says it has one is corrupt, and the
 * caller's answer is *no page at all* rather than a blank one.
 */
fun decodeAddOnQuestions(text: String, version: Int): List<AddOnQuestion>? {
    if (version !in SUPPORTED_ADD_ON_QUESTION_VERSIONS) return null
    if (text.isEmpty()) return null

    val seen = mutableSetOf<String>()
    val questions = mutableListOf<AddOnQuestion>()
    text.split('\n').forEach { line ->
        if (line.isBlank()) return@forEach
        val fields = line.split('\t')
        if (fields.size < 3) return null
        val id = fields[0].trim()
        val type = AddOnQuestionType.fromKey(fields[1].trim()) ?: return null
        val prompt = fields.subList(2, fields.size).joinToString("\t").trim()
        if (id.isEmpty() || prompt.isEmpty() || !seen.add(id)) return null
        questions += AddOnQuestion(id = id, type = type, prompt = prompt)
    }
    return questions.takeIf { it.isNotEmpty() }
}

/**
 * The questions an *editor draft* holds, read leniently enough to survive being edited.
 *
 * [decodeAddOnQuestions] is the right reading for a stored version: a payload with an empty prompt
 * is corrupt, and refusing the whole version is safer than showing a questionnaire that is not the
 * one on record. A draft is not on record. It is written and read again on every keystroke, and a
 * prompt on its way to being retyped is empty for a moment — judged by the stored rule, that moment
 * makes the version unreadable, and the editor would take every question away from under the
 * cursor. So this reading keeps a blank prompt and leaves it to the page's own validation to say it
 * must be filled before Update.
 *
 * The prompt is kept exactly as typed rather than trimmed: a space is something a user may be part
 * way through typing, and sanitising belongs at the write, not in the draft.
 */
internal fun decodeAddOnDraftQuestions(text: String): List<AddOnQuestion> =
    text.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 3) return@mapNotNull null
        val id = fields[0].trim()
        val type = AddOnQuestionType.fromKey(fields[1].trim()) ?: return@mapNotNull null
        if (id.isEmpty()) return@mapNotNull null
        AddOnQuestion(
            id = id,
            type = type,
            prompt = fields.subList(2, fields.size).joinToString("\t")
        )
    }

/**
 * An answer's stored text, given its type.
 *
 * Rating and Yes / No are written in one word so a row is readable without knowing the type; text is
 * written as it was typed. Trimming happens here rather than at the field because an exported
 * answer should not carry the spaces a keyboard left behind.
 */
fun encodeAddOnAnswer(value: AddOnAnswerValue): String = when (value) {
    is AddOnAnswerValue.Rating -> value.score.toString()
    is AddOnAnswerValue.YesNo -> if (value.yes) "yes" else "no"
    is AddOnAnswerValue.Text -> value.text.trim()
}

/**
 * The typed value a stored row means, or null when the row does not fit the type's vocabulary.
 *
 * The second gate for a value written by an older build or a corrupted database: a rating outside
 * 1–5 is not a score, `maybe` is not a Yes / No, and blank text is no answer at all. A caller that
 * gets null omits the answer rather than sending a value it could not interpret.
 */
fun parseAddOnAnswer(type: AddOnQuestionType, raw: String): AddOnAnswerValue? = when (type) {
    AddOnQuestionType.RATING -> {
        val score = raw.trim().toIntOrNull()
        score?.takeIf { it in 1..5 }?.let { AddOnAnswerValue.Rating(it) }
    }

    AddOnQuestionType.YES_NO -> when (raw.trim()) {
        "yes" -> AddOnAnswerValue.YesNo(true)
        "no" -> AddOnAnswerValue.YesNo(false)
        else -> null
    }

    AddOnQuestionType.TEXT -> raw.trim().takeIf { it.isNotEmpty() }?.let { AddOnAnswerValue.Text(it) }
}

/**
 * Whether [value] can be the answer to a question of [type].
 *
 * The second gate for a write that reached the table from somewhere other than the page: a rating
 * outside 1–5, a yes/no claimed for a text question, or blank text is not an answer to anything,
 * and storing it would make the row unreadable rather than wrong. The page offers only fitting
 * controls, so this can only bite a caller that bypassed it.
 */
fun addOnAnswerFits(type: AddOnQuestionType, value: AddOnAnswerValue): Boolean = when (type) {
    AddOnQuestionType.RATING ->
        value is AddOnAnswerValue.Rating && value.score in 1..5
    AddOnQuestionType.YES_NO ->
        value is AddOnAnswerValue.YesNo
    AddOnQuestionType.TEXT ->
        value is AddOnAnswerValue.Text && value.text.trim().isNotEmpty()
}

/**
 * An answer whose question the current version still offers, with that current question.
 *
 * [question] is the *current* wording and type; [answer] is what the reviewer said. They meet at the
 * question id.
 */
data class VisibleAddOnAnswer(val question: AddOnQuestion, val answer: AddOnAnswer)

/** The two readings Info and export need, and the rule that keeps them from disagreeing. */
data class ResolvedAddOnAnswers(
    /** Answers the page and the payload show, in the questionnaire's order. */
    val visible: List<VisibleAddOnAnswer>,
    /** Answers kept but not shown or sent, in stored order. */
    val hidden: List<AddOnAnswer>
)

/**
 * Which stored answers the attached version still asks, and which it has put aside.
 *
 * **Visible means all four of:** the Task is attached to an add-on; the answer was authored by
 * *that lineage*; the current version offers a question with the same id; and that question still
 * has the type the answer was given in. The lineage check is what stops an answer being reused by a
 * different questionnaire that happened to mint the same question id, and the type check is what
 * stops a `3` from a rating being read as a Yes / No because the editor changed the row.
 *
 * **Hidden is not deleted.** The rows stay for as long as the Task does, so removing a question and
 * later restoring it brings the same words back. Nothing here writes; it is a reading.
 *
 * Visible answers follow [addOn]'s question order rather than the order they were given in, because
 * the questionnaire has an order and the rows do not. Hidden answers keep their stored order, which
 * is the only order they have.
 */
fun resolveAddOnAnswers(
    addOn: StoredAddOnVersion?,
    answers: List<AddOnAnswer>
): ResolvedAddOnAnswers {
    if (addOn == null) {
        return ResolvedAddOnAnswers(visible = emptyList(), hidden = answers)
    }
    val byQuestion = answers.associateBy { it.questionId }
    val visible = addOn.questions.mapNotNull { question ->
        val answer = byQuestion[question.id] ?: return@mapNotNull null
        if (answer.authoredAddOnId != addOn.ref.addOnId) return@mapNotNull null
        if (answer.type != question.type) return@mapNotNull null
        VisibleAddOnAnswer(question, answer)
    }
    val shown = visible.mapTo(mutableSetOf()) { it.answer.questionId }
    val hidden = answers.filter { it.questionId !in shown }
    return ResolvedAddOnAnswers(visible = visible, hidden = hidden)
}

/**
 * Questions earlier versions asked that the draft no longer holds, for the editor's Restore list.
 *
 * Hidden answers are only ever shown again when a later version offers a question with the **same
 * id**, and the editor's *Add question* mints a fresh id — so without this reading, the sentence the
 * editor prints (*putting the question back brings those answers back*) would be a promise its own
 * button could not keep. Restoring one of these keeps the id, which is the whole of what the
 * visibility rule needs; the wording and type come from the newest earlier version that asked it,
 * because that is the last way a person phrased it before it was removed.
 *
 * [earlier] is oldest first, as the stored versions are; [kept] is the draft's questions, so a
 * question removed but not yet saved appears here at once. A question that is back in [kept] — by
 * this list or by any other route — is not offered twice.
 */
fun restorableAddOnQuestions(
    earlier: List<List<AddOnQuestion>>,
    kept: List<AddOnQuestion>
): List<AddOnQuestion> {
    val held = kept.mapTo(mutableSetOf()) { it.id }
    // Newest wording wins; first appearance fixes the order, so the list does not reshuffle when an
    // older version happens to be read again.
    val order = mutableListOf<String>()
    val newest = LinkedHashMap<String, AddOnQuestion>()
    earlier.forEach { questions ->
        questions.forEach { question ->
            if (question.id !in newest) order += question.id
            newest[question.id] = question
        }
    }
    return order.filter { it !in held }.mapNotNull { newest[it] }
}
