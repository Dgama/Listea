package com.example.listea

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.listea.data.AssetDecisions
import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewBlock
import com.example.listea.data.ReviewItem
import com.example.listea.ui.theme.ListeaMediaChromeTheme

/**
 * The media surface, shared by Full Review, Quick Review, the list item viewer and the folder File
 * Viewer.
 *
 * One presentation for all four, and **two levels rather than one column**: what is outside the
 * frame belongs to the queue and to the item, and what is inside it belongs to the file. The
 * navigator and the completion bar are given real room of their own; the file's own controls are
 * drawn over the media and come and go with a tap. That is the whole vocabulary - a control drawn
 * over the picture is the file's, and a control given its own space is not - and it is what makes
 * the two levels readable without a label.
 *
 * It also replaces V4.3's "the media fills the screen and everything is drawn over it" whenever
 * [MediaShell] is called with `framePersists`, which is the review frame. The reason V4.3 gave for
 * that rule still holds and is still why the frame is built this way: a 16:9 photograph in
 * landscape must not be squeezed into whatever a fixed top bar and a fixed action row leave
 * behind. What changed is that a bar drawn *over* the media cannot be told apart from a bar the
 * media is underneath - the transport and the tag chips landed on the same pixels and taps went to
 * whichever was drawn last. Real room makes that impossible rather than merely unlikely; the
 * gallery keeps the V4.3 behaviour through `framePersists = false`.
 *
 * There is no subtitle slot and no hint slot - not "unused by default" but absent. What used to
 * live there is in [ItemInfoSheet], opened on request.
 */

/**
 * How much of the media surface the dismissible controls are covering, published to whatever is
 * drawn inside it.
 *
 * Only the video player reads this. An image does not care that a bar is sitting on top of it -
 * it is letterboxed and centred either way - but a video brings its own transport, and that has
 * to land somewhere the action chips are not. See [LocalMediaChrome].
 */
@Immutable
data class MediaChromeInsets(
    val visible: Boolean = false,
    val bottom: Dp = 0.dp
)

/**
 * The chrome's footprint, ambient to everything [MediaShell] draws media into.
 *
 * A local rather than a parameter because the only reader is four call hops down - shell, swipe
 * card, item preview, renderer - through two screens that have no interest in the value and would
 * each have to carry it anyway. Anything composed outside a [MediaShell] sees an empty, invisible
 * chrome, which is the right answer for a thumbnail or a preview.
 */
val LocalMediaChrome = compositionLocalOf { MediaChromeInsets() }

/**
 * Everything the navigator bar says and everything its controls do: the outermost strip of a media
 * screen, the one that belongs to the queue and to the thing being looked at rather than to the
 * file's own controls.
 *
 * Three zones, and the two on the outside are what the bar is really for. The title on the left is
 * what you are reviewing or viewing; the counter with its arrows on the right is where you are in
 * it; and the middle names the file on screen, which only exists when there is more than one of
 * them to be on. **A level that has nothing to move between is not a level**, so a Task with one
 * Asset gets no `(m / n)` and no inner pair of arrows, and the bar then reads exactly as it did
 * before there were two levels at all.
 *
 * The two arrow pairs are deliberately separate rather than one pair that means "next" and leaves
 * it to the caller to decide next *what*. The outer pair moves the queue and the inner pair moves
 * a page, and a control that has to be read to know which of the two it does is the confusion this
 * whole layout exists to stop.
 *
 * A null callback is a disabled control, not an absent one, so an end of the queue is expressed by
 * having nowhere to go rather than by a second flag saying so - there is one answer to "can I step
 * this way", and it is whether there is a step to take. [hasAssetSteps] is the one thing a null
 * cannot say: it is the difference between "this Task has one file" and "you are on the last of
 * its files", and only the first should make the pair disappear.
 */
@Immutable
data class MediaNavigator(
    /** The left zone: the Task being reviewed, or the file being viewed. */
    val title: String,
    val onBack: () -> Unit,
    /**
     * The left zone's second line: what the Task is currently marked with.
     *
     * A slot rather than a `List<String>` of labels, because the caller already has the item and
     * the settings and [ItemActionTags] is the one place that turns them into badges. It sits under
     * the title and not beside it because the title already competes with the file's name for the
     * bar, and a third thing in that row would take width from both.
     *
     * Null on every screen that shows no decisions — a viewer, a Task preview — which is what makes
     * this bar still one bar rather than a review chrome in a viewer's clothing.
     */
    val tags: (@Composable () -> Unit)? = null,
    /** The middle zone: the file on screen, where the item has more than one. */
    val subtitle: String? = null,
    /** The right zone's label, already written: `3(2/3)/7` for a queue with levels under it, `3 / 7` without. */
    val counter: String? = null,
    /** The queue's own step controls. */
    val onPrevious: (() -> Unit)? = null,
    val onNext: (() -> Unit)? = null,
    /** The step controls for the pages inside one item. */
    val onPreviousAsset: (() -> Unit)? = null,
    val onNextAsset: (() -> Unit)? = null,
    /** Whether the item on screen has pages to step through at all. */
    val hasAssetSteps: Boolean = false
)

/**
 * The frame every media screen shares, in three bands: the navigator, the media, and the bar
 * underneath the media that belongs to the item rather than to the file.
 *
 * **The two outer bands are the frame and the middle band is the file.** [navigator] says what is
 * being looked at and moves the queue; [taskBar] is what can be decided *about* the item; and
 * [assetControls] is the file's own controls - its tags, its Info, and a video's transport - which
 * appear over the media on a tap and go away on another.
 *
 * The two outer bands take real room in the stack rather than floating over the picture. That is
 * what makes the arrangement mean something: a control drawn over the media is the file's, and a
 * control given its own space is not. It is also what makes overlap impossible rather than merely
 * unlikely. The media is letterboxed between them, so it is slightly smaller than the display,
 * which is the price - and the reason [framePersists] exists, so a gallery that only ever wanted
 * the picture can still have the whole screen.
 *
 * [assetControls] is drawn over the media and is the only band [chromeVisible] reaches when
 * [framePersists] is set. Its measured height is published through [LocalMediaChrome], which is
 * how a video's transport knows to sit above the tag chips rather than under them.
 *
 * The background is black rather than a theme surface: ContentScale.Fit letterboxes almost
 * everything, and a photograph reads better against black in a light theme than a light theme
 * reads against a photograph. The bands are a scrim over that, with their contents forced to the
 * dark scheme by [ListeaMediaChromeTheme] so they stay legible whatever is behind them - which is
 * the whole of why an unthemed bar is a bug rather than a style.
 *
 * Toggling [chromeVisible] is not this composable's business. The gesture that does it belongs to
 * the media - a tap has to miss the bands to count - so the state is hoisted to the screen and
 * driven from the swipe card, which is also what keeps it independent of which item is showing.
 */
@Composable
fun MediaShell(
    navigator: MediaNavigator,
    taskBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    chromeVisible: Boolean = true,
    framePersists: Boolean = false,
    assetControls: (@Composable () -> Unit)? = null,
    media: @Composable () -> Unit
) {
    // What the dismissible band actually measured, so a video's own transport can sit above it
    // rather than under it. Reported as zero while it is down, which is when the band that
    // measured the height is not on screen to be avoided.
    var assetBarHeight by remember { mutableStateOf(0.dp) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        MediaChromeSlot(
            insets = WindowInsets.safeDrawing
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            visible = chromeVisible,
            persists = framePersists
        ) {
            MediaTopBar(navigator)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(
                LocalMediaChrome provides MediaChromeInsets(
                    visible = chromeVisible,
                    bottom = if (chromeVisible) assetBarHeight else 0.dp
                )
            ) {
                media()
            }

            assetControls?.let { controls ->
                MediaAssetControls(
                    visible = chromeVisible,
                    onHeightChange = { assetBarHeight = it },
                    controls = controls
                )
            }
        }

        MediaChromeSlot(
            insets = WindowInsets.safeDrawing
                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            visible = chromeVisible,
            persists = framePersists
        ) {
            taskBar()
        }
    }
}

/**
 * One of the frame's two outer bands.
 *
 * [persists] is the difference between a band that is the queue's and a band that is the chrome's.
 * A review's navigator and completion bar are the first: a tap is meant to clear the *file's*
 * controls, and taking the position or the checkbox away with them would be answering a question
 * nobody asked. A gallery's bars are the second, and there the whole point of the tap is that the
 * picture gets the screen to itself - so they collapse, and the media grows into the room.
 */
@Composable
private fun ColumnScope.MediaChromeSlot(
    insets: WindowInsets,
    visible: Boolean,
    persists: Boolean,
    content: @Composable () -> Unit
) {
    if (persists) {
        MediaChromeBar(insets = insets) { content() }
    } else {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            MediaChromeBar(insets = insets) { content() }
        }
    }
}

/**
 * The file's own controls, laid over the media at the bottom of the middle band.
 *
 * Laid over rather than given a band of its own, which is the opposite of what the two outer bands
 * do and the whole reason the two levels stay legible: the navigator and the checkbox are the same
 * whatever is on screen and are always there, while what can be *done to the file* depends on what
 * it is — a video's transport, a PDF's markup, an image's nothing — and comes and goes with a tap.
 * Taking real room for a band that disappears would move the media every time it was summoned.
 *
 * It still reports its height, because a bar laid over the media is a bar the media cannot use:
 * [MediaChromeInsets.bottom] is what lets a video lift its transport clear of it, so the two are
 * stacked rather than drawn on top of each other.
 */
@Composable
private fun BoxScope.MediaAssetControls(
    visible: Boolean,
    onHeightChange: (Dp) -> Unit,
    controls: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        // No system-bar inset: this band sits above the frame's own bottom bar, so the navigation
        // bar is already accounted for by the time it is drawn. Its scrim reaches the media's
        // edges, which is where the media stops.
        MediaChromeBar(insets = WindowInsets(0, 0, 0, 0), onHeightChange = onHeightChange) {
            controls()
        }
    }
}

/**
 * One scrimmed bar of chrome, laid over the media and reporting how much of it it hides.
 *
 * The scrim sits outside the inset padding, so it runs under the status or navigation bar rather
 * than stopping short of it and leaving the system icons on the bare photograph. The reported
 * height is the whole node, inset included, because that is the part of the media the caller
 * cannot use.
 *
 * The scrim is [MediaChromeScrim], which is PlayerControlView's own value; matching it is what
 * makes a video's controls and Listea's bars read as one dimmed layer instead of three.
 */
@Composable
private fun MediaChromeBar(
    insets: WindowInsets,
    onHeightChange: (Dp) -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) }
            .background(MediaChromeScrim)
            .windowInsetsPadding(insets)
    ) {
        ListeaMediaChromeTheme { content() }
    }
}

/**
 * The dim behind anything Listea draws over media, shared so a bar and the video's own volume
 * control cannot end up two different shades of black. 60% is PlayerControlView's own scrim value.
 */
val MediaChromeScrim = Color.Black.copy(alpha = 0.6f)

/**
 * Ties the system bars to Listea's own: both sets are up together, or neither is.
 *
 * A media screen owns the whole display while it is open, so it owns the status and navigation
 * bars too. Hiding the chrome hides them as well - that is what "the picture is as large as the
 * screen" has to mean - and the hide is the transient-by-swipe kind, so the bars are always one
 * gesture away and nothing here can strand the user. A tap on the media brings everything back
 * at once.
 *
 * The bar icons are forced light for as long as the screen is up, because what is behind them is
 * either black or a photograph, never the app's surface. [DisposableEffect] restores the
 * appearance and the bars themselves however the screen is left - back, a swipe, or the activity
 * going away.
 */
@Composable
fun MediaSystemBars(chromeVisible: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    }

    DisposableEffect(controller) {
        val active = controller ?: return@DisposableEffect onDispose { }
        val hadLightStatusBars = active.isAppearanceLightStatusBars
        val hadLightNavigationBars = active.isAppearanceLightNavigationBars
        active.isAppearanceLightStatusBars = false
        active.isAppearanceLightNavigationBars = false
        active.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            active.show(WindowInsetsCompat.Type.systemBars())
            active.isAppearanceLightStatusBars = hadLightStatusBars
            active.isAppearanceLightNavigationBars = hadLightNavigationBars
        }
    }

    // Keyed on the state rather than run on every recomposition: while the chrome is down the
    // user can still swipe the system bars back transiently, and re-issuing hide() from an
    // unrelated recomposition would take them away again mid-glance.
    LaunchedEffect(controller, chromeVisible) {
        val active = controller ?: return@LaunchedEffect
        if (chromeVisible) {
            active.show(WindowInsetsCompat.Type.systemBars())
        } else {
            active.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Back, what you are looking at, the file inside it, where you are in the queue, and how to step.
 *
 * Three zones left to right, and they are laid out by what can afford to give way. The two outer
 * zones measure at their natural size first - the back affordance and the counter's arrows are
 * both fixed-width controls - and the names share what is left. The title asks for what it needs
 * and no more, so a short one leaves the room to the file's name beside it rather than holding a
 * share it has no use for.
 *
 * There is a floor under that, because the two rules above cannot both hold on every screen. Arrows
 * at Material's 48dp, an ordinary counter and an item's name worth reading need more width than a
 * narrow phone at a large font scale has, and the name is what loses. When the bar is narrower than
 * [NavigatorStepCompactBelow] every control in it comes down to [NavigatorStepCompactSize] at once,
 * and the name is measured against that instead. 40dp is under the 48dp touch guideline and is a
 * deliberate trade rather than an oversight: a control that shrinks is a control that stops being
 * as easy to hit, but the name is what tells the user which item they are on, and 40dp is still a
 * target a thumb finds.
 *
 * The counter is bracketed by its own arrows - "‹ 3 / 12 ›" - rather than sitting apart from them,
 * because that is what they move: they are the position's own control, and reading them as a group
 * is what stops them being mistaken for anything that acts on the item. When the item has pages
 * under it there is a second, outer pair, so the label reads "3(2/3)/7" between four arrows: the
 * outer two move the queue and the inner two move the file. Grouped, they also take width from the
 * names in one place instead of two, and the names are the part that can afford to lose it.
 *
 * A control at an end is shown disabled rather than removed. Removing it would re-measure the bar
 * as the user reaches the end of a queue, sliding the counter and the remaining arrow sideways
 * under a finger that is still on the screen; and an arrow that is present and greyed says "this
 * is the end", which an absent one does not.
 *
 * The back affordance is the icon alone: where it goes is obvious from how you got here, and
 * "Back to List" cost a third of the bar to say so.
 */
@Composable
private fun MediaTopBar(navigator: MediaNavigator) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stepSize =
            if (maxWidth < NavigatorStepCompactBelow) NavigatorStepCompactSize else 48.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = navigator.onBack, modifier = Modifier.size(stepSize)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            val subtitle = navigator.subtitle
            val tags = navigator.tags
            Column(
                // Measured at its natural width and capped, never weighted. A weighted child is
                // held to its *share* of the leftover rather than to what it needs, and on a bar
                // this narrow the share is not enough for either name: split evenly, the item's
                // name came out as "Wha..." and the file's as "I...". Measured first, the item's
                // name reads properly and the file's name - the zone the spec sacrifices - takes
                // what the bar has left, which on the narrowest screens is nothing at all.
                modifier = if (subtitle == null) {
                    Modifier.weight(1f)
                } else {
                    Modifier.widthIn(max = NavigatorTitleMaxWidth)
                }
            ) {
                Text(
                    navigator.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The Task's tags, under its name and inside the same capped column, so a marked
                // Task reads as one block rather than as a line and a stray row of chips. The
                // component emits nothing for an unmarked Task, so the bar keeps its height.
                if (tags != null) {
                    Box(Modifier.padding(top = ListeaDimens.CompactGap)) { tags() }
                }
            }

            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // The only weighted child in the row, so it takes all of what the fixed zones
                    // leave. Where that is too little to show anything it ellipsizes to a stub
                    // rather than pushing the controls off the bar, and the Info sheet still names
                    // the file.
                    modifier = Modifier.weight(1f)
                )
            }

            navigator.counter?.let { counter ->
                Spacer(Modifier.width(ListeaDimens.RowGap))
                if (navigator.hasAssetSteps) {
                    MediaStepButton(
                        icon = Icons.Filled.KeyboardDoubleArrowLeft,
                        label = "Previous task",
                        size = stepSize,
                        onClick = navigator.onPrevious
                    )
                    MediaStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "Previous file",
                        size = stepSize,
                        onClick = navigator.onPreviousAsset
                    )
                } else {
                    MediaStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "Previous",
                        size = stepSize,
                        onClick = navigator.onPrevious
                    )
                }
                Text(
                    counter,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                if (navigator.hasAssetSteps) {
                    MediaStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "Next file",
                        size = stepSize,
                        onClick = navigator.onNextAsset
                    )
                    MediaStepButton(
                        icon = Icons.Filled.KeyboardDoubleArrowRight,
                        label = "Next task",
                        size = stepSize,
                        onClick = navigator.onNext
                    )
                } else {
                    MediaStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "Next",
                        size = stepSize,
                        onClick = navigator.onNext
                    )
                }
            }
        }
    }
}

/**
 * One step control, drawn whether or not there is anywhere to step to.
 *
 * A disabled [IconButton] never invokes its callback, so standing one in for the missing one is
 * how a null step becomes a greyed arrow rather than no arrow - see [MediaTopBar] for why the
 * control stays on screen at the ends of a queue.
 */
@Composable
private fun MediaStepButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    onClick: (() -> Unit)?
) {
    IconButton(
        onClick = onClick ?: { },
        enabled = onClick != null,
        modifier = Modifier.size(size)
    ) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * The band under the media: whether the item is done, and nothing else on it.
 *
 * It is the item's control and not the file's, which is why it is on the frame rather than over
 * the media. Checking something off is a judgment about the Task — one item with several files is
 * checked once, not once per file — and a judgment that a tap could make disappear is a judgment
 * the user has to hunt for.
 *
 * Centred and alone, now that the tags have moved off it. The row it used to share with them had
 * completion at one end and Info at the other, and the checkbox was the thing that made the middle
 * zone too narrow to hold an ordinary label on one line. Apart from them it also stops being
 * readable as a fourth chip: a checkbox is a different kind of thing from a tag, and the swipe
 * gesture performs this one and nothing else.
 */
@Composable
fun TaskCompletionBar(
    item: ReviewItem,
    onToggleCompleted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    reviewBlocked: ReviewBlock? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A blocked Task is shown and navigable but not submittable; the reason is said rather than
        // left for the refusal to explain. Completion by other means is untouched, so the checkbox
        // is disabled here but works from the List's own page.
        if (reviewBlocked != null) {
            Text(
                reviewBlocked.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            checked = item.isCompleted,
            onCheckedChange = onToggleCompleted,
            enabled = reviewBlocked == null,
            modifier = Modifier.semantics {
                contentDescription =
                    if (item.isCompleted) "Checked" else "Not checked"
            }
        )
    }
}

/**
 * What can be done about the *file* on screen: tag it, or ask what it is.
 *
 * The file's band and not the frame's, so a tap that clears the picture takes this with it and
 * the media gets the screen to itself. It is a scrimmed, darkened strip rather than bare controls
 * laid on the picture, which is what keeps a white chip legible over a white photograph — and
 * what keeps an unthemed icon from being drawn black on black, which is what the icons here did
 * while they shared a row with the frame's own bars instead of being in one.
 *
 * Every control edits the pair's record, through the caller's ViewModel calls, which is what keeps
 * a decision reached in Quick Review indistinguishable from one reached in Review — including the
 * webhook it may fire. Nothing here navigates, and nothing here completes: the checkbox that does
 * is on the frame, a band below.
 *
 * The tags are centred in what the Info button leaves and Info keeps the trailing edge. Info is
 * apart because it opens a panel rather than toggling anything, and should not be mistaken for
 * another chip that can be switched on. Both outer edges are a 48dp touch target, and the FlowRow
 * wraps rather than clipping: a pair of unusually long custom names costs a second line instead of
 * an unreachable control.
 *
 * **Both arguments are the file on screen, not the Task.** [control] is the Task's record resolved
 * for this Asset's kind — derived from the Asset's own name, so paging from a photograph to a video
 * inside one Task changes the row — and [decisions] is that same pair's record, so a chip ticked on
 * one page is not ticked on the next.
 *
 * v0.5.0 fixed the first of those and left the second reading the Task: a control resolved without
 * being told which file it was for cannot be wrong about one, and a Task-level record cannot be
 * right about one either. Both are why the pair is passed in as a pair, and why neither argument
 * can be reached for from the item.
 */
@Composable
fun ReviewAssetControls(
    decisions: AssetDecisions,
    control: ResolvedAssetControl,
    onToggleAction: (ItemAction, Boolean) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ListeaDimens.RowGap, vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(
                ListeaDimens.RowGap,
                Alignment.CenterHorizontally
            ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed order, so the row never reshuffles as actions are toggled.
            control.actions.forEach { action ->
                val isSet = action.isSetOn(decisions)
                if (action == ItemAction.Favourite) {
                    // The only action whose meaning is fixed, so it is the only one shown as a
                    // symbol; the custom ones say whatever the control that governs this file has
                    // named them.
                    FilterChip(
                        selected = isSet,
                        onClick = { onToggleAction(action, !isSet) },
                        label = {
                            Icon(
                                if (isSet) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favourite",
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                } else {
                    FilterChip(
                        selected = isSet,
                        onClick = { onToggleAction(action, !isSet) },
                        label = {
                            Text(
                                control.labelOf(action),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // A custom name can be anything; past this it stops being a chip
                                // and starts being a paragraph that pushes the group off centre.
                                modifier = Modifier.widthIn(max = CustomActionLabelMaxWidth)
                            )
                        }
                    )
                }
            }
        }

        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "Item info")
        }
    }
}

private val CustomActionLabelMaxWidth = 88.dp

/**
 * What a Task is currently marked with: Favourite, and every configured custom action it carries,
 * one badge each.
 *
 * Lives here rather than in one of its two callers because there are now two, and they are two
 * halves of the same answer: a List's row shows the tags *before* a review, and a review surface
 * shows them *while* one is happening — and a tag set in either has to appear in the other the
 * moment it is set. Both draw it from the same resolved control, which is what makes that true
 * rather than two renderings that agree until one of them is changed.
 *
 * **This row is the Task's, and both of its arguments are therefore Task-wide.** It is drawn under
 * a Task's name, where no particular Asset is on screen, so it takes [ReviewItem.taskDecisions] —
 * the fold of the Task's pairs — and [control] resolved over every kind the Task is configured for
 * rather than over the kind of any one file. A tag set on a video has to be nameable while a
 * photograph is being shown; resolving per file would make the badge read "unknown id" for a tag
 * the user can see, or drop it entirely. That is the one place the chip row and this row
 * legitimately differ, and the difference is the design rather than a drift —
 * [TemplateResolver.controlForTask] is the resolver for this and [TemplateResolver.controlFor] the
 * one for the chips.
 *
 * The two are taken as arguments rather than read off a `ReviewItem` since v0.5.1, which is the
 * same move v0.5.0 made when it replaced `AppSettings` with a resolved control: a composable left
 * to find the marks itself is a composable that can find the wrong ones, and finding the Task's
 * where the file's were asked for is precisely the bug this release closes. A caller that means
 * one file's marks passes that pair's, and one that means the Task's passes the fold — both of
 * which are [AssetDecisions], so the type says which question is being asked.
 *
 * One badge per action rather than a single joined string, because these are independent flags and
 * the row is read at a glance — "★  Save" as one line invites reading it as one label. Favourite is
 * tinted, the custom ones are not: the star is the only one whose meaning is fixed, and the rest
 * say whatever the control that governs them has named them.
 *
 * Drawn from the configured actions rather than from the decisions, so an id left behind by a
 * deleted action stays on the row and off the screen. Emits nothing at all for an item with no
 * actions set, so an untagged row stays quiet — which is what lets the review surface call it
 * unconditionally.
 */
@Composable
fun ItemActionTags(
    decisions: AssetDecisions,
    control: ResolvedAssetControl,
    modifier: Modifier = Modifier
) {
    val tags = control.actions.filter { it.isSetOn(decisions) }
    if (tags.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
    ) {
        tags.forEach { action ->
            ListeaBadge(
                text = control.labelOf(action),
                container = if (action == ItemAction.Favourite) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (action == ItemAction.Favourite) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * How much of the navigator bar the item's name may take before it ellipsizes.
 *
 * A ceiling and not a share: the name is measured at the size it needs up to here, and the file's
 * name beside it takes what the bar has left. It exists so that a long Task name cannot take the
 * whole bar and leave the counter and the controls crowded against the edge. On a bar too narrow to
 * give the file's name a readable share even so, the item's name wins and the file's name goes to a
 * stub - the file is named in the Info sheet, and the item's name is what tells you where you are.
 */
private val NavigatorTitleMaxWidth = 120.dp

/**
 * The width below which the navigator's controls come down from Material's 48dp to
 * [NavigatorStepCompactSize].
 *
 * The arithmetic behind the number, so that a later change to the bar has something to check
 * against. On a bar wide enough to have both names, the fixed zones are five controls at 48dp
 * (240dp) and a counter of up to six glyphs (about 55dp); the item's name and the file's name
 * share what is left, and the item's name alone needs about 70dp to say "Whatsapp". Add a margin
 * for the horizontal inset the bar does not own and the total is around 365dp. Read it as "the
 * controls give way first, and only once the name has already been reduced to nothing would
 * shrinking them further buy anything".
 */
private val NavigatorStepCompactBelow = 365.dp

/**
 * What a navigator control measures on a bar too narrow to give it 48dp.
 *
 * Under the guideline on purpose — see [MediaTopBar] for the trade it makes — and not much under it.
 * It is a chosen compromise, not a measured minimum: it is only ever used when the alternative is
 * an item's name too short to identify the item, and it is bound to be revisited if it turns out to
 * be hard to hit in practice.
 */
private val NavigatorStepCompactSize = 40.dp

/**
 * The bottom bar of a viewer that has no actions: one control, and all it does is open a panel.
 *
 * Shared by the folder File Viewer and the list item viewer, which are both deliberately unable
 * to change anything about what they are showing. That they look identical is the point - one is
 * reached from a folder and the other from a list, and neither is a place where decisions get
 * made, so neither should grow a control that suggests otherwise.
 */
@Composable
fun MediaInfoBar(contentDescription: String, onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ListeaDimens.RowGap),
        horizontalArrangement = Arrangement.End
    ) {
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = contentDescription)
        }
    }
}

/** One labelled fact about whatever the media screen is showing. */
data class InfoField(val label: String, val value: String)

/**
 * Everything the media screen deliberately stopped saying, on request.
 *
 * A sheet rather than a panel: it is read occasionally and the screen is for the media, so it
 * costs nothing until it is asked for and gives the space straight back. Its content scrolls,
 * which is what keeps it usable in landscape where the sheet itself is short.
 *
 * [rows] are built by the caller. The File Viewer has no list, no completion and no actions, and
 * builds four rows about a file; Review builds rather more. Neither is handed the other's fields
 * to leave blank.
 *
 * [footer] is where the sheet stops describing and starts offering — currently [MediaFileActions]
 * and nothing else. It sits below the last row rather than beside the title because a control
 * that copies a file is not a fact about it, and putting it at the end means reaching it is a
 * deliberate scroll past everything the sheet had to say.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemInfoSheet(
    title: String,
    rows: List<InfoField>,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
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
        ) {
            // The full value of what the bar had to ellipsize, wrapped rather than cut.
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            rows.forEach { row ->
                Text(
                    row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(ListeaDimens.RowGap))
            }
            footer?.let {
                Spacer(Modifier.height(ListeaDimens.RowGap))
                it()
            }
        }
    }
}

/**
 * The two things every Info sheet ends with: keep a copy of this file, or hand it to something
 * else — and one line under whichever of them last had something to say.
 *
 * Shared by all three review surfaces — Full Review, Quick Review and the plain viewers — because
 * the offer is the same in each: whatever you are looking at, this is what can be done with the
 * file itself. Neither control is wired to the action chips. Both take effect immediately and by
 * themselves, with no list, no tag and no webhook involved, which is what keeps them available on
 * the read-only viewers that have no chips at all.
 *
 * Save leads because it is the one with a destination Listea controls and can report on. Share
 * ends at a chooser full of apps this code has never heard of, so it is the quieter of the two
 * and says nothing at all when it works — see [shareOutcomeMessage].
 *
 * Both states are keyed on [sourceUri], so paging to the next item in a sheet that stayed open
 * resets them rather than leaving the previous file's "Saved" under the new file's name.
 *
 * The permission launcher is only ever used below Android 10; see [needsLegacyStoragePermission].
 * Saving is driven from a token the click bumps rather than from the click itself, because the
 * permission result has to reach the same code path the direct click does, and a launcher
 * callback cannot call a function declared after it. Sharing needs none of that — it writes only
 * inside Listea's own cache — so it runs straight off the click.
 */
@Composable
fun MediaFileActions(sourceUri: String, name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var runToken by remember(sourceUri) { mutableStateOf(0) }
    var saving by remember(sourceUri) { mutableStateOf(false) }
    var outcome by remember(sourceUri) { mutableStateOf<SaveToAlbumResult?>(null) }

    var sharing by remember(sourceUri) { mutableStateOf(false) }
    var shareOutcome by remember(sourceUri) { mutableStateOf<ShareResult?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runToken++ else outcome = SaveToAlbumResult.PermissionDenied
    }

    LaunchedEffect(sourceUri, runToken) {
        if (runToken == 0) return@LaunchedEffect
        saving = true
        outcome = saveToListeaAlbum(context, sourceUri, name)
        saving = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Wraps rather than shrinking either button: a long album name and a translated Share
        // label can outgrow a narrow sheet, and a clipped button is worse than a second line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
            FilledTonalButton(
                onClick = {
                    outcome = null
                    if (needsLegacyStoragePermission(context)) {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        runToken++
                    }
                },
                // Re-saving is harmless — it reports "already there" — but the button is still
                // held while a copy is in flight, so a double tap cannot start a second one.
                enabled = !saving
            ) {
                Icon(
                    Icons.Outlined.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(ListeaDimens.IconSize)
                )
                Spacer(Modifier.width(ListeaDimens.RowGap))
                Text(if (saving) "Saving…" else "Save to $ListeaAlbumName")
            }

            OutlinedButton(
                onClick = {
                    shareOutcome = null
                    sharing = true
                    scope.launch {
                        shareOutcome = shareMediaFile(context, sourceUri, name)
                        sharing = false
                    }
                },
                // Held for the copy, which is the part that takes any time. Once the chooser is
                // up the button is live again: sharing the same file twice is a normal thing to
                // want and costs nothing but the same copy written again.
                enabled = !sharing
            ) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(ListeaDimens.IconSize)
                )
                Spacer(Modifier.width(ListeaDimens.RowGap))
                Text(if (sharing) "Preparing…" else "Share")
            }
        }

        outcome?.let { result ->
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Text(
                saveOutcomeMessage(result),
                style = MaterialTheme.typography.bodySmall,
                color = when (result) {
                    is SaveToAlbumResult.Saved -> MaterialTheme.colorScheme.primary
                    is SaveToAlbumResult.AlreadySaved ->
                        MaterialTheme.colorScheme.onSurfaceVariant

                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
        // Only ever a problem: a share that opened the chooser has already said so, on screen and
        // more loudly than a line of text could.
        shareOutcome?.let(::shareOutcomeMessage)?.let { message ->
            Spacer(Modifier.height(ListeaDimens.CompactGap))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * What the provider knows about a file that Listea does not store: its type, its size and when it
 * was last written.
 *
 * Nothing is kept for these on a list item, and asking per frame while the user swipes would be
 * one SAF round trip per card. So they are read once, when the Info sheet opens, and the sheet
 * renders without them until the answer arrives.
 */
data class MediaFacts(
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** Blocking I/O, moved off the main thread here rather than at every call site. */
suspend fun readMediaFacts(context: Context, uri: String): MediaFacts = withContext(Dispatchers.IO) {
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return@withContext MediaFacts()
    val document = runCatching { DocumentFile.fromSingleUri(context, parsed) }.getOrNull()
    MediaFacts(
        mimeType = runCatching { context.contentResolver.getType(parsed) }.getOrNull(),
        sizeBytes = runCatching { document?.length() }.getOrNull()?.takeIf { it > 0 },
        lastModified = runCatching { document?.lastModified() }.getOrNull()?.takeIf { it > 0 }
    )
}

/**
 * The type, size and modified rows every Info sheet ends with, from whatever is known.
 *
 * [fallbackSize] and [fallbackModified] are for the File Viewer, whose folder listing already
 * carries both — it has no reason to wait for a lookup to say what it already knows.
 */
fun mediaFactRows(
    facts: MediaFacts,
    name: String,
    fallbackSize: Long? = null,
    fallbackModified: Long? = null
): List<InfoField> = buildList {
    val type = facts.mimeType ?: name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
    type?.let { add(InfoField("Type", it)) }
    (facts.sizeBytes ?: fallbackSize)?.let { add(InfoField("Size", formatSize(it))) }
    (facts.lastModified ?: fallbackModified)?.let { add(InfoField("Modified", formatTimestamp(it))) }
}
