package com.example.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.listea.domain.AssetKind
import com.example.listea.domain.AssetMark
import com.example.listea.domain.ListId
import com.example.listea.domain.MarkKind
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.encodeValue
import com.example.listea.domain.parse
import com.example.listea.domain.parseEventValue
import com.example.listea.domain.decodePrompt
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TASK_REVIEW_COMPLETE_CONTROL
import com.example.listea.domain.TaskAction
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.ViewerEventType
import com.example.listea.domain.CommitPolicy
import com.example.listea.domain.markKind
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
    val listTitle: String?,
    /**
     * The Package this Task answers, when it came from one. Carried so the sheet names the
     * Package by its manifest title rather than printing the synthetic catalog key that sits in
     * [relativePath]; see [taskProvenance].
     */
    val packageKey: String? = null,
    val packageTitle: String? = null
)

/**
 * Which level of a Task an Info sheet is describing.
 *
 * The hierarchy is Task → Asset → media viewer, with the add-on a bounded Task-level property
 * that is *drawn* as a page. The Info button belongs to the asset level, and the sheet it opens
 * describes that level only: a file's sheet says what is true of the file, and the add-on page's
 * sheet says what is true of the question it is on. Mixing the Task's questionnaire into every
 * file's sheet made the two levels read as one. [Content] is the Prompt body's own level — a
 * Task-level page with no file, and so no marks, no media facts and no Save.
 */
enum class ReviewInfoLevel { Content, Asset, AddOn }

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
    listTitle = listTitle,
    packageKey = item.packageKey,
    packageTitle = item.packageTitle
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
    // The add-on tables, resolved once per emission: what this session asks is a reference on the
    // Task, and the questions behind it are whatever the attached version holds now.
    val addOnResolver by viewModel.addOnResolver.collectAsStateWithLifecycle()
    var currentTaskId by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    // Which *page* of the Task is on screen: one of its Assets, or the add-on page that follows the
    // last one. Keyed on the Task rather than reset by an effect, so arriving at another one shows
    // its first page on the very first frame instead of flashing the page the last Task was left
    // on. Saved with the session, so a rotation keeps the page.
    //
    // Held here, beside the Task the queue is on, because the navigator bar states both at once:
    // the two levels are one control group, and a selection living inside the viewer would have to
    // be reported back up for the bar to draw it and driven back down for the arrows to move it.
    var pageIndex by rememberSaveable(currentTaskId) { mutableStateOf(0) }
    // Set when this visit's commit has been accepted and the reviewer was sent to the add-on page
    // rather than the next Task. Leaving the add-on page forward is then a move in the queue, not a
    // second judgment, so it must not try to commit again. Keyed on the Task, so arriving at another
    // one starts uncommitted.
    var committedHere by remember(currentTaskId) { mutableStateOf(false) }
    // A page the guide asked for on a Task that is not on screen yet. The page index is keyed on
    // the Task, so the Task arrives at its first page and this moves it once that state exists.
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(currentTaskId) {
        pendingPage?.let { requested ->
            pageIndex = requested
            pendingPage = null
        }
    }
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

    // **The Task Review this Task is judged under**, and the whole of v0.8.1 on this surface.
    // Resolved per Task and not per session: the Todo stamped a review on each one at import, and
    // one round may walk Tasks from several Todos. The fallback chain is the Task's stamp, then
    // the List's legacy column (passed in as [ReviewTerms.template]), then the built-in — which is
    // what every Task was in fact judged by before this round existed.
    val review: StoredTemplateVersion = remember(
        item.taskId, item.taskReview, terms.template, resolver
    ) {
        resolver.reviewFor(item.taskReview)
            ?: resolver.reviewForRef(terms.template)
            ?: Templates.MediaRapidReviewStored
    }
    val reviewDefinition = review.taskReview
    val definitionAcceptsEvents = !reviewDefinition?.accepts.isNullOrEmpty()
    // The control that commits, when the template commits from one. Null for both intent forms:
    // an Immediate template commits from the viewer's own tap, and a ConfirmIntent template from
    // another viewer intent, so neither has a button to draw.
    val commitControlId: String? = when (val commit = reviewDefinition?.commit) {
        is CommitPolicy.ConfirmControl -> commit.controlId
        is CommitPolicy.Immediate, is CommitPolicy.ConfirmIntent -> null
        null -> TASK_REVIEW_COMPLETE_CONTROL
    }
    // A template that does not commit from the completion checkbox draws its own control and the
    // checkbox goes away: two controls that both complete the Task would be two answers to what
    // the template already said. A definition-less Task keeps the checkbox, as it always has.
    val commitIsCompletion = reviewDefinition == null ||
        (reviewDefinition.commit is CommitPolicy.ConfirmControl &&
            commitControlId == TASK_REVIEW_COMPLETE_CONTROL)
    val commitLabel = commitControlId
        ?.let { id -> review.buttons.firstOrNull { it.id == id }?.name }
        ?: "Submit"
    val acceptsPoint = reviewDefinition?.accepts?.contains(ViewerEventType.IMAGE_POINT) == true
    val acceptsChoice = reviewDefinition?.accepts?.contains(ViewerEventType.CHOICE_SELECT) == true
    // The tap itself commits: this is the dating card's Like, and the vocabulary round's one-tap
    // final answer. A ConfirmControl or ConfirmIntent template waits for its own route instead.
    val commitsChoiceImmediately =
        (reviewDefinition?.commit as? CommitPolicy.Immediate)?.intent ==
            ViewerEventType.CHOICE_SELECT

    // What the viewer has reported and the template will catch. Held encoded so it survives a
    // rotation, exactly as the import dialog's drafts are. Keyed by Task *and* page because a point
    // is an answer about one picture: advancing to another Task or paging to another file of the
    // same one clears it rather than leaving a stale colour to be submitted under the wrong page.
    var pendingSample by rememberSaveable(item.taskId, pageIndex) { mutableStateOf<String?>(null) }
    var sampling by remember(item.taskId, pageIndex) { mutableStateOf(false) }
    val sample = remember(pendingSample) { decodeSample(pendingSample) }
    val sampleScope = rememberCoroutineScope()

    // A choice a ConfirmControl/ConfirmIntent template is holding until its own route commits. An
    // Immediate template never uses it: the tap commits, and nothing is left pending. Encoded for
    // the same reason the sample is — it survives a rotation — and keyed by Task and page so moving
    // on cannot commit a choice under another Prompt.
    var pendingChoice by rememberSaveable(item.taskId, pageIndex) { mutableStateOf<String?>(null) }
    val choice = remember(pendingChoice) {
        pendingChoice?.let { parseEventValue(ViewerEventType.CHOICE_SELECT, it) as? ViewerEvent.Choice }
    }

    // The Content page's own two reads. The body is read by Task id here, not carried on the item;
    // the Resources are read with the Task's own requiredness.
    val payloadRow by remember(item.taskId) { viewModel.observeTaskPayload(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val taskResources by remember(item.taskId) { viewModel.observeTaskResources(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // The body is decoded through the Viewer table, so this screen never names a Viewer and never
    // parses one. The card resolves its own Resource against the Task's uses; the resources flow
    // is read here for the requiredness gate below.
    val content = remember(payloadRow) {
        payloadRow?.let { decodePrompt(it.kind, it.schemaVersion, it.payload) }
    }

    // What is on record for this Task, so a committed point is not write-only. Read as text and
    // parsed through the domain reader: the column holds two grammars, and a row from a later build
    // is unreadable rather than something this screen gets to guess at.
    val recordedText by remember(item.taskId) { viewModel.observeLatestResponse(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val recorded = remember(recordedText) { recordedText?.let { ReviewResponse.parse(it) } }
    // The latest recorded point, when the record is one this build can read and it carries one.
    // A legacy MediaReview has no point to show and displays exactly as it did before v0.8.1.
    val recordedPoint = remember(recorded) {
        (recorded as? ReviewResponse.PrimaryReview)
            ?.events?.filterIsInstance<ViewerEvent.ImagePoint>()?.lastOrNull()
    }
    // The same for a choice: the latest answer on record, so a revisited Prompt shows what was
    // said rather than an untouched card. History is append-only; this is only the newest.
    val recordedChoice = remember(recorded) {
        (recorded as? ReviewResponse.PrimaryReview)
            ?.events?.filterIsInstance<ViewerEvent.Choice>()?.lastOrNull()
    }
    // The same submission's events as rows of their own. Read from `review_event_answers` rather
    // than filtered out of the response above: that table is where a caught event is addressed as
    // an event, and it is what the Info sheet's Recorded section lists. A template that catches no
    // events keeps its flow empty and its sheet exactly as it was.
    val recordedEvents by remember(item.taskId, definitionAcceptsEvents) {
        if (definitionAcceptsEvents) viewModel.observeLatestEventAnswers(item.taskId)
        else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Which file each recorded point came from, so a Task of several pictures says so instead of
    // naming a content URI. Keyed by the source URI the event carries, as the write path is.
    val recordedSourceNames = remember(item.assets) {
        item.assets.mapNotNull { asset ->
            asset.sourceUri.takeIf { it.isNotEmpty() }?.let { it to asset.name }
        }.toMap()
    }

    // The queue as the guide names it, each Task carrying its pages: its Assets, then its add-on
    // page when this build can resolve the one it was stamped with. Rebuilt only when the round's
    // items or the add-on tables change, so stepping or paging never re-maps the whole queue for a
    // menu that is not open.
    val queueEntries = remember(items, addOnResolver) {
        reviewQueueEntries(items) { entry ->
            reviewPageEntries(
                assets = assetsOf(entry),
                addOn = addOnResolver.readableVersion(entry.addOn),
                // The Content page is a page of the Task and keeps its place in the guide. It was
                // omitted while every page after it was counted, so a Prompt Task's guide rows were
                // one page to the left of the position beside them.
                content = entry.hasContentPage()
            )
        }
    }
    // The frame takes real room here, so the system bars are never hidden: there is no full-bleed
    // picture for them to be hiding *from*. They are still forced light, which is the other half
    // of what this does — what sits under them is the navigator's scrim, not the app's surface.
    MediaSystemBars(chromeVisible = true)

    // The page state is clamped rather than repaired: a re-sync can take a file away under a
    // selection that was already made, and the honest answer is to show what is still there
    // instead of writing state from a composition.
    //
    // Read here, above the queue moves, because a commit has to know whether the Task has an add-on
    // page to land on before it decides to advance — see [commitArrival].
    val assets = assetsOf(item)
    // The questionnaire this Task was stamped with, if this build can read it. A reference this
    // build cannot resolve draws nothing — the fail-closed rule the resolver states — so the Task
    // simply has no extra page rather than a page it cannot fill.
    val storedAddOn = addOnResolver.readableVersion(item.addOn)
    val hasAddOnPage = storedAddOn != null
    // The pages this Task presents, in the order the arrows and the guide walk them: the Prompt
    // body first when it has one, then the Assets, then the add-on. One state derivation, shared
    // with the read-only Task surfaces, is the source for the count, the step, the guide and which
    // page is showing — see [taskPageState].
    val pageState = taskPageState(item, storedAddOn, pageIndex)
    val pages = pageState.pages
    val pageCount = pageState.pageCount
    // The pages as the jump list names them, for when the queue itself has only one Task to move
    // between. The Content and add-on pages are both entries, and each row says what it is — see
    // [reviewPageEntries].
    val pageEntries = remember(assets, storedAddOn, pages) {
        reviewPageEntries(assets, storedAddOn, content = item.hasContentPage())
    }
    val currentPage = pageState.pageIndex
    val currentModel = pageState.current
    val contentPage = currentModel as? ReviewPage.Content
    // The add-on page is the last index. `page` is then null, which every file-specific value below
    // already reads as *no file* rather than as an error.
    val onAddOnPage = pageState.onAddOnPage

    // Why this Prompt cannot be answered, resolved once: the Content page draws it, and the
    // forward edge has to know whether this Task has a judgment in it at all. Null means the page
    // is interactive. Each reason is a fact the submission boundary would refuse on, said before
    // the tap rather than only after it.
    val missingRequired = taskResources.firstOrNull { it.required && it.missing }
    // Whether *the Task* has a Content page, not whether one is on screen: the forward edge is
    // resolved on the add-on page too, and asking the current page would have said "no Prompt"
    // exactly where the gate is applied.
    val contentNotice = if (item.hasContentPage()) {
        when {
            payloadRow == null -> "This Prompt's body could not be read."
            content == null -> "This build cannot read this Prompt."
            item.payloadRevision != null && payloadRow?.revision != item.payloadRevision ->
                "This Prompt was updated after the review opened. Reopen it to judge the new text."

            missingRequired != null ->
                "The file this Prompt requires is missing from the source, so it cannot be answered."
            !acceptsChoice -> "No Task Review in force catches a choice."
            else -> null
        }
    } else {
        null
    }

    // Landing on a Task is what moves the List's cursor; [id] rather than [taskId] because the
    // cursor names an entry, and §6 leaves it that way on purpose.
    fun goTo(target: ReviewItem) {
        currentTaskId = target.taskId
        onPositionChanged(target.id)
    }

    val navigation = taskNavigation(items, index)

    // Forward off this Task's last page is the queue move the next arrow makes, rather than a
    // commit, when the Task has no judgment a commit could record: its source is blocked, or its
    // Prompt cannot be answered and the template in force is waiting for an event. Without this
    // the forward edge would be inert on exactly the Tasks that can never be judged, which is
    // the one place the documented swipe contract would stop working. A template that needs no
    // event still commits normally — a legacy rapid-review Prompt completes from the frame.
    val forwardIsNavigation = navigation.canGoNext && (
        item.reviewBlocked != null ||
            (contentNotice != null && definitionAcceptsEvents)
        )

    // The semantic-action boundary, and it sits a level above the Task on purpose: a swipe and an
    // explicit control both arrive here as a [TaskAction], and this is where one becomes its
    // Task-level operation. Nothing below it knows what a swipe means — the Asset run spends the
    // gesture on pages and only reports a leave at a run's end — and nothing here learns which
    // gesture produced the action, so the arrows and the swipe cannot drift apart.
    /**
     * A committed judgment: write it, then move on when the template says to.
     *
     * The write is the same call every route uses, so the completion transition, the round
     * bookkeeping and the webhook cannot differ between a checkbox, a swipe and a template's own
     * button. [events] is what the viewer reported; the headless definition gets an empty list and
     * records the fold of the pairs, exactly as it always has.
     */
    // The queue move a judgment leads to, once the Task has nothing left to show. Shared by the
    // accepted commit and by leaving the add-on page after one, so the two cannot disagree about
    // when a round ends.
    fun advanceToNextTask() {
        if (index == items.lastIndex) {
            finished = true
            // Announced after the check above, so the delivery it may lead to sees
            // the item that was just swiped as checked.
            onQueueFinished?.invoke(items)
        } else {
            goTo(items[index + 1])
        }
    }

    fun commit(events: List<ViewerEvent>) {
        viewModel.submitItemReview(item, terms, review, events) { accepted ->
            // A refused commit wrote nothing, so there is nowhere to move: advancing would show the
            // next Prompt while this one stayed unanswered, and the round would have walked past a
            // judgment it never made.
            if (!accepted) return@submitItemReview
            when (
                commitArrival(
                    advanceAfterCommit = reviewDefinition?.advanceAfterCommit != false,
                    hasAddOnPage = hasAddOnPage,
                    currentPage = currentPage,
                    pageCount = pageCount
                )
            ) {
                // Judged, but its questionnaire is a page it has not shown yet. Land there rather
                // than on the next Task: the add-on is the Task's last page, and the place a Task
                // is left from — advancing straight away skipped it without a trace.
                CommitArrival.AddOnPage -> {
                    committedHere = true
                    pageIndex = pageCount - 1
                }

                CommitArrival.NextTask -> advanceToNextTask()
                null -> Unit
            }
        }
    }

    fun dispatch(action: TaskAction) {
        when (action) {
            TaskAction.CompleteTask -> {
                // A presentation that accepts viewer events is committed with one, or not at all.
                // Swiping forward without the point would leave the card uncompleted while the
                // round moved past it; refusing the move is the honest answer, and the template's
                // own button is the control that says what is missing.
                if (definitionAcceptsEvents) {
                    sample?.let { commit(listOf(it)) }
                } else {
                    // A submission, because a review surface's completion is a judgment. Everything
                    // downstream of the write — the completion transition, the round bookkeeping,
                    // the webhook — is the same path a checkbox takes, so a list that becomes
                    // complete here still fires exactly as it always did.
                    commit(emptyList())
                }
            }

            TaskAction.PreviousTask -> navigation.previous?.let { goTo(it) }

            TaskAction.NextTask -> navigation.next?.let { goTo(it) }
        }
    }

    // The bands' two answers, resolved here because this is the only place that knows both which
    // Task is on screen and which of its files. The chip row belongs to the *file* — its kind comes
    // off the Asset's own basename rather than the Task's, so paging from a photograph to a video
    // changes it, which is the release. The badge row belongs to the *Task* and is resolved over
    // every kind the Task is configured for, because it is drawn under the Task's name with no
    // particular file on screen. See [ItemActionTags].
    //
    // `name` and not `relativePath`: a classifier wants the basename, and it is what
    // `reviewBlockFor` already hands `assetViewerAvailable` for the same file.
    // The Asset behind the page, which is none of them on the Content and add-on pages. The page
    // list already answers this; the old index arithmetic would have pointed one file too far on
    // a Prompt's first Asset.
    val page = (currentModel as? ReviewPage.Asset)?.asset
    val kind = assetKindOf(page?.name.orEmpty())
    // Null is a real answer here and is resolved to the empty row rather than to a default: nothing
    // attached admits this file's kind, so nothing offers it a button. See [TemplateResolver].
    val control = resolver.controlFor(item.assetControls, kind) ?: ResolvedAssetControl.Empty
    val taskControl = resolver.controlForTask(item.assetControls)
    // The page's own record, and the whole of v0.5.1 on the surface. `assets` is empty only for a
    // Task with no Asset at all — a manual item — where there is no pair to have recorded anything
    // and the middle band is not drawn in any case.
    val pageDecisions = page?.decisions ?: AssetDecisions()
    // A level with nothing to move between is not a level, so a one-file Task with no add-on keeps
    // the bar it has always had: no page name beside the title, no `(1 / 1)`, no inner arrows.
    val hasPageLevel = pageCount > 1
    val pageForward = pageStep(pageCount, currentPage, forward = true)
    val pageBack = pageStep(pageCount, currentPage, forward = false)

    // This Task's answers, live: the page writes as the reviewer taps and its own flow is what
    // draws the selection, so a re-read is the page noticing a write it just made.
    val answers by remember(item.taskId) { viewModel.observeTaskAnswers(item.taskId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // The middle band, and null where there is no file for it to be about. A Task with no Asset —
    // a legacy manual item — has no pair, so it has nowhere to record an action and no kind to
    // offer one for; a row of chips there would be a control whose every tap went nowhere. It keeps
    // its name, its position and its checkbox, which is the whole of what the other two bands are
    // for. See v0.5.1's out-of-scope note.
    //
    // [control] is this page's own, resolved from the Task's record and the file's kind, and
    // [ReviewAsset.decisions] is this page's own record — the pair, both halves of it, which is
    // what the band is.
    //
    // A PDF's tools live in its selection menu, not in a file-level chip row: a comment or a tag
    // applies to a passage, and a row of chips above the page would offer to mark the file as a
    // whole when the template governs nothing but passages. The decision is made here and not in
    // `ReviewAssetControls`, so a PDF cannot draw a row nobody can use.
    //
    // What a PDF keeps is the frame's other half, the Info entry, which every template carries and
    // none may remove: the band is the same dismissible strip the other kinds get, holding only the
    // icon that opens what this file carries. Tapping the page where no highlight sits puts it away
    // and brings it back, exactly as on a picture.
    //
    // The add-on page has no file and no pair, so its band is the frame's other half alone: the
    // Info icon, opening the Task's sheet. It is the static frame every template carries, applied
    // to a page that carries no template of its own.
    val assetBand: (@Composable () -> Unit)? = when {
        contentPage != null -> {
            {
                Column {
                    // Only a control-committed template draws a button here. An Immediate template
                    // has none: the options on the card are the controls, and the tap is the commit.
                    if (commitControlId != null && !commitIsCompletion) {
                        ReviewTemplateControl(
                            label = commitLabel,
                            enabled = choice != null && !sampling,
                            status = when {
                                choice != null -> "Selected: ${choice.label}"
                                recordedChoice != null -> "Recorded: ${recordedChoice.label}"
                                acceptsChoice -> "Choose an option."
                                else -> null
                            },
                            onClick = { choice?.let { commit(listOf(it)) } }
                        )
                    }
                    MediaInfoBar(contentDescription = "Prompt info") { infoOpen = true }
                }
            }
        }

        onAddOnPage -> {
            { MediaInfoBar(contentDescription = "Add-on info") { infoOpen = true } }
        }

        page == null -> null

        else -> {
            val current = page
            if (kind == AssetKind.PDF) {
                { MediaInfoBar(contentDescription = "PDF info") { infoOpen = true } }
            } else {
                {
                    Column {
                        // The template's own commit control, drawn where the completion checkbox
                        // would be. It is the button the template names, and pressing it is the
                        // only thing that records the sample — which is the compatibility case
                        // this round exists to prove: a template, not the viewer, owns submission.
                        if (commitControlId != null && !commitIsCompletion) {
                            ReviewTemplateControl(
                                label = commitLabel,
                                enabled = sample != null && !sampling,
                                status = when {
                                    sampling -> "Reading the picture…"
                                    sample != null -> "Point ready: ${pointSummary(sample)}"
                                    recordedPoint != null ->
                                        "Recorded: ${pointSummary(recordedPoint)}"
                                    // A record this build cannot read is still a record: saying so
                                    // is honest about what is stored without pretending to show
                                    // a value the reader refused to guess at.
                                    recordedText != null && recorded == null ->
                                        "Recorded: this build cannot read the answer."
                                    acceptsPoint -> "Long-press the picture to choose a point."
                                    else -> null
                                },
                                swatchColor = when {
                                    sample != null -> sample.color
                                    recordedPoint != null -> recordedPoint.color
                                    else -> null
                                },
                                onClick = { sample?.let { commit(listOf(it)) } }
                            )
                        }
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
            }
        }
    }
    // The PDF's tools and marks, both resolved against the *current* governing version. `control`
    // is already that version's button list for a PDF, so `PdfTools.of` is the same filter the
    // viewer applies — one authority for "which tools does this passage menu offer".
    //
    // A mark's visibility is decided by the tools, never by the version that authored it, which is
    // why the marks Flow is read alongside the resolver rather than through a join: removing a
    // button hides its marks without touching a row, and restoring it brings them back.
    val pdfGoverning = page
        ?.takeIf { kind == AssetKind.PDF }
        ?.let { resolver.governingFor(item.assetControls, AssetKind.PDF) }
    val pdfTools = remember(control) { PdfTools.of(control) }
    val pdfMarks by remember(page?.assetId, kind) {
        val assetId = page?.assetId
        if (kind == AssetKind.PDF && !assetId.isNullOrEmpty()) {
            viewModel.observeAssetMarks(assetId)
        } else {
            flowOf(emptyList<AssetMark>())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // What the viewer's last resolution found, for the Info sheet. Keyed on the Asset, so paging to
    // another file cannot leave the previous one's marks in this one's sheet; the viewer reports it
    // after resolving, not while composing, and reports an empty list when nothing is visible.
    var pdfMarkInfo by remember(page?.assetId) { mutableStateOf<List<PdfMarkInfo>>(emptyList()) }

    // Creation needs both the file and the version that offered the tool; a PDF with no governing
    // version has neither, and its selection menu offers Copy alone. The viewer never calls a
    // ViewModel itself — the same split the read-only viewer relies on.
    val onCreatePdfMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? =
        if (page != null && pdfGoverning != null) {
            { tool, comment, anchor ->
                val markKind = tool.type.markKind()
                if (markKind != null) {
                    viewModel.createPdfMark(
                        assetId = page.assetId,
                        tool = tool,
                        kind = markKind,
                        value = comment.orEmpty(),
                        anchor = anchor,
                        authored = pdfGoverning
                    )
                }
            }
        } else {
            null
        }

    MediaShell(
        modifier = modifier,
        navigator = MediaNavigator(
            title = item.title,
            onBack = onBack,
            // The page's own name, and the add-on page's is its word: a questionnaire is not a
            // file and must not borrow the name of the last one.
            subtitle = when {
                !hasPageLevel -> null
                contentPage != null -> "Prompt"
                onAddOnPage -> "Add-on"
                else -> page?.name
            },
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
            counter = if (hasPageLevel) {
                "${index + 1}(${currentPage + 1}/$pageCount)/${items.size}"
            } else {
                "${index + 1} / ${items.size}"
            },
            // The guide behind the *outer* number: the round's Tasks, and under each row its
            // pages. A row's own tap is goTo, the same move the outer arrows make, so List Review
            // records the cursor it would have recorded and Quick Review records nothing. A page
            // picked under a row is the page move the inner arrows make: it changes no cursor,
            // because the cursor names the Task and a page is not one.
            //
            // When the round holds a single Task there is no outer level to open, and the counter
            // still names one — its pages. The guide then starts at that page level.
            queueJump = when {
                items.size > 1 -> MediaQueueJump(
                    entries = queueEntries,
                    current = index,
                    currentPage = currentPage,
                    onJump = { entry, page ->
                        if (entry == index) {
                            pageIndex = page ?: 0
                        } else {
                            pendingPage = page ?: 0
                            goTo(items[entry])
                        }
                    }
                )

                pageCount > 1 -> MediaQueueJump(
                    entries = pageEntries,
                    current = currentPage,
                    onJump = { page, _ -> pageIndex = page }
                )

                else -> null
            },
            // Stepping here is navigation and nothing else, which is what §7 asks of these
            // controls and what the swipe deliberately is not. Neither of them submits, completes
            // or records: a null on a side is that arrow drawn disabled, because there is nowhere
            // to go.
            onPrevious = navigation.previous?.let { { dispatch(TaskAction.PreviousTask) } },
            onNext = navigation.next?.let { { dispatch(TaskAction.NextTask) } },
            onPreviousAsset = pageBack?.let { to -> { pageIndex = to } },
            onNextAsset = pageForward?.let { to -> { pageIndex = to } },
            hasAssetSteps = hasPageLevel
        ),
        chromeVisible = chromeVisible,
        // The checkbox never leaves, however far the file's own controls are put away: it is the
        // one thing a review is for, and a round cannot be finished by a tap that hid the control
        // that finishes it.
        framePersists = true,
        // The frame's bottom band is the Task's own state, and every template has one. When the
        // review commits from the checkbox it is that control. When a template names its own
        // control, that control lives in the dismissible file band and this band states the
        // result instead — a checkbox here would complete the Task without recording what the
        // template is waiting for, but an empty band leaves a headless Prompt with no task-level
        // display at all. Read-only, and beside the file's band rather than inside it, so a tap
        // that puts the file's controls away cannot put this away too.
        taskBar = if (commitIsCompletion) {
            {
                TaskCompletionBar(
                    item = item,
                    reviewBlocked = item.reviewBlocked,
                    onToggleCompleted = { completed ->
                        if (completed) {
                            // The frame's own control, so checking it here is a judgment and
                            // unchecking it is not: §5 records what somebody committed, and there
                            // is no retraction for a template to record. The uncheck still has to
                            // happen — it just moves state.
                            viewModel.submitItemReview(item, terms, review)
                        } else {
                            viewModel.setItemCompleted(item, false)
                        }
                    }
                )
            }
        } else {
            { TaskStatusBar(item = item, reviewBlocked = item.reviewBlocked) }
        },
        assetControls = assetBand,
    ) {
        if (contentPage != null) {
            // A page with a judgment to make is left alone: its tap is the answer, and a swipe
            // that could leave it would be a way to pass the question unknowingly. A page this
            // build cannot answer has no judgment to make, so it joins the ordinary run — swipe
            // turns the page, and at the run's ends crosses Tasks as navigation, not as a commit.
            // Both cases get the tap that reveals and hides the asset band.
            val interactive = contentNotice == null
            ContentPageSwipe(
                canSwipeForward = !interactive && (pageForward != null || navigation.next != null),
                canSwipeBack = !interactive && (pageBack != null || navigation.previous != null),
                onSwipeForward = {
                    pageForward?.let { pageIndex = it }
                        ?: navigation.next?.let { goTo(it) }
                },
                onSwipeBack = {
                    pageBack?.let { pageIndex = it }
                        ?: navigation.previous?.let { goTo(it) }
                },
                onTap = { chromeVisible = !chromeVisible },
                modifier = Modifier.fillMaxSize()
            ) {
                if (content != null) {
                    PromptContentPage(
                        content = content,
                        resources = taskResources,
                        selectedOptionId = (choice ?: recordedChoice)?.optionId,
                        interactive = interactive,
                        // A drill reveals its meaning only once the answer is on record. A page
                        // this build cannot answer is read-only and shows everything it has.
                        reveal = !interactive || recordedChoice != null,
                        notice = contentNotice,
                        player = player,
                        onSelect = { option ->
                            val event = ViewerEvent.Choice(option.id, option.label)
                            if (commitsChoiceImmediately) {
                                // The tap is the judgment. A refusal inside the write boundary
                                // leaves this page on screen, which is the honest answer to a
                                // refused commit.
                                commit(listOf(event))
                            } else {
                                // A template with its own commit route holds the choice until
                                // that route fires; nothing is written here.
                                pendingChoice = event.encodeValue()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PromptNoticePage(contentNotice ?: "This Prompt cannot be shown.")
                }
            }
        } else {
        // [AssetRun] counts Assets, and its add-on sentinel is the Asset count. The unified page
        // list counts the Content page as zero, so a Prompt Task carrying both a body and files
        // would otherwise open one file too far and put its add-on on the wrong page. The two
        // converters are the same pair the preview surfaces use; see [assetIndexOf].
        val hasContent = item.hasContentPage()
        val backToContent = hasContent && currentPage == 1
        // This visit's judgment has already been written, and the reviewer is on the add-on page it
        // was sent to. Forward from here is a queue move, not a second commit — for a template that
        // accepts events there is nothing left to report, so committing again would be refused and
        // the page would feel stuck.
        val passedCommit = onAddOnPage && committedHere
        AssetRun(
            item = item,
            selectedIndex = pageState.assetIndex,
            player = player,
            imageLoader = imageLoader,
            settings = settings,
            // A blocked Task cannot be swiped forward into a judgment; the end of the queue is
            // already this exact state, so it is the same inert forward edge, not a special one.
            // Its untouchable content is still browsable, which is why this gates only the end of
            // the run and not the run itself. A template that accepts viewer events is the second
            // closed gate: with no event reported, forward is nowhere to go either — and the
            // gesture has to know that before it slides, or a leave the dispatch refuses parks
            // the card off-screen as a black surface.
            canLeaveForward = passedCommit ||
                forwardIsNavigation ||
                canLeaveTaskForward(
                    reviewBlocked = item.reviewBlocked != null,
                    acceptsEvents = definitionAcceptsEvents,
                    hasPendingEvent = sample != null
                ),
            // Back off the first file of a Prompt Task returns to its Content page — a page turn,
            // not a queue step. Elsewhere it steps the queue, exactly as before.
            canLeaveBack = backToContent || navigation.canGoPrevious,
            pdfTools = pdfTools,
            pdfMarks = pdfMarks,
            onPdfCreateMark = onCreatePdfMark,
            onPdfEditMark = { id, text -> viewModel.editMarkValue(id, text) },
            onPdfDeleteMark = { id -> viewModel.deleteMark(id) },
            // A mark the resolver had to move is corrected in the store, not only on screen: the
            // next open then takes the fast path, and an export sees the same passage this frame
            // drew.
            onPdfRelocateMark = { id, anchor -> viewModel.relocateMark(id, anchor) },
            onPdfMarksResolved = { pdfMarkInfo = it },
            onLeaveForward = {
                if (passedCommit || forwardIsNavigation) {
                    advanceToNextTask()
                } else {
                    dispatch(TaskAction.CompleteTask)
                }
            },
            // Backwards is never a judgment: it either returns to this Task's Content page or
            // steps the queue, and changes nothing about the Task that was left behind.
            onLeaveBack = {
                if (backToContent) pageIndex = 0 else dispatch(TaskAction.PreviousTask)
            },
            onSelectAsset = { pageIndex = pageOfAsset(it, hasContent) },
            onTap = { chromeVisible = !chromeVisible },
            // The viewer's third gesture, offered only when the template in force accepts it and
            // the page on screen is a picture. The point is resolved through the card's own zoom
            // and letterbox geometry, then the picture itself is read — nothing here invents an
            // answer the image does not carry.
            onLongPress = if (acceptsPoint && page != null && kind == AssetKind.IMAGE) {
                { zoom, position ->
                    val point = zoom.imagePointAt(position.x, position.y)
                    val uri = page.sourceUri
                    if (point != null) {
                        sampling = true
                        sampleScope.launch {
                            val color = sampleImageColor(context, uri, point)
                            sampling = false
                            if (color != null) {
                                pendingSample = encodeSample(
                                    ViewerEvent.ImagePoint(point.x, point.y, color, uri)
                                )
                            }
                        }
                    }
                }
            } else {
                null
            },
            // The page after the last Asset. Its presence is what makes the page count one more
            // than the files, and its absence is what leaves every other Task exactly as it was.
            addOnPage = storedAddOn?.let { addOn ->
                {
                    AddOnPage(
                        addOn = addOn,
                        answers = answers,
                        onAnswer = { question, value ->
                            viewModel.setAddOnAnswer(item.taskId, question.id, value)
                        },
                        onClear = { question ->
                            viewModel.clearAddOnAnswer(item.taskId, question.id)
                        },
                        // The page's draft state is keyed by Task as well as question: two Tasks may
                        // be attached to the same add-on, and the field must not carry one's words
                        // into the other when the queue moves.
                        taskId = item.taskId
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        }
    }

    if (infoOpen) {
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, pageDecisions, resolver, chrome.listTitle),
            // The file the sheet's rows and its two offers are about. Without it a Task standing
            // for several files has no single source to save or share and the offers disappear —
            // which is exactly what a folder-as-Task used to do. On the add-on page this is null,
            // so the sheet has no file to save or share, which is the truth of that page.
            asset = page,
            // The level the Info button belongs to. Info is a property of the page on screen, not
            // of the Task: a file's sheet describes the file, the Prompt body's sheet describes
            // the Prompt, and the add-on's sheet describes the questionnaire. Handing every page
            // the add-on made the three levels read as one.
            level = when {
                onAddOnPage -> ReviewInfoLevel.AddOn
                currentModel is ReviewPage.Content -> ReviewInfoLevel.Content
                else -> ReviewInfoLevel.Asset
            },
            // The PDF's marks, placed and missing alike. The sheet is the only place a mark this
            // document cannot place is named; nothing on the page says so. Null for every other
            // kind, where a marks section would be a question the file cannot answer.
            marks = if (kind == AssetKind.PDF) pdfMarkInfo else null,
            // The questionnaire's answers, read only on its own page. Its current version no
            // longer asks some of them; those are kept and shown as no longer asked.
            addOnInfo = addOnInfoOf(storedAddOn, answers, addOnResolver),
            // What the latest submission's template caught. Null for a template that accepts no
            // events, so its sheet does not gain a section that could only ever say "None".
            recordedEvents = recordedEvents.takeIf { definitionAcceptsEvents },
            recordedSourceNames = recordedSourceNames,
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
    modifier: Modifier,
    /** A press held on the page, for a template that reads a point out of what is on screen. */
    onLongPress: ((MediaZoomState, androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    /**
     * The Task's questionnaire, drawn as the page after its last Asset.
     *
     * Null is the ordinary Task and keeps the run exactly as many pages as it has files. Given one,
     * the run is one page longer and every swipe past the last file arrives here rather than
     * leaving the Task — which is the whole of where an add-on sits.
     */
    addOnPage: (@Composable () -> Unit)? = null,
    pdfTools: List<TemplateButton> = emptyList(),
    pdfMarks: List<AssetMark> = emptyList(),
    onPdfCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? = null,
    onPdfEditMark: ((String, String) -> Unit)? = null,
    onPdfDeleteMark: ((String) -> Unit)? = null,
    onPdfRelocateMark: ((String, QuoteAnchor) -> Unit)? = null,
    onPdfMarksResolved: ((List<PdfMarkInfo>) -> Unit)? = null
) {
    val assets = assetsOf(item)
    val pageCount = taskPageCount(assets.size, addOnPage != null)
    val onAddOnPage = addOnPage != null && selectedIndex == assets.size
    val asset = if (onAddOnPage) null else assets.getOrNull(selectedIndex)

    if (asset == null && !onAddOnPage) {
        // No Asset, no viewer: a manual item is still a card in the queue, swipeable and
        // completable exactly like a file's, but with nothing to render.
        SwipeCard(
            key = item.taskId,
            canSwipeForward = canLeaveForward,
            canSwipeBack = canLeaveBack,
            onSwipeForward = onLeaveForward,
            onSwipeBack = onLeaveBack,
            modifier = modifier,
            onTap = onTap,
            onLongPress = onLongPress
        ) { zoom ->
            LaunchedEffect(zoom) { zoom.contentAspect = null }
            PreviewPlaceholder(Modifier.fillMaxSize().mediaZoom(zoom), "Manual item", item.title)
        }
        return
    }

    // Asked once per composition and read by the card and by both directions of the gesture, so
    // the three cannot disagree about there being a page to move to. Pages, not Assets: the
    // questionnaire is the last page, and stepping onto it from the last file is an interior move
    // that leaves nothing.
    val forward = pageStep(pageCount, selectedIndex, forward = true)
    val back = pageStep(pageCount, selectedIndex, forward = false)

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
        onTap = onTap,
        onLongPress = onLongPress
    ) { zoom ->
        if (onAddOnPage) {
            // The questionnaire is not a file: there is nothing to fit, so the card carries no
            // aspect and the page is laid out by its own scroll.
            LaunchedEffect(zoom) { zoom.contentAspect = null }
            addOnPage?.invoke()
        } else {
            ItemPreview(
                asset = asset!!,
                player = player,
                imageLoader = imageLoader,
                settings = settings,
                zoom = zoom,
                modifier = Modifier.fillMaxSize(),
                pdfTools = pdfTools,
                pdfMarks = pdfMarks,
                onPdfCreateMark = onPdfCreateMark,
                onPdfEditMark = onPdfEditMark,
                onPdfDeleteMark = onPdfDeleteMark,
                onPdfRelocateMark = onPdfRelocateMark,
                onPdfMarksResolved = onPdfMarksResolved
            )
        }
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
 * How many pages a Task has: one per Asset, and one more when a questionnaire follows it.
 *
 * The add-on page is the *last* page, which is the user's fixed position for it — no start/end
 * attribute exists to move it. A Task with no add-on is exactly as many pages as it has Assets, so
 * every existing Task pages exactly as it did.
 */
internal fun taskPageCount(assetCount: Int, hasAddOn: Boolean): Int =
    assetCount + if (hasAddOn) 1 else 0

/**
 * Which page of a Task a horizontal swipe moves to, or null when the swipe leaves the Task.
 *
 * [assetStep]'s sibling, one level up: an Asset is a page and the add-on is the page after the last
 * one. Keeping it a separate function is what lets the review surface count the questionnaire while
 * the folder browser, which shows files rather than Tasks, goes on paging Assets alone.
 */
internal fun pageStep(pageCount: Int, index: Int, forward: Boolean): Int? {
    if (index !in 0 until pageCount) return null
    val next = if (forward) index + 1 else index - 1
    return next.takeIf { it in 0 until pageCount }
}

/**
 * Whether a swipe forward off a Task's last page may leave it as a judgment.
 *
 * Two gates. A [Task whose content cannot be judged][reviewBlocked] closes the first, exactly as
 * it did before there was a second. The second is v0.8.1's: a template that accepts viewer events
 * can only be committed with one, so a Task with nothing reported has nowhere forward to go.
 *
 * The gate belongs to the gesture and not only to the dispatch. [SwipeCard] animates the card
 * off-screen *before* it reports the leave, and a leave the dispatch then refuses leaves the card
 * parked at `-width` with nothing behind it — the black surface the acceptance walk found by
 * swiping a one-Asset Task whose template had no point yet. An inert forward edge rubber-bands
 * instead, which is what "nowhere to go" already looks like on a blocked Task and at the end of
 * the queue.
 *
 * [hasPendingEvent] is the reported event for the page on screen. The caller resolves it because
 * only the caller knows both the viewer that produced one and the template version that accepts
 * it; a page's event does not carry over to the next page, because it is an answer about one file.
 */
internal fun canLeaveTaskForward(
    reviewBlocked: Boolean,
    acceptsEvents: Boolean,
    hasPendingEvent: Boolean
): Boolean = !reviewBlocked && (!acceptsEvents || hasPendingEvent)

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
 *
 * [marks] is a PDF's review record, already resolved by the viewer and handed down rather than
 * looked up here. Null is "nothing to report" — every kind but a PDF, and every surface that cannot
 * resolve marks — and is deliberately different from an empty list, which says this document holds
 * none. See [pdfMarkRows].
 */
@Composable
fun ReviewItemInfoSheet(
    info: ReviewItemInfo,
    asset: ReviewAsset?,
    onDismiss: () -> Unit,
    level: ReviewInfoLevel = ReviewInfoLevel.Asset,
    marks: List<PdfMarkInfo>? = null,
    addOnInfo: AddOnInfo? = null,
    recordedEvents: List<ViewerEvent>? = null,
    recordedSourceNames: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val sourceUri = asset?.sourceUri?.takeIf { it.isNotEmpty() } ?: info.sourceUri
    val sourceMissing = asset?.sourceMissing ?: info.sourceMissing
    val title = when (level) {
        ReviewInfoLevel.AddOn -> addOnInfo?.name ?: info.title
        ReviewInfoLevel.Content -> info.title
        ReviewInfoLevel.Asset -> asset?.name?.takeIf { it.isNotEmpty() } ?: info.title
    }
    val facts by produceState(MediaFacts(), sourceUri) {
        value = sourceUri?.let { readMediaFacts(context, it) } ?: MediaFacts()
    }
    // A PDF's file-level actions are always empty: its tools apply to passages, and its review
    // record is the marks below. Listing "Actions None" on one would describe a control the file
    // deliberately does not offer.
    val isPdf = asset?.let { assetKindOf(it.name) == AssetKind.PDF } == true
    val provenance = taskProvenance(
        packageKey = info.packageKey,
        packageTitle = info.packageTitle,
        relativePath = info.relativePath,
        rootRelativePath = null,
        title = info.title
    )

    ItemInfoSheet(
        title = title,
        onDismiss = onDismiss,
        rows = buildList {
            when (level) {
                // The questionnaire's own page: only the questionnaire. Its name, version and
                // answers are already in `addOnInfoRows`; the Task's path, status and file facts
                // are one level up and were never what this sheet was asked about.
                ReviewInfoLevel.AddOn -> addOnInfo?.let { addAll(addOnInfoRows(it)) }

                // The Prompt body is a Task-level page with no file: nothing here has a source,
                // a mark or a media fact, and the Task's own events are still about the content.
                ReviewInfoLevel.Content -> {
                    provenance?.let {
                        add(InfoField(provenanceLabel(info.packageKey), it))
                    }
                    info.listTitle?.let { add(InfoField("List", it)) }
                    add(InfoField("Status", if (info.isCompleted) "Checked" else "Not checked"))
                    recordedEvents?.let { addAll(recordedEventRows(it, recordedSourceNames)) }
                }

                // The file's own sheet. The Task's questionnaire deliberately does not appear:
                // the add-on is a Task-level property, and folding its answers into every file
                // made the two levels indistinguishable. It has its own page and its own sheet.
                ReviewInfoLevel.Asset -> {
                    provenance?.let {
                        add(InfoField(provenanceLabel(info.packageKey), it))
                    }
                    info.listTitle?.let { add(InfoField("List", it)) }
                    add(InfoField("Status", if (info.isCompleted) "Checked" else "Not checked"))
                    if (!isPdf) add(InfoField("Actions", info.actionLabel ?: "None"))
                    when {
                        sourceUri == null -> add(InfoField("Source", "Manual item — no file"))
                        sourceMissing ->
                            add(InfoField("Source", "Missing from the source folder"))
                    }
                    // What this document carries, comment by comment and tag by tag — including
                    // a mark whose passage is no longer in it. Null means the caller has no
                    // resolution to report (a read-only viewer, or a file that is not a PDF), not
                    // that the file is unmarked.
                    marks?.let { addAll(pdfMarkRows(it)) }
                    // What this Task's template caught in its latest submission — beside the marks
                    // it already carries, because both are committed facts about the content
                    // rather than current state. Null when no template accepted events; see
                    // [recordedEventRows].
                    recordedEvents?.let { addAll(recordedEventRows(it, recordedSourceNames)) }
                    addAll(mediaFactRows(facts, title))
                }
            }
        },
        footer = sourceUri
            ?.takeIf { level == ReviewInfoLevel.Asset && !sourceMissing }
            ?.let { uri -> { MediaFileActions(sourceUri = uri, name = title) } }
    )
}

/** What to call the provenance line for an item: a Package has a name, a file has a path. */
private fun provenanceLabel(packageKey: String?): String =
    if (packageKey != null) "Package" else "Path in source folder"

/**
 * The marks section of a PDF's Info sheet: one summary line, then one row per mark.
 *
 * Each row is the tool that made it, what kind it is and where it landed, with the quoted passage
 * and the reviewer's value beneath. A mark the document could not place says so in its own label —
 * that is the whole of the missing-mark report now. It is not drawn over the page and nothing pops
 * up to announce it; a reviewer who wants to know what the file carries asks the file, and finds it
 * here beside the marks that did land.
 *
 * Pure and separate from the sheet so the wording can be pinned without a Compose test.
 */
fun pdfMarkRows(marks: List<PdfMarkInfo>): List<InfoField> = buildList {
    val missing = marks.count { it.detached }
    add(
        InfoField(
            "Marks",
            when {
                marks.isEmpty() -> "None"
                missing == 0 -> "${marks.size} in this document"
                else -> "${marks.size} · $missing not found in this document"
            }
        )
    )
    marks.forEach { mark ->
        val kind = if (mark.kind == MarkKind.COMMENT) "Comment" else "Tag"
        val where = mark.pageIndex?.let { "Page ${it + 1}" } ?: "Not in this document"
        add(
            InfoField(
                label = "${mark.toolName} · $kind · $where",
                // The quote first, because it is what a person verifies the mark against, then
                // the value: a comment's words or a tag's configured value.
                value = "“${mark.quote}”\n${mark.value}"
            )
        )
    }
}

/**
 * The Recorded section of a Task's Info sheet: what its latest submission's template caught.
 *
 * **Committed facts, not current state** — the same shelf as [pdfMarkRows], and deliberately not
 * the actions row beside it. Tags live on the Asset and can be toggled after the fact; a caught
 * event was committed under one template version and is never edited. The sheet says so by listing
 * the events rather than by implying they are still adjustable.
 *
 * The summary row is always present when the surface has a template that accepts events, because
 * *nothing caught yet* is itself the answer a reviewer needs before a Submit control looks inert.
 * [sourceNames] maps the event's recorded source URI to the file's name so a Task of several
 * pictures names the right one; an event whose file the Task no longer holds keeps just the point.
 *
 * Pure and separate from the sheet so the wording can be pinned without a Compose test.
 */
fun recordedEventRows(
    events: List<ViewerEvent>,
    sourceNames: Map<String, String> = emptyMap()
): List<InfoField> = buildList {
    add(InfoField("Recorded", if (events.isEmpty()) "None" else countLabel(events.size, "event")))
    events.forEachIndexed { index, event ->
        val label = when (event) {
            is ViewerEvent.ImagePoint ->
                if (events.size == 1) "Image point" else "Point ${index + 1}"

            is ViewerEvent.Choice -> "Choice: ${event.label}"
        }
        val summary = when (event) {
            is ViewerEvent.ImagePoint -> pointSummary(event)
            is ViewerEvent.Choice -> event.optionId
        }
        val source = (event as? ViewerEvent.ImagePoint)
            ?.assetPath?.let { sourceNames[it] }
        add(InfoField(label, if (source.isNullOrEmpty()) summary else "$summary\n$source"))
    }
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
    modifier: Modifier,
    pdfTools: List<TemplateButton> = emptyList(),
    pdfMarks: List<AssetMark> = emptyList(),
    onPdfCreateMark: ((TemplateButton, String?, QuoteAnchor) -> Unit)? = null,
    onPdfEditMark: ((String, String) -> Unit)? = null,
    onPdfDeleteMark: ((String) -> Unit)? = null,
    onPdfRelocateMark: ((String, QuoteAnchor) -> Unit)? = null,
    onPdfMarksResolved: ((List<PdfMarkInfo>) -> Unit)? = null
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
        zoom = zoom,
        pdfTools = pdfTools,
        pdfMarks = pdfMarks,
        onPdfCreateMark = onPdfCreateMark,
        onPdfEditMark = onPdfEditMark,
        onPdfDeleteMark = onPdfDeleteMark,
        onPdfRelocateMark = onPdfRelocateMark,
        onPdfMarksResolved = onPdfMarksResolved
    )
}

/**
 * The template's own commit control, above the file's chips.
 *
 * Drawn only when the review in force does not commit from the completion checkbox, which is the
 * compatibility this round proves: the button belongs to the template, the gesture that fills it
 * belongs to the viewer, and neither can submit without the other.
 *
 * [status] is one line under the button and says what the template is still waiting for. It is
 * copy rather than a disabled state, because a disabled Submit with no sentence is a control the
 * user cannot learn from.
 */
@Composable
private fun ReviewTemplateControl(
    label: String,
    enabled: Boolean,
    status: String?,
    swatchColor: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ListeaDimens.PagePadding, vertical = ListeaDimens.CompactGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
        if (status != null) {
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                // The colour as a swatch as well as a number: the one thing this template records
                // is what the picture looked like at a point, and a hex string is not that.
                val color = swatchColor?.let(::parseSwatch)
                if (color != null) {
                    Box(
                        Modifier
                            .size(ListeaDimens.SwatchSize)
                            .clip(RoundedCornerShape(ListeaDimens.SwatchCorner))
                            .background(color)
                    )
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The point as a person reads it: position to two decimals and the sampled colour.
 *
 * The stored response keeps four decimals because it is a record; this is a line under a button and
 * two is as much as anybody can compare against a picture.
 */
private fun pointSummary(point: ViewerEvent.ImagePoint): String =
    String.format(Locale.ROOT, "(%.2f, %.2f) · %s", point.x, point.y, point.color)

/** A stored `#RRGGBB`, or null when it is not one this build can draw. */
private fun parseSwatch(value: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()

/**
 * A sample held between the long press and Submit, in the one text shape a bundle can hold.
 *
 * The same five-field line the response's event uses, minus the type word: there is one event kind
 * this build produces from a gesture, and storing the word again would be storing a constant.
 */
private fun encodeSample(event: ViewerEvent.ImagePoint): String =
    listOf(
        String.format(Locale.ROOT, "%.6f", event.x),
        String.format(Locale.ROOT, "%.6f", event.y),
        event.color,
        event.assetPath
    ).joinToString("\t")

/** The inverse of [encodeSample]; null for the empty draft and for anything that does not parse. */
private fun decodeSample(text: String?): ViewerEvent.ImagePoint? {
    if (text.isNullOrBlank()) return null
    val fields = text.split('\t')
    if (fields.size < 3) return null
    val x = fields[0].toDoubleOrNull() ?: return null
    val y = fields[1].toDoubleOrNull() ?: return null
    if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
    return ViewerEvent.ImagePoint(
        x = x,
        y = y,
        color = fields[2],
        assetPath = fields.getOrElse(3) { "" }
    )
}
