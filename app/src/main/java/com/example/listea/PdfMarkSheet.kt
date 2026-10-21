package com.example.listea

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.listea.domain.MarkKind

/**
 * The marks under a tapped point, or every mark the document could not place.
 *
 * **A list rather than a choice.** Overlapping marks are ordinary — a comment and a tag on the same
 * sentence — and a finger that landed on both has not said which one it meant. The sheet names each
 * one by its tool, says where it sits, and offers the two operations a mark has: edit a comment, or
 * delete any mark. Deleting removes the mark from Listea; the PDF is never rewritten, which is the
 * sentence the confirmation has to say because it is the one thing a user could reasonably fear.
 *
 * The rows are rendered from the viewer's live resolution, not from a snapshot taken when the sheet
 * opened: an edit made here updates the text in place, and a mark hidden by a template change
 * leaves the sheet rather than staying as a stale row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfMarkSheet(
    marks: List<ResolvedMark>,
    onDismiss: () -> Unit,
    onEdit: ((String, String) -> Unit)?,
    onDelete: (String) -> Unit
) {
    var editing by remember { mutableStateOf<ResolvedMark?>(null) }
    var deleting by remember { mutableStateOf<ResolvedMark?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ListeaDimens.PagePadding,
                    end = ListeaDimens.PagePadding,
                    bottom = ListeaDimens.SectionGap
                )
                .testTag("pdf-mark-sheet")
        ) {
            Text(
                if (marks.size == 1) "Mark" else "${marks.size} marks",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            marks.forEach { resolved ->
                MarkRow(
                    resolved = resolved,
                    onEdit = onEdit?.let { edit -> { editing = resolved } },
                    onDelete = { deleting = resolved }
                )
                Spacer(Modifier.height(ListeaDimens.SectionGap))
            }
        }
    }

    editing?.let { resolved ->
        EditCommentDialog(
            current = resolved.mark.value,
            onDismiss = { editing = null },
            onSave = { text ->
                onEdit?.invoke(resolved.mark.id, text)
                editing = null
            }
        )
    }

    deleting?.let { resolved ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = {
                Text(
                    if (resolved.mark.kind == MarkKind.COMMENT) {
                        "Delete this comment?"
                    } else {
                        "Delete this tag?"
                    }
                )
            },
            text = {
                Text(
                    "It is removed from Listea. The PDF file is not changed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(resolved.mark.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

/** One mark: what made it, where it sits, what it says, and the two things it can be done to. */
@Composable
private fun MarkRow(
    resolved: ResolvedMark,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit
) {
    val mark = resolved.mark
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                resolved.tool.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                resolved.pageIndex?.let { "Page ${it + 1}" } ?: "Not in this document",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "“${mark.anchor.exactText}”",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            mark.value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (mark.kind == MarkKind.COMMENT) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
        Row {
            if (mark.kind == MarkKind.COMMENT && onEdit != null) {
                TextButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** A comment's text, edited in place. Save is disabled while the field is blank. */
@Composable
private fun EditCommentDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit comment") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("pdf-mark-edit-field")
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim()) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
