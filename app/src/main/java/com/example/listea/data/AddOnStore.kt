package com.example.listea.data

import androidx.room.withTransaction
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.SUPPORTED_ADD_ON_QUESTION_VERSIONS
import com.example.listea.domain.decodeAddOnQuestions
import com.example.listea.domain.encodeAddOnQuestions
import com.example.listea.sanitizeActionText
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The add-ons as rows: what reads them, and the one way one is written.
 *
 * The same shape as [TemplateStore], deliberately — an add-on is authored the way a template is: you
 * name it, fill it in, confirm, and a change to what it asks appends a version. [update] is the
 * whole write side, a rename is an in-place change to the lineage, and nothing here can revise a
 * version or delete one. The difference is only the vocabulary: a version carries questions rather
 * than a scope and buttons.
 *
 * **Refusals live here rather than only in the screen.** The page disables Update until the draft is
 * valid, but a disabled button is a promise about a screen, not about the table; an empty
 * questionnaire or a question with no prompt reaching the rows would be a page with a blank line in
 * it on every Task that attaches it. So [update] re-checks what it was handed and writes nothing
 * when the draft could not be saved — the screen explains *which row*, and this function makes that
 * explanation true.
 *
 * There is no delete, for the reason a template has none: Tasks reference it, and an answer already
 * given names its version.
 */
class AddOnStore(private val database: ListeaDatabase) {

    private val reads = database.addOnDao()
    private val writes = database.addOnWriteDao()

    /** Every lineage, by name — what the shared list screen draws. */
    val addOns: Flow<List<AddOnEntity>> = reads.observeAddOns()

    /** Every version of every lineage, oldest first within each. */
    val versions: Flow<List<AddOnVersionEntity>> = reads.observeVersions()

    /**
     * Both tables as they stand, read once — for the import dialog, which has to *offer* them.
     *
     * One transaction for the reason [TemplateStore.snapshot] gives: a picker built from two reads
     * that happened a version apart could offer a ref whose questions are not the ones it showed.
     */
    suspend fun snapshot(): Pair<List<AddOnEntity>, List<AddOnVersionEntity>> =
        database.withTransaction { reads.addOnsOnce() to reads.versionsOnce() }

    /**
     * Starts a new lineage at version 1, and returns its id.
     *
     * **The seed is one Rating question, not an empty questionnaire.** An add-on with no questions
     * has nothing to ask and would draw a blank page on every Task it is attached to; it also could
     * not satisfy [update]'s own rule, so a user who made one could never save it. *Overall rating*
     * is the smallest thing that is a questionnaire, and it is the user's to rewrite.
     *
     * The name is sanitised and refused when blank, exactly as [TemplateStore.create] does.
     */
    suspend fun create(name: String): String? {
        val cleanName = sanitizeActionText(name)
        if (cleanName.isBlank()) return null
        val id = UUID.randomUUID().toString()
        val seed = listOf(
            AddOnQuestion(
                id = UUID.randomUUID().toString(),
                type = AddOnQuestionType.RATING,
                prompt = "Overall rating"
            )
        )

        database.withTransaction {
            writes.insertAddOn(
                AddOnEntity(id = id, name = cleanName, createdAt = System.currentTimeMillis())
            )
            writes.insertVersion(
                AddOnVersionEntity(
                    addOnId = id,
                    version = 1,
                    questions = encodeAddOnQuestions(seed),
                    questionsVersion = ADD_ON_QUESTIONS_VERSION,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return id
    }

    /**
     * Writes what one add-on's page was edited into.
     *
     * The name is written when it differs, and one version is appended when the questions differ
     * from the version that is currently last. Both, either, or neither: a rename alone is a rename,
     * and a page opened and closed unchanged writes nothing.
     *
     * **What it refuses:** an add-on with no questions, a question with a blank prompt, a duplicate
     * question id, a latest version this build cannot read, and a lineage that is not there. Each is
     * a draft that cannot be stored as a readable questionnaire; refusing writes nothing rather
     * than a version some later reader would have to work around. The screen's own validation makes
     * the refusal visible before Update is pressed; this one makes it enforceable when it is.
     *
     * Prompts are sanitised to one line by the same function template text passes through. The
     * encoding is one question per line, so a prompt containing a newline would split its own row
     * into two unreadable ones.
     *
     * **Compared as encoded text**, as [TemplateStore.update] compares its payloads: the encoding is
     * canonical in id, type and prompt, so a draft whose questions are the same values in the same
     * order compares equal even if it was built by a fresh copy. Order matters, as it does there —
     * the questions are asked in the order the draft lists them, and reordering the questionnaire is
     * a change to what it asks.
     */
    suspend fun update(
        addOnId: String,
        name: String,
        questions: List<AddOnQuestion>
    ) {
        database.withTransaction {
            val lineage = reads.addOn(addOnId) ?: return@withTransaction
            val latest = reads.versionsOf(addOnId).lastOrNull() ?: return@withTransaction
            if (latest.questionsVersion !in SUPPORTED_ADD_ON_QUESTION_VERSIONS) {
                return@withTransaction
            }
            if (decodeAddOnQuestions(latest.questions, latest.questionsVersion) == null) {
                return@withTransaction
            }

            val cleanName = sanitizeActionText(name)
            if (cleanName.isBlank()) return@withTransaction

            val cleanQuestions = questions.map {
                it.copy(prompt = sanitizeActionText(it.prompt))
            }
            if (cleanQuestions.isEmpty()) return@withTransaction
            if (cleanQuestions.any { it.prompt.isBlank() }) return@withTransaction
            if (cleanQuestions.map { it.id }.toSet().size != cleanQuestions.size) {
                return@withTransaction
            }

            val questionsText = encodeAddOnQuestions(cleanQuestions)

            if (lineage.name != cleanName) {
                writes.renameAddOn(addOnId, cleanName)
            }

            if (latest.questions == questionsText) return@withTransaction

            writes.insertVersion(
                AddOnVersionEntity(
                    addOnId = addOnId,
                    version = latest.version + 1,
                    questions = questionsText,
                    questionsVersion = ADD_ON_QUESTIONS_VERSION,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
