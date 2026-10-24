package com.example.listea.data

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.listea.domain.EXPORT_CHOICE_INHERIT
import com.example.listea.domain.Templates
import java.util.UUID

/**
 * What took rows out of a List, for the note it is left with.
 *
 * Two operations have the same consequence and different causes. A Todo deleted takes its Tasks
 * with it; a Task dropped under a `drop` content policy is replaced, and the Lists that held it
 * lose a membership the user never asked to lose. Both are worth saying, and saying *why* is the
 * whole of what the note is for — a List that reports rows vanishing without a reason is the
 * screen `docs/file-management.md` refuses to show about files.
 *
 * Stored as [key], so a build that does not know a value reads the delete's own wording rather than
 * showing nothing — the same shape and the same reason as [com.example.listea.ContentPolicy].
 */
enum class DissolutionReason(val key: String) {
    /** A Todo was deleted, and these were the memberships its Tasks held. */
    Dissolved("dissolved"),

    /** A Task's content changed under a `drop` policy, so it was replaced. */
    Dropped("dropped");

    companion object {
        fun fromKey(key: String?): DissolutionReason =
            entries.firstOrNull { it.key == key } ?: Dissolved
    }
}

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val completedAt: Long? = null,

    /**
     * Whether this List exports when it becomes complete.
     *
     * **The field is named for the question the switch asks, and its column is not.** The column
     * stays `webhookEnabled` because renaming one needs SQLite 3.25 and minSdk is 24, and this is
     * the case where keeping the old word would have been actively misleading: since v0.5.6 the
     * switch governs *every* kind, not only the POST it was named after, and a reader of
     * `list.webhookEnabled` sitting beside a Txt dropdown would be reading the exact confusion the
     * release exists to remove. A column name is a migration's business; a field name is a reader's.
     *
     * A question about *when*, answered once here. [exportKind] is the question about *how*, and
     * the two are independent: a List that has not chosen a kind still exports on completion, by
     * whatever each of its Todos names.
     */
    @ColumnInfo(name = "webhookEnabled")
    val exportOnCompletion: Boolean = false,

    /**
     * An address that overrides every webhook group this List exports.
     *
     * Empty is the ordinary state: a List follows each Todo's own address, because a List is a
     * selection of Tasks and not a destination. A non-empty value is the List saying *everything
     * from this List goes here*, and it overrides the Todos' addresses for every webhook group of
     * a round — see `planExports`.
     *
     * Until v0.6.2 the column held a copy of the Settings default, seeded at creation and never
     * refreshed, and was read by nothing. That copy was neither a choice nor current, so
     * `MIGRATION_26_27` clears every row; the column is live again under a different rule.
     */
    val webhookUrl: String = "",

    /**
     * The mechanism this List's rounds export by, as `domain/Exports.kt` encodes it.
     *
     * `inherit` until the user chooses, which is the state a List spends its whole life in unless
     * it is asked otherwise — and it means *follow the source Todos*, never *export nothing*. `none`
     * is the word for the latter. See [com.example.listea.domain.decodeExportChoice] for why the two
     * are different words.
     */
    val exportKind: String = EXPORT_CHOICE_INHERIT,

    /** Lightweight record of the last attempt, so the UI can show never sent / success / failed. */
    val lastDeliveryAt: Long? = null,
    val lastDeliveryStatus: String? = null,
    val lastDeliveryCode: Int? = null,
    val lastDeliveryError: String? = null,

    /**
     * How many memberships the last dissolution took out of this List, and when.
     *
     * Deliberately the same shape as the delivery quartet above — a record of *what happened last*,
     * not an audit log, so a second dissolution replaces the first note. A List whose member Task
     * was deleted has to be able to say so: the alternative is that rows simply disappear, and the
     * user is left with a List that is quietly shorter than it was, which the project's other
     * destructive operation (`docs/file-management.md`) refuses to do to files.
     *
     * Both null means nothing has ever been dissolved out of this List.
     */
    val lastDissolvedCount: Int? = null,
    val lastDissolvedAt: Long? = null,

    /**
     * Which of the two operations took those rows out.
     *
     * The note's sentence names a cause — *"removed when a Todo was deleted"* — and v0.4.0 step 2b
     * gave a second operation the same consequence: a Task dropped under a `drop` content policy
     * leaves the Lists that held it, and they lose rows just as quietly. Without this column the
     * sentence would have to lie about one of them, which is the failure the note exists to prevent.
     *
     * Stored as [DissolutionReason.key], non-null with the delete's own key as the default, because
     * that is the only cause a `lists` row could have been written with before 2b existed — so the
     * notes already on a device keep reading exactly as they did, and the read needs no fallback.
     * Unlike the two columns beside it this one is *not* cleared when a List moves on: nothing reads
     * it unless a count is standing next to it, and a stale value that nothing can reach is not
     * worth a write on the path that adds rows.
     */
    val lastDissolvedReason: String = DissolutionReason.Dissolved.key,

    /**
     * Folder link. Null on manually created lists. [sourceRootUri] is the persisted SAF root tree
     * URI (the grant we actually hold) and [sourceRelativePath] is the display-name path beneath
     * it, "" for the root folder itself. Stored this way so the link survives restarts without
     * depending on a raw filesystem path or on a child document URI staying resolvable.
     */
    val sourceRootUri: String? = null,
    val sourceRelativePath: String? = null,

    /**
     * Where swipe Review should resume. A stable item id, never an index: re-sync, missing-item
     * cleanup and manual deletes all shift positions, and a stale id simply falls back.
     */
    val reviewCurrentItemId: Long? = null,

    /**
     * The Task Review template this List is judged under, as a versioned reference.
     *
     * Two columns rather than one `id@version` token so a query can range over versions of one
     * lineage without parsing text. Stored per List rather than looked up, because a completed
     * review has to stay interpretable after the template it was made under has moved on.
     *
     * The defaults are what makes this invisible on upgrade: a List written before templates
     * existed resolves to the media workflow it was always going to get. The `@ColumnInfo` values
     * repeat that as SQL, which is not redundant — SQLite cannot add a `NOT NULL` column without a
     * default, and Room compares the declared default against the real one at open time.
     */
    @ColumnInfo(defaultValue = "'builtin.media.rapid-review'")
    val taskReviewTemplateId: String = Templates.MediaRapidReview.ref.templateId.value,
    @ColumnInfo(defaultValue = "1")
    val taskReviewTemplateVersion: Int = Templates.MediaRapidReview.ref.version
)

@Entity(
    tableName = "list_items",
    foreignKeys = [
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"]
        )
    ],
    indices = [Index("listId"), Index("rootRelativePath"), Index("taskId")]
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * What this item is called outside Listea — on the webhook and in whatever the webhook feeds.
     *
     * Assigned once, when the row is created, and never rewritten. [id] stays what it always was:
     * a local handle for foreign keys, resume positions and queue lookups, which is all a
     * sequence number is any good for. See [newItemPublicId] for what this is made of and why the
     * two are not the same thing.
     */
    val publicId: String,
    val listId: Long,

    /**
     * The Task this row is a membership of. This row is the *containment*; the Task is the thing.
     *
     * A ListEntry says a Task is in a List, at a position, and nothing else — which is what lets a
     * file reached from two Lists be one Task with two memberships rather than two rival copies of
     * one decision, all the way down to the schema instead of only in the queries.
     *
     * A foreign key with the default `NO ACTION` rather than `CASCADE`, on purpose. Deleting a
     * List cascades its memberships away, but a Task is not a child of any List and must not
     * disappear with one: the decision about a file outlives the List that happened to show it,
     * which is exactly the property `file_review_state` was introduced to get. Ordering is
     * explicit in the DAO — the membership goes, then whatever may follow it — so getting it
     * wrong fails loudly instead of silently taking a Task with it.
     *
     * There is no Kotlin default. A row whose Task was never minted cannot be stored, because the
     * constraint above will not accept it, and an empty default would only move that failure from
     * the compiler to the first insert.
     */
    val taskId: String,
    val title: String,
    /**
     * Not where completion is stored any more — this field is a slot the query fills.
     *
     * What is true of an item is true of its Task. Every query that loads these rows resolves it
     * from [TaskStateEntity] and lands it here with `ts.isCompleted AS isCompleted`, so a caller
     * reading `row.isCompleted` still gets the right answer and the screens did not have to learn a
     * new shape. That is the whole reason the field kept its name: the projection is the seam, and
     * it is the only place the two models touch.
     *
     * The column underneath it is dead. A query that reads the row's own column — `SELECT *`, as
     * [ListsDao.getSourceItems] does — fills this slot with whatever an older version last wrote,
     * or with its default, and nothing in this app writes that column now. Treat any value read
     * without the join as stale, because it is.
     *
     * Kept rather than dropped for the reason a migration never trades away the only remaining copy
     * of somebody's work, and it goes in a later release once no reader is provable — which is a
     * claim the tests exist to keep honest rather than to assume.
     */
    val isCompleted: Boolean = false,
    val sortOrder: Int,
    val createdAt: Long,

    /** Source file identity for folder-backed items. Null on manually added items. */
    val sourceUri: String? = null,
    val sourceRelativePath: String? = null,

    /**
     * The same file's path from the SAF root rather than from this list's folder: what the file is
     * named by everything downstream of this row.
     *
     * Denormalised on purpose. It is derivable — the owning list's folder plus [sourceRelativePath]
     * — but a list's folder link never changes after creation, so it cannot drift, and having it
     * on the row is what lets every query reach the file's Asset, and through it the Task, without
     * concatenating paths in SQL. Null for manual items, which have no file.
     */
    val rootRelativePath: String? = null,

    /**
     * Set by a re-sync when the source file is gone. The item and its review state are kept:
     * external tools move and re-sync files, and a completed review is worth more than the
     * current folder state.
     */
    val sourceMissing: Boolean = false,

    /**
     * What the source file measured at the last scan of the linked folder, cached here so the
     * Items header can filter and sort on it without touching the provider.
     *
     * Cached rather than authoritative, and null-tolerant on purpose. A list *is* a snapshot of a
     * folder — that is what the whole freshness machinery exists to reconcile — so a measurement
     * taken with the snapshot is the honest thing to hold. Null means no scan has recorded one
     * yet, and by the unknown rule (see FileArrange.kt) an unmeasured item is never filtered out
     * and never claims a position it has not earned in a sort.
     *
     * Refreshed by every scan of the linked folder, including the read-only freshness check, so a
     * list created before these existed fills itself in the first time its page is opened.
     */
    val sourceSizeBytes: Long? = null,
    val sourceModifiedAt: Long? = null,

    val isFavorite: Boolean = false,

    /** The slot above, in [encodeActionIds]' encoding. Both are dead. See [selections]. */
    val customActions: String = "",

    /**
     * A slot the query fills, exactly as [isCompleted] is: a button is a decision about a file, and
     * a decision about a file belongs to the Task–Asset pair the file is under.
     *
     * The query folds the Task's pairs in — `GROUP_CONCAT(tas.selections)` over every Asset the row's
     * Task holds — so a List row over a Task of several files reads the *union* of what was marked on
     * them. That is the same answer [unionOf] gives in Kotlin, reached in SQL because a row that
     * re-emits when any pair changes is worth more here than one that reads a single row.
     * `GROUP_CONCAT` is a union rather than a concatenation because [decodeActionIds] splits and
     * de-duplicates on the way out.
     *
     * [encodeActionIds]' encoding, which is what makes that fold legible — one format for one thing
     * in this schema, not two. It is the *only* selection slot on this row since v0.5.4, and the two
     * columns above are what it succeeds: a boolean and a set for one answer, where `★` is a button
     * like any other. See [TaskStateEntity.isFavorite] for the older pair these were themselves
     * projected from.
     *
     * **The two columns above stay, exactly as [AssetStateEntity.selections] says of its own.** A
     * dropped column needs SQLite 3.35 and this app ships minSdk 24, so the alternative is rebuilding
     * the table this List page reads on every row for no reader's benefit — and they are the only copy
     * of a mark that exists if this column is ever found to be wrong. The 23 → 24 step adds this one
     * beside them and writes neither again.
     */
    @ColumnInfo(defaultValue = "''")
    val selections: String = ""
)

/**
 * A file Listea has been asked to review, and the handle a folder review holds it by.
 *
 * **This table no longer holds a decision.** The job it was built for — one place a decision about
 * a file could live, so a List over a folder and a Quick Review over that same folder could never
 * disagree — is [TaskStateEntity]'s now, reached through [AssetEntity] by the very identity below.
 * The decision columns on the row are dead; the *fields* are still read, because the queries that
 * resolve a folder review project the Task's state into them — `ts.isCompleted` and the pair fold —
 * and that projection is the seam the screens read across. v0.5.4 added one more slot and retired no
 * column: see [FileReviewStateEntity.selections].
 *
 * What is left is registration: which files a review has a relationship with. The browser is given
 * one tick per registered file by [ListsDao.observeFileCompletion] — an empty tick for a file
 * nobody has judged yet, rather than no tick at all — and [ListsDao.observeFileStates] resolves the
 * same row set into a review queue, including the files that are still undecided. That row *set* is
 * the thing being preserved, which is why those queries joined out to the decision rather than
 * re-driving themselves from `assets`.
 *
 * Identity is [rootUri] plus [relativePath] — the SAF grant the file is under, and where it sits
 * beneath it — because that is the only name for a file that survives everything Listea does to
 * it. Row ids, list membership and document URIs all come and go; the path under the root does
 * not. A unique index enforces one row per file.
 *
 * [id] exists so a resolved file has a stable handle for a review queue to hold on to, which a
 * folder-resolved item has no [ListItemEntity] to borrow one from. Rows are created as files are
 * resolved for review — idempotently, and asserting nothing about them.
 */
@Entity(
    tableName = "file_review_state",
    indices = [Index(value = ["rootUri", "relativePath"], unique = true)]
)
data class FileReviewStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The webhook-facing name for this file's registration, exactly as [ListItemEntity.publicId]
     * is for a list row — and the reason both tables carry one. A folder Quick Review has no list
     * rows to send, so it sends these; before they existed the two tables' sequence numbers went
     * out under the same field name and a receiver had no way to tell which space an id came from.
     *
     * A receiver is no longer handed this column, though: the name is read off `legacy_item_ids`
     * now, which holds a copy of it against the Asset. The copy is not a new scheme — it is this
     * value, moved somewhere that survives the registration table — and it is written by
     * [ListsDao.registerFiles] as files arrive.
     */
    val publicId: String,
    val rootUri: String,
    val relativePath: String,

    /**
     * Slots the query fills. See the class doc: completion lives on the Task and a mark on the
     * Task's pair with this file, and the queries that resolve a folder review project both in
     * here — `ts.isCompleted`, and the pairs folded the way [ListItemEntity.selections] describes.
     */
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,

    /** The two slots above, the second in [encodeActionIds]' encoding. Both are dead. */
    val customActions: String = "",

    /**
     * The same slot, folded the way [ListItemEntity.selections] describes.
     *
     * The two columns above stay for [ListItemEntity.customActions]' reason — dropping one needs
     * SQLite 3.35 and this app ships minSdk 24, so it would be a rebuild of a table the folder
     * browser reads for no reader's benefit, and they are the only copy of a mark an upgrade left
     * behind.
     */
    @ColumnInfo(defaultValue = "''")
    val selections: String = "",

    /**
     * The presence flag, as the columns above it are decisions: a slot a query fills, not a column
     * anything reads.
     *
     * It is projected from [AssetEntity.sourceMissing] now — see [ListsDao.observeFileStates] — and
     * the value sitting in the table is whatever the migration left there. Nothing has written it
     * since, because presence is a fact about the file and the Asset is where the file is.
     *
     * The decision is kept rather than erased when a file goes, for the reason it always was: a
     * completed review is worth more than the current state of a folder, and a List whose files
     * were deleted must not silently become incomplete again. That is a rule about the flag, not
     * about which table holds it.
     */
    val sourceMissing: Boolean = false,

    /** When this row was last written. Nothing branches on it; it exists to date a registration. */
    val updatedAt: Long
)

/** One file's checked state, for the browser rows. Query result, not a table. */
data class FileCompletion(val relativePath: String, val isCompleted: Boolean)

/**
 * Which Task a registered file is of. Query result, not a table.
 *
 * The registration row has no such column, and giving it one would mean a migration for a value
 * that is already one join away — see §6's note that registration is not identity. It is read the
 * way the decision columns are read: from the `assets` row that names the same file.
 */
data class FileTaskId(val relativePath: String, val taskId: String)

/**
 * One Todo's claim on one path under one grant, through one of its Tasks. Query result, not a
 * table — see [ListsDao.observeFileClaims].
 *
 * [relativePath] is the thing the browser can show a row for, and [groupPath] is the thing the Task
 * was actually made over. They are equal exactly when the row *is* that Task — a file-as-Task Task's
 * own file, a folder-as-Task Task's own folder — and unequal when the row is one of the files such
 * a Task covers. That single comparison is the whole of the distinction the browser has to draw,
 * and it is deliberately left to Kotlin rather than projected as a flag: one home, one unit test.
 *
 * No `rootUri` here because the query is already scoped to one, and no `Task` count because the
 * user acts on the Todo.
 */
data class FileClaim(
    val relativePath: String,
    val groupPath: String,
    val sourceId: String,
    val sourceName: String,
    val taskId: String,
    val taskName: String
)

/**
 * One Task over a shared file, with the Todo that owns it and what that Todo decided. Query result,
 * not a table — see [ListsDao.getSharedFileStates].
 *
 * [sourceName] and [taskName] are the labels to show rather than the ids to act on; [taskId] is
 * what a caller that means to open the Task needs, and [sourceId] is what it needs to open the
 * Todo. The two decision columns are the same pair [ReviewDecisions] carries, still in storage
 * form — an id list in [selections], not labels — for the reason that type gives.
 *
 * [isOwnTask] is the capacity, and it is the same fact [FileClaim] carries: true when this Todo's
 * Task *is* this file, false when the Task is over a folder and this file is one of the files it
 * covers. The two are not the same claim about a file — one is an intention about it, the other is
 * an intention about a folder that happens to reach it — and a drill-in that asserted the first for
 * both sent the user looking for a Task that was not there.
 */
data class SharedFileState(
    val sourceId: String,
    val sourceName: String,
    val taskId: String,
    val taskName: String,
    val isOwnTask: Boolean,
    val isCompleted: Boolean,
    val selections: String,

    /**
     * The control the Task was imported under, so this row's tags can be *named* rather than
     * reported as bare ids.
     *
     * The same raw column [ReviewItem.assetControls] carries and for the same reason: a row that
     * says a Task carries `custom1` is unreadable on screen, and what `custom1` is called is
     * whatever the control governing that Task says it is.
     */
    val assetControls: String = ""
)

/**
 * Every decision a review can hold about one item, apart from where they are stored.
 *
 * Exists so the screens can stop caring whether they are looking at a List's row or a folder's
 * file. Both produce one of these, and the action bar, the Info sheet and the webhook all read it
 * the same way.
 *
 * [selections] holds ids, not labels and not wire values. What an id is called and what it is sent
 * as belong to the template version the id came from, resolved at the moment they are needed —
 * which is what lets a button be renamed, or its wire value changed, without rewriting a row.
 *
 * An id that no longer matches any button is kept rather than dropped. Removing a button is a
 * decision about the bar, not about the files somebody has already marked, and a decision this app
 * has recorded is not something it discards behind the user's back.
 */
data class ReviewDecisions(
    val isCompleted: Boolean = false,
    val selections: Set<String> = emptySet()
)

/**
 * What a **Task–Asset pair** carries: the instance of that Task's asset control on that one file.
 *
 * The other half of [ReviewDecisions], and the half that is the template's. The buttons are what a
 * control *is*, so they are recorded where the control was applied — on the pair — while completion
 * is the Task's own and is recorded once for the Task.
 *
 * A pair row — a catalog row, a List's row, a folder's file-state, a shared-file state — carries
 * both halves and so still speaks [ReviewDecisions]. The split is between the two **state tables**,
 * [com.example.listea.data.TaskStateEntity] and
 * [com.example.listea.data.AssetStateEntity], and not between the row types that read them.
 *
 * Same encoding and same "an id no longer offered is kept" rule as [ReviewDecisions]: this is the
 * same record, filed under a narrower key.
 *
 * **One set and not a boolean beside one.** `★` used to be a field of its own here, which made it
 * the one button a template could not choose, rename or drop; since v0.5.4 it is the button with the
 * id [com.example.listea.domain.Templates.FAVOURITE_BUTTON_ID], carrying that same string as its
 * record value, and it is selected like any other. The two columns this replaces were merged into
 * one by the 23 → 24 step, and [encodeActionIds] is where the merge is defined.
 */
data class AssetDecisions(
    val selections: Set<String> = emptySet()
)

/**
 * The pair's half of a row that carries both, for the callers that fold a Task rather than a file.
 *
 * A conversion and not a second opinion: the value is the same one, read off the same row, and this
 * only says which half the reader is about to use. The webhook's one entry per Task is the caller
 * that needs it — a payload names no file, so it reports everything the Task carries while the row
 * it is built from still speaks [ReviewDecisions].
 */
val ReviewDecisions.assetDecisions: AssetDecisions
    get() = AssetDecisions(selections = selections)

/**
 * A Task's pair records, together.
 *
 * The answer for a surface with no single file on screen — the badge row under a Task's name, a
 * catalog card, a List's row, the webhook's one entry per Task — and the only honest one: a Task
 * that presents three files and was marked on two of them is marked with what those two carry.
 *
 * Every id any pair holds, because ids are what a pair holds and a union loses none of them. There
 * used to be a second rule here for `★` — "if any pair carries it" — because it was one action with
 * one boolean behind it; with one set there is nothing for it to be a rule about.
 */
fun unionOf(decisions: List<AssetDecisions>): AssetDecisions =
    AssetDecisions(selections = decisions.flatMapTo(mutableSetOf()) { it.selections })

/**
 * How a set of action ids is stored in one column.
 *
 * Sorted, so the same set always writes the same string: storage that varies with the order the
 * user happened to tap things is storage that looks changed when nothing has.
 *
 * Comma-separated, which is safe because an id is generated rather than typed — see [newActionId].
 * Blanks are dropped going in and coming out, so a row written by hand decodes to something sane
 * rather than to a set containing an empty string.
 */
fun encodeActionIds(ids: Set<String>): String =
    ids.filter { it.isNotBlank() }.sorted().joinToString(",")

fun decodeActionIds(text: String): Set<String> =
    text.split(',').filter { it.isNotBlank() }.toSet()

/**
 * An id for a newly created action.
 *
 * Generated rather than derived from the name, because the name is the one thing about an action
 * that is expected to change: an id taken from "Save" would either have to be rewritten across
 * every item the day it became "Keep", or stop matching the thing it names. It is never shown.
 *
 * `custom1` and `custom2` are reserved — they are what the two original slots became — so an item
 * marked before there was any such thing still resolves to the action it was marked with.
 */
fun newActionId(): String =
    "a" + UUID.randomUUID().toString().replace("-", "").take(ActionIdChars)

/** Long enough that a collision is not worth thinking about, short enough to read in a log. */
private const val ActionIdChars = 12

/**
 * A file's path from the SAF root: the owning list's folder, then the file's path inside it.
 *
 * The canonical join, kept here because it is what a file's identity is made of — the same string
 * a [FileReviewStateEntity] registers the file under and the one an [AssetEntity] is located by,
 * which is what lets the two be joined at all. Everything that needs to name a file the way the
 * schema does goes through this.
 */
fun rootRelativePathOf(listPath: String, itemPath: String): String =
    if (listPath.isEmpty()) itemPath else "$listPath/$itemPath"

/**
 * The decisions a resolved file row is carrying.
 *
 * Only meaningful on a row that came out of [ListsDao.observeFileStates] or
 * [ListsDao.getFileStates], which fill these fields from the file's Task. On a row read any other
 * way it is reading a dead column, and the answer is whatever an older version left there.
 */
val FileReviewStateEntity.decisions: ReviewDecisions
    get() = ReviewDecisions(isCompleted, decodeActionIds(selections))

/**
 * The decisions a resolved membership row is carrying.
 *
 * Same caveat as [FileReviewStateEntity.decisions]: meaningful on a row that came out of a query
 * that joined the Task's state in, stale on one that did not.
 */
val ListItemEntity.decisions: ReviewDecisions
    get() = ReviewDecisions(isCompleted, decodeActionIds(selections))

/**
 * Where a decision is written when the user makes one.
 *
 * Two cases only, and they are not symmetric: [File] names content, and every List and every folder
 * review that reaches that content shares it. [ManualItem] names a membership row instead, because
 * a manually typed entry has no file to be a decision about — which is a difference in how the
 * Task is *found*, not in where its state ends up. Both write to the Task.
 */
sealed interface DecisionTarget {
    data class File(val rootUri: String, val relativePath: String) : DecisionTarget
    data class ManualItem(val listId: Long, val itemId: Long) : DecisionTarget
}

/**
 * One card in a review queue, whatever resolved it.
 *
 * The type both review modes speak. A List resolves its membership rows into these; a folder
 * resolves its files into these; from there the queue mechanics, the gestures, the action bar and
 * the media renderer cannot tell the two apart, which is exactly the decoupling — Quick Review
 * stopped being a filtered view of a List and became a peer of it.
 *
 * [id] is only an identity *within a queue*: a list row's id for List review, a file state row's
 * id for a folder review. It is never a cross-mode identity, and nothing persists it except a
 * List's own resume position, which only List review has. What is shared across modes is
 * [target], and that is a path, not an id.
 *
 * [publicId] is the opposite of that in every respect: it comes off the stored row either mode
 * resolved, it means the same thing across both, and it is the only one of the two a receiver
 * ever sees. Carried here because a folder round's payload is built from these items and has no
 * list rows to read it off instead.
 */
data class ReviewItem(
    val id: Long,

    /**
     * The Task this card is of, which is what a review session tracks its position by.
     *
     * A session identity and nothing more: it is not persisted, not shown and not sent. It is here
     * because a position held by [id] cannot survive the thing the card is *about* — [id] is a list
     * row's number for List review and a registration's number for a folder one, so the two modes
     * disagree about what identifies the same file, and neither matches what a judgment is recorded
     * against. A Task does, and the file on screen is that Task's Asset.
     */
    val taskId: String,
    val publicId: String,
    val title: String,
    val sourceUri: String?,

    /** For display: relative to whatever the queue is scoped to, not to the root. */
    val relativePath: String?,
    val sourceMissing: Boolean,
    val target: DecisionTarget,

    /**
     * What the file measures, for the filter and the sort that decide which items a page shows
     * and which of them a review round walks.
     *
     * Carried on the item rather than looked up beside it because both queues can answer it from
     * what they already have — a folder review off the listing it just read, a list review off the
     * row it resolved — and because a filter that has to join against a second structure is a
     * filter that will eventually be given the wrong one. Null is "not measured", never zero: see
     * the unknown rule in FileArrange.kt.
     */
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,

    /**
     * The `list_items` row this came from, for the operations that are membership rather than
     * review — renaming and removing an entry from a List. Null for a folder-resolved file, which
     * belongs to no List and has no membership to edit.
     */
    val rowId: Long? = null,

    /**
     * Whether the Task is complete, and the whole of what an item records about its Task.
     *
     * It used to be [ReviewDecisions], carrying `★` and the tags beside it. Those are the pairs'
     * since v0.5.1 — see [ReviewAsset.decisions] — because they are what the template records and
     * the template is applied to a file. Completion is the outer band's, the Task's own, and
     * checking it is one judgment about one Task however many files that Task presents.
     */
    val isCompleted: Boolean = false,

    /**
     * The Assets this Task is presented through, resolved once when the queue was built and carried
     * here so the viewer boundary reads a collection rather than inventing one from the one-Asset
     * shape. Empty for a Task with nothing to present — a manual item — and one-per-Asset for a
     * multi-Asset Task.
     */
    val assets: List<ReviewAsset> = emptyList(),

    /**
     * The Task's position in the source tree, as `task_imports.groupPath`. Null for a Task that is
     * not catalogued — a legacy Task nobody has imported, or a manual item.
     */
    val rootRelativePath: String? = null,

    /**
     * Why this Task cannot be review-submitted, or null when it can be. Derived at queue build time
     * and carried rather than re-derived on the surface, so a list screen and a review surface agree
     * about what is blocked and why.
     */
    val reviewBlocked: ReviewBlock? = null,

    /**
     * The asset control this Task was imported under, as `task_imports.assetControls` holds it.
     *
     * Raw and undecoded, deliberately. What the middle band needs is *one file's* answer, and the
     * file is a property of the page on screen rather than of the Task — so the resolution that
     * turns this string into chips is [TemplateResolver.controlFor], performed per page, at the one
     * place that knows which page is showing. A card carrying a resolved list would have had to pick
     * a file while building the queue, which is one frame before the user says which one they are
     * looking at.
     *
     * `""` means the Task has no record, and reads as no chips at all. See
     * [TaskImportEntity.assetControls].
     *
     * Bounded to the Task's own row, like [rootRelativePath] and for the same reason: a Task a
     * payload names must be answerable without walking back to the Todo that imported it.
     */
    val assetControls: String = "",

    /**
     * The add-on this Task was imported with, as `task_imports.addOn` holds it.
     *
     * Raw and undecoded, like [assetControls] and for the same reason: the reference is a fact about
     * the import, and resolving it into questions is [AddOnResolver]'s answer at the moment the
     * questionnaire is drawn. `""` means no add-on and therefore no extra page. A Task no Todo owns
     * has no import row and defaults to empty, which is the honest reading.
     */
    val addOn: String = "",

    /**
     * The Task Review this Task was imported under, as `task_imports.taskReview` holds it.
     *
     * Raw and undecoded, like [assetControls]: the reference is a fact about the import, and the
     * definition it names is resolved once the session opens. `""` means the review was never
     * stamped — every Task imported before v0.8.1 — and reads as the built-in rapid review, which
     * is what those Tasks were in fact judged by.
     */
    val taskReview: String = ""
)

/**
 * A Task's pair records, together: what a surface with no file on screen draws.
 *
 * The badge row under a Task's name, a List's own row, a catalog card, the webhook's one entry per
 * Task. Each of those has to be able to name anything the Task can carry — a tag set on a video
 * still has to be legible while a photograph is on screen — and each of them is answered by the
 * same fold, taken from the same place: the pairs the item is already carrying.
 *
 * Derived rather than stored, and derived *here*, so there is exactly one way of asking the
 * question. A caller that reached for a Task-level column instead would be reaching for a fact
 * v0.5.1 deleted.
 *
 * Empty for a Task with no Asset, which is not a gap: a manual item has no pair, so it has no
 * record, and the middle band is not drawn for it at all.
 */
val ReviewItem.taskDecisions: AssetDecisions
    get() = unionOf(assets.map { it.decisions })

/**
 * A stored row resolved for review, with its decisions taken from wherever they actually live.
 *
 * Its decisions come off the row's fields, but they were put there by the query that loaded it:
 * the queries that resolve items join the Task's state in and land it on those columns, so this
 * function is one of the places that assumption is visible rather than one of the places it is
 * made. Nothing here reads a decision out of the `list_items` table.
 */
fun ListItemEntity.toReviewItem(
    rootUri: String?,
    assetControls: String = "",
    addOn: String = "",
    taskReview: String = ""
): ReviewItem = ReviewItem(
    id = id,
    // The row's own column, not a projection: `list_items.taskId` is where membership records
    // which Task it is of, and it is the one column of this row that a decision is actually
    // about.
    taskId = taskId,
    publicId = publicId,
    title = title,
    sourceUri = sourceUri,
    relativePath = sourceRelativePath,
    sourceMissing = sourceMissing,
    target = if (rootUri != null && rootRelativePath != null) {
        DecisionTarget.File(rootUri, rootRelativePath)
    } else {
        DecisionTarget.ManualItem(listId, id)
    },
    isCompleted = decisions.isCompleted,
    sizeBytes = sourceSizeBytes,
    lastModified = sourceModifiedAt,
    rowId = id,
    // A parameter rather than a projection on this row, which is the one place this function
    // differs from `decisions` above it. A membership row cannot answer it: the control is the
    // Task's and reaches this entity only from `task_imports`, which the list query does not join,
    // so the caller reads it beside and hands it in. Defaulted, because a manual item has no
    // import row at all and a Task with no record is the honest answer for it.
    assetControls = assetControls,
    // The same parameter contract, for the same reason: the add-on is the Task's and reaches this
    // entity only from `task_imports`.
    addOn = addOn,
    // And the same again for the review: a Task is judged by the template its own import stamped,
    // not by whatever the Todo or the List says today.
    taskReview = taskReview
)

/**
 * One thing the action bar can offer an item: a button of a template version, by id.
 *
 * **One kind, and the id is the whole of it.** Until v0.5.4 this was a sealed pair — a `Favourite`
 * object that answered for itself and a `Custom(id)` that asked the settings page what it meant —
 * and the split was the model saying that one of its two buttons was special. A template's buttons
 * are a list now and `★` is one of them, so what the bar offers is exactly the ids of that list, and
 * everything else about a button (what it is called, what it sends, what it records) is answered by
 * the version the id came from.
 *
 * The order the bar draws and the payload emits comes from that version's button list, not from
 * here: it is deterministic and independent of the order the user toggled them, which is what a
 * receiver needs from the `actions` array.
 */
@Immutable
data class ItemAction(val id: String) {

    /**
     * Whether [decisions] currently carries this action.
     *
     * [AssetDecisions] and not [ReviewDecisions], since v0.5.1. These are the actions a *control*
     * offers and a *pair* records, and both of them live on the file. A row that reports a Task's
     * whole answer — a List's row, a catalog card — hands in [unionOf] its pairs, which is the same
     * type and the same question.
     */
    fun isSetOn(decisions: AssetDecisions): Boolean = id in decisions.selections

    /** The same decisions with [enabled] applied, for a write that replaces the whole set. */
    fun setOn(decisions: AssetDecisions, enabled: Boolean): AssetDecisions = decisions.copy(
        selections = if (enabled) decisions.selections + id else decisions.selections - id
    )

    companion object {
        /**
         * `★`, which is a button of the built-in like any other and only conventionally first.
         *
         * Kept as a name because a handful of surfaces speak about the star *as such* — the chip
         * row's tint, a test that means "the plain on/off button" — and spelling the id out at each
         * of them would be five copies of a promise. It is not a special case in the record: it is
         * selected, stored and sent exactly as a minted button is.
         */
        val Favourite = ItemAction(com.example.listea.domain.Templates.FAVOURITE_BUTTON_ID)
    }
}

/**
 * Result of comparing a folder-backed list against a fresh scan of its source folder.
 * Manual items take no part in this.
 */
data class FolderDiff(
    val addedPaths: List<String>,
    val missingPaths: List<String>,
    val restoredPaths: List<String>,
    val unchangedCount: Int,
    val newlyMissingCount: Int
) {
    /** Only a real delta is worth writing; an item that was already missing and still is is not. */
    val hasChanges: Boolean
        get() = addedPaths.isNotEmpty() || newlyMissingCount > 0 || restoredPaths.isNotEmpty()
}

/**
 * A folder-backed list plus its item counts, keyed by the folder it owns. One query returns every
 * scope under a SAF root, so the Folder screen never queries per visible row.
 * [relativePath] is nullable only to match the column; the query filters nulls out.
 */
data class FolderListScope(
    val id: Long,
    val title: String,
    val relativePath: String?,
    val completedAt: Long?,
    val totalItems: Int,
    val completedItems: Int,

    /**
     * Just enough of the export state to label a folder card. A delivery always records its
     * timestamp and status together, so a null [lastDeliveryStatus] means nothing has been exported.
     *
     * [exportKind] is the raw stored word, for the same reason [TaskExportSource.exportKind] is:
     * this is the `data` package, and `domain/Exports.kt` is where a word becomes a meaning.
     */
    val exportOnCompletion: Boolean,
    val exportKind: String,
    val lastDeliveryStatus: String?,
    val lastDeliveryCode: Int?
) {
    val isComplete: Boolean get() = completedAt != null
}

/**
 * A source-backed item of a folder-backed list, reduced to what subtree progress needs.
 * [relativePath] is relative to the owning list's linked folder, not to the SAF root.
 * Missing sources are included on purpose: they still belong to their subtree and still count.
 */
data class SourceItemRef(
    val listId: Long,
    val relativePath: String?,
    val isCompleted: Boolean
)

/**
 * One webhook delivery, kept with the body it carried.
 *
 * Written for every delivery that reached a verdict, whatever the verdict was: sent, failed,
 * refused by a switched-off webhook, or declined by the user when it asked. It was once a record
 * of failures only, and that turned out to be the wrong shape — a history that holds only the
 * things that went wrong cannot answer "did that round actually go out?", which is the question
 * people actually have. The one thing still not kept is a payload with no items in it: there
 * would be nothing to resend, and nothing was attempted.
 *
 * Deliberately standalone, with no foreign key to a list: the whole point is that the payload
 * outlives whatever happened to it, and deleting the list it came from must not quietly take the
 * record with it. [payload] is the exact JSON that was posted or would have been, so a resend
 * sends *that* rather than something rebuilt out of state that has since moved on.
 */
@Entity(tableName = "webhook_history")
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val listTitle: String,
    val itemCount: Int,
    val payload: String,

    /** When this record last reached a verdict. A resend moves it; that is what makes it current. */
    val at: Long,

    /** A [com.example.listea.NoticeOutcome] name. Stored as text so an old row survives new cases. */
    val outcome: String,

    /** How it went, in the same words the dialog used at the time. */
    val detail: String,

    /**
     * The mechanism this attempt used, as `domain/Exports.kt` encodes it.
     *
     * **Declared last on purpose**, so a fresh database's column order matches a migrated one's:
     * `ALTER TABLE ADD COLUMN` appends, and this project has already been bitten once by a
     * `SELECT *` reader seeing a different order on the two (`ListsDao.getSourceItems`). Room
     * compares columns as a map and would not care; a human reading a `SELECT *` would.
     *
     * The table is named `webhook_history` and its rows are no longer all webhooks. The user chose
     * *两种都记* — record both — and recording only POSTs would have made a text export
     * unfalsifiable: the user could not tell whether the file was written, and a List's *Last
     * export* line would have had nothing to say exactly when the effective kind was txt. The table
     * keeps its name for the reason v0.5.4's decision 8 gave about a column: renaming one is a
     * rebuild for a word, and the word is in the migration's history either way.
     */
    val kind: String = com.example.listea.domain.ExportKind.WEBHOOK.key,

    /**
     * The address this attempt posted to, or `""` for a txt export and for a row written before
     * v0.6.2.
     *
     * Stored per row rather than looked up, because one round may split across several addresses
     * and the record has to say which one *this* attempt used. A resend prefills it, and a
     * temporary address typed there is deliberately not written back: the record is a history of
     * what happened, not a second place a Todo's configuration lives.
     *
     * The SQL default matches `MIGRATION_26_27`'s `ALTER`, so a created and a migrated database
     * agree, and a fixture can insert a legacy-shaped row without naming these columns.
     */
    @ColumnInfo(defaultValue = "''")
    val webhookUrl: String = "",

    /**
     * The delivery this row was part of. Every export of one round shares it.
     *
     * `""` on rows written before v0.6.2. The history reads an empty id as a round of its own,
     * which is what each old row was: rounds did not exist to group them.
     */
    @ColumnInfo(defaultValue = "''")
    val roundId: String = ""
)

/**
 * One row of the webhook history: everything the list needs, and not the payload.
 *
 * A payload is as big as the round it covers, so the history observes this instead and reads the
 * body itself only when the user opens one.
 */
data class ExportRecordSummary(
    val id: Long,
    val event: String,
    val listTitle: String,
    val itemCount: Int,
    val at: Long,
    val outcome: String,
    val detail: String,

    /** Which mechanism made the attempt, so a history row can say what it was without opening it. */
    val kind: String,

    /** The address this attempt used; empty for txt and for rows written before v0.6.2. */
    val webhookUrl: String,

    /** The delivery this row belongs to; empty on legacy rows, which the UI reads as one each. */
    val roundId: String
)

/** One file found by a recursive folder scan, before it becomes a [ListItemEntity]. */
data class ScannedFile(
    val name: String,
    val relativePath: String,
    val uri: String,

    /**
     * What the provider said about the file at scan time, for the Items header to filter and sort
     * on. Null means it would not say, which every filter reads as "no reason to exclude this".
     *
     * Measured during the walk that was happening anyway rather than by a second pass: a list's
     * items span a whole subtree, and asking the provider for a size per row when the header's
     * filter icon is tapped would make that icon the most expensive control on the page — the
     * same reason the folder browser reads these off its listing.
     */
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** One row of the Lists screen: a list plus its item counts. Query result, not a table. */
data class ListSummary(
    val id: Long,
    val title: String,
    val completedAt: Long?,
    val totalItems: Int,
    val completedItems: Int,

    /** Same export fields the Folder cards show, so both screens read identically. */
    val exportOnCompletion: Boolean,
    val exportKind: String,
    val lastDeliveryStatus: String?,
    val lastDeliveryCode: Int?,

    /**
     * What the last dissolution took out of this List, and when — null when nothing has.
     *
     * On the summary as well as the row, because a List quietly getting shorter is precisely the
     * thing the index has to be able to say, and the index reads summaries. Cleared by the List
     * gaining a membership, not by time: see `ListsDao.clearDissolutionNote`.
     */
    val lastDissolvedCount: Int? = null,
    val lastDissolvedAt: Long? = null,

    /** Which operation that was — see [DissolutionReason]. Read only when the count is not null. */
    val lastDissolvedReason: String = DissolutionReason.Dissolved.key
) {
    val isComplete: Boolean get() = completedAt != null
}

/**
 * A list together with its items resolved for review, for the detail screen.
 *
 * [ReviewItem] rather than the stored rows, because a source-backed row does not carry its own
 * decisions any more: the resolution happens once, here, and the screen reads one shape whether
 * it is showing a folder-backed file or a manually typed entry.
 */
data class ListDetail(
    val list: ListEntity,
    val items: List<ReviewItem>
) {
    val completedCount: Int get() = items.count { it.isCompleted }
    val isComplete: Boolean get() = list.completedAt != null
}
