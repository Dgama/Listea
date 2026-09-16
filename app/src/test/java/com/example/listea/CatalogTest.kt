package com.example.listea

import com.example.listea.data.ReviewBlock
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
        assertFalse(assetViewerAvailable("c.pdf"))
        assertFalse(assetViewerAvailable("d.txt"))
        assertFalse(assetViewerAvailable("noextension"))
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
            reviewBlockFor(listOf(reviewAsset("a.pdf", missing = false)))
        )
        assertEquals(null, reviewBlockFor(listOf(reviewAsset("a.jpg", missing = false))))
        assertEquals(null, reviewBlockFor(emptyList()))
    }

    private fun reviewAsset(name: String, missing: Boolean) =
        com.example.listea.data.ReviewAsset(sourceUri = "content://root/$name", name = name, sourceMissing = missing)
}
