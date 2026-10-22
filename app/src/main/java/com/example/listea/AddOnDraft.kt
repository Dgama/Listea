package com.example.listea

import com.example.listea.domain.AddOnQuestion

/**
 * Why a draft add-on cannot be written, or null when it can.
 *
 * **The page's validation, kept pure so it is testable and so the store's refusals have words
 * beside them.** The store re-checks all of this before it writes — a disabled button is a promise
 * about a screen, not about a table — but the screen is where the user has to be told *which* row
 * is wrong, and that answer needs to exist before anything is called.
 *
 * The order is the page's order: a name, then whether there is a questionnaire at all, then the
 * questions themselves. One problem is named at a time rather than all of them, because the bar has
 * one line and the first thing to fix is the useful thing to say.
 */
sealed interface AddOnDraftProblem {
    data object NameRequired : AddOnDraftProblem

    data object NoQuestions : AddOnDraftProblem

    /** [index] is the zero-based draft position; the message names it from one. */
    data class PromptRequired(val index: Int) : AddOnDraftProblem

    /**
     * Two questions carry one id.
     *
     * Unreachable through the page, which mints a fresh id per question; it is reachable through a
     * draft restored from state or built by a caller that is not this page, and two questions
     * claiming one answer row is a table that cannot say which was answered.
     */
    data object DuplicateQuestion : AddOnDraftProblem
}

/**
 * The first thing wrong with a draft, or null when the store would save exactly this.
 *
 * Prompts are judged after the same sanitiser the store applies, so a prompt of spaces is blank here
 * for the same reason it is blank there: the encoding is one line per question, and a line with no
 * prompt is a question nobody can answer.
 */
internal fun addOnDraftProblem(
    name: String,
    questions: List<AddOnQuestion>
): AddOnDraftProblem? {
    if (sanitizeActionText(name).isBlank()) return AddOnDraftProblem.NameRequired
    if (questions.isEmpty()) return AddOnDraftProblem.NoQuestions
    questions.forEachIndexed { index, question ->
        if (sanitizeActionText(question.prompt).isBlank()) {
            return AddOnDraftProblem.PromptRequired(index)
        }
    }
    if (questions.map { it.id }.toSet().size != questions.size) {
        return AddOnDraftProblem.DuplicateQuestion
    }
    return null
}

/** What the save bar says about a draft that cannot be written. */
internal fun addOnDraftMessage(problem: AddOnDraftProblem): String = when (problem) {
    AddOnDraftProblem.NameRequired -> "A name is required."
    AddOnDraftProblem.NoQuestions -> "Add at least one question."
    is AddOnDraftProblem.PromptRequired -> "Question ${problem.index + 1} needs a prompt."
    AddOnDraftProblem.DuplicateQuestion -> "Two questions share an id, so nothing can be saved."
}

/**
 * Whether Update would write anything.
 *
 * Compared as the encoded draft against the stored payload, the same comparison [AddOnStore.update]
 * makes — so *Unsaved changes* and *a version will be appended* can never disagree. A draft whose
 * questions were reordered, retyped or reworded is a change; one rebuilt from the same values in the
 * same order is not.
 */
internal fun addOnDraftChanged(
    name: String,
    questionsText: String,
    storedName: String,
    storedQuestions: String
): Boolean = name != storedName || questionsText != storedQuestions
