package com.example.listea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.StoredAddOnVersion
import com.example.listea.domain.VisibleAddOnAnswer
import com.example.listea.domain.resolveAddOnAnswers

/**
 * The Task's questionnaire, drawn as the page after its last Asset.
 *
 * It is a page of the *Task*, not of the file that came before it: the name is the add-on's, the
 * questions are about the whole Task, and nothing here is a decision about one Asset. That is why
 * there is no Save: each control writes as it is touched, exactly as the action chips do, and a
 * reviewer who leaves every question alone still finishes the Task by paging past this.
 *
 * **Nothing is required, and re-tapping is how an answer is taken back.** A selected score or Yes /
 * No tapped again clears; a text field emptied clears. The page never disables the way forward, so an
 * unanswered questionnaire costs nothing and an answered one is never a precondition for completion.
 *
 * [answers] is the Task's stored rows; the page reads the ones the attached version still asks and
 * draws every question in the questionnaire's own order. An answer whose question was removed or
 * given a different type is not drawn here — it is kept, and Info is where it is explained. See
 * [resolveAddOnAnswers].
 */
@Composable
fun AddOnPage(
    addOn: StoredAddOnVersion,
    answers: List<AddOnAnswer>,
    onAnswer: (AddOnQuestion, AddOnAnswerValue) -> Unit,
    onClear: (AddOnQuestion) -> Unit,
    taskId: String,
    modifier: Modifier = Modifier
) {
    val resolved = remember(addOn, answers) { resolveAddOnAnswers(addOn, answers) }
    val byQuestion = remember(resolved) { resolved.visible.associateBy { it.question.id } }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ListeaDimens.PagePadding,
                    end = ListeaDimens.PagePadding,
                    top = ListeaDimens.SectionGap,
                    bottom = ListeaDimens.SectionGap
                ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
                Text(addOn.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Answers are kept as you go · nothing here is required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            addOn.questions.forEach { question ->
                AddOnQuestionBlock(
                    taskId = taskId,
                    question = question,
                    answer = byQuestion[question.id]?.answer,
                    onAnswer = onAnswer,
                    onClear = onClear
                )
            }
        }
    }
}

/**
 * One question and its controls.
 *
 * Keyed by task and question id at the caller: paging away and back discards the page's composition,
 * so a text field is re-seeded from the flow and a score reads from the stored row rather than from
 * whatever was on screen before.
 */
@Composable
private fun AddOnQuestionBlock(
    taskId: String,
    question: AddOnQuestion,
    answer: AddOnAnswer?,
    onAnswer: (AddOnQuestion, AddOnAnswerValue) -> Unit,
    onClear: (AddOnQuestion) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
        Text(question.prompt, style = MaterialTheme.typography.titleSmall)

        when (question.type) {
            AddOnQuestionType.RATING -> {
                val score = (answer?.value as? AddOnAnswerValue.Rating)?.score
                ActionGroup {
                    (1..5).forEach { value ->
                        FilterChip(
                            selected = score == value,
                            onClick = {
                                if (score == value) onClear(question)
                                else onAnswer(question, AddOnAnswerValue.Rating(value))
                            },
                            label = { Text("$value", maxLines = 1) }
                        )
                    }
                }
            }

            AddOnQuestionType.YES_NO -> {
                val yes = (answer?.value as? AddOnAnswerValue.YesNo)?.yes
                ActionGroup {
                    listOf(true to "Yes", false to "No").forEach { (value, label) ->
                        FilterChip(
                            selected = yes == value,
                            onClick = {
                                if (yes == value) onClear(question)
                                else onAnswer(question, AddOnAnswerValue.YesNo(value))
                            },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }

            AddOnQuestionType.TEXT -> AddOnAnswerField(
                key = taskId to question.id,
                stored = (answer?.value as? AddOnAnswerValue.Text)?.text.orEmpty(),
                onCommit = { text ->
                    if (text.isBlank()) onClear(question)
                    else onAnswer(question, AddOnAnswerValue.Text(text))
                }
            )
        }

        HorizontalDivider(Modifier.padding(top = ListeaDimens.RowGap))
    }
}

/**
 * A free-text answer, committed on Done, on leaving the field, or on leaving the page — never per
 * keystroke.
 *
 * The field is seeded from what is stored and follows a stored value that arrives after it was first
 * drawn (the answer flow starts empty). It stops following the moment somebody types: a value
 * arriving late must not overwrite words already being written.
 *
 * Blank is not an answer, so it is committed as a clear — the same line the write path draws between
 * *no row* and *a row saying nothing*.
 */
@Composable
private fun AddOnAnswerField(
    key: Any,
    stored: String,
    onCommit: (String) -> Unit
) {
    var text by remember(key) { mutableStateOf(stored) }
    var typed by remember(key) { mutableStateOf(false) }

    // The flow lands a beat after the page; until somebody has typed, the stored words are the truth.
    LaunchedEffect(key, stored) {
        if (!typed && text != stored) text = stored
    }

    fun commit() {
        val trimmed = text.trim()
        if (trimmed != stored.trim()) onCommit(trimmed)
    }

    // Disposal writes what is in the field at that moment, not what it held when the effect was set
    // up; committing a value already stored is a no-op, so a field that lost focus first is not
    // written twice.
    val commitOnLeave by rememberUpdatedState(::commit)
    DisposableEffect(key) { onDispose { commitOnLeave() } }

    OutlinedTextField(
        value = text,
        onValueChange = {
            typed = true
            text = it
        },
        label = { Text("Answer", maxLines = 1) },
        minLines = 2,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}

/**
 * What Info says about a Task's questionnaire: the attached version and the answers it explains.
 *
 * Built on the review page rather than read from the database, because the page already holds the
 * stored rows and the resolved version. Everything here is a reading; nothing writes.
 */
@Immutable
data class AddOnInfo(
    val name: String,
    val version: Int,
    /** Answers the attached version still asks, in the questionnaire's order. */
    val visible: List<VisibleAddOnAnswer>,
    /** Answers kept for a question the attached version no longer asks or asks differently. */
    val hidden: List<HiddenAddOnAnswer>
)

/**
 * One hidden answer, with the words its question had when it was answered.
 *
 * [prompt] is null when the authored version is no longer in the database or this build cannot read
 * it — the answer still exists, but the question it answered cannot be quoted. Info says so rather
 * than inventing a prompt.
 */
@Immutable
data class HiddenAddOnAnswer(val answer: AddOnAnswer, val prompt: String?)

/**
 * The add-on section of the Info sheet, or null for a Task with no readable questionnaire.
 *
 * A Task attached to a version this build cannot read also answers null: its answers are still in the
 * table, but there is no questionnaire to explain them against, and a section naming neither the
 * add-on nor its questions would be worse than none.
 */
fun addOnInfoOf(
    addOn: StoredAddOnVersion?,
    answers: List<AddOnAnswer>,
    resolver: AddOnResolver
): AddOnInfo? {
    if (addOn == null) return null
    val resolved = resolveAddOnAnswers(addOn, answers)
    return AddOnInfo(
        name = addOn.name,
        version = addOn.ref.version,
        visible = resolved.visible,
        hidden = resolved.hidden.map { answer ->
            HiddenAddOnAnswer(answer = answer, prompt = resolver.questionOf(answer)?.prompt)
        }
    )
}

/**
 * The rows the Info sheet appends for [info]: one summary, then one per answer.
 *
 * A hidden answer is named *no longer asked* and keeps its stored value, because that is the whole
 * promise of hidden-not-deleted: what somebody said is still there to be read, it simply is not on
 * the page or in the payload.
 *
 * Pure and separate from the sheet so the wording can be pinned without a Compose test.
 */
fun addOnInfoRows(info: AddOnInfo): List<InfoField> = buildList {
    val answered = info.visible.size
    val summary = when {
        info.visible.isEmpty() && info.hidden.isEmpty() -> "nothing answered"
        info.hidden.isEmpty() -> countLabel(answered, "answer")
        else -> "${countLabel(answered, "answer")} · " +
            "${info.hidden.size} no longer asked"
    }
    add(InfoField("Add-on", "${info.name} · version ${info.version} · $summary"))

    info.visible.forEach { visible ->
        add(InfoField(visible.question.prompt, addOnAnswerText(visible.answer.value)))
    }
    info.hidden.forEach { hidden ->
        val value = "${addOnAnswerText(hidden.answer.value)} · no longer asked"
        add(InfoField(hidden.prompt ?: "A question this version no longer asks", value))
    }
}

/**
 * An answer as a person reads it.
 *
 * A score says what it is out of, because `3` alone is ambiguous next to a question whose prompt may
 * have moved; Yes / No are words; text is the reviewer's own.
 */
fun addOnAnswerText(value: AddOnAnswerValue): String = when (value) {
    is AddOnAnswerValue.Rating -> "${value.score} / 5"
    is AddOnAnswerValue.YesNo -> if (value.yes) "Yes" else "No"
    is AddOnAnswerValue.Text -> value.text
}
