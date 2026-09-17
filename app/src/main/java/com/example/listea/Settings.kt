package com.example.listea

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ListItemEntity
import com.example.listea.data.newActionId
import com.example.listea.domain.AssetKind
import com.example.listea.domain.TemplateDefinition
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates

/**
 * App-wide configuration: the settings that outlive any one list, folder or review.
 *
 * Deliberately not in Room. These belong to the app, not to a list, and keeping them out of the
 * database is what lets an action be renamed without touching a single item row.
 *
 * The folders the app may look at are not here either, and no longer in a preferences file of their
 * own: since v16 they are rows in `upstreams`, so that one folder can carry several Todos and the
 * app can hold more than one folder at all. The SAF grant is still what makes one readable — the
 * row and the permission are two halves of the same fact, and neither replaces the other.
 */
data class AppSettings(
    val folderQuickReviewEnabled: Boolean = true,

    /**
     * The tags a *new* Todo starts with, per file kind.
     *
     * A seed rather than the live configuration, which is the change v0.5.0 makes: the answer a
     * Todo actually uses is recorded on the Todo and on each of its Tasks, and editing this decides
     * only what the next import run is offered. [defaultContentPolicy] is the same kind of statement
     * and is documented as one.
     *
     * Keyed by kind because a tag row is a function of two things, and this is the half Settings
     * knows. Images and video start with the same list, which is what the app did when the list was
     * app-wide; they are separate entries so that editing one does not silently move the other.
     *
     * A kind absent from this map is not "no tags" — see [controlFor], which falls back to
     * [DefaultCustomActions] rather than to nothing.
     */
    val assetControlTags: Map<AssetKind, List<CustomAction>> = DefaultAssetControlTags,

    val defaultWebhookEnabled: Boolean = false,
    val defaultWebhookUrl: String = "",

    val videoAutoplay: Boolean = true,
    val videoStartMuted: Boolean = false,

    /**
     * Whether full-list Review resumes where it left off. True is the long-standing behaviour and
     * stays the default; Quick Review is folder-scoped and temporary, so it always starts at the
     * first unchecked item regardless.
     *
     * This decides what is *read* on entry, never what is written: the position keeps being
     * recorded either way, so switching back on resumes from where Review actually got to rather
     * than from wherever it was when the setting was switched off.
     */
    val rememberReviewPosition: Boolean = true,

    /**
     * Whether opening a folder-backed list scans its source folder to see if it has changed.
     *
     * On is the long-standing behaviour. Off suppresses only the *automatic* scan: "Update from
     * folder" still works, and so does the Quick Review gate, because both are things the user
     * asked for. With it off a list reports [SourceFreshness.NotChecked] rather than an
     * up-to-date verdict nothing has established.
     */
    val autoCheckSourceFreshness: Boolean = true,

    /**
     * Whether finishing a Quick Review queue delivers a webhook of its own.
     *
     * Quick Review has no webhook configuration of its own and is not going to get one: it is
     * folder-scoped and temporary, so it posts to [defaultWebhookUrl] as it stands right now.
     * This switch is the whole gate — [defaultWebhookEnabled] is only a template for new lists
     * and has no say here — and an empty default URL is reported exactly like any other unusable
     * one rather than passing silently.
     *
     * Off by default, which is the behaviour Quick Review has always had: it edits the owning
     * list's real items, so a list that *completes* during a Quick Review still fires that list's
     * own `list.completed` webhook either way.
     */
    val quickReviewWebhookEnabled: Boolean = false,

    /**
     * Whether a payload carries only the items that are checked.
     *
     * Global: it applies to every event, the test webhook included, so what the test button sends
     * is still what a real delivery would send. Off is the long-standing behaviour, where a
     * receiver sees every item and decides for itself using `isCompleted`.
     *
     * Nothing is filtered out of a *review*, only out of what is sent. Pair it with
     * [reviewUncheckedOnly] to deliver just the decisions of one round.
     */
    val webhookCompletedItemsOnly: Boolean = false,

    /**
     * Whether entering a review queues only the items that are not checked yet.
     *
     * The queue is fixed when the review opens, not re-evaluated as items are checked: an item
     * checked mid-review stays in front of the user and stays swipeable backwards. Leaving and
     * re-entering is what starts a new round.
     *
     * Off is the long-standing behaviour, where a review walks everything it is given.
     */
    val reviewUncheckedOnly: Boolean = false,

    /**
     * Whether one filter and one sort order are shared by every folder and every list, or each
     * of them keeps its own.
     *
     * On by default, which is the answer that matches what setting a filter usually means: a user
     * who asks for "videos, newest first" is describing how they want to browse, not how they
     * want to browse *this* folder. Off is the richer behaviour and costs a per-folder memory,
     * which is why it is the one you opt into.
     *
     * Switching it never discards anything. Both the shared arrangement and the per-listing ones
     * are kept whichever way it is set, so turning it on to sweep through a root and turning it
     * back off returns every folder and list to what it had — see [FolderArrangements].
     */
    val sharedFileArrangement: Boolean = true,

    /**
     * Whether a webhook that went out is followed by an offer to delete the files it carried as
     * checked.
     *
     * Deliberately worded around the *webhook* rather than around the review, because that is
     * exactly what it acts on: the files offered are the checked ones in the payload that was
     * just sent, and nothing else. Every other switch here has already had its say by then —
     * [reviewUncheckedOnly] decided what the round walked, [webhookCompletedItemsOnly] decided
     * what the payload carried — so this one never needs to reason about them, and the offer
     * never covers a file the delivery did not.
     *
     * Only a delivery that actually succeeded leads to the offer. One that failed leaves every
     * file where it is and says so: the round can still be resent from the webhook history, and
     * deleting the files it described would be deleting the evidence of a round nobody has
     * received.
     *
     * Off by default. It is the only switch in the app whose "yes" destroys something outside
     * Listea's own database, and it is one confirmation away from doing it right after a swipe.
     */
    val askDeleteAfterWebhook: Boolean = false,

    /**
     * What a new Todo's Tasks do when their content changes: `keep`, `reset` or `drop`.
     *
     * A *seed*, not a policy — the answer is given per Todo in the Create Tasks dialog, written
     * back there, and has no say afterwards. Settings is where a user states the answer they
     * usually give, so the dialog opens on it rather than on a value they have to replace every
     * time; an existing Todo's own stored answer is what its dialog opens on instead.
     *
     * `keep` by default, per the rule in `docs/settings.md`: before v0.4.0 step 2 a re-import
     * changed no Task's state at all, so `keep` is where a fresh install and an untouched Settings
     * page already are. `reset` is deliberately not the default however useful it is — a default
     * cannot be seen at the moment it acts, and this one clears judgments.
     */
    val defaultContentPolicy: ContentPolicy = ContentPolicy.Default,

    /**
     * Whether a Todo still to be created follows its upstream.
     *
     * The same kind of statement as [defaultContentPolicy] and seeded the same way, but this one
     * is a standing instruction rather than a default: once it is on the Todo, entering it runs the
     * import that keeps it in step, and the dialog on that first run is the only time it was ever
     * asked about. Off, which is what every version before this one did.
     *
     * The two are not offered together in the dialog for the reason above: a policy is a choice
     * about a run the user is looking at, and this is a choice about runs that have not happened.
     * The Todo card's own overflow is where it is changed.
     */
    val followUpstreamNewTodos: Boolean = false,

    /**
     * Whether leaving a review delivers a webhook for the queue that was just reviewed.
     *
     * Full Review uses the list's own webhook configuration, exactly as its completion does;
     * Quick Review uses [defaultWebhookUrl] and is additionally gated on
     * [quickReviewWebhookEnabled], because that switch is what makes Quick Review send anything
     * at all. Either way the payload covers the review's own queue, not the whole list.
     *
     * A queue that finished on screen has already sent, and is not sent again on the way out.
     */
    val webhookOnReviewExit: Boolean = false
) {
    /**
     * The control governing one Asset, from what its Task recorded and the kind of the file.
     *
     * The whole of the lookup, and the only way any surface is meant to reach an action row. One
     * call per Asset rather than one per screen, because the answer changes as a user pages from a
     * photograph to a video inside a single Task — which is the release.
     *
     * **Never null.** A kind with nothing recorded falls back to this app's tags for that kind, so
     * there is one rule — *no recorded configuration for this kind means the app's current default
     * for it* — rather than one for an empty column and another for a partly-filled one. The case
     * it removes is a Task whose folder held only photographs when it was imported and a video
     * later: a control-less chip row showing nothing but `★` is an answer nobody chose, and the
     * default is at least the one the dialog would have offered.
     *
     * [recorded] is the raw column, not a decoded map, so no caller can hold a half-resolved value:
     * the decoding, the per-kind fallback and the definition lookup happen together or not at all.
     */
    fun controlFor(recorded: String, kind: AssetKind): ResolvedAssetControl {
        val config = decodeAssetControls(recorded)[kind] ?: defaultConfigFor(kind)
        return ResolvedAssetControl(
            favourite = favouriteOf(config.ref),
            tags = config.tags
        )
    }

    /**
     * The control governing a whole Task: the tags of every kind it is configured for, together.
     *
     * For the two surfaces that speak about the Task rather than about the file in front of the
     * user — the badge row under the filename, and the webhook payload, which carries one entry per
     * Task. A tag set on a video has to be nameable while a photograph is on screen, or the badge
     * would report an unknown id for a tag the user can see.
     *
     * Ordered: `★` first, then kinds in declaration order, then each kind's tags in configured
     * order, first occurrence winning. Stable because it decides the order of a webhook's `actions`
     * array, and an array that reshuffles itself is one a receiver cannot diff.
     */
    fun controlForTask(recorded: String): ResolvedAssetControl {
        val decoded = decodeAssetControls(recorded)
        val kinds = AssetKind.entries.filter { it != AssetKind.OTHER }

        val seen = mutableSetOf<String>()
        val tags = kinds.flatMap { kind ->
            (decoded[kind] ?: defaultConfigFor(kind)).tags
        }.filter { seen.add(it.id) }

        return ResolvedAssetControl(
            // Any control carrying `★` is enough for the Task to offer it: it is one action, not one
            // per kind, and an item stores one boolean for it.
            favourite = kinds.any { favouriteOf((decoded[it] ?: defaultConfigFor(it)).ref) },
            tags = tags
        )
    }

    /** This app's tags for a kind that was never configured, as a config naming the built-in. */
    private fun defaultConfigFor(kind: AssetKind): AssetControlConfig = AssetControlConfig(
        ref = Templates.MediaAssetControl.ref,
        tags = assetControlTags[kind] ?: DefaultCustomActions
    )

    /**
     * The app's tags as configurations naming the built-in — what the Create Tasks dialog opens on.
     *
     * The same value [controlFor] falls back to, reached from the other direction: that one is
     * asked with a column and fills a gap, this one is asked before there is a column at all. Kept
     * as one expression so the dialog's pre-fill and the runtime fallback cannot come to differ —
     * a dialog offering something other than what an unanswered kind would get is a difference the
     * user would only ever see as a bug.
     *
     * `OTHER` is absent, because nothing may be configured for it.
     */
    fun defaultControlConfigs(): Map<AssetKind, AssetControlConfig> =
        AssetKind.entries
            .filter { it != AssetKind.OTHER }
            .associateWith { defaultConfigFor(it) }

    /**
     * A recorded column as a complete configuration — the shape a run and the dialog both take.
     *
     * [controlFor] answers *what one file offers*; this answers *what a run is to be given*, and the
     * difference is completeness. A run has to stamp both kinds, not only the ones that happen to be
     * named in the column, or a Todo that has never been configured would write `''` over a Task
     * that had — so a kind the column leaves out is filled with this app's default for it, exactly as
     * [controlFor] would have resolved it for a reader.
     *
     * This is why a refresh does not simply pass `decodeAssetControls(source.assetControls)` along:
     * that map is *partial* by nature, and partial here means "delete the configuration of every kind
     * the user never touched", one run at a time.
     */
    fun controlConfigsFor(recorded: String): Map<AssetKind, AssetControlConfig> =
        defaultControlConfigs() + decodeAssetControls(recorded)

    /**
     * Whether the definition a config names carries `★`.
     *
     * Read from the definition rather than from a constant, because `★` being a property of the
     * template rather than of the review surface is the point of it living there at all. An
     * unknown reference — a config written by a build whose control this one has never heard of —
     * falls back to the built-in's answer, which is `true`: starring has never been optional in
     * any shipped version, and dropping it would lose the one action no user configured.
     */
    private fun favouriteOf(ref: TemplateVersionRef): Boolean =
        (Templates.byRef(ref)?.definition as? TemplateDefinition.AssetControl)?.favourite
            ?: MediaAssetControlDefinition.favourite
}

/**
 * One configured action: what it is called, what it is sent as, and what items store to say they
 * carry it.
 *
 * [id] is the only part an item ever holds. [displayName] and [webhookValue] are free to change
 * under it — that separation is the whole reason renaming an action does not touch a single row —
 * and neither is required to be unique, because two actions that look alike or send alike are odd
 * rather than dangerous.
 */
@Immutable
data class CustomAction(
    val id: String,
    val displayName: String,
    val webhookValue: String
)

/**
 * What a fresh install starts with, and what the two original slots migrate to.
 *
 * The ids are the migration: an item marked with the old `custom1` column is written out as the
 * id `custom1`, so it keeps pointing at the action the user marked it with rather than becoming
 * an orphan on upgrade.
 */
val DefaultCustomActions: List<CustomAction> = listOf(
    CustomAction(id = "custom1", displayName = "C1", webhookValue = "cust1"),
    CustomAction(id = "custom2", displayName = "C2", webhookValue = "cust2")
)

/**
 * The tags every kind starts with, which is the same list for both.
 *
 * Written as two entries rather than as a `withDefault`, because the two are independent from here:
 * a user who gives video its own two tags must not find that images moved as well, and a map whose
 * absent keys answer with a shared list makes that failure a matter of remembering.
 */
val DefaultAssetControlTags: Map<AssetKind, List<CustomAction>> = mapOf(
    AssetKind.IMAGE to DefaultCustomActions,
    AssetKind.VIDEO to DefaultCustomActions
)

/** The built-in's shape, for the one place that needs an answer before a definition is known. */
private val MediaAssetControlDefinition: TemplateDefinition.AssetControl =
    Templates.MediaAssetControl.definition as TemplateDefinition.AssetControl

/**
 * One kind's configuration: which definition governs it, and the tags it was given.
 *
 * The definition is a *reference*, never a copy. That is what makes a stored configuration
 * meaningful after the definition it named moves on — it still says which control these tags were
 * chosen for, and a later reader that wants to know what that control looked like can look it up.
 *
 * [tags] is a `List<CustomAction>` rather than a type of its own. A tag is exactly what an action
 * already is — an id nothing shows, a name, and a wire value — so the id generator, the sanitiser
 * and the item-side encoding all apply to it with no second implementation to keep in step.
 */
@Immutable
data class AssetControlConfig(
    val ref: TemplateVersionRef,
    val tags: List<CustomAction>
)

/**
 * An asset control with its configuration resolved: what this file's row actually offers.
 *
 * The completed answer, and the only shape any surface takes. Built from a definition and a
 * configuration by [AppSettings.controlFor] or [AppSettings.controlForTask], and thereafter
 * self-contained — a chip row, an Info sheet and a payload all resolve labels and wire values from
 * one of these rather than from settings, which is what stops them disagreeing about which kind
 * they are describing.
 */
@Immutable
data class ResolvedAssetControl(
    val favourite: Boolean,
    val tags: List<CustomAction>
) {
    /**
     * Every action the row offers, `★` first and the tags in their own order.
     *
     * The one place that order is decided, so the chip row, the badge and the payload cannot
     * disagree about it.
     */
    val actions: List<ItemAction>
        get() = (if (favourite) listOf(ItemAction.Favourite) else emptyList()) +
            tags.map { ItemAction.Custom(it.id) }

    /** What the user sees this action called. `★` is not configurable, so it answers for itself. */
    fun labelOf(action: ItemAction): String = when (action) {
        ItemAction.Favourite -> ItemAction.Favourite.Label
        is ItemAction.Custom -> tags.firstOrNull { it.id == action.id }?.displayName ?: action.id
    }

    /**
     * What the webhook calls this action. Resolved here, at delivery time, and never stored on an
     * item: an item's set means "the tag with this id is selected", not "the string `price` was
     * selected", so renaming the wire value changes future payloads without migrating a single row.
     *
     * An id these tags do not define answers with the id itself rather than with null. That case is
     * unreachable from [actions] — which is built *from* the tags — and the reason it is written
     * this way is that a null here would have to be handled by every caller to describe a state
     * none of them can be in. What actually keeps a deleted tag out of a payload is [actions]
     * omitting it, not this: a tag deleted from a control leaves the id on the items that carry it,
     * and stops being offered, labelled and sent, all by the same omission.
     */
    fun wireOf(action: ItemAction): String = when (action) {
        ItemAction.Favourite -> ItemAction.Favourite.WireName
        is ItemAction.Custom -> tags.firstOrNull { it.id == action.id }?.webhookValue ?: action.id
    }

    companion object {
        /** The action set of a Task with nothing recorded and no defaults — `★` and nothing else. */
        val FavouriteOnly = ResolvedAssetControl(favourite = true, tags = emptyList())
    }
}

/**
 * The file kinds an asset control may be configured for: the built-in's own answer, in declaration
 * order.
 *
 * Read from the definition rather than written out as `listOf(IMAGE, VIDEO)`, because the *only*
 * thing that makes a tag settable for a kind is that a control accepts that kind — `OTHER` is not
 * configurable because [Templates.MediaAssetControl] does not accept it, not because a screen
 * happens to leave it out. A second control that accepted a third kind would then be a definition
 * change and nothing else.
 *
 * Declaration order rather than the set's, so the sections a user sees do not reshuffle between
 * builds. [AppSettings.controlForTask] orders by the same rule for the same reason.
 */
fun configurableAssetKinds(): List<AssetKind> {
    val accepted =
        (Templates.MediaAssetControl.definition as TemplateDefinition.AssetControl).compatibleKinds
    return AssetKind.entries.filter { it in accepted }
}

/**
 * What one kind's asset control is called, everywhere it is named.
 *
 * One function rather than a literal at each surface, because the whole point of the phrase is that
 * a user meets the *same words* twice: Settings asks the question under this heading, the Create
 * Tasks dialog asks it under this heading, and the Todo card answers it under this heading. A card
 * that called it something else would make the user learn a second name for an answer they already
 * gave — the same rule `todoContentPolicyLabel` states for the tier beside it.
 *
 * `OTHER` has a heading even though nothing offers one, so this cannot be the thing that decides
 * which kinds are configurable: that is [TemplateDefinition.AssetControl.compatibleKinds], and a
 * heading for a kind nobody asks about is harmless where a missing one would be a crash.
 */
fun assetControlHeading(kind: AssetKind): String = when (kind) {
    AssetKind.IMAGE -> "Image asset control"
    AssetKind.VIDEO -> "Video asset control"
    AssetKind.OTHER -> "File asset control"
}

/**
 * How the action list is stored: one record per line, three tab-separated fields.
 *
 * Hand-rolled rather than JSON because org.json is Android's and this has to be readable by a
 * host test. The two separators are stripped from anything the user types on the way in, so a
 * display name can be anything else at all without being able to corrupt the record after it.
 */
fun encodeCustomActions(actions: List<CustomAction>): String =
    actions.joinToString("\n") { "${it.id}\t${it.displayName}\t${it.webhookValue}" }

/**
 * Tolerant on purpose: a record with no id is dropped, a record missing later fields keeps what
 * it has, and an id carrying a comma is refused because that is the separator items store with.
 * Anything unreadable costs its own line rather than the whole list.
 */
fun decodeCustomActions(text: String): List<CustomAction> =
    text.split('\n').mapNotNull { line ->
        val fields = line.split('\t')
        val id = fields.getOrNull(0)?.trim().orEmpty()
        if (id.isEmpty() || id.contains(',')) return@mapNotNull null
        CustomAction(
            id = id,
            displayName = fields.getOrNull(1).orEmpty(),
            webhookValue = fields.getOrNull(2).orEmpty()
        )
    }

/**
 * Keeps typed text to one line, so it cannot break the record it is stored in.
 *
 * `\t` and `\n` separate *records* — a line is an action, a tab is a field within it. `|` and `:`
 * separate the parts of an asset-control tag, which is a record nested one level deeper: the tags
 * of a kind live in a single field of the line above them. Stripping all four in one place is what
 * lets a tag's display name be anything at all without the format having to be thought about again,
 * and it is why there is one sanitiser rather than one per encoding.
 */
fun sanitizeActionText(value: String): String = value
    .replace('\t', ' ')
    .replace('\n', ' ')
    .replace('\r', ' ')
    .replace('|', ' ')
    .replace(':', ' ')
    .trim()

/**
 * The asset-control configuration as stored on a `sources` or `task_imports` row.
 *
 * One record per kind, four tab-separated fields: the kind's key, the template id, the version, and
 * the tags. Line-per-kind rather than a nested bag because both halves of the outer format already
 * exist — [encodeCustomActions] writes actions this way, and the fields are the same three.
 *
 * ```text
 * image	builtin.media.asset-control	1	a1:Price:price|a2:Quality:quality
 * video	builtin.media.asset-control	1	a3:Fresh:fresh
 * ```
 *
 * The template id and version are recorded rather than left implicit in the schema default, because
 * a configuration outlives the definition it was chosen for: once a second control version exists,
 * "which control were these tags picked for" is a question with more than one answer, and a column
 * that cannot say is a column whose reader has to guess. They are *not* hashed into anything — a
 * configuration is not part of a definition — so this is a record, not a commitment.
 *
 * An empty map writes `""`, which is the column default and means *nothing was ever chosen*. That
 * is not the same as an empty tag set, and [AppSettings.controlFor] is the only thing that should
 * ever interpret it. See the migration comment for what breaking that reading would cost.
 */
fun encodeAssetControls(controls: Map<AssetKind, AssetControlConfig>): String =
    controls.entries
        // Declaration order rather than the map's, so the same configuration always writes the same
        // string — storage that varies with iteration order looks changed when nothing has.
        .sortedBy { (kind, _) -> kind.ordinal }
        .joinToString("\n") { (kind, config) ->
            val tags = config.tags.joinToString("|") {
                "${it.id}:${it.displayName}:${it.webhookValue}"
            }
            "${kind.key}\t${config.ref.templateId.value}\t${config.ref.version}\t$tags"
        }

/**
 * Tolerant in the same way [decodeCustomActions] is, and for the same reason: this reads rows a
 * previous build wrote, so every failure costs its own record and never the whole column.
 *
 * A line whose kind this build does not know is dropped, a line with no template id is dropped, a
 * tag with an empty id or with a comma in it is dropped — the comma is what items store with, see
 * [encodeActionIds] — and a tag with no display name keeps the empty one rather than inventing a
 * label. An unparseable version reads as 1 rather than dropping the line, because 1 is the only
 * version any shipped build has written and a control with no tags is worse than a control whose
 * version is wrong but whose tags survive.
 */
fun decodeAssetControls(text: String): Map<AssetKind, AssetControlConfig> =
    text.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = line.split('\t')
        val kind = AssetKind.fromKey(fields.getOrNull(0)?.trim())
            ?: return@mapNotNull null
        val templateId = fields.getOrNull(1)?.trim().orEmpty()
        if (templateId.isEmpty()) return@mapNotNull null
        val version = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 1

        kind to AssetControlConfig(
            ref = TemplateVersionRef(TemplateId(templateId), version),
            tags = fields.getOrNull(3).orEmpty().split('|').mapNotNull { tag ->
                val parts = tag.split(':')
                val id = parts.getOrNull(0)?.trim().orEmpty()
                if (id.isEmpty() || id.contains(',')) return@mapNotNull null
                CustomAction(
                    id = id,
                    displayName = parts.getOrNull(1).orEmpty(),
                    webhookValue = parts.getOrNull(2).orEmpty()
                )
            }
        )
    }.toMap()

/**
 * The name and wire value a newly added action starts with.
 *
 * Numbered from the first free slot rather than from the count, so adding one after deleting one
 * does not hand out a name that is already on the bar.
 */
fun nextCustomActionDefaults(existing: List<CustomAction>): CustomAction {
    val taken = existing.map { it.displayName }.toSet()
    var index = existing.size + 1
    while ("C$index" in taken) index++
    return CustomAction(id = newActionId(), displayName = "C$index", webhookValue = "cust$index")
}

/**
 * The enabled actions in [decisions] as wire values, in [ItemAction] order.
 *
 * Duplicates are collapsed rather than rejected: two slots configured with the same wire value is
 * a strange thing to do but not a dangerous one, and emitting `["tag","tag"]` would be worse for
 * a receiver than emitting it once. Order stays fixed either way.
 */
fun itemActionNames(decisions: ReviewDecisions, control: ResolvedAssetControl): List<String> =
    control.actions.filter { it.isSetOn(decisions) }.map { control.wireOf(it) }.distinct()

/** Compact "★ · Save" summary for a checklist row, or null when the item carries no actions. */
fun itemActionLabel(decisions: ReviewDecisions, control: ResolvedAssetControl): String? =
    control.actions.filter { it.isSetOn(decisions) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ") { control.labelOf(it) }

/** Rejects only what would actually break something: an empty or whitespace-only value. */
fun settingsTextError(value: String): String? =
    if (value.isBlank()) "Cannot be empty" else null

private val Context.settingsDataStore by preferencesDataStore(name = "listea_settings")

/**
 * Reads and writes [AppSettings]. Small on purpose: no repository layer, no domain mapping, just
 * a flow for the UI and an authoritative one-shot read for the moments where a stale value would
 * be wrong (creating a list, sending a webhook).
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        // A corrupt or unreadable file falls back to defaults rather than taking the app down.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            val defaults = AppSettings()
            AppSettings(
                folderQuickReviewEnabled = prefs[KEY_QUICK_REVIEW]
                    ?: defaults.folderQuickReviewEnabled,
                assetControlTags = readAssetControlTags(prefs),
                defaultWebhookEnabled = prefs[KEY_WEBHOOK_ENABLED]
                    ?: defaults.defaultWebhookEnabled,
                defaultWebhookUrl = prefs[KEY_WEBHOOK_URL] ?: defaults.defaultWebhookUrl,
                videoAutoplay = prefs[KEY_VIDEO_AUTOPLAY] ?: defaults.videoAutoplay,
                videoStartMuted = prefs[KEY_VIDEO_MUTED] ?: defaults.videoStartMuted,
                rememberReviewPosition = prefs[KEY_REMEMBER_POSITION]
                    ?: defaults.rememberReviewPosition,
                autoCheckSourceFreshness = prefs[KEY_AUTO_CHECK_FRESHNESS]
                    ?: defaults.autoCheckSourceFreshness,
                quickReviewWebhookEnabled = prefs[KEY_QUICK_REVIEW_WEBHOOK]
                    ?: defaults.quickReviewWebhookEnabled,
                webhookCompletedItemsOnly = prefs[KEY_COMPLETED_ITEMS_ONLY]
                    ?: defaults.webhookCompletedItemsOnly,
                reviewUncheckedOnly = prefs[KEY_REVIEW_UNCHECKED_ONLY]
                    ?: defaults.reviewUncheckedOnly,
                webhookOnReviewExit = prefs[KEY_WEBHOOK_ON_REVIEW_EXIT]
                    ?: defaults.webhookOnReviewExit,
                askDeleteAfterWebhook = prefs[KEY_ASK_DELETE_AFTER_WEBHOOK]
                    ?: defaults.askDeleteAfterWebhook,
                defaultContentPolicy = ContentPolicy.fromKey(prefs[KEY_CONTENT_POLICY]),
                followUpstreamNewTodos = prefs[KEY_FOLLOW_UPSTREAM]
                    ?: defaults.followUpstreamNewTodos,
                sharedFileArrangement = prefs[KEY_SHARED_ARRANGEMENT]
                    ?: defaults.sharedFileArrangement
            )
        }

    /**
     * The remembered filters and sort orders, as their own flow rather than as fields on
     * [AppSettings].
     *
     * Kept apart because they are a different kind of thing on a different scale: [settings] is a
     * handful of switches read by most of the app, and this is a map that grows with the folders
     * a user has arranged and is read by one screen. Folding them together would re-parse every
     * remembered folder each time a webhook switch changed.
     */
    val arrangements: Flow<FolderArrangements> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            FolderArrangements(
                global = prefs[KEY_GLOBAL_ARRANGEMENT]?.let(::decodeArrangement)
                    ?: FileArrangement.Default,
                byFolder = prefs[KEY_FOLDER_ARRANGEMENTS]?.let(::decodeFolderArrangements)
                    ?: emptyMap()
            )
        }

    suspend fun read(): AppSettings = settings.first()

    suspend fun setFolderQuickReviewEnabled(enabled: Boolean) =
        put { it[KEY_QUICK_REVIEW] = enabled }

    /**
     * Appends a tag to one kind, named from the first free slot.
     *
     * Read-modify-write inside one edit, like every other change to this list, so two taps in
     * quick succession add two tags rather than racing to add the same one.
     */
    suspend fun addCustomAction(kind: AssetKind) = put { prefs ->
        val existing = tagsOf(prefs, kind)
        writeTags(prefs, kind, existing + nextCustomActionDefaults(existing))
    }

    /** Renames one tag of one kind, or changes what it sends. An unknown id writes nothing. */
    suspend fun updateCustomAction(
        kind: AssetKind,
        id: String,
        displayName: String? = null,
        webhookValue: String? = null
    ) = put { prefs ->
        writeTags(
            prefs,
            kind,
            tagsOf(prefs, kind).map { action ->
                if (action.id != id) {
                    action
                } else {
                    action.copy(
                        displayName = displayName?.let(::sanitizeActionText) ?: action.displayName,
                        webhookValue = webhookValue?.let(::sanitizeActionText) ?: action.webhookValue
                    )
                }
            }
        )
    }

    /**
     * Takes a tag off one kind's row.
     *
     * Items that carry it are not touched. The id stays on the rows that hold it, invisible and
     * unsent, because deleting a button is a decision about the row and not about the files
     * somebody has already marked with it — and because this app does not quietly rewrite
     * decisions the user made.
     *
     * Only this kind is affected. The other kind keeps its own key and its own tags, which is the
     * whole reason the configuration is stored per kind rather than as one list.
     */
    suspend fun removeCustomAction(kind: AssetKind, id: String) = put { prefs ->
        writeTags(prefs, kind, tagsOf(prefs, kind).filterNot { it.id == id })
    }

    suspend fun setDefaultWebhookEnabled(enabled: Boolean) =
        put { it[KEY_WEBHOOK_ENABLED] = enabled }

    suspend fun setDefaultWebhookUrl(url: String) = put { it[KEY_WEBHOOK_URL] = url.trim() }

    suspend fun setVideoAutoplay(enabled: Boolean) = put { it[KEY_VIDEO_AUTOPLAY] = enabled }

    suspend fun setVideoStartMuted(muted: Boolean) = put { it[KEY_VIDEO_MUTED] = muted }

    suspend fun setRememberReviewPosition(remember: Boolean) =
        put { it[KEY_REMEMBER_POSITION] = remember }

    suspend fun setAutoCheckSourceFreshness(enabled: Boolean) =
        put { it[KEY_AUTO_CHECK_FRESHNESS] = enabled }

    suspend fun setQuickReviewWebhookEnabled(enabled: Boolean) =
        put { it[KEY_QUICK_REVIEW_WEBHOOK] = enabled }

    suspend fun setWebhookCompletedItemsOnly(enabled: Boolean) =
        put { it[KEY_COMPLETED_ITEMS_ONLY] = enabled }

    suspend fun setReviewUncheckedOnly(enabled: Boolean) =
        put { it[KEY_REVIEW_UNCHECKED_ONLY] = enabled }

    suspend fun setWebhookOnReviewExit(enabled: Boolean) =
        put { it[KEY_WEBHOOK_ON_REVIEW_EXIT] = enabled }

    suspend fun setAskDeleteAfterWebhook(enabled: Boolean) =
        put { it[KEY_ASK_DELETE_AFTER_WEBHOOK] = enabled }

    suspend fun setDefaultContentPolicy(policy: ContentPolicy) =
        put { it[KEY_CONTENT_POLICY] = policy.key }

    suspend fun setFollowUpstreamNewTodos(enabled: Boolean) =
        put { it[KEY_FOLLOW_UPSTREAM] = enabled }

    suspend fun setSharedFileArrangement(shared: Boolean) =
        put { it[KEY_SHARED_ARRANGEMENT] = shared }

    /** The one arrangement every folder uses while [AppSettings.sharedFileArrangement] is on. */
    suspend fun setGlobalArrangement(arrangement: FileArrangement) =
        put { it[KEY_GLOBAL_ARRANGEMENT] = encodeArrangement(arrangement) }

    /**
     * One folder's own arrangement, read-modify-write so the rest of the map is untouched.
     *
     * The whole map is rewritten because it is stored as one value; [rememberArrangement] is what
     * decides what that value becomes, including forgetting a folder that has been put back to
     * its default and dropping the least recently arranged folder once the cap is reached.
     */
    suspend fun setFolderArrangement(folderKey: String, arrangement: FileArrangement) = put { prefs ->
        val existing = prefs[KEY_FOLDER_ARRANGEMENTS]?.let(::decodeFolderArrangements).orEmpty()
        prefs[KEY_FOLDER_ARRANGEMENTS] =
            encodeFolderArrangements(rememberArrangement(existing, folderKey, arrangement))
    }

    /**
     * The tags a new Todo starts with, per kind.
     *
     * Two fallbacks stacked, and both are reads rather than one-off rewrites on purpose. A kind's
     * own key wins once it has been written; failing that the app-wide list that existed before
     * v0.5.0 applies to *both* kinds; failing that the two original slots. So an install that never
     * opens this page keeps working off its oldest keys forever, and the first edit made in either
     * section is what settles that section's new form.
     *
     * Both kinds falling back to the same app-wide list is the whole of the upgrade story for the
     * tags themselves, and it is why the migration does not carry them: on the day this ships,
     * every existing Todo's Assets go on offering exactly the tags they offered the day before.
     */
    private fun readAssetControlTags(
        prefs: androidx.datastore.preferences.core.Preferences
    ): Map<AssetKind, List<CustomAction>> =
        AssetKind.entries
            // `OTHER` is not configurable, and offering a key nothing can be configured for would
            // put a tag set in the map that no Asset can ever resolve to.
            .filter { it != AssetKind.OTHER }
            .associateWith { kind ->
                prefs[tagsKeyFor(kind)]?.let(::decodeCustomActions) ?: readLegacyCustomActions(prefs)
            }

    /**
     * The app-wide list that predates per-kind tags, from the list if one was ever written and from
     * the two original slots if not.
     *
     * The ids match what the database migration wrote onto the items, so a file marked C1 in the
     * old world is still marked C1 in the new one, under both kinds.
     */
    private fun readLegacyCustomActions(
        prefs: androidx.datastore.preferences.core.Preferences
    ): List<CustomAction> {
        prefs[KEY_CUSTOM_ACTIONS]?.let { return decodeCustomActions(it) }

        return DefaultCustomActions.mapIndexed { index, default ->
            val name = if (index == 0) prefs[KEY_C1_NAME] else prefs[KEY_C2_NAME]
            val value = if (index == 0) prefs[KEY_C1_VALUE] else prefs[KEY_C2_VALUE]
            default.copy(
                displayName = name ?: default.displayName,
                webhookValue = value ?: default.webhookValue
            )
        }
    }

    /** Where one kind's tags live. `OTHER` has no key because it has no configuration. */
    private fun tagsKeyFor(kind: AssetKind) = when (kind) {
        AssetKind.IMAGE -> KEY_ASSET_CONTROL_TAGS_IMAGE
        AssetKind.VIDEO -> KEY_ASSET_CONTROL_TAGS_VIDEO
        AssetKind.OTHER -> error("OTHER has no asset control and no key to store one under")
    }

    /** The tags currently configured for one kind, written or fallen back — the same read as above. */
    private fun tagsOf(
        prefs: androidx.datastore.preferences.core.Preferences,
        kind: AssetKind
    ): List<CustomAction> =
        prefs[tagsKeyFor(kind)]?.let(::decodeCustomActions) ?: readLegacyCustomActions(prefs)

    /**
     * Writes one kind's tags, whatever the caller decided they should now be.
     *
     * Every mutation below goes through this, so that the first edit to a kind is also the moment
     * its fallback stops applying — and so that no mutation accidentally writes the *legacy* list
     * back into a key, which would freeze a kind at whatever it happened to be sharing.
     */
    private fun writeTags(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        kind: AssetKind,
        tags: List<CustomAction>
    ) {
        prefs[tagsKeyFor(kind)] = encodeCustomActions(tags)
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_QUICK_REVIEW = booleanPreferencesKey("folder_quick_review_enabled")

        val KEY_ASSET_CONTROL_TAGS_IMAGE = stringPreferencesKey("asset_control_tags_image")
        val KEY_ASSET_CONTROL_TAGS_VIDEO = stringPreferencesKey("asset_control_tags_video")

        // Read-only from here on: the app-wide list, which both kinds fall back to until each has
        // been edited once. Deleting it would strip the tags off every Todo that predates v0.5.0
        // the next time Settings was read.
        val KEY_CUSTOM_ACTIONS = stringPreferencesKey("custom_actions")

        // Read-only from here on: what an install configured before actions became a list.
        val KEY_C1_NAME = stringPreferencesKey("custom1_display_name")
        val KEY_C1_VALUE = stringPreferencesKey("custom1_webhook_value")
        val KEY_C2_NAME = stringPreferencesKey("custom2_display_name")
        val KEY_C2_VALUE = stringPreferencesKey("custom2_webhook_value")
        val KEY_WEBHOOK_ENABLED = booleanPreferencesKey("default_webhook_enabled")
        val KEY_WEBHOOK_URL = stringPreferencesKey("default_webhook_url")
        val KEY_VIDEO_AUTOPLAY = booleanPreferencesKey("video_autoplay")
        val KEY_VIDEO_MUTED = booleanPreferencesKey("video_start_muted")
        val KEY_REMEMBER_POSITION = booleanPreferencesKey("remember_review_position")
        val KEY_AUTO_CHECK_FRESHNESS = booleanPreferencesKey("auto_check_source_freshness")
        val KEY_QUICK_REVIEW_WEBHOOK = booleanPreferencesKey("quick_review_webhook_enabled")
        val KEY_COMPLETED_ITEMS_ONLY = booleanPreferencesKey("webhook_completed_items_only")
        val KEY_REVIEW_UNCHECKED_ONLY = booleanPreferencesKey("review_unchecked_only")
        val KEY_WEBHOOK_ON_REVIEW_EXIT = booleanPreferencesKey("webhook_on_review_exit")
        val KEY_ASK_DELETE_AFTER_WEBHOOK = booleanPreferencesKey("ask_delete_after_webhook")
        val KEY_CONTENT_POLICY = stringPreferencesKey("default_content_policy")
        val KEY_FOLLOW_UPSTREAM = booleanPreferencesKey("follow_upstream_new_todos")
        val KEY_SHARED_ARRANGEMENT = booleanPreferencesKey("shared_file_arrangement")
        val KEY_GLOBAL_ARRANGEMENT = stringPreferencesKey("global_file_arrangement")
        val KEY_FOLDER_ARRANGEMENTS = stringPreferencesKey("folder_file_arrangements")
    }
}
