package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.listea.data.ReviewItem
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.Templates

/**
 * What a Quick Review round was started on: a browsed folder, or one level of a Todo.
 *
 * The two are one type rather than two screens because everything after the queue is resolved is
 * identical — the gestures, the actions, the media renderer, the webhook path — and a second screen
 * would be a second copy of all of it. Which one this is, [sourceId] decides: set, the round is over
 * [scope]'s own Tasks in that Todo and [title] names the Todo; null, the round is over
 * [folderPath]'s own files and [title] is unused.
 *
 * **[scope] is a level, not a subtree.** Browsing a folder queues its own files and not everything
 * beneath it, and a Todo is browsed the same way: standing at a Todo's root — where there are only
 * folders — is not a place a round can start, and standing in one of them queues exactly the Tasks
 * that live there. That is the same [directChildOf] narrowing the catalog descends by, which is why
 * a scope's catalogue and a Quick Review over it can never disagree about what is in it.
 *
 * [folderPath] is carried either way and is what the round reports as its provenance
 * ([ReviewContext.InFolder]) and what the webhook payload names.
 */
data class QuickReviewTarget(
    val rootUri: String,
    val folderPath: String,
    val sourceId: String? = null,
    /** Which level of the Todo this round covers. Ignored when [sourceId] is null. */
    val scope: String = "",
    /**
     * Whether the Todo shows one flat list of every Task it holds.
     *
     * A level of a flat Todo is all of it, so a round here is the whole Todo rather than the Tasks
     * whose paths happen to sit one segment deep. Carried rather than re-read because it is an
     * answer the Create Tasks run gave, and it is the same answer the catalog is drawing from.
     */
    val flat: Boolean = false,
    val title: String = ""
)

/**
 * Scoped review: one folder's direct files, or one level of a Todo's Tasks.
 *
 * This is not a second review system, and it is no longer a view of a first one either. It resolves
 * its scope — reading a folder's listing for the one, its catalog rows for the other — attaches the
 * decisions already held about them, and hands that queue to the same [ReviewSession] full Review
 * uses, so gestures, actions, the media renderer and the webhook path are literally the same code.
 * Which scope it is is [QuickReviewTarget.sourceId]; there is no second screen for the second case,
 * because nothing past the queue differs.
 *
 * No List is involved at any point. Nothing is created, nothing is replaced, and a List that
 * happens to cover this material is not consulted, not scanned and not written to. It will still
 * show every decision made here the instant it is made, because a decision belongs to the file and
 * both are reading the same state — and if that decision completes it, its webhook fires exactly
 * as it would have from its own page.
 *
 * The folder queue is that folder's direct files, narrowed and ordered by the filter and sort the
 * folder page was showing: pick a subset there, press Quick Review, and that subset is what gets
 * edited. Subfolders are places to navigate to, not items. The Todo queue is the same shape one
 * level up: the Tasks that lie exactly at the scope being browsed, and none of the ones below it.
 * It is narrowed and ordered by the shared arrangement rather than by a folder's own — the one
 * place the two differ in what they read, and not at all in what they do with it.
 *
 * Nothing persists a position here — Quick Review is temporary and starts at the first unchecked
 * item every time it opens.
 */
@Composable
fun QuickReviewScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    target: QuickReviewTarget,
    onExit: () -> Unit
) {
    // Bumped by the import itself, once it has committed, so the flow re-collects and finds the
    // Tasks it just created rather than the empty catalog it replaced. Counting taps here instead -
    // which is what this did - re-subscribed on the tap, while the dialog was still open and the
    // import transaction had not run: the re-read came back empty, and the screen went on saying
    // "nothing imported here" over a folder that was by then imported.
    val importVersion by viewModel.importVersion.collectAsStateWithLifecycle()
    val sourceId = target.sourceId
    // Keyed on the import version either way: a run that commits while the round is open changes
    // which Tasks exist, and a Todo round reading a stale catalog would review rows the import has
    // since replaced.
    val reviewFlow = remember(target, importVersion) {
        if (sourceId == null) {
            viewModel.observeFolderReview(target.rootUri, target.folderPath)
        } else {
            viewModel.observeTaskReview(sourceId, target.rootUri, target.scope, target.flat)
        }
    }
    val review by reviewFlow.collectAsStateWithLifecycle(initialValue = FolderReview.Resolving)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    // What the round calls where it is. A Todo round standing at one of the Todo's own levels is
    // named for that level, because "Quick Review · Holiday" is the answer to which folder is on
    // screen and the Todo's name alone would be the answer to a different question.
    //
    // The Todo's own root is [QuickReviewTarget.folderPath] — the Source's `relativePath`, which is
    // where its run started and not the empty path — so standing there is the Todo's name and
    // standing anywhere below it is the folder's.
    val folderName = if (sourceId == null) {
        quickReviewFolderName(target.folderPath)
    } else if (target.scope == target.folderPath) {
        target.title
    } else {
        target.scope.substringAfterLast('/')
    }
    // Keyed by scope as well as by Todo: two levels of one Todo are two different rounds, and a
    // session key that named only the Todo would hand the second level the first one's frozen queue.
    val sessionKey = if (sourceId == null) {
        "quick:${target.rootUri}:${target.folderPath}"
    } else {
        "todo:$sourceId:${target.scope}"
    }

    // Read here rather than carried in from the Folder screen, and read from the same two sources
    // that screen reads: the arrangement is stored per folder (or shared), so both sides resolve
    // the same value and there is no third copy to fall out of step with the header controls.
    //
    // A Todo round takes the app-wide arrangement. There is no folder being looked at to have
    // stored one of its own, and reading the one stored against the Upstream's folder would be
    // borrowing a setting the user chose for *browsing* that folder to filter a queue of Tasks that
    // came out of it. The global arrangement is the one that is about the app rather than about a
    // place, which is the only kind a Todo has access to — and it is still what decides the filter,
    // the sort and the order of the round, so the user's setting is honoured, just not the folder's.
    val arrangement = if (sourceId == null) {
        resolveArrangement(
            arrangements,
            folderArrangementKey(target.rootUri, target.folderPath),
            settings.sharedFileArrangement
        )
    } else {
        arrangements.global
    }

    when (val current = review) {
        FolderReview.Resolving -> {
            BackHandler { onExit() }
            ReviewFrame(modifier, "Quick Review", onBack = onExit) { CircularProgressIndicator() }
        }

        FolderReview.Unavailable -> {
            BackHandler { onExit() }
            QuickReviewNotice(
                modifier = modifier,
                headline = "This folder could not be read",
                detail = folderName,
                onExit = onExit
            )
        }

        is FolderReview.NotImported -> {
            BackHandler { onExit() }
            ReviewFrame(modifier, "Quick Review", onBack = onExit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Nothing imported here yet",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Quick Review needs an import first. Import this folder as " +
                            current.importer + " at " + current.sourceRoot + "?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(ListeaDimens.SectionGap))
                    Row {
                        TextButton(onClick = onExit) { Text("Not now") }
                        Spacer(Modifier.width(ListeaDimens.RowGap))
                        Button(onClick = {
                            viewModel.importFolderForReview(target.rootUri, target.folderPath)
                        }) { Text("Import") }
                    }
                }
            }
        }

        is FolderReview.Ready -> QuickReviewQueue(
            modifier = modifier,
            viewModel = viewModel,
            target = target,
            name = folderName,
            sessionKey = sessionKey,
            files = current.items,
            scopeNotice = current.notice,
            arrangement = arrangement,
            uncheckedOnly = settings.reviewUncheckedOnly,
            onExit = onExit
        )
    }
}

/**
 * The resolved folder, once there is something to review or a reason there is not.
 *
 * Split from the state handling above so the round is only snapshotted when a real queue exists:
 * [rememberRoundQueue] freezes the round on entry, and entering that composition with an empty
 * list while the folder is still being read would freeze nothing at all.
 */
@Composable
private fun QuickReviewQueue(
    modifier: Modifier,
    viewModel: ListsViewModel,
    target: QuickReviewTarget,
    name: String,
    sessionKey: String,
    files: List<ReviewItem>,
    scopeNotice: ScopeNotice?,
    arrangement: FileArrangement,
    uncheckedOnly: Boolean,
    onExit: () -> Unit
) {
    val now = rememberRoundClock(sessionKey)

    // The subset picked out on the Folder screen, in the order it was shown there, minus anything
    // the unchecked-only setting removes. The very same call a List's Review makes: the two modes
    // differ in where the queue came from, not in what a filter and a sort do to one.
    val queue = rememberReviewRound(files, sessionKey, arrangement, uncheckedOnly, now)

    // Set when finishing the queue delivers, so leaving afterwards does not deliver a second time.
    val delivered = rememberSessionDelivered(sessionKey)
    val leave = leaveReview(
        queue = queue,
        delivered = delivered,
        onLeave = { queued ->
            viewModel.onFolderReviewExited(target.rootUri, target.folderPath, queued)
        },
        onBack = onExit
    )
    BackHandler { leave() }

    if (queue.isEmpty()) {
        // Nothing is pulled from deeper folders and no filter is quietly relaxed to avoid this: an
        // empty round is simply empty, and saying *which* of the several reasons emptied it is the
        // only way the user can tell what to change.
        //
        // The filter is asked on its own here rather than inferred from "a filter is set", because
        // a filter that matched ten files whose reviews are all done is a different situation from
        // one that matched none, and only one of them is fixed by changing it.
        //
        // [scopeNotice] is asked first and is the more specific answer when it exists: a folder
        // holding only part of a Task, or a Task whose content cannot be presented, is a reason the
        // filter cannot change and a reason the user has never been told.
        val matched = files.count { matchesFilter(reviewFacts(it), arrangement, now) }
        val copy = emptyRoundCopy(
            scopeNotice = scopeNotice,
            name = name,
            fileCount = files.size,
            matched = matched,
            filterIsDefault = arrangement.isFilterDefault
        )
        QuickReviewNotice(
            modifier = modifier,
            headline = copy.headline,
            detail = copy.detail,
            onExit = leave
        )
        return
    }

    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = queue,
        // Reopening on a different scope is a different session; leaving discards it entirely,
        // which is what makes every entry start at the first unchecked item again.
        sessionKey = sessionKey,
        chrome = ReviewChrome(
            context = "Quick Review · " + name,
            // No list to name: these decisions land on the files themselves, and claiming a list
            // here would be claiming something about where they went.
            listTitle = null,
            completeHeadline = "Quick Review complete",
            exitLabel = "Exit",
            // The scope's numbers, not the round's. A filtered round that reaches its end has not
            // checked off everything the scope holds, and saying it had would be the one lie that
            // matters. "The scope" is the Todo for a Todo round and the folder for a folder one,
            // and [files] is that scope's queue either way.
            progress = ReviewProgress(
                completed = files.count { it.decisions.isCompleted },
                total = files.size
            )
        ),
        // The folder this was reached from — the Todo's Upstream folder, for a Todo round — which
        // is the whole of what provenance means here: a round that landed on no List. There is no
        // List row to read a template version off, so its judgments are recorded under the only
        // Task Review this build defines.
        terms = ReviewTerms(
            context = ReviewContext.InFolder(target.rootUri, target.folderPath),
            template = Templates.MediaRapidReview.ref
        ),
        // Null start point and a no-op position sink: a folder round is temporary and has nowhere
        // to resume from, so normal Review resumes exactly where it always would.
        persistedItemId = null,
        onPositionChanged = {},
        onRestart = null,
        // Finishing a folder's queue is Quick Review's own completion, and the only moment it
        // has to report. Whether that actually sends is the ViewModel's decision.
        onQueueFinished = { queued ->
            delivered.value = true
            viewModel.onQuickReviewFinished(target.rootUri, target.folderPath, queued)
        },
        onBack = leave
    )
}

/** A folder with nothing to show, said plainly, with the one thing to do about it. */
@Composable
private fun QuickReviewNotice(
    modifier: Modifier,
    headline: String,
    detail: String,
    onExit: () -> Unit
) {
    ReviewFrame(modifier, "Quick Review", onBack = onExit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            Button(onClick = onExit) { Text("Exit") }
        }
    }
}

/** The browsed folder's own name, or the root's stand-in when the path is the root itself. */
private fun quickReviewFolderName(folderPath: String): String =
    folderPath.substringAfterLast('/').ifEmpty { "Selected folder" }
