package com.example.listea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The registered Sources, each with its importer and the status of its last run.
 *
 * A Source is the thing an import happens *from*, not the thing reviewed; this screen lists them so
 * the catalog, Quick Review and Create List have a place to be reached from once they exist. It
 * owns nothing — the rows are read, and deleting a Source is deliberately not offered.
 */
@Composable
fun SourcesScreen(modifier: Modifier = Modifier, viewModel: ListsViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()

    if (sources.isEmpty()) {
        Column(modifier.fillMaxSize().padding(ListeaDimens.PagePadding)) {
            Text("No Sources yet", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Text(
                "Import a folder from the Folder tab to bring material into Listea.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        items(sources, key = { it.id }) { source ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(ListeaDimens.CardPadding)) {
                    Text(
                        source.relativePath.ifEmpty { "Selected folder" },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(ListeaDimens.CompactGap))
                    Text(
                        source.importerKind + " · " + (source.lastImportStatus ?: "never run"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
