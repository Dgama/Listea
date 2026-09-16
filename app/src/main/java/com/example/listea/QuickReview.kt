package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ReviewItem
import com.example.listea.data.decodeActionIds
import com.example.listea.data.rootRelativePathOf

/**
 * Quick Review is offered on any folder the user can browse, whenever they have left it switched
 * on. Switched off means the Folder tab is a plain browser and gallery.
 *
 * It used to require a list covering the folder, because it reviewed that list's rows and had
 * nowhere else to put a decision. A decision now belongs to the file, so a folder needs nothing
 * but its own files to be reviewable, and no list is created, consulted or replaced by reviewing
 * one.
 *
 * Switching it off is presentation only. Lists keep their items, completion and actions, and the
 * Folder screen keeps showing which list owns what.
 */
fun quickReviewAvailable(enabled: Boolean): Boolean = enabled

/**
 * An item path, which is stored relative to its list's linked folder, rebased onto the SAF root
 * so it can be compared against browsed folder paths.
 */
fun rootRelativeItemPath(scopePath: String, itemPath: String): String =
    rootRelativePathOf(scopePath, itemPath)

/**
 * One direct file of a browsed folder, before the decisions held about it are attached.
 *
 * [sizeBytes] and [lastModified] are carried rather than dropped because the folder's filter and
 * sort are read off them, and Quick Review now reviews exactly what that filter and sort left on
 * screen. Null means the provider did not say, which every filter treats as "does not exclude" —
 * see the unknown rule in FileArrange.kt.
 */
data class FolderFile(
    val name: String,
    val uri: String,
    val rootRelativePath: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** The direct files of [folderPath], named the way a decision about them is named. */
fun folderFilesOf(folderPath: String, contents: FolderContents): List<FolderFile> =
    contents.entries
        .filter { !it.isDirectory }
        .map {
            FolderFile(
                name = it.name,
                uri = it.uri.toString(),
                rootRelativePath = rootRelativePathOf(folderPath, it.name),
                sizeBytes = it.sizeBytes,
                lastModified = it.lastModified
            )
        }

/**
 * A browsed scope's catalog Tasks turned into a review queue.
 *
 * The whole of what Quick Review resolves, and deliberately pure: the catalog decides which Tasks
 * exist, the scope decides which of them are reviewable, and the folder listing — when there is one
 * — decides the order. Nothing about any List takes part, and nothing here creates or imports a
 * Task: a scope with no imported material resolves to an empty queue, which is the honest answer
 * rather than a mint.
 *
 * A Task is queued only when the whole of it is a direct child of the scope, every Asset it needs
 * resolves, and every Asset it needs has a viewer — eligibility §6.4, narrowed to a scope's own
 * direct children by §6.2. A partial Task and a Task whose content cannot be presented are
 * excluded, never shown as a smaller Task.
 *
 * [states] are the registrations the browse still writes, and supply the folder round's wire id and
 * queue handle for file-backed Tasks; a folder-as-Task scope has no registrations and no listing,
 * so its Tasks are ordered by `groupPath` with the same case-insensitive comparator the scan uses.
 */
fun folderReviewQueue(
    rootUri: String,
    scope: String,
    files: List<FolderFile>,
    rows: List<CatalogRow>,
    states: List<FileReviewStateEntity> = emptyList()
): List<ReviewItem> {
    val eligible = rows
        .filter { row ->
            directChildOf(row.groupPath, scope) && row.missingAssets == 0 && !row.hasUnviewableAsset
        }
        .associateBy { it.groupPath }
    val byPath = states.associateBy { it.relativePath }

    fun itemFor(row: CatalogRow, file: FolderFile?): ReviewItem {
        val state = file?.let { byPath[it.rootRelativePath] }
        return ReviewItem(
            id = state?.id ?: 0L,
            taskId = row.taskId,
            // Off the registration row, not made here: a folder round's payload has to name a
            // file-backed Task the same way every time it reports on it.
            publicId = state?.publicId.orEmpty(),
            title = row.displayName,
            sourceUri = file?.uri,
            // Relative to the scope being reviewed: a direct file's own name, a folder Task's name.
            relativePath = file?.name ?: row.displayName,
            sourceMissing = false,
            target = DecisionTarget.File(rootUri, file?.rootRelativePath ?: row.groupPath),
            decisions = ReviewDecisions(row.isCompleted, row.isFavorite, decodeActionIds(row.customActions)),
            sizeBytes = file?.sizeBytes ?: row.sizeBytes,
            lastModified = file?.lastModified ?: row.lastModified,
            rootRelativePath = row.groupPath
        )
    }

    return if (files.isNotEmpty()) {
        // The listing is the source of order, exactly as v0.1: walk it, drop files with no Task.
        files.mapNotNull { file -> eligible[file.rootRelativePath]?.let { itemFor(it, file) } }
    } else {
        eligible.values
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, CatalogRow::groupPath)
                    .thenBy(CatalogRow::groupPath)
            )
            .map { itemFor(it, null) }
    }
}
