package com.example.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.listea.domain.FormField
import com.example.listea.domain.FormFieldType
import com.example.listea.domain.FormPrompt
import com.example.listea.domain.MAX_FORM_TEXT
import com.example.listea.domain.PromptOption
import com.example.listea.domain.visibleFields

/**
 * A `form`/`1` Prompt drawn as a short questionnaire.
 *
 * The card draws the fields that are visible **right now**: a condition is evaluated against the
 * answers the parent holds, so choosing `No` hides the follow-up before the person can answer it.
 * Hiding and showing is a drawing decision — the Task still has one part and one body — and the
 * parent never has to know which field was gated.
 *
 * [answers] is lifted to the caller because the commit comes from the frame's own Submit control,
 * not from a button inside the card: the control and the card must read one draft, or a submission
 * could carry a field the page had already hidden.
 */
@Composable
internal fun FormPromptPage(
    prompt: FormPrompt,
    answers: Map<String, String>,
    interactive: Boolean,
    onAnswer: (FormField, String) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding, vertical = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = prompt.stem,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        prompt.visibleFields(answers).forEach { field ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (field.required) "${field.label} *" else field.label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                when (field.type) {
                    FormFieldType.ChoiceSingle -> field.options.forEach { option ->
                        FormOptionRow(
                            option = option,
                            selected = answers[field.key] == option.id,
                            enabled = interactive,
                            onClick = { onAnswer(field, option.id) }
                        )
                    }

                    FormFieldType.Text -> OutlinedTextField(
                        value = answers[field.key].orEmpty(),
                        onValueChange = { text ->
                            if (text.length <= MAX_FORM_TEXT) onAnswer(field, text)
                        },
                        enabled = interactive,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.7f),
                            cursorColor = Color.White,
                            focusedBorderColor = Color.White.copy(alpha = 0.9f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            disabledBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        )
                    )
                }
            }
        }

        notice?.let { message ->
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FormOptionRow(
    option: PromptOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.7f else 0.3f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = option.label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
