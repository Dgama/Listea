package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil3.ImageLoader
import com.example.listea.data.AssetDecisions
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.taskDecisions
import com.example.listea.domain.ListId
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.TaskAction
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef

/**
 * The few strings that distinguish one review context from another. Everything else — the queue
 * mechanics, the gestures, the action bar, the media renderer and the Info sheet — is shared.
 *
 * Nothing in here reaches the media surface itself. The top bar says what the item is and where
 * you are in the queue, and it reads the same whether you arrived from a list or from a folder;
 * [context] and [listTitle] are for the completion screen and the Info sheet, which is where
 * saying which queue this is belongs.
 */
data class ReviewChrome(
    /** What finished, on the completion screen: a list's title, or the folder being reviewed. */
    val context: String,
    /**
     * The list the queue came from, named for the Info sheet. Null for a folder review, which
     * came from no list: its decisions land on the files, and the sheet says nothing rather
     * than naming a list that had no part in it.
     */
    val listTitle: String?,
    val completeHeadline: String,
    val exitLabel: String,
    /** What the completion screen reports, which is never the round's own arithmetic. */
    val progress: ReviewProgress
)

/**
 * What a round is conducted under: where it was reached from, and the exact template version its
 * judgments are made under.
 *
 * Supplied by the screen that opened the round, because it is the only thing that knows either. A
 * List's template lives on its own row and can differ between two Lists; a folder round belongs to
 * no List at all, so there is nothing to read one off and the built-in Task Review is what applies.
 * The two travel together because a submission needs both, and a caller that supplied one and
 * guessed the other would be recording a judgment under terms nobody chose.
 */
data class ReviewTerms(val context: ReviewContext, val template: TemplateVersionRef)

/**
 * How far along the *whole* list or folder is, passed in rather than counted from the queue.
 *
 * The distinction only shows up once a round can be narrowed, and then it shows up sharply: a
 * hundred-item list filtered down to fifty and reviewed to the end is fifty of a hundred done, not
 * fifty of fifty. Counting the queue would have reported a finished list every time, which is the
 * one thing the completion screen must never say wrongly — so the count comes from whoever owns
 * the whole set, and this type exists to make handing it over the only way to get one.
 */
data class ReviewProgress(val completed: Int, val total: Int)

/**
 * Everything the Info sheet says about the item on screen that Listea already knows.
 *
 * All of it is on the [ReviewItem] the queue is already holding, so building this costs
 * nothing and can happen per card. What is *not* here — type, size, modified time — is not stored
 * anywhere and is read from the provider by [MediaFacts], once, when the sheet opens.
 */
data class ReviewItemInfo(
    val title: String,
    val relativePath: String?,
    val sourceUri: String?,
    val sourceMissing: Boolean,
    val isCompleted: Boolean,
    val actionLabel: String?,
    val listTitle: String?
)

/**
 * Assembles what the current card knows about itself. Cheap: no queries, no file access.
 *
 * [listTitle] is null for a folder review, which lands on no list at all — the Info sheet says so
 * rather than naming one, because naming one would be a claim about where the decision went.
 *
 * [decisions] is the **page's** pair record and not the Task's, which is the sheet telling the
 * truth about the file in front of the user: the actions it lists are the ones set on this file,
 * so a button set on a video does not appear in a photograph's sheet. They are *named* through the
 * Task's control — [TemplateResolver.controlForTask] — so that an id set under a template the
 * page's own kind no longer admits still reads as the label the user knows rather than as a bare
 * id. The same pair of choices the badge row makes, for the same reasons.
 */
fun reviewItemInfo(
    item: ReviewItem,
    decisions: AssetDecisions,
    resolver: TemplateResolver,
    listTitle: String?
): ReviewItemInfo = ReviewItemInfo(
    title = item.title,
    relativePath = item.relativePath,
    sourceUri = item.sourceUri,
    sourceMissing = item.sourceMissing,
    isCompleted = item.isCompleted,
    actionLabel = itemActionLabel(decisions, resolver.controlForTask(item.assetControls)),
    listTitle = listTitle
)

/**
 * Sequential one-item-at-a-time review of a whole list, in its stored order.
 *
 * Swipe left  = advance one page; off a Task's last page, mark it completed and advance.
 * Swipe right = step back one page; off a Task's first page, step back a Task. It never changes
 *               any completion state.
 *
 * The session walks Tasks, but the position it remembers is the membership row's: what the list
 * page resumes is which entry the user was on, and only a row id names one of those.
 */
@Composable
fun ReviewScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resolver by viewModel.templateResolver.collectAsStateWithLifecycle()
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    // Read here rather than carried in from the detail page, and read from the same two sources
    // that page reads, so both resolve the same value with no third copy to drift.
    val arrangement = resolveArrangement(
        arrangements,
        listArrangementKey(listId),
        settings.sharedFileArrangement
    )

    val current = detail
    if (current == null) {
        BackHandler { onBack() }
        ReviewFrame(modifier, title = "", onBack = onBack) { CircularProgressIndicator() }
        return
    }

    val items = current.items
    val now = rememberRoundClock(listId)
    // Fixed for this round, so checking something off does not pull it out from under the user,
    // and so the subset picked out on the detail page is what this round walks.
    val queue = rememberReviewRound(items, listId, arrangement, settings.reviewUncheckedOnly, now)

    // A round begins here, and again on "Review again": whatever completed before now belongs to
    // an earlier one and must not be allowed to silence this one's report.
    LaunchedEffect(listId) { viewModel.onReviewStarted(listId) }

    // Set when finishing the queue reports the round, so leaving afterwards does not report it
    // twice. Whether anything is actually sent is the ViewModel's decision.
    val delivered = rememberSessionDelivered(listId)
    val leave = leaveReview(
        queue = queue,
        delivered = delivered,
        onLeave = { queued -> viewModel.onReviewExited(listId, queued.map { it.id }) },
        onBack = onBack
    )
    BackHandler { leave() }

    if (queue.isEmpty()) {
        ReviewFrame(modifier, current.list.title, onBack = leave) {
            // Which of the three emptied it, because only one of them is fixed by changing the
            // filter and the user cannot tell them apart from an empty screen.
            val matched = items.count { matchesFilter(reviewFacts(it), arrangement, now) }
            Text(
                when {
                    items.isEmpty() -> "This list has no items to review."
                    matched == 0 -> "No items match this list's filter."
                    arrangement.isFilterDefault -> "Every item in this list is already checked."
                    else -> "Every item this list's filter keeps is already checked."
                }
            )
        }
        return
    }

    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = queue,
        sessionKey = listId,
        chrome = ReviewChrome(
            context = current.list.title,
            listTitle = current.list.title,
            completeHeadline = "Review complete",
            exitLabel = "Back to list",
            // The list's own numbers, not the round's: a filtered round that finishes has not
            // finished the list, and the page it returns to will still say so.
            progress = ReviewProgress(current.completedCount, items.size)
        ),
        // This List's own template version, off its row rather than assumed, so a judgment is
        // recorded under the definition that was actually in force when it was made.
        terms = ReviewTerms(
            context = ReviewContext.InList(ListId(listId)),
            template = TemplateVersionRef(
                TemplateId(current.list.taskReviewTemplateId),
                current.list.taskReviewTemplateVersion
            )
        ),
        // The setting decides what is read on entry, not what is written: the position keeps
        // being recorded below either way, so switching resuming back on picks up where Review
        // actually got to rather than wherever it was when it was switched off.
        persistedItemId = current.list.reviewCurrentItemId
            .takeIf { settings.rememberReviewPosition },
        onPositionChanged = { viewModel.setReviewPosition(listId, it) },
        onRestart = {
            viewModel.setReviewPosition(listId, queue.first().id)
            // A new round, which reports for itself: re-open it and let it deliver again.
            viewModel.onReviewStarted(listId)
            delivered.value = false
        },
        // Reported the moment the queue is done rather than waiting for the user to leave the
        // completion screen. The round is over either way, and a report that has already left the
        // device cannot be lost to the app being killed while that screen sits there.
        onQueueFinished = { queued ->
            delivered.value = true
            viewModel.onReviewExited(listId, queued.map { it.id })
        },
        onBack = leave
    )
}

/**
 * The queue a review actually walks: the page's filter and sort, then the unchecked-only setting,
 * snapshotted once and held for the round.
 *
 * The narrowing is a snapshot by item id, deliberately not a live filter. Checking an item is the
 * normal way through a review, and a live filter would make the current card vanish under the
 * user's thumb, renumber the queue and take back the ability to swipe backwards. Leaving and
 * re-entering is what starts the next round.
 *
 * Frozen even when nothing is being narrowed, because the *order* is part of what is being held:
 * it lives in the snapshot and nowhere else, and would be lost the moment the database re-emitted.
 * The cost is that an item added to the list mid-round joins the next round rather than this one,
 * which is the same promise the filter makes — this round is over the set that was there when it
 * started.
 *
 * Taken on the first load that has anything in it, because the items arrive from the database a
 * frame later than the screen does and snapshotting an empty list would queue nothing at all.
 * Kept across rotation with the rest of the session, and dropped with it on the way out.
 */
@Composable
fun rememberReviewRound(
    items: List<ReviewItem>,
    sessionKey: Any,
    arrangement: FileArrangement,
    uncheckedOnly: Boolean,
    now: Long
): List<ReviewItem> = rememberRoundQueue(
    items = items,
    sessionKey = sessionKey,
    narrowed = true,
    round = {
        if (items.isEmpty()) {
            null
        } else {
            reviewRound(items, arrangement, uncheckedOnly, now).map { it.taskId }.toTypedArray()
        }
    }
)

/**
 * The clock a round's freshness filter is judged against, fixed for the round.
 *
 * Read once and kept, so "Today" cannot quietly become "yesterday" while the user is part way
 * through a queue, and so the same instant decides the queue and the message shown when that
 * queue comes out empty. Saved with the session; leaving and re-entering asks the clock again.
 */
@Composable
fun rememberRoundClock(sessionKey: Any): Long =
    rememberSaveable(sessionKey) { System.currentTimeMillis() }

/**
 * The snapshot machinery behind every narrowed round, whichever mode decided to narrow one.
 *
 * [round] is asked once — on the first composition that can answer — for the ids this round
 * queues, in the order it walks them. Every later emission of [items] is put through that
 * snapshot instead of being re-narrowed, which is what stops a card vanishing under the user's
 * thumb the moment they check it. Returning null from [round] means "not yet": the items arrive
 * from the database a frame after the screen does, and snapshotting an empty list would queue
 * nothing at all.
 *
 * [narrowed] false hands [items] straight back and never records anything, so a mode that does
 * not narrow costs nothing and, having stored no snapshot, does not start honouring a stale one
 * if the setting behind it is switched off mid-session.
 *
 * The snapshot holds task ids. [ReviewItem.id] cannot be one, because the two modes number the
 * same file differently — a list row's number here, a registration's number there — so a session
 * identified by it would be two sessions wearing one name.
 */
@Composable
fun rememberRoundQueue(
    items: List<ReviewItem>,
    sessionKey: Any,
    narrowed: Boolean,
    round: () -> Array<String>?
): List<ReviewItem> {
    // Held as a list rather than the array it is asked for, because a `Array<String>` is not one
    // of the types a saved-state bundle can carry and `rememberSaveable` would refuse it at
    // runtime rather than at the call site.
    var queuedIds by rememberSaveable(sessionKey) { mutableStateOf<List<String>?>(null) }

    val snapshot = when {
        !narrowed -> null
        queuedIds != null -> queuedIds!!.toTypedArray()
        else -> round()
    }
    // Recorded after the fact rather than during composition, so the first pass already renders
    // the narrowed queue instead of flashing the whole list and correcting itself.
    SideEffect {
        if (narrowed && queuedIds == null && snapshot != null) queuedIds = snapshot.toList()
    }

    val queued = snapshot ?: return items
    return remember(items, queued.toList()) { queuedItems(items, queued) }
}

/**
 * [items] narrowed to the round's snapshot, in the snapshot's own order, ignoring tasks that are
 * no longer there: a re-sync or a cleanup during a review shortens the queue rather than breaking
 * it. An item checked since the snapshot was taken stays in, which is the whole point.
 *
 * The order comes from [queuedIds] rather than from [items] because the snapshot is the only
 * record of it. Normal Review takes its snapshot in stored order, so nothing moves there; Quick
 * Review takes its snapshot in the order the folder page was showing, and that order would
 * otherwise be lost the moment the database re-emitted.
 */
fun queuedItems(items: List<ReviewItem>, queuedIds: Array<String>): List<ReviewItem> {
    val byTask = items.associateBy { it.taskId }
    return queuedIds.mapNotNull { byTask[it] }
}

/**
 * Which way a session can step from [index], and what stepping there would be.
 *
 * Pure and separate from the screen, because what these controls may do is a decision about the
 * review model and not about layout. A step changes which Task is shown and moves the List's
 * resume cursor; it does not complete anything, submit anything or append anything, and §7 makes
 * that a property of navigation itself rather than of one control. Holding the boundaries here is
 * what lets that be asserted without a device.
 *
 * The forward end is closed rather than special. Stepping past the last Task would be the only
 * way to reach the completion screen by navigation, and §7 rules it out: the round ends when the
 * last Task is *completed*, not when it is arrived at. So the last Task is where the forward
 * control stops, and completing it is still the thing that finishes the round.
 *
 * Each side is the adjacent Task, or null where there is none - which is the same statement as
 * "that control is disabled", so the boundary is not stored twice and cannot disagree with itself.
 * An index outside [items] has neither, which is the honest answer for a queue that is empty or
 * has not resolved yet.
 */
internal data class TaskNavigation(val previous: ReviewItem?, val next: ReviewItem?) {
    val canGoPrevious: Boolean get() = previous != null
    val canGoNext: Boolean get() = next != null
}

internal fun taskNavigation(items: List<ReviewItem>, index: Int): TaskNavigation {
    val canGoPrevious = index in 1..items.lastIndex
    val canGoNext = index >= 0 && index < items.lastIndex
    return TaskNavigation(
        previous = if (canGoPrevious) items[index - 1] else null,
        next = if (canGoNext) items[index + 1] else null
    )
}

/**
 * Wraps leaving a review so the on-exit webhook fires at most once, whichever way the user goes:
 * the top bar arrow, the system back gesture, or Exit on the completion screen.
 *
 * A queue that already delivered on reaching its end does not deliver again on the way out, and
 * an empty queue never delivers at all — there was no round to report.
 *
 * Both review modes now report when their queue finishes, so this guard is what stops the exit
 * that follows from reporting the same round again. Whether anything is actually sent is the
 * ViewModel's decision; this only says a review was left.
 */
fun leaveReview(
    queue: List<ReviewItem>,
    delivered: State<Boolean>,
    onLeave: (List<ReviewItem>) -> Unit,
    onBack: () -> Unit
): () -> Unit = {
    if (!delivered.value && queue.isNotEmpty()) onLeave(queue)
    onBack()
}

/** Whether this review session has already delivered, kept across rotation like the session. */
@Composable
fun rememberSessionDelivered(sessionKey: Any): MutableState<Boolean> =
    rememberSaveable(sessionKey) { mutableStateOf(false) }

/**
 * The review experience itself, over whatever queue it is handed: the whole list for normal
 * Review, one folder's direct files for Quick Review. There is one implementation of the
 * gestures, the action row and the media renderer, and one underlying item state — both modes
 * write the same persistent per-file decisions through the same ViewModel calls, so completion,
 * actions and the list completion webhook behave identically wherever the swipe came from.
 *
 * [persistedItemId] is the resume point, or null to always start at the first unchecked item.
 * [onPositionChanged] is where a mode decides whether moving is worth remembering: Quick Review
 * passes an empty lambda, which is what leaves the list's own review position untouched.
 * A null [onRestart] hides the restart offer on the completion screen.
 *
 * [onQueueFinished] is called with the queue's item ids the moment the last card is swiped off,
 * for the mode that reports a finished queue of its own. Null for a mode that does not.
 *
 * The session itself is one [MediaShell] with its three bands filled in, per §6: a [MediaNavigator]
 * that says where you are at both levels, a [TaskCompletionBar] that decides the Task, a
 * [ReviewAssetControls] band that tags the file, and an [AssetRun] between them that is the file
 * itself. All this function does is decide *which* Task is being shown, *which* of its files, and
 * hand both down.
 *
 * The gesture is arbitrated here rather than in the asset run, because it is the one thing that
 * belongs to both levels: [AssetRun] spends it on pages and reports back only when the run is at
 * an end, so what reaches [dispatch] is already a Task-level action. The explicit controls take
 * the same route — the bar's arrows call [dispatch] too — so the two cannot drift apart.
 */
@Composable
fun ReviewSession(
    modifier: Modifier,
    viewModel: ListsViewModel,
    items: List<ReviewItem>,
    sessionKey: Any,
    chrome: ReviewChrome,
    terms: ReviewTerms,
    persistedItemId: Long?,
    onPositionChanged: (Long) -> Unit,
    onRestart: (() -> Unit)?,
    onQueueFinished: ((List<ReviewItem>) -> Unit)? = null,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // The two bands below resolve against this rather than against [settings], which is the whole of
    // what v0.5.4 moved: a chip row is a template's buttons now, and there is no app-wide tag list
    // left to fall back to.
    val resolver by viewModel.templateResolver.collectAsStateWithLifecycle()
    var currentTaskId by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    // Which file of the Task is on screen. Keyed on the Task rather than reset by an effect, so
    // arriving at another one shows its *first* file on the very first frame instead of flashing
    // the page the last Task was left on. Saved with the session, so a rotation keeps the page.
    //
    // Held here, beside the Task the queue is on, because the navigator bar states both at once:
    // the two levels are one control group, and a selection living inside the viewer would have to
    // be reported back up for the bar to draw it and driven back down for the arrows to move it.
    var assetIndex by rememberSaveable(currentTaskId) { mutableStateOf(0) }
    var finished by rememberSaveable(sessionKey) { mutableStateOf(false) }
    // Not saved: reopening Info after a rotation would be answering a question nobody asked.
    var infoOpen by remember { mutableStateOf(false) }
    // Whether the bars are up, toggled by a tap on the media. Belongs to the session and not to
    // the card: a user who put the chrome away to look at photographs full-bleed meant it for the
    // rest of the queue, not for one item, so swiping does not bring it back and does not take it
    // away. Saved with the session so a rotation does not either.
    var chromeVisible by rememberSaveable(sessionKey) { mutableStateOf(true) }

    // One player for the whole screen rather than one per card, so nothing leaks between items.
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val imageLoader = remember(context) { buildImageLoader(context) }

    // Resolve the start position, and repair it if the Task it pointed at has gone.
    LaunchedEffect(items, currentTaskId) {
        if (items.none { it.taskId == currentTaskId }) {
            currentTaskId = resumeTaskId(persistedItemId, items)
        }
    }

    val index = items.indexOfFirst { it.taskId == currentTaskId }
    if (index < 0) {
        ReviewFrame(modifier, chrome.context, onBack) { CircularProgressIndicator() }
        return
    }

    if (finished) {
        ReviewFinished(
            modifier = modifier,
            headline = chrome.completeHeadline,
            title = chrome.context,
            completed = chrome.progress.completed,
            total = chrome.progress.total,
            roundSize = items.size,
            exitLabel = chrome.exitLabel,
            onBack = onBack,
            onRestart = onRestart?.let { restart ->
                {
                    finished = false
                    currentTaskId = items.first().taskId
                    restart()
                }
            }
        )
        return
    }

    val item = items[index]
    // The frame takes real room here, so the system bars are never hidden: there is no full-bleed
    // picture for them to be hiding *from*. They are still forced light, which is the other half
    // of what this does — what sits under them is the navigator's scrim, not the app's surface.
    MediaSystemBars(chromeVisible = true)

    // Landing on a Task is what moves the List's cursor; [id] rather than [taskId] because the
    // cursor names an entry, and §6 leaves it that way on purpose.
    fun goTo(target: ReviewItem) {
        currentTaskId = target.taskId
        onPositionChanged(target.id)
    }

    val navigation = taskNavigation(items, index)

    // The semantic-action boundary, and it sits a level above the Task on purpose: a swipe and an
    // explicit control both arrive here as a [TaskAction], and this is where one becomes its
    // Task-level operation. Nothing below it knows what a swipe means — the Asset run spends the
    // gesture on pages and only reports a leave at a run's end — and nothing here learns which
    // gesture produced the action, so the arrows and the swipe cannot drift apart.
    fun dispatch(action: TaskAction) {
        when (action) {
            TaskAction.CompleteTask -> {
                // A submission, because a review surface's completion is a judgment. Everything
                // downstream of the write — the completion transition, the round bookkeeping, the
                // webhook — is the same path a checkbox takes, so a list that becomes complete
                // here still fires exactly as it always did.
                viewModel.submitItemReview(item, terms)
                if (index == items.lastIndex) {
                    finished = true
                    // Announced after the check above, so the delivery it may lead to sees
                    // the item that was just swiped as checked.
                    onQueueFinished?.invoke(items)
                } else {
                    goTo(items[index + 1])
                }
            }

            TaskAction.PreviousTask -> navigation.previous?.let { goTo(it) }

            TaskAction.NextTask -> navigation.next?.let { goTo(it) }
        }
    }

    // The page state is clamped rather than repaired: a re-sync can take a file away under a
    // selection that was already made, and the honest answer is to show what is still there
    // instead of writing state from a composition.
    val assets = assetsOf(item)
    val currentAsset = assetIndex.coerceIn(0, maxOf(assets.lastIndex, 0))

    // The bands' two answers, resolved here because this is the only place that knows both which
    // Task is on screen and which of its files. The chip row belongs to the *file* — its kind comes
    // off the Asset's own basename rather than the Task's, so paging from a photograph to a video
    // changes it, which is the release. The badge row belongs to the *Task* and is resolved over
    // every kind the Task is configured for, because it is drawn under the Task's name with no
    // particular file on screen. See [ItemActionTags].
    //
    // `name` and not `relativePath`: a classifier wants the basename, and it is what
    // `reviewBlockFor` already hands `assetViewerAvailable` for the same file.
    val page = assets.getOrNull(currentAsset)
    val kind = assetKindOf(page?.name.orEmpty())
    // Null is a real answer here and is resolved to the empty row rather than to a default: nothing
    // attached admits this file's kind, so nothing offers it a button. See [TemplateResolver].
    val control = resolver.controlFor(item.assetControls, kind) ?: ResolvedAssetControl.Empty
    val taskControl = resolver.controlForTask(item.assetControls)
    // The page's own record, and the whole of v0.5.1 on the surface. `assets` is empty only for a
    // Task with no Asset at all — a manual item — where there is no pair to have recorded anything
    // and the middle band is not drawn in any case.
    val pageDecisions = page?.decisions ?: AssetDecisions()
    // A level with nothing to move between is not a level, so a one-file Task keeps the bar it has
    // always had: no file name beside the title, no `(1 / 1)`, no inner pair of arrows.
    val hasAssetLevel = assets.size > 1
    val assetForward = assetStep(assets.size, currentAsset, forward = true)
    val assetBack = assetStep(assets.size, currentAsset, forward = false)

    // The middle band, and null where there is no file for it to be about. A Task with no Asset —
    // a legacy manual item — has no pair, so it has nowhere to record an action and no kind to
    // offer one for; a row of chips there would be a control whose every tap went nowhere. It keeps
    // its name, its position and its checkbox, which is the whole of what the other two bands are
    // for. See v0.5.1's out-of-scope note.
    //
    // [control] is this page's own, resolved from the Task's record and the file's kind, and
    // [ReviewAsset.decisions] is this page's own record — the pair, both halves of it, which is
    // what the band is.
    val assetBand: (@Composable () -> Unit)? = page?.let { current ->
        {
            ReviewAssetControls(
                decisions = current.decisions,
                control = control,
                onToggleAction = { action, enabled ->
                    viewModel.setItemAction(item, current, action, enabled)
                },
                onInfo = { infoOpen = true }
            )
        }
    }

    MediaShell(
        modifier = modifier,
        navigator = MediaNavigator(
            title = item.title,
            onBack = onBack,
            subtitle = assets.getOrNull(currentAsset)?.name?.takeIf { hasAssetLevel },
            // What the *Task* is marked with, under its name and above the file's — the fold of
            // every pair it presents, so a tag set on its video is legible while its photograph is
            // on screen, and a tap that clears the picture does not take the answer with it.
            //
            // It is the one row on this surface that is not the page's, and v0.5.1 is what makes
            // that a choice rather than an accident: the chip row below draws this pair's record,
            // this draws all of them. Both are named through a control resolved over the Task's
            // kinds, so a chip and its badge cannot name the same tag differently. It is the live
            // record: the round re-reads its pairs on every write now, so toggling a chip fills
            // this in the same frame.
            tags = { ItemActionTags(item.taskDecisions, taskControl) },
            // The queue's own position, and inside it the page's. For Quick Review this is the
            // folder's items and not the owning list's total: completion progress is a list's
            // business and lives on its page.
            counter = if (hasAssetLevel) {
                "${index + 1}(${currentAsset + 1}/${assets.size})/${items.size}"
            } else {
                "${index + 1} / ${items.size}"
            },
            // Stepping here is navigation and nothing else, which is what §7 asks of these
            // controls and what the swipe deliberately is not. Neither of them submits, completes
            // or records: a null on a side is that arrow drawn disabled, because there is nowhere
            // to go.
            onPrevious = navigation.previous?.let { { dispatch(TaskAction.PreviousTask) } },
            onNext = navigation.next?.let { { dispatch(TaskAction.NextTask) } },
            onPreviousAsset = assetBack?.let { page -> { assetIndex = page } },
            onNextAsset = assetForward?.let { page -> { assetIndex = page } },
            hasAssetSteps = hasAssetLevel
        ),
        chromeVisible = chromeVisible,
        // The checkbox never leaves, however far the file's own controls are put away: it is the
        // one thing a review is for, and a round cannot be finished by a tap that hid the control
        // that finishes it.
        framePersists = true,
        taskBar = {
            TaskCompletionBar(
                item = item,
                reviewBlocked = item.reviewBlocked,
                onToggleCompleted = { completed ->
                    if (completed) {
                        // The frame's own control, so checking it here is a judgment and
                        // unchecking it is not: §5 records what somebody committed, and there is
                        // no retraction for a template to record. The uncheck still has to
                        // happen — it just moves state.
                        viewModel.submitItemReview(item, terms)
                    } else {
                        viewModel.setItemCompleted(item, false)
                    }
                }
            )
        },
        assetControls = assetBand,
    ) {
        AssetRun(
            item = item,
            selectedIndex = currentAsset,
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            // A blocked Task cannot be swiped forward into a judgment; the end of the queue is
            // already this exact state, so it is the same inert forward edge, not a special one.
            // Its untouchable content is still browsable, which is why this gates only the end of
            // the run and not the run itself.
            canLeaveForward = item.reviewBlocked == null,
            canLeaveBack = navigation.canGoPrevious,
            onLeaveForward = { dispatch(TaskAction.CompleteTask) },
            // Backwards is never a judgment: it steps the queue and changes nothing about the
            // Task that was left behind.
            onLeaveBack = { dispatch(TaskAction.PreviousTask) },
            onSelectAsset = { assetIndex = it },
            onTap = { chromeVisible = !chromeVisible },
            modifier = Modifier.fillMaxSize()
        )
    }

    if (infoOpen) {
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, pageDecisions, resolver, chrome.listTitle),
            // The file the sheet's rows and its two offers are about. Without it a Task standing
            // for several files has no single source to save or share and the offers disappear —
            // which is exactly what a folder-as-Task used to do.
            asset = page,
            onDismiss = { infoOpen = false }
        )
    }
}

/**
 * The boundary between a Task and the content it is of, and the run of pages inside it.
 *
 * It exists, in the composition and in the state model, for every Task — including the many that
 * have exactly one Asset and so nothing for it to navigate. That is the point of drawing it:
 * v0.2 had one Asset per Task by construction, and the day a Task carries two, what changes is
 * only what happens *inside* this boundary. Adding it later would have meant adding it to every
 * screen that shows a Task at once.
 *
 * It reads the Asset collection and the single viewer-dispatch point. A Task with no Asset — a
 * manually typed entry — is not an error and is not hidden: it renders [PreviewPlaceholder]
 * directly, because there is no Asset for a viewer to take; v0.3 adds
 * `media -> MediaViewer / pdf -> PdfViewer` here as one branch, without touching the write path or
 * the webhook boundary.
 *
 * **The Task is the container and the Asset is the page.** A horizontal swipe moves a page at a
 * time and leaves the Task only at a run's two ends, through [onLeaveForward] and [onLeaveBack].
 * That is what keeps the two levels distinguishable: on a one-Asset Task — still the overwhelming
 * majority — there is no interior page, so every swipe leaves and nothing about it changes.
 * Browsing a Task's files is not a judgment, and the caller is never asked to choose between the
 * two.
 *
 * The selection is the caller's, not this composable's. It is drawn in the navigator bar, above
 * the media, where the two levels sit side by side with the queue's own position — and a page
 * marker that could be moved from two places would have to be reported up and driven back down
 * anyway, which is a state holder with extra steps. What this owns is the gesture: [onSelectAsset]
 * is a page, and [onLeaveForward] and [onLeaveBack] are the ends of the run.
 */
@Composable
fun AssetRun(
    item: ReviewItem,
    selectedIndex: Int,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    canLeaveForward: Boolean,
    canLeaveBack: Boolean,
    onLeaveForward: () -> Unit,
    onLeaveBack: () -> Unit,
    onSelectAsset: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier
) {
    val assets = assetsOf(item)
    val asset = assets.getOrNull(selectedIndex)

    if (asset == null) {
        // No Asset, no viewer: a manual item is still a card in the queue, swipeable and
        // completable exactly like a file's, but with nothing to render.
        SwipeCard(
            key = item.taskId,
            canSwipeForward = canLeaveForward,
            canSwipeBack = canLeaveBack,
            onSwipeForward = onLeaveForward,
            onSwipeBack = onLeaveBack,
            modifier = modifier,
            onTap = onTap
        ) { zoom ->
            LaunchedEffect(zoom) { zoom.contentAspect = null }
            PreviewPlaceholder(Modifier.fillMaxSize().mediaZoom(zoom), "Manual item", item.title)
        }
        return
    }

    // Asked once per composition and read by the card and by both directions of the gesture, so
    // the three cannot disagree about there being a page to move to.
    val forward = assetStep(assets.size, selectedIndex, forward = true)
    val back = assetStep(assets.size, selectedIndex, forward = false)

    SwipeCard(
        // The page and not just the Task: the key is what recentres the card and drops the zoom,
        // so every file in a run arrives at its own fitted size and a swipe that stayed inside the
        // Task still animates rather than leaving the card off-screen.
        key = item.taskId to selectedIndex,
        canSwipeForward = forward != null || canLeaveForward,
        canSwipeBack = back != null || canLeaveBack,
        onSwipeForward = { if (forward != null) onSelectAsset(forward) else onLeaveForward() },
        onSwipeBack = { if (back != null) onSelectAsset(back) else onLeaveBack() },
        modifier = modifier,
        onTap = onTap
    ) { zoom ->
        ItemPreview(
            asset = asset,
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            zoom = zoom,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Which page of a Task a horizontal swipe moves to, or null when the swipe leaves the Task.
 *
 * Forward off the last Asset is the Task-level action and back off the first is the Task-level
 * step; what either of those *means* is the caller's business, which is why this says only that a
 * swipe leaves. A Task with nothing to page never has an interior step, so every swipe leaves —
 * and a single-Asset Task, still the overwhelming majority, behaves exactly as it did before there
 * was anything to page.
 *
 * An [index] outside the Task's pages has nowhere to go either: that is the honest answer for a
 * selection left behind by a Task whose Assets changed under it.
 */
internal fun assetStep(assetCount: Int, index: Int, forward: Boolean): Int? {
    if (index !in 0 until assetCount) return null
    val next = if (forward) index + 1 else index - 1
    return next.takeIf { it in 0 until assetCount }
}

/**
 * Everything the media surface stopped saying about the file in front of the user.
 *
 * The queue-side facts are already in memory on the [ReviewItem], so [reviewItemInfo] costs
 * nothing. Type, size and modified time are not stored anywhere and are read once, here, while
 * the sheet is open — never per card and never while swiping.
 *
 * **It is about the [asset], not the Task.** The sheet is reached from the file's own control row,
 * so what it names, describes and offers to save has to be the file on screen. A Task standing for
 * a folder has several files and no source of its own, which is exactly why reading the item alone
 * left a folder-as-Task with the two offers this sheet exists to make. The item still supplies what
 * only it knows — whether it is checked, which actions are on it, which List it belongs to — and is
 * the fallback for a Task with no Asset at all, where the item *is* the only thing there is.
 *
 * Shared with [ListItemViewerScreen], which shows the same items without being able to change
 * them. Nothing in here writes to the *item*, so a read-only screen can say exactly as much about
 * one as Review can — and can offer the same Save, which copies the file into Listea's gallery
 * album without touching the item, its completion or its actions.
 *
 * Save is offered only when there is a file to copy: a manual item has no source at all, and one
 * whose source has gone missing has a URI that no longer resolves. Both say so in a row above
 * instead of showing a button that could only fail.
 */
@Composable
fun ReviewItemInfoSheet(info: ReviewItemInfo, asset: ReviewAsset?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sourceUri = asset?.sourceUri?.takeIf { it.isNotEmpty() } ?: info.sourceUri
    val sourceMissing = asset?.sourceMissing ?: info.sourceMissing
    val title = asset?.name?.takeIf { it.isNotEmpty() } ?: info.title
    val facts by produceState(MediaFacts(), sourceUri) {
        value = sourceUri?.let { readMediaFacts(context, it) } ?: MediaFacts()
    }

    ItemInfoSheet(
        title = title,
        onDismiss = onDismiss,
        rows = buildList {
            info.relativePath?.let { add(InfoField("Path in source folder", it)) }
            info.listTitle?.let { add(InfoField("List", it)) }
            add(InfoField("Status", if (info.isCompleted) "Checked" else "Not checked"))
            add(InfoField("Actions", info.actionLabel ?: "None"))
            when {
                sourceUri == null -> add(InfoField("Source", "Manual item — no file"))
                sourceMissing ->
                    add(InfoField("Source", "Missing from the source folder"))
            }
            addAll(mediaFactRows(facts, title))
        },
        footer = sourceUri
            ?.takeIf { !sourceMissing }
            ?.let { uri -> { MediaFileActions(sourceUri = uri, name = title) } }
    )
}

/**
 * Resume rules: a still-valid persisted row wins, even if it is already checked. Otherwise start
 * at the first unchecked item, and if everything is checked, at the very first item. Passing a
 * null [persisted] is how Quick Review always starts at the first unchecked item.
 *
 * Answers with a task id because that is what the session holds, but takes the persisted value as
 * the row id it is on the List: which entry the user was on is a fact about their membership, and
 * which Task that entry is of is a fact about the judgment — see §6 of the plan, and decision 6.
 */
internal fun resumeTaskId(persisted: Long?, items: List<ReviewItem>): String {
    items.firstOrNull { it.id == persisted }?.let { return it.taskId }
    return (items.firstOrNull { !it.isCompleted } ?: items.first()).taskId
}

/**
 * Loading, and the queues that turn out to have nothing in them.
 *
 * Same thin bar as the media screens so entering a review never jumps between two layouts, minus
 * the position, because there is no queue to be anywhere in yet.
 *
 * It carries its own system-bar inset. The shell stops insetting nested screens so that a media
 * screen can run edge to edge, and this is ordinary content that still has to sit below the
 * status bar.
 */
@Composable
fun ReviewFrame(
    modifier: Modifier,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(end = ListeaDimens.PagePadding)
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * What a finished round leaves on screen: what it was, how far the whole thing has got, and — only
 * when they differ — how much of it this round actually covered.
 *
 * The two numbers are kept apart on purpose. "50 / 100 processed" is the list, and it is the one
 * that decides whether the list is done. "This round covered 50 items" is the queue, and it is
 * there so a user who forgot a filter was on can see why finishing did not finish anything.
 * Running them together as one heading is what made them look like a contradiction.
 */
@Composable
private fun ReviewFinished(
    modifier: Modifier,
    headline: String,
    title: String,
    completed: Int,
    total: Int,
    roundSize: Int,
    exitLabel: String,
    onBack: () -> Unit,
    onRestart: (() -> Unit)?
) {
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(headline, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(ListeaDimens.RowGap))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$completed / $total processed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (roundSize < total) {
            Text(
                "This round covered " + countLabel(roundSize, "item"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(ListeaDimens.SectionGap))
        Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
            Button(onClick = onBack) { Text(exitLabel) }
            onRestart?.let { restart ->
                OutlinedButton(onClick = restart) { Text("Review again") }
            }
        }
    }
}

/**
 * The Assets a Task is presented through, for the viewer boundary.
 *
 * A read of the collection the queue already resolved onto the [ReviewItem], rather than a
 * reconstruction from the item's own fields. That is the seam v0.2 left for a multi-Asset Task: the
 * viewer boundary has always spoken in a collection, and now the collection is carried rather than
 * invented from the one-Asset shape.
 */
fun assetsOf(item: ReviewItem): List<ReviewAsset> = item.assets

/**
 * Places one Asset in front of the shared [MediaPreview].
 *
 * A source that has vanished falls through to a placeholder and stays swipeable; anything with a
 * readable source is handed to the same renderer the Folder browser uses. A Task with no Asset at
 * all never reaches here — [AssetRun] renders that placeholder itself, because there is no Asset
 * for a viewer to take.
 *
 * The placeholder takes the zoom transform as well as the pictures do. It is not media and there is
 * nothing in it worth a closer look, but leaving it out would mean a pinch on one silently changed
 * a scale that nothing then honoured - and a scale above one is what decides whether the next drag
 * pages or pans.
 *
 * Shared with [ListItemViewerScreen] so that an item looks the same wherever it is opened from.
 * It renders and nothing else — no completion, no actions, no ViewModel — which is what lets a
 * read-only screen use it unchanged.
 */
@Composable
fun ItemPreview(
    asset: ReviewAsset,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    zoom: MediaZoomState,
    modifier: Modifier
) {
    if (asset.sourceMissing) {
        LaunchedEffect(zoom) { zoom.contentAspect = null }
        PreviewPlaceholder(modifier.mediaZoom(zoom), "Source missing", asset.name)
        return
    }

    MediaPreview(
        uri = asset.sourceUri,
        name = asset.name,
        player = player,
        imageLoader = imageLoader,
        modifier = modifier,
        videoAutoplay = settings.videoAutoplay,
        videoStartMuted = settings.videoStartMuted,
        zoom = zoom
    )
}
