package com.example.listea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.listea.ImporterKind
import com.example.listea.ImportPlan
import com.example.listea.PlannedTask
import com.example.listea.assetViewerAvailable
import com.example.listea.isAncestorOrSame
import com.example.listea.scopesOverlap
import com.example.listea.domain.AssetId
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewEventId
import com.example.listea.domain.ReviewEventKind
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TaskId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.completesTask
import com.example.listea.domain.encode
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ListsDao {

    @Query(
        """
        SELECT l.id AS id,
               l.title AS title,
               l.completedAt AS completedAt,
               COUNT(i.id) AS totalItems,
               COALESCE(SUM(ts.isCompleted), 0) AS completedItems,
               l.webhookEnabled AS webhookEnabled,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
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
     * A membership row holds no decision of its own any more. What is true of the thing judged
     * lives on its Task, and this joins it back in — one path for every row, whether it is a
     * manually typed entry or a file. The CASE that used to send the two kinds to different
     * tables is gone rather than rewritten, because a manual entry and a file now answer the
     * question the same way: a manual entry is a Task with no content, a file is a Task with an
     * Asset, and both have state.
     *
     * The join is inner, and that is safe for a reason worth stating: every Task is created with
     * its state in the same breath, and the migration test asserts over a realistic fixture that
     * migrated data holds the same. A left join here would not be more robust — it would turn a
     * violated invariant into a row that silently disappears from somebody's list.
     *
     * Room sees `task_state` in the query and invalidates this flow when it changes, which is what
     * makes a decision taken in a folder's Quick Review appear on this list immediately.
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
               ts.isFavorite AS isFavorite,
               ts.customActions AS customActions
        FROM list_items i
        JOIN task_state ts ON ts.taskId = i.taskId
        WHERE i.listId = :listId
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

        writeTaskState(taskId, now) { it.copy(isCompleted = completed) }

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

        val written = writeTaskState(taskId, now) { it.copy(isCompleted = response.completesTask) }

        // A write that found no state row is a submission about a Task that no longer exists, and
        // nothing is recorded against it: an event naming a Task nobody can reach would be a
        // judgment filed against nothing.
        if (written != null) {
            val (kind, ref) = reviewContextColumns(context)
            insertReviewEvent(
                ReviewEventEntity(
                    id = ReviewEventId.new().value,
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
        }

        return refreshAffected(affected, wasComplete, now)
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
               l.webhookEnabled AS webhookEnabled,
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
        webhookEnabled: Boolean,
        webhookUrl: String,
        now: Long
    ): Long {
        replacedListIds.forEach { deleteList(it) }

        val source = registerOrReuseSource(rootUri, relativePath, now)
        val result = runImportInternal(source.id, relativePath, fileAsTaskPlan(relativePath, files), now)
        if (result.outcome != ImportOutcome.COMPLETE) {
            throw IllegalStateException("Import of $relativePath did not complete")
        }

        val taskIdsByGroup = taskImportsForSource(source.id).associate { it.groupPath to it.taskId }
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
                // Copied from settings by the caller, once, at creation. The list owns it from
                // here on and a later settings change never reaches back to it.
                webhookEnabled = webhookEnabled,
                webhookUrl = webhookUrl,
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
        webhookEnabled: Boolean,
        webhookUrl: String,
        now: Long
    ): Long {
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
                webhookEnabled = webhookEnabled,
                webhookUrl = webhookUrl
            )
        )
        insertItems(
            taskIds.mapIndexed { index, taskId ->
                listItemForTask(taskId, listId, index, now, folderRelativePath = null)
            }
        )
        return listId
    }

    /** The Source a folder-tab action works against: a covering one, or a newly registered one. */
    private suspend fun registerOrReuseSource(
        rootUri: String,
        relativePath: String,
        now: Long
    ): SourceEntity {
        findCoveringSource(rootUri, relativePath)?.let { return it }
        return when (
            val result = registerSourceInternal(rootUri, relativePath, relativePath, ImporterKind.FILE_AS_TASK, now)
        ) {
            is RegisterSourceResult.Registered -> result.source
            is RegisterSourceResult.Overlaps ->
                throw IllegalStateException("Source overlaps ${result.covering.relativePath}")
        }
    }

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
               ts.isFavorite AS isFavorite,
               ts.customActions AS customActions
        FROM list_items i
        JOIN task_state ts ON ts.taskId = i.taskId
        WHERE i.listId = :listId
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
        SELECT a.relativePath FROM assets a
        JOIN task_state ts ON ts.taskId = a.taskId
        WHERE a.rootUri = :rootUri AND ts.isCompleted = 1 AND a.sourceMissing = 0
        ORDER BY a.relativePath COLLATE NOCASE ASC
        """
    )
    abstract suspend fun getCheckedFilePaths(rootUri: String): List<String>

    /** How many there are, for the card that offers the delete. Same filters, no paths loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM assets a
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
     */
    @Transaction
    open suspend fun applyReconcile(listId: Long, files: List<ScannedFile>, now: Long): FolderDiff {
        val items = getSourceItems(listId)
        val diff = diffAgainst(items, files)
        val byPath = items.associateBy { it.sourceRelativePath }
        // The list's own folder, so an appended item gets the same file identity a freshly
        // created list would have given it. An appended file that was reviewed under some other
        // list, or in a folder Quick Review, arrives already carrying that decision.
        val list = getList(listId)
        val listPath = list?.sourceRelativePath.orEmpty()

        // A covered file-as-Task import creates or reconciles the Tasks for what the scan saw, so
        // an appended file's Task is resolved rather than minted on the write path. The only thing
        // that can stop it is a folder-as-Task Source covering this folder, which would fold the
        // files into groups rather than append them as their own Tasks.
        val rootUri = list?.sourceRootUri
        val taskIdsByGroup = if (rootUri != null) {
            val source = registerOrReuseSource(rootUri, listPath, now)
            if (source.importerKind != ImporterKind.FILE_AS_TASK.key) {
                throw IllegalStateException("List folder is inside a folder-as-Task Source")
            }
            runImportInternal(source.id, listPath, fileAsTaskPlan(listPath, files), now)
            taskImportsForSource(source.id).associate { it.groupPath to it.taskId }
        } else {
            emptyMap()
        }

        val appendFrom = maxSortOrder(listId) + 1
        insertItems(
            diff.addedPaths.mapIndexedNotNull { index, path ->
                val file = files.firstOrNull { it.relativePath == path } ?: return@mapIndexedNotNull null
                val groupPath = rootRelativePathOf(listPath, file.relativePath)
                val taskId = taskIdsByGroup[groupPath] ?: return@mapIndexedNotNull null
                listItemForTask(taskId, listId, appendFrom + index, now, listPath)
            }
        )

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
     * Sets one review action on a Task. Action metadata only: it deliberately does not call
     * [refreshCompletion] and returns nothing, so it can never report a completion transition and
     * therefore can never cause a webhook to be delivered.
     *
     * Both kinds of action are one read-modify-write of the Task's set now. Favourite is no longer
     * a column of its own — [ItemAction.Favourite] and a configured action differ in who decides
     * what they mean, not in where they are kept — and the read stays inside the transaction so
     * two actions toggled at once cannot each write a set that never saw the other.
     */
    @Transaction
    open suspend fun setTaskAction(
        taskId: String,
        action: ItemAction,
        enabled: Boolean,
        now: Long
    ) {
        writeTaskState(taskId, now) { action.setOn(it, enabled) }
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
     * other is [taskForContent] minting its Asset. They are separate because either can come
     * first — a folder opened for review registers before anything has been decided about
     * anything in it, while a file reached through a List gets its Task before anybody thinks to
     * review it — and both call the same idempotent assignment, so whichever arrives second finds
     * the work already done.
     */
    @Transaction
    open suspend fun registerFiles(states: List<FileReviewStateEntity>) {
        insertRegistrations(states)
        states.groupBy { it.rootUri }.forEach { (rootUri, rows) ->
            assignLegacyItemIds(rootUri, rows.map { it.relativePath })
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
     */
    @Query(
        """
        SELECT frs.relativePath AS relativePath,
               COALESCE(ts.isCompleted, 0) AS isCompleted
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri
        """
    )
    abstract fun observeFileCompletion(rootUri: String): Flow<List<FileCompletion>>

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
               COALESCE(li.publicId, frs.publicId) AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(ts.isCompleted, 0) AS isCompleted,
               COALESCE(ts.isFavorite, 0) AS isFavorite,
               COALESCE(ts.customActions, '') AS customActions,
               COALESCE(a.sourceMissing, 0) AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN legacy_item_ids li ON li.assetId = a.id
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
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
               COALESCE(li.publicId, frs.publicId) AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(ts.isCompleted, 0) AS isCompleted,
               COALESCE(ts.isFavorite, 0) AS isFavorite,
               COALESCE(ts.customActions, '') AS customActions,
               COALESCE(a.sourceMissing, 0) AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN legacy_item_ids li ON li.assetId = a.id
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
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

    /**
     * Gives each of these files a Task if it has none, and answers with every one it now has.
     *
     * Registration alone is not enough for a folder review, and this is the difference between the
     * two paths that resolve a file. A List's rows get their Tasks from [createFolderList] or from
     * the migration; a file nobody has ever decided about has a registration row and nothing else.
     * A card that is being shown to somebody is a Task whether or not anything has been decided
     * about it yet — "every Task has exactly one state" is what the whole read model rests on, and
     * a registration is not a Task.
     *
     * Minting here rather than at registration is the same choice every resolution path makes:
     * what creates a Task is a reason to have one. Looking at a folder is a reason.
     * [ListsDao.registerFiles] is not, and it stays the cheap idempotent insert it was.
     *
     * One transaction for the whole listing, because it is one answer to one question and a folder
     * half-resolved would hand its queue a mixture of new Tasks and none.
     */
    @Transaction
    open suspend fun ensureTasksForFiles(
        rootUri: String,
        relativePaths: List<String>,
        now: Long
    ): List<FileTaskId> {
        val byPath = taskIdsForFiles(rootUri, relativePaths).associateBy { it.relativePath }
        return relativePaths.map { path ->
            byPath[path] ?: FileTaskId(path, taskForContent(rootUri, path, now))
        }
    }

    @Query(
        """
        SELECT id, event, listTitle, itemCount, at, outcome, detail
        FROM webhook_history
        ORDER BY at DESC, id DESC
        """
    )
    abstract fun observeWebhookHistory(): Flow<List<WebhookRecordSummary>>

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
    @Query("SELECT COUNT(*) FROM webhook_history WHERE outcome != 'SENT'")
    abstract fun observePendingWebhookCount(): Flow<Int>

    @Insert
    abstract suspend fun insertWebhookRecord(record: WebhookRecordEntity): Long

    @Query("SELECT * FROM webhook_history WHERE id = :id")
    abstract suspend fun getWebhookRecord(id: Long): WebhookRecordEntity?

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

    @Query("UPDATE lists SET webhookEnabled = :enabled WHERE id = :id")
    abstract suspend fun setWebhookEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE lists SET webhookUrl = :url WHERE id = :id")
    abstract suspend fun setWebhookUrl(id: Long, url: String)

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

    @Insert
    protected abstract suspend fun insertTaskState(state: TaskStateEntity)

    @Insert
    protected abstract suspend fun insertReviewEvent(event: ReviewEventEntity)

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

    @Query("SELECT taskId FROM assets WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun taskIdForFile(rootUri: String, relativePath: String): String?

    /**
     * Which Task each of these files is of, by the identity `assets` is unique on.
     *
     * A file with no Asset row is simply absent from the answer — it has no Task because nothing
     * has ever decided anything about it, which is a state a folder review meets constantly and
     * must not treat as an error.
     *
     * The one-shot twin of [observeFileStates], and for the same reason: a folder review resolves
     * its files, registers them, and then needs to know what Tasks they turned out to be.
     */
    @Query(
        """
        SELECT relativePath, taskId
        FROM assets
        WHERE rootUri = :rootUri AND relativePath IN (:relativePaths)
        """
    )
    protected abstract suspend fun taskIdsForFiles(
        rootUri: String,
        relativePaths: List<String>
    ): List<FileTaskId>

    @Query("SELECT * FROM task_state WHERE taskId = :taskId")
    protected abstract suspend fun getTaskState(taskId: String): TaskStateEntity?

    @Query(
        """
        UPDATE task_state
        SET isCompleted = :isCompleted,
            isFavorite = :isFavorite,
            customActions = :customActions,
            updatedAt = :updatedAt
        WHERE taskId = :taskId
        """
    )
    protected abstract suspend fun updateTaskState(
        taskId: String,
        isCompleted: Boolean,
        isFavorite: Boolean,
        customActions: String,
        updatedAt: Long
    )

    /**
     * Read-modify-write of one Task's current state, and the only way it is ever changed.
     *
     * Every decision in the app funnels through here — a checkbox on a List, a swipe in Quick
     * Review, a favourite toggled from either — which is what makes "the state of a Task" a single
     * fact rather than a value that four writes agree to keep in step. The read is inside the
     * caller's transaction, so two actions toggled at once cannot each write a set that never saw
     * the other.
     *
     * Update-only, deliberately. A Task's state row is created with the Task — [taskForContent]
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
        change: (ReviewDecisions) -> ReviewDecisions
    ): ReviewDecisions? {
        val existing = getTaskState(taskId) ?: return null
        val next = change(existing.decisions)
        updateTaskState(
            taskId = taskId,
            isCompleted = next.isCompleted,
            isFavorite = next.isFavorite,
            customActions = encodeActionIds(next.customActions),
            updatedAt = now
        )
        return next
    }

    /**
     * The Task a source-backed piece of content belongs to, minting one if this file has none yet.
     *
     * Looked up before it is created, because `assets` is unique on `(rootUri, relativePath)` and
     * that uniqueness is the whole point: a file reached from a second List, or re-appended to one
     * by a re-sync, is the *same* Task. Minting a rival here would either collide on the index or
     * — if the index were ever relaxed — quietly split one file's judgment in two.
     *
     * [rootUri] is nullable for the one degenerate shape the migration also refuses to guess about:
     * a row carrying a root-relative path whose List is not linked to a root. Such a row is treated
     * as manual everywhere else, so its Task simply has pathless content rather than being dropped.
     *
     * A minted Task always gets its state row in the same breath. Nothing reads it yet, but "every
     * Task has exactly one state" is what lets the read paths drop their `COALESCE` later, and
     * holding that from the start is cheaper than reconciling it afterwards.
     */
    private suspend fun taskForContent(rootUri: String?, relativePath: String, now: Long): String {
        if (rootUri != null) {
            val known = taskIdForFile(rootUri, relativePath)
            if (known != null) {
                // A file the migration had to mint an Asset for, or one a List reached before any
                // folder did. The registration may have arrived since, and this is where that is
                // noticed — the assignment matches on both rows, so it does nothing until it can.
                assignLegacyItemIds(rootUri, listOf(relativePath))
                return known
            }
        }

        val taskId = TaskId.new().value
        insertTask(TaskEntity(id = taskId, createdAt = now))
        insertAsset(
            AssetEntity(
                id = AssetId.new().value,
                taskId = taskId,
                // v0.2 is one Asset per Task, so nothing has to choose an order yet.
                sortOrder = 0,
                rootUri = rootUri,
                relativePath = relativePath
            )
        )
        insertTaskState(TaskStateEntity(taskId = taskId, updatedAt = now))
        // Only now is there anything for a legacy name to hang off. A pathless Asset matches
        // nothing and is left alone: it was never a file under a root, so no folder round could
        // ever have named it.
        if (rootUri != null) assignLegacyItemIds(rootUri, listOf(relativePath))
        return taskId
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
     */
    @Query(
        """
        INSERT OR IGNORE INTO legacy_item_ids (assetId, publicId)
        SELECT a.id, frs.publicId
        FROM assets a
        JOIN file_review_state frs
          ON frs.rootUri = a.rootUri AND frs.relativePath = a.relativePath
        WHERE a.rootUri = :rootUri AND a.relativePath IN (:relativePaths)
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

    @Query("SELECT * FROM sources WHERE rootUri = :rootUri")
    protected abstract suspend fun sourcesForRoot(rootUri: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun sourceAt(rootUri: String, relativePath: String): SourceEntity?

    @Insert
    protected abstract suspend fun insertSource(source: SourceEntity)

    /**
     * Registers a folder Source, or refuses to.
     *
     * Registration is rejected when another real Source's import root overlaps the requested one —
     * parent over child or child over parent, by [scopesOverlap] — with the covering Source named in
     * the answer. Non-overlap is what makes ownership a settled question rather than a race: a Task
     * can change owner exactly once, from the legacy claim set to the one Source that covers its
     * file, and never back. An already-registered root is the same Source, returned rather than
     * re-inserted, so the idempotent "reuse" path and this one agree on the object.
     */
    @Transaction
    open suspend fun registerSource(
        rootUri: String,
        relativePath: String,
        displayName: String,
        importerKind: ImporterKind,
        now: Long
    ): RegisterSourceResult = registerSourceInternal(rootUri, relativePath, displayName, importerKind, now)

    /**
     * The body of [registerSource], split out so a composed operation such as [createFolderList] can
     * register or reuse a Source inside its own transaction rather than nesting one.
     */
    protected open suspend fun registerSourceInternal(
        rootUri: String,
        relativePath: String,
        displayName: String,
        importerKind: ImporterKind,
        now: Long
    ): RegisterSourceResult {
        sourceAt(rootUri, relativePath)?.let { return RegisterSourceResult.Registered(it) }
        sourcesForRoot(rootUri)
            .firstOrNull { scopesOverlap(it.relativePath, relativePath) }
            ?.let { return RegisterSourceResult.Overlaps(it) }
        val source = SourceEntity(
            id = UUID.randomUUID().toString(),
            rootUri = rootUri,
            relativePath = relativePath,
            displayName = displayName,
            importerKind = importerKind.key,
            importerVersion = 1,
            createdAt = now
        )
        insertSource(source)
        return RegisterSourceResult.Registered(source)
    }

    /**
     * The real Source whose import root already covers [relativePath], or null.
     *
     * Non-overlap guarantees at most one, so "a Folder-tab action inside a covering Source" has a
     * single answer. A Source at the grant root (`relativePath = ''`) covers everything beneath it.
     */
    open suspend fun findCoveringSource(rootUri: String, relativePath: String): SourceEntity? =
        sourcesForRoot(rootUri).firstOrNull { isAncestorOrSame(it.relativePath, relativePath) }

    /**
     * One import run: turn a [ImportPlan] into Tasks, Assets and provenance, inside one transaction.
     *
     * [coverage] is the root-relative path that was authoritatively scanned, passed in rather than
     * inferred from the plan — an empty scan is exactly the case where the distinction matters, and
     * a plan with no Tasks cannot say whether it saw an empty folder or no folder. `sourceMissing`
     * may only be set for Assets inside [coverage]; an Asset outside it was not looked at and has
     * nothing new said about it.
     *
     * Nothing is ever deleted by a run, whatever the outcome: missing material is flagged, never
     * removed, and a conflict leaves the affected Task untouched while the rest proceeds. The Source
     * row's `lastImportAt` / `lastImportStatus` are the durable summary; the result is returned and
     * shown, then dropped.
     */
    @Transaction
    open suspend fun runImport(
        sourceId: String,
        coverage: String,
        plan: ImportPlan,
        now: Long
    ): ImportResult = runImportInternal(sourceId, coverage, plan, now)

    /**
     * The body of [runImport], split out so a composed operation such as [createFolderList] can run
     * the import inside its own transaction rather than nesting one.
     */
    protected open suspend fun runImportInternal(
        sourceId: String,
        coverage: String,
        plan: ImportPlan,
        now: Long
    ): ImportResult {
        val source = getSource(sourceId) ?: return ImportResult(outcome = ImportOutcome.FAILED)
        val importer = ImporterKind.fromKey(source.importerKind)

        // A folder-as-Task Task is assembled from a whole subtree, so a half-scanned group is a
        // group whose Asset list is wrong. Refuse a scoped refresh rather than write one. FAILED
        // here means "nothing was written", not that the folder was unreadable.
        if (importer == ImporterKind.FOLDER_AS_TASK && coverage != source.relativePath) {
            return ImportResult(outcome = ImportOutcome.FAILED)
        }

        val owned = taskImportsForSource(sourceId).associateBy { it.groupPath }
        val plannedPaths = plan.tasks.flatMap { it.assets }.map { it.rootRelativePath }.toSet()

        var created = 0
        var adopted = 0
        var reconciled = 0
        var assetsCreated = 0
        var assetsUpdated = 0
        var assetsMissing = 0
        val conflicts = mutableListOf<ImportConflict>()

        for (task in plan.tasks) {
            val existing = owned[task.groupPath]
            if (existing != null) {
                reconciled++
                val delta = reconcileTask(source, existing.taskId, task, now)
                assetsCreated += delta.created
                assetsUpdated += delta.updated
            } else {
                val delta = resolveNewTask(source, importer, task, now)
                if (delta.conflict != null) {
                    conflicts += delta.conflict
                } else {
                    if (delta.adopted) adopted++ else created++
                    assetsCreated += delta.assetsCreated
                    assetsUpdated += delta.assetsUpdated
                }
            }
        }

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
        if (plannedPaths.isNotEmpty()) {
            assignLegacyItemIds(source.rootUri, plannedPaths.toList())
        }

        updateSourceStatus(sourceId, now, if (conflicts.isEmpty()) ImportOutcome.COMPLETE.key else ImportOutcome.PARTIAL.key)

        return ImportResult(
            outcome = if (conflicts.isEmpty()) ImportOutcome.COMPLETE else ImportOutcome.PARTIAL,
            tasksCreated = created,
            tasksAdopted = adopted,
            tasksReconciled = reconciled,
            assetsCreated = assetsCreated,
            assetsUpdated = assetsUpdated,
            assetsMarkedMissing = assetsMissing,
            conflicts = conflicts,
            notices = plan.notices
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

    @Query("SELECT * FROM task_imports WHERE sourceId = :sourceId")
    protected abstract suspend fun taskImportsForSource(sourceId: String): List<TaskImportEntity>

    @Query("SELECT * FROM task_imports WHERE taskId = :taskId")
    protected abstract suspend fun taskImportForTask(taskId: String): TaskImportEntity?

    @Insert
    protected abstract suspend fun insertTaskImport(import: TaskImportEntity)

    @Query("UPDATE task_imports SET lastSeenAt = :lastSeenAt WHERE taskId = :taskId")
    protected abstract suspend fun updateTaskImportLastSeen(taskId: String, lastSeenAt: Long)

    @Query("DELETE FROM legacy_source_tasks WHERE taskId = :taskId")
    protected abstract suspend fun deleteLegacyClaim(taskId: String)

    @Query("SELECT * FROM assets WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun assetByLocator(rootUri: String, relativePath: String): AssetEntity?

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

    /**
     * The catalog's SQL half: a Task's state and Asset measurements, aggregated per Task. The
     * viewability flag is resolved in [getCatalogTasks], because the classification is Kotlin, not
     * SQL.
     */
    @Query(
        """
        SELECT ti.taskId AS taskId,
               ti.groupPath AS groupPath,
               ti.displayName AS displayName,
               ts.isCompleted AS isCompleted,
               ts.isFavorite AS isFavorite,
               ts.customActions AS customActions,
               COUNT(a.id) AS assetCount,
               COALESCE(SUM(a.sourceMissing), 0) AS missingAssets,
               SUM(a.sizeBytes) AS sizeBytes,
               MAX(a.lastModified) AS lastModified
        FROM task_imports ti
        JOIN task_state ts ON ts.taskId = ti.taskId
        LEFT JOIN assets a ON a.taskId = ti.taskId
        WHERE ti.sourceId = :sourceId
        GROUP BY ti.taskId
        """
    )
    protected abstract suspend fun catalogProjections(sourceId: String): List<CatalogProjection>

    /** The catalog for one Source, with its viewability and its Assets resolved from the Task. */
    @Transaction
    open suspend fun getCatalogTasks(sourceId: String): List<CatalogRow> =
        catalogProjections(sourceId).map { projection ->
            val assets = assetsForTask(projection.taskId).sortedBy { it.sortOrder }
            CatalogRow(
                taskId = projection.taskId,
                groupPath = projection.groupPath,
                displayName = projection.displayName,
                isCompleted = projection.isCompleted,
                isFavorite = projection.isFavorite,
                customActions = projection.customActions,
                assetCount = projection.assetCount,
                missingAssets = projection.missingAssets,
                sizeBytes = projection.sizeBytes,
                lastModified = projection.lastModified,
                hasUnviewableAsset = assets.any { asset ->
                    !assetViewerAvailable(asset.relativePath?.substringAfterLast('/').orEmpty())
                },
                assets = assets.map { it.toReviewAsset() }
            )
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

    private class ReconcileDelta(val created: Int, val updated: Int)

    /** A Task this Source already owns: update it in place, adding new Assets and measuring old ones. */
    private suspend fun reconcileTask(
        source: SourceEntity,
        taskId: String,
        task: PlannedTask,
        now: Long
    ): ReconcileDelta {
        val existing = assetsForTask(taskId).associateBy { it.relativePath }
        var created = 0
        var updated = 0

        for (asset in task.assets) {
            val current = existing[asset.rootRelativePath]
            if (current == null) {
                insertAsset(
                    AssetEntity(
                        id = UUID.randomUUID().toString(),
                        taskId = taskId,
                        sortOrder = asset.sortOrder,
                        rootUri = source.rootUri,
                        relativePath = asset.rootRelativePath,
                        sourceUri = asset.uri,
                        sizeBytes = asset.sizeBytes,
                        lastModified = asset.lastModified
                    )
                )
                created++
            } else {
                updateAssetMetrics(current.id, asset.uri, asset.sizeBytes, asset.lastModified)
                updated++
            }
        }

        updateTaskImportLastSeen(taskId, now)
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
     */
    private suspend fun resolveNewTask(
        source: SourceEntity,
        importer: ImporterKind?,
        task: PlannedTask,
        now: Long
    ): NewTaskDelta {
        var adoptTaskId: String? = null
        for (asset in task.assets) {
            val existing = assetByLocator(source.rootUri, asset.rootRelativePath) ?: continue
            val ownerTaskId = existing.taskId
            val owner = taskImportForTask(ownerTaskId)
            if (owner != null) {
                // Owned by a Source. A locator already claimed is never silently moved.
                return NewTaskDelta(
                    conflict = ImportConflict(
                        relativePath = asset.rootRelativePath,
                        ownerLabel = if (owner.sourceId == source.id) "this Source" else "another Source",
                        taskId = ownerTaskId
                    )
                )
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
                    lastSeenAt = now
                )
            )
            deleteLegacyClaim(adoptTaskId)
            val asset = task.assets.single()
            val existing = assetByLocator(source.rootUri, asset.rootRelativePath)
            if (existing != null) {
                updateAssetMetrics(existing.id, asset.uri, asset.sizeBytes, asset.lastModified)
            }
            return NewTaskDelta(adopted = true, assetsUpdated = 1)
        }

        val taskId = UUID.randomUUID().toString()
        insertTask(TaskEntity(id = taskId, createdAt = now))
        insertTaskState(TaskStateEntity(taskId = taskId, updatedAt = now))
        insertTaskImport(
            TaskImportEntity(
                taskId = taskId,
                sourceId = source.id,
                groupPath = task.groupPath,
                displayName = task.displayName,
                importedAt = now,
                lastSeenAt = now
            )
        )
        for (asset in task.assets) {
            insertAsset(
                AssetEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    sortOrder = asset.sortOrder,
                    rootUri = source.rootUri,
                    relativePath = asset.rootRelativePath,
                    sourceUri = asset.uri,
                    sizeBytes = asset.sizeBytes,
                    lastModified = asset.lastModified
                )
            )
        }
        return NewTaskDelta(assetsCreated = task.assets.size)
    }
}
