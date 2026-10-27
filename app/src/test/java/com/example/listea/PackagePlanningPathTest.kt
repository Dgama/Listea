package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ScannedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where a Package says it lives, and where its Resources say they were declared.
 *
 * Both questions were answered wrongly on the first device walk. Discovery reports a Package root
 * *relative to the scanned folder*, and an empty root means "the scanned folder is the Package".
 * Joining that empty root with the folder made `"dating/"`, and every Resource beneath it was
 * stored as `"dating//media/alice.png"` — a path that never named a file, while
 * `package-protocol.md` promised the Package-relative member. [planPackages] therefore records the
 * scope path unchanged, and the write store keeps the member path exactly as declared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackagePlanningPathTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a Package at the scanned folder keeps the scope path without a trailing slash`() {
        val plan = planPackages(context, "dating", snapshot(temporary.root))

        assertEquals(1, plan.packages.size)
        assertEquals("dating", plan.packages.single().rootRelativePath)
        assertEquals("media/alice.png", plan.packages.single().resources.single().member)
        assertNotNull(plan.packages.single().resources.single().uri)
        assertEquals(1, plan.tasks.size)
        // The Prompt is one Package part carrying the payload; it has no file Asset of its own.
        assertTrue(plan.tasks.single().allAssets().isEmpty())
        assertEquals("alice", plan.tasks.single().parts.single().prompt?.promptKey)
    }

    @Test
    fun `a Package that is the whole scan leaves an empty scope path`() {
        val plan = planPackages(context, "", snapshot(temporary.root))

        assertEquals("", plan.packages.single().rootRelativePath)
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `a nested Package joins the scope and its folder with one slash`() {
        val root = temporary.newFolder("nested")
        val plan = planPackages(context, "dating", snapshot(root, rootPath = "nested"))

        assertEquals("dating/nested", plan.packages.single().rootRelativePath)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * A scope holding one Package. [rootPath] is where that Package sits *relative to the scan*,
     * and so also where the files sit; the temporary folder itself is that Package.
     */
    private fun snapshot(root: File, rootPath: String = ""): FolderSnapshot {
        fun write(path: String, text: String): String {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
            return file.toURI().toString()
        }

        fun scope(path: String): String = if (rootPath.isEmpty()) path else "$rootPath/$path"

        val manifestUri = write(".listea/manifest.json", MANIFEST)
        val promptUri = write("prompts/alice.json", PROMPT)
        val photoUri = write("media/alice.png", "png bytes")

        return FolderSnapshot(
            files = listOf(
                ScannedFile("manifest.json", scope(".listea/manifest.json"), manifestUri),
                ScannedFile("alice.json", scope("prompts/alice.json"), promptUri),
                ScannedFile("alice.png", scope("media/alice.png"), photoUri)
            ),
            directories = buildList {
                if (rootPath.isNotEmpty()) add(rootPath)
                add(scope(".listea"))
                add(scope("prompts"))
                add(scope("media"))
            }
        )
    }

    private companion object {
        const val MANIFEST = """
            {
              "protocol": "listea.package/1",
              "packageKey": "dating-demo",
              "revision": 1,
              "title": "Dating demo",
              "prompts": [
                {
                  "promptKey": "alice",
                  "kind": "profile",
                  "schema": 1,
                  "response": "choice.single",
                  "title": "Alice, 28",
                  "description": "prompts/alice.json",
                  "resources": [{ "resourceKey": "alice-photo", "required": true }]
                }
              ],
              "resources": [
                { "resourceKey": "alice-photo", "kind": "image", "member": "media/alice.png" }
              ]
            }
        """

        const val PROMPT = """
            {
              "stem": "Alice, 28",
              "profile": { "name": "Alice", "age": 28, "bio": "Likes hiking." },
              "photo": "alice-photo",
              "options": [
                { "id": "like", "label": "Like" },
                { "id": "dislike", "label": "Dislike" }
              ]
            }
        """
    }
}
