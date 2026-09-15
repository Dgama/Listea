package com.example.listea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.listea.domain.AssetId
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewEventId
import com.example.listea.domain.ReviewEventKind
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TaskId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.completesTask
import com.example.listea.domain.encode
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

    @Insert
    abstract suspend fun insertList(list: ListEntity): Long

    @Query("UPDATE lists SET title = :title WHERE id = :id")
    abstract suspend fun updateListTitle(id: Long, title: String)

    @Query("DELETE FROM lists WHERE id = :id")
    abstract suspend fun deleteList(id: Long)

    /**
     * Drops every list linked to one SAF root, whether it was created on the root itself or on a
     * folder below it, and returns how many went. Items cascade with their list.
     *
     * Manual lists have no root and can never match. Lists linked to a *different* root are left
     * alone: they have nothing to do with the folder being given up.
     */
    @Query("DELETE FROM lists WHERE sourceRootUri = :rootUri")
    abstract suspend fun deleteListsForRoot(rootUri: String): Int

    /**
     * Appends an item and re-evaluates completion: adding an item to an already complete list
     * makes it incomplete again.
     *
     * Returns true when this edit moved the list from incomplete to complete. The before/after
     * reads happen inside the same transaction as the write, so concurrent edits cannot both
     * observe the transition, and the caller can fire the completion action exactly once.
     */
    @Transaction
    open suspend fun addItem(listId: Long, title: String, now: Long): Boolean {
        val wasComplete = isComplete(listId) == true
        insertItem(
            ListItemEntity(
                publicId = newItemPublicId(manualItemIdentity(listId, title), now),
                listId = listId,
                taskId = newManualTask(now),
                title = title,
                sortOrder = maxSortOrder(listId) + 1,
                createdAt = now
            )
        )
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    /**
     * Checks or unchecks a *manual* item, whose state really is its own. See [addItem].
     *
     * A manual entry has no file to share a decision with, so this reaches its Task directly and
     * can only ever affect the list the row is in. It writes to the Task like every other
     * completion write: what makes an item manual is the shape of its Task — one with no Asset —
     * and not a second place for its state to live.
     *
     * A row that has gone from underneath this call writes nothing and still recomputes, which is
     * what the previous version did too: the update matched no rows and the list was re-evaluated
     * regardless.
     */
    @Transaction
    open suspend fun setManualItemCompleted(
        listId: Long,
        itemId: Long,
        completed: Boolean,
        now: Long
    ): Boolean {
        val wasComplete = isComplete(listId) == true
        taskIdForItem(itemId)?.let { taskId ->
            writeTaskState(taskId, now) { it.copy(isCompleted = completed) }
        }
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    /**
     * Checks or unchecks a *file*, from wherever it was reached.
     *
     * One write, and then every list that happens to contain that file is re-evaluated — which is
     * the point of moving the state onto the file. Checking the last unreviewed file of a folder
     * from a Quick Review that knows nothing about any List still completes the List covering it,
     * and still fires that List's webhook, because completion is recomputed here for every list
     * the file belongs to.
     *
     * Returns the lists that moved from incomplete to complete, so the caller fires exactly one
     * completion action per list that actually transitioned. Non-overlap means that is at most
     * one today; nothing here assumes it.
     */
    @Transaction
    open suspend fun setFileCompleted(
        rootUri: String,
        relativePath: String,
        completed: Boolean,
        now: Long
    ): List<Long> {
        val affected = listsContainingFile(rootUri, relativePath)
        val wasComplete = affected.filter { isComplete(it) == true }.toSet()

        writeFileState(rootUri, relativePath, now) { it.copy(isCompleted = completed) }

        return refreshAffected(affected, wasComplete, now)
    }

    /**
     * A review *submission*: somebody was shown this Task through a Task Review surface and
     * committed a response to it.
     *
     * The other half of the fork §5 turns on, and the only method here that appends a
     * [ReviewEventEntity]. [setFileCompleted] and [setManualItemCompleted] write the same
     * [TaskStateEntity] field and record nothing, because every other reason a Task's completion
     * moves — a checkbox on the List's own page, a bulk edit, a migration backfill — is not
     * somebody's verdict. No caller inherits event-writing by asking for "completed": a submission
     * has to say so.
     *
     * The state and the event are written in one transaction, so a judgment and the state it
     * committed cannot be observed apart. The response is written as given rather than recomputed
     * from the state: what goes on record is what was committed, and if the two ever disagreed that
     * disagreement is itself worth having, since a history that can only repeat current state would
     * answer nothing.
     *
     * Returns the Lists that this moved from incomplete to complete, on the same terms as
     * [setFileCompleted], so whoever called it fires the completion action exactly as before.
     */
    @Transaction
    open suspend fun submitReview(
        target: DecisionTarget,
        response: ReviewResponse,
        template: TemplateVersionRef,
        context: ReviewContext?,
        now: Long
    ): List<Long> {
        val taskId = when (target) {
            is DecisionTarget.File -> taskForContent(target.rootUri, target.relativePath, now)
            is DecisionTarget.ManualItem -> taskIdForItem(target.itemId) ?: return emptyList()
        }
        val affected = when (target) {
            is DecisionTarget.File -> listsContainingFile(target.rootUri, target.relativePath)
            is DecisionTarget.ManualItem -> listOf(target.listId)
        }
        val wasComplete = affected.filter { isComplete(it) == true }.toSet()

        writeTaskState(taskId, now) { it.copy(isCompleted = response.completesTask) }

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

    /** Returns true when removing the last unchecked item completed the list. See [addItem]. */
    @Transaction
    open suspend fun removeItem(listId: Long, itemId: Long, now: Long): Boolean {
        val wasComplete = isComplete(listId) == true
        deleteItem(itemId)
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
     * (cascading their items and webhook state), inserts the new list, then all of its items.
     * Either the whole snapshot lands or nothing does, so a failure can never leave a
     * half-populated folder list behind.
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
                ListItemEntity(
                    // Its name downstream, fixed now. A second list built over this same folder
                    // later gives the same file a different one, on purpose: they are two
                    // arrivals of it, and a receiver must be able to hold both.
                    publicId = newItemPublicId(
                        fileIdentity(rootRelativePathOf(relativePath, file.relativePath)),
                        now
                    ),
                    listId = listId,
                    // Resolved, not minted: a folder can be covered by more than one List, and
                    // both must arrive at the same Task for the same file.
                    taskId = taskForContent(
                        rootUri,
                        rootRelativePathOf(relativePath, file.relativePath),
                        now
                    ),
                    title = file.name,
                    sortOrder = index,
                    createdAt = now,
                    sourceUri = file.uri,
                    sourceRelativePath = file.relativePath,
                    // The identity its decisions are stored against. Resolved once, at insert,
                    // because a list's folder never moves after this point.
                    rootRelativePath = rootRelativePathOf(relativePath, file.relativePath),
                    // Measured by the scan that produced this file, so the Items header can sort
                    // and filter on it from the moment the list exists.
                    sourceSizeBytes = file.sizeBytes,
                    sourceModifiedAt = file.lastModified
                )
            }
        )
        return listId
    }

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
     * The registration row drives it, because that is where a file's path under the root and its
     * presence flag live; the state itself comes from the Task. Both joins are inner, and can be,
     * because a file is only ever checked *because* somebody decided about it, and deciding mints
     * the Task and writes its state in the same transaction. A file that could be marked checked
     * without those rows existing is not a shape this code can produce.
     *
     * A file already flagged missing is excluded — there is nothing left to delete — and so is an
     * unchecked one, which is exactly the thing the user did not mark.
     */
    @Query(
        """
        SELECT frs.relativePath FROM file_review_state frs
        JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND ts.isCompleted = 1 AND frs.sourceMissing = 0
        ORDER BY frs.relativePath COLLATE NOCASE ASC
        """
    )
    abstract suspend fun getCheckedFilePaths(rootUri: String): List<String>

    /** How many there are, for the card that offers the delete. Same filters, no paths loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM file_review_state frs
        JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND ts.isCompleted = 1 AND frs.sourceMissing = 0
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

    @Query(
        """
        UPDATE file_review_state SET sourceMissing = :missing
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

        val appendFrom = maxSortOrder(listId) + 1
        insertItems(
            diff.addedPaths.mapIndexedNotNull { index, path ->
                val file = files.firstOrNull { it.relativePath == path } ?: return@mapIndexedNotNull null
                ListItemEntity(
                    publicId = newItemPublicId(
                        fileIdentity(rootRelativePathOf(listPath, file.relativePath)),
                        now
                    ),
                    listId = listId,
                    // The same resolution a fresh list makes, and for the same reason: a file
                    // appended here may already be under review somewhere else.
                    taskId = taskForContent(
                        list?.sourceRootUri,
                        rootRelativePathOf(listPath, file.relativePath),
                        now
                    ),
                    title = file.name,
                    sortOrder = appendFrom + index,
                    createdAt = now,
                    sourceUri = file.uri,
                    sourceRelativePath = file.relativePath,
                    rootRelativePath = rootRelativePathOf(listPath, file.relativePath),
                    sourceSizeBytes = file.sizeBytes,
                    sourceModifiedAt = file.lastModified
                )
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
     * Sets one review action on a manual item. Action metadata only: it deliberately does not call
     * [refreshCompletion] and returns nothing, so it can never report a completion transition and
     * therefore can never cause a webhook to be delivered.
     *
     * Both kinds of action are one read-modify-write of the Task's set now. Favourite is no longer
     * a column of its own — [ItemAction.Favourite] and a configured action differ in who decides
     * what they mean, not in where they are kept — and the read stays inside the transaction so
     * two actions toggled at once cannot each write a set that never saw the other.
     */
    @Transaction
    open suspend fun setManualItemAction(
        itemId: Long,
        action: ItemAction,
        enabled: Boolean,
        now: Long
    ) {
        val taskId = taskIdForItem(itemId) ?: return
        writeTaskState(taskId, now) { action.setOn(it, enabled) }
    }

    /** The same, on a file, so every list holding it sees the action. Still never completes. */
    @Transaction
    open suspend fun setFileAction(
        rootUri: String,
        relativePath: String,
        action: ItemAction,
        enabled: Boolean,
        now: Long
    ) {
        writeFileState(rootUri, relativePath, now) { action.setOn(it, enabled) }
    }

    /**
     * Read-modify-write of one file's decisions, registering the file and minting its Task on the
     * first decision anybody makes about it.
     *
     * Three things have to exist before a decision about a file is visible from every screen that
     * asks about it, and they are made together: the registration row a folder review resolves the
     * file through and which carries its path and presence flag, the Task that owns the decision,
     * and the state row the decision is actually written into. Registration is IGNORE-inserted, so
     * a file already known is left exactly as it was; [taskForContent] resolves by `(rootUri,
     * relativePath)`, so a file another List has already decided about lands on the same Task
     * rather than a rival one.
     *
     * Callers are already inside a transaction, so none of this can interleave with another
     * decision about the same file.
     */
    protected open suspend fun writeFileState(
        rootUri: String,
        relativePath: String,
        now: Long,
        change: (ReviewDecisions) -> ReviewDecisions
    ) {
        registerFiles(
            listOf(
                FileReviewStateEntity(
                    publicId = newItemPublicId(fileIdentity(relativePath), now),
                    rootUri = rootUri,
                    relativePath = relativePath,
                    updatedAt = now
                )
            )
        )
        writeTaskState(taskForContent(rootUri, relativePath, now), now, change)
    }

    /**
     * Gives each of these files an identity to carry decisions on, without asserting a single
     * decision about any of them.
     *
     * IGNORE rather than REPLACE, with every field left at its default: resolving a folder for
     * review must never touch what is already there. This is what lets Quick Review hand its
     * queue a stable id per file while leaving a file reviewed last month exactly as it was.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun registerFiles(states: List<FileReviewStateEntity>)

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
     */
    @Query(
        """
        SELECT frs.id AS id,
               frs.publicId AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(ts.isCompleted, 0) AS isCompleted,
               COALESCE(ts.isFavorite, 0) AS isFavorite,
               COALESCE(ts.customActions, '') AS customActions,
               frs.sourceMissing AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
        """
    )
    abstract fun observeFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): Flow<List<FileReviewStateEntity>>

    /** The same, once, for building a payload out of what is stored right now. */
    @Query(
        """
        SELECT frs.id AS id,
               frs.publicId AS publicId,
               frs.rootUri AS rootUri,
               frs.relativePath AS relativePath,
               COALESCE(ts.isCompleted, 0) AS isCompleted,
               COALESCE(ts.isFavorite, 0) AS isFavorite,
               COALESCE(ts.customActions, '') AS customActions,
               frs.sourceMissing AS sourceMissing,
               frs.updatedAt AS updatedAt
        FROM file_review_state frs
        LEFT JOIN assets a ON a.rootUri = frs.rootUri AND a.relativePath = frs.relativePath
        LEFT JOIN task_state ts ON ts.taskId = a.taskId
        WHERE frs.rootUri = :rootUri AND frs.relativePath IN (:relativePaths)
        """
    )
    abstract suspend fun getFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): List<FileReviewStateEntity>

    /** Every list holding this file, so a decision about it can reach all of them. */
    @Query(
        """
        SELECT DISTINCT l.id
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        WHERE l.sourceRootUri = :rootUri AND i.rootRelativePath = :relativePath
        """
    )
    protected abstract suspend fun listsContainingFile(
        rootUri: String,
        relativePath: String
    ): List<Long>

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
    protected abstract suspend fun insertLegacyManualTask(task: LegacyManualTaskEntity)

    @Insert
    protected abstract suspend fun insertReviewEvent(event: ReviewEventEntity)

    @Query("SELECT taskId FROM assets WHERE rootUri = :rootUri AND relativePath = :relativePath")
    protected abstract suspend fun taskIdForFile(rootUri: String, relativePath: String): String?

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
     * The row is upserted by hand rather than with `@Upsert` for the reason [taskForContent] gives:
     * the key here is the Task, and a Task's state is created with the Task. An absent row means
     * this call is the first thing to touch the Task, not that something is wrong.
     *
     * Returns what was written rather than nothing, because a caller that has to record *what it
     * committed* — [submitReview] — must record the state as it stood after the write and not as
     * the caller guessed before it. Returning the value is what keeps that from being a second read
     * that could see something else.
     */
    protected open suspend fun writeTaskState(
        taskId: String,
        now: Long,
        change: (ReviewDecisions) -> ReviewDecisions
    ): ReviewDecisions {
        val existing = getTaskState(taskId)
        val next = change(existing?.decisions ?: ReviewDecisions())
        val encoded = encodeActionIds(next.customActions)
        if (existing == null) {
            insertTaskState(
                TaskStateEntity(
                    taskId = taskId,
                    isCompleted = next.isCompleted,
                    isFavorite = next.isFavorite,
                    customActions = encoded,
                    updatedAt = now
                )
            )
        } else {
            updateTaskState(
                taskId = taskId,
                isCompleted = next.isCompleted,
                isFavorite = next.isFavorite,
                customActions = encoded,
                updatedAt = now
            )
        }
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
        if (rootUri != null) taskIdForFile(rootUri, relativePath)?.let { return it }

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
        return taskId
    }

    /**
     * A Task for a manually typed item, which has no file and therefore no content row.
     *
     * Every manual item gets its own Task even when two share a title, because a manual item is
     * identified by its row and by nothing else — two "Buy milk" entries are two different things
     * and must not collapse into one the moment they are given an identity.
     *
     * Recorded in [LegacyManualTaskEntity] as it is created, and not only during the migration.
     * Manual creation is not removed until a later step, so a Task minted today would otherwise be
     * invisible to the cleanup path that is supposed to know it may be deleted — and the table
     * would be claiming to name a set it had stopped keeping up to date. It stays what it was
     * meant to be, the Tasks with no file behind them, and closes for good when the write path
     * goes.
     */
    private suspend fun newManualTask(now: Long): String {
        val taskId = TaskId.new().value
        insertTask(TaskEntity(id = taskId, createdAt = now))
        insertTaskState(TaskStateEntity(taskId = taskId, updatedAt = now))
        insertLegacyManualTask(LegacyManualTaskEntity(taskId))
        return taskId
    }

    @Query("DELETE FROM list_items WHERE id = :id")
    protected abstract suspend fun deleteItem(id: Long)

    /** The Task a membership row is of. Null only if the row has already gone. */
    @Query("SELECT taskId FROM list_items WHERE id = :itemId")
    protected abstract suspend fun taskIdForItem(itemId: Long): String?

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
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM list_items i
        JOIN task_state ts ON ts.taskId = i.taskId
        WHERE i.listId = :listId AND ts.isCompleted = 0
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
}
