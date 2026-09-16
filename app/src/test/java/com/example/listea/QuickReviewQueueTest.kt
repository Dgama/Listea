package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Quick Review resolves out of a scope, now that it resolves catalog Tasks rather than minting
 * them.
 *
 * The queue is built by walking the browsed listing — where there is one — and mapping each entry to
 * its catalog Task, dropping entries with no Task. A scope with only part of a Task, or with Tasks
 * whose content cannot be presented, resolves to what is eligible and nothing more. What is worth
 * pinning is the identity and the order, because both are what make a decision reach every List
 * that holds the same Task.
 */
class QuickReviewQueueTest {

    private val root = "content://tree/Listea"

    private fun file(
        folderPath: String,
        name: String,
        sizeBytes: Long? = null,
        lastModified: Long? = null
    ) = FolderFile(
        name = name,
        uri = "content://tree/Listea/doc/$name",
        rootRelativePath = rootRelativeItemPath(folderPath, name),
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )

    private fun catalogRow(
        taskId: String,
        groupPath: String,
        displayName: String,
        completed: Boolean = false,
        favorite: Boolean = false,
        missingAssets: Int = 0,
        unviewable: Boolean = false,
        sizeBytes: Long? = null,
        lastModified: Long? = null
    ) = CatalogRow(
        taskId = taskId,
        groupPath = groupPath,
        displayName = displayName,
        isCompleted = completed,
        isFavorite = favorite,
        customActions = "",
        assetCount = 1,
        missingAssets = missingAssets,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        hasUnviewableAsset = unviewable
    )

    private fun state(id: Long, path: String) = FileReviewStateEntity(
        id = id,
        publicId = "FILE-$path",
        rootUri = root,
        relativePath = path,
        updatedAt = 0
    )

    private fun pathOf(item: ReviewItem) = (item.target as DecisionTarget.File).relativePath

    @Test
    fun `a queued file is addressed by its path under the root`() {
        val files = listOf(file("2026-07-11/bilibili", "a.jpg"))
        val rows = listOf(catalogRow("task-a", "2026-07-11/bilibili/a.jpg", "a.jpg"))
        val queue = folderReviewQueue(root, "2026-07-11/bilibili", files, rows, listOf(state(1, "2026-07-11/bilibili/a.jpg")))

        assertEquals("2026-07-11/bilibili/a.jpg", pathOf(queue.single()))
        assertEquals(root, (queue.single().target as DecisionTarget.File).rootUri)
    }

    @Test
    fun `a file directly in the root is addressed by its bare name`() {
        val queue = folderReviewQueue(
            root,
            "",
            listOf(file("", "a.jpg")),
            listOf(catalogRow("task-a", "a.jpg", "a.jpg")),
            listOf(state(1, "a.jpg"))
        )
        assertEquals("a.jpg", pathOf(queue.single()))
    }

    @Test
    fun `decisions already held about a Task come back with it`() {
        val queue = folderReviewQueue(
            root,
            "sub",
            listOf(file("sub", "a.jpg"), file("sub", "b.jpg")),
            listOf(
                catalogRow("task-a", "sub/a.jpg", "a.jpg", completed = true, favorite = true),
                catalogRow("task-b", "sub/b.jpg", "b.jpg")
            ),
            listOf(state(1, "sub/a.jpg"), state(2, "sub/b.jpg"))
        )

        assertTrue(queue[0].decisions.isCompleted)
        assertTrue(queue[0].decisions.isFavorite)
        assertEquals(false, queue[1].decisions.isCompleted)
        assertEquals(true, reviewFacts(queue[0]).isChecked)
        assertEquals(false, reviewFacts(queue[1]).isChecked)
    }

    @Test
    fun `the queue keeps the folder's own order`() {
        val files = listOf(file("", "c.jpg"), file("", "a.jpg"), file("", "b.jpg"))
        val rows = listOf(
            catalogRow("task-a", "a.jpg", "a.jpg"),
            catalogRow("task-b", "b.jpg", "b.jpg"),
            catalogRow("task-c", "c.jpg", "c.jpg")
        )
        val states = listOf(state(3, "b.jpg"), state(1, "c.jpg"), state(2, "a.jpg"))
        val queue = folderReviewQueue(root, "", files, rows, states)

        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), queue.map { it.title })
    }

    @Test
    fun `a queued file is identified by its Task, not by its registration row`() {
        val queue = folderReviewQueue(
            root,
            "",
            listOf(file("", "a.jpg")),
            listOf(catalogRow("task-a", "a.jpg", "a.jpg")),
            listOf(state(42, "a.jpg"))
        )

        assertEquals(42L, queue.single().id)
        assertEquals("task-a", queue.single().taskId)
    }

    // §6.4, §7
    @Test
    fun `a file with no Task is dropped rather than minted`() {
        val files = listOf(file("", "a.jpg"), file("", "b.jpg"))
        val queue = folderReviewQueue(
            root,
            "",
            files,
            listOf(catalogRow("task-a", "a.jpg", "a.jpg")),
            listOf(state(1, "a.jpg"), state(2, "b.jpg"))
        )

        assertEquals(listOf("a.jpg"), queue.map { it.title })
    }

    // §6.4, §7 — test 16
    @Test
    fun `Quick Review excludes partial and ineligible Tasks and includes the eligible ones`() {
        val rows = listOf(
            // The scope itself, not a direct child of the scope.
            catalogRow("task-alice", "alice", "alice"),
            // A direct child whose Asset is unviewable.
            catalogRow("task-pdf", "alice/papers", "papers", unviewable = true),
            // A direct child whose Asset is missing.
            catalogRow("task-missing", "alice/missing", "missing", missingAssets = 1),
            // A direct child, everything present and viewable.
            catalogRow("task-ok", "alice/ok", "ok")
        )
        val queue = folderReviewQueue(root, "alice", emptyList(), rows)

        assertEquals(listOf("ok"), queue.map { it.title })
    }

    // §7 — test 23
    @Test
    fun `a folder of files produces the same order as the v13 registration queue did`() {
        // The listing order is the queue order, exactly as it was when the queue walked
        // registrations; the catalog only supplies the Task, never a new order.
        val files = listOf(file("", "c.jpg"), file("", "a.jpg"), file("", "b.jpg"))
        val rows = files.map { catalogRow("task-${it.name}", it.rootRelativePath, it.name) }
        val queue = folderReviewQueue(root, "", files, rows)

        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), queue.map { it.title })
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), queue.map { it.rootRelativePath })
    }

    @Test
    fun `quick review is offered on any folder once the setting is on`() {
        assertTrue(quickReviewAvailable(enabled = true))
        assertEquals(false, quickReviewAvailable(enabled = false))
    }

    // --- The round: which of the folder's files one Quick Review actually walks. -------------

    private val now = 1_700_000_000_000L

    /** A day's worth of milliseconds back from [now], for the freshness cases. */
    private fun daysAgo(days: Int) = now - days * 24L * 60L * 60L * 1000L

    /** A folder resolved the way the screen resolves one, for the [reviewRound] narrowing tests. */
    private fun folder(vararg files: FolderFile, checked: Set<String> = emptySet()): List<ReviewItem> =
        files.mapIndexed { index, file ->
            ReviewItem(
                id = index + 1L,
                taskId = "task-${file.rootRelativePath}",
                publicId = "FILE-${file.rootRelativePath}",
                title = file.name,
                sourceUri = file.uri,
                relativePath = file.name,
                sourceMissing = false,
                target = DecisionTarget.File(root, file.rootRelativePath),
                decisions = ReviewDecisions(isCompleted = file.name in checked),
                sizeBytes = file.sizeBytes,
                lastModified = file.lastModified
            )
        }

    private fun names(items: List<ReviewItem>) = items.map { it.title }

    @Test
    fun `an unfiltered round is the whole folder, in the order the folder page shows it`() {
        val files = folder(file("", "c.jpg"), file("", "a.jpg"), file("", "b.jpg"))

        assertEquals(
            listOf("c.jpg", "a.jpg", "b.jpg"),
            names(reviewRound(files, FileArrangement.Default, uncheckedOnly = false, now = now))
        )
        assertEquals(
            listOf("a.jpg", "b.jpg", "c.jpg"),
            names(
                reviewRound(
                    files,
                    FileArrangement(sort = FileSort.NAME_ASC),
                    uncheckedOnly = false,
                    now = now
                )
            )
        )
    }

    @Test
    fun `the folder's filter decides which files the round contains`() {
        val round = reviewRound(
            folder(file("", "a.jpg"), file("", "b.txt"), file("", "c.mp4")),
            FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(listOf("a.jpg"), names(round))
    }

    @Test
    fun `the folder's sort decides the order the round walks them in`() {
        val round = reviewRound(
            folder(
                file("", "a.jpg", lastModified = daysAgo(5)),
                file("", "b.jpg", lastModified = daysAgo(1)),
                file("", "c.jpg", lastModified = daysAgo(9))
            ),
            FileArrangement(sort = FileSort.TIME_DESC),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names(round))
    }

    @Test
    fun `filtering to checked while the setting says unchecked only leaves nothing`() {
        val round = reviewRound(
            folder(file("", "a.jpg"), file("", "b.jpg"), checked = setOf("a.jpg")),
            FileArrangement(checked = setOf(CheckedState.CHECKED)),
            uncheckedOnly = true,
            now = now
        )

        assertTrue(round.isEmpty())
    }

    @Test
    fun `filtering to checked without that setting reviews exactly the checked files`() {
        val round = reviewRound(
            folder(file("", "a.jpg"), file("", "b.jpg"), checked = setOf("a.jpg")),
            FileArrangement(checked = setOf(CheckedState.CHECKED)),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(listOf("a.jpg"), names(round))
    }

    @Test
    fun `the unchecked only setting still narrows a round the folder did not filter`() {
        val round = reviewRound(
            folder(file("", "a.jpg"), file("", "b.jpg"), checked = setOf("a.jpg")),
            FileArrangement.Default,
            uncheckedOnly = true,
            now = now
        )

        assertEquals(listOf("b.jpg"), names(round))
    }

    @Test
    fun `a filter and the setting narrow together rather than either replacing the other`() {
        val round = reviewRound(
            folder(
                file("", "a.jpg"),
                file("", "b.jpg"),
                file("", "c.txt"),
                checked = setOf("a.jpg")
            ),
            FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)),
            uncheckedOnly = true,
            now = now
        )

        assertEquals(listOf("b.jpg"), names(round))
    }

    @Test
    fun `a file the provider gave no timestamp for survives a freshness filter`() {
        val round = reviewRound(
            folder(
                file("", "a.jpg", lastModified = daysAgo(40)),
                file("", "b.jpg", lastModified = null)
            ),
            FileArrangement(freshness = setOf(Freshness.TODAY)),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(listOf("b.jpg"), names(round))
    }
}
