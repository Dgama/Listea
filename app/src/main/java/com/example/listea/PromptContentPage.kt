package com.example.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.listea.data.TaskResourceView
import com.example.listea.domain.ProfilePrompt
import com.example.listea.domain.PromptContent
import com.example.listea.domain.PromptOption
import com.example.listea.domain.VocabularyPrompt

/**
 * The one Content-page dispatcher.
 *
 * A viewing surface hands this a parsed [PromptContent] and the Task's Resources; it does not know
 * which Viewer drew the Task and does not parse a body itself. Adding a Viewer is therefore one
 * capability row, one parser and one arm here — not a fourth copy of profile derivation in a
 * screen.
 *
 * [reveal] asks a drill card to show its meaning. Review passes false until the answer is on
 * record; the read-only surfaces pass true, because a look is not an attempt.
 *
 * [player] is the screen's existing ExoPlayer. A pronunciation Resource uses it and stops when the
 * Content page leaves composition, so a word cannot play over a video page or into the next Task.
 */
@Composable
internal fun PromptContentPage(
    content: PromptContent,
    resources: List<TaskResourceView>,
    selectedOptionId: String?,
    interactive: Boolean,
    reveal: Boolean,
    onSelect: (PromptOption) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
    player: ExoPlayer? = null
) {
    when (content) {
        is ProfilePrompt -> {
            val photo = remember(content, resources) {
                content.photoResource?.let { key ->
                    resources.firstOrNull { it.resourceKey == key }
                }
            }
            val photoMissing = content.photoResource != null && (photo == null || photo.missing)
            ProfilePromptPage(
                prompt = content,
                photoUri = photo?.takeIf { !it.missing }?.uri,
                photoMissing = photoMissing,
                selectedOptionId = selectedOptionId,
                interactive = interactive,
                notice = notice,
                onSelect = onSelect,
                modifier = modifier
            )
        }

        is VocabularyPrompt -> {
            // A Resource used by this card must be the kind the card can play. A same-named image
            // is not a pronunciation and reads as missing rather than being handed to the player.
            val audio = remember(content, resources) {
                content.audioResource?.let { key ->
                    resources.firstOrNull { it.resourceKey == key && it.kind == "audio" }
                }
            }
            val audioMissing = content.audioResource != null && (audio == null || audio.missing)
            VocabularyPromptPage(
                prompt = content,
                audioUri = audio?.takeIf { !it.missing }?.uri,
                audioMissing = audioMissing,
                selectedOptionId = selectedOptionId,
                interactive = interactive,
                reveal = reveal,
                notice = notice,
                player = player,
                onSelect = onSelect,
                modifier = modifier
            )
        }
    }
}

/**
 * A `vocabulary`/`1` Prompt drawn as a drill card.
 *
 * The options are full-width rows in declared order, with the same inverted-selection convention
 * as the profile card: chosen is white on black, the rest are outlined. Nothing here sorts or
 * shuffles; the order on screen is the order the body declared.
 */
@Composable
internal fun VocabularyPromptPage(
    prompt: VocabularyPrompt,
    audioUri: String?,
    audioMissing: Boolean,
    selectedOptionId: String?,
    interactive: Boolean,
    reveal: Boolean,
    onSelect: (PromptOption) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
    player: ExoPlayer? = null
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

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = prompt.word,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            prompt.phonetic?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        if (prompt.audioResource != null) {
            PronunciationControl(
                uri = audioUri,
                missing = audioMissing,
                player = player
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt.options.forEach { option ->
                VocabularyOptionRow(
                    option = option,
                    selected = option.id == selectedOptionId,
                    enabled = interactive,
                    onClick = { onSelect(option) }
                )
            }
        }

        if (reveal) {
            val chosen = selectedOptionId?.let { id -> prompt.options.firstOrNull { it.id == id } }
            val correct = prompt.answerOptionId?.let { id ->
                prompt.options.firstOrNull { it.id == id }
            }
            if (prompt.definition != null || prompt.example != null || correct != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.definition?.let {
                        RevealLine(label = "Meaning", text = it)
                    }
                    prompt.example?.let {
                        RevealLine(label = "Example", text = it, italic = true)
                    }
                    correct?.let {
                        val sentence = when {
                            chosen == null -> "Correct answer: ${it.label}"
                            chosen.id == it.id -> "Correct: ${it.label}"
                            else -> "Correct answer: ${it.label}"
                        }
                        Text(
                            text = sentence,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Why the options cannot be taken, or why the pronunciation is unavailable. It sits below
        // the controls because it explains the refusals above it, and read-only surfaces draw it
        // too so a Preview and a Review say the same thing.
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
private fun RevealLine(label: String, text: String, italic: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        )
    }
}

/**
 * The pronunciation control: one button, one optional line of refusal.
 *
 * It drives the screen's player rather than owning one. The effect stops and clears only when this
 * control leaves the page, which is what keeps a word from playing over the next file; a pause is
 * a pause, and the same page can resume it.
 */
@Composable
private fun PronunciationControl(
    uri: String?,
    missing: Boolean,
    player: ExoPlayer?
) {
    var playing by remember(uri, player) { mutableStateOf(false) }
    DisposableEffect(uri, player) {
        val exo = player
        if (exo == null || uri == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) playing = false
                }
            }
            exo.addListener(listener)
            onDispose {
                exo.removeListener(listener)
                exo.stop()
                exo.clearMediaItems()
            }
        }
    }

    val enabled = uri != null && player != null && !missing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.10f))
                .clickable(enabled = enabled) {
                    val exo = player ?: return@clickable
                    if (playing) {
                        exo.pause()
                    } else {
                        exo.setMediaItem(MediaItem.fromUri(uri!!))
                        exo.prepare()
                        exo.play()
                    }
                }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause pronunciation" else "Play pronunciation",
                tint = if (enabled) Color.Black else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (playing) "Pause" else "Listen",
                color = if (enabled) Color.Black else Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (!enabled) {
            Text(
                text = "Pronunciation is missing from the source.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VocabularyOptionRow(
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
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = option.label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
