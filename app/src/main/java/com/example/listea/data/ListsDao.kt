package com.example.listea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.listea.ContentDecision
import com.example.listea.ContentPolicy
import com.example.listea.ImporterKind
import com.example.listea.ImportPlan
import com.example.listea.PlannedTask
import com.example.listea.assetViewerAvailable
import com.example.listea.childRelativePath
import com.example.listea.decideContentDecision
import com.example.listea.hasSourceChanges
import com.example.listea.isAncestorOrSame
import com.example.listea.normalizeKeepTree
import com.example.listea.taskChanges
import com.example.listea.domain.AssetKind
import com.example.listea.domain.EXPORT_CHOICE_NONE
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewEventId
import com.example.listea.domain.ReviewEventKind
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.assetUri
import com.example.listea.domain.completesTask
import com.example.listea.domain.encode
import com.example.listea.domain.viewerEvents
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * How many paths one `IN (:paths)` statement may name.
 *
 * SQLite binds one variable per element and older Androids cap that at 999, which `minSdk = 24`
 * means this build cannot assume away. The import path is the only place a single statement can be
 * handed a whole folder's worth of paths, so it chunks rather than inheriting the ceiling; 500
 * leaves room for the bound parameters any such statement carries besides the list itself.
 */
private const val PathBindChunk = 500

@Dao
abstract class ListsDao {

    @Query(
        """
        SELECT l.id AS id,
               l.title AS title,
               l.completedAt AS completedAt,
               COUNT(i.id) AS totalItems,
               COALESCE(SUM(ts.isCompleted), 0) AS completedItems,
               l.webhookEnabled AS exportOnCompletion,
               l.exportKind AS exportKind,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode,
               l.lastDissolvedCount AS lastDissolvedCount,
               l.lastDissolvedAt AS lastDissolvedAt,
               l.lastDissolvedReason AS lastDissolvedReason
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
        LEFT JOIN task_state ts ON ts.taskId = i.taskId
        GROUP BY l.id
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    abstract fun observeSummaries(): Flow<List<ListSummary>>

    @Query("SELECT * FROM lists WHERE id = :listId")
    abstract fun observeList(listId: Long): Flow<ListEntity?>

    /**
     * One list's items with their decisions resolved.
     *
     * A membership row holds no decision of its own. What is true of the thing judged lives on its
     * Task, and this joins it back in — one path for every row, whether it is a manually typed
     * entry or a file. The CASE that used to send the two kinds to different tables is gone rather
     * than rewritten, because a manual entry and a file now answer the question the same way: a
     * manual entry is a Task with no content, a file is a Task with an Asset, and both have state.
     *
     * **Two joins since v0.5.1**, because the row reports both halves a list row is asked for:
     * completion off `task_state`, the tags off the Task's pairs. A row that carries both speaks
     * [ReviewDecisions] — see [AssetDecisions] — so this is where the two are put back together.
     *
     * The tags are the union over the Task's Assets, and it is taken in SQL: `★` as a `MAX`, every
     * tag as a `GROUP_CONCAT` of the per-pair columns, which decodes to the union because
     * [decodeActionIds] splits and de-duplicates. A row is one line on a list and a Task can be
     * several files, so the alternative — reporting one Asset's tags — would show a mark the user
     * cannot find on the files they open, or hide one they can. The comma is safe to concatenate
     * because an id is generated rather than typed; see [newActionId].
     *
     * The `assets` join is left, unlike `task_state`'s. Both are on a Task, but only one of them is
     * an invariant: every Task is created with its state in the same breath — the migration test
     * asserts over a realistic fixture that migrated data holds the same — while a Task with no
     * Asset is an ordinary thing, a manual entry, and its row must not vanish for having no pair to
     * fold. A missing pair reads as nothing marked, which is what it is.
     *
     * Room sees `task_state`, `assets` and `task_asset_state` in the query and invalidates this flow
     * when any of them changes, which is what makes a decision taken in a folder's Quick Review
     * appear on this list immediately — a chip ticked on one file included, since a pair is one row
     * of the table that invalidates it.
     */
    @Query(
        """
        SELECT i.id AS id,
               i.publicId AS publicId,
               i.listId AS listId,
               i.taskId AS taskId,
               i.title AS title,
               ts.isCompleted AS isCompleted,
               i.sortOrder AS sortOrder,
               i.createdAt AS createdAt,
               i.sourceUri AS sourceUri,
               i.sourceRelativePath AS sourceRelativePath,
               i.rootRelativePath AS rootRelativePath,
               i.sourceMissing AS sourceMissing,
               i.sourceSizeBytes AS sourceSizeBytes,
               i.sourceModifiedAt AS sourceModifiedAt,
               -- The two retired columns, selected because the entity still declares them and they
               -- are still there. Both are dead: the pair table is where a mark lives, and nothing
               -- has written either since v0.5.4 — see `ListItemEntity.customActions`.
               i.isFavorite AS isFavorite,
               i.customActions AS customActions,
               COALESCE(GROUP_CONCAT(tas.selections), '') AS selections
        FROM list_items i
        JOIN task_state ts ON ts.taskId = i.taskId
        LEFT JOIN assets a ON a.taskId = i.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE i.listId = :listId
        GROUP BY i.id
        ORDER BY i.sortOrder ASC, i.id ASC
        """
    )
    abstract fun observeItems(listId: Long): Flow<List<ListItemEntity>>

    /**
     * The Assets of a List's Tasks, in Asset order, so the review surface can resolve a multi-Asset
     * Task's pages without a per-card query. Joined through `list_items` rather than by Task id list
     * so it stays a single Room query that re-emits when any Asset changes.
     */
    @Query(
        """
        SELECT a.* FROM assets a
        JOIN list_items i ON i.taskId = a.taskId
        WHERE i.listId = :listId
        ORDER BY a.taskId, a.sortOrder ASC
        """
    )
    abstract fun observeAssetsForList(listId: Long): Flow<List<AssetEntity>>

    /**
     * The pair records of a List's Tasks, read beside the Assets rather than joined onto them.
     *
     * The same move [observeAssetControlsForList] makes and for the same reason: the record is not
     * a column of `assets` and must not become one, so the surface that needs both reads both and
     * pairs them in Kotlin. Joined through `list_items` so it stays one Room query that re-emits
     * when a record changes — which is what makes a chip tapped on the review surface move the
     * List's own row behind it.
     */
    @Query(
        """
        SELECT tas.* FROM task_asset_state tas
        JOIN assets a ON a.id = tas.assetId
        JOIN list_items i ON i.taskId = a.taskId
        WHERE i.listId = :listId
        """
    )
    abstract fun observeAssetStatesForList(listId: Long): Flow<List<AssetStateEntity>>

    /**
     * The asset control each of a List's Tasks was imported under.
     *
     * A query of its own rather than two more lines on [observeItems], for two reasons. The control
     * lives on `task_imports`, which the membership query would have to join — and a *left* join,
     * because a manual item and a legacy Task have no import row and an inner one would take them
     * out of somebody's list. And the column it brings back is not a slot on [ListItemEntity]: that
     * entity's `isCompleted`, `isFavorite` and `customActions` are projections Room fills because
     * they were *once columns of the row*, and adding a field there would mean a schema change to
     * carry a value that was never this table's. This is the same move [observeAssetsForList] makes
     * directly above it for the same reason — read the Task's side table, keyed by the Task.
     *
     * Tasks with no import row are simply absent, and [ReviewItem.assetControls] defaults to `""`,
     * which is the correct reading of a Task nobody imported.
     */
    @Query(
        """
        SELECT ti.taskId AS taskId, ti.assetControls AS assetControls, ti.addOn AS addOn,
               ti.taskReview AS taskReview, ppt.packageKey AS packageKey,
               ppt.promptKey AS promptKey, tp.kind AS payloadKind,
               tp.schemaVersion AS payloadSchema, tp.revision AS payloadRevision
        FROM task_imports ti
        JOIN list_items i ON i.taskId = ti.taskId
        LEFT JOIN package_prompt_tasks ppt ON ppt.taskId = ti.taskId
        LEFT JOIN task_payloads tp ON tp.taskId = ti.taskId
        WHERE i.listId = :listId
        """
    )
    abstract fun observeAssetControlsForList(listId: Long): Flow<List<TaskAssetControls>>

    /**
     * The most recent response committed for one Task, or null when it has never been judged.
     *
     * Read by the review surface to show what is on record beside the control that can replace it —
     * the point a template with a commit control records is otherwise write-only, since the file's
     * chips are state and an event is history. Ordered by the write's timestamp with the id as a
     * tie-break so two submissions in one millisecond still have one latest.
     *
     * The text is returned as stored rather than decoded here: `review_events.response` is a
     * permanent record written by a build that may be newer than this one, and only the reader in
     * `domain/ReviewResponse` is allowed to decide it is unreadable.
     */
    @Query(
        """
        SELECT response FROM review_events
        WHERE taskId = :taskId
        ORDER BY at DESC, id DESC
        LIMIT 1
        """
    )
    abstract fun observeLatestResponse(taskId: String): Flow<String?>

    /**
     * The events of that same latest submission, split out of its response and in reported order.
     *
     * This is the read the Info sheet's *Recorded* section uses. It is deliberately a child read
     * rather than a second parse of `observeLatestResponse`: the table is the place a caught event
     * is addressed as an event, and an asset reference or a later event kind belongs there rather
     * than inside a text line. The subquery matches [observeLatestResponse]'s ordering exactly, so
     * the band and the sheet can never name two different submissions as *the latest*.
     *
     * A Task with no event-bearing submission — never reviewed, reviewed by the legacy media
     * template, or reviewed by an event template that caught nothing because the write was refused —
     * answers an empty list.
     */
    @Query(
        """
        SELECT a.* FROM review_event_answers a
        WHERE a.reviewEventId = (
            SELECT id FROM review_events
            WHERE taskId = :taskId
            ORDER BY at DESC, id DESC
            LIMIT 1
        )
        ORDER BY a.ordinal
        """
    )
    abstract fun observeLatestEventAnswers(taskId: String): Flow<List<ReviewEventAnswerEntity>>

    /** The same, once, for the two paths that build a payload rather than drive a screen. */
    @Query(
        """
        SELECT ti.taskId AS taskId, ti.assetControls AS assetControls, ti.addOn AS addOn,
               ti.taskReview AS taskReview, ppt.packageKey AS packageKey,
               ppt.promptKey AS promptKey, tp.kind AS payloadKind,
               tp.schemaVersion AS payloadSchema, tp.revision AS payloadRevision
        FROM task_imports ti
        JOIN list_items i ON i.taskId = ti.taskId
        LEFT JOIN package_prompt_tasks ppt ON ppt.taskId = ti.taskId
        LEFT JOIN task_payloads tp ON tp.taskId = ti.taskId
        WHERE i.listId = :listId
        """
    )
    abstract suspend fun getAssetControlsForList(listId: Long): List<TaskAssetControls>

    /**
     * The Todo each Task came from, and how that Todo exports. The lookup the export split is built
     * on, and the only place `task_imports` is joined to `sources` for this purpose.
     *
     * **Addressed by Task id, and an absent row is an answer.** A Task with no `task_imports` row —
     * one added by hand, or a folder round's file that no Todo owns — simply does not appear, and
     * the caller reads its absence as *no source* rather than as an error. That is why this returns
     * a list and not one row per id passed in: inventing a row for a Task that has none would turn
     * *no Todo owns this* into *a Todo called null owns this*.
     *
     * `ORDER BY` is deliberately absent. The order payloads are delivered in comes from
     * [planExports], which works off the round's own item order; a second ordering here could only
     * disagree with it.
     */
    @Query(
        """
        SELECT ti.taskId AS taskId, s.id AS sourceId, s.displayName AS sourceName,
               s.exportKind AS exportKind, s.webhookUrl AS webhookUrl
        FROM task_imports ti
        JOIN sources s ON s.id = ti.sourceId
        WHERE ti.taskId IN (:taskIds)
        """
    )
    abstract suspend fun getExportSources(taskIds: List<String>): List<TaskExportSource>

    /**
     * The same for a named set of Tasks, which is what a folder round has instead of a list.
     *
     * Addressed by Task id rather than by the registration rows the payload is built from, because
     * a folder round's Tasks need not have a registration row at all — a multi-Asset Task has none —
     * and a payload that dropped one for want of a row would be the same failure
     * [ListsViewModel.deliverFolderReview] already avoids for decisions.
     */
    @Query(
        """
        SELECT taskId AS taskId, assetControls AS assetControls, addOn AS addOn,
               taskReview AS taskReview
        FROM task_imports
        WHERE taskId IN (:taskIds)
        """
    )
    abstract suspend fun getAssetControls(taskIds: List<String>): List<TaskAssetControls>

    /**
     * The Tasks a payload is about: their public id, their imported name, and what governs them.
     *
     * A `LEFT JOIN` because the payload must contain every Task the round selected, including one
     * no Todo owns. Absence is answered by the caller falling back to the row's title.
     */
    @Query(
        """
        SELECT t.id AS taskId, t.publicId AS publicId,
               ti.displayName AS displayName, ti.assetControls AS assetControls,
               ti.addOn AS addOn
        FROM tasks t
        LEFT JOIN task_imports ti ON ti.taskId = t.id
        WHERE t.id IN (:taskIds)
        """
    )
    abstract suspend fun getPayloadTasks(taskIds: List<String>): List<PayloadTaskRow>

    /**
     * Those Tasks' Prompt envelopes, when they have one.
     *
     * A `LEFT JOIN` onto the Prompt link because the envelope is stored on the Task and the Prompt
     * key is the Package's: a body the import could not name still has a kind and a revision, and a
     * v5 body should say what kind of content it is even then. A Task with no payload row is simply
     * absent, which is how the writer tells the two apart.
     */
    @Query(
        """
        SELECT tp.taskId AS taskId, ppt.packageKey AS packageKey,
               ppt.promptKey AS promptKey, tp.kind AS kind,
               tp.schemaVersion AS schemaVersion, tp.revision AS revision
        FROM task_payloads tp
        LEFT JOIN package_prompt_tasks ppt ON ppt.taskId = tp.taskId
        WHERE tp.taskId IN (:taskIds)
        """
    )
    abstract suspend fun getPayloadPrompts(taskIds: List<String>): List<PayloadPromptRow>

    /**
     * The newest submission of each Task in the round, as stored text.
     *
     * `NOT EXISTS` rather than `MAX(at)`: two submissions can share a millisecond, and the ordering
     * that decides *latest* everywhere else is `(at, id)`. Picking by timestamp alone would let the
     * export and the review surface disagree about which answer is on record.
     */
    @Query(
        """
        SELECT r.taskId AS taskId, r.response AS response
        FROM review_events r
        WHERE r.taskId IN (:taskIds)
          AND NOT EXISTS (
              SELECT 1 FROM review_events n
              WHERE n.taskId = r.taskId
                AND (n.at > r.at OR (n.at = r.at AND n.id > r.id))
          )
        """
    )
    abstract suspend fun getLatestResponses(taskIds: List<String>): List<PayloadReviewRow>

    /**
     * The Assets under those Tasks, with each pair's decisions in the same read.
     *
     * Ordered by the Asset's own place in its Task, so a payload's `assets` array is the order the
     * review surface pages through. A second Task over the same file is a second row with its own
     * id; nothing here merges by path.
     */
    @Query(
        """
        SELECT a.taskId AS taskId, a.id AS assetId, a.publicId AS publicId,
               a.relativePath AS relativePath, a.sourceMissing AS sourceMissing,
               a.sortOrder AS sortOrder, tas.selections AS selections,
               a.sourceUri AS sourceUri
        FROM assets a
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE a.taskId IN (:taskIds)
        ORDER BY a.taskId, a.sortOrder, a.id
        """
    )
    abstract suspend fun getPayloadAssets(taskIds: List<String>): List<PayloadAssetRow>

    /**
     * The stored passages of the given Assets, in the order they were made.
     *
     * Everything the export needs is on the row: the tool and kind decide whether the current
     * template still offers the mark at all, the anchor fingerprint decides whether it still
     * describes the file, and the quote is what the receiver is shown. The authored template is
     * deliberately not read — visibility is a question about the template in force now.
     */
    suspend fun getPayloadMarks(assetIds: List<String>): List<PayloadMarkRow> =
        assetIds.chunked(PathBindChunk).flatMap { chunk -> getPayloadMarksChunk(chunk) }

    @Query(
        """
        SELECT id, assetId, toolId, kind, value, pageIndex, exactText,
               documentFingerprint, createdAt
        FROM task_asset_marks
        WHERE assetId IN (:assetIds)
        ORDER BY assetId, createdAt, id
        """
    )
    protected abstract suspend fun getPayloadMarksChunk(
        assetIds: List<String>
    ): List<PayloadMarkRow>

    @Insert
    abstract suspend fun insertList(list: ListEntity): Long

    @Query("UPDATE lists SET title = :title WHERE id = :id")
    abstract suspend fun updateListTitle(id: Long, title: String)

    /**
     * Takes a List and everything that was only reachable through it.
     *
     * Memberships cascade away with the List. A file's Task does not: its decision outlives the
     * List that happened to show it, which is the property the whole model is built to keep. A
     * manual item's Task has nothing to outlive the membership *for* - there is no file, and the
     * membership was the only way to reach it - so it goes. Those are the ones
     * `legacy_manual_tasks` names, and they go here.
     *
     * The read happens first for the obvious reason: once the List has cascaded away there is
     * nothing left to join the names to.
     */
    @Transaction
    open suspend fun deleteList(id: Long) {
        val manualTasks = manualTasksOfList(id)
        deleteListRow(id)
        deleteManualTasks(manualTasks)
    }

    @Query("DELETE FROM lists WHERE id = :id")
    protected abstract suspend fun deleteListRow(id: Long)

    /**
     * Drops every list linked to one SAF root, whether it was created on the root itself or on a
     * folder below it, and returns how many went. Items cascade with their list.
     *
     * Manual lists have no root and can never match. Lists linked to a *different* root are left
     * alone: they have nothing to do with the folder being given up.
     *
     * A folder-backed List can still hold a manual item, so this takes the same cleanup [deleteList]
     * does. It is the same question - which Tasks existed only because of a List that is going -
     * asked of several Lists at once, and answering it for only one of the ways a List can be
     * deleted would leave the others quietly orphaning Tasks.
     */
    @Transaction
    open suspend fun deleteListsForRoot(rootUri: String): Int {
        val manualTasks = manualTasksUnderRoot(rootUri)
        val deleted = deleteListRowsForRoot(rootUri)
        deleteManualTasks(manualTasks)
        return deleted
    }

    @Query("DELETE FROM lists WHERE sourceRootUri = :rootUri")
    protected abstract suspend fun deleteListRowsForRoot(rootUri: String): Int

    /**
     * Checks or unchecks a Task, from wherever it was reached.
     *
     * The one completion write, and the reason it is addressed by a Task's id rather than by a
     * file's location: what is being judged is the Task, and a Task reached from two Lists, from a
     * folder Quick Review and from nowhere any List covers is still one Task. One write
     * re-evaluates every List that contains it, so checking the last unreviewed file of a folder
     * from a Quick Review that knows nothing about any List still completes the List covering it,
     * and still fires that List's webhook.
     *
     * A Task that has gone — a membership removed, taking a manual Task with it — has no Lists left
     * to re-evaluate, and the state write is a silent no-op: nothing is minted to stand in for it,
     * and no completion is reported.
     *
     * Returns the Lists that moved from incomplete to complete, so the caller fires exactly one
     * completion action per List that actually transitioned. Non-overlap means that is at most one
     * today; nothing here assumes it.
     */
    @Transaction
    open suspend fun setTaskCompleted(
        taskId: String,
        completed: Boolean,
        now: Long
    ): List<Long> {
        val affected = listsContainingTask(taskId)
        val wasComplete = affected.filter { isComplete(it) == true }.toSet()

        writeTaskState(taskId, now) { completed }

        return refreshAffected(affected, wasComplete, now)
    }

    /**
     * A review *submission*: somebody was shown this Task through a Task Review surface and
     * committed a response to it.
     *
     * The other half of the fork §5 turns on, and the only method here that appends a
     * [ReviewEventEntity]. [setTaskCompleted] writes the same [TaskStateEntity] field and records
     * nothing, because every other reason a Task's completion moves — a checkbox on the List's own
     * page, a bulk edit, a migration backfill — is not somebody's verdict. No caller inherits
     * event-writing by asking for "completed": a submission has to say so.
     *
     * Addressed by the Task's id like every Task-level write: the Task was resolved long before the
     * review surface showed it, so a submission can never be a reason to mint one. A submission
     * about a Task that has gone writes nothing — neither state nor event — because a judgment
     * filed against a Task nobody can reach would be a judgment against nothing.
     *
     * The state and the event are written in one transaction, so a judgment and the state it
     * committed cannot be observed apart. The response is written as given rather than recomputed
     * from the state: what goes on record is what was committed, and if the two ever disagreed that
     * disagreement is itself worth having, since a history that can only repeat current state would
     * answer nothing.
     *
     * Returns the Lists that this moved from incomplete to complete, on the same terms as
     * [setTaskCompleted], so whoever called it fires the completion action exactly as before.
     */
    @Transaction
    open suspend fun submitReview(
        taskId: String,
        response: ReviewResponse,
        template: TemplateVersionRef,
        context: ReviewContext?,
        now: Long
    ): List<Long> {
        // A Task whose content cannot be presented is not reviewable: a judgment recorded against
        // uninspectable evidence is a judgment about nothing. The refusal lives here, at the write
        // boundary, so no path — gesture, control, or future caller — can record one. A disabled
        // control in the UI is a courtesy; this is the guarantee.
        if (!contentPresentable(taskId)) return emptyList()

        val affected = listsContainingTask(taskId)
        val wasComplete = affected.filter { isComplete(it) == true }.toSet()

        val written = writeTaskState(taskId, now) { response.completesTask }

        // A write that found no state row is a submission about a Task that no longer exists, and
        // nothing is recorded against it: an event naming a Task nobody can reach would be a
        // judgment filed against nothing.
        if (written != null) {
            val (kind, ref) = reviewContextColumns(context)
            val eventId = ReviewEventId.new().value
            insertReviewEvent(
                ReviewEventEntity(
                    id = eventId,
                    taskId = taskId,
                    at = now,
                    kind = ReviewEventKind.SUBMITTED.key,
                    templateId = template.templateId.value,
                    templateVersion = template.version,
                    response = response.encode(),
                    contextKind = kind,
                    contextRef = ref
                )
            )
            // The events are split into rows in the same transaction, so a submission is either
            // wholly on record — the response and the events it can be asked about — or not on
            // record at all. A child row that could outlive a failed write would be an event whose
            // submission nobody made.
            writeReviewEventAnswers(eventId, taskId, response.viewerEvents)
        }

        return refreshAffected(affected, wasComplete, now)
    }

    /**
     * The events of a just-written submission, one row each, with their Assets resolved.
     *
     * The event's asset path is its source URI — what the viewer was showing when the gesture
     * happened — so resolution is a lookup over the Task's own Assets and nothing else. A path that
     * names no Asset the Task still holds leaves `assetId` null: the event stays a true record of
     * what was caught, and a missing file is never a reason to drop it. Two Assets under one Task
     * cannot carry the same source URI (the import merge keys on that pair), so the map is not
     * ambiguous; if one ever did, the first in sort order would be named rather than the row
     * invented twice.
     */
    private suspend fun writeReviewEventAnswers(
        reviewEventId: String,
        taskId: String,
        events: List<ViewerEvent>
    ) {
        if (events.isEmpty()) return
        val assetsByUri = assetsForTask(taskId)
            .mapNotNull { asset -> asset.sourceUri?.let { uri -> uri to asset.id } }
            .toMap()
        events.forEachIndexed { ordinal, event ->
            insertReviewEventAnswer(
                event.toReviewEventAnswer(
                    reviewEventId = reviewEventId,
                    ordinal = ordinal,
                    assetId = event.assetUri()?.let { assetsByUri[it] }
                )
            )
        }
    }

    /**
     * Re-evaluates every List one decision could have completed, and reports the ones it did.
     *
     * Shared by the two write paths rather than written twice, because "which Lists moved to
     * complete" is the same question whether the answer leads to a delivery or to nothing, and two
     * copies of it would be two chances to answer it differently.
     */
    private suspend fun refreshAffected(
        affected: List<Long>,
        wasComplete: Set<Long>,
        now: Long
    ): List<Long> {
        val transitioned = mutableListOf<Long>()
        for (listId in affected) {
            refreshCompletion(listId, now)
            if (listId !in wasComplete && isComplete(listId) == true) transitioned += listId
        }
        return transitioned
    }

    /**
     * How a submission's provenance is spelled in `review_events`.
     *
     * [ReviewEventEntity.contextKind] says what [ReviewEventEntity.contextRef] names, so the two are
     * produced together and never interpreted apart. A List's ref is its id. A folder's is the
     * folder's path under its SAF grant — the grant is deliberately not repeated, because the
     * event's Task already points at an Asset carrying that pair, so the root is one join away and
     * a second stored copy is a second copy that can disagree.
     *
     * A null context stays null on both columns rather than becoming an empty string: "submitted
     * from nowhere in particular" is a fact about the submission, and a blank ref would read as a
     * folder with an empty path.
     */
    private fun reviewContextColumns(context: ReviewContext?): Pair<String?, String?> =
        when (context) {
            null -> null to null
            is ReviewContext.InList -> ReviewContextKind.LIST to context.listId.value.toString()
            is ReviewContext.InFolder -> ReviewContextKind.FOLDER to context.relativePath
        }

    /**
     * Removes one item, and the Task with it if that Task existed only as a manual item.
     *
     * Returns true when removing the last unchecked item completed the list.
     *
     * A source-backed row takes its Task nowhere: the file keeps its decisions, and the next List
     * to show it will resolve the same Task. A manual one takes its Task with it, because the row
     * was the only thing that ever reached it. The name is read while the row is still there to
     * read it from.
     */
    @Transaction
    open suspend fun removeItem(listId: Long, itemId: Long, now: Long): Boolean {
        val wasComplete = isComplete(listId) == true
        val manualTask = manualTaskOfItem(itemId)
        deleteItem(itemId)
        deleteManualTasks(manualTask)
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    @Query("SELECT * FROM lists WHERE id = :listId")
    abstract suspend fun getList(listId: Long): ListEntity?

    /** Every folder-backed list under one SAF root. Scope overlap is worked out in Kotlin. */
    @Query("SELECT * FROM lists WHERE sourceRootUri = :rootUri")
    abstract fun observeFolderLists(rootUri: String): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE sourceRootUri = :rootUri")
    abstract suspend fun getFolderLists(rootUri: String): List<ListEntity>

    /**
     * Every folder-backed scope under [rootUri] with its progress, in one query. The Folder screen
     * observes this once and derives exact/inherited ownership in memory.
     */
    @Query(
        """
        SELECT l.id AS id,
               l.title AS title,
               l.sourceRelativePath AS relativePath,
               l.completedAt AS completedAt,
               COUNT(i.id) AS totalItems,
               COALESCE(SUM(ts.isCompleted), 0) AS completedItems,
               l.webhookEnabled AS exportOnCompletion,
               l.exportKind AS exportKind,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
        LEFT JOIN task_state ts ON ts.taskId = i.taskId
        WHERE l.sourceRootUri = :rootUri AND l.sourceRelativePath IS NOT NULL
        GROUP BY l.id
        """
    )
    abstract fun observeFolderScopes(rootUri: String): Flow<List<FolderListScope>>

    /**
     * Source-backed items of every folder-backed list under [rootUri], for deriving per-subtree
     * progress. One query for the whole screen. Manual items are excluded here, so they can never
     * count toward a folder's progress; missing sources are kept, since they still belong to their
     * subtree.
     */
    @Query(
        """
        SELECT i.listId AS listId,
               i.sourceRelativePath AS relativePath,
               ts.isCompleted AS isCompleted
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        JOIN task_state ts ON ts.taskId = i.taskId
        WHERE l.sourceRootUri = :rootUri
          AND l.sourceRelativePath IS NOT NULL
          AND i.sourceRelativePath IS NOT NULL
        """
    )
    abstract fun observeFolderScopeItems(rootUri: String): Flow<List<SourceItemRef>>

    /**
     * Creates a folder-backed list in one transaction: drops the confirmed overlapping lists
     * (cascading their items and webhook state), runs a file-as-Task import over the folder, then
     * builds the list from the resulting Tasks. Either the whole snapshot lands or nothing does.
     *
     * The composition proceeds only on a `complete` import; on a partial one it throws and creates
     * nothing, which is the spec's "no List is created from a failed or incomplete import". The
     * resulting `list_items` row is byte-identical to the one this method wrote before it imported,
     * because the import's groupPath and Asset measurements are exactly what the scan measured.
     */
    @Transaction
    open suspend fun createFolderList(
        title: String,
        rootUri: String,
        relativePath: String,
        files: List<ScannedFile>,
        replacedListIds: List<Long>,
        exportOnCompletion: Boolean,
        now: Long
    ): Long {
        replacedListIds.forEach { deleteList(it) }

        val source = registerOrReuseSource(rootUri, relativePath, now)
        val result = runImportInternal(source.id, relativePath, fileAsTaskPlan(relativePath, files), now)
        if (result.outcome != ImportOutcome.COMPLETE) {
            throw FolderListImportConflict(source.relativePath)
        }

        val taskIdsByGroup = taskImportsForSource(source.id).associate { it.groupPath to it.taskId }
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
                // Seeded from settings by the caller at creation, and owned by the List from here
                // on. What is *no longer* copied is a URL: since v0.5.6 every webhook reads the
                // settings default at the moment it exports, so changing it reaches every List.
                exportOnCompletion = exportOnCompletion,
                sourceRootUri = rootUri,
                sourceRelativePath = relativePath
            )
        )
        insertItems(
            files.mapIndexed { index, file ->
                val groupPath = rootRelativePathOf(relativePath, file.relativePath)
                val taskId = taskIdsByGroup[groupPath]
                    ?: throw IllegalStateException("no Task for $groupPath after import")
                listItemForTask(taskId, listId, index, now, relativePath)
            }
        )
        return listId
    }

    /**
     * Creates a List from already-normalized Tasks, one membership per Task in the order given.
     *
     * Resolves no files, scans nothing and calls no importer: the Task ids are the whole input, and
     * a Task id it cannot reach fails the transaction rather than creating a partial List. The
     * file-shaped columns are filled from each Task's Assets — the importer's name as the title,
     * the single Asset's URI for a file Task, the groupPath as both paths, and the summed sizes and
     * newest modified time as the measurements.
     */
    @Transaction
    open suspend fun createListFromTasks(
        title: String,
        taskIds: List<String>,
        exportOnCompletion: Boolean,
        now: Long
    ): Long {
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
                exportOnCompletion = exportOnCompletion
            )
        )
        insertItems(
            taskIds.mapIndexed { index, taskId ->
                listItemForTask(taskId, listId, index, now, folderRelativePath = null)
            }
        )
        // A List made from Tasks that are already finished is finished the moment it exists. The
        // membership just moved, and that is the only thing this question is asked about — a caller
        // that skipped this would leave the row reading `N / N` while the screen filed it under
        // *In progress*, which is what the caller that imports a recovered Todo and builds a List
        // from it used to do.
        refreshCompletion(listId, now)
        return listId
    }

    /**
     * Puts Tasks into a List that already exists, each as a membership at the end of it.
     *
     * The other half of [createListFromTasks], and the reason a List is not owned by the Todo its
     * Tasks came from: a List is an order over Tasks, so making one is picking Tasks and picking a
     * List, and this is the order where the List is chosen first. Nothing else differs — each row
     * is built by the same [listItemForTask], from the same Task's own Assets, so a membership
     * added this way is indistinguishable from one the List was created with.
     *
     * A Task the List already holds is skipped rather than added a second time. A List shows each
     * of its Tasks once; a second membership would be one Task at two positions, which is not a
     * second opinion about it and would put the review queue at the same Task twice.
     *
     * Returns how many memberships were actually added, so a caller can say "these were already in
     * it" rather than report a success that wrote nothing.
     */
    @Transaction
    open suspend fun addTasksToList(listId: Long, taskIds: List<String>, now: Long): Int {
        val already = taskIdsInList(listId).toSet()
        val additions = taskIds.distinct().filterNot { it in already }
        if (additions.isEmpty()) return 0

        var order = maxSortOrder(listId) + 1
        insertItems(
            additions.map { taskId ->
                listItemForTask(taskId, listId, order++, now, folderRelativePath = null)
            }
        )
        clearDissolutionNote(listId)
        // The same re-answer a creation gets, and for the same reason: the membership moved, in
        // both directions. A List that was waiting on nothing but finished rows stops being
        // finished the moment an unfinished one arrives, and one that was complete because it was
        // empty stays empty rather than complete. The early return above is deliberate — with no
        // additions nothing moved, so there is no new answer to write.
        refreshCompletion(listId, now)
        return additions.size
    }

    /** Every Task this List already shows, so an addition can skip the ones it holds. */
    @Query("SELECT taskId FROM list_items WHERE listId = :listId")
    protected abstract suspend fun taskIdsInList(listId: Long): List<String>

    /** The Source a folder-tab action works against: a covering one, or a newly registered one. */
    private suspend fun registerOrReuseSource(
        rootUri: String,
        relativePath: String,
        now: Long
    ): SourceEntity =
        sourcesCovering(rootUri, relativePath).firstOrNull()
            ?: registerSource(
                rootUri = rootUri,
                relativePath = relativePath,
                displayName = relativePath,
                importerKind = ImporterKind.FILE_AS_TASK,
                keepTree = true,
                now = now
            )

    private fun fileAsTaskPlan(importRoot: String, files: List<ScannedFile>) =
        com.example.listea.plan(
            ImporterKind.FILE_AS_TASK,
            importRoot,
            com.example.listea.FolderSnapshot(files, emptyList())
        )

    /** One membership row for a Task, its file-shaped columns read from the Task's Assets. */
    private suspend fun listItemForTask(
        taskId: String,
        listId: Long,
        sortOrder: Int,
        now: Long,
        folderRelativePath: String?
    ): ListItemEntity {
        val import = taskImportForTask(taskId) ?: throw IllegalStateException("no Task $taskId")
        val assets = assetsForTask(taskId)
        val groupPath = import.groupPath
        return ListItemEntity(
            publicId = newItemPublicId(fileIdentity(groupPath), now),
            listId = listId,
            taskId = taskId,
            title = import.displayName,
            sortOrder = sortOrder,
            createdAt = now,
            // A file Task's single Asset carries the document URI; a folder Task's Assets are
            // under its groupPath, so its entry carries no single source.
            sourceUri = assets.singleOrNull()?.takeIf { it.relativePath == groupPath }?.sourceUri,
            sourceRelativePath = folderRelativePath?.let { relativeToFolder(groupPath, it) } ?: groupPath,
            rootRelativePath = groupPath,
            sourceSizeBytes = assets.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum(),
            sourceModifiedAt = assets.mapNotNull { it.lastModified }.maxOrNull()
        )
    }

    private fun relativeToFolder(groupPath: String, folder: String): String =
        if (folder.isEmpty()) groupPath else groupPath.removePrefix("$folder/")

    /** The same resolution as [observeItems], for the one-shot reads a webhook payload needs. */
    @Query(
        """
        SELECT i.id AS id,
               i.publicId AS publicId,
               i.listId AS listId,
               i.taskId AS taskId,
               i.title AS title,
               ts.isCompleted AS isCompleted,
               i.sortOrder AS sortOrder,
               i.createdAt AS createdAt,
               i.sourceUri AS sourceUri,
               i.sourceRelativePath AS sourceRelativePath,
               i.rootRelativePath AS rootRelativePath,
               i.sourceMissing AS sourceMissing,
               i.sourceSizeBytes AS sourceSizeBytes,
               i.sourceModifiedAt AS sourceModifiedAt,
               -- The two retired columns, selected because the entity still declares them and they
               -- are still there. Both are dead: the pair table is where a mark lives, and nothing
               -- has written either since v0.5.4 — see `ListItemEntity.customActions`.
               i.isFavorite AS isFavorite,
               i.customActions AS customActions,
               COALESCE(GROUP_CONCAT(tas.selections), '') AS selections
        FROM list_items i
        JOIN task_state ts ON ts.taskId = i.taskId
        LEFT JOIN assets a ON a.taskId = i.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE i.listId = :listId
        GROUP BY i.id
        ORDER BY i.sortOrder ASC, i.id ASC
        """
    )
    abstract suspend fun getItems(listId: Long): List<ListItemEntity>

    /**
     * Every checked file under one SAF root that Listea still believes exists.
     *
     * Read from what is true of the file, not from what any List says about it, which is what
     * makes it right: a file checked in a folder no List covers is just as checked as one checked
     * from a List, and the feature that deletes checked files has to see both or it is lying about
     * what "checked" means.
     *
     * Checked state is a fact about the Task, reached through the Asset that names the file. The
     * delete path deliberately does **not** join `file_review_state` to establish that a file is one
     * Listea knows as a file: a review *submission* writes a Task's state without registering the
     * file, and the set this feature may delete must not shrink to the subset that has happened to
     * pass through a folder browse. Registration is a browse and public-id concern, not a
     * precondition for a decision being real, so that join would silently under-report every file
     * that was checked by a swipe and never registered — which is exactly how a completed round
     * could leave Settings claiming nothing is checked.
     *
     * A file already flagged missing is excluded — there is nothing left to delete — and so is an
     * unchecked one, which is exactly the thing the user did not mark.
     *
     * Presence is read off the Asset, where the write paths set it: it was the registration's flag
     * until v0.2 finished moving it, and the registration's own copy is written once by the
     * migration and never again.
     */
    @Query(
        """
        SELECT DISTINCT a.relativePath FROM assets a
        JOIN task_state ts ON ts.taskId = a.taskId
        WHERE a.rootUri = :rootUri AND ts.isCompleted = 1 AND a.sourceMissing = 0
        ORDER BY a.relativePath COLLATE NOCASE ASC
        """
    )
    abstract suspend fun getCheckedFilePaths(rootUri: String): List<String>

    /**
     * How many there are, for the card that offers the delete. Same filters, no paths loaded.
     *
     * Counted over distinct *paths* rather than rows, because a file under two Todos has two Tasks
     * and so two rows here. The card offers to delete files from disk, and there is one of those
     * however many intentions are about it — a count that said two would make the button a lie.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT a.relativePath) FROM assets a
        JOIN task_state ts ON ts.taskId = a.taskId
        WHERE a.rootUri = :rootUri AND ts.isCompleted = 1 AND a.sourceMissing = 0
        """
    )
    abstract fun observeCheckedFileCount(rootUri: String): Flow<Int>

    /**
     * Records that these files are gone, after they have actually been deleted from disk.
     *
     * Flagging rather than deleting, which is what a re-sync does when it finds a file missing for
     * any other reason: the review decisions are the user's work and the file being gone was the
     * point, not a reason to throw them away. Both halves are flagged — the file's own state, so
     * it stops being counted as deletable, and any list rows pointing at it, so those lists say
     * "missing" exactly as they would after a re-sync.
     *
     * The decision itself is untouched, so a list cannot lose its completion by having its files
     * deleted, and nothing on this path can reach the webhook.
     */
    @Transaction
    open suspend fun markFilesDeleted(rootUri: String, relativePaths: List<String>) {
        setFilesMissing(rootUri, relativePaths, true)
        setListRowsMissing(rootUri, relativePaths, true)
    }

    /**
     * Clears the missing flag for files a folder resolution has just found present.
     *
     * A file that went away and came back is present again, and would otherwise stay excluded from
     * the checked-file count forever, because registration cannot update a row that already exists.
     */
    @Transaction
    open suspend fun markFilesPresent(rootUri: String, relativePaths: List<String>) {
        setFilesMissing(rootUri, relativePaths, false)
        setListRowsMissing(rootUri, relativePaths, false)
    }

    /**
     * The one write of a file's presence, and it lands on the Asset.
     *
     * Three things call it and they are all talking about the same file: a re-sync that did not
     * find it, a re-sync that did, and the delete that took it away. Each is a fact about the file
     * rather than about any List that happened to show it, which is why the flag is where the file
     * is and not on the registration a folder review resolves through.
     *
     * A file with no Asset is not written to, and does not need to be. Nothing has ever been
     * decided about it, so nothing can offer it for deletion and nothing reads its presence; the
     * row that would hold the flag does not exist yet. When a decision finally does arrive, the
     * Asset is minted and the next scan sets the flag on it like any other.
     */
    @Query(
        """
        UPDATE assets SET sourceMissing = :missing
        WHERE rootUri = :rootUri AND relativePath IN (:relativePaths)
          AND sourceMissing != :missing
        """
    )
    protected abstract suspend fun setFilesMissing(
        rootUri: String,
        relativePaths: List<String>,
        missing: Boolean
    )

    @Query(
        """
        UPDATE list_items SET sourceMissing = :missing
        WHERE rootRelativePath IN (:relativePaths)
          AND listId IN (SELECT id FROM lists WHERE sourceRootUri = :rootUri)
          AND sourceMissing != :missing
        """
    )
    protected abstract suspend fun setListRowsMissing(
        rootUri: String,
        relativePaths: List<String>,
        missing: Boolean
    )

    /** How many source-backed items in this list currently have no source file. */
    @Query(
        """
        SELECT COUNT(*) FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL AND sourceMissing = 1
        """
    )
    abstract suspend fun missingSourceItemCount(listId: Long): Int

    /**
     * Optional, explicitly confirmed cleanup: drops every source-backed item in this list whose
     * source file is gone, then recomputes completion. One transaction, so a failure leaves the
     * list untouched. Returns a row count, never a completion transition, so it cannot cause a
     * webhook to fire.
     */
    @Transaction
    open suspend fun removeMissingSourceItems(listId: Long, now: Long): Int {
        val removed = deleteMissingSourceItems(listId)
        refreshCompletion(listId, now)
        return removed
    }

    /** Read-only comparison, for the confirmation dialog. Writes nothing. */
    @Transaction
    open suspend fun previewReconcile(listId: Long, files: List<ScannedFile>): FolderDiff =
        diffAgainst(getSourceItems(listId), files)

    /**
     * Applies a re-sync in one transaction: appends the new files, flags vanished sources, and
     * restores ones that came back. Existing items keep their id, title, sortOrder, createdAt and
     * checked state; manual items are never selected in the first place.
     *
     * The diff is recomputed here from the same scan so the write is consistent with what the
     * database holds at commit time. Completion is recalculated, but this returns a plain diff and
     * never a completion transition, so it cannot cause a webhook to fire.
     *
     * [source] is passed in rather than resolved here, and that is the point of the parameter:
     * registering a Source is a write, so it is the caller's to consent to (§8.3). A re-sync used to
     * register one silently from inside this transaction, which meant a List's folder could be
     * brought into the catalog by pressing Update rather than by agreeing to an import.
     */
    @Transaction
    open suspend fun applyReconcile(
        listId: Long,
        source: SourceEntity,
        files: List<ScannedFile>,
        now: Long
    ): FolderDiff {
        val items = getSourceItems(listId)
        val diff = diffAgainst(items, files)
        val byPath = items.associateBy { it.sourceRelativePath }
        // The list's own folder, so an appended item gets the same file identity a freshly
        // created list would have given it. An appended file that was reviewed under some other
        // list, or in a folder Quick Review, arrives already carrying that decision.
        val list = getList(listId)
        val listPath = list?.sourceRelativePath.orEmpty()

        // A covered file-as-Task import creates or reconciles the Tasks for what the scan saw, so
        // an appended file's Task is resolved rather than minted on the write path. The caller has
        // already refused the case where the covering Source is folder-as-Task, which would fold
        // these files into groups rather than append them as their own Tasks.
        val taskIdsByGroup = if (list?.sourceRootUri != null) {
            runImportInternal(source.id, listPath, fileAsTaskPlan(listPath, files), now)
            taskImportsForSource(source.id).associate { it.groupPath to it.taskId }
        } else {
            emptyMap()
        }

        val appendFrom = maxSortOrder(listId) + 1
        val additions = diff.addedPaths.mapIndexedNotNull { index, path ->
            val file = files.firstOrNull { it.relativePath == path } ?: return@mapIndexedNotNull null
            val groupPath = rootRelativePathOf(listPath, file.relativePath)
            val taskId = taskIdsByGroup[groupPath] ?: return@mapIndexedNotNull null
            listItemForTask(taskId, listId, appendFrom + index, now, listPath)
        }
        insertItems(additions)
        // Only when the List actually grew — counted from the rows about to be written rather than
        // from the diff, which can name a path whose Task could not be resolved. A re-sync that
        // found nothing new is not the List moving on, and a note that vanished on every refresh
        // would be a note nobody could read.
        if (additions.isNotEmpty()) clearDissolutionNote(listId)

        diff.missingPaths.forEach { path ->
            byPath[path]?.takeIf { !it.sourceMissing }?.let { setSourceMissing(it.id, true) }
        }

        // A file that came back may be a different document, so refresh the pointer too.
        diff.restoredPaths.forEach { path ->
            val item = byPath[path] ?: return@forEach
            val file = files.firstOrNull { it.relativePath == path } ?: return@forEach
            restoreSource(item.id, file.uri)
        }

        // The same verdict, applied to the files themselves. A scan is the best evidence there is
        // about what exists, and things that are not this list read it: without this a file
        // deleted outside Listea would still be offered up as a checked file to delete, and one
        // that came back would stay excluded.
        list?.sourceRootUri?.let { rootUri ->
            setFilesMissing(rootUri, diff.missingPaths.map { rootRelativePathOf(listPath, it) }, true)
            setFilesMissing(rootUri, diff.restoredPaths.map { rootRelativePathOf(listPath, it) }, false)
        }

        refreshCompletion(listId, now)
        return diff
    }

    /**
     * Brings each source-backed row's cached measurements up to date with what a scan just saw.
     *
     * Membership is not touched: no row is added, removed, flagged or restored, and nothing here
     * can change a checked state or a completion. That is what makes it safe to run from the
     * read-only freshness check as well as from a re-sync — the two things a scan can tell you
     * are "the folder has changed" and "this file is 4 MB", and only the first is a decision for
     * the user to make.
     *
     * Only rows whose numbers actually moved are written. A list's page runs this on every entry,
     * and writing every row each time would re-emit the items flow — and rebuild the page — once
     * per visit for no change at all.
     *
     * Returns how many rows were touched, which is nothing but a test's way of asking.
     */
    @Transaction
    open suspend fun refreshSourceMetrics(listId: Long, files: List<ScannedFile>): Int {
        val byPath = files.associateBy { it.relativePath }
        var updated = 0
        getSourceItems(listId).forEach { item ->
            val file = byPath[item.sourceRelativePath] ?: return@forEach
            val unchanged = file.sizeBytes == item.sourceSizeBytes &&
                file.lastModified == item.sourceModifiedAt
            if (unchanged) return@forEach
            setSourceMetrics(item.id, file.sizeBytes, file.lastModified)
            updated++
        }
        return updated
    }

    /** Matches purely on relative path, so same-named files in different subfolders stay distinct. */
    private fun diffAgainst(items: List<ListItemEntity>, files: List<ScannedFile>): FolderDiff {
        val itemPaths = items.mapNotNull { it.sourceRelativePath }.toSet()
        val scanPaths = files.map { it.relativePath }.toSet()

        val added = files.map { it.relativePath }.filterNot { it in itemPaths }.sorted()
        val present = items.filter { it.sourceRelativePath in scanPaths }
        val absent = items.filter { it.sourceRelativePath !in scanPaths }

        return FolderDiff(
            addedPaths = added,
            missingPaths = absent.mapNotNull { it.sourceRelativePath }.sorted(),
            restoredPaths = present.filter { it.sourceMissing }.mapNotNull { it.sourceRelativePath }.sorted(),
            unchangedCount = present.size,
            newlyMissingCount = absent.count { !it.sourceMissing }
        )
    }

    /**
     * Sets one review action on one **Task–Asset pair**. Action metadata only: it deliberately does
     * not call [refreshCompletion] and returns nothing, so it can never report a completion
     * transition and therefore can never cause a webhook to be delivered.
     *
     * The address is the pair, because that is what a control is applied to: the tags a file is
     * offered are the Task's control resolved for that file's kind, so the answer belongs to that
     * file and not to the Task's other pages. v0.5.1 is the release that made the write say so —
     * before it, this took a `taskId` alone and a mark landed on every file the Task presented.
     *
     * [taskId] is carried beside the Asset even though the record is keyed by the Asset alone. It
     * is what the callers have, it is what makes the write legible in a log, and it is the half of
     * the pair the transaction is scoped to — the record is the Task's, filed under the file.
     *
     * Both kinds of action are one read-modify-write. Favourite is not a column of its own —
     * [ItemAction.Favourite] and a configured action differ in who decides what they mean, not in
     * where they are kept — and the read stays inside the transaction so two actions toggled at
     * once cannot each write a set that never saw the other.
     *
     * A pair whose Asset has gone is a silent no-op, like a write at a vanished Task and for the
     * same reason: nothing on a write path conjures a record for a file nobody can reach.
     */
    @Transaction
    open suspend fun setAssetAction(
        taskId: String,
        assetId: String,
        action: ItemAction,
        enabled: Boolean,
        now: Long
    ) {
        writeAssetState(assetId, now) { action.setOn(it, enabled) }
    }

    /**
     * Gives each of these files an identity to carry decisions on, without asserting a single
     * decision about any of them.
     *
     * IGNORE rather than REPLACE, with every field left at its default: resolving a folder for
     * review must never touch what is already there. This is what lets Quick Review hand its
     * queue a stable id per file while leaving a file reviewed last month exactly as it was.
     *
     * Registering is one of the two moments a file can acquire the name a receiver sees, and the
     * other is an import run creating its Asset. They are separate because either can come first —
     * a folder opened for review registers before anything has been imported, while a file reached
     * through a List gets its Task before anybody thinks to review it — and both call the same
     * idempotent assignment, so whichever arrives second finds the work already done.
     */
    @Transaction
    open suspend fun registerFiles(states: List<FileReviewStateEntity>) {
        insertRegistrations(states)
        // Chunked for the same reason the import run chunks: `assignLegacyItemIds` binds one
        // variable per path, and API 24-29 caps a statement at 999. A folder big enough to exceed
        // that is a folder the import path can already produce, and the two write the same ids.
        states.groupBy { it.rootUri }.forEach { (rootUri, rows) ->
            rows.map { it.relativePath }.chunked(PathBindChunk).forEach { chunk ->
                assignLegacyItemIds(rootUri, chunk)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRegistrations(states: List<FileReviewStateEntity>)

    /**
     * Every file under one root that anything is known about, for the browser's per-file ticks.
     *
     * Not scoped to a list, because the tick is not about a list: a file reviewed in a folder no
     * List has ever covered is checked, and the browser has to be able to say so. One query for
     * the whole screen, like the ownership it sits beside.
     *
     * **One row per file, ticked when any Task over it is ticked.** A file under two Todos has two
     * Tasks and so two states, and a browser row can only show one tick; leaving the join
     * unaggregated would emit the file twice and let whichever row the caller happened to keep
     * decide what the user sees. `MAX` is the honest collapse, and it is the same rule
     * [getCheckedFilePaths] uses to decide a file is deletable: a file means one thing on disk, and
     * any intention that called it done has called it done.
     *
     * It is a collapse and not an answer, which is why [observeFileClaims] exists beside it: a file
     * held by more than one Todo is shown ticked *and* marked, rather than quietly reported as the
     * vote of whichever row came first.
     */
    @Query(
        """
        SELECT frs.relativePath AS relativePath,
               COALESCE(MAX(ts.isCompleted), 0) AS isCompleted
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri
        GROUP BY frs.relativePath
        """
    )
    abstract fun observeFileCompletion(rootUri: String): Flow<List<FileCompletion>>

    /**
     * Every claim any Todo under [rootUri] has on a path, in both capacities, in one read.
     *
     * The first arm is the Tasks themselves — a file-as-Task Task's own file, a folder-as-Task
     * Task's own folder. The second is the files those Tasks cover, and it deliberately excludes
     * the rows the first arm already carries: `groupPath = relativePath` is a Task's own Asset, and
     * arm one holds that `(path, Todo)` pair already. Excluding it is not only half the rows on a
     * file-as-Task root — the common case — it is what lets `<>` mean "covers it rather than is it"
     * in one place.
     *
     * One read rather than two flows combined later, because the arms are the same vocabulary in
     * the same path space and have to be deduped *against each other*. Handed them separately, a
     * caller would have to decide what a file-as-Task Todo's file is, and that is a rule rather
     * than a lookup — so it belongs in `FolderListStatus.kt` where a unit test can reach it.
     *
     * `FROM sources s JOIN task_imports ti`, not the reverse, so the planner can drive from
     * `sources(rootUri, relativePath)` and reach `task_imports` through `(sourceId, groupPath)`
     * rather than scanning every Task in the database. Both arms filter `:rootUri` on both sides,
     * which is what makes "arm one subsumes arm two's equal-path rows" exactly true. `ORDER BY`
     * because a compound select has no order of its own, and owners that reordered between two
     * reads of unchanged data would be a flicker with no cause.
     */
    @Query(
        """
        SELECT ti.groupPath AS relativePath,
               ti.groupPath AS groupPath,
               s.id AS sourceId,
               s.displayName AS sourceName,
               ti.taskId AS taskId,
               ti.displayName AS taskName
        FROM sources s
        JOIN task_imports ti ON ti.sourceId = s.id
        WHERE s.rootUri = :rootUri
        UNION ALL
        SELECT a.relativePath AS relativePath,
               ti.groupPath AS groupPath,
               s.id AS sourceId,
               s.displayName AS sourceName,
               a.taskId AS taskId,
               ti.displayName AS taskName
        FROM assets a
        JOIN task_imports ti ON ti.taskId = a.taskId
        JOIN sources s ON s.id = ti.sourceId
        WHERE a.rootUri = :rootUri
          AND s.rootUri = :rootUri
          AND a.relativePath IS NOT NULL
          AND ti.groupPath <> a.relativePath
        ORDER BY relativePath
        """
    )
    abstract fun observeFileClaims(rootUri: String): Flow<List<FileClaim>>

    /**
     * Every Task over one file, with its Todo and what that Todo decided — the drill-in behind a
     * marked row.
     *
     * Keyed by the file rather than by a Task, because the question being answered is the one the
     * browser's mark raises: *which* Todos, and do they agree. Deliberately per file rather than
     * for the whole screen — a folder of shared files would answer with a row per Task over every
     * one of them, and nothing shows that until something is expanded.
     *
     * The tags come from `task_asset_state` since v0.5.1, and this is the place they are most
     * visibly *not* the Task's: the file is the same file in every row, and two Tasks over it can
     * disagree, which is exactly the disagreement this drill-in exists to show. Completion stays
     * the Task's, so one row can read `completed` and unmarked while the next reads the reverse.
     *
     * Left-joined, where `task_state` is inner: a Task with no state row is not a Task this app can
     * describe, but a pair with no record is simply a file nobody has tagged, and the row about it
     * is still worth showing — the file is claimed by the Task either way.
     */
    @Query(
        """
        SELECT s.id AS sourceId,
               s.displayName AS sourceName,
               ti.taskId AS taskId,
               ti.displayName AS taskName,
               COALESCE(ti.groupPath = a.relativePath, 0) AS isOwnTask,
               ts.isCompleted AS isCompleted,
               COALESCE(tas.selections, '') AS selections,
               ti.assetControls AS assetControls
        FROM assets a
        JOIN task_imports ti ON ti.taskId = a.taskId
        JOIN sources s ON s.id = ti.sourceId
        JOIN task_state ts ON ts.taskId = ti.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE a.rootUri = :rootUri AND a.relativePath = :relativePath
        ORDER BY s.createdAt, s.id
        """
    )
    abstract suspend fun getSharedFileStates(
        rootUri: String,
        relativePath: String
    ): List<SharedFileState>

    /**
     * The decisions currently held for these files, for a folder-resolved review queue.
     *
     * Left-joined where the list queries are inner, and the asymmetry is the point. A file reaches
     * this table by being *registered* — which happens whenever a folder is opened for review and
     * asserts nothing about the file — while a Task only exists once something has actually been
     * decided about it. So an undecided file legitimately has a row here and no Task, and it must
     * still come back: the queue's job is to show what the folder holds, and dropping the files
     * nobody has judged yet would leave it showing only the work already done.
     *
     * Two of the columns come from somewhere other than the registration, and both are the same
     * move: presence is the Asset's now, and so is the name a receiver sees. The name falls back
     * to the registration's own while there is no Asset to hang one on, and that fallback is not a
     * second naming scheme — [ListsDao.assignLegacyItemIds] writes the registration's value into
     * the Asset's row, so the two agree by construction and the fallback only covers the window
     * before either exists.
     */
    @Query(
        """
        SELECT frs.id AS id,
               COALESCE(MAX(li.publicId), frs.publicId) AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(MAX(ts.isCompleted), 0) AS isCompleted,
               -- The retired pair, folded for the group by like everything else here. Dead columns
               -- the entity still declares: see `FileReviewStateEntity.customActions`.
               COALESCE(MAX(frs.isFavorite), 0) AS isFavorite,
               COALESCE(MAX(frs.customActions), '') AS customActions,
               COALESCE(MAX(tas.selections), '') AS selections,
               COALESCE(MAX(a.sourceMissing), 0) AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN legacy_item_ids li ON li.assetId = a.id
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
        GROUP BY frs.relativePath
        """
    )
    abstract fun observeFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): Flow<List<FileReviewStateEntity>>

    /**
     * The same, once, for building a payload out of what is stored right now.
     *
     * Which is where a folder round's item ids come from, and why [FileReviewStateEntity.publicId]
     * is not what a receiver is handed any more: the name is read off `legacy_item_ids`, the side
     * table that exists to outlive this one. Nothing is sent from here that a List round would
     * send differently — the ids are the same strings either way, because the value written into
     * the side table is this row's own.
     */
    @Query(
        """
        SELECT frs.id AS id,
               COALESCE(MAX(li.publicId), frs.publicId) AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(MAX(ts.isCompleted), 0) AS isCompleted,
               -- The retired pair, folded for the group by like everything else here. Dead columns
               -- the entity still declares: see `FileReviewStateEntity.customActions`.
               COALESCE(MAX(frs.isFavorite), 0) AS isFavorite,
               COALESCE(MAX(frs.customActions), '') AS customActions,
               COALESCE(MAX(tas.selections), '') AS selections,
               COALESCE(MAX(a.sourceMissing), 0) AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN legacy_item_ids li ON li.assetId = a.id
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
        GROUP BY frs.relativePath
        """
    )
    abstract suspend fun getFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): List<FileReviewStateEntity>

    /**
     * Every list containing this Task, so a decision about it can reach all of them.
     *
     * Index-backed by `index_list_items_taskId`: a file in two Lists returns both, a Task no List
     * covers returns empty, and two Lists over one folder share one Task so still name it together.
     */
    @Query(
        """
        SELECT DISTINCT l.id
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        WHERE i.taskId = :taskId
        """
    )
    protected abstract suspend fun listsContainingTask(taskId: String): List<Long>

    @Query(
        """
        SELECT id, event, listTitle, itemCount, at, outcome, detail, kind, webhookUrl, roundId
        FROM webhook_history
        ORDER BY at DESC, id DESC
        """
    )
    abstract fun observeWebhookHistory(): Flow<List<ExportRecordSummary>>

    /**
     * How many rounds the history holds.
     *
     * Rows written before v0.6.2 have no round id and were each a round of one, so an empty id is
     * counted under a key derived from the row rather than all blanks collapsing into one round.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT CASE WHEN roundId = '' THEN 'row:' || id ELSE roundId END)
        FROM webhook_history
        """
    )
    abstract fun observeWebhookRoundCount(): Flow<Int>

    /** Everything ever recorded, for the Settings line that says how big the history is. */
    @Query("SELECT COUNT(*) FROM webhook_history")
    abstract fun observeWebhookRecordCount(): Flow<Int>

    /**
     * The records that still have somewhere to go: anything that did not come back successful.
     *
     * Counted separately from the total because they mean different things on the Settings page —
     * the total is how much history there is, and this is how much of it is still owed to a
     * receiver. Only this one is worth a warning tint.
     */
    @Query("SELECT COUNT(*) FROM webhook_history WHERE outcome NOT IN ('SENT', 'WRITTEN')")
    abstract fun observePendingWebhookCount(): Flow<Int>

    @Insert
    abstract suspend fun insertWebhookRecord(record: ExportRecordEntity): Long

    @Query("SELECT * FROM webhook_history WHERE id = :id")
    abstract suspend fun getWebhookRecord(id: Long): ExportRecordEntity?

    /**
     * Moves a record on to its newest verdict, after a resend.
     *
     * The row is updated rather than a second one appended, because a record *is* one payload:
     * the history is a list of bodies and what became of each, not a log of every attempt at one.
     * Appending would turn a receiver that was down for an hour into a page the user has to
     * clean up by hand. The payload column is deliberately not touched — what is resent is byte
     * for byte what was built the first time.
     */
    @Query("UPDATE webhook_history SET at = :at, outcome = :outcome, detail = :detail WHERE id = :id")
    abstract suspend fun updateWebhookRecord(id: Long, at: Long, outcome: String, detail: String)

    @Query("DELETE FROM webhook_history WHERE id = :id")
    abstract suspend fun deleteWebhookRecord(id: Long)

    @Query("UPDATE lists SET reviewCurrentItemId = :itemId WHERE id = :id")
    abstract suspend fun setReviewPosition(id: Long, itemId: Long?)

    /** The column keeps the old word — see [com.example.listea.data.ListEntity.exportOnCompletion]. */
    @Query("UPDATE lists SET webhookEnabled = :enabled WHERE id = :id")
    abstract suspend fun setExportOnCompletion(id: Long, enabled: Boolean)

    /** The List's own kind, or `inherit`/`none`. Encoded by `domain/Exports.kt`. */
    @Query("UPDATE lists SET exportKind = :kind WHERE id = :id")
    abstract suspend fun setListExportKind(id: Long, kind: String)

    /**
     * The List's optional address override. Empty means follow each Todo's own address.
     *
     * Written only after the caller has validated a non-empty value, so what sits here is either
     * an address `webhookUrlError` accepts or the empty string that means *no override*.
     */
    @Query("UPDATE lists SET webhookUrl = :url WHERE id = :id")
    abstract suspend fun setListWebhookUrl(id: Long, url: String)

    @Query(
        """
        UPDATE lists
        SET lastDeliveryAt = :at,
            lastDeliveryStatus = :status,
            lastDeliveryCode = :code,
            lastDeliveryError = :error
        WHERE id = :id
        """
    )
    abstract suspend fun recordDelivery(
        id: Long,
        at: Long,
        status: String,
        code: Int?,
        error: String?
    )

    /**
     * A list counts as complete once it holds at least one item and none are left unchecked.
     * An already recorded completedAt is kept, so the original completion time survives edits
     * that do not break completion.
     */
    @Transaction
    open suspend fun refreshCompletion(listId: Long, now: Long) {
        if (itemCount(listId) > 0 && unfinishedItemCount(listId) == 0) {
            markCompleted(listId, now)
        } else {
            clearCompleted(listId)
        }
    }

    /**
     * Only source-backed items take part in reconciliation; manual items are excluded here.
     *
     * `SELECT *`, and deliberately not a resolved projection like [getItems]: every caller is
     * asking about *membership* — which paths are present, which have gone, how big they are — and
     * none of them asks what was decided. The decision columns on these rows are therefore not the
     * state, only whatever those columns happen to hold; reading one from here would be reading a
     * copy nothing keeps up to date. Should a caller ever need a decision, it wants [getItems] or
     * a join, not this.
     */
    @Query(
        """
        SELECT * FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL
        ORDER BY sortOrder ASC, id ASC
        """
    )
    protected abstract suspend fun getSourceItems(listId: Long): List<ListItemEntity>

    /** The sourceRelativePath guard keeps manual items out of the delete, regardless of state. */
    @Query(
        """
        DELETE FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL AND sourceMissing = 1
        """
    )
    protected abstract suspend fun deleteMissingSourceItems(listId: Long): Int

    @Query("UPDATE list_items SET sourceMissing = :missing WHERE id = :id")
    protected abstract suspend fun setSourceMissing(id: Long, missing: Boolean)

    @Query(
        """
        UPDATE list_items
        SET sourceSizeBytes = :sizeBytes, sourceModifiedAt = :modifiedAt
        WHERE id = :id
        """
    )
    protected abstract suspend fun setSourceMetrics(id: Long, sizeBytes: Long?, modifiedAt: Long?)

    @Query("UPDATE list_items SET sourceMissing = 0, sourceUri = :sourceUri WHERE id = :id")
    protected abstract suspend fun restoreSource(id: Long, sourceUri: String)

    @Insert
    protected abstract suspend fun insertItem(item: ListItemEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<ListItemEntity>)

    // --- the v13 tables --------------------------------------------------------------------------
    //
    // `task_state` is where a decision lives, and `tasks` / `assets` are how one is found: through
    // the membership row when a List is asking, and through the file's path when a folder is.
    // `file_review_state` still carries a file's registration, its path under the root and its
    // presence flag, and is the row a folder review resolves — but no decision is read from it or
    // written to it any more, and neither is one read from or written to a `list_items` column.
    // Those columns are still there, still holding whatever they held, and nothing consults them;
    // they go in a later release once no reader is provable, which is what the tests here are for.
    //
    // Room turns foreign keys on, so a `list_items` row whose Task was never minted is not a row
    // that reads oddly — it is a row that cannot be written at all. The same holds for a decision,
    // which has nowhere to go until its Task exists. That is why the minting lives beside the
    // writes rather than in the migration alone.

    @Insert
    protected abstract suspend fun insertTask(task: TaskEntity)

    @Insert
    protected abstract suspend fun insertAsset(asset: AssetEntity)

    /**
     * An Asset and its pair record, together, which is the only way an Asset is ever written.
     *
     * The two tables are one fact about a Task's file — what it is, and what the control governing
     * it records — and they are written in one statement pair inside the import run's transaction so
     * that a reader never sees an Asset with no record. That is what lets every read path take the
     * record without a `COALESCE`, the same property `task_state` has had since v0.2 and for the
     * same reason.
     *
     * Defaults, deliberately. A new Asset has had nothing recorded about it, and the import run is
     * not entitled to assert a decision on a file it has just discovered — the same rule
     * [registerFiles] gives for a folder it has just opened.
     */
    protected suspend fun insertAssetWithState(asset: AssetEntity, now: Long) {
        insertAsset(asset)
        insertAssetState(AssetStateEntity(assetId = asset.id, updatedAt = now))
    }

    @Insert
    protected abstract suspend fun insertTaskState(state: TaskStateEntity)

    @Insert
    protected abstract suspend fun insertReviewEvent(event: ReviewEventEntity)

    @Insert
    protected abstract suspend fun insertReviewEventAnswer(answer: ReviewEventAnswerEntity)

    // --- the manual-item cleanup path --------------------------------------------------------------
    //
    // Manual items are the one kind of Task whose life is tied to a membership: there is no file
    // holding the decision, so when the row that was the only way to reach it goes, the Task has
    // nothing left to be for. `legacy_manual_tasks` names them, and these queries are the whole of
    // how that name is used - read while the membership still exists, applied after it does not.

    /** The Tasks of one List the migration named as manual items, read before the List goes. */
    @Query(
        """
        SELECT i.taskId FROM list_items i
        JOIN legacy_manual_tasks m ON m.taskId = i.taskId
        WHERE i.listId = :listId
        """
    )
    protected abstract suspend fun manualTasksOfList(listId: Long): List<String>

    /** The same for every List under one SAF root, for the path that gives a root up. */
    @Query(
        """
        SELECT i.taskId FROM list_items i
        JOIN legacy_manual_tasks m ON m.taskId = i.taskId
        JOIN lists l ON l.id = i.listId
        WHERE l.sourceRootUri = :rootUri
        """
    )
    protected abstract suspend fun manualTasksUnderRoot(rootUri: String): List<String>

    /** The same for a single membership row. */
    @Query(
        """
        SELECT i.taskId FROM list_items i
        JOIN legacy_manual_tasks m ON m.taskId = i.taskId
        WHERE i.id = :itemId
        """
    )
    protected abstract suspend fun manualTaskOfItem(itemId: Long): List<String>

    /** The Tasks themselves. Their state and any Asset go with them, both of which cascade. */
    @Query("DELETE FROM tasks WHERE id IN (:taskIds)")
    protected abstract suspend fun deleteTasks(taskIds: List<String>)

    /**
     * Drops the names of Tasks that have just been deleted.
     *
     * The table says which Tasks *are* manual items, so a row left behind would have it claiming
     * a set it no longer describes - and the next reader of this table, whatever it is for, would
     * be handed a Task id that names nothing. The set only ever shrinks, and this is the shrinking.
     */
    @Query("DELETE FROM legacy_manual_tasks WHERE taskId IN (:taskIds)")
    protected abstract suspend fun forgetManualTasks(taskIds: List<String>)

    /**
     * Removes the manual Tasks of a gone membership, and the names of them, in one step.
     *
     * Empty is the ordinary case rather than an edge: most deletes are of source-backed Tasks,
     * whose decisions live on the file and must survive the List that showed them. An empty set
     * needs nothing said about it - SQLite accepts an empty `IN ()` and matches nothing, which is
     * the same assumption [markFilesDeleted] and the rest of this DAO already run on.
     */
    private suspend fun deleteManualTasks(taskIds: List<String>) {
        deleteTasks(taskIds)
        forgetManualTasks(taskIds)
    }

    @Query("SELECT * FROM task_state WHERE taskId = :taskId")
    protected abstract suspend fun getTaskState(taskId: String): TaskStateEntity?

    // --- the v21 pair records ----------------------------------------------------------------------
    //
    // `task_asset_state` is where the template's instance lives, and `assets` is how one is found:
    // the pair's primary key is the Asset, and the Task is reached through it. Nothing here is
    // addressed by Task alone, because nothing in this table is about a Task alone — a caller that
    // has only a Task wants [getTaskAssetStates] or [unionOf], both of which are the fold rather
    // than a decision.

    @Insert
    protected abstract suspend fun insertAssetState(state: AssetStateEntity)

    @Query("SELECT * FROM task_asset_state WHERE assetId = :assetId")
    protected abstract suspend fun getAssetState(assetId: String): AssetStateEntity?

    /**
     * One pair's selection set, replaced whole.
     *
     * The two columns beside it are left exactly as the 23 → 24 step wrote them: dead, and the only
     * copy of a mark that exists if [selections] is ever found to be wrong. Nothing writes them
     * again — see [AssetStateEntity.selections].
     */
    @Query(
        """
        UPDATE task_asset_state
        SET selections = :selections,
            updatedAt = :updatedAt
        WHERE assetId = :assetId
        """
    )
    protected abstract suspend fun updateAssetState(
        assetId: String,
        selections: String,
        updatedAt: Long
    )

    /**
     * Every pair record of these Tasks.
     *
     * Scoped by Task through `assets`, which is the join decision 2 chose over a duplicated
     * `taskId` column: `assets` is indexed on `taskId` and the Asset's key is what the record hangs
     * off, so the Task's own rows reach their records without a second copy of a fact that could
     * drift.
     *
     * A Task with no Asset contributes nothing, which is the no-middle-band rule rather than a gap:
     * there is no pair to have a record.
     */
    @Query(
        """
        SELECT tas.* FROM task_asset_state tas
        JOIN assets a ON a.id = tas.assetId
        WHERE a.taskId IN (:taskIds)
        """
    )
    abstract suspend fun getTaskAssetStates(taskIds: List<String>): List<AssetStateEntity>

    /**
     * The same, as a live read: what a review round draws while it is open.
     *
     * [observeTaskStates]' twin and watched for the same reason — a chip tapped on the file the user
     * is looking at is the normal way through a round, and a surface that drew its controls from the
     * queue it built on entry would write every tap to the database and show nothing. Both flows are
     * watched by the same round, because a round draws completion and the marks together.
     */
    @Query(
        """
        SELECT tas.* FROM task_asset_state tas
        JOIN assets a ON a.id = tas.assetId
        WHERE a.taskId IN (:taskIds)
        """
    )
    abstract fun observeTaskAssetStates(taskIds: List<String>): Flow<List<AssetStateEntity>>

    @Query(
        """
        UPDATE task_state
        SET isCompleted = :isCompleted,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """
    )
    protected abstract suspend fun updateTaskState(
        taskId: String,
        isCompleted: Boolean,
        updatedAt: Long
    )

    /**
     * Read-modify-write of one Task's current completion, and the only way it is ever changed.
     *
     * The completion half of what used to be the whole of a Task's state. Every write that moves a
     * checkbox — a List's own row, a swipe in Quick Review, a submission — funnels through here,
     * which is what makes "is this Task done" a single fact rather than a value that four writes
     * agree to keep in step. The read is inside the caller's transaction, so two writes that race
     * cannot each land a value that never saw the other.
     *
     * It used to carry `isFavorite` and `customActions` too. Those are the pair's now — see
     * [writeAssetState] — and this method deliberately does not touch the two columns they left
     * behind, so what the migration copied out of them stays readable for a Task that has no Asset
     * to have copied it to.
     *
     * Update-only, deliberately. A Task's state row is created with the Task — the import run
     * inserts it, the migration inserts it — so an absent row does not mean "this is the first
     * thing to touch the Task", it means the Task has gone. A write addressed at a vanished Task is
     * therefore a silent no-op (zero rows updated) rather than a mint or a foreign-key violation:
     * nothing on a write path is allowed to conjure a replacement Task because an address missed.
     *
     * Returns null when there was no row to update, so [submitReview] can tell a no-op from a
     * write and decline to record an event against a Task that no longer exists.
     */
    protected open suspend fun writeTaskState(
        taskId: String,
        now: Long,
        change: (Boolean) -> Boolean
    ): Boolean? {
        val existing = getTaskState(taskId) ?: return null
        val next = change(existing.isCompleted)
        updateTaskState(taskId = taskId, isCompleted = next, updatedAt = now)
        return next
    }

    /**
     * Read-modify-write of one Task–Asset pair's record, and the only way it is ever changed.
     *
     * [writeTaskState]'s sibling, and every one of its rules for the same reasons — the read inside
     * the caller's transaction, update-only, and null when there was no row rather than a mint. The
     * difference is the address: this one is a *pair*, so a write that names an Asset the import run
     * has taken away no-ops instead of resurrecting a record for a file nobody can see.
     *
     * Two actions toggled at once on one file cannot each write a set that never saw the other for
     * exactly the reason the completion funnel gives: the read-modify-write is one transaction.
     */
    protected open suspend fun writeAssetState(
        assetId: String,
        now: Long,
        change: (AssetDecisions) -> AssetDecisions
    ): AssetDecisions? {
        val existing = getAssetState(assetId) ?: return null
        val next = change(existing.decisions)
        updateAssetState(
            assetId = assetId,
            selections = encodeActionIds(next.selections),
            updatedAt = now
        )
        return next
    }

    /**
     * Hands these files the name a receiver knows them by, once there is an Asset to hang it on.
     *
     * The value is not invented here — it is the registration's own `publicId`, copied across.
     * That is what makes this a move rather than a second naming scheme, and what keeps a
     * receiver's ids stable through it: every payload a file has ever produced named it by this
     * string, and it goes on doing so. The fallback in [getFileStates] is the same string for the
     * same reason, not a rival answer.
     *
     * A file with no registration, or no Asset yet, matches nothing and is left alone. The table
     * fills as files arrive rather than in one pass, because there is no later point at which
     * every file is known; `OR IGNORE` is what lets both call sites run without asking which of
     * them got there first.
     *
     * **One Asset per file is named, and it is the first one to be named that keeps it.** The
     * table's key is the Asset, which was the same thing as the file while a file could only be
     * one Task's; now that two Todos may hold it, naming every Asset at a path would hand two
     * Tasks the same id and a receiver two rows it cannot tell apart. So the row goes to one of
     * them — the `NOT EXISTS` guard means whichever Asset is named first is the only one ever
     * named. The rest of the file's readers are unaffected either way: the file-state reads fall
     * back to the registration's own `publicId`, which is the same string, on every row that has no
     * Asset to have copied it to. Since schema 26 every Task and Asset also carries its own
     * `publicId`; this table survives as the source the 25 → 26 backfill reads to keep old ids.
     */
    @Query(
        """
        INSERT OR IGNORE INTO legacy_item_ids (assetId, publicId)
        SELECT a.id, frs.publicId
        FROM assets a
        JOIN file_review_state frs
          ON frs.rootUri = a.rootUri AND frs.relativePath = a.relativePath
        WHERE a.rootUri = :rootUri AND a.relativePath IN (:relativePaths)
          AND a.id = (
              SELECT MIN(a2.id) FROM assets a2
              WHERE a2.rootUri = a.rootUri AND a2.relativePath = a.relativePath
          )
          AND NOT EXISTS (
              SELECT 1 FROM legacy_item_ids li
              JOIN assets named ON named.id = li.assetId
              WHERE named.rootUri = a.rootUri AND named.relativePath = a.relativePath
          )
        """
    )
    protected abstract suspend fun assignLegacyItemIds(rootUri: String, relativePaths: List<String>)

    @Query("DELETE FROM list_items WHERE id = :id")
    protected abstract suspend fun deleteItem(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM list_items WHERE listId = :listId")
    protected abstract suspend fun maxSortOrder(listId: Long): Int

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId")
    protected abstract suspend fun itemCount(listId: Long): Int

    /**
     * How many of a list's items are still unchecked, by the state that actually counts.
     *
     * This is what decides whether a list is complete, so it reads the same value the user sees —
     * the Task's, which is also what a folder's Quick Review writes. Reading the row's own column
     * here would leave a list permanently incomplete the moment its files were checked from
     * somewhere other than the list.
     *
     * Left-joined with a `COALESCE` so it agrees with [itemCount], which counts `list_items`
     * outright: a membership whose Task somehow lacked state would otherwise vanish from this
     * count and mark a list complete on the rows that remain. Unreachable in practice — a Task's
     * state is created with it — but the count that decides completion should not silently drop a
     * row on an invariant rather than failing it.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM list_items i
        LEFT JOIN task_state ts ON ts.taskId = i.taskId
        WHERE i.listId = :listId AND COALESCE(ts.isCompleted, 0) = 0
        """
    )
    protected abstract suspend fun unfinishedItemCount(listId: Long): Int

    @Query("UPDATE lists SET completedAt = :now WHERE id = :id AND completedAt IS NULL")
    protected abstract suspend fun markCompleted(id: Long, now: Long)

    @Query("UPDATE lists SET completedAt = NULL WHERE id = :id")
    protected abstract suspend fun clearCompleted(id: Long)

    /** Null when the list no longer exists, e.g. it was deleted while an edit was in flight. */
    @Query("SELECT completedAt IS NOT NULL FROM lists WHERE id = :id")
    protected abstract suspend fun isComplete(id: Long): Boolean?

    // --- the v14 import tables ----------------------------------------------------------------------
    //
    // The only thing allowed to create a Task is an import run, and this is where that rule lives.
    // `runImport` is the sole writer of `tasks`, `task_state` and `assets`; everything else selects
    // Tasks that already exist. A Source is registered separately, so that "bring material in" and
    // "choose what to review" stay two operations whose failure states are not each other's.

    @Query("SELECT * FROM sources WHERE id = :id")
    abstract suspend fun getSource(id: String): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY createdAt DESC, id DESC")
    abstract fun observeSources(): Flow<List<SourceEntity>>

    // ---------------------------------------------------------------------------------------
    // Upstreams (v16): the folders material comes from, as their own table.
    //
    // Read and written through their own handful of queries rather than through the Source ones
    // above. The two are different objects with different lifetimes — an Upstream is written once
    // when a folder is picked and never by an import, a Source only ever by one — and the whole
    // point of v16 is that neither is allowed to answer for the other.
    // ---------------------------------------------------------------------------------------

    @Query("SELECT * FROM upstreams ORDER BY addedAt DESC, id DESC")
    abstract fun observeUpstreams(): Flow<List<UpstreamEntity>>

    /** The same, once, for a caller that has to look rather than watch. */
    @Query("SELECT * FROM upstreams ORDER BY addedAt DESC, id DESC")
    abstract suspend fun getUpstreams(): List<UpstreamEntity>

    @Query("SELECT * FROM upstreams WHERE id = :id")
    abstract suspend fun getUpstream(id: String): UpstreamEntity?

    @Query("SELECT * FROM upstreams WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun upstreamAt(rootUri: String, relativePath: String): UpstreamEntity?

    @Query("SELECT * FROM upstreams WHERE rootUri = :rootUri")
    protected abstract suspend fun upstreamsForRoot(rootUri: String): List<UpstreamEntity>

    @Insert
    protected abstract suspend fun insertUpstream(upstream: UpstreamEntity)

    @Query("DELETE FROM upstreams WHERE id = :id")
    abstract suspend fun deleteUpstream(id: String)

    // --- dissolution ---------------------------------------------------------------------------

    /**
     * Deletes a Todo and everything reachable only through it, writing its judgments into
     * `dissolved_task_state` first when [keepState] is set.
     *
     * **This is the only path in the app that deletes a Task, and it is the only one that should
     * ever be.** A Task belongs to exactly one Todo, so when the intention goes the Tasks it
     * produced go with it — which is what makes the *Todo* the honest unit of abandonment rather
     * than deleting Tasks one at a time. Nothing here reads an Asset count, or anything else about
     * a Task's shape: `ARCHITECTURE.md` forbids deriving lifetime from structure, and the one place
     * that gets near it (an import told to drop a Task, step 2b) is an explicit policy a user names
     * rather than an inference this function makes.
     *
     * **The order is the whole of the safety**, and the foreign keys enforce it rather than a
     * comment doing so. `list_items.taskId` is `NO ACTION`, so a membership left behind fails the
     * delete loudly instead of a Task being taken by cascade; `task_imports.sourceId` and the
     * three Package tables' `sourceId` are `NO ACTION` too, so those rows must fall before the
     * Source can go.
     *
     * 1. read the Source, the Task count and the Lists that hold them — a snapshot's `sourceName`
     *    and the report both need facts that will not exist a moment later;
     * 2. snapshot, **before** anything is removed, or there is nothing left to copy;
     * 3. memberships;
     * 4. Tasks, which cascade `assets`, `task_state`, `task_imports` and the legacy id links;
     * 5. the Package rows — checkpoint, Prompt links, Resources — whose `sourceId` foreign keys
     *    are NO ACTION as well, and whose Prompt links can outlive a Task the revision stopped
     *    naming;
     * 6. the Source row;
     * 7. tell each List that lost rows, so none of them silently gets shorter.
     *
     * Returns `null` when the Todo is already gone, having touched nothing. The caller reaches this
     * from a card it just drew, so the race is theoretical — but a delete that guessed past a
     * missing Source would be a delete that could not report what it did.
     *
     * Nothing here touches files on disk, `file_review_state`, `legacy_item_ids` or `review_events`.
     * The first is not this operation's business. The rest are what makes a dissolution survivable:
     * a file keeps its public id because `file_review_state.publicId` is keyed by path and outlives
     * the Task, and history is append-oriented by design, so its rows stay and simply stop being
     * reachable from anything.
     */
    @Transaction
    open suspend fun deleteTodo(
        sourceId: String,
        keepState: Boolean,
        now: Long
    ): DissolutionReport? {
        val source = getSource(sourceId) ?: return null
        val taskCount = countTasksOfTodo(sourceId)
        val held = listsHolding(sourceId)
        if (keepState) snapshotDissolvedState(sourceId, now)
        deleteListItemsOfTodoTasks(sourceId)
        deleteTasksOfTodo(sourceId)
        // The Package tables name the Todo with NO ACTION foreign keys, like `task_imports`, so
        // they have to fall before the Source row. `PackageImportTest` found this on device: a
        // Package Todo refused to delete and the app could only say "Nothing was changed".
        // Prompt links usually went with their Tasks by cascade; this also clears links whose
        // Task is already gone, which is the state reconciliation leaves behind.
        deletePackagePromptTasksOfTodo(sourceId)
        deletePackageResourcesOfTodo(sourceId)
        deletePackageCheckpointsOfTodo(sourceId)
        deleteSourceRow(sourceId)
        for (row in held) {
            noteDissolution(row.listId, row.itemCount, now, DissolutionReason.Dissolved.key)
            // The rows that went took their answer with them. A List waiting on this Todo's one
            // unfinished Task is finished now, and nothing else was going to say so; a List that
            // was complete and has just been emptied is not finished, which is the same rule
            // `refreshCompletion` applies everywhere else. The note above says what was lost and
            // this says what is left, and neither implies the other.
            refreshCompletion(row.listId, now)
        }
        return DissolutionReport(
            sourceName = source.displayName,
            deletedTaskCount = taskCount,
            lists = held,
            keptState = keepState
        )
    }

    /**
     * How many Tasks this Todo holds. Not `protected`: the confirmation reads it too, so the dialog
     * can say *"3 Tasks and their decisions"* from the same statement the report will later be built
     * from, rather than from a second count that could disagree with it.
     */
    @Query("SELECT COUNT(*) FROM task_imports WHERE sourceId = :sourceId")
    abstract suspend fun countTasksOfTodo(sourceId: String): Int

    /**
     * Every List holding a Task of this Todo, and how many rows of it will go.
     *
     * Written against `sourceId` rather than a list of Task ids, so it does not care how large the
     * Todo is: a file-as-Task Todo over a big folder holds one Task per file, and an `IN (:taskIds)`
     * would meet the 999-variable ceiling [PathBindChunk] exists for. Joining the provenance rows is
     * also exactly what the delete below does, so the two cannot drift apart about what "this Todo's
     * Tasks" means.
     *
     * Read twice, for two jobs: by the caller before the dialog is shown, so the confirmation can
     * name every List rather than total them, and inside the transaction, so each can be told what
     * it lost.
     */
    @Query(
        """
        SELECT li.listId AS listId, l.title AS title, COUNT(*) AS itemCount
        FROM list_items li
        JOIN lists l ON l.id = li.listId
        JOIN task_imports ti ON ti.taskId = li.taskId
        WHERE ti.sourceId = :sourceId
        GROUP BY li.listId
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    abstract suspend fun listsHolding(sourceId: String): List<ListLosingItems>

    /**
     * Every Task of the Todo, with one row per file it holds and the Task's own facts repeated on
     * each. Read, grouped and encoded by [snapshotDissolvedState] into one row per Task.
     *
     * `LEFT JOIN` on both sides with `COALESCE`, because a judgment that was never made is a fact
     * worth keeping — an unmarked file's history is what lets a recovery put *that* file back to
     * unmarked without touching its neighbour, and an inner join would silently drop those rows.
     * `LEFT JOIN assets` is the other half of the same rule: a manual item has no file, and it still
     * has a completion to record. [DissolvedSnapshotRow.assetPath] being null is exactly that case.
     *
     * `s.relativePath` — the Todo's own folder — is copied rather than left to be inferred from the
     * group paths, because it is what decides which folder offers this history back.
     *
     * Ordered by Task and then by `a.sortOrder`, which is the order the review surface pages the
     * files in: the payload is read back as a Task's files, so a snapshot that reordered them would
     * reorder the pages behind a restore.
     */
    @Query(
        """
        SELECT ti.taskId AS taskId, ti.groupPath AS groupPath, s.displayName AS sourceName,
               s.rootUri AS rootUri, s.relativePath AS sourcePath, s.importerKind AS importerKind,
               COALESCE(ts.isCompleted, 0) AS isCompleted,
               a.relativePath AS assetPath,
               COALESCE(tas.selections, '') AS selections
        FROM task_imports ti
        JOIN sources s ON s.id = ti.sourceId
        LEFT JOIN task_state ts ON ts.taskId = ti.taskId
        LEFT JOIN assets a ON a.taskId = ti.taskId
        LEFT JOIN task_asset_state tas ON tas.assetId = a.id
        WHERE ti.sourceId = :sourceId
        ORDER BY ti.taskId, a.sortOrder
        """
    )
    protected abstract suspend fun dissolvedSnapshotRows(sourceId: String): List<DissolvedSnapshotRow>

    @Insert
    protected abstract suspend fun insertDissolvedState(row: DissolvedTaskStateEntity)

    /**
     * The snapshot itself: one row per Task this Todo holds, keyed by the path the Task was made
     * over.
     *
     * Read, encoded, written — three steps rather than one `INSERT … SELECT`, because the judgments
     * are per *file* and belong to the Assets while the row is per Task. Nothing before SQLite 3.33
     * can fold a group's rows into one ordered string, and the encoding is [encodeDissolvedRecords]'
     * business rather than SQL's. `recoverStateFrom` makes the same move in the other direction, for
     * the same reason.
     *
     * [DissolvedSnapshotRow.assetPath] is nullable and stays that way here: a file that cannot be
     * named cannot be matched on the way back, so it is left out of the payload rather than spelled
     * as a name a recovery would look for and never find. Its Task's completion is recorded either
     * way — a Task with no file is a Task, and its history is not empty.
     *
     * Runs inside [deleteTodo]'s transaction, **before** anything is removed: the ordering the
     * comment above already gives as the whole of the safety. A read that happened afterwards would
     * have nothing left to copy.
     */
    @Transaction
    open suspend fun snapshotDissolvedState(sourceId: String, now: Long) {
        dissolvedSnapshotRows(sourceId)
            .groupBy { it.taskId }
            .forEach { (_, rows) ->
                val task = rows.first()
                insertDissolvedState(
                    DissolvedTaskStateEntity(
                        sourceId = sourceId,
                        sourceName = task.sourceName,
                        rootUri = task.rootUri,
                        sourcePath = task.sourcePath,
                        groupPath = task.groupPath,
                        importerKind = task.importerKind,
                        isCompleted = task.isCompleted,
                        recordVersion = DISSOLVED_RECORDS_VERSION,
                        records = encodeDissolvedRecords(
                            rows.mapNotNull { row ->
                                row.assetPath?.let { path ->
                                    DissolvedFileRecord(
                                        relativePath = path,
                                        decisions = AssetDecisions(
                                            selections = decodeActionIds(row.selections)
                                        )
                                    )
                                }
                            }
                        ),
                        dissolvedAt = now
                    )
                )
            }
    }

    /**
     * The same removal, for one Task rather than a whole Todo — what `drop` does to a Task whose
     * content changed.
     *
     * **Deliberately the same three steps in the same order**, because a drop takes exactly what a
     * dissolution takes from the Lists and nothing else: the memberships, then the Task (which
     * cascades its Assets, its `task_state` and its provenance row), then the note each List is left
     * with. The one thing it does *not* do is snapshot — a history belongs to a Todo, and a Task
     * re-minted in place under a Todo that still exists has no dissolution to be recovered from.
     *
     * The reason on the note is the other difference, and it is the whole point of the reason
     * column: a List that loses a membership here was not orphaned by a delete, and saying it was
     * would be the note lying in the voice of the feature that exists to stop it lying.
     */
    protected suspend fun dropTask(taskId: String, now: Long) {
        val held = listsHoldingTask(taskId)
        deleteListItemsOfTask(taskId)
        deleteTaskRow(taskId)
        for (row in held) {
            noteDissolution(row.listId, row.itemCount, now, DissolutionReason.Dropped.key)
            // `drop` is a dissolution of one Task and the Lists it leaves behind are in exactly
            // the state a whole-Todo delete would leave them in, so they get the same re-answer.
            refreshCompletion(row.listId, now)
        }
    }

    /**
     * Every List holding one Task, and how many rows of it will go.
     *
     * The per-Task twin of [listsHolding], and separate from it rather than parameterised over a
     * task-id list: the two are asked at different times about different things, and the Todo-wide
     * one is written against `sourceId` precisely so it never has to bind a list of ids.
     */
    @Query(
        """
        SELECT li.listId AS listId, l.title AS title, COUNT(*) AS itemCount
        FROM list_items li
        JOIN lists l ON l.id = li.listId
        WHERE li.taskId = :taskId
        GROUP BY li.listId
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    protected abstract suspend fun listsHoldingTask(taskId: String): List<ListLosingItems>

    @Query("DELETE FROM list_items WHERE taskId = :taskId")
    protected abstract suspend fun deleteListItemsOfTask(taskId: String)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    protected abstract suspend fun deleteTaskRow(taskId: String)

    /**
     * Forgets everything a Task was judged to be, keeping the Task.
     *
     * The `reset` tier's whole write. A row that is *updated* to empty rather than deleted, for the
     * same reason every read joins `task_state` without a `COALESCE` on the state columns: a Task
     * whose judgment is gone and a Task that was never judged are the same thing to every reader,
     * and `updatedAt` is where the difference between "cleared now" and "never touched" is kept.
     *
     * The `WHERE` finds nothing when the Task has no row, which is the ordinary case for a Task
     * nobody has judged — there is nothing to clear and nothing to create.
     */
    @Query(
        """
        UPDATE task_state
        SET isCompleted = 0,
            isFavorite = 0,
            customActions = '',
            updatedAt = :now
        WHERE taskId = :taskId
        """
    )
    protected abstract suspend fun clearTaskState(taskId: String, now: Long)

    @Query(
        "DELETE FROM list_items WHERE taskId IN " +
            "(SELECT taskId FROM task_imports WHERE sourceId = :sourceId)"
    )
    protected abstract suspend fun deleteListItemsOfTodoTasks(sourceId: String)

    @Query(
        "DELETE FROM tasks WHERE id IN " +
            "(SELECT taskId FROM task_imports WHERE sourceId = :sourceId)"
    )
    protected abstract suspend fun deleteTasksOfTodo(sourceId: String)

    @Query("DELETE FROM package_prompt_tasks WHERE sourceId = :sourceId")
    protected abstract suspend fun deletePackagePromptTasksOfTodo(sourceId: String)

    @Query("DELETE FROM package_resources WHERE sourceId = :sourceId")
    protected abstract suspend fun deletePackageResourcesOfTodo(sourceId: String)

    @Query("DELETE FROM package_checkpoints WHERE sourceId = :sourceId")
    protected abstract suspend fun deletePackageCheckpointsOfTodo(sourceId: String)

    @Query("DELETE FROM sources WHERE id = :sourceId")
    protected abstract suspend fun deleteSourceRow(sourceId: String)

    /**
     * Leaves the note that says this List lost rows, and which operation took them.
     *
     * The reason travels with the count rather than being inferred later, because it cannot be: the
     * rows are gone by the time anyone reads the note, and the two causes leave nothing behind that
     * tells them apart. See [DissolutionReason].
     */
    @Query(
        """
        UPDATE lists
        SET lastDissolvedCount = :count,
            lastDissolvedAt = :now,
            lastDissolvedReason = :reason
        WHERE id = :listId
        """
    )
    protected abstract suspend fun noteDissolution(
        listId: Long,
        count: Int,
        now: Long,
        reason: String
    )

    /**
     * Forgets a dissolution note, because the List it was about has taken on rows since.
     *
     * A note says *this List lost 3 items when that Todo was deleted*. It is about a moment, and a
     * List that has gained a membership is no longer the List it was written about — after a
     * re-sync puts the files back, a note still reading "3 items removed" is a screen contradicting
     * itself. Cleared by the two operations that can add a membership to an existing List,
     * [addTasksToList] and [applyReconcile], rather than by a screen or a timer: whether a List has
     * moved on is a fact about its rows, and nothing outside this file can know it.
     *
     * Guarded at the call sites by the same condition that adds rows, so a re-sync that found
     * nothing new leaves the note standing.
     */
    @Query("UPDATE lists SET lastDissolvedCount = NULL, lastDissolvedAt = NULL WHERE id = :listId")
    protected abstract suspend fun clearDissolutionNote(listId: Long)

    /**
     * The histories one folder can offer back, newest first. One row per dissolved Todo, not per
     * Task — [DissolvedHistory.taskCount] is how many judgments it holds.
     *
     * Scoped to the Todo's own folder rather than the grant root, because a recovery belongs to the
     * place the intention was made: a Todo dissolved over `Holiday/` is offered when something is
     * imported at `Holiday/`, not when anything is imported anywhere under the same grant.
     *
     * Ordered newest first so the picker opens on the most recent abandonment, which is the one a
     * user who just changed their mind is looking for.
     */
    @Query(
        """
        SELECT sourceId AS sourceId,
               MAX(sourceName) AS sourceName,
               MAX(importerKind) AS importerKind,
               COUNT(*) AS taskCount,
               MAX(dissolvedAt) AS dissolvedAt
        FROM dissolved_task_state
        WHERE rootUri = :rootUri AND sourcePath = :sourcePath
        GROUP BY sourceId
        ORDER BY dissolvedAt DESC
        """
    )
    abstract suspend fun dissolvedHistoriesAt(
        rootUri: String,
        sourcePath: String
    ): List<DissolvedHistory>

    /**
     * Copies one history's judgments onto the Tasks a new import has just minted.
     *
     * **A recovery is a copy into the new Todo, not a resurrection of the old Tasks.** Nothing about
     * the dissolved Tasks comes back — they had no ids worth keeping and no surroundings left to
     * return to — so what moves is the judgment and the path it was about, onto whatever Task now
     * sits at that path. That is also why a path the new import did not produce recovers nothing:
     * the old List's missing rows stay missing, which is what "nothing missing from the old List
     * comes back" means in practice.
     *
     * Match is `(rootUri, groupPath)` and nothing else. A history whose grouping rule differs from
     * the new Todo's therefore matches nothing at all — dissolve a folder-as-Task Todo and re-import
     * the same folder file-as-Task, and the group paths will not line up. That is honest rather than
     * broken: the judgments were about *groups*, and the new Todo has no such groups. The picker is
     * what should say so, by reporting how many rows a recovery would carry before it is chosen.
     *
     * **One history at a time**, enforced by taking a single [dissolvedSourceId]: two histories at
     * one folder are two separate questions, and a run that took some Tasks from one and some from
     * another would produce a Todo that never existed as an intention.
     *
     * Read then write, rather than one `UPDATE … FROM`: SQLite gained `UPDATE … FROM` in 3.33 and
     * `minSdk = 24` ships 3.9, so the join is done here and the writes are addressed by id.
     *
     * **The files come back the same way the judgments do** — matched by path onto the new Task's
     * Assets, through [writeAssetState], which is already the only way a pair is ever written. So a
     * file the new import did not produce recovers nothing while its neighbour recovers what it had,
     * and a Task with no file recovers its completion alone. Nothing is spread across a Task's files
     * because nothing here knows better than the payload which file a mark was made on.
     */
    @Transaction
    open suspend fun recoverStateFrom(
        dissolvedSourceId: String,
        newSourceId: String,
        now: Long
    ): Int {
        val matches = recoveryMatches(dissolvedSourceId, newSourceId)
        for (row in matches) {
            writeRecoveredState(row.taskId, row.isCompleted, now)
            // An unknown version, or a payload this build did not write, ends this Task here:
            // completion is restored and nothing is guessed at. That is also the whole answer a
            // history written before v0.5.2 gets, its payload being empty by construction.
            val records = decodeDissolvedRecords(row.records, row.recordVersion) ?: continue
            // And `''` is an answer rather than a gap — the Task had no file to record — so there is
            // nothing to look up and nothing to write.
            if (records.isEmpty()) continue
            val byPath = assetsForTask(row.taskId).associateBy { it.relativePath }
            for (record in records) {
                // A file the new import did not produce matches no Asset and writes nothing. That is
                // the rule a recovery has always applied per Task — a path the Todo no longer holds
                // recovers nothing — now applied per file, which is what it was always about.
                val asset = byPath[record.relativePath] ?: continue
                writeAssetState(asset.id, now) { record.decisions }
            }
        }
        return matches.size
    }

    /**
     * The new Tasks one history has something to say about, and what it has to say.
     *
     * `JOIN sources` rather than trusting `task_imports` for the root, because provenance rows carry
     * no `rootUri` of their own — the grant lives on the Todo — and a match across two grants would
     * be a match between two different folders that happen to share a relative path.
     *
     * The judgments come back as the encoded payload and its version, not as columns: they are the
     * files', and there is one of this row per Task. See [decodeDissolvedRecords].
     */
    @Query(
        """
        SELECT ti.taskId AS taskId,
               d.isCompleted AS isCompleted,
               d.recordVersion AS recordVersion,
               d.records AS records
        FROM task_imports ti
        JOIN sources s ON s.id = ti.sourceId
        JOIN dissolved_task_state d
          ON d.rootUri = s.rootUri AND d.groupPath = ti.groupPath
        WHERE ti.sourceId = :newSourceId AND d.sourceId = :dissolvedSourceId
        """
    )
    protected abstract suspend fun recoveryMatches(
        dissolvedSourceId: String,
        newSourceId: String
    ): List<RecoveredTaskState>

    /**
     * Completion, and only completion. The marks are restored per file — see [recoverStateFrom] —
     * because they are the pair's and this row is the Task's.
     *
     * The two columns a recovery used to land them in are the ones v0.5.1 retired, which is exactly
     * the defect this release repairs: a Task-level mark written here would be read by nobody, and
     * a recovery that left one behind would be one migration away from marking every file at once.
     * They are left untouched, as [writeTaskState] leaves them.
     */
    @Query(
        """
        UPDATE task_state
           SET isCompleted = :isCompleted,
               updatedAt = :now
         WHERE taskId = :taskId
        """
    )
    protected abstract suspend fun writeRecoveredState(
        taskId: String,
        isCompleted: Boolean,
        now: Long
    )

    /**
     * Registers a folder as an Upstream, or returns the one already there.
     *
     * Registering says nothing beyond *here is where material is*: no importer, no tree, no run.
     * Two Upstreams may nest freely, and an Upstream may contain any number of Todos, because an
     * Upstream is a place and a Todo is a decision taken about part of one. The only thing refused
     * is the same folder twice, which is answered by handing back the row that is already there —
     * and that is the one place it still differs from [registerSource], which mints a second row
     * because for an import a second row is a second intention rather than a duplicate.
     */
    @Transaction
    open suspend fun registerUpstream(
        rootUri: String,
        relativePath: String,
        displayName: String,
        now: Long
    ): UpstreamEntity {
        upstreamAt(rootUri, relativePath)?.let { return it }
        val upstream = UpstreamEntity(
            id = UUID.randomUUID().toString(),
            rootUri = rootUri,
            relativePath = relativePath,
            displayName = displayName,
            addedAt = now
        )
        insertUpstream(upstream)
        return upstream
    }

    /**
     * The Upstreams that share a grant, for the removal check and for naming a folder.
     *
     * Public rather than protected, unlike [upstreamsForRoot]'s Source counterpart: with several
     * folders granted at once there is no single "the root" left to ask about, so a caller that
     * needs to know whether a grant is still in use asks about the grant.
     */
    open suspend fun upstreamsUnder(rootUri: String): List<UpstreamEntity> = upstreamsForRoot(rootUri)

    /**
     * The Sources surface's one query: every Source with the size of its catalog.
     *
     * A left join, so a Source that has been registered but not yet run — or whose run imported
     * nothing — is still a row, reporting zero. Dropping it would make registering a Source look
     * like it had failed.
     */
    @Query(
        """
        SELECT s.id AS id,
               s.rootUri AS rootUri,
               s.relativePath AS relativePath,
               s.displayName AS displayName,
               s.importerKind AS importerKind,
               s.keepTree AS keepTree,
               s.contentPolicy AS contentPolicy,
               s.followUpstream AS followUpstream,
               s.keepStateOnDelete AS keepStateOnDelete,
               s.exportKind AS exportKind,
               s.webhookUrl AS webhookUrl,
               s.webhookFromDefault AS webhookFromDefault,
               s.lastImportAt AS lastImportAt,
               s.lastImportStatus AS lastImportStatus,
               s.assetControls AS assetControls,
               COALESCE(MAX(ti.assetControls), '') AS taskAssetControls,
               COUNT(ti.taskId) AS taskCount
        FROM sources s
        LEFT JOIN task_imports ti ON ti.sourceId = s.id
        GROUP BY s.id
        ORDER BY s.createdAt DESC, s.id DESC
        """
    )
    abstract fun observeSourceSummaries(): Flow<List<SourceSummary>>

    /** The same, once, for a caller that has to look rather than watch. */
    @Query(
        """
        SELECT s.id AS id,
               s.rootUri AS rootUri,
               s.relativePath AS relativePath,
               s.displayName AS displayName,
               s.importerKind AS importerKind,
               s.keepTree AS keepTree,
               s.contentPolicy AS contentPolicy,
               s.followUpstream AS followUpstream,
               s.keepStateOnDelete AS keepStateOnDelete,
               s.exportKind AS exportKind,
               s.webhookUrl AS webhookUrl,
               s.webhookFromDefault AS webhookFromDefault,
               s.lastImportAt AS lastImportAt,
               s.lastImportStatus AS lastImportStatus,
               s.assetControls AS assetControls,
               COALESCE(MAX(ti.assetControls), '') AS taskAssetControls,
               COUNT(ti.taskId) AS taskCount
        FROM sources s
        LEFT JOIN task_imports ti ON ti.sourceId = s.id
        WHERE s.id = :sourceId
        GROUP BY s.id
        """
    )
    abstract suspend fun getSourceSummary(sourceId: String): SourceSummary?

    @Query("SELECT * FROM sources WHERE rootUri = :rootUri")
    protected abstract suspend fun sourcesForRoot(rootUri: String): List<SourceEntity>

    /**
     * The Sources registered at exactly this folder, oldest first.
     *
     * A list rather than a row, because one folder may back any number of Todos and this is how a
     * caller finds them: an Upstream browser asking which Todos a folder already has, a dialog
     * deciding what to say before a second run, a legacy resync asking which Source it came from.
     * Ordered by registration so a caller that has to choose, or that has to name them all in a
     * sentence, gets a stable answer rather than whichever row the query returned first.
     */
    @Query(
        "SELECT * FROM sources WHERE rootUri = :rootUri AND relativePath = :relativePath " +
            "ORDER BY createdAt, id"
    )
    abstract suspend fun sourcesAt(rootUri: String, relativePath: String): List<SourceEntity>

    @Insert
    protected abstract suspend fun insertSource(source: SourceEntity)

    /**
     * Registers a new Source over a folder — always a new one, and never refused.
     *
     * This is the whole of what the release changes about registration, and it is a *deletion*: the
     * old rule refused a root that overlapped an existing one, and returned the existing row for an
     * exact match. Both arms are gone, because both were the same claim — that a folder has one
     * import — and a folder may now back any number of Todos. A second run over a folder is a second
     * intention, the same market supplying another dish, and it gets its own Source and its own
     * Tasks rather than joining the rows of the first.
     *
     * Nothing is checked before the insert, and there is nothing left to check: a file stopped
     * belonging to exactly one Task, so an overlapping import root stopped being a collision and
     * became an ordinary thing for a folder to have. Ownership is settled by the Task instead —
     * `task_imports.taskId` is a primary key, so a Task has exactly one Source however many Sources
     * cover its folder.
     *
     * The callers that *do* mean an existing Source do not come here. A refresh carries its
     * `sourceId`; the legacy resync paths read [sourcesAt] and refuse rather than guess; and
     * [createFolderList] keeps what the folder already had. Reuse is now a decision each caller
     * makes with a reason, rather than a rule hidden inside registration.
     *
     * [keepTree] is normalised rather than trusted: see [normalizeKeepTree]. The caller offers it as
     * an import option, and this is where the importer's opinion of it becomes the stored fact.
     *
     * [contentPolicy] and [followUpstream] arrive from different places on purpose. The policy is
     * the answer the Create Tasks dialog collected for *this* run, and storing it here is what makes
     * the next dialog open on it; following the upstream is not asked in that dialog at all, and
     * comes from Settings as the standing preference a new Todo is born with — after which the
     * Todo's own card is the only thing that changes it.
     */
    @Transaction
    open suspend fun registerSource(
        rootUri: String,
        relativePath: String,
        displayName: String,
        importerKind: ImporterKind,
        keepTree: Boolean = true,
        contentPolicy: ContentPolicy = ContentPolicy.Default,
        followUpstream: Boolean = false,
        now: Long,
        assetControls: String = "",
        addOn: String = "",
        taskReview: String = "",
        exportKind: String = EXPORT_CHOICE_NONE,
        keepStateOnDelete: Boolean = false,
        webhookUrl: String = "",
        webhookFromDefault: Boolean = false
    ): SourceEntity {
        val source = SourceEntity(
            id = UUID.randomUUID().toString(),
            rootUri = rootUri,
            relativePath = relativePath,
            displayName = displayName,
            importerKind = importerKind.key,
            importerVersion = 1,
            createdAt = now,
            keepTree = normalizeKeepTree(importerKind, keepTree),
            contentPolicy = contentPolicy.key,
            followUpstream = followUpstream,
            keepStateOnDelete = keepStateOnDelete,
            assetControls = assetControls,
            addOn = addOn,
            taskReview = taskReview,
            exportKind = exportKind,
            webhookUrl = webhookUrl,
            webhookFromDefault = webhookFromDefault
        )
        insertSource(source)
        return source
    }

    /**
     * Records what an existing Source is called and how it is read.
     *
     * Separate from [registerSource] because they answer two questions: that one brings a Source
     * into existence, this one says what an existing Source's rule is. They are two writes because
     * registration writes the row and nothing after it — a caller that only wants a row to hang a
     * run on passes placeholders for the very fields this one sets, and this is where the answers
     * the user actually gave replace them.
     *
     * The configuration is stored after a run rather than before it, so "nothing was changed" can
     * still be said of a run that failed. [keepTree] is normalised here exactly as registration
     * normalises it, so the column cannot hold a value [normalizeKeepTree] calls impossible however
     * the caller arrived.
     *
     * [contentPolicy] is written back from the answer the dialog collected, which is the whole of
     * "a Todo remembers what it was told": the dialog pre-selects it from this column, so the next
     * run opens on what the user said last time rather than on what Settings happens to default to.
     * It is a write even when the answer did not change, because the two are the same statement and
     * a conditional write would be a second rule about when this one applies.
     *
     * `followUpstream` is here because the run's dialog carries it as of v0.6.2. The policy still
     * has [setFollowUpstream] on the Todo's own card as its everyday writer; this call writes the
     * answer the dialog showed, and a caller that never showed the switch passes the value it read
     * rather than a default, so a run cannot quietly reset it.
     *
     * [assetControls] is the **encoded attachment list**, not a decoded value: the templates the
     * dialog attached, in the order it attached them, written as the same string the resolver reads.
     * It keeps the column's name and changes its payload, which is the shape decision 8 chose for
     * this release — `sources.assetControls` was always "what this Todo is configured with", and it
     * is a list of references now rather than a per-kind copy of what they said. Encoding is the
     * caller's one call to [encodeTemplateAttachments], so this DAO has no opinion about the format.
     */
    open suspend fun configureSource(
        id: String,
        displayName: String,
        importerKind: ImporterKind,
        keepTree: Boolean,
        contentPolicy: ContentPolicy = ContentPolicy.Default,
        assetControls: String = "",
        addOn: String = "",
        taskReview: String = "",
        exportKind: String = EXPORT_CHOICE_NONE,
        followUpstream: Boolean = false,
        keepStateOnDelete: Boolean = false,
        webhookUrl: String = "",
        webhookFromDefault: Boolean = false
    ) = setSourceConfig(
        id,
        displayName,
        importerKind.key,
        normalizeKeepTree(importerKind, keepTree),
        contentPolicy.key,
        assetControls,
        addOn,
        taskReview,
        exportKind,
        followUpstream,
        keepStateOnDelete,
        webhookUrl,
        webhookFromDefault
    )

    /**
     * The two standings a Todo card's overflow can change about the Todo itself.
     *
     * Both are writes to a policy, not to a run, and both are separate statements rather than one
     * because the switches are independent: a user may want a Todo to follow its upstream and to
     * keep its decisions when deleted, or either on its own.
     */
    @Query("UPDATE sources SET keepStateOnDelete = :keep WHERE id = :id")
    abstract suspend fun setKeepStateOnDelete(id: String, keep: Boolean)

    @Query("UPDATE sources SET followUpstream = :follow WHERE id = :id")
    abstract suspend fun setFollowUpstream(id: String, follow: Boolean)

    /**
     * The address a Todo posts to, and whether it was copied from Settings' default or typed.
     *
     * One statement for the pair because they are one answer — a URL with no provenance, or
     * provenance whose URL was replaced separately, is a state the dialog would have to guess at.
     * Callers validate a non-empty value before writing; empty is the legitimate *not set*.
     */
    @Query("UPDATE sources SET webhookUrl = :url, webhookFromDefault = :fromDefault WHERE id = :id")
    abstract suspend fun setSourceWebhookAddress(id: String, url: String, fromDefault: Boolean)

    /**
     * The one-time move of the old global webhook configuration onto the Todos that relied on it.
     *
     * Returns whether the marker was still there, which is the only thing that makes this a
     * backfill: **an absent marker means the work is done, and this returns without writing**. A
     * second start must not re-copy a default the user has since edited, so the marker is checked
     * and consumed in the same transaction as the update.
     *
     * [gateWasOn] is `defaultWebhookEnabled` as the upgrade found it. On means every existing Todo
     * was posting through that gate and must keep posting; off means the install had delivery
     * switched off, and copying the default would start work the user had disabled. In that case
     * the addresses are written empty on purpose, and the marker is still consumed — the decision
     * has been made, and a later default edit is not a reason to revisit it.
     *
     * [url] is trimmed here rather than by the caller, so a default saved with stray whitespace
     * cannot become the stored value of every Todo.
     */
    @Transaction
    open suspend fun applyWebhookAddressBootstrap(
        name: String,
        url: String,
        gateWasOn: Boolean
    ): Boolean {
        if (schemaBackfillCount(name) == 0) return false
        val address = if (gateWasOn) url.trim() else ""
        setAllSourceWebhookAddresses(address, fromDefault = address.isNotEmpty())
        clearSchemaBackfill(name)
        return true
    }

    @Query("SELECT COUNT(*) FROM schema_backfills WHERE name = :name")
    protected abstract suspend fun schemaBackfillCount(name: String): Int

    @Query("DELETE FROM schema_backfills WHERE name = :name")
    protected abstract suspend fun clearSchemaBackfill(name: String)

    @Query("UPDATE sources SET webhookUrl = :url, webhookFromDefault = :fromDefault")
    protected abstract suspend fun setAllSourceWebhookAddresses(url: String, fromDefault: Boolean)

    /**
     * Every Source whose import root covers [relativePath], nearest first.
     *
     * A list, because covering stopped being a question with one answer. Two Todos may be rooted at
     * the same folder, and a Todo may be rooted at an Upstream while another is rooted at a folder
     * inside that Upstream's tree — the nested arrangement [scopesOverlap] used to refuse, and which
     * is now an ordinary thing for a folder to have. A Source at the grant root (`relativePath = ''`)
     * covers everything beneath it.
     *
     * Nearest first, because that is what "which Todo does this browsed folder belong to" wants:
     * the deepest root is the one whose run covered the folder most directly. Ties are broken by
     * registration order, so the answer is stable rather than whichever row the query returned.
     *
     * **A caller that means to *write* must not take the first element and move on.** With several
     * Sources covering one path there is no fact of the matter about which one was meant, and
     * choosing is worse than declining — see `requestResync`, which refuses and names them.
     */
    open suspend fun sourcesCovering(rootUri: String, relativePath: String): List<SourceEntity> =
        sourcesForRoot(rootUri)
            .filter { isAncestorOrSame(it.relativePath, relativePath) }
            .sortedWith(
                compareByDescending<SourceEntity> { it.relativePath.length }
                    .thenBy { it.createdAt }
                    .thenBy { it.id }
            )

    /**
     * One import run: turn a [ImportPlan] into Tasks, Assets and provenance, inside one transaction.
     *
     * [coverage] is the root-relative path that was authoritatively scanned, passed in rather than
     * inferred from the plan — an empty scan is exactly the case where the distinction matters, and
     * a plan with no Tasks cannot say whether it saw an empty folder or no folder. `sourceMissing`
     * may only be set for Assets inside [coverage]; an Asset outside it was not looked at and has
     * nothing new said about it.
     *
     * **The one thing a run can delete is a Task its Todo's `drop` policy says to replace** — an
     * explicit, standing instruction the user gave about this material, and never an inference this
     * function draws. Everything else holds: missing material is flagged, never removed; a conflict
     * leaves the affected Task untouched while the rest proceeds; and an Asset is never deleted on
     * any path, because a file Listea can no longer see is not a file Listea may forget. The Source
     * row's `lastImportAt` / `lastImportStatus` are the durable summary; the result is returned and
     * shown, then dropped.
     *
     * [contentPolicy] is the answer this run is being asked under, and null means the Todo's stored
     * one. It is a parameter rather than a read of `sources.contentPolicy` because the two can
     * legitimately differ for the length of one run: the import dialog asks the question and runs on
     * the answer, while the Todo's own row is only written back afterwards. Reading the row here
     * would answer with the value the run is replacing, and a user who chose *Replace the Task* and
     * pressed Refresh would watch the tier do nothing — the control promising a consequence the run
     * in front of them does not have.
     */
    @Transaction
    open suspend fun runImport(
        sourceId: String,
        coverage: String,
        plan: ImportPlan,
        now: Long,
        recoverFrom: String? = null,
        contentPolicy: ContentPolicy? = null,
        assetControls: String = "",
        addOn: String = "",
        taskReview: String = ""
    ): ImportResult = runImportInternal(
        sourceId,
        coverage,
        plan,
        now,
        recoverFrom,
        contentPolicy,
        assetControls,
        addOn,
        taskReview
    )

    /**
     * The body of [runImport], split out so a composed operation such as [createFolderList] can run
     * the import inside its own transaction rather than nesting one.
     *
     * [recoverFrom] names a dissolved Todo whose history this run's Tasks should be born knowing. It
     * is a parameter here rather than a second call by the caller so that the writing and the
     * recovering share one transaction: a run that wrote Tasks and then failed to load the
     * decisions it was asked to load would be a run that half-did the thing it was asked to do, and
     * there is nothing the caller could do with that.
     *
     * [assetControls] is stamped onto every Task this run touches — the ones it creates, the ones it
     * adopts and the ones it merely sees again — so that a Task's control is always a statement
     * about one run rather than a mixture of when each Task last moved. It comes from the
     * *argument*, never from `source.assetControls`: the row was written by an earlier run or by
     * nobody, and a run that read it back would apply the dialog's answer one run late, which is the
     * failure `contentPolicy`'s parameter exists to avoid and this one copies.
     */
    protected open suspend fun runImportInternal(
        sourceId: String,
        coverage: String,
        plan: ImportPlan,
        now: Long,
        recoverFrom: String? = null,
        contentPolicy: ContentPolicy? = null,
        assetControls: String = "",
        addOn: String = "",
        taskReview: String = ""
    ): ImportResult {
        val source = getSource(sourceId) ?: return ImportResult(outcome = ImportOutcome.FAILED)
        val importer = ImporterKind.fromKey(source.importerKind)

        // A folder-as-Task Task is assembled from a whole subtree, so a half-scanned group is a
        // group whose Asset list is wrong. Refuse a scoped refresh rather than write one. FAILED
        // here means "nothing was written", not that the folder was unreadable.
        if (importer == ImporterKind.FOLDER_AS_TASK && coverage != source.relativePath) {
            return ImportResult(outcome = ImportOutcome.FAILED)
        }

        val imports = taskImportsForSource(sourceId)
        val owned = imports.associateBy { it.groupPath }.toMutableMap()
        val plannedPaths = plan.tasks.flatMap { it.assets }.map { it.rootRelativePath }.toSet()

        var created = 0
        var adopted = 0
        var reconciled = 0
        var reset = 0
        var dropped = 0
        var assetsCreated = 0
        var assetsUpdated = 0
        var assetsMissing = 0
        val conflicts = mutableListOf<ImportConflict>()

        // What this run would change about each Task the Todo already holds, and what the Todo's
        // own content policy makes of that change. Decided here, before a row is written, because
        // the answer has to reach both of the loops below: the plan's Tasks, and the Assets the
        // plan no longer names — and a Task whose content has all gone appears in the second and
        // never in the first. One computation, one decision, both paths reading it.
        val decisions = taskChanges(
            coverage = coverage,
            imports = imports,
            assets = assetsForSource(sourceId),
            planned = plan.tasks,
            plannedPaths = plannedPaths
        ).mapNotNull { change ->
            val decision = decideContentDecision(
                policy = contentPolicy ?: ContentPolicy.fromKey(source.contentPolicy),
                added = change.added,
                missing = change.missing,
                assetCount = change.assetCount
            )
            if (decision == ContentDecision.KEEP) null else change to decision
        }

        // Drops are applied first, and before the plan is walked rather than during it: a dropped
        // Task has to be gone from `owned` by the time the plan reaches its groupPath, so the run
        // mints a replacement in the same pass instead of reconciling a Task it is about to delete.
        // Nothing is minted here — the plan still does all of that — and a dropped Task the plan
        // has nothing to say about (its content has all gone) simply leaves nothing behind.
        for ((change, decision) in decisions) {
            if (decision != ContentDecision.DROP) continue
            dropTask(change.taskId, now)
            owned.remove(change.groupPath)
            dropped++
        }

        for (task in plan.tasks) {
            val existing = owned[task.groupPath]
            if (existing != null) {
                reconciled++
                val delta = reconcileTask(
                    source, existing.taskId, task, now, assetControls, addOn, taskReview
                )
                assetsCreated += delta.created
                assetsUpdated += delta.updated
            } else {
                val delta = resolveNewTask(
                    source, importer, task, now, assetControls, addOn, taskReview
                )
                if (delta.conflict != null) {
                    conflicts += delta.conflict
                } else {
                    if (delta.adopted) adopted++ else created++
                    assetsCreated += delta.assetsCreated
                    assetsUpdated += delta.assetsUpdated
                }
            }
        }

        // Resets come after the loop, because a Task under `reset` keeps its identity but not
        // necessarily its place in the plan: a file-as-Task Task whose one file has gone is
        // reconciled by no line above, and its judgment is exactly what the policy says to clear.
        // A Task that was dropped is not reachable here — its replacement has a different id.
        for ((change, decision) in decisions) {
            if (decision != ContentDecision.RESET) continue
            clearTaskState(change.taskId, now)
            reset++
        }

        // Package metadata comes after every Task id exists, because a payload, a Prompt link and a
        // Resource use all name one. Each Package classifies itself against its own checkpoint and
        // either advances or refuses without touching the Tasks above.
        val packages = applyPackages(source, plan, now)

        // A file the plan no longer names, inside the scope that was authoritatively scanned, has
        // gone missing. Marked, never deleted, and never for an Asset outside the coverage: a
        // scoped refresh must not reach past the folder it scanned.
        for (asset in assetsForSource(sourceId)) {
            val path = asset.relativePath ?: continue
            if (isAncestorOrSame(coverage, path) && path !in plannedPaths && !asset.sourceMissing) {
                setAssetMissing(asset.id)
                assetsMissing++
            }
        }

        // A file registered before this run is owed its wire name; naming catches up once the Asset
        // exists, whichever arrived first. A fresh import with no registrations is a no-op.
        //
        // Chunked, because this is the one statement in the app that can be asked to name a whole
        // folder at once and the binding ceiling is real on the API levels this build supports
        // (§14.10). A folder bigger than one chunk is not an error, it is a longer import.
        plannedPaths.toList().chunked(PathBindChunk).forEach { chunk ->
            assignLegacyItemIds(source.rootUri, chunk)
        }

        // Last, and only if asked: the Tasks have to exist before a history can say anything about
        // them, so this cannot move above the loop. An unmatched history is not a failure — it
        // recovers fewer Tasks than it holds and the run reports the number it actually reached,
        // which is what lets the dialog warn before the fact and the report be honest after it.
        val recovered = recoverFrom?.let { recoverStateFrom(it, sourceId, now) } ?: 0

        val packagesRejected = plan.packagesRejected + packages.rejected
        val partial = conflicts.isNotEmpty() || packagesRejected > 0
        updateSourceStatus(sourceId, now, if (partial) ImportOutcome.PARTIAL.key else ImportOutcome.COMPLETE.key)

        return ImportResult(
            outcome = if (partial) ImportOutcome.PARTIAL else ImportOutcome.COMPLETE,
            tasksCreated = created,
            tasksAdopted = adopted,
            tasksReconciled = reconciled,
            tasksReset = reset,
            tasksDropped = dropped,
            tasksRecovered = recovered,
            assetsCreated = assetsCreated,
            assetsUpdated = assetsUpdated,
            assetsMarkedMissing = assetsMissing,
            conflicts = conflicts,
            notices = plan.notices,
            packagesWritten = packages.written,
            packagesRejected = packagesRejected,
            packageDiagnostics = plan.diagnostics
        )
    }

    @Query(
        """
        SELECT a.* FROM assets a
        JOIN task_imports ti ON ti.taskId = a.taskId
        WHERE ti.sourceId = :sourceId
        """
    )
    protected abstract suspend fun assetsForSource(sourceId: String): List<AssetEntity>

    /**
     * Whether a run over [coverage] would change anything this Todo holds — the question a Todo
     * that follows its upstream asks before it writes anything at all.
     *
     * Read-only, and the same [taskChanges] the run itself decides on, asked through the same three
     * reads. That is the point of it living here rather than in the caller: a card that said "up to
     * date" while the run it was describing would reset three Tasks would be two answers to one
     * question, and the one that acted would be the one nobody read.
     *
     * A plan costs nothing to hold: it is what the folder walk already produced, and this is the
     * second reader of it. Nothing is written on either branch.
     */
    open suspend fun sourceHasChanges(
        sourceId: String,
        coverage: String,
        plan: ImportPlan
    ): Boolean {
        // A Package can change without changing any Task row — a payload revision, a checkpoint
        // that has never been written — so it is asked before the Task comparison rather than
        // being inferred from it. An unchanged fingerprint and a complete set of Prompt links is
        // the one combination that answers "no" without a write.
        for (plannedPackage in plan.packages) {
            val checkpoint = packageCheckpoint(sourceId, plannedPackage.packageKey) ?: return true
            if (checkpoint.revision != plannedPackage.revision ||
                checkpoint.fingerprint != plannedPackage.fingerprint
            ) {
                return true
            }
            val linked = packagePromptTasks(sourceId, plannedPackage.packageKey)
                .filter { it.present }
                .map { it.promptKey }
                .toSet()
            val planned = plan.tasks
                .filter { it.prompt?.packageKey == plannedPackage.packageKey }
                .mapNotNull { it.prompt?.promptKey }
                .toSet()
            if (!linked.containsAll(planned)) return true
        }

        val imports = taskImportsForSource(sourceId)
        if (imports.isEmpty()) return plan.tasks.isNotEmpty()
        val plannedPaths = plan.tasks.flatMap { it.assets }.map { it.rootRelativePath }.toSet()
        return hasSourceChanges(
            changes = taskChanges(
                coverage = coverage,
                imports = imports,
                assets = assetsForSource(sourceId),
                planned = plan.tasks,
                plannedPaths = plannedPaths
            ),
            planned = plan.tasks
        )
    }

    @Query("SELECT * FROM task_imports WHERE sourceId = :sourceId")
    protected abstract suspend fun taskImportsForSource(sourceId: String): List<TaskImportEntity>

    @Query("SELECT * FROM task_imports WHERE taskId = :taskId")
    protected abstract suspend fun taskImportForTask(taskId: String): TaskImportEntity?

    @Insert
    protected abstract suspend fun insertTaskImport(import: TaskImportEntity)

    /**
     * A Task this Source already holds, met again by a run.
     *
     * `assetControls` is re-stamped here, on the same statement that records the sighting, because
     * it is the same fact: what this Task is and what governs it are both claims about an import,
     * and a run that saw the Task and left a stale control on it would be a Task describing an
     * import that never happened. Re-stamping on every run is deliberate — a Todo whose template
     * was edited moves its Tasks at the *next* run, and a run over an unchanged folder is still a
     * run the user asked for.
     */
    @Query(
        "UPDATE task_imports SET lastSeenAt = :lastSeenAt, assetControls = :assetControls, " +
            "addOn = :addOn, taskReview = :taskReview WHERE taskId = :taskId"
    )
    protected abstract suspend fun touchTaskImport(
        taskId: String,
        lastSeenAt: Long,
        assetControls: String,
        addOn: String,
        taskReview: String
    )

    @Query("DELETE FROM legacy_source_tasks WHERE taskId = :taskId")
    protected abstract suspend fun deleteLegacyClaim(taskId: String)

    /**
     * Any Asset at a locator, whichever Task holds it.
     *
     * Not "the" Asset, because there may be several: a file under two Todos is the content of two
     * Tasks. Only used to ask whether *somebody* holds a file and whether that somebody is a Source
     * at all — the question "does **this** Source already hold it" is [assetOfSourceAt]'s, and the
     * two are asked in that order so that a second Todo's Task is never mistaken for a collision.
     */
    @Query("SELECT * FROM assets WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun assetByLocator(rootUri: String, relativePath: String): AssetEntity?

    /**
     * The Asset this Source's own imports put at a locator, or null.
     *
     * Scoped by `task_imports` rather than by the locator alone, and that scoping *is* the release:
     * a file reached through another Todo is that Todo's Task and says nothing about this one. What
     * is left for this query to find is this Source holding a file under a Task its own grouping
     * rule no longer produces, which is a real disagreement and still a conflict.
     */
    @Query(
        """
        SELECT a.* FROM assets a
        JOIN task_imports ti ON ti.taskId = a.taskId
        WHERE ti.sourceId = :sourceId AND a.rootUri = :rootUri AND a.relativePath = :relativePath
        LIMIT 1
        """
    )
    protected abstract suspend fun assetOfSourceAt(
        sourceId: String,
        rootUri: String,
        relativePath: String
    ): AssetEntity?

    @Query("SELECT * FROM assets WHERE taskId = :taskId")
    protected abstract suspend fun assetsForTask(taskId: String): List<AssetEntity>

    @Query(
        """
        UPDATE assets
        SET sourceUri = :sourceUri, sizeBytes = :sizeBytes, lastModified = :lastModified,
            sourceMissing = 0
        WHERE id = :id
        """
    )
    protected abstract suspend fun updateAssetMetrics(
        id: String,
        sourceUri: String,
        sizeBytes: Long?,
        lastModified: Long?
    )

    @Query("UPDATE assets SET sourceMissing = 1 WHERE id = :id")
    protected abstract suspend fun setAssetMissing(id: String)

    @Query("UPDATE sources SET lastImportAt = :at, lastImportStatus = :status WHERE id = :id")
    protected abstract suspend fun updateSourceStatus(id: String, at: Long, status: String)

    @Query(
        "UPDATE sources SET displayName = :displayName, importerKind = :importerKind, " +
            "keepTree = :keepTree, contentPolicy = :contentPolicy, " +
            "assetControls = :assetControls, addOn = :addOn, taskReview = :taskReview, " +
            "exportKind = :exportKind, " +
            "followUpstream = :followUpstream, keepStateOnDelete = :keepStateOnDelete, " +
            "webhookUrl = :webhookUrl, webhookFromDefault = :webhookFromDefault WHERE id = :id"
    )
    protected abstract suspend fun setSourceConfig(
        id: String,
        displayName: String,
        importerKind: String,
        keepTree: Boolean,
        contentPolicy: String,
        assetControls: String,
        addOn: String,
        taskReview: String,
        exportKind: String,
        followUpstream: Boolean,
        keepStateOnDelete: Boolean,
        webhookUrl: String,
        webhookFromDefault: Boolean
    )

    /**
     * The catalog's SQL half: a Task's state and Asset measurements, aggregated per Task. The
     * viewability flag is resolved in [getCatalogTasks], because the classification is Kotlin, not
     * SQL.
     *
     * It carries `isCompleted` and nothing else of the Task's decisions, since v0.5.1. It used to
     * bring `isFavorite` and `customActions` too, and both were already read by nobody on this
     * screen — the tags a catalog row would want are its pairs', and the fold for those is Kotlin.
     * Rather than select two columns and overwrite them, the projection stopped asking.
     */
    @Query(
        """
        SELECT ti.taskId AS taskId,
               ti.groupPath AS groupPath,
               ti.displayName AS displayName,
               ts.isCompleted AS isCompleted,
               ti.assetControls AS assetControls,
               ti.addOn AS addOn,
               ti.taskReview AS taskReview,
               COUNT(a.id) AS assetCount,
               COALESCE(SUM(a.sourceMissing), 0) AS missingAssets,
               SUM(a.sizeBytes) AS sizeBytes,
               MAX(a.lastModified) AS lastModified,
               ppt.packageKey AS packageKey,
               pc.title AS packageTitle,
               ppt.promptKey AS promptKey,
               tp.kind AS payloadKind,
               tp.schemaVersion AS payloadSchema,
               tp.revision AS payloadRevision
        FROM task_imports ti
        JOIN task_state ts ON ts.taskId = ti.taskId
        LEFT JOIN assets a ON a.taskId = ti.taskId
        LEFT JOIN package_prompt_tasks ppt ON ppt.taskId = ti.taskId
        LEFT JOIN package_checkpoints pc
               ON pc.sourceId = ti.sourceId AND pc.packageKey = ppt.packageKey
        LEFT JOIN task_payloads tp ON tp.taskId = ti.taskId
        WHERE ti.sourceId = :sourceId
        GROUP BY ti.taskId
        """
    )
    protected abstract suspend fun catalogProjections(sourceId: String): List<CatalogProjection>

    /**
     * The catalog for one Source, with its viewability and its Assets resolved from the Task.
     *
     * The pair records are read once for the whole catalog rather than per Task — a Source has many
     * Tasks and a pair record is one small row per Asset, so the round trip is the cost worth
     * avoiding — and each is hung back on the Asset it belongs to rather than folded onto the row.
     * A catalog row is a Task with no file on screen and so has no tags of its own to carry; the
     * Assets below it each know what was said about them, under this Task's control.
     */
    @Transaction
    open suspend fun getCatalogTasks(sourceId: String): List<CatalogRow> {
        val projections = catalogProjections(sourceId)
        val pairs = getTaskAssetStates(projections.map { it.taskId }).associateBy { it.assetId }
        return projections.map { projection ->
            val assets = assetsForTask(projection.taskId).sortedBy { it.sortOrder }
            CatalogRow(
                taskId = projection.taskId,
                groupPath = projection.groupPath,
                displayName = projection.displayName,
                isCompleted = projection.isCompleted,
                assetControls = projection.assetControls,
                addOn = projection.addOn,
                taskReview = projection.taskReview,
                assetCount = projection.assetCount,
                missingAssets = projection.missingAssets,
                sizeBytes = projection.sizeBytes,
                lastModified = projection.lastModified,
                hasUnviewableAsset = assets.any { asset ->
                    !assetViewerAvailable(asset.relativePath?.substringAfterLast('/').orEmpty())
                },
                assets = assets.map { it.toReviewAsset(pairs[it.id]?.decisions ?: AssetDecisions()) },
                packageKey = projection.packageKey,
                packageTitle = projection.packageTitle,
                promptKey = projection.promptKey,
                payloadKind = projection.payloadKind,
                payloadSchema = projection.payloadSchema,
                payloadRevision = projection.payloadRevision
            )
        }
    }

    /**
     * Whether a Task's content can currently be presented: every required Asset resolves and has a
     * viewer in this build. A Task with no Assets is presentable — a legacy manual item has no
     * content that could fail to render. This is the write-boundary half of review eligibility; the
     * containment half is answered by the scope a queue was built from, upstream of the write.
     */
    protected open suspend fun contentPresentable(taskId: String): Boolean =
        assetsForTask(taskId).all { asset ->
            !asset.sourceMissing &&
                assetViewerAvailable(asset.relativePath?.substringAfterLast('/').orEmpty())
        }

    /**
     * The current state of several Tasks, for a payload built right now. A folder round re-reads its
     * decisions by Task id rather than by a registration row, so a Task without a registration —
     * a multi-Asset Task, or one no List ever touched — does not silently drop out of the payload.
     */
    @Query("SELECT * FROM task_state WHERE taskId IN (:taskIds)")
    abstract suspend fun getTaskStates(taskIds: List<String>): List<TaskStateEntity>

    /**
     * The same, as a live read: the decisions a review round draws while it is open.
     *
     * A round freezes *which* items it walks, but it must not freeze what has been decided about
     * them — checking a Task or tagging it is the normal way through a round, and a surface that
     * drew its controls from the queue it built on entry would write every tap to the database
     * and show nothing. `task_state` is what Room invalidates on, so a decision taken here, on a
     * List behind it or in a folder round elsewhere re-emits and is drawn in the same frame.
     *
     * A filter and not a `@Transaction` list read, because this one is watched: it has to stay a
     * single observable query whose only moving part is the rows a round is actually holding.
     */
    @Query("SELECT * FROM task_state WHERE taskId IN (:taskIds)")
    abstract fun observeTaskStates(taskIds: List<String>): Flow<List<TaskStateEntity>>

    private class ReconcileDelta(val created: Int, val updated: Int)

    /** A Task this Source already owns: update it in place, adding new Assets and measuring old ones. */
    private suspend fun reconcileTask(
        source: SourceEntity,
        taskId: String,
        task: PlannedTask,
        now: Long,
        assetControls: String,
        addOn: String,
        taskReview: String
    ): ReconcileDelta {
        val existing = assetsForTask(taskId).associateBy { it.relativePath }
        var created = 0
        var updated = 0

        for (asset in task.assets) {
            val current = existing[asset.rootRelativePath]
            if (current == null) {
                insertAssetWithState(
                    AssetEntity(
                        id = UUID.randomUUID().toString(),
                        taskId = taskId,
                        sortOrder = asset.sortOrder,
                        rootUri = source.rootUri,
                        relativePath = asset.rootRelativePath,
                        sourceUri = asset.uri,
                        sizeBytes = asset.sizeBytes,
                        lastModified = asset.lastModified,
                        publicId = newItemPublicId(
                            assetIdentity(taskId, asset.rootRelativePath),
                            now
                        )
                    ),
                    now
                )
                created++
            } else {
                updateAssetMetrics(current.id, asset.uri, asset.sizeBytes, asset.lastModified)
                updated++
            }
        }

        touchTaskImport(taskId, now, assetControls, addOn, taskReview)
        return ReconcileDelta(created, updated)
    }

    private class NewTaskDelta(
        val adopted: Boolean = false,
        val conflict: ImportConflict? = null,
        val assetsCreated: Int = 0,
        val assetsUpdated: Int = 0
    )

    /**
     * A planned Task this Source has no row for yet. Its file locators decide whether it is brand
     * new, adopted from the legacy claim set, or in conflict with an existing Task.
     *
     * **Another Source holding a file is not a conflict.** It is the ordinary case this release
     * exists for: a second Todo over a folder plans its own Tasks over the same files, and the two
     * decisions about one picture are meant to be separate. So the question asked per file is not
     * "does anybody hold this" but "does *this* Source already hold it" — [assetOfSourceAt] — and
     * only that answer can stop a Task being written.
     *
     * What is left as a conflict is the Source disagreeing with itself: a file it already holds
     * under a Task whose grouping rule now puts that file elsewhere. That is a real contradiction
     * and it is settled the same way it always was, by not writing the Task and saying which one
     * holds the file.
     */
    private suspend fun resolveNewTask(
        source: SourceEntity,
        importer: ImporterKind?,
        task: PlannedTask,
        now: Long,
        assetControls: String,
        addOn: String,
        taskReview: String
    ): NewTaskDelta {
        var adoptTaskId: String? = null
        for (asset in task.assets) {
            assetOfSourceAt(source.id, source.rootUri, asset.rootRelativePath)?.let { owned ->
                return NewTaskDelta(
                    conflict = ImportConflict(
                        relativePath = asset.rootRelativePath,
                        ownerLabel = "this Source",
                        taskId = owned.taskId
                    )
                )
            }

            val existing = assetByLocator(source.rootUri, asset.rootRelativePath) ?: continue
            val ownerTaskId = existing.taskId
            if (taskImportForTask(ownerTaskId) != null) {
                // Held by another Source's Task. A Task of this Source's own is written beside it,
                // sharing the file and sharing nothing else.
                continue
            }
            // A free file-backed Task: legacy-claimed, or minted by an older migration before the
            // claim set existed. A file-as-Task run planning exactly this one file can adopt it; a
            // folder-as-Task run would fold it into a group and take a reviewed Task's identity
            // with it.
            if (importer == ImporterKind.FILE_AS_TASK && task.assets.size == 1) {
                adoptTaskId = ownerTaskId
            } else {
                return NewTaskDelta(
                    conflict = ImportConflict(asset.rootRelativePath, "a legacy Task", ownerTaskId)
                )
            }
        }

        if (adoptTaskId != null) {
            insertTaskImport(
                TaskImportEntity(
                    taskId = adoptTaskId,
                    sourceId = source.id,
                    groupPath = task.groupPath,
                    displayName = task.displayName,
                    importedAt = now,
                    lastSeenAt = now,
                    assetControls = assetControls,
                    addOn = addOn,
                    taskReview = taskReview
                )
            )
            deleteLegacyClaim(adoptTaskId)
            val asset = task.assets.single()
            // The adopted Task's *own* Asset, not whichever row the locator happens to return now
            // that a file may sit under several Tasks at once.
            assetsForTask(adoptTaskId)
                .firstOrNull { it.rootUri == source.rootUri && it.relativePath == asset.rootRelativePath }
                ?.let { updateAssetMetrics(it.id, asset.uri, asset.sizeBytes, asset.lastModified) }
            return NewTaskDelta(adopted = true, assetsUpdated = 1)
        }

        val taskId = UUID.randomUUID().toString()
        insertTask(TaskEntity(
            id = taskId,
            createdAt = now,
            publicId = newItemPublicId(taskIdentity(taskId), now)
        ))
        insertTaskState(TaskStateEntity(taskId = taskId, updatedAt = now))
        insertTaskImport(
            TaskImportEntity(
                taskId = taskId,
                sourceId = source.id,
                groupPath = task.groupPath,
                displayName = task.displayName,
                importedAt = now,
                lastSeenAt = now,
                assetControls = assetControls,
                addOn = addOn,
                taskReview = taskReview
            )
        )
        for (asset in task.assets) {
            insertAssetWithState(
                AssetEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    sortOrder = asset.sortOrder,
                    rootUri = source.rootUri,
                    relativePath = asset.rootRelativePath,
                    sourceUri = asset.uri,
                    sizeBytes = asset.sizeBytes,
                    lastModified = asset.lastModified,
                    publicId = newItemPublicId(assetIdentity(taskId, asset.rootRelativePath), now)
                ),
                now
            )
        }
        return NewTaskDelta(assetsCreated = task.assets.size)
    }

    // --- Packages (v0.8.2) --------------------------------------------------------------------

    @Query(
        "SELECT * FROM package_checkpoints WHERE sourceId = :sourceId AND packageKey = :packageKey"
    )
    protected abstract suspend fun packageCheckpoint(
        sourceId: String,
        packageKey: String
    ): PackageCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertPackageCheckpoint(checkpoint: PackageCheckpointEntity)

    @Query(
        "SELECT * FROM package_prompt_tasks WHERE sourceId = :sourceId AND packageKey = :packageKey"
    )
    protected abstract suspend fun packagePromptTasks(
        sourceId: String,
        packageKey: String
    ): List<PackagePromptTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPackagePromptTask(link: PackagePromptTaskEntity)

    @Query("UPDATE package_prompt_tasks SET present = :present WHERE taskId = :taskId")
    protected abstract suspend fun setPackagePromptPresent(taskId: String, present: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertTaskPayload(payload: TaskPayloadEntity)

    @Query("SELECT * FROM task_payloads WHERE taskId = :taskId")
    abstract suspend fun taskPayload(taskId: String): TaskPayloadEntity?

    /** The same row, watched: the Content page re-reads its body rather than carrying it in a queue. */
    @Query("SELECT * FROM task_payloads WHERE taskId = :taskId")
    abstract fun observeTaskPayload(taskId: String): Flow<TaskPayloadEntity?>

    @Query("SELECT * FROM package_resources WHERE sourceId = :sourceId AND packageKey = :packageKey")
    protected abstract suspend fun packageResources(
        sourceId: String,
        packageKey: String
    ): List<PackageResourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertPackageResource(resource: PackageResourceEntity)

    @Query("UPDATE package_resources SET missing = 1, updatedAt = :now WHERE id = :id")
    protected abstract suspend fun markPackageResourceMissing(id: String, now: Long)

    @Query("DELETE FROM task_resources WHERE taskId = :taskId")
    protected abstract suspend fun clearTaskResources(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTaskResource(link: TaskResourceEntity)

    @Query("SELECT * FROM task_resources WHERE taskId = :taskId")
    abstract suspend fun taskResources(taskId: String): List<TaskResourceEntity>

    @Query("SELECT * FROM package_resources WHERE id = :id")
    abstract suspend fun packageResource(id: String): PackageResourceEntity?

    /**
     * The Resources one Prompt uses, with the requiredness of *this* use.
     *
     * The join is the point: a Resource row may be read by two Prompts, one of which requires the
     * photo and one of which does not, so the requiredness is the link's and never the Resource's.
     * [rootUri] is the document URI the import's scan saw; [relativePath] is the member path kept
     * as provenance for a person reading the row.
     */
    @Query(
        """
        SELECT pr.resourceKey AS resourceKey, pr.kind AS kind, pr.rootUri AS uri,
               pr.relativePath AS relativePath, pr.missing AS missing, tr.required AS required
        FROM task_resources tr
        JOIN package_resources pr ON pr.id = tr.resourceId
        WHERE tr.taskId = :taskId
        ORDER BY pr.resourceKey
        """
    )
    abstract fun observeTaskResourceViews(taskId: String): Flow<List<TaskResourceView>>

    /** The same rows once, for the submission boundary's synchronous check. */
    @Query(
        """
        SELECT pr.resourceKey AS resourceKey, pr.kind AS kind, pr.rootUri AS uri,
               pr.relativePath AS relativePath, pr.missing AS missing, tr.required AS required
        FROM task_resources tr
        JOIN package_resources pr ON pr.id = tr.resourceId
        WHERE tr.taskId = :taskId
        ORDER BY pr.resourceKey
        """
    )
    abstract suspend fun taskResourceViews(taskId: String): List<TaskResourceView>

    /**
     * The last response written for a Task, read rather than observed.
     *
     * The submission boundary needs the same latest row the Info sheet observes, but it asks once
     * and inside the write's own decision: a non-revisable template refuses a second primary
     * commit, and a check that raced a flow would be deciding on a value that might already have
     * moved.
     */
    @Query(
        "SELECT response FROM review_events WHERE taskId = :taskId " +
            "ORDER BY at DESC, id DESC LIMIT 1"
    )
    abstract suspend fun latestResponse(taskId: String): String?

    @Query(
        "SELECT * FROM task_imports WHERE sourceId = :sourceId AND groupPath = :groupPath"
    )
    protected abstract suspend fun taskImportForGroupPath(
        sourceId: String,
        groupPath: String
    ): TaskImportEntity?

    /**
     * Advance every Package in a run, after the ordinary Task loop has minted or reconciled its
     * Tasks.
     *
     * The Task loop runs first because a Package Task is an ordinary Task: the same `task_imports`
     * row, the same `task_state`, the same List memberships, the same drop policy. This function
     * adds only what is Package-specific — payloads, links, Resources, and the checkpoint — and
     * therefore never mints a Task of its own.
     *
     * The reconciliation identity is `(sourceId, packageKey, promptKey)`, read through
     * `package_prompt_tasks`, and not `groupPath`. The two agree for every Package this build
     * writes, but only the link row is the promise: changing a title keeps the Task, and only a
     * changed `promptKey` creates another one. A Prompt the revision no longer names is marked
     * absent and keeps everything else; a Prompt that comes back is marked present again by the
     * ordinary upsert. Nothing here deletes a Task, a payload, an event, or a Resource row.
     */
    private suspend fun applyPackages(
        source: SourceEntity,
        plan: ImportPlan,
        now: Long
    ): PackageRunDelta {
        var written = 0
        var rejected = 0

        for (plannedPackage in plan.packages) {
            val checkpoint = packageCheckpoint(source.id, plannedPackage.packageKey)
            val links = packagePromptTasks(source.id, plannedPackage.packageKey)
            val planned = plan.tasks.filter { it.prompt?.packageKey == plannedPackage.packageKey }
            val plannedKeys = planned.mapNotNull { it.prompt?.promptKey }.toSet()

            val sameFingerprint = checkpoint != null &&
                checkpoint.revision == plannedPackage.revision &&
                checkpoint.fingerprint == plannedPackage.fingerprint
            if (sameFingerprint) {
                val linked = links.filter { it.present }.map { it.promptKey }.toSet()
                val complete = linked.containsAll(plannedKeys) &&
                    links.none { it.present && it.promptKey !in plannedKeys }
                if (complete) continue
            } else if (checkpoint != null) {
                val stale = plannedPackage.revision < checkpoint.revision
                val fingerprintConflict = plannedPackage.revision == checkpoint.revision
                if (stale || fingerprintConflict) {
                    rejected++
                    continue
                }
            }

            // Resources first: a Prompt's links name them, and a row that is absent makes the
            // Prompt's requiredness impossible to express.
            val existingResources = packageResources(source.id, plannedPackage.packageKey)
                .associateBy { it.resourceKey }
            val resourceIds = mutableMapOf<String, String>()
            for (resource in plannedPackage.resources) {
                val id = existingResources[resource.resourceKey]?.id ?: UUID.randomUUID().toString()
                resourceIds[resource.resourceKey] = id
                upsertPackageResource(
                    PackageResourceEntity(
                        id = id,
                        sourceId = source.id,
                        packageKey = plannedPackage.packageKey,
                        resourceKey = resource.resourceKey,
                        kind = resource.kind,
                        // The document URI the scan saw, so a Viewer can open the Resource without
                        // walking the granted tree. The member path stays beside it as provenance;
                        // a host that could not hand over a URI leaves the Source root, and
                        // `missing` decides whether anyone may read it anyway.
                        //
                        // The path is the Package-relative member as declared, not the path inside
                        // the Upstream: package-protocol.md's storage note promises that pair (where
                        // the file was declared, and the exact document that answered it), and the
                        // checkpoint already records where the Package itself lives.
                        rootUri = resource.uri ?: source.rootUri,
                        relativePath = resource.member,
                        missing = !resource.present,
                        updatedAt = now
                    )
                )
            }
            for (row in existingResources.values) {
                if (row.resourceKey !in resourceIds && !row.missing) {
                    markPackageResourceMissing(row.id, now)
                }
            }

            var advanced = false
            for (task in planned) {
                val prompt = task.prompt ?: continue
                val link = links.firstOrNull { it.promptKey == prompt.promptKey }
                val taskId = link?.taskId
                    ?: taskImportForGroupPath(source.id, task.groupPath)?.taskId
                    ?: continue
                if (link == null) {
                    insertPackagePromptTask(
                        PackagePromptTaskEntity(
                            taskId = taskId,
                            sourceId = source.id,
                            packageKey = plannedPackage.packageKey,
                            promptKey = prompt.promptKey,
                            present = true
                        )
                    )
                } else if (!link.present) {
                    setPackagePromptPresent(taskId, true)
                }

                upsertTaskPayload(
                    TaskPayloadEntity(
                        taskId = taskId,
                        kind = prompt.kind,
                        schemaVersion = prompt.schema,
                        payload = prompt.payload,
                        revision = prompt.revision,
                        updatedAt = now
                    )
                )

                clearTaskResources(taskId)
                for (use in prompt.resources) {
                    val resourceId = resourceIds[use.resourceKey] ?: continue
                    insertTaskResource(
                        TaskResourceEntity(taskId = taskId, resourceId = resourceId, required = use.required)
                    )
                }
                advanced = true
            }

            for (link in links) {
                if (link.present && link.promptKey !in plannedKeys) {
                    setPackagePromptPresent(link.taskId, false)
                    advanced = true
                }
            }

            upsertPackageCheckpoint(
                PackageCheckpointEntity(
                    sourceId = source.id,
                    packageKey = plannedPackage.packageKey,
                    rootUri = source.rootUri,
                    relativePath = plannedPackage.rootRelativePath,
                    protocol = plannedPackage.protocol,
                    title = plannedPackage.title,
                    revision = plannedPackage.revision,
                    fingerprint = plannedPackage.fingerprint,
                    lastSeenAt = now
                )
            )
            // An empty Package (no Prompts) still writes its checkpoint, which is a Package that
            // was seen and says nothing. It counts as written because the checkpoint moved or was
            // created, and a later import must be able to tell that from "never looked at".
            if (advanced || checkpoint == null || checkpoint.revision != plannedPackage.revision ||
                checkpoint.fingerprint != plannedPackage.fingerprint
            ) {
                written++
            }
        }

        return PackageRunDelta(written = written, rejected = rejected)
    }

    private class PackageRunDelta(val written: Int, val rejected: Int)

    // --- passage marks (v0.7.0) ---------------------------------------------------------------

    /**
     * The marks on one Asset, in the order they were made.
     *
     * Ordered in SQL rather than in the UI because the order is part of what a reader sees: an
     * overlap sheet lists the older mark first, and the payload repeats that order so a receiver can
     * diff two deliveries. [id] is the tie-break, because two marks created in the same millisecond
     * are still two marks and their order must not depend on how SQLite happened to return them.
     */
    @Query(
        "SELECT * FROM task_asset_marks WHERE assetId = :assetId ORDER BY createdAt, id"
    )
    abstract fun observeMarksForAsset(assetId: String): Flow<List<AssetMarkEntity>>

    /**
     * Every mark under [assetIds], for a payload being assembled.
     *
     * Chunked for the reason [PathBindChunk] gives: a payload can name a whole folder's Assets, and
     * older APIs cap a statement at 999 bound variables.
     */
    suspend fun getMarksForAssets(assetIds: List<String>): List<AssetMarkEntity> =
        assetIds.chunked(PathBindChunk).flatMap { chunk -> getMarksForAssetsChunk(chunk) }

    @Query(
        "SELECT * FROM task_asset_marks WHERE assetId IN (:assetIds) " +
            "ORDER BY assetId, createdAt, id"
    )
    protected abstract suspend fun getMarksForAssetsChunk(
        assetIds: List<String>
    ): List<AssetMarkEntity>

    @Query("SELECT * FROM task_asset_marks WHERE id = :id")
    abstract suspend fun getMark(id: String): AssetMarkEntity?

    @Insert
    abstract suspend fun insertMark(mark: AssetMarkEntity)

    /** A comment's text was edited. Only ever called for a comment; a tag's value is its tool's. */
    @Query("UPDATE task_asset_marks SET value = :value, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateMarkValue(id: String, value: String, updatedAt: Long)

    /**
     * A later reading found the passage somewhere else, so the stored anchor moves to where it was
     * found.
     *
     * The quote and its neighbours are rewritten along with the coordinates: the whole point of a
     * relocation is that the new document's text is what a future reader has to match, and leaving
     * the old values beside the new fingerprint would make the row disagree with itself.
     */
    @Query(
        """
        UPDATE task_asset_marks
        SET documentFingerprint = :documentFingerprint, pageIndex = :pageIndex,
            charStart = :charStart, charEnd = :charEnd, exactText = :exactText,
            prefix = :prefix, suffix = :suffix, charParser = :charParser,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    abstract suspend fun updateMarkAnchor(
        id: String,
        documentFingerprint: String,
        pageIndex: Int,
        charStart: Int,
        charEnd: Int,
        exactText: String,
        prefix: String,
        suffix: String,
        charParser: String,
        updatedAt: Long
    )

    @Query("DELETE FROM task_asset_marks WHERE id = :id")
    abstract suspend fun deleteMark(id: String)

    // --- add-on answers (v0.7.1) ---------------------------------------------------------------

    /**
     * The answers one Task carries, in the order they were first given.
     *
     * A Flow because the questionnaire writes as the reviewer taps and the next frame has to show
     * it; re-reading the Task to notice would be the page asking itself a question it already
     * answered. The order is `createdAt` and then question id, which is stable without pretending
     * to be the questionnaire's order — the page and the payload order answers by the add-on
     * version's questions, and Info has no order of its own to impose.
     */
    @Query(
        "SELECT * FROM task_review_answers WHERE taskId = :taskId ORDER BY createdAt, questionId"
    )
    abstract fun observeAnswersForTask(taskId: String): Flow<List<TaskReviewAnswerEntity>>

    @Query(
        "SELECT * FROM task_review_answers WHERE taskId = :taskId AND questionId = :questionId"
    )
    abstract suspend fun answer(taskId: String, questionId: String): TaskReviewAnswerEntity?

    /**
     * One answer, replacing the row for the same question.
     *
     * REPLACE rather than ABORT because a question asked twice has one answer: re-answering is the
     * ordinary gesture, not a conflict. The caller preserves `createdAt` before this call — the row
     * is read first — so the timestamp still says when the Task was first answered rather than when
     * it was last touched.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAnswer(answer: TaskReviewAnswerEntity)

    @Query("DELETE FROM task_review_answers WHERE taskId = :taskId AND questionId = :questionId")
    abstract suspend fun deleteAnswer(taskId: String, questionId: String)

    /**
     * Every answer under [taskIds], for a payload being assembled.
     *
     * Chunked for the reason [PathBindChunk] gives: a round can name a whole folder's Tasks, and
     * older APIs cap a statement at 999 bound variables. The authored reference is read because the
     * payload decides visibility by it: an answer authored by a different add-on is not this
     * questionnaire's answer even if the question id happens to match.
     */
    suspend fun getPayloadAnswers(taskIds: List<String>): List<PayloadAnswerRow> =
        taskIds.chunked(PathBindChunk).flatMap { chunk -> getPayloadAnswersChunk(chunk) }

    @Query(
        """
        SELECT taskId, questionId, kind, value, authoredAddOnId, authoredAddOnVersion,
               createdAt, updatedAt
        FROM task_review_answers
        WHERE taskId IN (:taskIds)
        ORDER BY taskId, createdAt, questionId
        """
    )
    protected abstract suspend fun getPayloadAnswersChunk(
        taskIds: List<String>
    ): List<PayloadAnswerRow>
}
