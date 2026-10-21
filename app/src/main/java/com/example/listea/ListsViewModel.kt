package com.example.listea

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.listea.data.AssetDecisions
import com.example.listea.data.CatalogRow
import com.example.listea.data.DissolutionReport
import com.example.listea.data.DissolvedHistory
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.FolderDiff
import com.example.listea.data.ListLosingItems
import com.example.listea.data.FolderListImportConflict
import com.example.listea.data.fileIdentity
import com.example.listea.data.ImportResult
import com.example.listea.data.ItemAction
import com.example.listea.data.ListDetail
import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.ListSummary
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.newItemPublicId
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.ScannedFile
import com.example.listea.data.SharedFileState
import com.example.listea.data.SourceEntity
import com.example.listea.data.SourceSummary
import com.example.listea.data.TaskStateEntity
import com.example.listea.data.UpstreamEntity
import com.example.listea.data.WEBHOOK_ADDRESS_BACKFILL
import com.example.listea.data.decisions
import com.example.listea.data.rootRelativePathOf
import com.example.listea.data.taskDecisions
import com.example.listea.data.toReviewAsset
import com.example.listea.data.TaskAssetControls
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateStore
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.data.toReviewItem
import com.example.listea.data.ExportRecordEntity
import com.example.listea.data.ExportRecordSummary
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.decodeExportChoice
import com.example.listea.domain.encodeExportChoice
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import java.util.UUID

/** What the Folder screen should be showing about a pending "create list from folder" action. */
sealed interface FolderListRequest {
    data object Scanning : FolderListRequest

    /**
     * Overlapping folder lists exist; creation waits for the user to confirm the replacement.
     *
     * [replacesExact] means one of them is this very folder's own list, so the user is rebuilding
     * it rather than taking the folder over from an ancestor or from folders below.
     */
    data class Confirm(
        val folderName: String,
        val fileCount: Int,
        val replacedTitles: List<String>,
        val replacesExact: Boolean
    ) : FolderListRequest

    /**
     * The folder is not yet imported, so creating a List over it would register a Source. Nothing
     * is written until the user accepts; the confirmation names the root and the importer it
     * proposes, and a decline leaves the database exactly as it was.
     */
    data class ImportConsent(
        val folderName: String,
        val fileCount: Int,
        val sourceRoot: String,
        val importer: String
    ) : FolderListRequest

    data class Error(val message: String) : FolderListRequest
}

/** What the List detail screen should be showing about a pending "refresh from folder" action. */
sealed interface ResyncRequest {
    data object Scanning : ResyncRequest
    data object UpToDate : ResyncRequest

    /** Changes were found; nothing is written until the user confirms. */
    data class Confirm(val diff: FolderDiff) : ResyncRequest

    /**
     * Changes were found, but no Source covers this list's folder, so applying them would register
     * one. Registration is a write, so it is asked for in the same words §11.2 asks with, and
     * declining leaves the list, the folder and `sources` exactly as they were.
     *
     * Its own state rather than a sentence inside [Confirm], because accepting a replace is not
     * accepting an import: the two are different writes and a user may want one without the other.
     */
    data class ImportConsent(
        val diff: FolderDiff,
        val sourceRoot: String,
        val importer: String
    ) : ResyncRequest

    /**
     * Reconciliation is already applied. Offers the optional, destructive follow-up of dropping
     * every source-backed item in the list that currently has no source file. Keeping them is the
     * default; nothing is deleted unless the user picks removal.
     */
    data class CleanupMissing(val listId: Long, val missingCount: Int) : ResyncRequest

    data class Error(val message: String) : ResyncRequest
}

/**
 * What an Upstream's Create Tasks action, and a Todo's Refresh Import, are showing.
 *
 * One state machine for both because they are one operation with one difference: Create Tasks may
 * register a Source, a refresh runs against a Source that already exists. Both walk the same tree,
 * plan it the same way and write through the same run, so there is one writer and one report. The
 * difference is what the dialog offers: refreshing cannot change the configuration, so it shows the
 * one that is already stored instead of asking for it again.
 */
sealed interface ImportFolderRequest {
    /** The folder is being walked. Nothing has been written. */
    data object Reading : ImportFolderRequest

    /**
     * The tree is read and nothing has been written. The dialog names the folder the run would
     * cover, and — unless this is a refresh — collects the whole of [ImportOptions] before anything
     * commits.
     *
     * [options] carries the values the dialog opens with: the Source's own name and importer when
     * refreshing, and the folder's name with the default grouping when creating. It is a starting
     * point rather than the answer — the user edits it, and [confirmImportFolder] is what decides.
     *
     * [existingTodos] names the Todos already registered at this folder, and is a **notice, not a
     * refusal**. Running Create Tasks over a folder that has a Todo is how a second intention is
     * expressed — the same market supplying another dish — and it is allowed. Saying so up front is
     * what keeps it from looking like a mistake: the dialog is about to create a thing the folder
     * already has, and the one moment that can be said cheaply is before the button is pressed.
     * Empty for a refresh, which is running against the Todo it names.
     *
     * [histories] is what Todos were dissolved at this folder and never recovered from. It is the
     * one thing this dialog can offer that a fresh folder cannot: the judgments of an intention that
     * was abandoned here. **At most one may be chosen** — a run that took some Tasks from one
     * history and some from another would produce a Todo that never existed as an intention — and
     * the picker is what enforces that by being a choice rather than a set of checkboxes. Empty when
     * nothing was ever dissolved here, which is the ordinary case and shows no control at all.
     */
    data class Confirm(
        val folderLabel: String,
        val fileCount: Int,
        val folderCount: Int,
        val refresh: Boolean,
        val options: ImportOptions,
        val existingTodos: List<String> = emptyList(),
        val histories: List<DissolvedHistory> = emptyList(),

        /**
         * Every template there is to attach, in the order the attach step offers them.
         *
         * Read once, when the folder was walked, and carried on the request rather than looked up
         * by the dialog. Two reasons and the second is the one that decided it: the dialog has no
         * store to read from, and a picker built from a flow would spend its first frame saying
         * there are no templates on a device that has one — the same failure the template list's
         * nullable flow exists to avoid, arriving on the screen where it would be acted on.
         *
         * [ImportOptions.templates] is what the dialog *chose*; this is what there was to choose
         * from, and the two are deliberately not the same field: an attachment naming a template
         * this list does not hold is a real state — a deleted template, a version a later build
         * wrote — and a dialog that read its own answer back off its own choices could not show it.
         */
        val templates: List<StoredTemplateVersion> = emptyList()
    ) : ImportFolderRequest

    /** The run is writing. */
    data object Importing : ImportFolderRequest

    /**
     * The run's answer, shown and then dropped: there is no import history for it to live in.
     *
     * [recoveredFrom] names the history the user chose to be born knowing, or null if they chose to
     * start fresh. The count of what it actually reached is on [ImportResult.tasksRecovered], and
     * the two are not the same question: a name with a count of zero is a recovery that matched
     * nothing, which is a thing the user asked for and so a thing the report has to answer.
     */
    data class Report(
        val result: ImportResult,
        val importer: ImporterKind,
        val recoveredFrom: String? = null
    ) : ImportFolderRequest

    data class Error(val message: String) : ImportFolderRequest
}

/**
 * Whether a folder-backed list has source changes it has not acknowledged yet.
 *
 * Purely derived from a read-only comparison: producing this never inserts, flags, restores or
 * deletes an item, never touches completion or actions, and never delivers a webhook. Acting on
 * it stays manual.
 */
sealed interface SourceFreshness {
    data object Checking : SourceFreshness

    /**
     * Nothing authoritative is known, because the automatic check is switched off and nothing has
     * asked for one. Deliberately its own state rather than an absent one: silence would be read
     * as "fine", and the whole point of the setting is that Listea has not looked.
     */
    data object NotChecked : SourceFreshness

    /**
     * No *unacknowledged* changes, which is not the same as the rows matching the filesystem
     * byte for byte. A source file that vanished, was reconciled, and was deliberately kept as a
     * missing item is already acknowledged: it must not make the list stale forever.
     */
    data object UpToDate : SourceFreshness

    data class ChangesAvailable(val diff: FolderDiff) : SourceFreshness

    /** The linked folder could not be resolved or read: revoked grant, deleted folder, bad path. */
    data object SourceUnavailable : SourceFreshness
}

/**
 * A freshness result bound to the list it describes. The screen renders it only when the id
 * matches the list it is showing, so a scan that finishes after the user has moved on can never
 * label a different list.
 */
data class ListFreshness(val listId: Long, val state: SourceFreshness)

/**
 * What a Quick Review round is working on, before it has one.
 *
 * Quick Review used to be gated on a List: it reviewed the owning List's rows, so it had to check
 * that List was not stale before it could show anything, and a folder with no List had nothing to
 * review at all. It now resolves the scope itself, which removes both problems at once — the scope
 * *is* the source, so there is no snapshot to be stale, and no List needs to exist.
 *
 * Two scopes resolve into this: a browsed folder ([observeFolderReview]) and a whole Todo
 * ([observeTaskReview]). They differ in what they read and agree on everything after that — the
 * same items, the same decisions, the same write path — so the states below are the union of what
 * either can be in, not a description of one of them. [Unavailable] and [NotImported] belong to the
 * folder path alone; a round opened on a Todo has already found its material.
 */
sealed interface FolderReview {
    /** The folder is being read and its files resolved. No decision is visible yet. */
    data object Resolving : FolderReview

    /** The folder could not be read: revoked grant, deleted folder, unmounted storage. */
    data object Unavailable : FolderReview

    /**
     * The folder's scope and everything the catalog offers in it, each item carrying whatever has
     * already been decided about it and what it measures. Which of them a round actually queues is
     * the screen's decision, not this one's.
     *
     * [notice] is why there is nothing to queue, and is only ever non-null when [items] is empty:
     * the two are one answer, computed from one read, so a screen can never show a reason next to a
     * queue that contradicts it.
     */
    data class Ready(
        val items: List<ReviewItem>,
        val notice: ScopeNotice? = null
    ) : FolderReview

    /**
     * Nothing imported here: no Source covers this folder, so Quick Review would be reviewing
     * material that was never brought in. The consent dialog is the answer, not a silent empty
     * round; nothing is written until it is accepted.
     */
    data class NotImported(val sourceRoot: String, val importer: String) : FolderReview
}

/**
 * Where a "delete checked files" run has got to.
 *
 * The only thing in Listea that destroys something outside its own database, so the whole run is
 * a state machine the user can see: what was found, what it would cost, and what actually
 * happened. Nothing touches a file until [Confirm] has been answered, and the outcome is reported
 * rather than assumed — a partial delete is a real result here, not an error.
 */
sealed interface DeleteCheckedRequest {
    data object Scanning : DeleteCheckedRequest

    /** Nothing is checked, or nothing checked still has a file. Its own state, not an error. */
    data object NothingChecked : DeleteCheckedRequest

    /**
     * The folder can be read but not changed, so the run pauses to ask for that.
     *
     * Not an error, because nothing has gone wrong and there is something the user can do about
     * it on the spot: Android hands write access over through the folder picker and nowhere else,
     * so the screen asks, opens the picker on the folder already in use, and resumes here.
     */
    data object NeedsWriteAccess : DeleteCheckedRequest

    /** What would go, grouped by folder for reading. [fileCount] counts files, not folders. */
    data class Confirm(
        val groups: List<CheckedFileGroup>,
        val fileCount: Int
    ) : DeleteCheckedRequest

    data object Deleting : DeleteCheckedRequest

    /** [failed] is files the provider would not remove; they are still on the device. */
    data class Done(val deleted: Int, val failed: Int) : DeleteCheckedRequest

    data class Error(val message: String) : DeleteCheckedRequest
}

/**
 * The offer made after a webhook has gone out, when
 * [AppSettings.askDeleteAfterWebhook] is on.
 *
 * A second state machine rather than a reuse of [DeleteCheckedRequest], because the two answer
 * different questions with different scopes. That one starts from the root folder and gathers
 * every checked file under it, whenever it was checked; this one starts from a payload that has
 * just been accepted by a receiver and covers exactly the files that payload carried as checked.
 * The two will disagree the moment anything was checked outside the round, and folding them
 * together would mean one dialog quietly acting on the other's set.
 *
 * Nothing here is offered until the delivery has already been reported. The round's own result
 * comes first and this follows it, so the user is never asked whether to delete files without
 * having just been told whether the thing that justifies deleting them actually worked.
 */
sealed interface PostWebhookDeletion {
    /**
     * The delivery succeeded and these are the files it carried as checked. [fileCount] counts
     * files; [groups] is the same set arranged for reading.
     */
    data class Ask(
        val listTitle: String,
        val eventLabel: String,
        val groups: List<CheckedFileGroup>,
        val fileCount: Int
    ) : PostWebhookDeletion

    /**
     * The delivery did not go out, so there is nothing to offer — only something to say.
     *
     * Its own state rather than a silence, because the switch being on is the user saying they
     * expect to be asked about these files, and a round that fails is exactly when they need to
     * know that the files are still there and where the round went instead.
     */
    data class NotSent(
        val listTitle: String,
        val fileCount: Int,
        val detail: String
    ) : PostWebhookDeletion

    /** The root can be read but not changed, which only Settings can do anything about. */
    data class NeedsWriteAccess(val fileCount: Int) : PostWebhookDeletion

    data object Deleting : PostWebhookDeletion

    /** [failed] is files the provider would not remove; they are still on the device. */
    data class Done(val deleted: Int, val failed: Int) : PostWebhookDeletion

    data class Error(val message: String) : PostWebhookDeletion
}

/**
 * Where dissolving a Todo has got to.
 *
 * A third destructive run, and a third state machine rather than a reuse of either of the other
 * two, because none of them destroys the same thing: [DeleteCheckedRequest] destroys *files* the
 * user can point at, [PostWebhookDeletion] destroys the files one payload carried, and this
 * destroys the only thing Listea really owns — an intention and the Tasks that carry it. The two
 * file machines are also, in the end, guardians of a rule this one does not share: nothing here
 * touches the device. Every row it removes is in Listea's own database, which is why this run
 * cannot half-succeed on the filesystem and why it can offer to keep what it is removing.
 *
 * [Confirm] is shown and its answer read, and the answer is the checkbox: [keepState] is the
 * user's decision about the snapshot, taken at the only moment it is legible.
 */
sealed interface DeleteTodoRequest {
    /** Reading what the Todo holds and which Lists would lose rows. Writes nothing. */
    data object Reading : DeleteTodoRequest

    /**
     * What would go, named rather than counted.
     *
     * [taskCount] is what the Todo holds; [lists] names every List that would lose memberships and
     * how many, because a List quietly getting shorter is the failure `docs/file-management.md`
     * already refuses for files. [keepState] is pre-selected from the Todo's own stored default
     * ([SourceEntity.keepStateOnDelete]), and it is a checkbox rather than a fixed policy for the
     * same reason: the consequence is a later Todo at this folder being able to load these
     * decisions, and that is a thought a user can only have here.
     */
    data class Confirm(
        val sourceId: String,
        val sourceName: String,
        val taskCount: Int,
        val lists: List<ListLosingItems>,
        val keepState: Boolean
    ) : DeleteTodoRequest

    data object Deleting : DeleteTodoRequest

    /**
     * [report] is what actually went, so the screen can name the Lists again rather than assuming
     * the ones it listed.
     */
    data class Done(val report: DissolutionReport) : DeleteTodoRequest

    data class Error(val message: String) : DeleteTodoRequest
}

/** Holds the List screens' state. Talks to the DAO directly; there is no repository layer yet. */
/**
 * One automatic delivery held at the confirmation, with the answer it is waiting for.
 *
 * A plain class and not a data class on purpose: two identical-looking deliveries are still two
 * deliveries, and the queue removes entries by identity.
 */
private class PendingWebhookSend(val describing: WebhookConfirmation) {
    val decision = CompletableDeferred<Boolean>()
}

/**
 * What one run of the file deletion actually achieved, for whichever dialog asked for it.
 *
 * [recordFailed] is deliberately separate from [failed]: the files are gone either way, and the
 * two are different things to have to tell someone. One says a file is still on the device; the
 * other says it is not, but Listea's own rows have not caught up.
 */
private class FileDeletionOutcome(
    val deleted: Int,
    val failed: Int,
    val recordFailed: Boolean
)

/**
 * What a Todo was found to hold, read in one go for the confirmation dialog.
 *
 * The two reads travel together because they are two halves of one question — *what goes* and *who
 * notices* — and the dialog cannot be drawn with only one of them. Not a data class: nothing
 * compares two of these.
 */
private class TodoHeld(
    val taskCount: Int,
    val lists: List<ListLosingItems>
)

/** What a deletion that worked but could not be filed away has to admit to. Shared, so both
 * dialogs admit to it in the same words. */
private const val DeletionNotRecordedMessage =
    "The files were deleted, but Listea could not record that. Use \"Update from folder\" on " +
        "the Lists they came from."

/**
 * [dao], [folders] and [ioDispatcher] are seams for one test and are defaulted to the real thing
 * everywhere else: `ConsentGateViewModelTest` has to drive the consent gate at the level it lives
 * at, and a Robolectric run has neither a database it can watch nor a SAF grant it can read, nor a
 * `viewModelScope` that finishes when the test says so.
 *
 * `@JvmOverloads` is load-bearing, not tidiness. Kotlin generates no constructor for a class with
 * defaulted parameters except the full one and a synthetic bitmask one, so adding these parameters
 * silently deleted the single-argument constructor the app is actually built with —
 * `ViewModelProvider.AndroidViewModelFactory` looks the ViewModel up as
 * `modelClass.getConstructor(Application.class)` and throws `NoSuchMethodException` from inside
 * `setContent` when it is not there, which is a crash before anything renders rather than a wrong
 * screen. The annotation restores the one-argument overload. `AndroidFactoryTest` holds it down.
 */
class ListsViewModel @JvmOverloads constructor(
    application: Application,
    private val dao: ListsDao = ListeaDatabase.get(application).listsDao(),
    private val folders: FolderSource = SafFolderSource(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The template tables, when the caller has a database of its own.
     *
     * Null on the app's own path, which opens [ListeaDatabase.get] on first use. A test that seeds
     * an in-memory database and also drives a refresh needs both halves to be the *same* database:
     * the picker offers what this reads, while the run writes through [dao], and two databases would
     * quietly disagree about what the offered version is.
     */
    private val injectedTemplateStore: TemplateStore? = null
) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    /**
     * App settings for the screens to observe. Writes are immediate; the UI simply follows.
     * Anywhere a stale value would actually be wrong — creating a list, sending a webhook — the
     * store is read directly instead, so nothing depends on this having warmed up.
     */
    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * Whether the one-time v27 address backfill has been attempted in this process.
     *
     * Deliberately not run in the constructor: most tests build a ViewModel against a database and
     * a settings store they arranged, and a constructor with a write would make every one of them
     * depend on the marker table. [ListeaApp] starts [ensureWebhookAddressBackfill] once on launch,
     * and the delivery, test and resend paths await it, so a send can never race the snapshot.
     */
    @Volatile
    private var webhookAddressBackfillDone = false
    private val webhookAddressBackfillLock = Mutex()

    /**
     * Applies `MIGRATION_26_27`'s deferred work: copy the old default address onto existing Todos.
     *
     * Idempotent at the database as well as here — the marker is consumed in the same transaction
     * as the write — so a second call, or a second process, cannot re-copy a default the user has
     * since edited. The flag only saves the round trip.
     */
    suspend fun ensureWebhookAddressBackfill() {
        if (webhookAddressBackfillDone) return
        webhookAddressBackfillLock.withLock {
            if (webhookAddressBackfillDone) return
            val current = settingsStore.read()
            dao.applyWebhookAddressBootstrap(
                name = WEBHOOK_ADDRESS_BACKFILL,
                url = current.defaultWebhookUrl,
                gateWasOn = current.defaultWebhookEnabled
            )
            webhookAddressBackfillDone = true
        }
    }

    /**
     * The remembered filters and sort orders. Observed like [settings] and written the same way.
     *
     * Read through [resolveArrangement] rather than directly: which of these a folder is actually
     * showing under depends on [AppSettings.sharedFileArrangement], and that decision belongs in
     * one place.
     */
    val arrangements: StateFlow<FolderArrangements> = settingsStore.arrangements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderArrangements())

    val summaries: StateFlow<List<ListSummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The registered Sources, for the Sources surface. */
    val sources: StateFlow<List<SourceEntity>> = dao.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The registered Upstreams, for the Upstreams surface.
     *
     * Nothing is joined onto them and nothing is counted: an Upstream is a folder, and v16 exists
     * because the app used to read a folder and a decision made about one off the same row. What a
     * card can say about one is its name, where it sits, and whether the grant is still held — the
     * last of which is the screen's to answer, because only it has a Context.
     */
    val upstreams: StateFlow<List<UpstreamEntity>> = dao.observeUpstreams()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Sources as the Sources screen reads them: with the count of Tasks each one owns.
     *
     * A second flow rather than a wider [sources], because the two are read by different surfaces
     * with different needs — the Folder tab asks which Source covers a folder and has no use for a
     * count it would then have to join for. Both are the same `sources` table, so neither can drift
     * from the other about which Sources exist.
     */
    val sourceSummaries: StateFlow<List<SourceSummary>> = dao.observeSourceSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderListRequest = MutableStateFlow<FolderListRequest?>(null)
    val folderListRequest: StateFlow<FolderListRequest?> = _folderListRequest.asStateFlow()

    private val _resyncRequest = MutableStateFlow<ResyncRequest?>(null)
    val resyncRequest: StateFlow<ResyncRequest?> = _resyncRequest.asStateFlow()

    /**
     * Bumped once each time an import run has committed, from any entry point: Quick Review's
     * consent, the Folder tab's Import Folder, or a Source's Refresh Import. Screens that read the
     * catalog re-subscribe on every bump, which is what makes the Tasks a run just created show up.
     *
     * A counter published by the *write* rather than by the tap. Quick Review used to count its own
     * taps, which re-subscribed it the moment the dialog closed and read the catalog back before the
     * import had run.
     */
    private val _importVersion = MutableStateFlow(0)
    val importVersion: StateFlow<Int> = _importVersion.asStateFlow()

    private val _importFolderRequest = MutableStateFlow<ImportFolderRequest?>(null)
    val importFolderRequest: StateFlow<ImportFolderRequest?> = _importFolderRequest.asStateFlow()

    /**
     * Why a catalog selection could not be written into a List, waiting to be acknowledged.
     *
     * One channel for both ways a selection reaches a List — a new one and an existing one — for
     * the reason they can fail: the selection names a Task the catalog can no longer reach, and
     * that failure does not depend on which of the two was being asked for.
     */
    private val _listCreationError = MutableStateFlow<String?>(null)
    val listCreationError: StateFlow<String?> = _listCreationError.asStateFlow()

    /**
     * Delivery results waiting to be acknowledged, oldest first. Held here rather than on a screen
     * because a completion webhook can fire from anywhere, and the user should not have to be
     * looking at the right list to find out what happened.
     *
     * A queue rather than a single slot: one swipe can finish a review *and* complete the list it
     * belongs to, which is two deliveries against two different webhook configurations. They used
     * to overwrite each other, so whichever finished second was the only one the user ever saw.
     */
    private val _webhookNotices = MutableStateFlow<List<WebhookNotice>>(emptyList())

    /** The result the user is being shown, or null when there is nothing left to acknowledge. */
    val webhookNotice: StateFlow<WebhookNotice?> = _webhookNotices
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Automatic deliveries waiting for the user to say yes, oldest first.
     *
     * A queue for the same reason [_webhookNotices] is one: one swipe can reach the end of a
     * round and complete the list it belongs to, and two questions asked at once must not
     * overwrite each other. Only one is ever on screen; the next comes up when it is answered.
     */
    private val _webhookConfirmations = MutableStateFlow<List<PendingWebhookSend>>(emptyList())

    /** The delivery the user is being asked about, or null when nothing is waiting. */
    val webhookConfirmation: StateFlow<WebhookConfirmation?> = _webhookConfirmations
        .map { it.firstOrNull()?.describing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The POST currently in flight, or null when none is.
     *
     * A single slot rather than a queue: a delivery holds this for exactly as long as it is on
     * the wire, and nothing else can be posting at the same moment because every send goes
     * through [post]. What it buys is the seconds a large round spends uploading, which used to
     * be an empty screen the user could not tell from a finished one.
     */
    private val _webhookProgress = MutableStateFlow<WebhookProgress?>(null)
    val webhookProgress: StateFlow<WebhookProgress?> = _webhookProgress.asStateFlow()

    /**
     * Every delivery ever recorded, newest first. Observed rather than fetched so the history
     * page and the Settings entry that leads to it can never disagree about how much there is.
     */
    val webhookHistory: StateFlow<List<ExportRecordSummary>> = dao.observeWebhookHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How much history there is, without loading a single payload to count it. */
    val webhookRecordCount: StateFlow<Int> = dao.observeWebhookRecordCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * How many rounds the history holds, which is not how many rows it holds: one delivery can
     * leave several exports. Legacy rows without a round id each count as a round of one.
     */
    val webhookRoundCount: StateFlow<Int> = dao.observeWebhookRoundCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** How much of it is still owed to a receiver — the part worth drawing attention to. */
    val pendingWebhookCount: StateFlow<Int> = dao.observePendingWebhookCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The templates, for the two pages Settings reaches them through.
     *
     * **Built on first use rather than in the constructor**, and not only to save the work. This is
     * the one thing on this ViewModel that reaches past the [ListsDao] it is handed, so it is the
     * one thing a test that injects a fake DAO would be made to open the app's real database file
     * for — and none of those tests looks at a template. [injectedTemplateStore] is for the test
     * that does.
     */
    private val templateStore: TemplateStore by lazy {
        injectedTemplateStore ?: TemplateStore(ListeaDatabase.get(getApplication()))
    }

    /**
     * Every template lineage, by name. Observed, because the page edits what the list shows.
     *
     * **Null until the table has been read**, and the null is load-bearing rather than a
     * placeholder. This app has no spinner anywhere, so an empty list would have to stand in for
     * *there are none* — and the template list would spend its first frame telling the user there
     * are no templates on an install that always has one. Null is also the only thing that lets the
     * page behind a row say *this template is gone* rather than *nothing has been read yet*, which
     * are opposite facts a row's absence cannot tell apart on its own.
     */
    val templates: StateFlow<List<TemplateEntity>?> by lazy {
        templateStore.templates
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /**
     * Every version of every template, oldest first within each, with the same null rule.
     *
     * The whole table rather than one lineage's versions, because the list screen names each row's
     * current version and that is one read for all of them; a page picks its own lineage out by the
     * last entry carrying its id.
     */
    val templateVersions: StateFlow<List<TemplateVersionEntity>?> by lazy {
        templateStore.versions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /**
     * The two tables as one lookup: what a Task attached to a template actually offers. See
     * [TemplateResolver].
     *
     * **Half of this release lives in this flow.** Every surface that draws a chip, a badge or a
     * payload used to ask [AppSettings], which answered from a DataStore tag list with a built-in
     * fallback; the answer is a row's now, and this is the one place rows are turned into something
     * a screen can be given. It is a resolver value rather than a pair of lists so that a screen
     * takes *the lookup* and cannot help but resolve the same way as the screen beside it.
     *
     * **Not null, unlike the two above**, and for the opposite reason. Those are null to say *not
     * read yet* to a page that would otherwise report an empty install; a resolver has no such
     * page, and every caller only ever uses it to answer for a file. So the seed is
     * [TemplateResolver.Empty] — which is *no template governs anything* — and the first real read
     * replaces it. A chip row drawn in the frame before the tables land is a chip row with no chips,
     * which is one frame of an empty row rather than a claim about the templates.
     *
     * Both flows are observed together because a resolver built from one of them would be a resolver
     * that offered a template's buttons before its scope: the two tables are read in one transaction
     * by [TemplateStore] and are only ever meaningfully consumed as a pair.
     */
    val templateResolver: StateFlow<TemplateResolver> by lazy {
        combine(templates, templateVersions) { lineages, versions ->
            TemplateResolver.of(lineages.orEmpty(), versions.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateResolver.Empty)
    }

    private val _sourceFreshness = MutableStateFlow<ListFreshness?>(null)
    val sourceFreshness: StateFlow<ListFreshness?> = _sourceFreshness.asStateFlow()

    /** The in-flight automatic check, kept so a newer one can replace it rather than race it. */
    private var freshnessJob: Job? = null

    private val _deleteCheckedRequest = MutableStateFlow<DeleteCheckedRequest?>(null)
    val deleteCheckedRequest: StateFlow<DeleteCheckedRequest?> = _deleteCheckedRequest.asStateFlow()

    /** Where a Todo dissolution has got to, or null when none is being asked about. */
    private val _deleteTodoRequest = MutableStateFlow<DeleteTodoRequest?>(null)
    val deleteTodoRequest: StateFlow<DeleteTodoRequest?> = _deleteTodoRequest.asStateFlow()

    /**
     * What a delivered webhook is offering to clean up, or null when nothing is.
     *
     * A single slot and not a queue, unlike the notices: two rounds cannot be delivered at once
     * — every send goes through the one [post] — and by the time a second could arrive the first
     * offer has been answered or dismissed. Hosted above the tabs, because the round that
     * triggered it may have been left behind by then.
     */
    private val _postWebhookDeletion = MutableStateFlow<PostWebhookDeletion?>(null)
    val postWebhookDeletion: StateFlow<PostWebhookDeletion?> = _postWebhookDeletion.asStateFlow()

    /**
     * Orders item writes against the payload reads that follow them.
     *
     * A review's last swipe checks an item and then asks for a delivery, as two separate calls
     * from the same handler. Both reach Room on its own executor, so without this the payload
     * could be built from rows the swipe had not landed on yet — and with "send only checked
     * items" on, the very item just swiped would be the one missing. The lock is fair, and it is
     * held only across the database work: the network post happens outside it, so a slow endpoint
     * never blocks the next tap.
     */
    private val writeOrder = Mutex()

    /**
     * Lists with a review round open on screen right now.
     *
     * A round is reported once. Swiping the last item of a List review both completes the List and
     * ends the round, and `list.completed` and `review.exited` go to the *same* webhook for a List
     * review — so without this the receiver gets the same round twice, seconds apart, which is
     * what [leaveReview] has always claimed does not happen.
     *
     * The round's own report is the one that survives, because it is the narrower and more honest
     * of the two: with *Review unchecked items only* and *Send checked items only* both on, it
     * carries exactly the decisions of the round just done, while the completion would carry the
     * whole List — including items checked weeks ago that have already been sent.
     *
     * Read and written only inside [writeOrder], which is what makes it correct rather than merely
     * likely: the swipe's completion write and the exit that closes the round are two separate
     * coroutines, and only the lock guarantees the swipe sees the round still open. Membership is
     * added at the start of each round, so a completion reached anywhere else — the detail page's
     * checkbox, a folder's Quick Review — is never mistaken for a round's work.
     */
    private val openReviewRounds = mutableSetOf<Long>()

    /**
     * How many rounds are open that belong to no List — a folder round, which has no id to be named
     * by.
     *
     * Counted rather than tracked, because the only question ever asked of it is *is anything being
     * reviewed right now*; a folder has no second question to answer, and a set of nothing would be
     * a set. It exists because [CompletionScope.AnotherRound] is about the **screen** the user is
     * on and not about the Lists involved: a List completing while a folder's Quick Review is open
     * is the same interruption as one completing during another List's review, and the Tasks are
     * frequently the same Tasks, so it is not a rare side effect either.
     */
    private var openFolderRounds = 0

    /** Scan result waiting for confirmation. Nothing is written until it is committed. */
    private var pendingFolderList: PendingFolderList? = null
    private var pendingResync: PendingResync? = null

    /**
     * A folder walked but not yet imported. The snapshot is held rather than re-scanned so that the
     * count the user agreed to is the count that is imported.
     */
    private var pendingImport: PendingImport? = null

    /** The exact files the confirmation dialog is describing, held until it is answered. */
    private var pendingDeletion: List<String> = emptyList()

    /**
     * The Todo the confirmation is describing, held until it is answered.
     *
     * Only the id, because everything else about it is re-read inside the transaction that removes
     * it — a Todo can gain Tasks between the dialog opening and the answer coming back, and the
     * delete is about what the Todo *is* rather than about the listing the user was shown.
     */
    private var pendingDeleteSourceId: String? = null

    /**
     * The same, for the offer a delivered webhook makes, with the root its paths are relative to.
     *
     * Held apart from [pendingDeletion] because the two dialogs can genuinely both be waiting:
     * a round can finish while Settings is open behind it, and an answer to one must never be
     * able to act on the other's files.
     */
    private var pendingSentDeletion: List<String> = emptyList()
    private var pendingSentDeletionRoot: String? = null

    private data class PendingResync(val listId: Long, val files: List<ScannedFile>)

    /**
     * A Create Tasks run that has been read but not written.
     *
     * The tree is held so the run does not re-read a folder the user has just been shown a count of,
     * and so what is imported is what the dialog described even if the folder moves underneath it.
     *
     * [sourceId] is which of the two runs this is, and it is the *only* difference that matters at
     * the write. A refresh knows the Todo it is refreshing and must land in that one; Create Tasks
     * has no Todo yet and mints one. There is no third case, because a folder may back any number
     * of Todos, so an import that resolved "the Source over this folder" could not say which
     * intention it had just extended.
     */
    private data class PendingImport(
        val rootUri: String,
        val folderPath: String,
        val tree: FolderSnapshot,
        val options: ImportOptions,
        val sourceId: String? = null
    )

    private data class PendingFolderList(
        val title: String,
        val rootUri: String,
        val relativePath: String,
        val files: List<ScannedFile>,
        val replacedIds: List<Long>,
        val replacedTitles: List<String>,
        val replacesExact: Boolean
    )

    /**
     * Every folder-backed scope under [rootUri], per-folder subtree progress, per-file ticks, and
     * which Todos hold each path: four observations for the whole screen, never one per visible row.
     * The indexing pass runs off the main thread and only when the data changes.
     */
    fun observeFolderOwnership(rootUri: String): Flow<FolderOwnership> =
        combine(
            dao.observeFolderScopes(rootUri),
            dao.observeFolderScopeItems(rootUri),
            dao.observeFileCompletion(rootUri),
            dao.observeFileClaims(rootUri)
        ) { scopes, items, files, claims ->
            buildFolderOwnership(scopes, items, files, claims)
        }.flowOn(Dispatchers.Default)

    /**
     * Which Todos have a Task over one file, and what each of them decided — the drill-in behind a
     * marked file row.
     *
     * Read on demand rather than observed with the rest of the browser, because it is asked one
     * file at a time and only when somebody opens one: a folder of shared files would otherwise
     * carry a row per Task over every one of them into every listing.
     */
    suspend fun sharedFileStates(rootUri: String, relativePath: String): List<SharedFileState> =
        dao.getSharedFileStates(rootUri, relativePath)

    /**
     * One list, with its rows resolved into review items.
     *
     * The list is what supplies each row's root: a decision's identity is the file under a grant,
     * and only the list knows which grant its items are under. Manual rows resolve to their own
     * state, which is still theirs.
     */
    fun observeDetail(listId: Long): Flow<ListDetail?> =
        combine(
            dao.observeList(listId),
            dao.observeItems(listId),
            dao.observeAssetsForList(listId),
            dao.observeAssetStatesForList(listId),
            dao.observeAssetControlsForList(listId)
        ) { list, items, assets, states, controls ->
            list?.let { owner ->
                val byTask = assets.groupBy { it.taskId }
                // The records, read beside the Assets and paired here, exactly as the fold of them
                // onto the item is taken from the Assets themselves a few lines down. A pair that
                // has never been written has no row, and `AssetDecisions()` is what that means: a
                // file nobody has said anything about, which is not the same as a missing answer.
                val stateByAsset = states.associate { it.assetId to it.decisions }
                // A Task with no import row is absent, and the item's default `""` is the right
                // answer for it: a manual entry has no run behind it to have been configured by.
                val controlByTask = controls.associate { it.taskId to it.assetControls }
                ListDetail(
                    owner,
                    items.map { item ->
                        val resolved = byTask[item.taskId].orEmpty().map { asset ->
                            asset.toReviewAsset(stateByAsset[asset.id] ?: AssetDecisions())
                        }
                        item.toReviewItem(
                            rootUri = owner.sourceRootUri,
                            assetControls = controlByTask[item.taskId].orEmpty()
                        ).copy(
                            assets = resolved,
                            reviewBlocked = reviewBlockFor(resolved)
                        )
                    }
                )
            }
        }

    /**
     * A folder's own files, resolved for review without a List anywhere in sight.
     *
     * This is the decoupling in one function. The folder is read, its direct files are registered
     * so each has an identity to carry decisions on, and from then on the queue simply follows the
     * decisions held against those identities. No list is consulted, created or replaced, and any
     * List that happens to cover this folder is left completely alone — it will nonetheless show
     * every decision made here, because both are reading the same file state.
     *
     * Subfolders are excluded, exactly as before: Quick Review has always been one folder's own
     * files, and a folder of folders is a place to navigate rather than a queue to review.
     */
    fun observeFolderReview(rootUri: String, folderPath: String): Flow<FolderReview> = flow {
        // Deliberately does not emit [FolderReview.Resolving]: the screen supplies that as its
        // initial value, and emitting it here would also fire on every *re*-collection. Coming
        // back from the background would then drop the queue composable, taking the round's
        // "what was unchecked on entry" snapshot with it and silently re-narrowing the queue to
        // exclude everything just reviewed. Staying on the last resolved value until the next one
        // arrives is what makes a paused review resume as the same round.
        val files = withContext(ioDispatcher) { resolveFolderFiles(rootUri, folderPath) }
        if (files == null) {
            emit(FolderReview.Unavailable)
            return@flow
        }

        // Nothing imported here: the consent dialog, not a silent empty round. Once a Source covers
        // the folder the question is never asked again, which is why it is a per-folder check.
        //
        // Asked before the listing decides anything, because a folder-as-Task scope has no listing
        // behind its Tasks: browsing the folder that holds them *is* the case where the browsed
        // folder has no direct files, and resolving that to an empty round would leave the whole
        // groupPath-ordered branch of [folderReviewQueue] unreachable. A folder with no files and no
        // Source covering it is still not asked, though: there is nothing a file-as-Task import over
        // it would bring in, so consent there would be a dialog that imports nothing.
        val source = dao.sourcesCovering(rootUri, folderPath).firstOrNull()
        if (source == null) {
            if (files.isEmpty()) {
                emit(FolderReview.Ready(emptyList()))
            } else {
                emit(FolderReview.NotImported(folderPath.ifEmpty { "the selected folder" }, "file-as-Task"))
            }
            return@flow
        }

        val paths = files.map { it.rootRelativePath }
        if (paths.isNotEmpty()) {
            dao.registerFiles(
                paths.map {
                    FileReviewStateEntity(
                        // Generated per resolution but kept only on the first, because registerFiles
                        // ignores conflicts: a file's id is assigned the first time Listea sees it
                        // under this root and is not renewed by later visits to the same folder.
                        publicId = newItemPublicId(fileIdentity(it), now()),
                        rootUri = rootUri,
                        relativePath = it,
                        updatedAt = now()
                    )
                }
            )
            // Reading them is proof they are there, which un-flags anything deleted and put back.
            dao.markFilesPresent(rootUri, paths)
        }
        // The queue is built from catalog Tasks, not minted here. Registration is a browse concern
        // and stays; creating a Task is an import's job and does not happen on this path.
        val rows = dao.getCatalogTasks(source.id)
        val states = if (paths.isEmpty()) emptyList() else dao.getFileStates(rootUri, paths)
        val queue = folderReviewQueue(rootUri, folderPath, files, rows, states)
        if (queue.isEmpty()) {
            // Nothing to walk, so nothing to watch: the notice is one answer read once, and
            // subscribing to an empty set of ids would be a subscription to nothing.
            emit(FolderReview.Ready(items = queue, notice = scopeNoticeOf(rows, folderPath)))
            return@flow
        }
        // The queue's *membership* is frozen — this round is over the files that were here when it
        // opened, which is what the screen's own snapshot then narrows further — but its decisions
        // follow the state live. A checkbox or a tag tapped on this surface writes to the Task, and
        // until this the flow had already emitted its one value: the write landed and the control
        // never moved. Room invalidates on both state tables, so the re-emission arrives in the
        // frame.
        emitAll(liveDecisions(queue))
    }

    /**
     * The round a Todo starts at [scope]: the Tasks that lie exactly there, narrowed by eligibility.
     *
     * "Exactly there" is [directChildOf], the same test the catalog screen descends by and the same
     * one a folder round has always used, so what a scope shows and what a Quick Review over it
     * queues are one answer and not two. A scope's subfolders are places to go, not material to
     * review: standing at a Todo's root and being handed every Task beneath it is the behaviour this
     * narrowing removes.
     *
     * The notification for an empty scope is [scopeNoticeOf] on the *whole* catalog rather than on
     * the narrowed rows, because the two reasons a scope can be empty include one — a Task whose
     * whole lies above the scope — that the narrowed rows no longer contain evidence of.
     *
     * **[scope] is grant-relative and a Todo's root is not the grant's.** A run started inside a
     * subfolder prefixes every `groupPath` it writes with that subfolder — `task_imports.groupPath`
     * is `childRelativePath(source.relativePath, …)` — so the Todo's own root is the Source's
     * `relativePath` and not `""`. Asking with `""` finds nothing in such a Todo and reports an
     * empty level, which is what a Todo opened at the wrong origin used to do.
     *
     * [flat] because a flat Todo has one level and it is every Task the Todo holds, whatever depth
     * their paths sit at. [catalogLevel] is the same answer the catalog draws, so the round and the
     * list it was started from cannot disagree about what is on screen.
     *
     * [rootUri] is still needed, and is the one thing that can be wrong: a decision about a Task is
     * written against its file, and the file is named relative to a grant. The screen does not open
     * this round at all when the grant is gone.
     */
    fun observeTaskReview(
        sourceId: String,
        rootUri: String,
        scope: String,
        flat: Boolean = false
    ): Flow<FolderReview> = flow {
        val rows = dao.getCatalogTasks(sourceId)
        val here = catalogLevel(rows, scope, flat)
        val queue = taskReviewQueue(rootUri, here)
        if (queue.isEmpty()) {
            emit(FolderReview.Ready(items = queue, notice = scopeNoticeOf(rows, scope, flat)))
            return@flow
        }
        // The same live decisions as a folder round, and for the same reason: this flow used to
        // emit exactly once, so a checkbox or a tag tapped on a Todo round was written to the
        // database and drawn nowhere — the value was still whatever the queue was built with.
        emitAll(liveDecisions(queue))
    }

    /**
     * A round's queue, re-read on every write.
     *
     * Both state tables, combined, because a round draws both halves: `task_state` for the tick and
     * `task_asset_state` for the marks, the latter scoped by Task through the Assets the queue is
     * already holding. One flow and not two subscriptions, so a write that moves both — a
     * submission — cannot be observed as a tick without its marks.
     */
    private fun liveDecisions(queue: List<ReviewItem>): Flow<FolderReview> =
        combine(
            dao.observeTaskStates(queue.map { it.taskId }),
            dao.observeTaskAssetStates(queue.map { it.taskId })
        ) { states, assetStates ->
            FolderReview.Ready(items = withLiveDecisions(queue, states, assetStates))
        }

    /**
     * Reads a browsed folder's direct files. Blocking: call on IO. Null when it cannot be read.
     */
    private fun resolveFolderFiles(rootUri: String, folderPath: String): List<FolderFile>? =
        folders.readDirectFiles(rootUri, folderPath)

    // There is no createList any more. Lists are made from the Folder tab, since a List is a review
    // context over Tasks and the only way to hand it any is to point it at a folder. A List with no
    // folder link could never gain an item once manual entry went, and completion needs an item to
    // complete, so the control could only ever have made permanently empty Lists.
    //
    // A product boundary, not an architectural rule: the standalone Lists an older database holds
    // keep working, and a future importer or Task creator is free to add another way to make one.

    fun renameList(listId: Long, title: String) = edit(title) { clean ->
        dao.updateListTitle(listId, clean)
    }

    fun deleteList(listId: Long) = launchDb { dao.deleteList(listId) }

    /** How many checked files a delete would take, for the card that offers it. */
    fun observeCheckedFileCount(rootUri: String): Flow<Int> =
        dao.observeCheckedFileCount(rootUri)

    /**
     * Step 1 of deleting checked files: gather them and describe them. Reads only — nothing is
     * written and no file is touched until the confirmation comes back.
     *
     * The write grant is checked here rather than at the point of deletion, because finding out
     * halfway through a hundred files that Listea was never allowed to delete any of them is the
     * worst possible moment to say so.
     */
    fun requestCheckedFileDeletion(rootUri: String?) = launchDb {
        _deleteCheckedRequest.value = DeleteCheckedRequest.Scanning
        pendingDeletion = emptyList()

        if (rootUri == null) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.Error("No folder is selected.")
            return@launchDb
        }

        val writable = withContext(ioDispatcher) {
            hasPersistedWriteAccess(getApplication(), rootUri.toUri())
        }
        if (!writable) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.NeedsWriteAccess
            return@launchDb
        }

        val paths = runCatching { dao.getCheckedFilePaths(rootUri) }.getOrNull()
        if (paths == null) {
            _deleteCheckedRequest.value =
                DeleteCheckedRequest.Error("Could not read the checked files. Nothing was deleted.")
            return@launchDb
        }
        if (paths.isEmpty()) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.NothingChecked
            return@launchDb
        }

        pendingDeletion = paths
        _deleteCheckedRequest.value = DeleteCheckedRequest.Confirm(
            groups = withContext(Dispatchers.Default) { groupCheckedFiles(paths) },
            fileCount = paths.size
        )
    }

    /**
     * Step 2: the permanent part. Deletes exactly the files the dialog listed — re-reading them
     * here could pick up a file checked while the dialog was open, which the user never saw and
     * never agreed to.
     *
     * Only the files that genuinely went are recorded as gone, and recorded as *missing* rather
     * than erased: the decision about a file outlives the file, so a List whose files were deleted
     * stays complete and says its sources are missing, exactly as it would after a re-sync. The
     * existing "remove missing items" cleanup is there for anyone who wants the rows gone too.
     */
    fun confirmDeleteCheckedFiles(rootUri: String?) = launchDb {
        val paths = pendingDeletion
        if (paths.isEmpty() || rootUri == null) return@launchDb
        pendingDeletion = emptyList()
        _deleteCheckedRequest.value = DeleteCheckedRequest.Deleting

        val outcome = runFileDeletion(rootUri, paths)
        _deleteCheckedRequest.value = if (outcome.recordFailed) {
            // The files are gone either way; what failed is the bookkeeping, and saying so is
            // better than a success message in front of items that still claim to have a file.
            DeleteCheckedRequest.Error(DeletionNotRecordedMessage)
        } else {
            DeleteCheckedRequest.Done(deleted = outcome.deleted, failed = outcome.failed)
        }
    }

    /**
     * The permanent part itself, shared by the two dialogs that can reach it.
     *
     * Only the files that genuinely went are recorded as gone, and recorded as *missing* rather
     * than erased: the decision about a file outlives the file, so a List whose files were
     * deleted stays complete and says its sources are missing, exactly as it would after a
     * re-sync.
     */
    private suspend fun runFileDeletion(
        rootUri: String,
        paths: List<String>
    ): FileDeletionOutcome {
        val result = withContext(ioDispatcher) {
            deleteCheckedFiles(getApplication(), rootUri.toUri(), paths)
        }
        val recorded = runCatching {
            writeOrder.withLock { dao.markFilesDeleted(rootUri, result.deletedPaths) }
        }
        return FileDeletionOutcome(
            deleted = result.deletedPaths.size,
            failed = result.failedCount,
            recordFailed = recorded.isFailure
        )
    }

    fun dismissDeleteCheckedRequest() {
        pendingDeletion = emptyList()
        _deleteCheckedRequest.value = null
    }

    /**
     * Forget one Upstream. The registration goes; everything made from it stays.
     *
     * Only the `upstreams` row is deleted. The Todos that were created under it, their Tasks and
     * decisions, the Lists holding those Tasks and the files on disk are all untouched — an
     * Upstream is where material came from, not what was made of it, and forgetting the source of
     * something is not the same act as destroying the thing. The SAF grant is left in place too,
     * because another Upstream may sit under the same tree and because dropping a permission is a
     * separate decision the confirmation names rather than takes.
     */
    fun deleteUpstream(id: String) = launchDb { dao.deleteUpstream(id) }

    /**
     * Step 1 of dissolving a Todo: read what it holds and which Lists would lose rows. Reads only.
     *
     * The Lists are gathered here rather than inside the delete so the confirmation can *name* them,
     * which is the same rule [requestCheckedFileDeletion] follows: a destructive operation shows the
     * listing, not a total. They are gathered again inside the transaction anyway, because that is
     * what tells each List afterwards what it lost — the two reads are the same query for the same
     * reason, and neither can be dropped for the other.
     */
    fun requestDeleteTodo(sourceId: String) = launchDb {
        _deleteTodoRequest.value = DeleteTodoRequest.Reading

        val source = dao.getSource(sourceId)
        if (source == null) {
            _deleteTodoRequest.value =
                DeleteTodoRequest.Error("This Todo is no longer registered. Nothing was changed.")
            return@launchDb
        }

        val held = runCatching {
            TodoHeld(
                taskCount = dao.countTasksOfTodo(sourceId),
                lists = dao.listsHolding(sourceId)
            )
        }.getOrNull()
        if (held == null) {
            _deleteTodoRequest.value =
                DeleteTodoRequest.Error("Could not read this Todo. Nothing was changed.")
            return@launchDb
        }

        pendingDeleteSourceId = sourceId
        _deleteTodoRequest.value = DeleteTodoRequest.Confirm(
            sourceId = sourceId,
            sourceName = source.displayName.ifEmpty { sourceNameFor(source.relativePath) },
            taskCount = held.taskCount,
            lists = held.lists,
            // The Todo's stored default, which the dialog may then change. The decision is the
            // checkbox's; this is only where it starts.
            keepState = source.keepStateOnDelete
        )
    }

    /**
     * Step 2: the Todo goes.
     *
     * [keepState] is the answer to the checkbox, taken as the argument rather than read back off the
     * state, because the state is what the screen is *drawing* and this is what the user said. The
     * write itself is one transaction in the DAO — the snapshot, the memberships, the Tasks, the
     * Source and the notes to each List all commit together or not at all, which is the only reason
     * a half-dissolved Todo cannot exist.
     */
    fun confirmDeleteTodo(keepState: Boolean) = launchDb {
        val sourceId = pendingDeleteSourceId
        if (sourceId == null) return@launchDb
        pendingDeleteSourceId = null
        _deleteTodoRequest.value = DeleteTodoRequest.Deleting

        val report = runCatching {
            writeOrder.withLock { dao.deleteTodo(sourceId, keepState, now()) }
        }.getOrNull()

        if (report == null) {
            // Two causes and one sentence, because they are the same sentence: `deleteTodo` returns
            // null only before it writes anything, whether because the Source had already gone or
            // because the transaction threw. Either way nothing was changed, and claiming a delete
            // this run did not perform is the one thing the message must not do.
            _deleteTodoRequest.value =
                DeleteTodoRequest.Error("The Todo could not be deleted. Nothing was changed.")
            return@launchDb
        }

        // Every screen that re-collects on this is looking at a database whose Tasks moved under it:
        // the catalog, the Quick Review queue, the List detail page and the browser's completion
        // marks all read rows this transaction just removed.
        _importVersion.value += 1
        _deleteTodoRequest.value = DeleteTodoRequest.Done(report)
    }

    fun cancelDeleteTodo() {
        pendingDeleteSourceId = null
        _deleteTodoRequest.value = null
    }

    /**
     * What a webhook that has just been reported leaves behind, when the user has asked to be
     * offered it.
     *
     * The set is taken from [items] — the payload's own items, as they were sent — and narrowed
     * once, to the checked ones that still have a file. That is the whole semantics of the
     * feature: whatever the review queued and whatever the payload filter kept, the files offered
     * here are exactly the ones the receiver was just told are checked. Nothing is re-read from
     * the database, because a file checked between the send and this moment was not part of what
     * was sent.
     *
     * A delivery that failed is reported rather than acted on. The round is in the webhook
     * history and can be sent again from Settings, and deleting the files it described before
     * anybody has received it would destroy the only copy of what the round was about.
     *
     * Silent when the switch is off, when the payload came from a list with no source folder — a
     * manual list has no files to delete — and when nothing in it was both checked and still on
     * the device, because an offer to delete nothing is a dialog that only costs a tap.
     */
    private suspend fun offerPostWebhookDeletion(
        settings: AppSettings,
        list: ListEntity,
        items: List<ListItemEntity>,
        notice: WebhookNotice
    ) {
        if (!settings.askDeleteAfterWebhook) return
        val rootUri = list.sourceRootUri ?: return
        val paths = sentCheckedFilePaths(items)
        if (paths.isEmpty()) return

        if (!notice.succeeded) {
            _postWebhookDeletion.value = PostWebhookDeletion.NotSent(
                listTitle = list.title,
                fileCount = paths.size,
                detail = notice.outcomeLabel
            )
            return
        }

        // Checked before the offer rather than after it, for the same reason the Settings flow
        // checks it before listing: being asked to confirm a deletion that was never permitted
        // is worse than being told up front that it is not.
        val writable = withContext(ioDispatcher) {
            hasPersistedWriteAccess(getApplication(), rootUri.toUri())
        }
        if (!writable) {
            _postWebhookDeletion.value = PostWebhookDeletion.NeedsWriteAccess(paths.size)
            return
        }

        pendingSentDeletion = paths
        pendingSentDeletionRoot = rootUri
        _postWebhookDeletion.value = PostWebhookDeletion.Ask(
            listTitle = list.title,
            eventLabel = webhookEventLabel(notice.event),
            groups = withContext(Dispatchers.Default) { groupCheckedFiles(paths) },
            fileCount = paths.size
        )
    }

    /**
     * The user accepted the offer. Deletes exactly the files it listed and nothing it did not.
     */
    fun confirmPostWebhookDeletion() = launchDb {
        val paths = pendingSentDeletion
        val rootUri = pendingSentDeletionRoot
        if (paths.isEmpty() || rootUri == null) return@launchDb
        pendingSentDeletion = emptyList()
        pendingSentDeletionRoot = null
        _postWebhookDeletion.value = PostWebhookDeletion.Deleting

        val outcome = runFileDeletion(rootUri, paths)
        _postWebhookDeletion.value = if (outcome.recordFailed) {
            PostWebhookDeletion.Error(DeletionNotRecordedMessage)
        } else {
            PostWebhookDeletion.Done(deleted = outcome.deleted, failed = outcome.failed)
        }
    }

    /**
     * The user declined it, or acknowledged the outcome. Declining costs nothing: the files stay
     * where they are and Settings can still delete every checked file under the root later.
     */
    fun dismissPostWebhookDeletion() {
        pendingSentDeletion = emptyList()
        pendingSentDeletionRoot = null
        _postWebhookDeletion.value = null
    }

    /**
     * Checks or unchecks one item, wherever the user is looking at it from.
     *
     * The write is addressed by the item's Task, not by where the item was reached from: a file's
     * decision goes to the file's Task and reaches every List holding it, a manual entry's to its
     * own Task. Either way the write takes the ordering lock and the deliveries it may trigger run
     * outside it.
     */
    fun setItemCompleted(item: ReviewItem, completed: Boolean) = launchDb {
        // The transition and which round each completing List is in are read under one lock. The
        // exit that closes a round is a separate coroutine racing this one, and only the lock
        // guarantees it cannot close it between the write and the decision.
        val (transitioned, scopes) = writeOrder.withLock {
            val ids = dao.setTaskCompleted(item.taskId, completed, now())
            ids to ids.associateWith { scopeOf(it) }
        }
        transitioned.forEach { onCompletionTransition(it, true, scopes.getValue(it)) }
    }

    /**
     * A judgment committed on a Task Review surface, which is the only thing that records one.
     *
     * The other half of §5's fork. [setItemCompleted] moves the same state and records nothing,
     * because a checkbox on a List's own page is an edit rather than a verdict; this is reached
     * only from the review surface, and only when the person *checking* the Task is doing so as a
     * review. Unchecking goes back through [setItemCompleted] even on that surface: retracting a
     * decision is not committing a judgment, and there is no retraction for a template to record.
     *
     * The response is built from the item's pairs as the surface was showing them, which is what
     * makes the stored snapshot the answer to "what did they commit" rather than to "what the row
     * says now". It is the **fold** of them and not the page's, because a submission is one
     * judgment about one Task: a Task presenting three files commits what all three carry, which is
     * also exactly what the payload built beside it reports.
     *
     * Everything after the write is [setItemCompleted]'s, unchanged and shared: the ordering lock,
     * the round bookkeeping and the completion transition are the same whether the completion came
     * from a judgment or an edit, so a List completes and fires its webhook identically either way.
     */
    fun submitItemReview(item: ReviewItem, terms: ReviewTerms) = launchDb {
        val decisions = item.taskDecisions
        val response = ReviewResponse.MediaReview(
            completed = true,
            selections = decisions.selections
        )
        val (transitioned, scopes) = writeOrder.withLock {
            val ids = dao.submitReview(item.taskId, response, terms.template, terms.context, now())
            ids to ids.associateWith { scopeOf(it) }
        }
        transitioned.forEach { onCompletionTransition(it, true, scopes.getValue(it)) }
    }

    /**
     * Toggles one review action on one of a Task's files. Persisted immediately and nothing else:
     * completion is untouched, so this never delivers a webhook. The value simply travels with the
     * item into the next list.completed payload.
     *
     * Addressed by the **pair**, which is the whole of v0.5.1 at this boundary. The surface passes
     * the page it is showing, and the write lands on that file's record alone — a tag ticked on one
     * photograph of `Camera` is not ticked on the next.
     */
    fun setItemAction(
        item: ReviewItem,
        asset: ReviewAsset,
        action: ItemAction,
        enabled: Boolean
    ) = launchDb {
        writeOrder.withLock {
            dao.setAssetAction(item.taskId, asset.assetId, action, enabled, now())
        }
    }

    /**
     * Removes an item from a list. Membership only: the file's decisions are not a member of
     * anything and are left exactly as they are, so putting the file back later — here or in
     * another list — brings them back with it.
     */
    fun deleteItem(listId: Long, itemId: Long) = launchDb {
        onCompletionTransition(listId, dao.removeItem(listId, itemId, now()))
    }

    /**
     * Step 1 of creating a folder-backed list: scan, then work out overlapping scopes.
     * Writes nothing. Commits immediately only when there is nothing to replace.
     */
    fun requestListForFolder(
        rootUri: String,
        relativePath: String,
        folderUri: Uri,
        folderName: String
    ) = launchDb {
        _folderListRequest.value = FolderListRequest.Scanning
        pendingFolderList = null

        val existing = dao.getFolderLists(rootUri)

        val files = withContext(ioDispatcher) { folders.scanFiles(folderUri) }
        if (files == null) {
            _folderListRequest.value =
                FolderListRequest.Error("Could not read that folder. Nothing was changed.")
            return@launchDb
        }

        // Everything this folder's scope would collide with: its own existing list, an ancestor
        // that already covers it, or lists sitting in folders below it. One overlap rule for all
        // three, so there is a single Create List action rather than three near-identical ones.
        val conflicts = existing.filter { other ->
            val otherPath = other.sourceRelativePath ?: return@filter false
            scopesOverlap(relativePath, otherPath)
        }
        val replacedTitles = conflicts.map { it.title }
        val replacesExact = conflicts.any { it.sourceRelativePath == relativePath }
        pendingFolderList = PendingFolderList(
            title = folderName,
            rootUri = rootUri,
            relativePath = relativePath,
            files = files,
            replacedIds = conflicts.map { it.id },
            replacedTitles = replacedTitles,
            replacesExact = replacesExact
        )

        // folderListStep is the whole of this decision, held apart and tested so a List is never
        // deleted by a dialog that never named it (N1): import consent and replace confirmation
        // are two different acceptances, asked one at a time, never folded into each other.
        val isImported = dao.sourcesCovering(rootUri, relativePath).isNotEmpty()
        when (folderListStep(isImported, hasConflicts = conflicts.isNotEmpty())) {
            FolderListStep.NEEDS_IMPORT_CONSENT -> _folderListRequest.value = FolderListRequest.ImportConsent(
                folderName = folderName,
                fileCount = files.size,
                sourceRoot = relativePath.ifEmpty { "the selected folder" },
                importer = "file-as-Task"
            )
            FolderListStep.NEEDS_REPLACE_CONFIRM -> _folderListRequest.value = FolderListRequest.Confirm(
                folderName = folderName,
                fileCount = files.size,
                replacedTitles = replacedTitles,
                replacesExact = replacesExact
            )
            FolderListStep.COMMIT -> commitPendingFolderList()
        }
    }

    /** Step 2 (or step 3, after an import consent): the user accepted replacing the overlapping lists. */
    fun confirmListForFolder() = launchDb { commitPendingFolderList() }

    /**
     * Step 2 for the import consent: accepted, so the import proceeds. If the same folder also
     * overlaps existing lists, that is still a separate, unaccepted write - the replace confirmation
     * is shown next, naming them, exactly as it would have been shown had the folder already been
     * imported. Only a folder with nothing to replace commits directly from here.
     */
    fun confirmImportForFolder() = launchDb {
        val pending = pendingFolderList ?: return@launchDb
        when (folderListStep(isImported = true, hasConflicts = pending.replacedIds.isNotEmpty())) {
            FolderListStep.NEEDS_REPLACE_CONFIRM -> _folderListRequest.value = FolderListRequest.Confirm(
                folderName = pending.title,
                fileCount = pending.files.size,
                replacedTitles = pending.replacedTitles,
                replacesExact = pending.replacesExact
            )
            FolderListStep.COMMIT -> commitPendingFolderList()
            FolderListStep.NEEDS_IMPORT_CONSENT -> Unit // unreachable: isImported is forced true here
        }
    }

    /**
     * The write a Quick Review consent accepted: register a file-as-Task Source over the folder and
     * run the import. Once it has run, entering Quick Review is immediate again.
     */
    fun importFolderForReview(rootUri: String, folderPath: String) = launchDb {
        val tree = withContext(ioDispatcher) { folders.scanTree(rootUri, folderPath) }
        val source = dao.registerSource(
            rootUri = rootUri,
            relativePath = folderPath,
            displayName = folderPath.ifEmpty { "Root" },
            importerKind = ImporterKind.FILE_AS_TASK,
            now = now()
        )
        if (tree != null) {
            dao.runImport(source.id, folderPath, plan(ImporterKind.FILE_AS_TASK, folderPath, tree), now())
        }
        // Signal the write, not the tap: published after the transaction has committed, so a
        // collector that re-reads on it sees Tasks that exist.
        _importVersion.value += 1
    }

    /**
     * Step 1 of Create Tasks: read the picked folder and say what a run would cover. Writes nothing,
     * and the walk happens once — the tree is held so the import itself does not re-read a folder
     * the user has just been shown a count of.
     */
    fun requestCreateTasks(rootUri: String, folderPath: String) = launchDb {
        pendingImport = null
        _importFolderRequest.value = ImportFolderRequest.Reading

        val tree = scanFolderTreeAt(rootUri, folderPath)
        if (tree == null) {
            _importFolderRequest.value =
                ImportFolderRequest.Error("Could not read this folder. Nothing was changed.")
            return@launchDb
        }

        val label = folderLabelFor(rootUri, folderPath)
        // Read before the options are built rather than after, because the options are seeded from
        // it: what a new Todo starts on is the built-in *as it stands now*, and that is a row.
        val attachable = attachableTemplates()
        val options = ImportOptions(
            importer = ImporterKind.FILE_AS_TASK,
            displayName = label,
            // Seeded from Settings rather than from any Todo, because there is no Todo yet: this is
            // the answer the user usually gives, offered here so they do not have to give it again.
            contentPolicy = settings.value.defaultContentPolicy,
            templates = listOf(seededAttachment(attachable)),
            // The standing answer a new Todo is born with, offered as the switch it is rather than
            // applied silently after registration. A new Todo keeps its decisions on delete, the
            // behaviour every Todo had before that switch existed.
            followUpstream = settings.value.followUpstreamNewTodos,
            keepStateOnDelete = false,
            // The address starts empty and the radio starts where the preselect preference says.
            // *Use default* resolves at Confirm, not here, so the Todo gets a copy of what the
            // default is at the moment the user says yes.
            webhookUrl = "",
            webhookFromDefault = settings.value.defaultWebhookEnabled
        )
        pendingImport = PendingImport(rootUri, folderPath, tree, options)
        _importFolderRequest.value = ImportFolderRequest.Confirm(
            folderLabel = label,
            fileCount = tree.files.size,
            folderCount = tree.directories.size,
            refresh = false,
            options = options,
            // Named after the label is decided, so a folder and the Todo over it read as the same
            // words in the same dialog rather than as a path and a name.
            existingTodos = dao.sourcesAt(rootUri, folderPath).map { it.displayName },
            histories = dao.dissolvedHistoriesAt(rootUri, folderPath),
            templates = attachable
        )
    }

    /**
     * Step 1 of a Todo's Refresh Import: walk the Source's own root again and report what a run
     * would cover. The configuration is the Source's, not the caller's — re-importing a
     * folder-as-Task Source as file-as-Task would not be a refresh, it would be a different Source
     * wearing the same row — so the dialog opens on the stored values and offers no way to change
     * them.
     */
    fun requestSourceRefresh(sourceId: String) = launchDb {
        pendingImport = null
        _importFolderRequest.value = ImportFolderRequest.Reading

        val source = dao.getSource(sourceId)
        val importer = source?.let { ImporterKind.fromKey(it.importerKind) }
        if (source == null || importer == null) {
            _importFolderRequest.value =
                ImportFolderRequest.Error("This Source is no longer registered. Nothing was changed.")
            return@launchDb
        }

        val tree = scanFolderTreeAt(source.rootUri, source.relativePath)
        if (tree == null) {
            _importFolderRequest.value =
                ImportFolderRequest.Error("Could not read this Source's folder. Nothing was changed.")
            return@launchDb
        }

        val options = ImportOptions(
            importer = importer,
            displayName = source.displayName,
            keepTree = source.keepTree,
            // This Todo's own answer, not Settings': a refresh is about a Todo that exists and has
            // already said what a change to its material means.
            contentPolicy = ContentPolicy.fromKey(source.contentPolicy),
            // The same read, and it is the Todo's own answer for the same reason. A refresh opens
            // on the templates the Todo is attached to, so the attach step shows what is already
            // true and a user changes it deliberately rather than re-choosing from scratch.
            //
            // Decoded through the tolerant reader, so a Todo imported before this release opens on
            // the built-in it was configured with rather than on nothing — see
            // [decodeTemplateAttachments] for the two shapes it accepts.
            templates = decodeTemplateAttachments(source.assetControls),
            // A refresh is the run that carries a Todo forward: what it holds stays ticked, and
            // confirming moves each one to the version the picker offered. This is what makes an
            // untouched row adopt an edited template instead of re-stamping the old version — see
            // [upgradeAttachments] and the notice the dialog draws from [refreshVersionNotice].
            upgradeTemplates = true,
            // The Todo's standing answers, offered here so a refresh can change them without a
            // second trip to the Todo's card. The address opens on what the row holds and on
            // whether that value was a default copy; the dialog preselected *Keep this Todo's
            // address* so an untouched refresh writes the same value back.
            followUpstream = source.followUpstream,
            keepStateOnDelete = source.keepStateOnDelete,
            webhookUrl = source.webhookUrl,
            webhookFromDefault = source.webhookFromDefault,
            // The same rule a third time: how this Todo exports is the Todo's own answer, and a
            // refresh offers it unchanged rather than re-deciding it. `inheritable = false`
            // because this is a Todo's column — `inherit` on one is *nothing*, which is the
            // answer that fails closed.
            exportChoice = decodeExportChoice(source.exportKind, inheritable = false)
        )
        pendingImport = PendingImport(source.rootUri, source.relativePath, tree, options, source.id)
        _importFolderRequest.value = ImportFolderRequest.Confirm(
            folderLabel = source.displayName.ifEmpty { sourceNameFor(source.relativePath) },
            fileCount = tree.files.size,
            folderCount = tree.directories.size,
            refresh = true,
            options = options,
            templates = attachableTemplates()
            // Deliberately no histories: a refresh runs over a Todo that is still here, and a
            // recovery overwrites a Task's judgment. Loading a history into a live Todo would take
            // the decisions made since and quietly replace them with older ones. Recovery is for a
            // Todo being born, which is why only Create Tasks offers it.
        )
    }

    /**
     * Step 2: accepted, so the run happens under the chosen configuration.
     *
     * The importer choice exists because it is the whole point of this release: the same folder
     * read as one Task per file and as one Task per child folder are two different judgements about
     * the same material, and only the person looking at it can say which one they mean. It is not
     * offered for a refresh, where the Source has already answered.
     */
    fun confirmImportFolder(
        options: ImportOptions,
        recoverFrom: DissolvedHistory? = null
    ) = launchDb {
        val pending = pendingImport ?: return@launchDb
        applyImport(pending, options, recoverFrom)
    }

    fun dismissImportFolderRequest() {
        pendingImport = null
        _importFolderRequest.value = null
    }

    /**
     * Registers a picked folder as an Upstream, before any Tasks exist under it.
     *
     * Adding an Upstream and creating Tasks are two steps on purpose. Picking a folder answers
     * "where does material come from", which is a fact the user can state without further
     * decisions; how its contents group into Tasks is a second question, asked by the Create Tasks
     * dialog, and answering it beforehand would mean a folder could never be looked at before
     * something was committed about it.
     *
     * The importer and the tree are therefore placeholders — file-as-Task, tree kept — and Create
     * Tasks overwrites both with what the user chooses. A folder that is already registered under
     * this grant is returned rather than registered twice, so re-picking the same folder adds no
     * second row.
     */
    fun registerUpstream(rootUri: String, relativePath: String) = launchDb {
        val upstream = dao.registerUpstream(
            rootUri = rootUri,
            relativePath = relativePath,
            displayName = folderNameFor(rootUri, relativePath),
            now = now()
        )
        _newUpstream.value = upstream
    }

    /**
     * The Upstream just added, so the screen can open it.
     *
     * An event rather than a piece of screen state: the picking happens outside Compose, in the
     * folder picker's own activity, so there is no callback in scope to navigate from and nothing
     * for the screen to hang the answer on. The screen that acts on it clears it, and the flow is
     * only ever set by a pick that actually committed a folder.
     */
    private val _newUpstream = MutableStateFlow<UpstreamEntity?>(null)
    val newUpstream: StateFlow<UpstreamEntity?> = _newUpstream.asStateFlow()

    fun consumeNewUpstream() {
        _newUpstream.value = null
    }

    /**
     * The one writer both Create Tasks and Refresh Import go through, so there is one run, one
     * report and one place the registration rule is applied.
     *
     * Which Todo the run lands in is [PendingImport.sourceId], and it is the whole of the difference
     * between the two: a refresh continues the Todo whose card was tapped, and Create Tasks mints a
     * new one. There is deliberately no fallback from the first to the second. A folder may back any
     * number of Todos, so "the Source over this folder" is not a question with an answer, and a
     * refresh that resolved it by covering would write a run into an intention the user did not
     * name. A refresh whose Todo has since gone says so instead.
     *
     * [recoverFrom] is at most one dissolved history to load judgments from, passed whole rather
     * than by id so the report can name it: a recovery that matched nothing has to say *which*
     * history matched nothing, and at a folder with two of them the id alone would not. It is
     * decided here rather than stored on the Source, because recovering is something a user does
     * *to this run* and not a property of the Todo it creates: a Todo that recovered once is not
     * thereby a Todo that always recovers, and the next import at this folder is a new question
     * with a new answer.
     *
     * Registration is not refused for anything any more. A second run over a folder is a second
     * intention — the same market supplying another dish — and the Tasks it plans are its own, so
     * there is no overlap left to reject. See [ListsDao.registerSource].
     *
     * The configuration is recorded after the run, and only once the run has happened: a run that
     * failed changed nothing, and a name saying otherwise would be the one thing it did.
     *
     * Two of those values are not only records, and both are passed into the run rather than left to
     * the write below: `contentPolicy` and `assetControls` are instructions *for this run*. The name
     * and the grouping describe what the Todo is, so a failed run must not leave them changed; the
     * policy describes what this run should do, and the control describes what the Tasks it makes
     * should offer, so reading either back from a row the write has not reached yet would make the
     * dialog's answer apply one run late — the user picks *Replace the Task*, presses Refresh, and
     * watches a `keep` run report nothing replaced.
     *
     * The two are passed as the same value the write-back below records, which is what keeps the
     * Todo and its Tasks agreeing about a run neither of them can see: the Todo's copy is what the
     * next run will get, each Task's is what this one got, and both come from here.
     */
    private suspend fun applyImport(
        pending: PendingImport,
        options: ImportOptions,
        recoverFrom: DissolvedHistory? = null
    ) {
        _importFolderRequest.value = ImportFolderRequest.Importing
        pendingImport = null

        val importer = options.importer
        // A blank name is not a name. Falling back here rather than in the dialog keeps the rule in
        // one place, and the fallback is the same one the folder would have been offered under, so
        // a user who clears the field gets what they were shown.
        val name = options.displayName.trim().ifEmpty { sourceNameFor(pending.folderPath) }

        // A refresh adopts the offered version of every template it still holds — see
        // [upgradeAttachments] — while Create and an upstream sync submit exactly what they were
        // given. Resolved before the encode so the Todo's row, the run and the write-back all get
        // one answer.
        val attachments = if (options.upgradeTemplates) {
            upgradeAttachments(options.templates, attachableTemplates())
        } else {
            options.templates
        }

        // Encoded once, here, and the same string goes to all three writes below — the Todo's row,
        // the run, and the Todo's row again. The dialog collected *references*; what every column
        // holds is the one canonical spelling of them, so no two of those writes can disagree about
        // how a list of attachments is written down.
        val assetControls = encodeTemplateAttachments(attachments)

        // The address is resolved by the dialog (*Use default* copies the live default at Confirm;
        // *Custom* is what was typed) and arrives here final. A Todo whose method is Webhook must
        // have a usable one **before anything is written**: *Use default* over an empty default is
        // a tap away, and letting the run register first and fail later would leave a Todo whose
        // every export fails. A refresh and a create are the runs that made this decision, so they
        // are the ones that may refuse on it; a sync asked nobody and must still bring in material.
        val webhookUrl = options.webhookUrl.trim()
        val addressProblem = if (options.validateWebhookAddress &&
            options.exportChoice == ExportChoice.By(ExportKind.WEBHOOK)
        ) {
            webhookAddressProblem(ExportKind.WEBHOOK, webhookUrl)
        } else {
            null
        }
        if (addressProblem != null) {
            _importFolderRequest.value =
                ImportFolderRequest.Error("$addressProblem. Nothing was changed.")
            return
        }

        val source = if (pending.sourceId == null) {
            dao.registerSource(
                rootUri = pending.rootUri,
                relativePath = pending.folderPath,
                displayName = name,
                importerKind = importer,
                keepTree = options.keepTree,
                contentPolicy = options.contentPolicy,
                assetControls = assetControls,
                exportKind = encodeExportChoice(options.exportChoice),
                followUpstream = options.followUpstream,
                keepStateOnDelete = options.keepStateOnDelete,
                webhookUrl = webhookUrl,
                webhookFromDefault = options.webhookFromDefault,
                now = now()
            )
        } else {
            val refreshed = dao.getSource(pending.sourceId)
            if (refreshed == null) {
                _importFolderRequest.value = ImportFolderRequest.Error(
                    "This Todo is no longer registered. Nothing was changed."
                )
                return
            }
            refreshed
        }

        val result = runCatching {
            dao.runImport(
                source.id,
                pending.folderPath,
                plan(importer, pending.folderPath, pending.tree),
                now(),
                recoverFrom = recoverFrom?.sourceId,
                // The dialog's answer, not the row's: the write-back below records it, and a run
                // that waited for that write would apply the tier the user just replaced.
                contentPolicy = options.contentPolicy,
                // Same rule, same reason: the Tasks this run produces are stamped from this string,
                // so it has to be what the user just chose rather than what the Todo holds.
                assetControls = assetControls
            )
        }.getOrNull()

        if (result == null) {
            _importFolderRequest.value =
                ImportFolderRequest.Error("The import could not be written. Nothing was changed.")
            return
        }

        dao.configureSource(
            id = source.id,
            displayName = name,
            importerKind = importer,
            keepTree = options.keepTree,
            contentPolicy = options.contentPolicy,
            assetControls = assetControls,
            // The dialog's answer, written back here rather than in `runImport` above, for the
            // same reason `contentPolicy` is: the run is about this folder's Tasks, and the
            // choice is a standing answer about the Todo that outlives it.
            exportKind = encodeExportChoice(options.exportChoice),
            // The standing answers the dialog now carries. A create has just written them in
            // `registerSource`; writing them again here keeps one statement of the Todo's
            // configuration rather than two paths that could disagree. A sync passes back exactly
            // what it read, so *no dialog* means *no change*, never *reset to default*.
            followUpstream = options.followUpstream,
            keepStateOnDelete = options.keepStateOnDelete,
            webhookUrl = webhookUrl,
            webhookFromDefault = options.webhookFromDefault
        )

        _importVersion.value += 1
        _importFolderRequest.value = ImportFolderRequest.Report(
            result = result,
            importer = importer,
            recoveredFrom = recoverFrom?.sourceName
        )
    }

    /**
     * A Todo that follows its upstream, kept in step — the one write in the app that happens
     * without being asked at the moment it happens.
     *
     * Asked on *entry* to the Todo and nowhere else, because that is the moment the user has said
     * they are looking at this material: a background schedule would act on Todos nobody has opened
     * and a switch thrown weeks ago would be the only record of why.
     *
     * Three gates before anything is written, in this order. The Todo must say it follows its
     * upstream — the flag is the whole permission. The folder must scan. And the scan must find
     * something, which is [ListsDao.sourceHasChanges]: a walk of an unchanged folder costs a read
     * and produces nothing, and that is the ordinary case, so it is the one that has to be cheap.
     *
     * When it does find something the run is [applyImport] — the same single writer Create Tasks
     * and Refresh Import share — so there is still one run, one transaction and one report. The
     * report is shown, and it is deliberate: a write nobody watched has to say what it did, and the
     * user is on the screen it is about.
     */
    fun syncTodoUpstream(sourceId: String) = launchDb {
        val source = dao.getSource(sourceId) ?: return@launchDb
        if (!source.followUpstream) return@launchDb

        val importer = ImporterKind.fromKey(source.importerKind) ?: return@launchDb
        val tree = scanFolderTreeAt(source.rootUri, source.relativePath) ?: return@launchDb
        if (!dao.sourceHasChanges(sourceId, source.relativePath, plan(importer, source.relativePath, tree))) {
            return@launchDb
        }

        // The Todo's own stored answers, not Settings': this is a run over a Todo that exists, and
        // the dialog that would have asked is not being shown. These are the same four values
        // [requestSourceRefresh] puts in front of the user, taken without the dialog in between —
        // which is what makes the dialog and this run one configuration rather than two.
        val options = ImportOptions(
            importer = importer,
            displayName = source.displayName,
            keepTree = source.keepTree,
            contentPolicy = ContentPolicy.fromKey(source.contentPolicy),
            // The same decode [requestSourceRefresh] does, because this is that dialog's answer
            // taken without the dialog: a run nobody was asked about re-attaches exactly what the
            // Todo already names, so following an upstream changes the material and never the
            // buttons.
            templates = decodeTemplateAttachments(source.assetControls),
            // The same rule for every standing answer the dialog now carries. A sync has no dialog
            // and asked nobody, so it writes back exactly what it read — including the address,
            // which it also may not refuse over: see [ImportOptions.validateWebhookAddress].
            followUpstream = source.followUpstream,
            keepStateOnDelete = source.keepStateOnDelete,
            webhookUrl = source.webhookUrl,
            webhookFromDefault = source.webhookFromDefault,
            validateWebhookAddress = false,
            // And the same rule for the choice, which matters more here than anywhere else: a
            // sync has no dialog, so a Todo that did not carry its own answer through this call
            // would be silently reset to *export nothing* by the act of following its upstream.
            exportChoice = decodeExportChoice(source.exportKind, inheritable = false)
        )
        applyImport(
            pending = PendingImport(
                rootUri = source.rootUri,
                folderPath = source.relativePath,
                tree = tree,
                options = options,
                sourceId = source.id
            ),
            options = options
        )
    }

    /**
     * What a Source is called when nobody has named it: the folder's own name, or the picked folder
     * itself when the Source *is* the whole of what was picked.
     *
     * Not the relative path: a path is how the app finds the folder, and a Todo list titled
     * `2026-09-11/bilibili` is the app talking to itself. The dialog opens with this in the name
     * field, so the user sees a name rather than an empty box and can still replace it.
     */
    private fun sourceNameFor(folderPath: String): String =
        folderPath.substringAfterLast('/').ifEmpty { "Picked folder" }

    /**
     * The templates a dialog may offer to attach, read once.
     *
     * Goes through [templateStore] rather than through [templateResolver], and the difference is the
     * one that matters here: the resolver is an observed value a screen recomposes over, while this
     * answers a question asked *inside* a coroutine that is building a dialog. A flow read from
     * there would either suspend on a first emission that has not happened or answer with the null
     * seed, and either way the picker would be built from something other than the tables.
     *
     * Built by [TemplateResolver.of] anyway, so *which version of a template is current* has one
     * definition — the resolver's — rather than a second one here that could come to differ from
     * what a Task attached to that version would resolve to.
     */
    private suspend fun attachableTemplates(): List<StoredTemplateVersion> {
        val (lineages, versions) = templateStore.snapshot()
        return TemplateResolver.of(lineages, versions).attachable
    }

    /**
     * What a Todo with nothing to go on is attached to: the built-in, at the version it is *now*.
     *
     * **Not the registry's reference, which is version 1 and always will be.** Every Todo this app
     * has ever made offered the built-in's buttons, so a new one opening on them is the
     * behaviour-preserving default — but *the built-in's buttons* is a question about a row, and the
     * row moves. Seeding the constant meant a user who added a button to the built-in got it nowhere:
     * a new Todo opened attached to version 1, the picker offered version 2 under the same name, and
     * because it matched on the whole reference it drew that row *unticked* — so ticking it added a
     * second reference for one template instead of moving the first, and first-match resolution
     * handed every file the older one. The new button was unreachable, silently.
     *
     * Fallible rather than guaranteed, hence the fallback: a lineage is offered only while its current
     * version's buttons decode, so a built-in that has been edited into something unreadable drops
     * out of the list — and then the registry's own reference is the better answer than nothing,
     * because it is at least the buttons the app has always had, and the attach step draws no row for
     * it either way. Unreachable today by any path but a hand-edited database.
     */
    private fun seededAttachment(attachable: List<StoredTemplateVersion>): TemplateVersionRef =
        attachable
            .firstOrNull { it.ref.templateId == Templates.AssetControlSeed.ref.templateId }
            ?.ref
            ?: Templates.AssetControlSeed.ref

    /**
     * The resolver for the two tables as they stand, read once, for a coroutine rather than a screen.
     *
     * [templateResolver] is the same answer and is deliberately not what this reads. That flow is
     * `WhileSubscribed`, so it holds nothing at all while no screen is watching — and a webhook
     * fires from a background round, which is exactly a moment when nobody is. Reading `.value`
     * there would answer [TemplateResolver.Empty] often enough to matter, and the payload's
     * `actions` array would then name stored ids instead of wire values depending on whether the
     * user happened to have the app open. A delivery's body must not turn on that.
     */
    private suspend fun resolverNow(): TemplateResolver {
        val (lineages, versions) = templateStore.snapshot()
        return TemplateResolver.of(lineages, versions)
    }

    /**
     * What a folder is called: its own name, as the device gives it, or else the path's last
     * segment.
     *
     * [sourceNameFor] can only name a folder by its path, and a picked folder's own path is empty —
     * the grant root *is* the folder — so every Upstream added by picking would be called "Picked
     * folder". The provider knows the real name, and asking it is what lets the Upstreams list and
     * the Create Tasks dialog say "2026-09-11" rather than making the user type it in to replace a
     * placeholder.
     */
    private suspend fun folderNameFor(rootUri: String, folderPath: String): String =
        withContext(ioDispatcher) { folders.folderName(rootUri, folderPath) }
            ?: sourceNameFor(folderPath)

    /**
     * The name a Create Tasks run is offered under: the Upstream it is running over, when it is that
     * Upstream's own folder, else the folder's own name.
     *
     * Running over an Upstream's root is the case the Upstreams screen leads to, and the Upstream has
     * already been named — by the user when it was added, or by [folderNameFor] when it was picked.
     * Offering a different name there would let one folder carry two names, one on the card and one
     * on the Todo the run creates, with nothing to say which was meant. A run started deeper, from a
     * folder inside an Upstream, is about that folder and is named for itself.
     */
    private suspend fun folderLabelFor(rootUri: String, folderPath: String): String =
        dao.sourcesAt(rootUri, folderPath)
            .firstOrNull()
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: folderNameFor(rootUri, folderPath)

    /** Walks a root-relative folder from a SAF grant, or null when it cannot be resolved or read. */
    private suspend fun scanFolderTreeAt(rootUri: String, folderPath: String): FolderSnapshot? =
        withContext(ioDispatcher) { folders.scanTree(rootUri, folderPath) }

    /**
     * One Source's whole catalog, re-read on every collection.
     *
     * Cold on purpose: the catalog is a projection of `task_imports` that changes only when an
     * import runs, and a screen that re-collects on [importVersion] is then looking at what the last
     * run actually wrote rather than at what a Room `Flow` had cached. A Task selection made from a
     * stale read would create a List of Tasks that are no longer what the user was shown.
     */
    fun observeCatalog(sourceId: String): Flow<List<CatalogRow>> = flow {
        emit(dao.getCatalogTasks(sourceId))
    }

    /**
     * Creates a List from Tasks picked in a Source's catalog.
     *
     * Membership, not judgment: it resolves no files, scans nothing and imports nothing (§8.1), and
     * eligibility deliberately does not gate it — a List has always been able to hold a Task that
     * cannot be review-submitted, and refusing here would break that to enforce a rule about review.
     *
     * Fails whole rather than partially. A selection that names a Task the catalog can no longer
     * reach throws inside the DAO's transaction, so the List is either exactly the selection or does
     * not exist; there is no half-list to explain.
     */
    fun createListFromCatalog(title: String, taskIds: List<String>, onCreated: (Long) -> Unit) =
        launchDb {
            if (taskIds.isEmpty()) return@launchDb
            val created = runCatching {
                dao.createListFromTasks(
                    title = title.trim().ifEmpty { "New list" },
                    taskIds = taskIds,
                    // A new List starts with completion export off. Since v0.6.2 the switch is a
                    // per-List decision, and no List should begin posting because it happened to
                    // be created — least of all one made right after an install that had delivery
                    // switched off.
                    exportOnCompletion = false,
                    now = now()
                )
            }.getOrNull()

            if (created == null) {
                _listCreationError.value =
                    "These Tasks could not all be found any more. No list was created."
                return@launchDb
            }
            onCreated(created)
        }

    /**
     * Puts a selection of Tasks into a List that already exists.
     *
     * The gesture the shared-Task model is for: Tasks gathered from one Todo, or from two, landing
     * in a List that may already hold Tasks of its own. What it adds is memberships and nothing
     * else — no Task is created, no decision is touched, and a Task the List already shows is
     * skipped rather than doubled.
     *
     * [onAdded] receives how many memberships were actually written, so a caller can say the Tasks
     * were already there instead of claiming to have added them. Zero is a success and not a
     * failure: nothing was wrong with the selection, it was simply already in the list.
     */
    fun addTasksToCatalogList(listId: Long, taskIds: List<String>, onAdded: (Int) -> Unit) =
        launchDb {
            if (taskIds.isEmpty()) return@launchDb
            val added = runCatching { dao.addTasksToList(listId, taskIds, now()) }.getOrNull()

            if (added == null) {
                _listCreationError.value =
                    "These Tasks could not all be found any more. Nothing was added."
                return@launchDb
            }
            onAdded(added)
        }

    fun dismissListCreationError() {
        _listCreationError.value = null
    }

    fun dismissFolderListRequest() {
        pendingFolderList = null
        _folderListRequest.value = null
    }

    private suspend fun commitPendingFolderList() {
        val pending = pendingFolderList ?: return
        val created = runCatching {
            dao.createFolderList(
                title = pending.title,
                rootUri = pending.rootUri,
                relativePath = pending.relativePath,
                files = pending.files,
                replacedListIds = pending.replacedIds,
                // Same template as a manual list gets, including when this replaces an
                // overlapping list: there is one default, not one per kind of list — and it is
                // off. A folder List is no more entitled to start posting than a picked one.
                exportOnCompletion = false,
                now = now()
            )
        }
        pendingFolderList = null
        // The one failure worth naming. A folder already inside a Source is a normal state to be
        // in, not a fault, and the generic sentence below tells the user nothing about it: what
        // they need to hear is which Source holds these files and where the Tasks they wanted can
        // be picked instead. Every other failure keeps the sentence that says only that nothing
        // happened, because nothing else about it is something the user can act on.
        _folderListRequest.value = when (val failure = created.exceptionOrNull()) {
            null -> null
            is FolderListImportConflict -> FolderListRequest.Error(
                "This folder is already inside the Source " +
                    coverLabel(failure.sourceRelativePath) +
                    ". Pick its Tasks from that Source's Catalog instead. Nothing was changed."
            )
            else -> FolderListRequest.Error("Could not create the list. Nothing was changed.")
        }
    }

    /**
     * The automatic check, run when a list's detail page becomes the active screen. It resolves
     * the folder, scans it, and compares through the same [ListsDao.previewReconcile] the manual
     * update uses. Nothing about membership or review is written: nothing is inserted, flagged,
     * restored, deleted or completed, and no webhook can result.
     *
     * The one thing it does keep from the scan is what each file measured — see
     * [ListsDao.refreshSourceMetrics]. That is not a decision about the list, it is the answer to
     * "how big is this and when was it written", and caching it here is what lets the Items header
     * sort and filter on it without a provider round trip per row. It is also the backfill: a list
     * created before those columns existed measures itself the first time its page is opened.
     *
     * Manual lists have no source to compare against, so they are dropped before any scan starts.
     * A previous check is cancelled rather than left to race: whichever check started last wins.
     *
     * Skipped entirely when the user has switched the automatic check off. The setting is read
     * from the store rather than from the observed [settings] flow, because this runs the instant
     * a page opens and a flow that has not warmed up yet would answer with the default and scan
     * something the user asked it not to. A verdict already held for *this* list survives the
     * skip: it came from a scan that really happened, and discarding it would throw away an
     * update the user had just asked for.
     */
    fun checkSourceFreshness(listId: Long) {
        freshnessJob?.cancel()
        freshnessJob = viewModelScope.launch {
            val list = dao.getList(listId)
            if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
                _sourceFreshness.value = null
                return@launch
            }

            if (!settingsStore.read().autoCheckSourceFreshness) {
                if (_sourceFreshness.value?.listId != listId) {
                    _sourceFreshness.value = ListFreshness(listId, SourceFreshness.NotChecked)
                }
                return@launch
            }

            _sourceFreshness.value = ListFreshness(listId, SourceFreshness.Checking)
            val files = scanLinkedFolder(list)
            val state = if (files == null) {
                SourceFreshness.SourceUnavailable
            } else {
                dao.refreshSourceMetrics(listId, files)
                freshnessOf(dao.previewReconcile(listId, files))
            }
            _sourceFreshness.value = ListFreshness(listId, state)
        }
    }

    /**
     * Step 1 of a manual update: rescan the linked folder and compare. Writes nothing.
     * Deliberately does not go anywhere near the webhook path. Its scan is authoritative, so it
     * publishes freshness too rather than leaving a stale badge next to a fresh dialog.
     *
     * Two cases a re-sync cannot meet on its own are settled here rather than thrown from inside
     * the write (§8.3):
     *
     * - **no Source covers the folder.** Appending a file needs its Task resolved, which means an
     *   import, which means registering a Source — a write, so it goes through the same consent the
     *   first Create List does, and declining costs nothing.
     * - **the folder is inside a folder-as-Task Source.** Appending a file as its own Task there
     *   would contradict that Source's grouping, so the update is refused by name and nothing is
     *   appended. This is the one re-sync that has no answer, and saying so up front is better than
     *   a generic failure from inside a transaction.
     */
    fun requestResync(listId: Long) = launchDb {
        _resyncRequest.value = ResyncRequest.Scanning
        pendingResync = null

        val list = dao.getList(listId)
        if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
            _resyncRequest.value = ResyncRequest.Error("This list is not linked to a folder.")
            return@launchDb
        }

        // A re-sync is a write, and with several Todos over one folder there is no fact of the
        // matter about which one an old folder-backed list came from. Declining and naming them is
        // better than picking the nearest and silently extending an intention nobody asked for;
        // the modern path into these Tasks is the Todo's own Refresh Import, which knows its id.
        val covering = dao.sourcesCovering(list.sourceRootUri, list.sourceRelativePath)
        if (covering.size > 1) {
            _resyncRequest.value = ResyncRequest.Error(
                "This list's folder is imported " + covering.size + " times — as " +
                    covering.joinToString(", ") { todoLabel(it) } +
                    ". Open the Todo you meant and refresh it from there. Nothing was changed."
            )
            return@launchDb
        }
        val owner = covering.firstOrNull()
        if (owner != null && owner.importerKind != ImporterKind.FILE_AS_TASK.key) {
            _resyncRequest.value = ResyncRequest.Error(
                "This list's folder is inside the folder-as-Task Source " +
                    coverLabel(owner) + ", which groups files into Tasks of its own. " +
                    "Nothing was changed."
            )
            return@launchDb
        }

        val files = scanLinkedFolder(list)
        if (files == null) {
            _resyncRequest.value =
                ResyncRequest.Error("Could not read the source folder. Nothing was changed.")
            publishFreshness(listId, SourceFreshness.SourceUnavailable)
            return@launchDb
        }

        // Whether or not there is anything to reconcile, the scan measured every file it found
        // and those numbers are worth keeping — an "up to date" verdict is still a fresh look.
        dao.refreshSourceMetrics(listId, files)

        val diff = dao.previewReconcile(listId, files)
        publishFreshness(listId, freshnessOf(diff))
        if (!diff.hasChanges) {
            _resyncRequest.value = ResyncRequest.UpToDate
            return@launchDb
        }

        pendingResync = PendingResync(listId, files)
        _resyncRequest.value = if (owner == null) {
            ResyncRequest.ImportConsent(
                diff = diff,
                sourceRoot = list.sourceRelativePath.ifEmpty { "the selected folder" },
                importer = "file-as-Task"
            )
        } else {
            ResyncRequest.Confirm(diff)
        }
    }

    /**
     * Resolve then scan the folder a list is linked to. Shared by the automatic check and the
     * manual update so there is one scanner, not two. Blocking work stays on IO. Null when the
     * grant is gone, the folder has been deleted, the path no longer resolves, or the scan fails
     * — in every case the list itself is left untouched.
     */
    private suspend fun scanLinkedFolder(list: ListEntity): List<ScannedFile>? {
        val rootUri = list.sourceRootUri ?: return null
        val relativePath = list.sourceRelativePath ?: return null
        return withContext(ioDispatcher) { folders.scanTree(rootUri, relativePath)?.files }
    }

    /** The one place a diff becomes a freshness verdict, so both entry points agree. */
    private fun freshnessOf(diff: FolderDiff): SourceFreshness =
        if (diff.hasChanges) SourceFreshness.ChangesAvailable(diff) else SourceFreshness.UpToDate

    /**
     * Publishes a verdict reached outside the automatic check. Cancels any check still running,
     * so an older scan cannot land on top of this newer, user-initiated result.
     */
    private fun publishFreshness(listId: Long, state: SourceFreshness) {
        freshnessJob?.cancel()
        _sourceFreshness.value = ListFreshness(listId, state)
    }

    /**
     * Step 2: apply the whole reconciliation in one transaction, or nothing at all.
     *
     * Registration is *not* part of this, and that is deliberate: the Source is resolved and
     * checked before the transaction opens, so a re-sync can only ever reuse a Source that already
     * exists. Registering one is [confirmResyncImport]'s job, behind a dialog that says so.
     */
    fun confirmResync() = launchDb {
        val pending = pendingResync ?: return@launchDb
        val source = coveringSourceOf(pending.listId)
        if (source == null) {
            pendingResync = null
            _resyncRequest.value = ResyncRequest.Error(
                "This list's folder is not imported any more. Nothing was changed."
            )
            return@launchDb
        }
        applyResync(pending, source)
    }

    /**
     * Step 2 for the import consent: accepted, so the Source is registered and the apply follows.
     *
     * The registration is its own write outside the reconcile transaction, which is what lets a
     * decline leave nothing behind and what keeps the two failures separable: a Source that could
     * not be registered has imported nothing, and the reconcile never ran.
     */
    fun confirmResyncImport() = launchDb {
        val pending = pendingResync ?: return@launchDb
        val list = dao.getList(pending.listId)
        val rootUri = list?.sourceRootUri
        val listPath = list?.sourceRelativePath
        if (rootUri == null || listPath == null) {
            pendingResync = null
            _resyncRequest.value = ResyncRequest.Error("This list is not linked to a folder.")
            return@launchDb
        }

        // Same refusal as [requestResync], for the same reason: this is the write. Registered fresh
        // otherwise, which is what the consent asked for and what an import with no Todo yet means.
        val existing = dao.sourcesAt(rootUri, listPath)
        if (existing.size > 1) {
            pendingResync = null
            _resyncRequest.value = ResyncRequest.Error(
                "This list's folder is imported " + existing.size + " times — as " +
                    existing.joinToString(", ") { todoLabel(it) } +
                    ". Open the Todo you meant and refresh it from there. Nothing was changed."
            )
            return@launchDb
        }
        val source = existing.firstOrNull() ?: dao.registerSource(
            rootUri = rootUri,
            relativePath = listPath,
            displayName = listPath.ifEmpty { "Root" },
            importerKind = ImporterKind.FILE_AS_TASK,
            now = now()
        )
        applyResync(pending, source)
    }

    /**
     * How a Source is named inside a sentence: the same word the Sources screen titles it with, in
     * quotes.
     *
     * Quoted because the copy around every one of these already says "the Source". A Source whose
     * import root is the granted root has an empty path, and standing "the selected folder" in
     * unquoted read as "already inside the Source the selected folder", which does not parse.
     * Quoting also makes it plain that this names one particular Source rather than describing the
     * folder, and matching the Sources screen is what lets the user go and find it.
     */
    private fun coverLabel(relativePath: String): String =
        "\"" + relativePath.ifEmpty { "Selected folder" } + "\""

    private fun coverLabel(source: SourceEntity): String = coverLabel(source.relativePath)

    /**
     * How a Todo is named inside a sentence, when two of them sit over one folder and the path
     * cannot tell them apart. The card's own name, quoted, matching what the Todo tab shows.
     */
    private fun todoLabel(source: SourceEntity): String =
        "\"" + source.displayName.ifBlank { source.relativePath.ifEmpty { "Selected folder" } } + "\""

    /**
     * The covering Source for a list's folder, or null when nothing covers it — and null too when
     * several do, because a caller reaching the apply with an ambiguous folder is a caller about to
     * write into one of two intentions at random. [requestResync] refuses that case by name before
     * ever getting here, so this is the belt to that pair of braces.
     */
    private suspend fun coveringSourceOf(listId: Long): SourceEntity? {
        val list = dao.getList(listId) ?: return null
        val rootUri = list.sourceRootUri ?: return null
        val relativePath = list.sourceRelativePath ?: return null
        return dao.sourcesCovering(rootUri, relativePath).singleOrNull()
    }

    private suspend fun applyResync(pending: PendingResync, source: SourceEntity) {
        val applied = runCatching {
            dao.applyReconcile(pending.listId, source, pending.files, now())
        }
        pendingResync = null

        if (applied.isFailure) {
            _resyncRequest.value = ResyncRequest.Error("Could not update the list. Nothing was changed.")
            return
        }

        // Everything the scan found has now been acknowledged, so the warning clears here rather
        // than making the user leave and re-enter the screen to be told the same thing.
        publishFreshness(pending.listId, SourceFreshness.UpToDate)

        // Step 3, only when something is actually missing: offer the optional cleanup. The count
        // covers every currently missing source-backed item, including ones flagged by an earlier
        // re-sync, so the prompt matches what removal would actually delete.
        val missing = dao.missingSourceItemCount(pending.listId)
        _resyncRequest.value = if (missing > 0) {
            ResyncRequest.CleanupMissing(pending.listId, missing)
        } else {
            null
        }
    }

    /**
     * Raises the same cleanup prompt on demand, so the offer is recoverable: dismissing the
     * post-re-sync dialog keeps the items, and a later re-sync then reports "up to date" because
     * they are already flagged. Writes nothing by itself.
     */
    fun requestMissingCleanup(listId: Long) = launchDb {
        val missing = dao.missingSourceItemCount(listId)
        if (missing > 0) {
            _resyncRequest.value = ResyncRequest.CleanupMissing(listId, missing)
        }
    }

    /**
     * The explicit destructive choice. Deletes only source-backed items whose source is gone;
     * completion is recomputed inside the same transaction. Never reaches the webhook path.
     */
    fun confirmRemoveMissingItems() = launchDb {
        val request = _resyncRequest.value as? ResyncRequest.CleanupMissing ?: return@launchDb
        val removed = runCatching { dao.removeMissingSourceItems(request.listId, now()) }
        _resyncRequest.value = if (removed.isSuccess) {
            null
        } else {
            ResyncRequest.Error("Could not remove the missing items. Nothing was changed.")
        }
    }

    fun dismissResyncRequest() {
        pendingResync = null
        _resyncRequest.value = null
    }

    fun setFolderQuickReviewEnabled(enabled: Boolean) = launchDb {
        settingsStore.setFolderQuickReviewEnabled(enabled)
    }

    /**
     * Writes an edited template: its name, and one more version when its answer changed.
     *
     * The only write the template pages make, and it is made here rather than in the screen for the
     * reason the store gives — *was anything actually different* is a question for the rows, not for
     * a copy of them. Nothing about which of the two writes happens is decided by the UI: it hands
     * over what was edited into and the store works it out.
     *
     * **What this writes governs every Todo attached to it, immediately.** That is the change
     * v0.5.4 makes: [templateResolver] rebuilds from these two tables, so a button renamed here
     * reads renamed on the next recomposition of every surface drawing a chip of that template —
     * including the files of Tasks imported before the edit. Nothing is re-imported and no row is
     * rewritten: a Task holds a *reference*, and the buttons are whatever the reference points at
     * now. The one thing that does not move is a completed review's snapshot, which is held at the
     * version it was submitted under.
     */
    fun updateTemplate(
        templateId: String,
        name: String,
        scope: TemplateScope,
        buttons: List<TemplateButton>
    ) = launchDb {
        templateStore.update(templateId, name, scope, buttons)
    }

    /**
     * Starts a template from the shipped shape, at version 1.
     *
     * The screen stays where it is: the two flows this view model exposes refresh by themselves, so
     * the new row appears without navigation plumbing. A blank name is refused by the store and
     * simply leaves the list as it was.
     */
    fun createTemplate(name: String) = launchDb {
        templateStore.create(name)
    }

    fun setDefaultWebhookEnabled(enabled: Boolean) = launchDb {
        settingsStore.setDefaultWebhookEnabled(enabled)
    }

    fun setDefaultWebhookUrl(url: String) = launchDb { settingsStore.setDefaultWebhookUrl(url) }

    /**
     * Saves a Todo's own webhook address, and whether it is a copy of the default or typed.
     *
     * The copy is a snapshot, not a subscription: nothing re-reads Settings afterwards, which is
     * what lets a Todo keep posting where it was configured while the default moves on. The
     * backfill is awaited first for the same reason the delivery paths await it — a user can reach
     * this dialog before the one-time upgrade has marked old Todos, and an edit made during that
     * window must not be overwritten by the snapshot a moment later.
     */
    fun setTodoWebhookAddress(sourceId: String, url: String, fromDefault: Boolean) = launchDb {
        ensureWebhookAddressBackfill()
        dao.setSourceWebhookAddress(sourceId, url.trim(), fromDefault)
    }

    /**
     * Saves a List's webhook address override. Empty means *follow each Todo*, which is the
     * ordinary case and why the field can be cleared.
     */
    fun setListWebhookUrl(listId: Long, url: String) = launchDb {
        dao.setListWebhookUrl(listId, url.trim())
    }

    fun setVideoAutoplay(enabled: Boolean) = launchDb { settingsStore.setVideoAutoplay(enabled) }

    fun setVideoStartMuted(muted: Boolean) = launchDb { settingsStore.setVideoStartMuted(muted) }

    fun setRememberReviewPosition(remember: Boolean) = launchDb {
        settingsStore.setRememberReviewPosition(remember)
    }

    fun setAutoCheckSourceFreshness(enabled: Boolean) = launchDb {
        settingsStore.setAutoCheckSourceFreshness(enabled)
    }

    /**
     * The two per-import policies' defaults, seeded into the next Todo rather than applied to any
     * existing one. A Todo's own answers are changed on its card, beside the Todo they are about.
     */
    /**
     * The two standings a Todo card can change about the Todo itself.
     *
     * Both are the Todo's own policy and not a run's, which is why they write to `sources` and
     * nothing else: neither has anything to say until a run happens. Neither re-reads or re-runs
     * anything either — a switch that fired an import would be a switch that does something the
     * moment it is touched, and the point of [ListsDao.setFollowUpstream] is the run that happens
     * when the *Todo* is next opened.
     */
    fun setTodoFollowUpstream(sourceId: String, follow: Boolean) = launchDb {
        dao.setFollowUpstream(sourceId, follow)
    }

    fun setTodoKeepStateOnDelete(sourceId: String, keep: Boolean) = launchDb {
        dao.setKeepStateOnDelete(sourceId, keep)
    }

    fun setDefaultContentPolicy(policy: ContentPolicy) = launchDb {
        settingsStore.setDefaultContentPolicy(policy)
    }

    fun setFollowUpstreamNewTodos(enabled: Boolean) = launchDb {
        settingsStore.setFollowUpstreamNewTodos(enabled)
    }

    fun setQuickReviewWebhookEnabled(enabled: Boolean) = launchDb {
        settingsStore.setQuickReviewWebhookEnabled(enabled)
    }

    fun setWebhookCompletedItemsOnly(enabled: Boolean) = launchDb {
        settingsStore.setWebhookCompletedItemsOnly(enabled)
    }

    fun setReviewUncheckedOnly(enabled: Boolean) = launchDb {
        settingsStore.setReviewUncheckedOnly(enabled)
    }

    fun setWebhookOnReviewExit(enabled: Boolean) = launchDb {
        settingsStore.setWebhookOnReviewExit(enabled)
    }

    fun setAskDeleteAfterWebhook(enabled: Boolean) = launchDb {
        settingsStore.setAskDeleteAfterWebhook(enabled)
    }

    fun setSharedFileArrangement(shared: Boolean) = launchDb {
        settingsStore.setSharedFileArrangement(shared)
    }

    /**
     * Records a listing's new filter and sort, wherever the switch says they belong.
     *
     * The routing is decided here, from an authoritative read rather than from whatever the UI
     * last observed, so a change made in the same breath as flipping the switch cannot be filed
     * against the mode that was on a moment ago. The caller passes the key of whatever it was
     * looking at — a folder under a root, or a List — and does not have to know which mode is
     * active or that the other kind of caller exists.
     */
    fun setArrangement(key: String, arrangement: FileArrangement) = launchDb {
        if (settingsStore.read().sharedFileArrangement) {
            settingsStore.setGlobalArrangement(arrangement)
        } else {
            settingsStore.setFolderArrangement(key, arrangement)
        }
    }

    /** Remembers where Review is, by item id. Never touches completion state. */
    fun setReviewPosition(listId: Long, itemId: Long?) = launchDb {
        dao.setReviewPosition(listId, itemId)
    }

    /** *Export when this list is completed.* About **when**, and independent of [setExportKind]. */
    fun setExportOnCompletion(listId: Long, enabled: Boolean) = launchDb {
        dao.setExportOnCompletion(listId, enabled)
    }

    /**
     * Which mechanism this List's rounds use, as `domain/Exports.kt` encodes it.
     *
     * About **how**, and independent of the switch above. *Inherit* is the state a List spends its
     * whole life in unless asked otherwise: it does not mean *export nothing*, it means *decide one
     * group at a time from the Todos these Tasks came from* — which is the release's headline rule.
     */
    fun setListExportKind(listId: Long, choice: ExportChoice) = launchDb {
        dao.setListExportKind(listId, encodeExportChoice(choice))
    }

    /**
     * Explicit user action, so it posts regardless of any switch and never touches state.
     *
     * **A payload of its own and not a List's**, as of v0.5.6. It used to send whatever List the
     * user happened to be looking at, which made the button a statement about that List wearing a
     * setting's clothes — and it lived on the List's card, next to a URL that is no longer there.
     * The button now sits on the webhook method's own page in Settings and posts a payload naming
     * nothing, because the only question it answers is *can this URL receive?*.
     *
     * **It is a round of one, history included.** v0.6.2 made the History the single place to see
     * what left and to resend it, so a test that posted without leaving a record would be the one
     * delivery the History cannot account for — and a test that *failed* would leave no trace of
     * the address that was tried. It is recorded even when the URL is unusable, for the same
     * reason every other failed attempt is.
     *
     * The one export that does not ask first, along with a resend: pressing a button labelled
     * *Send a test payload* is the confirmation. It still shows the progress dialog, because a
     * receiver can be slow and the seconds in between otherwise look like nothing happening.
     *
     * **[address] is the value the caller is looking at**, when it has one. The default-URL field
     * commits on Done, on losing focus or on leaving the page — and a tap does not take focus from
     * a text field on touch, so a URL typed and then tested was still a draft, and the test posted
     * to the address that had been stored before it. The button exists to answer *can this address
     * receive?*, so it sends to the field as it stands. The value is used for this one attempt and
     * recorded on it; storing it remains the field's commit, not this.
     */
    fun testWebhook(address: String? = null) = launchDb {
        ensureWebhookAddressBackfill()
        val url = (address ?: settingsStore.read().defaultWebhookUrl).trim()
        val at = now()
        val roundId = UUID.randomUUID().toString()

        fun tell(outcome: NoticeOutcome, httpCode: Int?, error: String?) = announce(
            listTitle = "",
            event = EVENT_EXPORT_TEST,
            url = url,
            kind = ExportKind.WEBHOOK,
            severalDestinations = false,
            itemCount = 1,
            outcome = outcome,
            httpCode = httpCode,
            error = error
        )

        val payload = ExportPayload(
            event = EVENT_EXPORT_TEST,
            listId = 0,
            listTitle = "",
            completedAt = null,
            sourceId = null,
            sourceTitle = null,
            at = at,
            tasks = listOf(
                ExportTaskView(
                    publicId = "test",
                    title = "Test",
                    isCompleted = true,
                    templates = emptyList(),
                    assets = listOf(
                        ExportAssetView(
                            publicId = "test-asset",
                            title = "Test",
                            relativePath = null,
                            sourceMissing = false,
                            actions = emptyList()
                        )
                    )
                )
            )
        )
        val body = buildWebhookPayload(payload)

        suspend fun keep(notice: WebhookNotice) = dao.insertWebhookRecord(
            ExportRecordEntity(
                event = EVENT_EXPORT_TEST,
                listTitle = "",
                itemCount = payload.tasks.size,
                payload = body,
                at = now(),
                outcome = notice.outcome.name,
                detail = notice.outcomeLabel,
                kind = ExportKind.WEBHOOK.key,
                webhookUrl = url,
                roundId = roundId
            )
        )

        val urlProblem = webhookAddressProblem(ExportKind.WEBHOOK, url)
        if (urlProblem != null) {
            keep(tell(NoticeOutcome.FAILED, null, urlProblem))
            return@launchDb
        }

        val result = post(
            WebhookProgress(
                event = EVENT_EXPORT_TEST,
                listTitle = "",
                host = webhookHost(url),
                itemCount = 1,
                severalDestinations = false
            ),
            url,
            body
        )
        keep(
            tell(
                if (result.status == DeliveryStatus.SUCCESS) {
                    NoticeOutcome.SENT
                } else {
                    NoticeOutcome.FAILED
                },
                result.httpCode,
                result.error
            )
        )
    }

    /**
     * A Quick Review reached the end of its queue.
     *
     * Sends only when [AppSettings.quickReviewWebhookEnabled] is on. Quick Review has no
     * configuration of its own, by design, so it defers to the Todos its files came from: each
     * group posts to its Todo's address, and files no Todo owns fall back to the live default.
     * Nothing is sent — and nothing is said — when Quick Review webhooks are off, because that is
     * the state Quick Review has always been in. The payload covers [queueIds], the folder that
     * was reviewed, not the whole list.
     *
     * The settings are read from the store rather than from the observed [settings] flow: this
     * fires on a swipe, and a delivery must never act on a value that has not warmed up yet.
     */
    fun onQuickReviewFinished(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>
    ) = launchDb {
        if (!settingsStore.read().quickReviewWebhookEnabled) return@launchDb
        deliverFolderReview(rootUri, folderPath, items, EVENT_QUICK_REVIEW_COMPLETED)
    }

    /**
     * A folder review's round, as a payload.
     *
     * The decisions are re-read under the ordering lock rather than taken from the screen, for the
     * same reason a list's are: the swipe that ended the round is a separate write, and a payload
     * built without waiting for it would be missing the very item that triggered it.
     *
     * The `list` a receiver sees is the folder — it has an id of 0, because no list is involved,
     * and it is named after the folder that was reviewed. That keeps one payload shape on the
     * wire for every event rather than inventing a second one for folder rounds.
     */
    private suspend fun deliverFolderReview(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>,
        event: String
    ) {
        val taskIds = items.map { it.taskId }
        if (taskIds.isEmpty()) return

        // Re-read decisions by Task id rather than by a registration row: a Task without one — a
        // multi-Asset Task, or one no List ever touched — must not silently drop out of the payload.
        // The payload facts are read beside the rows, for the same reason and with the same
        // freshness: a payload built from the queue's frozen copy would report the tags a Task was
        // imported under even after its Todo had been re-run, and the `actions` array is current
        // configuration by definition.
        val (rows, facts) = writeOrder.withLock {
            val fresh = dao.getTaskStates(taskIds)
            val pairs = dao.getTaskAssetStates(taskIds)
            val rows = withLiveDecisions(items, fresh, pairs)
                .mapIndexed { index, item -> item.toPayloadRow(index) }
            rows to payloadFacts(rows)
        }

        deliverPayload(
            list = folderPayloadList(rootUri, folderPath),
            rows = rows,
            event = event,
            // There is no List, so there is nothing above the Todos to defer to. `Inherit` is the
            // choice that means *decide this one group at a time, from the Todos these Tasks came
            // from*, which is exactly what a folder round has always done.
            listChoice = ExportChoice.Inherit,
            // No List switch to consult either. A folder round is gated by the export-wide
            // switches its callers already read, and then by the Todo each group came from.
            requireRoundEnabled = false,
            recordAgainstList = false,
            facts = facts,
            // Both callers are automatic - a queue that ran out, or a review being left - so a
            // folder round always asks, exactly as a List round does.
            confirm = true
        )
    }

    /**
     * The stand-in "list" a folder round reports as. Never stored: built here, serialised, gone.
     * Carrying the folder as its source path is what makes the per-item `relativePath` on the wire
     * come out identical to the one a List over the same folder would have produced.
     */
    private fun folderPayloadList(rootUri: String, folderPath: String) = ListEntity(
        id = 0,
        title = folderPath.substringAfterLast('/').ifEmpty { "Selected folder" },
        createdAt = now(),
        sourceRootUri = rootUri,
        sourceRelativePath = folderPath
    )

    /**
     * A List review round is starting: entering the screen, or "Review again" from the end of one.
     *
     * From here until the round ends, a completion this List reaches is the round's to report.
     */
    fun onReviewStarted(listId: Long) = launchDb {
        writeOrder.withLock { openReviewRounds += listId }
    }

    /**
     * A List review round has ended — its queue finished on screen, or the user left it.
     *
     * Sends only when [AppSettings.webhookOnReviewExit] is on, through the list's own webhook, so
     * a list with its webhook switched off says so exactly as a completion would. This is the one
     * report the round produces, whether or not it completed the List along the way.
     */
    fun onReviewExited(listId: Long, queueIds: List<Long>) = launchDb {
        writeOrder.withLock { openReviewRounds -= listId }
        if (!settingsStore.read().webhookOnReviewExit) return@launchDb
        deliver(
            listId = listId,
            event = EVENT_REVIEW_EXITED,
            requireEnabled = true,
            queueIds = queueIds,
            confirm = true
        )
    }

    /**
     * A folder review is starting: entering Quick Review's queue.
     *
     * The counterpart of [onReviewStarted] for the round that has no List. It exists for
     * [CompletionScope.AnotherRound]: a List completing under a folder review is the same
     * interruption as one completing under another List's, and the Tasks are often literally the
     * same Tasks — a file imported by two Todos is in both Lists — so this is reached by ordinary
     * use rather than by a corner case.
     *
     * Nothing else is keyed off it. A folder round reports through [onFolderReviewExited] and never
     * through a completion, so the count only ever answers *is somebody reviewing something*.
     */
    fun onFolderReviewStarted() = launchDb {
        writeOrder.withLock { openFolderRounds++ }
    }

    /**
     * The folder review's screen is gone, so the round is over whatever happened on it.
     *
     * **Paired with [onFolderReviewStarted] by the composition and not by a gesture**, which is the
     * whole of why it is a separate call: Quick Review has two ways to end — its queue runs out, or
     * the user leaves — and only the second goes through [onFolderReviewExited]. Leaving that as the
     * only place the count came back would leak it on the first path, and a leaked count does not
     * fail quietly: *a folder round is open* is what makes a completing List keep its payload
     * instead of asking, so one finished Quick Review would leave every later completion in the app
     * answering for itself, for good.
     *
     * The start and the end are one `DisposableEffect` on the screen's own session key for that
     * reason: a round is the screen, the composition disposes exactly once per session, and no
     * future exit path has to remember to close it.
     */
    fun onFolderReviewEnded() = launchDb {
        writeOrder.withLock { openFolderRounds = (openFolderRounds - 1).coerceAtLeast(0) }
    }

    /**
     * The same, for a folder review, which has no list and so delivers through the default
     * webhook. Gated on [AppSettings.quickReviewWebhookEnabled] as well, since that switch is
     * what lets a folder round send anything at all.
     *
     * Only the report lives here. The round's bookkeeping is [onFolderReviewEnded]'s, because this
     * is called on one of the two ways a round ends and the count has to come back on both.
     */
    fun onFolderReviewExited(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>
    ) = launchDb {
        val current = settingsStore.read()
        if (!current.webhookOnReviewExit || !current.quickReviewWebhookEnabled) return@launchDb
        deliverFolderReview(rootUri, folderPath, items, EVENT_REVIEW_EXITED)
    }

    /**
     * Where a List's completion landed relative to whatever is on screen.
     *
     * The distinction the completion action turns on, and the reason it is a value rather than a
     * boolean: there are three answers and only two of them are about *this* List. What a
     * completion should do depends on whether a round is open, and — if one is — on whether it is
     * this List's.
     */
    private enum class CompletionScope {
        /** Nothing is being reviewed. The completion is reported on its own behalf, as it always was. */
        Unattended,

        /** This List is under review: its round reports the completion, narrowed to the round. */
        ThisRound,

        /**
         * A round is open on something else entirely — another List, or a folder.
         *
         * Nobody on that screen asked for this, and this List's business is not what they are
         * looking at. See [NoticeOutcome.DEFERRED] for what happens instead of sending or asking.
         */
        AnotherRound
    }

    /**
     * Which of the three a completing List is in, read under the same lock as the write that
     * completed it.
     *
     * Read inside the lock and not after it, for the reason [openReviewRounds] gives: the exit that
     * closes a round is a separate coroutine racing the write, and only the lock keeps a round that
     * was open when the item was checked from having closed by the time the decision is made.
     */
    private fun scopeOf(listId: Long): CompletionScope = when {
        listId in openReviewRounds -> CompletionScope.ThisRound
        openReviewRounds.isNotEmpty() || openFolderRounds > 0 -> CompletionScope.AnotherRound
        else -> CompletionScope.Unattended
    }

    /**
     * The single place the completion action fires. [transitioned] comes from the DAO transaction
     * that performed the edit, so a list that merely stays complete never delivers again.
     */
    private suspend fun onCompletionTransition(
        listId: Long,
        transitioned: Boolean,
        scope: CompletionScope = CompletionScope.Unattended
    ) {
        if (!transitioned) return

        // The round that completed this List is about to report it, to this same webhook, narrowed
        // to what the round actually covered — so this would be the same moment described twice.
        // The round's payload is the one worth having: the settings that narrow it are the user
        // saying they want the decisions they just made, not every decision the List has ever
        // accumulated.
        //
        // Gated on the exit report actually being switched on. With it off there is no second
        // payload to prefer, and staying silent here would lose the completion altogether — it has
        // always fired independently of any of the review switches, and still does.
        if (scope == CompletionScope.ThisRound && settingsStore.read().webhookOnReviewExit) return

        // The user is in the middle of a round on something else, so they are not asked and this is
        // not sent — but it is not dropped either. The payload is built and kept, exactly as a
        // declined one is, and the List is left saying *not sent*. The user comes back to a List
        // that has a round waiting in its history rather than to a dialog they had to answer from
        // inside someone else's review, which is the whole of what the user asked for: *"行为和
        // 结果预期相匹配。用户在一个list里动作，不要反馈给用户另一个list的结果"*.
        deliver(
            listId = listId,
            event = EVENT_LIST_COMPLETED,
            requireEnabled = true,
            confirm = true,
            assumeNotNow = scope == CompletionScope.AnotherRound
        )
    }

    /**
     * The one delivery path, whatever asked for it.
     *
     * [queueIds] narrows the payload to the items a review actually covered, in stored order;
     * null means the whole list, which is what a completion and the test button send.
     *
     * There is no longer a *which URL* to choose between — the List's own copy is gone as of
     * v0.5.6 and every webhook reads the app default — so what used to be `useDefaultWebhook` has
     * nothing left to decide and is not a parameter. The List's **kind** is, and it comes off the
     * row read under the lock below.
     *
     * Every attempt that reaches a verdict is recorded against the list and announced, including
     * one abandoned over an unusable URL. That an empty default URL fails loudly here is the
     * point: it is the case the user most needs telling about.
     *
     * [confirm] is what separates a delivery the app decided to make from one the user pressed a
     * button for. [assumeNotNow] is the narrower case of one the app decided *not* to put to them,
     * and both are [deliverPayload]'s.
     */
    private suspend fun deliver(
        listId: Long,
        event: String,
        requireEnabled: Boolean,
        queueIds: List<Long>? = null,
        confirm: Boolean,
        assumeNotNow: Boolean = false
    ) {
        // The whole payload is read in one pass under the ordering lock, so an item write from
        // the swipe that triggered this has certainly landed and the list and its items cannot
        // disagree about what just happened.
        // The payload facts are read inside the same pass as the rows, which is the point of the
        // pass: the Assets, their decisions and the templates that name those decisions are one
        // answer about one moment, and a row and a template read at two different moments could
        // disagree about which tag a selected slot is.
        val stored = writeOrder.withLock {
            dao.getList(listId)?.let { list ->
                val rows = scopedItems(listId, queueIds)
                ListDelivery(list, rows, payloadFacts(rows))
            }
        } ?: return
        deliverPayload(
            list = stored.list,
            rows = stored.rows,
            event = event,
            // The List's choice, decoded here rather than in the dispatcher so that the one place
            // that reads the column and the one place that acts on it cannot drift. `inheritable`
            // is true because this is a List: `inherit` on one is a decision, not a dead word.
            listChoice = decodeExportChoice(stored.list.exportKind, inheritable = true),
            requireRoundEnabled = requireEnabled,
            recordAgainstList = true,
            confirm = confirm,
            assumeNotNow = assumeNotNow,
            facts = stored.facts
        )
    }

    /**
     * One list round's whole payload input, read in one pass under [writeOrder].
     *
     * A named type rather than a `Triple`, because the three parts are read together for a reason
     * that has to travel with them: a row, the list it belongs to, and the facts about its Tasks and
     * Assets are one answer about one moment, and a caller that read them separately would be free
     * to be wrong about which tag a selected slot is.
     */
    private data class ListDelivery(
        val list: ListEntity,
        val rows: List<ListItemEntity>,
        val facts: PayloadFacts
    )

    /**
     * Everything the payload needs that is not the row itself: the Task and Asset rows, and the
     * template table they resolve against.
     *
     * Read once per round, in one transaction's worth of queries, so a split round's two groups
     * describe the same moment and a template edit mid-delivery cannot give them two opinions. The
     * resolver is built here rather than per row; the old per-row read was an N+1 query and, worse,
     * could straddle a template update.
     */
    private data class PayloadFacts(
        val tasks: Map<String, PayloadTaskRow>,
        val assetsByTask: Map<String, List<PayloadAssetRow>>,
        val resolver: TemplateResolver
    )

    private suspend fun payloadFacts(rows: List<ListItemEntity>): PayloadFacts {
        val taskIds = rows.map { it.taskId }.distinct()
        return PayloadFacts(
            tasks = if (taskIds.isEmpty()) {
                emptyMap()
            } else {
                dao.getPayloadTasks(taskIds).associateBy { it.taskId }
            },
            assetsByTask = if (taskIds.isEmpty()) {
                emptyMap()
            } else {
                dao.getPayloadAssets(taskIds).groupBy { it.taskId }
            },
            resolver = resolverNow()
        )
    }

    /**
     * One round's delivery, whatever asked for it. **The release's headline rule, implemented.**
     *
     * [listChoice] is what the List decided, decoded from `lists.exportKind` by the caller —
     * [ExportChoice.By] when the List named a mechanism, [ExportChoice.Nothing] when it said
     * nothing exports, and [ExportChoice.Inherit] when it deferred to its Todos. A folder round
     * passes [ExportChoice.Inherit] too, for the honest reason: there is no List, so there is
     * nothing above the Todos to defer to.
     *
     * [requireRoundEnabled] applies the List's own switch — the card's *Export when this list is
     * completed* — and it is the one thing here that is about the **List** rather than about a
     * mechanism. It survives into the plural case because a round that does not happen cannot be
     * split, so it is asked once, of the round, and never per group. A folder round passes `false`
     * and is gated by the switches its own callers already read.
     *
     * The order is the user's own, and the code is their sentence: *"浏览退出或者完成 -> 触发 export
     * -> 检查 list 当前是否有配置 export -> 没有，则统计此次 payload 涉及的 task 的来源 -> 按照来源
     * todo 整理成多份 payload -> 按照 todo 各自配置的 payload 输出"*.
     *
     * [assumeNotNow] is the app answering *Not now* on the user's behalf, for a round they are not
     * present for — a List that completed under somebody else's review. It changes two things and
     * both follow from the same fact: nothing is sent, and **nothing is shown**, because a notice
     * about a List the user is not looking at is the same interruption the dialog would have been.
     * The round is kept, the List is left saying *not sent*, and the history is where it will be
     * found. Every outcome is silenced and not merely the confirmation — a failure here is still a
     * failure, recorded as one, to be read when the round is.
     */
    private suspend fun deliverPayload(
        list: ListEntity,
        rows: List<ListItemEntity>,
        event: String,
        listChoice: ExportChoice,
        requireRoundEnabled: Boolean,
        recordAgainstList: Boolean,
        confirm: Boolean,
        facts: PayloadFacts,
        assumeNotNow: Boolean = false
    ) {
        // The one-time snapshot must have finished before any round asks where it goes: on an
        // upgraded install this call fills every Todo's address from Settings, and a round that
        // ran ahead of it would see every Todo blank and refuse them all.
        ensureWebhookAddressBackfill()
        val current = settingsStore.read()
        val listId = list.id
        val defaultUrl = current.defaultWebhookUrl.trim()
        val listUrl = list.webhookUrl.trim()
        // For the outcomes that end before any group exists — switched off, nothing to send,
        // nothing configured — there is no group to take an address from. The List's own address
        // when it has one, the live default otherwise.
        val roundUrl = listUrl.ifEmpty { defaultUrl }

        // The one door every notice in this method goes through, so a round that is being kept
        // quietly cannot leak one out of the branch nobody remembered. The notice is still built
        // and still returned — [keepRound] writes its words into the history, which is the whole
        // of the report this round gets.
        fun queued(notice: WebhookNotice): WebhookNotice = if (assumeNotNow) notice else raise(notice)

        // Action wire values resolved now, not when the chips were tapped: an item's boolean
        // means "slot 1 is selected", and what slot 1 is called is current configuration — which
        // since v0.5.0 also means the *control* is current, because it is what names the slots.
        //
        // `webhookItems` is the one switch that narrows *what* leaves rather than *how*, so it
        // runs once on the round rather than per group: a group is a subset of a round, and the
        // same filter applied twice is the same filter.
        val items = webhookItems(rows, current)

        // The List said nothing exports. Silence is the answer, and deliberately without a notice:
        // the user set this up, and telling someone what they chose not to have happen is the
        // noise a declined confirmation already refuses to make.
        if (listChoice is ExportChoice.Nothing) return

        // One clock read for the round, so a file named `…-143022` cannot contain a header that
        // says a different second — and so the two are assertable together rather than being a
        // thing that is usually right.
        val at = now()

        // One round, one id, however many groups it splits into. History groups by this so a
        // split round reads as one event with several deliveries; an empty id means a row written
        // before v0.6.2, which is a round of one.
        val roundId = UUID.randomUUID().toString()

        // The round's payload, in the shape the pre-split code already had: only the origin moves.
        // Every group of a split round walks the same facts, so the two bodies are two views of one
        // moment rather than two readings of the tables.
        fun payloadOf(rowsToSend: List<ListItemEntity>, sourceId: String?, sourceTitle: String?) =
            ExportPayload(
                event = event,
                listId = list.id,
                listTitle = list.title,
                completedAt = list.completedAt,
                sourceId = sourceId,
                sourceTitle = sourceTitle,
                at = at,
                tasks = exportTaskViews(rowsToSend, facts.tasks, facts.assetsByTask, facts.resolver)
            )

        // No delivery is recorded against the list for a switched-off List — there was no attempt
        // — but the user is still told, and the body is still kept. A List quietly completing with
        // no export is exactly the case that otherwise looks identical to one that silently
        // failed, and the round inside it is worth just as much.
        //
        // The body is rendered by the List's own kind when it named one, and by the webhook
        // otherwise. It describes the round rather than a delivery — which is what it is for, and
        // what the record's `kind` column then says which rendering of it this is.
        // The mechanism the *round* would have used, for the two outcomes that end before any
        // group exists: the List's own kind when it named one, and the webhook otherwise — the same
        // fallback the body below is rendered by, so a notice and the payload it describes agree.
        val roundKind = (listChoice as? ExportChoice.By)?.kind ?: ExportKind.WEBHOOK

        if (requireRoundEnabled && !list.exportOnCompletion) {
            val kind = roundKind
            val payload = payloadOf(items, null, null)
            val notice = queued(
                noticeOf(
                    list.title, event, roundUrl, kind = kind, severalDestinations = false,
                    itemCount = payload.tasks.size,
                    outcome = NoticeOutcome.DISABLED, httpCode = null, error = null
                )
            )
            keepRound(
                list, event, kind, payload, bodyOf(kind, roundUrl, payload), notice,
                roundUrl, roundId
            )
            return
        }

        // A usable configuration with nothing to put in it: a review left without a single swipe,
        // or a payload narrowed to checked items when none are. Each group would render an empty
        // payload, and an empty items array would tell a receiver a round happened when none did —
        // so this is reported rather than delivered, and nothing is recorded, because nothing was
        // attempted.
        if (items.isEmpty()) {
            queued(
                noticeOf(
                    list.title, event, roundUrl, kind = roundKind, severalDestinations = false,
                    itemCount = 0, outcome = NoticeOutcome.EMPTY, httpCode = null, error = null
                )
            )
            return
        }

        // Task -> the Todo it came from, in one query for the whole round rather than one per
        // group. A Task with no row in `task_imports` is absent from the map rather than mapped to
        // a null kind, which is what lets the planner tell *no Todo owns this* from *a Todo whose
        // kind could not be read*.
        val sources = writeOrder.withLock {
            dao.getExportSources(items.map { it.taskId }.distinct())
        }.associateBy { it.taskId }

        val groups = planExports(
            items = items,
            listChoice = listChoice,
            sourceOf = { row ->
                sources[row.taskId]?.let { stored ->
                    ExportSource(
                        id = stored.sourceId,
                        title = stored.sourceName,
                        // A Todo's word decoded as a Todo's: `inherit` on one is Nothing, because a
                        // Todo has nothing above it to follow. This is decision 6's asymmetry, met
                        // at the one place the two columns' vocabularies touch.
                        kind = (decodeExportChoice(stored.exportKind, inheritable = false)
                            as? ExportChoice.By)?.kind,
                        // The address this Todo holds. Empty is a fact, not a gap: the planner
                        // keeps it and the group fails visibly instead of borrowing the default.
                        webhookUrl = stored.webhookUrl.trim()
                    )
                }
            },
            // A List's non-empty address overrides every webhook group it produces; when it has
            // none, each group falls back to its Todo, and unowned items to the live default.
            listWebhookUrl = listUrl,
            defaultWebhookUrl = defaultUrl
        )

        // Every Todo this round touched said nothing exports, so there is no group to deliver and
        // no payload to keep. **The round is reported anyway, and that is the point.**
        //
        // This used to return in silence, which made a round that exported nothing indistinguishable
        // from one that exported and failed to tell anyone: no file, no POST, no record, no word.
        // Quick Review's promise is that finishing a round produces a result — that is what "send
        // when leaving a review" is for — so the user has to be told which of the two they got. The
        // notice is the cheap half of the fix the user chose: nothing is stored, because there is no
        // payload whose kind would mean anything and a row reading *exported by nothing, to nowhere*
        // would be a record of an attempt that was never made. See UNCONFIGURED.
        //
        // Note that the *List* saying nothing is deliberately still silent, above: that is an answer
        // the user gave on this very List, while this is a configuration gap they cannot see from
        // the screen they finished on.
        if (groups.isEmpty()) {
            queued(
                noticeOf(
                    list.title, event, roundUrl, kind = roundKind, severalDestinations = false,
                    itemCount = items.size,
                    outcome = NoticeOutcome.UNCONFIGURED, httpCode = null, error = null
                )
            )
            return
        }

        // How many different receivers this round has, said once so the one confirmation it asks
        // and the summary it shows cannot disagree. Blank addresses are left out: a group with no
        // address fails before anything is sent, and counting it would make a round with one real
        // destination claim to have several.
        val roundAddresses = groups
            .filter { it.kind == ExportKind.WEBHOOK }
            .map { it.webhookUrl.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val severalDestinations = roundAddresses.size > 1
        val roundHost = roundAddresses.singleOrNull()?.let { webhookHost(it) }

        val notices = mutableListOf<WebhookNotice>()

        // The answer to this round's one question, kept so that the second group of a split round
        // does not ask it again: a round is one thing the user agreed to, and two dialogs for it
        // would be the app asking twice whether it may do the single thing it was told to do.
        var roundAnswer: Boolean? = null

        groups.forEach { group ->
            // Where this group posts. Resolved by the planner and used for the check, the body,
            // the exporter, the progress and the history row, so all five name one destination.
            val groupUrl = group.webhookUrl.trim()
            val payload = payloadOf(group.items, group.sourceId, group.sourceTitle)
            val body = bodyOf(group.kind, groupUrl, payload)

            fun describe(
                outcome: NoticeOutcome,
                httpCode: Int?,
                error: String?,
                location: String? = null
            ) = noticeOf(
                listTitle = list.title,
                event = event,
                url = groupUrl,
                kind = group.kind,
                // Per-group notices name this group's own host, so they are never *several*.
                severalDestinations = false,
                itemCount = payload.tasks.size,
                outcome = outcome,
                httpCode = httpCode,
                error = error,
                location = location
            )

            fun tell(
                outcome: NoticeOutcome,
                httpCode: Int?,
                error: String?,
                location: String? = null
            ): WebhookNotice = queued(describe(outcome, httpCode, error, location))
                .also { notices += it }

            // A webhook with nowhere to go is the failure the user most needs telling about, and
            // it is recorded against the List as every other failure is: the round happened, and
            // the reason it did not leave is configuration the user can fix. The message
            // distinguishes *no address at all* from a malformed one, because the fix differs.
            val urlProblem = webhookAddressProblem(group.kind, groupUrl)
            if (urlProblem != null) {
                if (recordAgainstList) {
                    dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, urlProblem)
                }
                keepRound(
                    list, event, group.kind, payload, body,
                    tell(NoticeOutcome.FAILED, null, urlProblem),
                    groupUrl, roundId
                )
                return@forEach
            }

            val exporter = exporterFor(group.kind, groupUrl, getApplication())
            if (exporter == null) {
                keepRound(
                    list, event, group.kind, payload, body,
                    tell(NoticeOutcome.FAILED, null, "there is nowhere for this to go"),
                    groupUrl, roundId
                )
                return@forEach
            }

            // Everything is settled and something really is about to leave — so this is where an
            // automatic delivery asks. Deliberately after the checks above and not before: an
            // address that cannot be used is not a decision to put to the user, and asking
            // whether to export only to answer that it had nowhere to go would be theatre.
            //
            // `asksBeforeDelivering` is what keeps a file write out of it. Asking before every
            // round would train the user to tap through the one dialog that matters.
            //
            // Declining is not a failure and costs nothing. The payload is kept exactly as a
            // failed one is, so a round refused here can still be sent later from the history, and
            // nothing about the items — least of all whether they are checked — is touched either
            // way. There is no notice for it: the user just pressed the button, and telling them
            // what they did is noise. The List is told all the same — see the record below.
            //
            // Sits exactly here, and not before the checks above, so that *the app is answering
            // for the user* means the same thing it means when the user answers: this is the point
            // past which something would actually go. An unusable address is still recorded as
            // the failure it is — dismissing a List's export as *not now* because its address
            // happens to be blank would file a configuration problem under a choice the user never
            // made.
            if (assumeNotNow) {
                val held = describe(NoticeOutcome.DEFERRED, null, null)
                if (recordAgainstList) {
                    dao.recordDelivery(listId, now(), DeliveryStatus.NOT_SENT.name, null, null)
                }
                keepRound(list, event, group.kind, payload, body, held, groupUrl, roundId)
                return@forEach
            }

            if (confirm && exporter.asksBeforeDelivering) {
                // One question for the whole round, so it describes the round: the one host when
                // every group shares it, and *several webhook addresses* when they do not. The
                // per-group notices afterwards are the ones that name which host failed.
                val allowed = roundAnswer ?: awaitConfirmation(
                    WebhookProgress(
                        event = event,
                        listTitle = list.title,
                        host = roundHost,
                        itemCount = payload.tasks.size,
                        severalDestinations = severalDestinations
                    )
                ).also { roundAnswer = it }
                if (!allowed) {
                    // The same record as the branch above, from the same answer: the user said
                    // *not now*, so the List's last export is a payload that did not leave. Kept
                    // as a delivery of its own rather than left as whatever came before, which
                    // would have the List claiming its most recent export succeeded when the most
                    // recent thing that happened to it was this.
                    if (recordAgainstList) {
                        dao.recordDelivery(listId, now(), DeliveryStatus.NOT_SENT.name, null, null)
                    }
                    keepRound(
                        list, event, group.kind, payload, body,
                        describe(NoticeOutcome.DECLINED, null, null),
                        groupUrl, roundId
                    )
                    return@forEach
                }
            }

            val attempt = exporter.deliver(payload, body)
            val notice = when (attempt) {
                is ExportAttempt.Delivered -> {
                    if (recordAgainstList) {
                        dao.recordDelivery(
                            listId, now(), DeliveryStatus.SUCCESS.name, attempt.httpCode, null
                        )
                    }
                    tell(exporter.deliveredOutcome, attempt.httpCode, null, attempt.location)
                }
                is ExportAttempt.Failed -> {
                    if (recordAgainstList) {
                        dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, attempt.detail)
                    }
                    tell(NoticeOutcome.FAILED, null, attempt.detail)
                }
                ExportAttempt.Skipped -> tell(NoticeOutcome.DISABLED, null, null)
            }
            keepRound(list, event, group.kind, payload, body, notice, groupUrl, roundId)
        }

        // What the round leaves on the device, once the round itself has been reported.
        //
        // **Once per round and not once per group.** The offer is about the files the round
        // covered, and a round that split into two payloads did not cover two sets of files.
        // [offerPostWebhookDeletion] reads one notice to decide whether it is an offer or a
        // report, so a round in which any group failed reports rather than offers — deleting the
        // files a group described before anybody had received them would destroy the only copy of
        // what that group was about.
        //
        // Only for a delivery the app decided to make, which is what [confirm] already stands for:
        // the Test button sends a List the user is looking at rather than a round they just
        // finished, and offering to delete its files off the back of a button labelled *Test*
        // would be indefensible. A resend never reaches here at all.
        //
        // And never for a round the user was not present for. [assumeNotNow] is a promise that this
        // List says nothing on their screen, and that has to include the reports this offer turns
        // into when a group failed or was switched off — a *these files were not sent* dialog over
        // somebody else's review is the interruption the whole branch exists to prevent.
        if (confirm && notices.isNotEmpty() && !assumeNotNow) {
            offerPostWebhookDeletion(
                current,
                list,
                items,
                notices.firstOrNull { !it.succeeded } ?: notices.last()
            )
        }
    }

    /**
     * The body one group produces, rendered by the kind that would have delivered it.
     *
     * Built before the branches that use it rather than just before the delivery: the payload that
     * never leaves is exactly the one whose body has to be kept. For a webhook with no URL there is
     * no exporter, and the JSON is still what was going to be posted — which is why the fallback is
     * the webhook body rather than nothing.
     */
    private fun bodyOf(kind: ExportKind, url: String, payload: ExportPayload): String =
        exporterFor(kind, url, getApplication())?.render(payload) ?: buildWebhookPayload(payload)

    /**
     * Keeps one group's delivery in the history, in the same words the user was just given.
     *
     * Successes included: a history that holds only failures cannot answer *did that round actually
     * go out?*, which is the question people have. Nothing is kept for a group with nothing in it —
     * there would be nothing to resend.
     *
     * [webhookUrl] is the address this attempt used, recorded so a resend can go back to it rather
     * than to whatever Settings happens to say later; it is empty for a text export, which has no
     * destination of that kind. [roundId] is shared by every group of one `deliverPayload` call, so
     * the History can show a split round as one event.
     */
    private suspend fun keepRound(
        list: ListEntity,
        event: String,
        kind: ExportKind,
        payload: ExportPayload,
        body: String,
        notice: WebhookNotice,
        webhookUrl: String,
        roundId: String
    ) {
        if (payload.tasks.isEmpty()) return
        dao.insertWebhookRecord(
            ExportRecordEntity(
                event = event,
                listTitle = list.title,
                itemCount = payload.tasks.size,
                payload = body,
                at = now(),
                outcome = notice.outcome.name,
                detail = notice.outcomeLabel,
                kind = kind.key,
                webhookUrl = webhookUrl,
                roundId = roundId
            )
        )
    }

    /**
     * Puts one delivery to the user and waits for the answer.
     *
     * Suspends the delivery coroutine rather than unwinding it and picking the work up again on
     * the other side, which is what keeps the confirmation honest: the payload the user is being
     * asked about is already built and is the exact one that will go, so nothing can change
     * between the question and the send.
     *
     * The `finally` is for the case where nobody ever answers - the screen the delivery came from
     * going away, or the ViewModel being cleared. [settleConfirmation] has normally removed the
     * entry already, and removing it twice is a no-op.
     */
    private suspend fun awaitConfirmation(describing: WebhookProgress): Boolean {
        val pending = PendingWebhookSend(
            describing = WebhookConfirmation(
                event = describing.event,
                listTitle = describing.listTitle,
                host = describing.host,
                itemCount = describing.itemCount,
                severalDestinations = describing.severalDestinations
            )
        )
        _webhookConfirmations.update { it + pending }
        return try {
            pending.decision.await()
        } finally {
            _webhookConfirmations.update { queued -> queued.filterNot { it === pending } }
        }
    }

    /** The user answered the confirmation on screen. */
    fun confirmWebhookSend() = settleConfirmation(send = true)

    /** The user declined it. Nothing is sent, and nothing about the items changes. */
    fun declineWebhookSend() = settleConfirmation(send = false)

    private fun settleConfirmation(send: Boolean) {
        val head = _webhookConfirmations.value.firstOrNull() ?: return
        _webhookConfirmations.update { it.drop(1) }
        head.decision.complete(send)
    }

    /**
     * The one place a payload actually leaves the device, and the only thing that publishes
     * [webhookProgress].
     *
     * Every send goes through here - an automatic one, the Test button, a resend from the history
     * - so "Sending..." appears for all of them and cannot be forgotten by whichever path gets
     * added next. The clearing is in a `finally` because a POST that throws is still a POST that
     * finished, and a progress dialog nothing can dismiss would be the worst of the three
     * outcomes.
     */
    private suspend fun post(
        describing: WebhookProgress,
        url: String,
        payload: String
    ): DeliveryResult {
        _webhookProgress.value = describing
        return try {
            withContext(ioDispatcher) { postWebhook(url, payload) }
        } finally {
            _webhookProgress.value = null
        }
    }

    /**
     * Sends a kept payload again.
     *
     * The address, in order of preference: [address] when the caller supplies one — the History's
     * resend dialog prefills it and the user may edit it for this attempt — then the address the
     * record itself used, then the live default. The middle one is why resend can fix a round that
     * went to a Todo's own address after that Todo has moved on; the last is all a row written
     * before v0.6.2 can offer, and such a row says its original address was not recorded.
     *
     * A temporary [address] is for one attempt only: neither it nor the round id is written back,
     * so the record keeps saying where the original went. Only `at`, `outcome` and `detail` move,
     * and the detail gains the host when the retry went somewhere else.
     *
     * Only the URL is required: a record exists precisely because something was misconfigured, and
     * refusing to retry it because a switch elsewhere is off would be refusing to fix the thing the
     * user came here to fix. The body is replayed byte for byte rather than rebuilt, so what
     * arrives is what failed, whatever has happened to the list since.
     */
    fun resendWebhookRecord(id: Long, address: String? = null) = launchDb {
        val record = dao.getWebhookRecord(id) ?: return@launchDb
        val stored = record.webhookUrl.trim()
        val current = settingsStore.read().defaultWebhookUrl.trim()
        val url = address?.trim()?.takeIf { it.isNotEmpty() }
            ?: stored.takeIf { it.isNotEmpty() }
            ?: current

        // Said beside the outcome, never written into the address column: what this attempt should
        // be findable by is where it went and why there is no record of the original.
        val note = when {
            stored.isEmpty() -> "original address not recorded"
            url != stored -> webhookHost(url)?.let { "resent to $it" }
            else -> null
        }

        fun tell(outcome: NoticeOutcome, httpCode: Int?, error: String?) = announce(
            listTitle = record.listTitle,
            event = record.event,
            url = url,
            // The record's own kind, so a replayed round is described as what it was. Only a
            // webhook record is offered a Resend at all; a txt one never reaches here.
            kind = ExportKind.fromKey(record.kind) ?: ExportKind.WEBHOOK,
            severalDestinations = false,
            itemCount = record.itemCount,
            outcome = outcome,
            httpCode = httpCode,
            error = error
        )

        suspend fun settle(notice: WebhookNotice) = dao.updateWebhookRecord(
            id,
            now(),
            notice.outcome.name,
            notice.outcomeLabel + (note?.let { " · $it" } ?: "")
        )

        val urlProblem = webhookAddressProblem(ExportKind.WEBHOOK, url)
        if (urlProblem != null) {
            settle(tell(NoticeOutcome.FAILED, null, urlProblem))
            return@launchDb
        }

        val result = post(
            WebhookProgress(
                event = record.event,
                listTitle = record.listTitle,
                host = webhookHost(url),
                itemCount = record.itemCount,
                severalDestinations = false
            ),
            url,
            record.payload
        )
        val succeeded = result.status == DeliveryStatus.SUCCESS
        settle(
            tell(
                if (succeeded) NoticeOutcome.SENT else NoticeOutcome.FAILED,
                result.httpCode,
                result.error
            )
        )
    }

    /** Drops a kept payload for good. The only thing in the app that can lose one. */
    fun deleteWebhookRecord(id: Long) = launchDb { dao.deleteWebhookRecord(id) }

    /** The body itself, read only when the user opens one rather than with every history row. */
    suspend fun webhookRecordPayload(id: Long): String? = dao.getWebhookRecord(id)?.payload

    /**
     * The list's items, or just the ones a review queued. Read fresh from the database rather
     * than taken from the screen, so the payload reflects what is stored; ids that have since
     * been deleted simply drop out, and stored order is kept either way.
     */
    private suspend fun scopedItems(listId: Long, queueIds: List<Long>?): List<ListItemEntity> {
        val items = dao.getItems(listId)
        if (queueIds == null) return items
        val queued = queueIds.toSet()
        return items.filter { it.id in queued }
    }

    /**
     * Queues an acknowledgeable result behind anything the user has not dealt with yet.
     *
     * Every delivery that was actually attempted gets its own notice: two of them for one list in
     * one moment means two things really happened, and collapsing them would hide one. Results
     * where *nothing* was attempted collapse per list instead, because one reason for a quiet
     * round — the webhook being switched off, nothing to send, nothing configured to send it —
     * repeated across a split round is one thing to say, not one per group.
     *
     * Returns what it built, collapsed or not, so a caller that also has to file the result away
     * describes it in exactly the words the user was given.
     */
    private fun announce(
        listTitle: String,
        event: String,
        url: String,
        kind: ExportKind,
        severalDestinations: Boolean,
        itemCount: Int?,
        outcome: NoticeOutcome,
        httpCode: Int?,
        error: String?,
        location: String? = null
    ): WebhookNotice = raise(
        noticeOf(
            listTitle, event, url, kind, severalDestinations,
            itemCount, outcome, httpCode, error, location
        )
    )

    /**
     * The same result, described but not shown.
     *
     * Split out from [announce] for the one outcome that must be recorded without being reported:
     * a delivery the user has just declined. The history still gets it, in exactly the words
     * every other outcome is kept in, but putting a dialog on screen to tell someone what they
     * did a moment ago is noise.
     */
    private fun noticeOf(
        listTitle: String,
        event: String,
        url: String,
        kind: ExportKind,
        severalDestinations: Boolean,
        itemCount: Int?,
        outcome: NoticeOutcome,
        httpCode: Int?,
        error: String?,
        location: String? = null
    ): WebhookNotice = WebhookNotice(
        event = event,
        listTitle = listTitle,
        kind = kind,
        // The address this attempt actually used — a group's Todo, a List's override, or the
        // default — and never a value read from Settings at display time.
        //
        // Null when the round was not going anywhere over the wire. A text export has no host, and
        // a URL sitting anywhere else because it is used by *other* rounds is not this round's
        // destination - showing it on a txt notice names a machine the file never went near.
        host = if (kind == ExportKind.WEBHOOK) webhookHost(url) else null,
        itemCount = itemCount,
        outcome = outcome,
        httpCode = httpCode,
        error = error,
        severalDestinations = severalDestinations,
        at = now(),
        location = location
    )

    /** Queues one for acknowledgement, collapsed against what is already waiting, and returns it. */
    private fun raise(notice: WebhookNotice): WebhookNotice {
        _webhookNotices.update { queued ->
            if (notice.addsTo(queued)) queued + notice else queued
        }
        return notice
    }

    /** Acknowledges the notice on screen, which brings up the next one if there is one. */
    fun dismissWebhookNotice() {
        _webhookNotices.update { it.drop(1) }
    }

    /** Runs [block] with a trimmed title, ignoring blank input. */
    private fun edit(title: String, block: suspend (String) -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchDb { block(clean) }
    }

    private fun launchDb(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun now() = System.currentTimeMillis()
}
