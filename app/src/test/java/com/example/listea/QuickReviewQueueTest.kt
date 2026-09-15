package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.fileIdentity
import com.example.listea.data.newItemPublicId
import com.example.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Quick Review resolves out of a folder, now that it resolves a folder rather than a List.
 *
 * These used to be tests about filtering a List's rows down to one folder level. That filtering is
 * gone: the folder listing decides what the queue is, and the only thing a List contributes is
 * nothing at all. What is worth pinning down instead is the identity — a queued file must be
 * addressed by its path under the root, because that is what every List holding it also reads, and
 * an item addressed any other way would make a decision that nothing else could see.
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

    private fun state(
        id: Long,
        path: String,
        completed: Boolean = false,
        favorite: Boolean = false
    ) = FileReviewStateEntity(
        id = id,
        publicId = newItemPublicId(fileIdentity(path), now = id),
        rootUri = root,
        relativePath = path,
        isCompleted = completed,
        isFavorite = favorite,
        updatedAt = 0
    )

    /**
     * The Tasks the same files resolve to, which the review resolves a step before this and hands
     * in. Per path and not per row id: two files are two Tasks whatever their registrations are
     * numbered, and here the two numbers differ so that one standing for the other cannot pass.
     */
    private fun tasksOf(states: List<FileReviewStateEntity>): Map<String, String> =
        states.associate { it.relativePath to "task-${it.relativePath}" }

    private fun pathOf(item: ReviewItem) = (item.target as DecisionTarget.File).relativePath


    @Test
    fun `a queued file is addressed by its path under the root`() {
        // The identity every List over this folder reads too. Addressing it by anything narrower
        // — the file name, an id — would make a decision only this queue could see.
        val files = listOf(file("2026-07-11/bilibili", "a.jpg"))
        val states = listOf(state(1, "2026-07-11/bilibili/a.jpg"))
        val queue = folderReviewQueue(root, files, states, tasksOf(states))

        assertEquals("2026-07-11/bilibili/a.jpg", pathOf(queue.single()))
        assertEquals(root, (queue.single().target as DecisionTarget.File).rootUri)
    }

    @Test
    fun `a file directly in the root is addressed by its bare name`() {
        val states = listOf(state(1, "a.jpg"))
        val queue = folderReviewQueue(root, listOf(file("", "a.jpg")), states, tasksOf(states))
        assertEquals("a.jpg", pathOf(queue.single()))
    }

    @Test
    fun `decisions already held about a file come back with it`() {
        // The point of the whole refactor: reviewing a folder shows what is already known, from
        // wherever it was decided, and creating or deleting a List has nothing to do with it.
        val states = listOf(
            state(1, "sub/a.jpg", completed = true, favorite = true),
            state(2, "sub/b.jpg")
        )
        val queue = folderReviewQueue(
            root,
            listOf(file("sub", "a.jpg"), file("sub", "b.jpg")),
            states,
            tasksOf(states)
        )

        assertTrue(queue[0].decisions.isCompleted)
        assertTrue(queue[0].decisions.isFavorite)
        assertEquals(false, queue[1].decisions.isCompleted)
        // The same answer reaches the filter, so "Checked" means something in a folder no List
        // covers rather than degrading to "nobody knows, keep everything".
        assertEquals(true, reviewFacts(queue[0]).isChecked)
        assertEquals(false, reviewFacts(queue[1]).isChecked)
    }

    @Test
    fun `the queue keeps the folder's own order`() {
        val files = listOf(file("", "c.jpg"), file("", "a.jpg"), file("", "b.jpg"))
        val states = listOf(state(3, "b.jpg"), state(1, "c.jpg"), state(2, "a.jpg"))
        val queue = folderReviewQueue(root, files, states, tasksOf(states))

        // Stored state arrives in whatever order the database returns; the folder decides.
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), queue.map { it.title })
    }

    @Test
    fun `a queued file is identified by its Task, not by its registration row`() {
        // The registration's own number is a handle onto a relationship with a file; the Task is
        // what a session walks and what a judgment is recorded against. They are different numbers
        // here so that either one standing in for the other cannot pass unnoticed.
        val states = listOf(state(42, "a.jpg"))
        val queue = folderReviewQueue(root, listOf(file("", "a.jpg")), states, tasksOf(states))

        assertEquals(42L, queue.single().id)
        assertEquals("task-a.jpg", queue.single().taskId)
    }

    @Test
    fun `a file whose state vanished is dropped rather than shown undecided`() {
        val states = listOf(state(1, "a.jpg"))
        val queue = folderReviewQueue(
            root,
            listOf(file("", "a.jpg"), file("", "b.jpg")),
            states,
            tasksOf(states)
        )

        assertEquals(listOf("a.jpg"), queue.map { it.title })
    }

    @Test
    fun `a folder of nothing but subfolders resolves to an empty queue`() {
        // Directories are filtered out before this, so an empty file list is the honest result:
        // nothing is pulled up from the folders below to make the queue look less empty.
        assertTrue(
            folderReviewQueue(root, emptyList(), listOf(state(1, "a.jpg")), emptyMap()).isEmpty()
        )
        // A file dropped for want of a Task is the same answer as one dropped for want of a
        // registration: there is nothing to review, rather than a card with nothing behind it.
        val states = listOf(state(1, "a.jpg"))
        assertTrue(
            folderReviewQueue(root, listOf(file("", "a.jpg")), states, emptyMap()).isEmpty()
        )
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

    /**
     * A folder resolved the way the screen resolves one: files, their state rows, paired up.
     * [checked] names the files that are already done.
     */
    private fun folder(
        vararg files: FolderFile,
        checked: Set<String> = emptySet()
    ): List<ReviewItem> {
        val states = files.mapIndexed { index, file ->
            state(index + 1L, file.rootRelativePath, completed = file.name in checked)
        }
        return folderReviewQueue(root, files.toList(), states, tasksOf(states))
    }

    private fun names(items: List<ReviewItem>) = items.map { it.title }

    @Test
    fun `an unfiltered round is the whole folder, in the order the folder page shows it`() {
        // The default sort is not a sort: the listing's own order survives it untouched. A folder
        // arrives already in name order, so this looks alphabetical and is not.
        val files = folder(file("", "c.jpg"), file("", "a.jpg"), file("", "b.jpg"))

        assertEquals(
            listOf("c.jpg", "a.jpg", "b.jpg"),
            names(reviewRound(files, FileArrangement.Default, uncheckedOnly = false, now = now))
        )
        // Asked for by name, it really does sort.
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
        // The two narrowings are an AND and neither overrides the other, so the honest answer to
        // a contradiction is an empty round rather than one of them quietly winning.
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
        // The unknown rule, reaching the round intact: a fact nobody knows never excludes a file,
        // because a file hidden for an invisible reason is the one thing a filter must not do.
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
