package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.ReviewBlock
import com.example.listea.data.ReviewPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Package Prompt in the catalog: a Task with no Assets whose content is its payload.
 *
 * Zero Assets used to mean "nothing to judge", which was right for a media Task whose file had
 * gone and wrong for a Prompt — an image-less dating profile is still a Prompt. The two questions
 * this pins are therefore the eligibility one (a payload Task is reviewable exactly when a Viewer
 * can draw its envelope) and the scope one (the Package is named by the manifest title).
 */
class PackageCatalogTest {

    @Test
    fun `a Prompt with a Viewer is reviewable though it has no Assets`() {
        val rows = listOf(row("alice"))

        assertNull(catalogBlock(rows.single()))
        assertEquals(listOf("alice"), taskReviewQueue(ROOT, rows).map { it.title })
    }

    @Test
    fun `a Prompt of an unsupported kind is refused rather than vacuously eligible`() {
        val rows = listOf(row("alice", kind = "video", schema = 1))

        assertEquals(ReviewBlock.NO_VIEWER, catalogBlock(rows.single()))
        assertTrue(taskReviewQueue(ROOT, rows).isEmpty())
    }

    @Test
    fun `a Prompt of an unsupported schema is refused too`() {
        val rows = listOf(row("alice", kind = "profile", schema = 2))

        assertEquals(ReviewBlock.NO_VIEWER, catalogBlock(rows.single()))
        assertTrue(taskReviewQueue(ROOT, rows).isEmpty())
    }

    @Test
    fun `a Prompt whose required media is missing stays ineligible`() {
        val rows = listOf(row("alice", missing = 1))

        assertEquals(ReviewBlock.MISSING, catalogBlock(rows.single()))
        assertTrue(taskReviewQueue(ROOT, rows).isEmpty())
    }

    @Test
    fun `the Package scope is named by the manifest title`() {
        val rows = listOf(row("alice"), row("bob"), row("carol"))

        val view = catalogAtScope(rows, "")

        val scope = view.scopes.single()
        assertEquals("dating-demo", scope.path)
        assertEquals("Dating demo", scope.name)
        assertEquals(3, scope.taskCount)
        // The Tasks remain exactly one level down, named by their Prompt keys at that level.
        assertTrue(view.tasks.isEmpty())
        assertEquals(
            listOf("alice", "bob", "carol"),
            catalogAtScope(rows, "dating-demo").tasks.map { it.displayName }
        )
    }

    @Test
    fun `a scope with no Package title falls back to its path segment`() {
        val rows = listOf(row("alice", packageKey = null, title = null))

        assertEquals("dating-demo", catalogAtScope(rows, "").scopes.single().name)
    }

    private fun row(
        prompt: String,
        kind: String? = "profile",
        schema: Int? = 1,
        packageKey: String? = "dating-demo",
        title: String? = "Dating demo",
        missing: Int = 0
    ): CatalogRow = CatalogRow(
        taskId = "t-$prompt",
        groupPath = "dating-demo/$prompt",
        displayName = prompt,
        isCompleted = false,
        assetCount = 0,
        missingAssets = missing,
        sizeBytes = null,
        lastModified = null,
        hasUnviewableAsset = false,
        packageKey = packageKey,
        packageTitle = title,
        // The Prompt envelope lives on the part now: a Package Prompt Task holds one part whose
        // payload is what the eligibility question reads.
        parts = listOf(
            ReviewPart(
                partKey = prompt,
                position = 0,
                promptKey = prompt,
                payloadKind = kind,
                payloadSchema = schema,
                payloadRevision = if (kind == null) null else 1
            )
        )
    )

    private companion object {
        const val ROOT = "content://root"
    }
}
