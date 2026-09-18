package com.example.listea

import com.example.listea.data.AssetDecisions
import com.example.listea.data.AssetStateEntity
import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.FileReviewStateEntity
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.TaskStateEntity
import com.example.listea.data.encodeActionIds
import com.example.listea.data.taskDecisions
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

    /**
     * A catalog row with one Asset, which is what a row's tags hang on since v0.5.1.
     *
     * `favorite` and `actions` mark that Asset rather than the row, because a row has no tags of
     * its own to mark — that is the whole of the change these tests are here to pin, and a helper
     * that took them and put them back on the row would be asserting the shape that was removed.
     */
    private fun catalogRow(
        taskId: String,
        groupPath: String,
        displayName: String,
        completed: Boolean = false,
        favorite: Boolean = false,
        actions: Set<String> = emptySet(),
        missingAssets: Int = 0,
        unviewable: Boolean = false,
        sizeBytes: Long? = null,
        lastModified: Long? = null
    ) = CatalogRow(
        taskId = taskId,
        groupPath = groupPath,
        displayName = displayName,
        isCompleted = completed,
        assetCount = 1,
        missingAssets = missingAssets,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        hasUnviewableAsset = unviewable,
        assets = listOf(
            ReviewAsset(
                assetId = "$taskId#0",
                sourceUri = "content://tree/Listea/doc/$groupPath",
                name = displayName,
                sourceMissing = false,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                decisions = AssetDecisions(isFavorite = favorite, customActions = actions)
            )
        )
    )

    private fun state(id: Long, path: String) = FileReviewStateEntity(
        id = id,
        publicId = "FILE-$path",
        rootUri = root,
        relativePath = path,
        updatedAt = 0
    )

    private fun pathOf(item: ReviewItem) = (item.target as DecisionTarget.File).relativePath

    /** A Task's own half: completion and nothing else, now that the marks live on the pairs. */
    private fun taskState(taskId: String, completed: Boolean = false) = TaskStateEntity(
        taskId = taskId,
        isCompleted = completed,
        updatedAt = 0
    )

    /** One pair's half, for the live reads below. */
    private fun assetState(
        assetId: String,
        favorite: Boolean = false,
        actions: Set<String> = emptySet()
    ) = AssetStateEntity(
        assetId = assetId,
        isFavorite = favorite,
        customActions = encodeActionIds(actions),
        updatedAt = 0
    )

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

        assertTrue(queue[0].isCompleted)
        // The mark comes off the row's Asset, which is the only place a row can carry one now.
        assertTrue(queue[0].taskDecisions.isFavorite)
        assertEquals(false, queue[1].isCompleted)
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

    // §7 — the round a Todo starts, where there is no scope to be a child of.
    @Test
    fun `a Todo queues every Task it holds, folders or not`() {
        // Three shapes in one catalog: a file at the Upstream's root, a file in a folder, and a
        // folder-as-Task Task whose groupPath names the folder itself. A folder round would queue
        // at most one of them from any single scope; a Todo round queues all three, because the
        // Todo is the scope.
        val rows = listOf(
            catalogRow("task-root", "a.jpg", "a.jpg"),
            catalogRow("task-nested", "2026-07-11/b.jpg", "b.jpg"),
            catalogRow("task-folder", "2026-07-11/c", "c")
        )
        val queue = taskReviewQueue(root, rows)

        assertEquals(listOf("2026-07-11/b.jpg", "2026-07-11/c", "a.jpg"), queue.map { it.rootRelativePath })
    }

    @Test
    fun `a Todo addresses each Task by its own path under the root`() {
        val queue = taskReviewQueue(
            root,
            listOf(catalogRow("task-a", "2026-07-11/bilibili/a.jpg", "a.jpg"))
        )

        assertEquals("2026-07-11/bilibili/a.jpg", pathOf(queue.single()))
        assertEquals(root, (queue.single().target as DecisionTarget.File).rootUri)
        assertEquals("task-a", queue.single().taskId)
    }

    @Test
    fun `a Todo leaves out what cannot be presented rather than showing part of it`() {
        val rows = listOf(
            catalogRow("task-missing", "missing.jpg", "missing.jpg", missingAssets = 1),
            catalogRow("task-pdf", "paper.pdf", "paper.pdf", unviewable = true),
            catalogRow("task-ok", "ok.jpg", "ok.jpg")
        )

        assertEquals(listOf("ok.jpg"), taskReviewQueue(root, rows).map { it.title })
    }

    // What a round holds live once it is open: the decisions, and nothing else.
    @Test
    fun `a round keeps its membership but takes its decisions from the state as it stands`() {
        // The freeze is on *which* Tasks a round walks, and it has to stay: a live filter would
        // take the current card out from under the thumb that checked it. What must not be frozen
        // is the decision, or a checkbox tapped on a review surface writes to the database and
        // draws nothing — which is exactly what a round that emitted once used to do.
        val queue = taskReviewQueue(root, listOf(catalogRow("task-a", "a.jpg", "a.jpg")))
        assertTrue(queue.single().isCompleted.not())

        // Both halves move at once, which is the point: completion arrives by Task and the marks by
        // pair, and a re-read that carried one and not the other would leave a tick that follows the
        // write beside a chip that does not.
        val live = withLiveDecisions(
            queue,
            states = listOf(taskState("task-a", completed = true)),
            assetStates = listOf(assetState("task-a#0", favorite = true, actions = setOf("a1")))
        )

        assertTrue(live.single().isCompleted)
        assertTrue(live.single().taskDecisions.isFavorite)
        assertEquals(setOf("a1"), live.single().taskDecisions.customActions)
        // Unchecking is the same write seen from the other side, so the merge has to carry it back
        // down as readily as it carried it up.
        assertEquals(
            false,
            withLiveDecisions(live, listOf(taskState("task-a", completed = false)), emptyList())
                .single().isCompleted
        )
    }

    @Test
    fun `a live read moves one file's marks without touching its neighbour's`() {
        // The bug v0.5.1 was opened for, at the level the fix lives on: two files of one Task, and a
        // re-read that names only the first leaves the second exactly as the queue resolved it.
        val row = catalogRow("task-a", "a.jpg", "a.jpg").copy(
            assets = listOf(
                ReviewAsset(
                    assetId = "task-a#0",
                    sourceUri = "content://tree/Listea/doc/a.jpg",
                    name = "a.jpg",
                    sourceMissing = false
                ),
                ReviewAsset(
                    assetId = "task-a#1",
                    sourceUri = "content://tree/Listea/doc/b.jpg",
                    name = "b.jpg",
                    sourceMissing = false
                )
            )
        )
        val queue = taskReviewQueue(root, listOf(row))

        val live = withLiveDecisions(
            queue,
            states = emptyList(),
            assetStates = listOf(assetState("task-a#0", actions = setOf("a1")))
        ).single()

        assertEquals(setOf("a1"), live.assets[0].decisions.customActions)
        assertTrue(live.assets[1].decisions.customActions.isEmpty())
    }

    @Test
    fun `a live read says nothing about what a Task is, only what was decided about it`() {
        // Everything that describes the material — the name, the path, what it measures, whether
        // its viewer exists — is the import's answer and the queue's to hold. A re-read that moved
        // any of it would let a row change shape under a round that had already fixed what it
        // walks, and would make the queue's own order meaningless.
        val queue = taskReviewQueue(
            root,
            listOf(catalogRow("task-a", "2026-07-11/a.jpg", "a.jpg", sizeBytes = 10L))
        )

        val live = withLiveDecisions(queue, listOf(taskState("task-a", completed = true)), emptyList())
            .single()

        assertEquals(queue.single().title, live.title)
        assertEquals(queue.single().relativePath, live.relativePath)
        assertEquals(queue.single().rootRelativePath, live.rootRelativePath)
        assertEquals(queue.single().target, live.target)
        assertEquals(10L, live.sizeBytes)
    }

    @Test
    fun `a Task the read no longer names keeps what the queue gave it`() {
        // A Task deleted while a round holds it — a Todo dissolved behind an open review. The round
        // is already over material that has gone, and the honest answer is the card it was drawn
        // from rather than a blank or a dropped row: dropping it would renumber the queue under the
        // user, which is the one thing the freeze exists to prevent.
        val rows = listOf(
            catalogRow("task-a", "a.jpg", "a.jpg", completed = true),
            catalogRow("task-b", "b.jpg", "b.jpg")
        )
        val queue = taskReviewQueue(root, rows)

        val live = withLiveDecisions(queue, listOf(taskState("task-b", completed = true)), emptyList())

        assertEquals(listOf("a.jpg", "b.jpg"), live.map { it.title })
        assertTrue(live[0].isCompleted)
        assertTrue(live[1].isCompleted)
    }

    @Test
    fun `an empty round has nothing to re-read`() {
        assertTrue(
            withLiveDecisions(emptyList(), listOf(taskState("task-a")), emptyList()).isEmpty()
        )
    }

    @Test
    fun `an empty Todo round says why, and a Todo with nothing in it says nothing`() {
        // Asked at the Todo's root, which is the scope a round opened from the outer screen would
        // have used. The scope is now an argument rather than implied, because a round can be
        // started at any level of a Todo and the answer is different at each: what is "empty" at
        // the root may be a folder's worth of Tasks one level down.
        val rootScope = ""
        val nothingEligible = listOf(
            catalogRow("task-missing", "missing.jpg", "missing.jpg", missingAssets = 1)
        )
        assertEquals(
            ScopeNotice.NothingEligible(missingContent = true, noViewer = false),
            scopeNoticeOf(nothingEligible, rootScope)
        )
        assertEquals(
            ScopeNotice.NothingEligible(missingContent = false, noViewer = true),
            scopeNoticeOf(
                listOf(catalogRow("task-pdf", "paper.pdf", "paper.pdf", unviewable = true)),
                rootScope
            )
        )
        // A Todo holding nothing at all has no reason to give: it is empty rather than blocked, and
        // the empty round's own sentence is the honest one.
        assertEquals(null, scopeNoticeOf(emptyList(), rootScope))
        // And nothing is said when there is something to review.
        assertEquals(
            null,
            scopeNoticeOf(listOf(catalogRow("task-ok", "ok.jpg", "ok.jpg")), rootScope)
        )
    }

    // --- Where a Todo's own root is, and why a flat one has a level at all. -------------------

    /**
     * A Todo made by a run started inside a subfolder.
     *
     * Every `groupPath` such a run writes is prefixed with that subfolder — `childRelativePath(
     * source.relativePath, …)` — so the Todo's material begins at `xvideo-jp/` and the grant's root
     * is a level above the Todo rather than the Todo itself. Opening one at `""` found nothing,
     * which is the defect these pin.
     */
    private val nested = listOf(
        catalogRow("t1", "xvideo-jp/a.mp4", "a.mp4"),
        catalogRow("t2", "xvideo-jp/b.mp4", "b.mp4")
    )

    @Test
    fun `a level is read from the Todo's own root and not from the grant's`() {
        assertEquals(emptyList<CatalogRow>(), catalogLevel(nested, ""))
        assertEquals(listOf("t1", "t2"), catalogLevel(nested, "xvideo-jp").map { it.taskId })
    }

    @Test
    fun `a flat Todo has one level, whatever depth its Tasks sit at`() {
        // Flat says the folders were not made into places, so the level is every Task the Todo
        // holds — including one two segments down, which a path-prefix reading would miss.
        val rows = nested + catalogRow("t3", "xvideo-jp/sub/c.mp4", "c.mp4")

        assertEquals(
            listOf("t1", "t2", "t3"),
            catalogLevel(rows, "xvideo-jp", flat = true).map { it.taskId }
        )
        // Not flat, the same catalog is a tree: `sub` is a folder to open and c.mp4 is not here.
        assertEquals(listOf("t1", "t2"), catalogLevel(rows, "xvideo-jp").map { it.taskId })
    }

    @Test
    fun `a flat Todo full of reviewable Tasks reports no reason to be empty`() {
        // The reported symptom, at the layer it comes from: the level is not empty, so an empty
        // round over it has no notice to give — the round that came back empty was reading a
        // different set of rows from the one the catalog was drawing.
        assertEquals(
            listOf("t1", "t2"),
            catalogLevel(nested, "xvideo-jp", flat = true).map { it.taskId }
        )
        assertEquals(null, scopeNoticeOf(nested, "xvideo-jp", flat = true))
        assertEquals(null, scopeNoticeOf(nested, "xvideo-jp"))
    }

    @Test
    fun `a flat Todo whose Tasks are all blocked still says which way`() {
        val blocked = listOf(
            catalogRow("t1", "xvideo-jp/a.pdf", "a.pdf", unviewable = true),
            catalogRow("t2", "xvideo-jp/b.mp4", "b.mp4", missingAssets = 1)
        )

        assertEquals(
            ScopeNotice.NothingEligible(missingContent = true, noViewer = true),
            scopeNoticeOf(blocked, "xvideo-jp", flat = true)
        )
    }

    @Test
    fun `a Todo whose run started at the Upstream itself is unchanged by any of this`() {
        // The origin is the empty path for a run started on the whole Upstream, which is what every
        // Todo made before this existed looks like, and what it did keeps working. One Task per
        // child folder, so each groupPath is a single segment and each is a child of the root.
        val own = listOf(
            catalogRow("t1", "Camera", "Camera"),
            catalogRow("t2", "Wechat", "Wechat")
        )

        assertEquals(listOf("t1", "t2"), catalogLevel(own, "").map { it.taskId })
        assertEquals(listOf("t1", "t2"), catalogLevel(own, "", flat = true).map { it.taskId })
        assertEquals(null, scopeNoticeOf(own, ""))
    }

    @Test
    fun `a tree Todo's root holds folders and no Tasks, and says so by holding none`() {
        // The other half of the same rule, and the reason a level is not a subtree: with the tree
        // kept, one Task per file under a folder leaves the root itself empty of Tasks. Quick
        // Review is not offered there — the catalog hides the button on an empty level — and the
        // level one down is where the round starts.
        val tree = listOf(
            catalogRow("t1", "Camera/a.jpg", "a.jpg"),
            catalogRow("t2", "Camera/b.jpg", "b.jpg")
        )

        assertEquals(emptyList<CatalogRow>(), catalogLevel(tree, ""))
        assertEquals(listOf("t1", "t2"), catalogLevel(tree, "Camera").map { it.taskId })
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
                isCompleted = file.name in checked,
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
