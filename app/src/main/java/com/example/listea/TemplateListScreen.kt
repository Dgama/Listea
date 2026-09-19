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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope

/**
 * Every template Listea has, newest name first, and the way into one.
 *
 * The page the release is about: before it, *what to record about a file* was a blob configured in
 * two places and owned by nobody — **Settings → Asset controls** edited an app-wide default, the
 * Create Tasks dialog edited one Todo's answer, and neither was a thing the user could name, find
 * again or point at. A template is a row now, so this is a list, and each row is a page.
 *
 * **It is a list you read and open, and it does not yet make anything.** A lineage is minted by
 * whatever has something to name — the upgrade that brings a Todo's own buttons across, and an
 * import that attaches one — so there is no *new template* button here. Authoring a template from
 * a blank page is a real thing to want and it is not this step's; adding a button now would be a
 * screen with nothing behind it.
 *
 * The rows show what the template *is*, not what it does. There is no count of Tasks judged with
 * it and no "used by" line, because nothing attaches one yet: a number here would be a claim about
 * a relationship that does not exist.
 */
@Composable
fun TemplateListScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val versions by viewModel.templateVersions.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    ListeaNestedScaffold(
        modifier = modifier,
        title = "Templates",
        backLabel = "Settings",
        onBack = onBack,
        trailing = {
            // Named only once there is something to name: a count drawn from a table that has not
            // been read yet would say "0 templates" over an install that has one.
            templates?.let {
                Text(
                    countLabel(it.size, "template"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { bodyModifier ->
        val loaded = templates
        // Nothing read yet, and that is a blank body rather than a message: the two are a frame
        // apart and only one of them is worth words.
        if (loaded == null) return@ListeaNestedScaffold

        if (loaded.isEmpty()) {
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(ListeaDimens.PagePadding)
                ) {
                    Text(
                        "No templates",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    // Said plainly rather than left as an empty page. An install always has the
                    // built-in, so nobody should ever read this, and someone who does should come
                    // away knowing that what they are looking at is wrong rather than new.
                    Text(
                        "Every install starts with the built-in media asset control. This list is " +
                            "empty only if the database this build opened has none.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
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
            items(loaded, key = { it.id }) { template ->
                TemplateCard(
                    template = template,
                    // The versions arrive ordered by lineage and then by number, so the last one
                    // carrying this id is the newest — which is the one the page behind this row
                    // edits, and the one whose number the row offers as "current".
                    current = versions.orEmpty().lastOrNull { it.templateId == template.id },
                    onOpen = { onOpen(template.id) }
                )
            }
        }
    }
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
            StatusLine(
                current?.let { templateScopeLabel(decodeTemplateScope(it.scope)) }
                    ?: "No version yet"
            )
            StatusLine(templateVersionLine(current))
        }
    }
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

    val buttons = decodeTemplateButtons(version.buttons, version.buttonsVersion)
        ?: return "Version ${version.version} · written by a newer build"

    return countLabel(buttons.size, "button") + " · version ${version.version}"
}
