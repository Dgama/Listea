package com.example.listea

import com.example.listea.data.ScannedFile
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The importer layer, pinned where it is purest: what a folder snapshot becomes before any row
 * exists.
 *
 * §4.1, §4.2 and §4.3 are one rule — which files are one Task — expressed two ways. Since v0.8.2f
 * **both folder rules apply the same supported-content policy**: a file a viewer can open becomes a
 * Task, and everything else is skipped and reported. File-as-Task used to keep v0.1's "every file"
 * parity, which made a Package's manifest and Prompt bodies reviewable Tasks with nothing on them.
 * Neither rule decides whether the result can be reviewed — that is the review surface's question.
 */
class ImportPlanTest {

    private fun file(
        name: String,
        relativePath: String,
        sizeBytes: Long? = null,
        lastModified: Long? = null
    ) = ScannedFile(
        name = name,
        relativePath = relativePath,
        uri = "content://root/$relativePath",
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )

    // §4.2, §4.3 rule 1
    @Test
    fun `file-as-Task produces one Task per supported file and reports the rest`() {
        val plan = plan(
            ImporterKind.FILE_AS_TASK,
            importRoot = "",
            FolderSnapshot(
                files = listOf(
                    file("a.jpg", "a.jpg"),
                    file("b.mp4", "b.mp4"),
                    file("sub/c.txt", "sub/c.txt"),
                    file("sub/notes.pdf", "sub/notes.pdf")
                ),
                directories = listOf("sub")
            )
        )

        // Pictures, video and PDF become Tasks; the text file has no viewer, so it would be a Task
        // nobody could judge. It is skipped and reported rather than imported as an empty row.
        assertEquals(listOf("a.jpg", "b.mp4", "sub/notes.pdf"), plan.tasks.map { it.displayName })
        assertEquals(listOf("a.jpg", "b.mp4", "sub/notes.pdf"), plan.tasks.map { it.groupPath })
        plan.tasks.forEach { task ->
            assertEquals(1, task.allAssets().size)
            assertEquals(task.groupPath, task.allAssets().single().rootRelativePath)
            assertEquals(0, task.allAssets().single().sortOrder)
            // A file is one decision unit: the local rule synthesizes exactly one part, keyed
            // `local:0`, with no Prompt payload and no composition of its own.
            val part = task.parts.single()
            assertEquals("local:0", part.partKey)
            assertEquals(0, part.position)
            assertEquals(PartOrigin.FILE, part.origin)
            assertNull(part.prompt)
            assertNull(task.compositionKey)
        }
        assertEquals(listOf(ImportNotice.UnsupportedFile("sub/c.txt")), plan.notices)
    }

    /**
     * The dating Package's shape, imported with *One Task per file*: folders are not Tasks here, so
     * only the media survives. This is the device scenario that exposed the old rule — JSON, the
     * manifest and the README became Tasks with no chips and nothing to review.
     */
    @Test
    fun `a package folder imported file-as-Task keeps only its photos and reports every other file`() {
        val plan = plan(
            ImporterKind.FILE_AS_TASK,
            importRoot = "dating",
            FolderSnapshot(
                files = listOf(
                    file("manifest.json", ".listea/manifest.json"),
                    file("README.md", "README.md"),
                    file("alice.json", "prompts/alice.json"),
                    file("bob.json", "prompts/bob.json"),
                    file("alice.png", "media/alice.png"),
                    file("bob.png", "media/bob.png")
                ),
                directories = listOf(".listea", "media", "prompts")
            )
        )

        assertEquals(
            listOf("dating/media/alice.png", "dating/media/bob.png"),
            plan.tasks.map { it.groupPath }
        )
        assertEquals(
            listOf(
                ImportNotice.UnsupportedFile("dating/.listea/manifest.json"),
                ImportNotice.UnsupportedFile("dating/README.md"),
                ImportNotice.UnsupportedFile("dating/prompts/alice.json"),
                ImportNotice.UnsupportedFile("dating/prompts/bob.json")
            ),
            plan.notices
        )
    }

    @Test
    fun `a Package run is offered no file templates because its Tasks are Prompts`() {
        val templates = listOf(
            TemplateVersionRef(TemplateId("media"), 1),
            TemplateVersionRef(TemplateId("text"), 1)
        )

        assertEquals(emptyList<TemplateVersionRef>(), fileTemplatesFor(ImporterKind.PACKAGE, templates))
        assertEquals(templates, fileTemplatesFor(ImporterKind.FILE_AS_TASK, templates))
        assertEquals(templates, fileTemplatesFor(ImporterKind.FOLDER_AS_TASK, templates))
    }

    // §4.3
    @Test
    fun `folder-as-Task groups immediate child folders, orders descendants, and skips the unsupported`() {
        val plan = plan(
            ImporterKind.FOLDER_AS_TASK,
            importRoot = "",
            FolderSnapshot(
                files = listOf(
                    file("rubric.txt", "rubric.txt"),
                    file("root.jpg", "root.jpg"),
                    file("alice/papers/sample.pdf", "alice/papers/sample.pdf"),
                    file("alice/cv.pdf", "alice/cv.pdf"),
                    file("bob/cv.pdf", "bob/cv.pdf"),
                    file("bob/notes.txt", "bob/notes.txt")
                ),
                directories = listOf("alice", "alice/papers", "bob")
            )
        )

        // root.jpg is a direct supported file, so it is a one-Asset Task exactly as file-as-Task
        // would make it; alice and bob are immediate child folders, so they are Tasks of their own.
        assertEquals(listOf("alice", "bob", "root.jpg"), plan.tasks.map { it.displayName })
        assertEquals(listOf("alice", "bob", "root.jpg"), plan.tasks.map { it.groupPath })

        // Descendants become Assets in deterministic relative-path order, nested folders included
        // but never promoted to Tasks.
        val alice = plan.tasks.first { it.displayName == "alice" }
        assertEquals(
            listOf("alice/cv.pdf", "alice/papers/sample.pdf"),
            alice.allAssets().map { it.rootRelativePath }
        )
        assertEquals(listOf(0, 1), alice.allAssets().map { it.sortOrder })
        // A folder is one synthesized part, exactly as a file is; its files are that part's Assets.
        assertEquals(PartOrigin.FOLDER, alice.parts.single().origin)
        assertEquals(
            listOf("bob/cv.pdf"),
            plan.tasks.first { it.displayName == "bob" }.allAssets().map { it.rootRelativePath }
        )

        // The unsupported files are reported, not Assets: the stray text under the root and the one
        // inside bob.
        assertEquals(
            setOf(ImportNotice.UnsupportedFile("rubric.txt"), ImportNotice.UnsupportedFile("bob/notes.txt")),
            plan.notices.toSet()
        )
    }

    // §4.1, AR-8
    @Test
    fun `a child folder with no files produces no Task and exactly one notice`() {
        val plan = plan(
            ImporterKind.FOLDER_AS_TASK,
            importRoot = "",
            FolderSnapshot(files = emptyList(), directories = listOf("empty"))
        )

        // The directory is visible in the snapshot — the one fact a file-only scan cannot supply —
        // and the importer turns it into a notice rather than guessing it was never looked at.
        assertEquals(emptyList<PlannedTask>(), plan.tasks)
        assertEquals(listOf(ImportNotice.EmptyFolder("empty")), plan.notices)
    }

    @Test
    fun `paths are rebased onto the import root so a nested scope keeps the whole file's identity`() {
        val plan = plan(
            ImporterKind.FILE_AS_TASK,
            importRoot = "2026-07-11",
            FolderSnapshot(files = listOf(file("a.jpg", "a.jpg")), directories = emptyList())
        )

        // The snapshot is relative to the folder scanned; the identity every other part of Listea
        // speaks in is relative to the grant, so the importer prefixes the import root.
        assertEquals("2026-07-11/a.jpg", plan.tasks.single().groupPath)
        assertEquals("2026-07-11/a.jpg", plan.tasks.single().allAssets().single().rootRelativePath)
    }
}
