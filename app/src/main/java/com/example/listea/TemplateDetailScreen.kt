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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import com.example.listea.domain.templateDraftVersion

/**
 * One template: its name, what it governs, and the buttons it offers — and the one gesture that
 * writes any of it.
 *
 * **Nothing on this page is written until Update.** That is not a rule the page follows, it is where
 * the state is: what is held here is a *draft*, and the draft is three payload strings — the name,
 * the scope, the buttons — in this composable's own saved state. The rows are read, the drafts are
 * built from them, and the only thing that can put a draft back is a call to
 * [ListsViewModel.updateTemplate]. A page that forgot to write would leave the user's edit on screen
 * and nowhere else; a page that wrote as it went could not offer Cancel at all. This is the user's
 * *add and remove buttons freely, but nothing is updated until you confirm*, and it is §Templates'
 * *editing a template creates a new version* arriving as a gesture.
 *
 * **Holding the draft as the encoded payload is the point rather than an implementation detail.** A
 * draft is exactly the string an Update would store, so there is no second representation that could
 * disagree with the first — and comparing it against the stored row is what answers *has anything
 * changed*, the question the bottom bar exists to answer. It also means the draft survives rotation
 * for free, through the same encoding the database uses. The one thing the string does not carry is
 * the vocabulary it is written in, and that is not the row's once the draft is edited: a comment
 * button is version-2 vocabulary, so the draft is read at [templateDraftVersion] rather than at the
 * version it started from.
 *
 * **The version is what the page is about.** The bar names the version being edited and the one
 * Update would write, because a user renaming a button should be able to see that they have not
 * changed history: the Tasks judged under version 1 go on meaning version 1, and none of that is
 * worth anything if it is invisible.
 */
@Composable
fun TemplateDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    templateId: String,
    onBack: () -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val versions by viewModel.templateVersions.collectAsStateWithLifecycle()

    val template = templates?.firstOrNull { it.id == templateId }
    val current = versions?.lastOrNull { it.templateId == templateId }

    BackHandler { onBack() }

    ListeaNestedScaffold(
        modifier = modifier,
        // The *stored* name, not the draft's: the bar titles the page, and a title that changed
        // under the user's cursor would be the page renaming itself as they typed.
        title = template?.name ?: "Template",
        backLabel = "Templates",
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
            // Not read yet, and that is a blank body rather than a message: the alternative is
            // telling the user their template is gone a frame before the row proving it is not.
            templates == null -> Unit

            template == null || current == null -> TemplateUnavailable(
                modifier = bodyModifier,
                title = "This template is not in the database any more.",
                detail = "Nothing here can edit it. Go back to the list to see what is left."
            )

            // A payload this build cannot read is not an empty bar, and the difference matters: an
            // empty bar is a choice somebody made, and this is a full one nothing here can see.
            // Editing is refused rather than offered empty, because an Update from here would
            // replace a newer version's answer with this build's idea of nothing.
            decodeTemplateButtons(current.buttons, current.buttonsVersion) == null ->
                TemplateUnavailable(
                    modifier = bodyModifier,
                    title = "This version was written by a newer build.",
                    detail = "Its buttons are stored in a form this build cannot read, so there " +
                        "is no editor here for them. The template itself is unchanged."
                )

            else -> TemplateEditor(
                template = template,
                current = current,
                viewModel = viewModel,
                onBack = onBack
            )
        }
    }
}

/**
 * The editor itself, over one version that has already been read.
 *
 * Split out of [TemplateDetailScreen] so that the three answers above it — not read yet, gone, and
 * unreadable — are decided once, before anything here assumes there is a version to edit.
 */
@Composable
private fun TemplateEditor(
    template: TemplateEntity,
    current: TemplateVersionEntity,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    // Re-seeded when the rows move under them, which happens for one reason: an Update landed. The
    // drafts then become the version it just wrote, the bar goes back to saying nothing has changed,
    // and the page stays where it is rather than closing — renaming a button and re-scoping a
    // template in one visit is an ordinary thing to want, and being thrown back to the list after
    // each of them is not.
    var name by rememberSaveable(template.id, template.name) { mutableStateOf(template.name) }
    var scopeText by rememberSaveable(template.id, current.version, current.scope) {
        mutableStateOf(current.scope)
    }
    var buttonsText by rememberSaveable(template.id, current.version, current.buttons) {
        mutableStateOf(current.buttons)
    }

    val scope = remember(scopeText) { decodeTemplateScope(scopeText) }
    val kinds = scope.kinds
    // The draft may say more than the row it started from: changing a button to a Comment writes
    // version-2 vocabulary into it. Reading it at the stored version would drop the very row the
    // change added, so the version travels with the draft rather than with the row.
    val buttonsVersion = remember(buttonsText, current.buttons, current.buttonsVersion) {
        templateDraftVersion(current.buttons, current.buttonsVersion, buttonsText)
    }
    val buttons = remember(buttonsText, buttonsVersion) {
        decodeTemplateButtons(buttonsText, buttonsVersion)
    }.orEmpty()

    fun setButtons(next: List<TemplateButton>) {
        buttonsText = encodeTemplateButtons(next)
    }

    fun toggleKind(kind: AssetKind) {
        // Arithmetic on the kinds the scope really governs, not on the chips that were drawn: a
        // scope naming a kind this band has no control for still shows in the line below, and
        // turning one of the two chips on must not quietly take the third away.
        val next = if (kind in kinds) kinds - kind else kinds + kind
        scopeText = encodeTemplateScope(scope.withKinds(next))
    }

    val nameError = settingsTextError(name)
    val changed = name != template.name || scopeText != current.scope || buttonsText != current.buttons

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
                    label = "Template name",
                    value = name,
                    onValueChange = { name = it },
                    error = nameError
                )
                HelperText(
                    "What this template is called where it is listed and attached. A name is not " +
                        "part of its identity: renaming one changes nothing about the Tasks " +
                        "already judged with it."
                )
            }

            SectionCard(title = "Scope") {
                Text("Applies to", style = MaterialTheme.typography.bodyMedium)
                ActionGroup {
                    configurableAssetKinds().forEach { kind ->
                        FilterChip(
                            selected = kind in kinds,
                            onClick = { toggleKind(kind) },
                            label = { Text(assetKindChipLabel(kind), maxLines = 1) }
                        )
                    }
                }
                StatusLine(templateScopeLabel(scope))
                templateScopeWarning(scope)?.let { HelperText(it, tone = StatusTone.Warning) }
                HelperText(
                    "Which files this template governs. Nothing chosen means it applies to no " +
                        "file at all — a template that says nothing about what it is for cannot " +
                        "be used for anything, and a file no template matches gets no chips."
                )
            }

            SectionCard(title = "Buttons") {
                buttons.forEachIndexed { index, button ->
                    // Keyed by id rather than by position: removing the first of three must not
                    // hand the second one's half-typed name to the third.
                    key(button.id) {
                        CustomActionFields(
                            title = "Button ${index + 1}",
                            action = CustomAction(button.id, button.name, button.recordValue),
                            onDisplayNameCommit = {
                                setButtons(buttons.replacing(index, button.copy(name = it)))
                            },
                            onWebhookValueCommit = {
                                setButtons(buttons.replacing(index, button.copy(recordValue = it)))
                            },
                            onRemove = { setButtons(buttons.filterNot { it.id == button.id }) },
                            buttonType = button.type,
                            onButtonTypeChange = { type ->
                                setButtons(
                                    buttons.replacing(
                                        index,
                                        button.copy(
                                            type = type,
                                            // A comment has no configured word; clearing the
                                            // field keeps a later type change from inheriting text
                                            // that never applied to it.
                                            recordValue = if (type == ButtonType.COMMENT) {
                                                ""
                                            } else {
                                                button.recordValue
                                            }
                                        )
                                    )
                                )
                            },
                            // Every keystroke reaches the draft. This page's edit is never
                            // *finished* — Update is what finishes it — so a field that waited for
                            // focus to leave would lose the last thing typed to a tap on Update,
                            // which does not move focus on touch.
                            commitAsYouType = true
                        )
                    }
                }

                Spacer(Modifier.height(ListeaDimens.RowGap))
                TextButton(onClick = { setButtons(buttons + newTemplateButton(buttons)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(ListeaDimens.RowGap))
                    Text("Add button")
                }
                HelperText(
                    "The name is what the chip says and the record value is what a payload sends. " +
                        "Neither is identity — an id is, and it is minted rather than typed — so " +
                        "renaming a button or changing what it sends leaves every Task that used " +
                        "it saying exactly what it said."
                )
            }

            Spacer(Modifier.height(ListeaDimens.SectionGap))
        }

        TemplateSaveBar(
            version = current.version,
            changed = changed,
            // A blank name is refused here rather than by the store, because the field already
            // says why and an Update that silently wrote nothing would not.
            enabled = changed && nameError == null,
            onCancel = {
                // Put back before leaving, so that *Cancel changes nothing* is a fact about this
                // page and not a bet on when Compose discards a draft it is done with.
                name = template.name
                scopeText = current.scope
                buttonsText = current.buttons
                onBack()
            },
            onUpdate = { viewModel.updateTemplate(template.id, name, scope, buttons) }
        )
    }
}

/**
 * The bar that decides whether any of this is written: what is at stake, and the two ways out.
 *
 * **Update is disabled until something differs from the stored version**, which is the honest
 * reading of a button that writes a version: pressing it with nothing to say would append a version
 * identical to the one before it, and a history of identical versions is a history nobody can use.
 * The line on the left says which of the two it is, so the disabled button is explained rather than
 * mysterious.
 *
 * Cancel **restores the drafts and leaves**. Both halves matter: leaving is what the user asked for,
 * and restoring first is what makes the promise *nothing moves* true of the page itself as well as
 * of the database — a draft is not a row, but a user who came back to a page still holding the edit
 * they cancelled would be right to think otherwise.
 */
@Composable
private fun TemplateSaveBar(
    version: Int,
    changed: Boolean,
    enabled: Boolean,
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
                    "kept, and so is what any Task was judged with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (changed) "Unsaved changes" else "No changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (changed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onUpdate, enabled = enabled) { Text("Update") }
            }
        }
    }
}

/**
 * The page when there is nothing to edit, and the reason why.
 *
 * Two states reach this and both have to say which one they are. A template that is not in the
 * database and a version written by a newer build look the same — a page with no editor on it — and
 * mean opposite things: one is a row that is gone, and the other is a row this build is too old to
 * read and must not write over.
 */
@Composable
private fun TemplateUnavailable(modifier: Modifier, title: String, detail: String) {
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

/**
 * A text field bound to a draft, one keystroke at a time.
 *
 * Deliberately not [SettingsTextField], which writes on Done or on losing focus because what it
 * edits is already stored. This one edits something that is not stored at all, so the moment the
 * text exists is the moment the draft should have it — and waiting for focus to leave would lose
 * the last thing typed to a tap on Update, which does not move focus on touch.
 *
 * Blank is still refused, and for the same reason: an unnamed template is one nobody can find.
 */
@Composable
internal fun DraftTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
}

/** [buttons] with the one at [index] replaced. The draft is a list, so an edit is a rebuild. */
private fun List<TemplateButton>.replacing(index: Int, button: TemplateButton): List<TemplateButton> =
    mapIndexed { at, existing -> if (at == index) button else existing }

/**
 * A button with a fresh id, named from the first free slot, ready for the user to rename.
 *
 * The id comes from [newActionId] — the same generator a configured tag uses — because a button id
 * has to be unique across the whole app and not merely within its template: an Asset's record is a
 * set of ids, so two templates defining the same one would make a file that matched both show one
 * template's mark under the other's chip with nothing in the stored set able to tell them apart.
 */
private fun newTemplateButton(buttons: List<TemplateButton>): TemplateButton {
    val fresh = nextCustomActionDefaults(
        buttons.map { CustomAction(it.id, it.name, it.recordValue) }
    )
    return TemplateButton(
        id = fresh.id,
        name = fresh.displayName,
        // A tag rather than a boolean: `★` is the one boolean the app ships and it is the
        // built-in's, so a button somebody adds is one whose meaning the export decides.
        type = ButtonType.TAG,
        recordValue = fresh.webhookValue
    )
}
