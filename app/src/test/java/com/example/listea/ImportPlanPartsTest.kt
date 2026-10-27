package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ScannedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The part-shaped planners: a local Task's one synthesized part, and a Package composition's N.
 *
 * A file or folder importer knows files, not judgments, so it writes exactly one part keyed
 * `local:0` and does not fake a Prompt. A Package composition writes one part per grouped Prompt,
 * each carrying its own payload, Assets and Resources. These tests pin both shapes at the planner
 * boundary, where no database or Android view is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPlanPartsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a file Task is one local part with one Asset`() {
        val file = scanned("photo.jpg", "photo.jpg")
        val plan = planFileAsTask("", FolderSnapshot(files = listOf(file), directories = emptyList()))

        val task = plan.tasks.single()
        assertEquals("photo.jpg", task.displayName)
        assertNull(task.compositionKey)
        val part = task.parts.single()
        assertEquals("local:0", part.partKey)
        assertEquals(0, part.position)
        assertEquals(PartOrigin.FILE, part.origin)
        assertNull(part.prompt)
        assertTrue(part.resources.isEmpty())
        assertTrue(part.required)
        assertEquals("photo.jpg", part.assets.single().name)
        assertEquals(0, part.assets.single().sortOrder)
    }

    @Test
    fun `a folder Task is one local part holding the folder's files in order`() {
        val snapshot = FolderSnapshot(
            files = listOf(
                scanned("album/c.png", "c.png"),
                scanned("album/b.jpg", "b.jpg"),
                scanned("album/notes.txt", "notes.txt")
            ),
            directories = listOf("album")
        )
        val plan = planFolderAsTask("", snapshot)

        val task = plan.tasks.single()
        assertEquals("album", task.displayName)
        assertEquals("album", task.groupPath)
        val part = task.parts.single()
        assertEquals("local:0", part.partKey)
        assertEquals(PartOrigin.FOLDER, part.origin)
        assertNull(part.prompt)
        // Supported files sorted by path; the text file is reported, not made an Asset.
        assertEquals(listOf("b.jpg", "c.png"), part.assets.map { it.name })
        assertEquals(listOf(0, 1), part.assets.map { it.sortOrder })
        assertEquals(1, plan.notices.size)
    }

    @Test
    fun `a package section part carries its own payload, Assets and Resources`() {
        val root = temporary.root
        val manifest = """
            {
              "protocol": "listea.package/2",
              "packageKey": "album",
              "revision": 2,
              "title": "Album",
              "prompts": [
                {
                  "promptKey": "one", "kind": "profile", "schema": 1,
                  "response": "choice.single", "title": "One",
                  "description": "prompts/one.json",
                  "files": [{"member": "media/one.jpg", "name": "One photo"}],
                  "resources": [{"resourceKey": "cover", "required": true}],
                  "section": "set"
                },
                {
                  "promptKey": "two", "kind": "profile", "schema": 1,
                  "response": "choice.single", "title": "Two",
                  "description": "prompts/two.json",
                  "files": [],
                  "section": "set"
                }
              ],
              "resources": [
                {"resourceKey": "cover", "kind": "image", "member": "media/cover.jpg"}
              ],
              "sections": [{"sectionKey": "set", "title": "The set"}]
            }
        """.trimIndent()
        val files = listOf(
            scanned(".listea/manifest.json", ".listea/manifest.json"),
            scanned("prompts/one.json", "prompts/one.json"),
            scanned("prompts/two.json", "prompts/two.json"),
            scanned("media/one.jpg", "media/one.jpg"),
            scanned("media/cover.jpg", "media/cover.jpg")
        ).map {
            it.copy(
                uri = write(root, it.relativePath, bodyFor(it.relativePath, manifest))
                    .toURI().toString()
            )
        }

        val plan = planPackages(context, "", FolderSnapshot(files, listOf(".listea", "prompts", "media")), CompositionChoice.SECTION)

        val task = plan.tasks.single()
        assertEquals("section:set", task.compositionKey)
        assertEquals(listOf("one", "two"), task.parts.map { it.partKey })
        assertEquals(listOf(0, 1), task.parts.map { it.position })
        assertTrue(task.parts.all { it.origin == PartOrigin.PACKAGE })
        assertEquals("one", task.parts[0].prompt?.promptKey)
        assertEquals("cover", task.parts[0].prompt?.resources?.single()?.resourceKey)
        assertEquals(listOf("One photo"), task.parts[0].assets.map { it.name })
        assertTrue(task.parts[0].assets.single().rootRelativePath.endsWith("media/one.jpg"))
        assertEquals(listOf("One photo"), task.allAssets().map { it.name })
        assertTrue(task.parts.all { it.required })
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun scanned(relativePath: String, name: String): ScannedFile = ScannedFile(
        name = name,
        relativePath = relativePath,
        uri = "file:///tmp/$relativePath",
        sizeBytes = 1,
        lastModified = 1
    )

    private fun write(root: File, path: String, text: String): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun bodyFor(path: String, manifest: String): String = when {
        path == ".listea/manifest.json" -> manifest
        else -> """
            {
              "stem": "Which?",
              "options": [
                {"id": "like", "label": "Like"},
                {"id": "dislike", "label": "Dislike"}
              ]
            }
        """.trimIndent()
    }
}
