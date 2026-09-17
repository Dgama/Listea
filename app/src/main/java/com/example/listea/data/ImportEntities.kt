package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.listea.ContentPolicy

/**
 * The v14 tables: where a Task came from, and where a Task's content is measured.
 *
 * v0.2 gave the model a Task and an Asset but no story for how either came to exist. A Task was
 * minted lazily, one file at a time, as a side effect of *looking* at a folder — which is why the
 * same folder reached through two Lists, or entered twice, was a question the schema could not
 * answer. These tables answer it: a [SourceEntity] is a registered origin, [TaskImportEntity] is
 * the provenance of one Task under that origin, and the two legacy tables are the compatibility
 * claim sets that name what a v0.1 database already contained.
 *
 * The split is the same one [TaskEntities] makes about the Task itself: a Task has no opinion about
 * where it was found, and that is a *rule* rather than an unfinished edge. `groupPath` and
 * `displayName` are exactly the things a Task must not carry, so they live on a row *about* the
 * import, in the same style as [LegacyItemIdEntity] and [LegacyManualTaskEntity]. A Task with its
 * `task_imports` row deleted is still a Task; it is simply no longer in any catalog.
 */

/**
 * A registered origin from which raw material can be discovered.
 *
 * A Source is a folder, a grouping rule, and the intent behind running that rule over it: the SAF
 * grant the folder sits under, the import root beneath that grant (`''` is the grant root itself),
 * a display name, and the importer that turns the folder's contents into Tasks. It owns no state
 * and no history — those belong to the Tasks it produces — and its identity is [id], which is
 * opaque and not a path.
 *
 * **One folder may back any number of Sources, and each gets its own Tasks.** The same folder read
 * twice is two intentions — two dishes the same market supplies — and a Task reached through one
 * Source is not the Task reached through another, so their state does not have to agree. What were
 * two rows is genuinely two rows; nothing here recognises "the same folder again" and nothing
 * refuses a second run over ground an earlier one covers. A `(rootUri, relativePath)` pair is how a
 * caller *finds* the Sources at a folder, and the answer is a list.
 *
 * The two `lastImport` columns are the durable summary of the most recent run; the detailed result
 * is returned to the caller and shown, then dropped. There is deliberately no import history table:
 * nothing in v0.2.5 reads one, and the Source row plus [TaskImportEntity.lastSeenAt] is the whole
 * provenance reconciliation needs.
 *
 * [keepTree] is the first per-importer option, and it lives here rather than in a preference
 * because it is a fact about *this import*: a refresh has to reproduce the same shape the first run
 * produced, and a setting read at display time could have been changed in between. It is a
 * presentation rule, not an import rule - nothing in [TaskImportEntity.groupPath] is reshaped by
 * it, because that string is the containment key the catalog descends by, and flattening it would
 * break containment for every Task under the Source. It is stored `false` for a folder-as-Task
 * Source, whose Tasks are flat by construction; the importer cannot honour it and no control
 * offers it.
 */
@Entity(
    tableName = "sources",
    indices = [Index(value = ["rootUri", "relativePath"])]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val rootUri: String,
    val relativePath: String,
    val displayName: String,
    val importerKind: String,
    val importerVersion: Int,
    val createdAt: Long,
    val keepTree: Boolean = true,

    /**
     * Whether dissolving this Todo writes its Tasks' decisions into `dissolved_task_state` first.
     *
     * Defaults to `false`, which is the behaviour-preserving value: before v0.4.0 step 2 nothing in
     * the app could delete a Todo at all, and the rule in `docs/settings.md` is that a control added
     * after a feature shipped starts where an untouched install already was. Existing rows are
     * backfilled to `false` by `MIGRATION_17_18` for the same reason, so the Todos already on a
     * device do not begin behaving differently because a column appeared.
     *
     * It is a *default*, not the decision. The delete dialog carries the checkbox and pre-selects it
     * from here, because the consequence — a later Todo at this folder can load these decisions —
     * is legible at the moment of deleting and not weeks earlier when the Todo was created.
     *
     * **Where the default comes from**, which 2a left implicit and nothing wrote: the Todo card's
     * overflow, beside [followUpstream], offers it as a switch. Without a writer the column was a
     * constant, so the dialog's pre-selection could only ever say no however the user had answered
     * the question before — and `docs/settings.md` was already describing it as *stored per Todo*.
     */
    val keepStateOnDelete: Boolean = false,

    /**
     * What a change to one of this Todo's Tasks' Asset sets means — `keep`, `reset` or `drop`.
     *
     * A property of the Todo and not of a run, because it is a standing statement about the
     * material: *"these are my photographs, if one moves start it over"*. The import dialog
     * pre-selects it and the answer given there is written back, so the next run opens on what the
     * user said last time rather than on what Settings happened to default to. Settings' own entry
     * seeds it for new Todos and has no say afterwards.
     *
     * Stored as [ContentPolicy.key], with an unrecognised string reading back as
     * `ContentPolicy.Default` — the same shape [ImporterKind] is stored in, and for the same
     * reason: a value this build does not have is not a reason to refuse to open the Todo.
     *
     * `keep` and the `0`/`false` beside it are the behaviour-preserving values, which
     * `docs/settings.md` requires of anything added after a feature shipped, and `MIGRATION_18_19`
     * backfills both so the Todos already on a device do not start behaving differently because a
     * column appeared.
     */
    val contentPolicy: String = ContentPolicy.Default.key,

    /**
     * Whether new material in this Todo's folder flows into it on its own.
     *
     * Off by default and off for every existing row, per the same rule as above — but the sharper
     * reason is that this is the *only* policy in the app that writes without being asked at the
     * moment it writes. Everything else here is a dialog the user is looking at; this one is a
     * consequence of opening a Todo. Off means opening one still reads nothing and writes nothing,
     * which is what every version before this one did.
     *
     * A Todo that has it on is not thereby *allowed* to run — it is asked to by its owner, and what
     * the run then does to any Task whose content moved is [contentPolicy]'s answer, not this
     * flag's.
     */
    val followUpstream: Boolean = false,

    val lastImportAt: Long? = null,
    val lastImportStatus: String? = null
)

/**
 * The provenance of one Task under one [SourceEntity]: what the importer grouped to make it, what
 * it called it, and when it was last seen.
 *
 * [groupPath] is the root-relative path of the thing the importer grouped — a file's own path for
 * file-as-Task, the child folder's path for folder-as-Task — and it is the one string the catalog's
 * containment algebra (§6) needs to place a Task in the source tree. It is not identity; a path may
 * rename under a Task that keeps its id, and the row is a claim about an import, not the Task.
 *
 * [displayName] is what the *importer* called the thing, and is deliberately distinct from the
 * List-side title on [ListItemEntity] and from any wire value: a Task reached from two Lists may be
 * titled differently in each, and the webhook keeps sending the List's title.
 *
 * The foreign key to [SourceEntity] is NO ACTION rather than CASCADE, because a Source is never
 * deleted in v0.2.5 and the row must outlive whatever later release adds deletion — a provenance
 * row is a fact about how the Task was made, not a child of the Source that made it.
 */
@Entity(
    tableName = "task_imports",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"]
        )
    ],
    indices = [Index(value = ["sourceId", "groupPath"])]
)
data class TaskImportEntity(
    @PrimaryKey val taskId: String,
    val sourceId: String,
    val groupPath: String,
    val displayName: String,
    val importedAt: Long,
    val lastSeenAt: Long
)

/**
 * What one dissolved Todo's Tasks were judged to be, kept so a later Todo at the same folder can
 * load it. The snapshot is the whole reason dissolving is not the same thing as destroying.
 *
 * Written by `ListsDao.deleteTodo` immediately before the Tasks go, and read by
 * `ListsDao.recoverStateFrom` when a new import chooses to load from it. [SourceEntity]'s KDoc and
 * `docs/iterations/v0.4.0-step2-plan.md` carry the design; the three decisions that shaped this
 * table are here.
 *
 * **One row per Task, keyed by the path the Task was made over — `task_imports.groupPath`, never a
 * file path.** [groupPath] borrows that column's name on purpose: a recovery matches a freshly
 * minted Task against the history by `(rootUri, groupPath)`, so that string is the only key that
 * can survive the delete, and the shared word is what makes the join in `recoverStateFrom` read as
 * the same idea rather than a coincidence. It is provenance for reconciling a later import, which
 * `ARCHITECTURE.md` allows an Upstream to retain, and not a domain id — so the invariant that a
 * path is never a Task's identity is untouched.
 *
 * [sourcePath] is the *other* path, and it is not redundant: it is where the dissolved Todo itself
 * stood under the grant, which is what decides **which folder offers this history back**. A
 * recovery belongs to the folder the intention was made at, not to wherever a Task happened to sit.
 *
 * **A history has no `taskId` column, deliberately.** The Tasks it describes are gone, and giving
 * their ids a home here would invite a reader to treat the rows as a resurrection of them. A
 * recovery is a *copy into the new Todo*, so what is stored is the judgment and the path it was
 * about, and nothing that could be mistaken for a live Task.
 *
 * **There is no foreign key to `sources`.** [sourceId] names the whole point: the row exists to
 * outlive the `sources` row it came from, and [sourceName] and [importerKind] are copied rather than
 * joined for the same reason — after the delete there is nothing left to join to, and a picker that
 * could not say *which* history it was offering would be useless.
 */
@Entity(
    tableName = "dissolved_task_state",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["rootUri", "sourcePath"])
    ]
)
data class DissolvedTaskStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val sourceName: String,
    val rootUri: String,
    /** The dissolved Todo's own folder under the grant: which folder offers this history back. */
    val sourcePath: String,
    /** The dissolved Task's `groupPath`: what a later import's Task is matched against. */
    val groupPath: String,
    val importerKind: String,
    val isCompleted: Boolean,
    val isFavorite: Boolean,
    val customActions: String,
    val dissolvedAt: Long
)

/**
 * One List a dissolution would take memberships out of, and how many. Query result, not a table —
 * see `ListsDao.listsHolding`.
 *
 * Read twice for two different jobs: before the delete, so the confirmation can name every List
 * that will lose rows rather than only counting them; and inside the transaction, so each of those
 * Lists can be told afterwards what it lost. Naming them is the point — `docs/file-management.md`
 * sets the precedent that a destructive operation shows *the listing* rather than a total, and a
 * List that quietly gets shorter is the same failure in a different place.
 */
data class ListLosingItems(
    val listId: Long,
    val title: String,
    val itemCount: Int
)

/**
 * What one call to `ListsDao.deleteTodo` actually did. Returned rather than observed, and dropped
 * once shown, like [ImportResult] — no table keeps it.
 *
 * [deletedTaskCount] is what the Todo held at the moment it went, so the report can say *"3 Tasks
 * and their decisions"* without the caller having counted anything first. [lists] is the same
 * listing the confirmation showed, carried through so the result can name the Lists again rather
 * than matching them by id afterwards.
 */
data class DissolutionReport(
    val sourceName: String,
    val deletedTaskCount: Int,
    val lists: List<ListLosingItems>,
    /**
     * Whether the snapshot was taken, which is the checkbox's answer and not something the caller
     * can infer: the screen that showed the dialog may have been recomposed away since, and a result
     * screen that guessed would be guessing about the one part of this operation that is reversible.
     */
    val keptState: Boolean
)

/**
 * One recoverable history at a folder: what a dissolved Todo left behind. Query result, not a
 * table — see `ListsDao.dissolvedHistoriesAt`.
 *
 * Grouped by `sourceId`, which is what makes "load from one history at a time" expressible: the
 * picker offers these, the user chooses exactly one, and its rows are what a recovery copies from.
 * [taskCount] counts the judgments held, not Tasks that still exist — there are none — and
 * [dissolvedAt] is the newest row's, since a Todo is dissolved in one transaction.
 */
data class DissolvedHistory(
    val sourceId: String,
    val sourceName: String,
    val importerKind: String,
    val taskCount: Int,
    val dissolvedAt: Long
)

/**
 * One new Task a history has something to say about, and what it has to say. Query result, not a
 * table — see `ListsDao.recoveryMatches`.
 *
 * Carries `taskId` because the write that follows is addressed by id, and the three state columns
 * because they are the whole of what crosses the boundary: a recovery moves judgments, never Tasks.
 */
data class RecoveredTaskState(
    val taskId: String,
    val isCompleted: Boolean,
    val isFavorite: Boolean,
    val customActions: String
)

/**
 * The Tasks that exist because someone once looked at a folder, before import existed.
 *
 * A closed claim set, in the same sense [LegacyManualTaskEntity] is: every file-backed Task in a
 * v0.1 database was minted by a browse, not by an import, and modelling that as a [SourceEntity]
 * rooted at `''` would occupy `(rootUri, '')` — the one key a user needs to import their whole
 * grant. So the legacy set is *named* rather than *personified*: it has no importer, no root, no
 * status, and it can only shrink — when a real import covers one of its files, the Task is adopted
 * into that Source and this claim row goes.
 *
 * A Task named here is reachable through any List that contains it, exactly as today. It is not in
 * any catalog until it is imported, which is the honest statement: nothing has yet decided that it
 * is imported material.
 */
@Entity(
    tableName = "legacy_source_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LegacySourceTaskEntity(
    @PrimaryKey val taskId: String
)

/**
 * The Task-level counterpart of [LegacyItemIdEntity]: a stable wire id for a Task with no single
 * Asset to borrow one from.
 *
 * A folder round over a multi-Asset Task has no `list_items` row and no single Asset whose
 * [LegacyItemIdEntity] can name it, yet the wire still has to carry a stable `id`. This table is
 * where that name lives, and — like [LegacyItemIdEntity] — it is a compatibility boundary, written
 * lazily by the webhook adapter the first time such a Task is reported. Never by an import run,
 * never by the catalog.
 */
@Entity(
    tableName = "legacy_task_ids",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LegacyTaskIdEntity(
    @PrimaryKey val taskId: String,
    val publicId: String
)
