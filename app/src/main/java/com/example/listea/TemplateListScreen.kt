package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.data.StoredTemplateKind
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.decodeAddOnQuestions
import com.example.listea.domain.decodeTaskReviewDefinition
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope

/**
 * Everything Listea can attach to a Task, templates and add-ons together, by name.
 *
 * **One list rather than two.** The two kinds are authored the same way — name it, fill it in,
 * confirm, and an edit appends a version — and the shared name is the question a user arrives with:
 * *what do I have?* A second section would make the answer two screens and the difference between
 * them a taxonomy the list already says in a subtitle.
 *
 * **They are still two kinds, and the row says which.** A template governs files of some kinds and
 * offers buttons; an add-on asks questions about the whole Task after its last file. The subtitle
 * carries that, so a row is never opened by someone expecting the other page.
 *
 * **New asks which kind first.** The two editors share a shape but not a vocabulary, and a name
 * prompt that created one or the other without saying so would be a coin toss the user could not see.
 * A new template starts from the shipped shape; a new add-on starts with one Rating question. Neither
 * is blank, because a thing with nothing in it can be neither attached nor saved.
 */
@Composable
fun TemplateListScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onOpenAddOn: (String) -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val versions by viewModel.templateVersions.collectAsStateWithLifecycle()
    val addOns by viewModel.addOns.collectAsStateWithLifecycle()
    val addOnVersions by viewModel.addOnVersions.collectAsStateWithLifecycle()

    // Which kind the New flow is creating, and whether the name prompt is up. Two states rather
    // than one nullable, because *the chooser is open* and *a name is being typed* are different
    // moments and the chooser must not reappear behind the prompt when the prompt closes.
    var choosingKind by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<CreateKind?>(null) }

    BackHandler { onBack() }

    if (choosingKind) {
        NewKindDialog(
            onTemplate = {
                choosingKind = false
                creating = CreateKind.TEMPLATE
            },
            onAddOn = {
                choosingKind = false
                creating = CreateKind.ADD_ON
            },
            onDismiss = { choosingKind = false }
        )
    }

    creating?.let { kind ->
        TextPromptDialog(
            title = when (kind) {
                CreateKind.TEMPLATE -> "New template"
                CreateKind.ADD_ON -> "New add-on"
            },
            label = "Name",
            initialText = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                when (kind) {
                    CreateKind.TEMPLATE -> viewModel.createTemplate(name)
                    CreateKind.ADD_ON -> viewModel.createAddOn(name)
                }
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    ListeaNestedScaffold(
        modifier = modifier,
        title = "Templates & Add-ons",
        backLabel = "Settings",
        onBack = onBack,
        trailing = {
            // Named only once both tables have been read: a count drawn from one table would report
            // an install as having half of what it has.
            if (templates != null && addOns != null) {
                Text(
                    countLabel(templates!!.size, "template") + " · " +
                        countLabel(addOns!!.size, "add-on"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { choosingKind = true }) { Text("New") }
        }
    ) { bodyModifier ->
        val loadedTemplates = templates
        val loadedAddOns = addOns
        // Nothing read yet, and that is a blank body rather than a message: the two are a frame
        // apart and only one of them is worth words.
        if (loadedTemplates == null || loadedAddOns == null) return@ListeaNestedScaffold

        val entries = remember(loadedTemplates, versions, loadedAddOns, addOnVersions) {
            val templateRows = loadedTemplates.map { template ->
                Entry.OfTemplate(
                    template = template,
                    // The versions arrive ordered by lineage and then by number, so the last one
                    // carrying this id is the newest — which is the one the page behind this row
                    // edits, and the one whose number the row offers as "current".
                    current = versions.orEmpty().lastOrNull { it.templateId == template.id }
                )
            }
            val addOnRows = loadedAddOns.map { addOn ->
                Entry.OfAddOn(
                    addOn = addOn,
                    current = addOnVersions.orEmpty().lastOrNull { it.addOnId == addOn.id }
                )
            }
            (templateRows + addOnRows).sortedWith(
                compareBy({ it.name.lowercase() }, { it.name })
            )
        }

        if (entries.isEmpty()) {
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(ListeaDimens.PagePadding)
                ) {
                    Text(
                        "Nothing to attach",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    // Said plainly rather than left as an empty page. An install always has the
                    // built-in template, so nobody should ever read this, and someone who does
                    // should come away knowing that what they are looking at is wrong.
                    Text(
                        "Every install starts with the built-in media asset control. This list is " +
                            "empty only if the database this build opened has none.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = { choosingKind = true }) { Text("New") }
                }
            }
            return@ListeaNestedScaffold
        }

        LazyColumn(
            modifier = bodyModifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ListeaDimens.PagePadding,
                vertical = ListeaDimens.RowGap
            ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is Entry.OfTemplate -> TemplateCard(
                        template = entry.template,
                        current = entry.current,
                        onOpen = { onOpenTemplate(entry.template.id) }
                    )

                    is Entry.OfAddOn -> AddOnCard(
                        addOn = entry.addOn,
                        current = entry.current,
                        onOpen = { onOpenAddOn(entry.addOn.id) }
                    )
                }
            }
        }
    }
}

/** Which kind New is about to create. */
private enum class CreateKind { TEMPLATE, ADD_ON }

/**
 * One row of the shared list, in whatever kind it is.
 *
 * [name] is lifted out so the list can sort both kinds by the one thing they both have; the key is
 * prefixed by kind, because two UUIDs colliding is not the risk — a template and an add-on sharing
 * a *string* is not a collision either, but a prefixed key says which table a key belongs to at the
 * one place a lazy list's identity is decided.
 */
private sealed interface Entry {
    val name: String

    val key: String

    data class OfTemplate(
        val template: TemplateEntity,
        val current: TemplateVersionEntity?
    ) : Entry {
        override val name: String get() = template.name
        override val key: String get() = "template:" + template.id
    }

    data class OfAddOn(
        val addOn: AddOnEntity,
        val current: AddOnVersionEntity?
    ) : Entry {
        override val name: String get() = addOn.name
        override val key: String get() = "addon:" + addOn.id
    }
}

/**
 * The question *what are you making*, before the name prompt.
 *
 * The two sentences are the difference the user is being asked about: a template adds buttons to
 * files, an add-on asks questions about the whole Task. Without them the chooser is two words naming
 * two kinds the user may not know they are different.
 */
@Composable
private fun NewKindDialog(
    onTemplate: () -> Unit,
    onAddOn: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
                Text(
                    "A template adds buttons to files of the kinds you choose. An add-on asks " +
                        "questions about the whole Task, on one page after its last file."
                )
            }
        },
        confirmButton = { TextButton(onClick = onTemplate) { Text("Template") } },
        dismissButton = { TextButton(onClick = onAddOn) { Text("Add-on") } }
    )
}

/**
 * One template: what it is called, what it governs, and which version of it is current.
 *
 * The whole card is the way in, the same way a webhook record is the way into its payload and a
 * list card is the way into its list. Nothing is edited here — a row is a row, and the page behind
 * it is where a change is decided — so the card carries no buttons at all.
 *
 * The version is named because a template is not one answer but a series of them, and "which one am
 * I looking at" is the question this release exists to make answerable. It is the *current* version
 * and not the attached one: a Todo attached at version 1 goes on meaning version 1, and this page
 * is about the template rather than about any Todo.
 */
@Composable
private fun TemplateCard(
    template: TemplateEntity,
    current: TemplateVersionEntity?,
    onOpen: () -> Unit
) {
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(
                template.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Read from the stored scope rather than from a summary computed when it was written,
            // so a payload a later build wrote still describes itself as far as this one can tell —
            // and a rule this build cannot evaluate comes out as "applies to nothing", which is
            // what it does.
            // A Task review governs no files: its line says what it is instead of "applies to
            // nothing", which for a review would read as a broken template rather than as one
            // whose subject is a Task.
            StatusLine(
                when {
                    current == null -> "No version yet"
                    current.kind == StoredTemplateKind.TASK_REVIEW -> reviewLine(current)
                    else -> templateScopeLabel(decodeTemplateScope(current.scope))
                }
            )
            StatusLine(templateVersionLine(current))
        }
    }
}

/**
 * One add-on: what it is called and what it asks.
 *
 * The subtitle opens with *Add-on* so the row cannot be mistaken for a template whose scope line
 * failed to read; the count is the questionnaire's size and the version is how many times it has
 * been edited. An unreadable version is named as such rather than counted as zero questions.
 */
@Composable
private fun AddOnCard(
    addOn: AddOnEntity,
    current: AddOnVersionEntity?,
    onOpen: () -> Unit
) {
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(
                addOn.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StatusLine(addOnQuestionLine(current))
            StatusLine(
                if (current == null) "No version yet" else "Version ${current.version}"
            )
        }
    }
}

/**
 * What a Task Review is, said as what it catches rather than as a file scope.
 *
 * A review governs no files, so the asset-control line has nothing to say about it; the thing a
 * user chooses between two reviews by is what each one catches. The empty set is not "nothing":
 * it is the default headless definition, which records the Task's own file decisions, and naming
 * that keeps it legible beside a review that catches a single choice or a sampled point.
 */
private fun reviewLine(version: TemplateVersionEntity): String {
    val definition = decodeTaskReviewDefinition(version.definition, version.definitionVersion)
        ?: return "Headless template · unreadable"
    return reviewCatchLine(version.templateId, definition)
}

/**
 * One template's buttons and version as a single line, or what is wrong with them.
 *
 * A version this build cannot read is named as such rather than counted as zero buttons, because
 * the two are opposite facts: an unreadable payload is a full bar nothing here can see, and a bar
 * with nothing on it is a choice somebody made.
 */
private fun templateVersionLine(version: TemplateVersionEntity?): String {
    if (version == null) return "This template has no version, so it cannot be attached"

    // A Task review offers its commit control rather than a bar, so counting buttons would
    // call a one-control template "1 button" and say nothing about what it is for. Its line above
    // already says what it catches, so this one only has to name the version.
    if (version.kind == StoredTemplateKind.TASK_REVIEW) {
        decodeTaskReviewDefinition(version.definition, version.definitionVersion)
            ?: return "Version ${version.version} · written by a newer build"
        return "Version ${version.version}"
    }

    val buttons = decodeTemplateButtons(version.buttons, version.buttonsVersion)
        ?: return "Version ${version.version} · written by a newer build"

    return countLabel(buttons.size, "button") + " · version ${version.version}"
}

/** The same line for an add-on, counted in questions. */
private fun addOnQuestionLine(version: AddOnVersionEntity?): String {
    if (version == null) return "Add-on · no version yet"

    val questions = decodeAddOnQuestions(version.questions, version.questionsVersion)
        ?: return "Add-on · version ${version.version} written by a newer build"

    return "Add-on · " + countLabel(questions.size, "question")
}
