package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ScannedFile
import com.example.listea.domain.VocabularyPrompt
import com.example.listea.domain.decodePrompt
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
 * One Package, two Viewers.
 *
 * This is acceptance 3 in the form the protocol actually stores: a `.listea` is a manifest of
 * Prompts, and nothing about its structure is per-kind. The planner must plan both, keep the audio
 * Resource beside the image, and leave the vocabulary body readable by its Viewer — the same plan
 * an import run would write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyPackageTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `one Package holding profile and vocabulary plans both, with both Resource kinds`() {
        // The scan scope is the parent; the Package is the `mixed` folder inside it.
        val plan = planPackages(context, "", snapshot(temporary.root))

        assertEquals(2, plan.tasks.size)
        assertTrue(
            "unexpected diagnostics: ${plan.diagnostics}",
            plan.diagnostics.none { it.code == "unsupported-viewer" }
        )

        val tasks = plan.tasks.associateBy { it.groupPath }
        val dating = requireNotNull(tasks["mixed-demo/alice"])
        val vocabulary = requireNotNull(tasks["mixed-demo/serendipity"])
        assertEquals("profile", dating.prompt!!.kind)
        assertEquals("vocabulary", vocabulary.prompt!!.kind)
        assertEquals(listOf("alice-photo"), dating.prompt.resources.map { it.resourceKey })
        assertEquals(listOf("word-audio"), vocabulary.prompt.resources.map { it.resourceKey })

        val packagePlan = plan.packages.single()
        assertEquals(setOf("image", "audio"), packagePlan.resources.map { it.kind }.toSet())
        assertNotNull(packagePlan.resources.single { it.kind == "audio" }.uri)

        val content = decodePrompt(
            vocabulary.prompt.kind,
            vocabulary.prompt.schema,
            vocabulary.prompt.payload
        )
        assertTrue(content is VocabularyPrompt)
        assertEquals("serendipity", (content as VocabularyPrompt).word)
        assertEquals(listOf("c", "b", "a"), content.options.map { it.id })
    }

    @Test
    fun `a vocabulary Prompt without a Viewer would be diagnosed, but this build has one`() {
        // The planner asks the same capability table the runtime does. This test is the reason a
        // new Viewer needs no planner branch: if the table answers, the Package plans.
        assertTrue(com.example.listea.domain.Viewers.supports("vocabulary", 1))
    }

    // --- fixture ----------------------------------------------------------------------------------

    private fun snapshot(root: File): FolderSnapshot {
        fun write(path: String, text: String): String {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
            return file.toURI().toString()
        }

        val manifestUri = write(".listea/manifest.json", MANIFEST)
        val aliceUri = write("prompts/alice.json", ALICE)
        val wordUri = write("prompts/serendipity.json", SERENDIPITY)
        val photoUri = write("media/alice.png", "png bytes")
        val audioUri = write("media/serendipity.mp3", "mp3 bytes")

        return FolderSnapshot(
            files = listOf(
                ScannedFile("manifest.json", "mixed/.listea/manifest.json", manifestUri),
                ScannedFile("alice.json", "mixed/prompts/alice.json", aliceUri),
                ScannedFile(
                    "serendipity.json",
                    "mixed/prompts/serendipity.json",
                    wordUri
                ),
                ScannedFile("alice.png", "mixed/media/alice.png", photoUri),
                ScannedFile("serendipity.mp3", "mixed/media/serendipity.mp3", audioUri)
            ),
            directories = listOf(
                "mixed",
                "mixed/.listea",
                "mixed/prompts",
                "mixed/media"
            )
        )
    }

    private companion object {
        const val MANIFEST = """
            {
              "protocol": "listea.package/1",
              "packageKey": "mixed-demo",
              "revision": 1,
              "title": "Dating and words",
              "prompts": [
                {
                  "promptKey": "alice",
                  "kind": "profile",
                  "schema": 1,
                  "response": "choice.single",
                  "title": "Alice, 28",
                  "description": "prompts/alice.json",
                  "resources": [{ "resourceKey": "alice-photo", "required": true }]
                },
                {
                  "promptKey": "serendipity",
                  "kind": "vocabulary",
                  "schema": 1,
                  "response": "choice.single",
                  "title": "serendipity",
                  "description": "prompts/serendipity.json",
                  "resources": [{ "resourceKey": "word-audio", "required": true }]
                }
              ],
              "resources": [
                { "resourceKey": "alice-photo", "kind": "image", "member": "media/alice.png" },
                { "resourceKey": "word-audio", "kind": "audio", "member": "media/serendipity.mp3" }
              ]
            }
        """

        val ALICE = """
            {
              "stem": "Alice, 28",
              "profile": { "name": "Alice", "age": 28, "bio": "Likes hiking." },
              "photo": "alice-photo",
              "options": [
                { "id": "like", "label": "Like" },
                { "id": "dislike", "label": "Dislike" }
              ]
            }
        """.trimIndent()

        val SERENDIPITY = """
            {
              "stem": "Choose the meaning of the word.",
              "word": "serendipity",
              "audio": "word-audio",
              "answer": "b",
              "options": [
                { "id": "c", "label": "a formal apology" },
                { "id": "b", "label": "a happy accident" },
                { "id": "a", "label": "a long journey" }
              ]
            }
        """.trimIndent()
    }
}
