package com.example.listea

import com.example.listea.data.ScannedFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The importer layer, pinned where it is purest: what a folder snapshot becomes before any row
 * exists.
 *
 * §4.1, §4.2 and §4.3 are one rule — which files are one Task — expressed two ways, and these tests
 * hold both to the plan's letter: file-as-Task admits everything the scanner found (v0.1 parity),
 * folder-as-Task applies the supported-content policy and reports the rest, and neither ever decides
 * whether the result can be reviewed.
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
    fun `file-as-Task produces one Task and one Asset per file, including files no viewer supports`() {
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

        // Every file the scanner admitted becomes a Task, the stray text and the PDF included:
        // admission is not eligibility, and this importer has no content filter to apply.
        assertEquals(listOf("a.jpg", "b.mp4", "sub/c.txt", "sub/notes.pdf"), plan.tasks.map { it.displayName })
        assertEquals(listOf("a.jpg", "b.mp4", "sub/c.txt", "sub/notes.pdf"), plan.tasks.map { it.groupPath })
        plan.tasks.forEach { task ->
            assertEquals(1, task.assets.size)
            assertEquals(task.groupPath, task.assets.single().rootRelativePath)
            assertEquals(0, task.assets.single().sortOrder)
        }
        assertEquals(emptyList<ImportNotice>(), plan.notices)
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
            alice.assets.map { it.rootRelativePath }
        )
        assertEquals(listOf(0, 1), alice.assets.map { it.sortOrder })
        assertEquals(listOf("bob/cv.pdf"), plan.tasks.first { it.displayName == "bob" }.assets.map { it.rootRelativePath })

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
        assertEquals("2026-07-11/a.jpg", plan.tasks.single().assets.single().rootRelativePath)
    }
}
