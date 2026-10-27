package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.ReviewBlock
import com.example.listea.domain.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The containment algebra (§6.1) and the one content-classification function (§6.4), held to their
 * letter. A Task's position is one `groupPath`; a scope either contains the whole of it, sits
 * strictly inside it, or is unrelated — and no narrower scope may ever split it.
 */
class CatalogTest {

    // §6.1, architecture invariant 3
    @Test
    fun `entering a nested folder reports the Task as partial rather than splitting it`() {
        // Task Alice lives at "alice". Entering "alice/papers" is inside it, so the scope is partial
        // and the whole Task is not queued there.
        assertFalse(contained("alice", "alice/papers"))
        assertTrue(partial("alice", "alice/papers"))
        assertFalse(directChildOf("alice", "alice/papers"))
    }

    @Test
    fun `a scope at or above a Task contains it, and is never partial`() {
        assertTrue(contained("alice", ""))
        assertTrue(contained("alice", "alice"))
        assertFalse(partial("alice", ""))
        assertFalse(partial("alice", "alice"))
    }

    @Test
    fun `direct children are exactly one level down`() {
        assertTrue(directChildOf("alice", ""))
        assertFalse(directChildOf("alice", "alice"))
        assertTrue(directChildOf("alice/papers", "alice"))
        assertFalse(directChildOf("alice/papers/deep", "alice"))
    }

    @Test
    fun `sibling names do not bleed into each other`() {
        // The path-segment arithmetic, not a LIKE: "bilibili2/x" is not under "bilibili".
        assertFalse(contained("bilibili2/x", "bilibili"))
        assertFalse(directChildOf("bilibili2/x", "bilibili"))
    }

    // §6.4
    @Test
    fun `a viewer exists for picture and video, and for nothing else`() {
        assertTrue(assetViewerAvailable("a.jpg"))
        assertTrue(assetViewerAvailable("b.mp4"))
        assertTrue(assetViewerAvailable("c.PNG"))
        assertTrue(assetViewerAvailable("c.pdf"))
        assertFalse(assetViewerAvailable("d.txt"))
        assertFalse(assetViewerAvailable("noextension"))
    }

    /**
     * The test that stops a second list of extensions existing.
     *
     * `assetKindOf` is written as a mapping through `mediaKindOf` precisely so that it cannot drift
     * from what `assetViewerAvailable` says, and this is the assertion that would catch it if
     * somebody wrote it as its own set instead. Every extension the app knows is checked from both
     * sides at once: a file either has a kind a control governs *and* a viewer, or neither.
     *
     * The failure it is guarding against is quiet and user-facing — a file that renders but has no
     * tag row, or has a tag row its Task can never be submitted from.
     */
    @Test
    fun `the asset-control kind and the viewer agree on every extension this app knows`() {
        val extensions = MediaKind.entries.flatMap { kind -> kind.extensions } +
            listOf("txt", "noextension", "")

        extensions.forEach { extension ->
            val name = if (extension.isEmpty()) "noextension" else "file.$extension"
            val governed = when (assetKindOf(name)) {
                AssetKind.IMAGE, AssetKind.VIDEO, AssetKind.PDF -> true
                AssetKind.OTHER -> false
            }

            assertEquals(
                "a file named $name is governed differently from whether it can be shown",
                assetViewerAvailable(name),
                governed
            )
        }
    }

    @Test
    fun `the kind of a file is what the filter vocabulary says it is`() {
        // The mapping is the whole of the function, so it is worth stating the three arms directly
        // rather than only through the agreement above — `OTHER` is what a text file and an unknown
        // file have in common, and it is not a fourth kind of picture.
        assertEquals(AssetKind.IMAGE, assetKindOf("a.jpg"))
        assertEquals(AssetKind.IMAGE, assetKindOf("a.JPEG"))
        assertEquals(AssetKind.VIDEO, assetKindOf("a.mp4"))
        assertEquals(AssetKind.OTHER, assetKindOf("a.txt"))
        assertEquals(AssetKind.PDF, assetKindOf("a.pdf"))
        assertEquals(AssetKind.PDF, assetKindOf("a.PDF"))
        assertEquals(AssetKind.OTHER, assetKindOf("noextension"))
    }

    @Test
    fun `a name with a dot in its parent is classified by its own extension`() {
        // The callers pass a basename today, and one of them passes a `ReviewAsset.name` that has
        // already been reduced. This pins the behaviour if a caller ever hands over a path instead:
        // the last dot wins, so a file under a dotted folder is still itself.
        assertEquals(AssetKind.IMAGE, assetKindOf("my.folder/photo.jpg"))
        assertEquals(AssetKind.IMAGE, assetKindOf("photo.jpg"))
    }

    // §6.4, §9.3
    @Test
    fun `a Task is blocked by a missing Asset or an unviewable one, and a Task with none is not`() {
        assertEquals(
            ReviewBlock.MISSING,
            reviewBlockFor(listOf(reviewAsset("a.jpg", missing = true)))
        )
        assertEquals(
            ReviewBlock.NO_VIEWER,
            reviewBlockFor(listOf(reviewAsset("a.txt", missing = false)))
        )
        assertEquals(null, reviewBlockFor(listOf(reviewAsset("a.jpg", missing = false))))
        assertEquals(null, reviewBlockFor(listOf(reviewAsset("a.pdf", missing = false))))
        assertEquals(null, reviewBlockFor(emptyList()))
    }

    // A Task row names the add-on page beside its content, because the row is where a Task is
    // chosen. The count is Task-shaped: it does not care whether the Task is a picture, a PDF or a
    // Prompt, only whether a resolvable add-on stamp will actually draw a page.
    @Test
    fun `a Task row says an add-on page exists beside its Asset count`() {
        assertEquals("0 Assets, 1 add-on", catalogTaskDetail(row(assetCount = 0), null, hasAddOn = true))
        assertEquals("2 Assets, 1 add-on", catalogTaskDetail(row(assetCount = 2), null, hasAddOn = true))
    }

    @Test
    fun `a Task row without an add-on says only what it holds`() {
        assertEquals("0 Assets", catalogTaskDetail(row(assetCount = 0), null))
        assertEquals("3 Assets, 1 missing", catalogTaskDetail(row(assetCount = 3, missing = 1), null))
    }

    @Test
    fun `the blocked reason comes after the content and the add-on`() {
        assertEquals(
            "1 Asset, 1 add-on · Source missing",
            catalogTaskDetail(row(assetCount = 1), ReviewBlock.MISSING, hasAddOn = true)
        )
    }

    private fun row(assetCount: Int, missing: Int = 0) = CatalogRow(
        taskId = "t",
        groupPath = "a.jpg",
        displayName = "a.jpg",
        isCompleted = false,
        assetCount = assetCount,
        missingAssets = missing,
        sizeBytes = null,
        lastModified = null,
        hasUnviewableAsset = false
    )

    private fun reviewAsset(name: String, missing: Boolean) =
        com.example.listea.data.ReviewAsset(sourceUri = "content://root/$name", name = name, sourceMissing = missing)
}
