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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil3.ImageLoader
import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewItem
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
 */
fun reviewItemInfo(
    item: ReviewItem,
    settings: AppSettings,
    listTitle: String?
): ReviewItemInfo = ReviewItemInfo(
    title = item.title,
    relativePath = item.relativePath,
    sourceUri = item.sourceUri,
    sourceMissing = item.sourceMissing,
    isCompleted = item.decisions.isCompleted,
    actionLabel = itemActionLabel(item.decisions, settings),
    listTitle = listTitle
)

/**
 * Sequential one-item-at-a-time review of a whole list, in its stored order.
 *
 * Swipe left  = mark the current item completed, then advance.
 * Swipe right = step back only; it never changes any completion state.
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
 * The session itself is three things stacked, per §6: a [TaskNavigator] that says where you are,
 * a [TaskReviewSurface] that is one Task's review, and inside that a [AssetNavigator] and a
 * [TaskReviewControls] that can each be extended without the other knowing. All this function
 * does is decide *which* Task is being shown and hand it down.
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
    var currentTaskId by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
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
    // The status and navigation bars follow Listea's own, so hiding the chrome really does give
    // the whole display to the media.
    MediaSystemBars(chromeVisible)

    // Landing on a Task is what moves the List's cursor; [id] rather than [taskId] because the
    // cursor names an entry, and §6 leaves it that way on purpose.
    fun goTo(target: ReviewItem) {
        currentTaskId = target.taskId
        onPositionChanged(target.id)
    }

    val navigation = taskNavigation(items, index)

    // The semantic-action boundary: a gesture and an explicit control both resolve to a
    // [TaskAction], and this is where an action becomes its Task-level operation. The viewer
    // arbitrates raw input against its own gestures and hands back a callback; it never learns
    // which action a swipe means, and nothing here learns which gesture produced an action.
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

    TaskNavigator(
        modifier = modifier,
        title = item.title,
        // Where you are in this queue, which for Quick Review is the folder's items and not the
        // owning list's total. Completion progress is a list's business and lives on its page.
        position = "${index + 1} / ${items.size}",
        onBack = onBack,
        chromeVisible = chromeVisible,
        // Stepping here is navigation and nothing else, which is what §7 asks of these controls
        // and what the swipe deliberately is not. Neither of them submits, completes or records:
        // a null on a side is that arrow drawn disabled, because there is nowhere to go.
        navigation = MediaNavigation(
            onPrevious = navigation.previous?.let { { dispatch(TaskAction.PreviousTask) } },
            onNext = navigation.next?.let { { dispatch(TaskAction.NextTask) } }
        )
    ) {
        TaskReviewSurface(
            item = item,
            index = index,
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            onSwipeForward = { dispatch(TaskAction.CompleteTask) },
            // Backwards is never a judgment: it steps the queue and changes nothing about the
            // Task that was left behind.
            onSwipeBack = { dispatch(TaskAction.PreviousTask) },
            onTap = { chromeVisible = !chromeVisible },
            onToggleCompleted = { completed ->
                if (completed) {
                    // The surface's own control, so checking it here is a judgment and unchecking
                    // it is not: §5 records what somebody committed, and there is no retraction
                    // for a template to record. The uncheck still has to happen — it just moves
                    // state.
                    viewModel.submitItemReview(item, terms)
                } else {
                    viewModel.setItemCompleted(item, false)
                }
            },
            onToggleAction = { action, enabled ->
                viewModel.setItemAction(item, action, enabled)
            },
            onInfo = { infoOpen = true }
        )
    }

    if (infoOpen) {
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, settings, chrome.listTitle),
            onDismiss = { infoOpen = false }
        )
    }
}

/**
 * The top of the hierarchy: which Task of the queue is in front of the user, and how to leave.
 *
 * It is the presentation the review modes already shared — [MediaShell] drawing a bar over the
 * media — with the naming made honest. What that bar was called before was the *item's*, and the
 * item was whichever of a file, a stored row and a queue entry the code happened to have in hand.
 * A navigator moves between Tasks. That is what it has ever done and all it does here.
 *
 * It renders nothing of its own. [MediaShell] holds the frame and owns the chrome-visible state,
 * because it is the thing that knows how much room the bars take and what the media is therefore
 * left with; the navigator supplies what goes *in* the bar.
 *
 * [navigation] is what makes this a navigator rather than a viewer: the bar gains explicit
 * previous/next controls beside the counter. Navigation's entry point is always an explicit
 * control and never a gesture, per §7 - the swipe bindings belong to the Task Review template and
 * mean what that template says they mean, which for the built-in one is a judgment rather than a
 * step. The two agree only about where the ends of the queue are.
 */
@Composable
fun TaskNavigator(
    modifier: Modifier,
    title: String,
    position: String,
    onBack: () -> Unit,
    chromeVisible: Boolean,
    navigation: MediaNavigation? = null,
    media: @Composable () -> Unit
) {
    MediaShell(
        modifier = modifier,
        title = title,
        position = position,
        onBack = onBack,
        chromeVisible = chromeVisible,
        navigation = navigation,
        media = media
    )
}

/**
 * One Task, reviewed: the Asset in front of the user, and the controls that judge it.
 *
 * The two halves are deliberately siblings under it rather than one calling the other. The Asset
 * side knows about pixels — zoom, pan, playback, whether there is anything to render at all — and
 * the control side knows about decisions. Nothing on the control side needs to know what the Asset
 * viewer is doing, which is what lets a second viewer be added without touching a chip.
 *
 * Behaviour is unchanged from when both were inlined in [ReviewSession]: forward is a submission,
 * back is a step, a tap toggles the chrome.
 */
@Composable
private fun TaskReviewSurface(
    item: ReviewItem,
    index: Int,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    onTap: () -> Unit,
    onToggleCompleted: (Boolean) -> Unit,
    onToggleAction: (ItemAction, Boolean) -> Unit,
    onInfo: () -> Unit
) {
    // The media fills the box and the controls are drawn *over* it, exactly as the shell draws the
    // chrome over the media. A Column that gave the bar its own room would shrink every photograph
    // and every video by the height of a row of chips, which is the layout the whole media surface
    // exists to have stopped doing.
    Box(Modifier.fillMaxSize()) {
        AssetNavigator(
            item = item,
            assets = assetsOf(item),
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            // Forward is never blocked: swiping the last item off checks it and ends the review.
            canSwipeForward = true,
            canSwipeBack = index > 0,
            onSwipeForward = onSwipeForward,
            onSwipeBack = onSwipeBack,
            onTap = onTap,
            modifier = Modifier.fillMaxSize()
        )
        TaskReviewControls(
            item = item,
            settings = settings,
            onToggleCompleted = onToggleCompleted,
            onToggleAction = onToggleAction,
            onInfo = onInfo,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * The boundary between a Task and the content it is of.
 *
 * It exists, in the composition and in the state model, for every Task — including the many that
 * have exactly one Asset and so nothing for it to navigate. That is the point of drawing it now:
 * v0.2 has one Asset per Task by construction, so the day a Task carries two, the only thing that
 * has to change is what happens *inside* this boundary. Adding it then would mean adding it to
 * every screen that shows a Task at once.
 *
 * It owns the Asset collection and is the single viewer-dispatch point. A Task with no Asset — a
 * manually typed entry — is not an error and is not hidden: it renders [PreviewPlaceholder]
 * directly, because there is no Asset for a viewer to take. A Task with its one Asset hands it to
 * the viewer, which is the fixed choice today; v0.3 adds `media -> MediaViewer / pdf -> PdfViewer`
 * here as one branch, without touching the review surface, the Task navigator, the write path or
 * the webhook boundary.
 *
 * It renders no selector while there is one Asset — a selector for one Asset is not a navigator —
 * and holds no `selectedAssetId`; that state's semantics cannot be designed before a real
 * multi-Asset Task exists to answer them.
 */
@Composable
private fun AssetNavigator(
    item: ReviewItem,
    assets: List<ReviewAsset>,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier
) {
    val asset = assets.firstOrNull()
    if (asset == null) {
        // No Asset, no viewer: a manual item is still a card in the queue, swipeable and
        // completable exactly like a file's, but with nothing to render.
        SwipeCard(
            key = item.taskId,
            canSwipeForward = canSwipeForward,
            canSwipeBack = canSwipeBack,
            onSwipeForward = onSwipeForward,
            onSwipeBack = onSwipeBack,
            modifier = modifier,
            onTap = onTap
        ) { zoom ->
            LaunchedEffect(zoom) { zoom.contentAspect = null }
            PreviewPlaceholder(Modifier.fillMaxSize().mediaZoom(zoom), "Manual item", item.title)
        }
    } else {
        AssetViewer(
            taskId = item.taskId,
            asset = asset,
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            canSwipeForward = canSwipeForward,
            canSwipeBack = canSwipeBack,
            onSwipeForward = onSwipeForward,
            onSwipeBack = onSwipeBack,
            onTap = onTap,
            modifier = modifier
        )
    }
}

/**
 * One Asset, as large as it will go: the gestures that move through the queue, and the renderer.
 *
 * It owns the zoom, the pan and the playback, and it decides nothing about the Task the Asset
 * belongs to — no completion, no action, no ViewModel. The gestures it hosts are *navigation*
 * gestures, which is why the callbacks are handed to it rather than made here: whether a swipe is
 * a judgment or just a step is a fact about the surface, not about the picture.
 *
 * It receives a [ReviewAsset], not the Task it stands in for: the viewer's boundary is the content
 * it renders, and nothing here reaches back into the Task for a field the Asset does not carry.
 *
 * The key is the task id, so landing on a Task recentres the card and drops the zoom exactly as
 * landing on an item always has — that is the behaviour, and only the identity behind it moved.
 */
@Composable
private fun AssetViewer(
    taskId: String,
    asset: ReviewAsset,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier
) {
    SwipeCard(
        key = taskId,
        canSwipeForward = canSwipeForward,
        canSwipeBack = canSwipeBack,
        onSwipeForward = onSwipeForward,
        onSwipeBack = onSwipeBack,
        modifier = modifier,
        // The one control that is not in a bar, because it is the control *for* the bars.
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
 * What can be done *about* the Task: mark it completed, tag it, ask what it is.
 *
 * The one part of the surface that writes. Every control goes through the caller's ViewModel
 * calls, which is what keeps a decision reached in Quick Review indistinguishable from one
 * reached in Review — including the webhook it may fire. Nothing here navigates; that belongs to
 * the navigator above it.
 */
@Composable
private fun TaskReviewControls(
    item: ReviewItem,
    settings: AppSettings,
    onToggleCompleted: (Boolean) -> Unit,
    onToggleAction: (ItemAction, Boolean) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    ReviewActionBar(
        item = item,
        // Observed by the caller rather than threaded through every screen: renaming an action in
        // Settings relabels the chips immediately, with no restart and no item touched.
        settings = settings,
        onToggleCompleted = onToggleCompleted,
        onToggleAction = onToggleAction,
        onInfo = onInfo,
        modifier = modifier
    )
}

/**
 * Everything the media surface stopped saying about the item in front of the user.
 *
 * The queue-side facts are already in memory on the [ReviewItem], so [reviewItemInfo] costs
 * nothing. Type, size and modified time are not stored anywhere and are read once, here, while
 * the sheet is open — never per card and never while swiping.
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
fun ReviewItemInfoSheet(info: ReviewItemInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val facts by produceState(MediaFacts(), info.sourceUri) {
        value = info.sourceUri?.let { readMediaFacts(context, it) } ?: MediaFacts()
    }

    ItemInfoSheet(
        title = info.title,
        onDismiss = onDismiss,
        rows = buildList {
            info.relativePath?.let { add(InfoField("Path in source folder", it)) }
            info.listTitle?.let { add(InfoField("List", it)) }
            add(InfoField("Status", if (info.isCompleted) "Checked" else "Not checked"))
            add(InfoField("Actions", info.actionLabel ?: "None"))
            when {
                info.sourceUri == null -> add(InfoField("Source", "Manual item — no file"))
                info.sourceMissing ->
                    add(InfoField("Source", "Missing from the source folder"))
            }
            addAll(mediaFactRows(facts, info.title))
        },
        footer = info.sourceUri
            ?.takeIf { !info.sourceMissing }
            ?.let { uri -> { MediaFileActions(sourceUri = uri, name = info.title) } }
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
    return (items.firstOrNull { !it.decisions.isCompleted } ?: items.first()).taskId
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
 * One piece of content a Task is presented through, reduced to what the presentation needs.
 *
 * The Asset-shaped value the viewer boundary is handed. It carries the source location, a display
 * name, whether the file is still there, and the cached size/modified measurements the Info sheet
 * and the arrangement already read — and nothing else. There is deliberately no persisted kind: the
 * media classifier stays the one that decides image/GIF/video behaviour, and a stored kind arrives
 * only when a second viewer actually needs one.
 */
data class ReviewAsset(
    val sourceUri: String,
    val name: String,
    val sourceMissing: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/**
 * The Assets a Task is presented through, for the viewer boundary.
 *
 * Zero for a Task with nothing to present — a manual item — and one for everything v0.2 can create,
 * since the media workflow is one Asset per Task. In v0.4 this becomes a DAO-backed read of
 * `assets WHERE taskId = ?`; everything above it already speaks in a collection, so it compiles
 * untouched.
 */
fun assetsOf(item: ReviewItem): List<ReviewAsset> =
    item.sourceUri?.let { uri ->
        listOf(
            ReviewAsset(
                sourceUri = uri,
                name = item.title,
                sourceMissing = item.sourceMissing,
                sizeBytes = item.sizeBytes,
                lastModified = item.lastModified
            )
        )
    } ?: emptyList()

/**
 * Places one Asset in front of the shared [MediaPreview].
 *
 * A source that has vanished falls through to a placeholder and stays swipeable; anything with a
 * readable source is handed to the same renderer the Folder browser uses. A Task with no Asset at
 * all never reaches here — [AssetNavigator] renders that placeholder itself, because there is no
 * Asset for a viewer to take.
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
