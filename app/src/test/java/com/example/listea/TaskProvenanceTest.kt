package com.example.listea

import com.example.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under a Task's name says where it came from — and for a Package Task that is the
 * Package's manifest title, never the synthetic `<packageKey>/<promptKey>` catalog key.
 *
 * These cases pin the acceptance finding: a dating Task read `dating-demo/bob` under its name,
 * and `dating-demo` exists nowhere the user can see. The real directory is `dating`, and the
 * Catalog names the same Package `Dating demo` from its manifest.
 */
class TaskProvenanceTest {

    private fun provenance(
        packageKey: String? = null,
        packageTitle: String? = null,
        relativePath: String? = null,
        rootRelativePath: String? = null,
        title: String = "Bob, 31"
    ) = taskProvenance(packageKey, packageTitle, relativePath, rootRelativePath, title)

    @Test
    fun `a Package Task shows its manifest title`() {
        assertEquals(
            "Dating demo",
            provenance(
                packageKey = "dating-demo",
                packageTitle = "Dating demo",
                relativePath = "dating-demo/bob",
                rootRelativePath = "dating-demo/bob"
            )
        )
    }

    @Test
    fun `a Package Task never falls back to the synthetic catalog key`() {
        assertNull(
            provenance(
                packageKey = "dating-demo",
                packageTitle = null,
                relativePath = "dating-demo/bob",
                rootRelativePath = "dating-demo/bob"
            )
        )
    }

    @Test
    fun `a Package title equal to the Task title adds nothing`() {
        assertNull(
            provenance(
                packageKey = "dating-demo",
                packageTitle = "Dating demo",
                relativePath = "dating-demo/dating-demo",
                title = "Dating demo"
            )
        )
    }

    @Test
    fun `a blank Package title adds nothing`() {
        assertNull(provenance(packageKey = "dating-demo", packageTitle = "  "))
    }

    @Test
    fun `a file Task shows its path`() {
        assertEquals(
            "bilibili/a.jpg",
            provenance(relativePath = "bilibili/a.jpg", rootRelativePath = "bilibili/a.jpg")
        )
    }

    @Test
    fun `a path equal to the title falls back to the root path`() {
        assertEquals(
            "bilibili/a.jpg",
            provenance(
                relativePath = "a.jpg",
                rootRelativePath = "bilibili/a.jpg",
                title = "a.jpg"
            )
        )
    }

    @Test
    fun `nothing worth saying is null`() {
        assertNull(provenance(relativePath = "a.jpg", rootRelativePath = "a.jpg", title = "a.jpg"))
        assertNull(provenance(relativePath = "", rootRelativePath = "  "))
        assertNull(provenance())
    }

    @Test
    fun `the item extension reads the fields it carries`() {
        val packaged = ReviewItem(
            id = 1,
            taskId = "task",
            publicId = "public",
            title = "Alice, 28",
            sourceUri = null,
            relativePath = "dating-demo/alice",
            sourceMissing = false,
            target = com.example.listea.data.DecisionTarget.ManualItem(1, 1),
            packageKey = "dating-demo",
            packageTitle = "Dating demo"
        )
        assertEquals("Dating demo", packaged.provenance())
    }
}
