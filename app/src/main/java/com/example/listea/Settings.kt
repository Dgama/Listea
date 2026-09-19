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
import com.example.listea.data.AssetDecisions
import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ListItemEntity
import com.example.listea.data.newActionId
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ScopeRule
import com.example.listea.domain.TemplateDefinition
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateScope
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
)

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
 * The buttons one file's row actually offers, resolved: what a chip row, a badge and a payload read.
 *
 * The completed answer, and the only shape any surface takes. Built by [TemplateResolver] from the
 * templates a Task is attached to, and thereafter self-contained — the chip row, the Info sheet and
 * the payload all resolve labels and wire values from one of these rather than from the settings,
 * which is what stops them disagreeing about which file they are describing.
 *
 * **A list of buttons, and no `favourite` flag beside it**, which is the change v0.5.4 makes. `★`
 * used to be a boolean next to a tag list, and that was the model missing a rule: the buttons of a
 * template are all the same kind of thing, `★` is one of them, and giving one of them a field of its
 * own meant every surface that drew a row had to know which button was the special one. It reads its
 * name and its wire value from the button like every other button does, because `★` is a button the
 * built-in template happens to ship — not a property of the row that draws it.
 *
 * **Empty is a real answer, and is not filled in.** [TemplateResolver.controlFor] answers null when
 * no attached template governs a file, and a caller that has to draw something draws [Empty]: a
 * control row with no chips on it at all. That is the honest picture of a file nothing admits, and
 * it is deliberately not the built-in standing in for it — see the resolver's note on why a chip
 * nobody chose is worse than a chip row that is empty.
 */
@Immutable
data class ResolvedAssetControl(
    val buttons: List<TemplateButton> = emptyList()
) {
    /**
     * Every action the row offers, in the buttons' own order.
     *
     * The one place that order is decided, so the chip row, the badge and the payload cannot
     * disagree about it. `★` is wherever the template listed it — first for the built-in, because
     * that is where its seed puts it — since order is the template's to decide and not this type's.
     */
    val actions: List<ItemAction> get() = buttons.map { ItemAction(it.id) }

    /** The ids this control offers, for the callers that ask that question rather than the order. */
    val ids: Set<String> get() = buttons.mapTo(mutableSetOf()) { it.id }

    /**
     * What the user sees this action called.
     *
     * An id these buttons do not define answers with the id itself rather than with null. That case
     * is unreachable from [actions] — which is built *from* the buttons — and the reason it is
     * written this way is that a null here would have to be handled by every caller to describe a
     * state none of them can be in. What actually keeps a deleted button out of a payload is
     * [actions] omitting it, not this: a button removed from a template leaves the id on the items
     * that carry it, and stops being offered, labelled and sent, all by the same omission.
     */
    fun labelOf(action: ItemAction): String =
        buttons.firstOrNull { it.id == action.id }?.name ?: action.id

    /**
     * What the webhook calls this action. Resolved here, at delivery time, and never stored on an
     * item: an item's set means "the button with this id is selected", not "the string `price` was
     * selected", so renaming a wire value changes future payloads without migrating a single row.
     *
     * See [labelOf] for why an unknown id answers with itself.
     */
    fun wireOf(action: ItemAction): String =
        buttons.firstOrNull { it.id == action.id }?.recordValue ?: action.id

    companion object {
        /** The row a file no template governs gets: no chips at all. See the class note. */
        val Empty = ResolvedAssetControl()
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
 * Declaration order rather than the set's, so the chip rows a user sees do not reshuffle between
 * builds.
 *
 * Still read off the built-in definition since v0.5.4, even though what governs a file is now a
 * *stored* template rather than this definition. The two questions have come apart — a user-made
 * template's scope can name any kind — and this one is no longer "which kinds may be configured"
 * but "which kinds a template editor offers to scope over", which is still the built-in's shape.
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
 * What a template's scope covers, in the words a user reads rather than the rule it is written as.
 *
 * `kind:image,video` is the encoding and not the answer, and a page that showed it would be asking
 * the user to read the storage format to find out which files a template governs. The list and the
 * template's own page both say it this way, from this one function, because they describe the same
 * template a tap apart and two phrasings of one scope would read as two scopes.
 *
 * **"Applies to nothing" is a real answer and is said as one.** An empty scope admits nothing — the
 * direction [TemplateScope] fails in — and a scope whose only rules are unreadable governs nothing
 * either. Neither is an empty state to be papered over: a template that matches no file is a
 * template whose chips will never appear, and that is worth finding out on the page rather than at
 * the review screen.
 */
fun templateScopeLabel(scope: TemplateScope): String {
    // Every rule is a conjunction, so a scope with two `kind` rules governs their intersection —
    // `TemplateScope.kinds` reads it that way, and so does this.
    val kinds = scope.kinds
    if (kinds.isEmpty()) return "Applies to nothing"

    val named = AssetKind.entries.filter { it in kinds }.map { assetKindPlural(it) }
    return "Applies to " + joinWords(named)
}

/**
 * The rules in a scope this build cannot evaluate, said out loud, or null when there are none.
 *
 * A rule type from a later release is not dropped and not ignored: it makes the template match
 * nothing, and the page has to say so, or the user meets the consequence on a review screen with
 * chips missing and no explanation anywhere. Naming the rule is the point — *something is wrong*
 * would leave them nothing to look at.
 */
fun templateScopeWarning(scope: TemplateScope): String? {
    val unknown = scope.rules.filterIsInstance<ScopeRule.Unknown>()
    if (unknown.isEmpty()) return null

    val named = joinWords(unknown.map { "`${it.type}:${it.argument}`" })
    return "This version also carries $named, a rule this build cannot evaluate. A scope is every " +
        "rule together, so the template matches nothing at all while it is there."
}

/**
 * A file kind as a word you could put on a chip: "Images", "Videos", "Other files".
 *
 * Where the plural is used inside a sentence — [templateScopeLabel] — it is this, lowercased. One
 * `when` rather than two, because the chip and the sentence describe the same kind and a list of
 * three words is exactly the kind of thing that drifts when it is written twice.
 */
fun assetKindChipLabel(kind: AssetKind): String = when (kind) {
    AssetKind.IMAGE -> "Images"
    AssetKind.VIDEO -> "Videos"
    AssetKind.OTHER -> "Other files"
}

private fun assetKindPlural(kind: AssetKind): String = assetKindChipLabel(kind).lowercase()

/** "a", "a and b", "a, b and c" — because a scope naming three kinds is a sentence, not a list. */
private fun joinWords(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words.first()
    else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
}

/**
 * Keeps typed text to one line, so it cannot break the record it is stored in.
 *
 * `\t` and `\n` separate *records* — a line is a button, a tab is a field within it — so a name or a
 * wire value containing either would split one button into two, or shift its fields onto the next
 * one along. `\r` comes along for the ride because a paste from Windows carries it and it reads as
 * an ordinary space to everybody except the line it lands in the middle of.
 *
 * **Two more separators used to be stripped here and are not any more.** `|` and `:` separated the
 * parts of an asset-control tag, which was a record nested one level deeper inside the field above
 * it; v0.5.4 replaced that configuration with a template's buttons, which are flat, and a button's
 * name is now free to contain either. Nothing reads the old shape any more, so keeping the rule
 * would only be refusing punctuation a user may type for a format that no longer exists.
 *
 * One sanitiser rather than one per encoding, because [encodeTemplateButtons] is the only record
 * typed text goes into and this is the only thing that guards it.
 */
fun sanitizeActionText(value: String): String = value
    .replace('\t', ' ')
    .replace('\n', ' ')
    .replace('\r', ' ')
    .trim()

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
 * [AssetDecisions], because these are the actions a control offers and a pair records. A caller
 * speaking about a whole Task — the payload, a List's row — hands in [unionOf] of its pairs, which
 * is the same type and the same question asked of every file at once.
 *
 * Duplicates are collapsed rather than rejected: two slots configured with the same wire value is
 * a strange thing to do but not a dangerous one, and emitting `["tag","tag"]` would be worse for
 * a receiver than emitting it once. Order stays fixed either way.
 */
fun itemActionNames(decisions: AssetDecisions, control: ResolvedAssetControl): List<String> =
    control.actions.filter { it.isSetOn(decisions) }.map { control.wireOf(it) }.distinct()

/** Compact "★ · Save" summary for a checklist row, or null when the item carries no actions. */
fun itemActionLabel(decisions: AssetDecisions, control: ResolvedAssetControl): String? =
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
     * The tag keys this store used to read, gone since v0.5.4.
     *
     * They are absent rather than kept as read-only constants, because nothing reads them: a tag is
     * a button on a stored template now, and the built-in template's buttons come from
     * [Templates.AssetControlSeed] through the database rather than from this file. The four keys
     * they named — `asset_control_tags_image`, `asset_control_tags_video`, the app-wide
     * `custom_actions`, and the two original `custom1`/`custom2` name and value slots — are still
     * on the devices that wrote them, and there is deliberately no migration to remove them. A
     * preference nobody reads costs a few bytes, and a rewrite pass over every install to delete
     * something harmless is a chance to lose something that is not.
     *
     * What those keys *were* has not been lost either: the 23 → 24 step wrote them onto the
     * built-in template's first version as buttons, which is what every pre-upgrade Todo resolves
     * to now. This is the note that says so — see `docs/iterations/v0.5.4.md`.
     */

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_QUICK_REVIEW = booleanPreferencesKey("folder_quick_review_enabled")

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
