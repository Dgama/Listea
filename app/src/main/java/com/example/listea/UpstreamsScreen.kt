package com.example.listea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.UpstreamEntity

/**
 * The **Upstreams** tab: one card per folder material comes from.
 *
 * An Upstream is a folder the user pointed the app at — the folder itself, not its contents, and
 * not any decision taken about them. That is the whole of what this tab is, and it is deliberately
 * a short list of things to do on it.
 *
 * **What is deliberately not here.** Create Tasks, Catalog, Quick Review and Create List were all
 * on this card before v16 and none of them are about a folder. The first two answer questions about
 * Tasks, and the last two are List-shaped; a folder has no Tasks until an import runs over it, and a
 * round or a List started from one would be about material nothing has claimed yet. They live in
 * Todo now, which is where every row is something an import already wrote, and — for Create Tasks —
 * in the browser one tap below, where the folder being read is the one actually on screen.
 *
 * **Adding one is not here either.** It is in Settings, beside the other things that configure what
 * the app holds rather than what it is showing. This screen lists what has been added and opens it;
 * a second place to add one would be a second place for the grant to be taken and a second wording
 * of whatever that needs to say.
 *
 * There is no Task count on a card, and that is the point of the version rather than an omission:
 * an Upstream that reported how much had been imported from it would be claiming a relationship
 * this table does not hold. How much material there is under a folder is what opening it answers.
 */
@Composable
fun UpstreamsScreen(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel,
    /** The roots whose SAF grant the app still holds, so a card can say when one is gone. */
    heldRoots: Set<String>,
    onOpenBrowser: (UpstreamEntity) -> Unit
) {
    val upstreams by viewModel.upstreams.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        if (upstreams.isEmpty()) {
            item {
                Text("No Upstreams yet", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    "Add the folder your material is in, under Settings. Nothing is read from it " +
                        "until you open it and run Create Tasks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(upstreams, key = { it.id }) { upstream ->
            UpstreamCard(
                upstream = upstream,
                available = upstream.rootUri in heldRoots,
                onOpen = { onOpenBrowser(upstream) }
            )
        }
    }
}

/**
 * One Upstream: the folder's name, where it sits, and whether it can still be read.
 *
 * The whole card opens the folder, and the whole card is the only control on it — that is what an
 * Upstream *is*, and a card that could only be acted on through a button would make looking at the
 * material the one thing the screen for material could not do.
 *
 * Unavailable is a state and not a deletion: a folder whose grant Android no longer holds is still
 * the user's Upstream, and saying so plainly is better than a card that disappears without
 * explanation or one that offers a tap that can only fail.
 */
@Composable
private fun UpstreamCard(
    upstream: UpstreamEntity,
    available: Boolean,
    onOpen: () -> Unit
) {
    OutlinedCard(onClick = onOpen, enabled = available, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ListeaDimens.CardPadding)) {
            Text(upstream.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (upstream.relativePath.isNotEmpty()) {
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    "Inside " + upstream.relativePath.substringBefore('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (!available) {
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    "Listea no longer has access to this folder. Nothing here is deleted; add " +
                        "the folder again in Settings to browse it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
