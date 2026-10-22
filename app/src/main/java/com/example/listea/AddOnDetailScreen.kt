package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.decodeAddOnDraftQuestions
import com.example.listea.domain.decodeAddOnQuestions
import com.example.listea.domain.encodeAddOnQuestions
import com.example.listea.domain.restorableAddOnQuestions
import java.util.UUID

/**
 * One add-on: its name and the questions it asks — and the one gesture that writes any of it.
 *
 * The template page's sibling and the same contract: nothing here is written until Update. What the
 * page holds is a draft — the name and the encoded questions — in saved state, built from the rows
 * and put back only by [ListsViewModel.updateAddOn]. Cancel restores the draft and leaves.
 *
 * **The questions are the whole of what an add-on is.** There is no scope to choose and no position:
 * the page answers *what is this called* and *what does it ask*, and the round fixes everything else.
 * Each question has a prompt and one of the three types; order is the draft order and removing one
 * removes it from the next version, never from the answers already stored.
 *
 * **A question id survives everything except removal.** Editing a prompt or changing a type keeps the
 * id, which is what lets an answer be recognised as *the same question, asked differently* — or, when
 * the type moves, hidden rather than reinterpreted.
 */
@Composable
fun AddOnDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    addOnId: String,
    onBack: () -> Unit
) {
    val addOns by viewModel.addOns.collectAsStateWithLifecycle()
    val versions by viewModel.addOnVersions.collectAsStateWithLifecycle()

    val addOn = addOns?.firstOrNull { it.id == addOnId }
    val current = versions?.lastOrNull { it.addOnId == addOnId }

    BackHandler { onBack() }

    ListeaNestedScaffold(
        modifier = modifier,
        // The stored name, not the draft's: a title that changed under the cursor would be the page
        // renaming itself as the user typed.
        title = addOn?.name ?: "Add-on",
        backLabel = "Templates & Add-ons",
        onBack = onBack,
        trailing = {
            current?.let {
                Text(
                    "Version ${it.version}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { bodyModifier ->
        when {
            // Not read yet: a blank body rather than a message that the add-on is gone.
            addOns == null -> Unit

            addOn == null || current == null -> AddOnUnavailable(
                modifier = bodyModifier,
                title = "This add-on is not in the database any more.",
                detail = "Nothing here can edit it. Go back to the list to see what is left."
            )

            // A payload this build cannot read is refused rather than offered empty: an Update from
            // here would replace a newer version's questionnaire with this build's idea of nothing.
            decodeAddOnQuestions(current.questions, current.questionsVersion) == null ->
                AddOnUnavailable(
                    modifier = bodyModifier,
                    title = "This version was written by a newer build.",
                    detail = "Its questions are stored in a form this build cannot read, so there " +
                        "is no editor here for them. The add-on itself is unchanged."
                )

            else -> AddOnEditor(
                addOn = addOn,
                current = current,
                // Everything earlier, oldest first, so the editor can offer a removed question back
                // under the id its answers already name. An unreadable earlier version contributes
                // nothing rather than making the editor unavailable: it is not the version being
                // edited, and this build can still read the one that is.
                earlierQuestions = versions.orEmpty()
                    .filter { it.addOnId == addOnId && it.version < current.version }
                    .sortedBy { it.version }
                    .mapNotNull { decodeAddOnQuestions(it.questions, it.questionsVersion) },
                viewModel = viewModel,
                onBack = onBack
            )
        }
    }
}

/**
 * The editor itself, over one version that has already been read.
 *
 * Split out so the three answers above — not read yet, gone, unreadable — are decided once, before
 * anything here assumes there is a questionnaire to edit.
 */
@Composable
private fun AddOnEditor(
    addOn: AddOnEntity,
    current: AddOnVersionEntity,
    earlierQuestions: List<List<AddOnQuestion>>,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    // Re-seeded when the rows move, which happens for one reason: an Update landed. The drafts then
    // become the version it just wrote and the bar goes back to saying nothing has changed.
    var name by rememberSaveable(addOn.id, addOn.name) { mutableStateOf(addOn.name) }
    var questionsText by rememberSaveable(addOn.id, current.version, current.questions) {
        mutableStateOf(current.questions)
    }

    // The draft's own reading, not the stored one: a prompt being retyped is blank for a moment,
    // and the strict decoder would take every question off the page at that moment.
    val questions = remember(questionsText) { decodeAddOnDraftQuestions(questionsText) }

    fun setQuestions(next: List<AddOnQuestion>) {
        questionsText = encodeAddOnQuestions(next)
    }

    val problem = addOnDraftProblem(name, questions)
    val changed = addOnDraftChanged(name, questionsText, addOn.name, current.questions)
    // Read off the draft, so removing a question puts it in the list at once and restoring it takes
    // it straight back out; an Update is not needed for either to show.
    val restorable = remember(questionsText, earlierQuestions) {
        restorableAddOnQuestions(earlierQuestions, questions)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ListeaDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
        ) {
            Spacer(Modifier.height(ListeaDimens.CompactGap))

            SectionCard(title = "Name") {
                DraftTextField(
                    label = "Add-on name",
                    value = name,
                    onValueChange = { name = it },
                    error = if (problem == AddOnDraftProblem.NameRequired) {
                        addOnDraftMessage(problem)
                    } else {
                        null
                    }
                )
                HelperText(
                    "What this add-on is called where it is listed and attached. A name is not " +
                        "part of its identity: renaming one changes nothing about the Tasks that " +
                        "already answered it."
                )
            }

            SectionCard(title = "Questions") {
                questions.forEachIndexed { index, question ->
                    // Keyed by id: removing the first of three must not hand the second one's
                    // half-typed prompt to the third.
                    key(question.id) {
                        AddOnQuestionEditor(
                            index = index,
                            question = question,
                            promptError = (problem as? AddOnDraftProblem.PromptRequired)
                                ?.takeIf { it.index == index }
                                ?.let { addOnDraftMessage(it) },
                            onPromptChange = { prompt ->
                                setQuestions(questions.replacing(index, question.copy(prompt = prompt)))
                            },
                            onTypeChange = { type ->
                                setQuestions(questions.replacing(index, question.copy(type = type)))
                            },
                            onRemove = {
                                setQuestions(questions.filterNot { it.id == question.id })
                            }
                        )
                    }
                }

                if (questions.isEmpty()) {
                    HelperText(
                        "An add-on needs at least one question. Add one below.",
                        tone = StatusTone.Warning
                    )
                }

                Spacer(Modifier.height(ListeaDimens.RowGap))
                TextButton(onClick = { setQuestions(questions + newAddOnQuestion(questions.size)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(ListeaDimens.RowGap))
                    Text("Add question")
                }
                // A question removed from an earlier version, offered back with its own id. Without
                // this, the sentence below would promise a restore that *Add question* cannot make:
                // it mints a fresh id, and an answer is only shown again under the id that asked it.
                if (restorable.isNotEmpty()) {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text("Removed questions", style = MaterialTheme.typography.titleSmall)
                    restorable.forEach { question ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    addOnQuestionTypeLabel(question.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { setQuestions(questions + question) }) {
                                Text("Restore")
                            }
                        }
                    }
                }
                HelperText(
                    "Questions are asked in this order on the add-on's page, after the Task's " +
                        "last file. Nothing here is required of a reviewer: the page records " +
                        "whatever is answered and completes the Task either way."
                )
                HelperText(
                    "Removing a question does not delete answers already given to it. They are " +
                        "kept on each Task and no longer shown or sent; putting the question back " +
                        "brings those answers back."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))
        }

        AddOnSaveBar(
            version = current.version,
            changed = changed,
            problem = problem,
            onCancel = {
                // Put back before leaving, so *Cancel changes nothing* is true of this page and not
                // a bet on when Compose discards a draft it is done with.
                name = addOn.name
                questionsText = current.questions
                onBack()
            },
            onUpdate = { viewModel.updateAddOn(addOn.id, name, questions) }
        )
    }
}

/**
 * One question's prompt, its type, and the way to remove it.
 *
 * The id is not shown and not editable. It is what an answer names, so a user who could change it
 * would be renaming the thing a reviewer's words are attached to.
 */
@Composable
private fun AddOnQuestionEditor(
    index: Int,
    question: AddOnQuestion,
    promptError: String?,
    onPromptChange: (String) -> Unit,
    onTypeChange: (AddOnQuestionType) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Question ${index + 1}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove question ${index + 1}")
        }
    }

    DraftTextField(
        label = "Prompt",
        value = question.prompt,
        onValueChange = onPromptChange,
        error = promptError
    )

    Spacer(Modifier.height(ListeaDimens.CompactGap))
    Text("Answer with", style = MaterialTheme.typography.bodySmall)
    ActionGroup {
        AddOnQuestionType.entries.forEach { type ->
            FilterChip(
                selected = type == question.type,
                onClick = { onTypeChange(type) },
                label = { Text(addOnQuestionTypeLabel(type), maxLines = 1) }
            )
        }
    }
    HelperText(addOnQuestionTypeHelp(question.type))

    HorizontalDivider(Modifier.padding(vertical = ListeaDimens.RowGap))
}

/**
 * The bar that decides whether any of this is written.
 *
 * Update is disabled until something differs from the stored version *and* nothing is wrong with the
 * draft. The line on the left says which of the two it is — a disabled button with no explanation is
 * the thing this bar exists to prevent.
 */
@Composable
private fun AddOnSaveBar(
    version: Int,
    changed: Boolean,
    problem: AddOnDraftProblem?,
    onCancel: () -> Unit,
    onUpdate: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(
                "Version $version. Update writes version ${version + 1} — an earlier version is " +
                    "kept, and so is every answer given under it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        problem != null -> addOnDraftMessage(problem)
                        changed -> "Unsaved changes"
                        else -> "No changes"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        problem != null -> MaterialTheme.colorScheme.error
                        changed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = onUpdate,
                    enabled = changed && problem == null
                ) { Text("Update") }
            }
        }
    }
}

/** The page when there is nothing to edit, and the reason why. */
@Composable
private fun AddOnUnavailable(modifier: Modifier, title: String, detail: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(ListeaDimens.PagePadding)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** What a question type is called where a user picks one. */
private fun addOnQuestionTypeLabel(type: AddOnQuestionType): String = when (type) {
    AddOnQuestionType.RATING -> "Rating 1–5"
    AddOnQuestionType.YES_NO -> "Yes / No"
    AddOnQuestionType.TEXT -> "Text"
}

/** What answering that type means, in the page's own words. */
private fun addOnQuestionTypeHelp(type: AddOnQuestionType): String = when (type) {
    AddOnQuestionType.RATING ->
        "Five buttons, 1 to 5. Tapping the selected score again clears it."
    AddOnQuestionType.YES_NO ->
        "Two buttons, Yes and No. Tapping the selected answer again clears it."
    AddOnQuestionType.TEXT ->
        "A free-text answer. It is stored when the field is left or Done is pressed, and a " +
            "blank field clears it."
}

/** [questions] with the one at [index] replaced. The draft is a list, so an edit is a rebuild. */
private fun List<AddOnQuestion>.replacing(
    index: Int,
    question: AddOnQuestion
): List<AddOnQuestion> = mapIndexed { at, existing -> if (at == index) question else existing }

/**
 * A question with a fresh id, ready for the user to rewrite.
 *
 * The id is minted here rather than by the store, because the draft has to be able to hold a question
 * that has never been written: from the moment it appears in the list, the row's own edit callbacks
 * name it. It is opaque and never shown.
 */
private fun newAddOnQuestion(count: Int): AddOnQuestion = AddOnQuestion(
    id = UUID.randomUUID().toString(),
    type = AddOnQuestionType.RATING,
    prompt = "Question ${count + 1}"
)
