package com.example.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.listea.domain.ProfilePrompt
import com.example.listea.domain.PromptOption

/**
 * The first structured Viewer: a `profile`/`1` Prompt drawn as a card.
 *
 * It draws only. The choice is emitted through [onSelect] as a domain [PromptOption]; committing is
 * the template's business, and this composable has no DAO, no navigation and no completion state.
 * That split is what makes a Like tap the same kind of fact as a sampled point: the viewer reports,
 * the template decides what catching it means.
 *
 * A missing required photo is the caller's refusal to make, not a disabled card here: the page still
 * shows everything else, and [photoMissing] only chooses the placeholder's sentence. The submission
 * boundary is what refuses a judgment the content cannot support.
 *
 * [interactive] is false on the read-only viewing surfaces. The same card is drawn, with the same
 * labels, so a Preview and a Review cannot disagree about what the Prompt says.
 */
@Composable
internal fun ProfilePromptPage(
    prompt: ProfilePrompt,
    photoUri: String?,
    photoMissing: Boolean,
    selectedOptionId: String?,
    interactive: Boolean,
    onSelect: (PromptOption) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding, vertical = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = prompt.stem,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (photoUri != null) {
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
        } else if (photoMissing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "The photo is missing from the source.",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            prompt.fields.forEach { field ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = field.label,
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = field.value,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            prompt.options.forEach { option ->
                ProfileOptionPill(
                    option = option,
                    selected = option.id == selectedOptionId,
                    enabled = interactive,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Why the options cannot be taken. It sits below them because it explains the refusals
        // above it, and it is drawn even when read-only so the Preview says the same thing.
        notice?.let { message ->
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * The page a Prompt shows when the Viewer has nothing it can draw: no payload yet, a kind this
 * build cannot read, a revision it does not know, or content its template refuses.
 *
 * It is still a page of the Task — the counter and the guide stay honest about where the Task's
 * content sits — rather than a card that silently disappears and leaves a queue of photographs.
 */
@Composable
internal fun PromptNoticePage(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * One option, painted the chrome's inverted-selection way: chosen is white on black text, the rest
 * are outlined. The whole card is a target, because a Like has to be one tap.
 */
@Composable
private fun ProfileOptionPill(
    option: PromptOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.7f else 0.3f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = option.label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
