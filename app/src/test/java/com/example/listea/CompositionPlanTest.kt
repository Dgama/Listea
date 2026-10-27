package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ScannedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The taker's Composition: one Task per Prompt, or one Task per Section.
 *
 * The Section is a declaration in the manifest; grouping tagged Prompts into one Task is a rule the
 * taker chooses at import, so the two compositions are two shapes over the same plan. The tests
 * pin the shapes, the catalog keys that keep a Section Task and a Prompt Task apart, and the
 * dialog's pre-question, which must answer from the manifest alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionPlanTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `prompt composition makes one Task per Prompt`() {
        val plan = planPackages(context, "meals", snapshot(PROTOCOL_V2))

        assertEquals(4, plan.tasks.size)
        assertEquals(
            listOf("meals/p1", "meals/p2", "meals/p3", "meals/p4"),
            plan.tasks.map { it.groupPath }
        )
        assertEquals(
            listOf("First", "Second", "Loose", "Fourth"),
            plan.tasks.map { it.displayName }
        )
        assertEquals(
            listOf("prompt:p1", "prompt:p2", "prompt:p3", "prompt:p4"),
            plan.tasks.map { it.compositionKey }
        )
        assertTrue(plan.tasks.all { it.parts.size == 1 })
    }

    @Test
    fun `section composition groups tagged Prompts and leaves untagged ones alone`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(PROTOCOL_V2),
            composition = CompositionChoice.SECTION
        )

        // The plan sorts by group path, so the untagged Prompt's `meals/p3` comes before the two
        // `meals/~section/…` keys and `main` before `warmup`.
        assertEquals(3, plan.tasks.size)
        val loose = plan.tasks[0]
        val main = plan.tasks[1]
        val warmup = plan.tasks[2]

        assertEquals(CompositionChoice.SECTION.key, "section")
        assertEquals("meals/p3", loose.groupPath)
        assertEquals("Loose", loose.displayName)
        assertEquals("prompt:p3", loose.compositionKey)

        assertEquals("meals/~section/main", main.groupPath)
        assertEquals("Main", main.displayName)
        assertEquals("section:main", main.compositionKey)
        assertEquals(listOf("p2"), main.parts.map { it.partKey })

        assertEquals("meals/~section/warmup", warmup.groupPath)
        assertEquals("Warm-up", warmup.displayName)
        assertEquals("section:warmup", warmup.compositionKey)
        assertEquals(listOf("p1", "p4"), warmup.parts.map { it.partKey })
        assertEquals(listOf(0, 1), warmup.parts.map { it.position })
    }

    @Test
    fun `a section with no tagged Prompt produces no Task`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(PROTOCOL_V2),
            composition = CompositionChoice.SECTION
        )
        assertFalse(plan.tasks.any { it.groupPath.contains("empty") })
    }

    @Test
    fun `section composition over a v1 Package is prompt composition`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(PROTOCOL_V1),
            composition = CompositionChoice.SECTION
        )

        assertEquals(4, plan.tasks.size)
        assertEquals(
            listOf("prompt:p1", "prompt:p2", "prompt:p3", "prompt:p4"),
            plan.tasks.map { it.compositionKey }
        )
    }

    @Test
    fun `section composition over a v2 Package with no registry is prompt composition`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(PROTOCOL_V2.replace(V2_SECTIONS, "[]")),
            composition = CompositionChoice.SECTION
        )

        assertEquals(
            listOf("prompt:p1", "prompt:p2", "prompt:p3", "prompt:p4"),
            plan.tasks.map { it.compositionKey }
        )
    }

    @Test
    fun `a Section and a Prompt catalog key cannot collide`() {
        assertEquals("meals/~section/warmup", sectionCatalogKey("meals", "warmup"))
        assertEquals("meals/p1", catalogKey("meals", "p1"))
        assertFalse(sectionCatalogKey("meals", "p1") == catalogKey("meals", "p1"))
    }

    @Test
    fun `the dialog offers section composition only for a v2 Package that declares sections`() {
        assertTrue(folderOffersSectionComposition(context, "meals", snapshot(PROTOCOL_V2)))
        assertFalse(folderOffersSectionComposition(context, "meals", snapshot(PROTOCOL_V1)))
        assertFalse(
            folderOffersSectionComposition(
                context,
                "meals",
                snapshot(PROTOCOL_V2.replace(V2_SECTIONS, "[]"))
            )
        )
    }

    @Test
    fun `the dialog's answer does not need prompt bodies`() {
        // The snapshot holds the manifest and no description member at all: offering the choice
        // reads the declaration, not the Prompts.
        val root = temporary.root
        val manifestUri = write(root, ".listea/manifest.json", PROTOCOL_V2)
        val snapshot = FolderSnapshot(
            files = listOf(ScannedFile("manifest.json", ".listea/manifest.json", manifestUri)),
            directories = listOf(".listea")
        )
        assertTrue(folderOffersSectionComposition(context, "meals", snapshot))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun write(root: File, path: String, text: String): String {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file.toURI().toString()
    }

    /**
     * A scope holding one Package: four Prompts, three tagged (`warmup` holds two, `main` one) and
     * one untagged. `empty` is declared with no members.
     */
    private fun snapshot(manifest: String): FolderSnapshot {
        val root = temporary.root
        val files = mutableListOf<ScannedFile>()
        files += ScannedFile(
            "manifest.json",
            ".listea/manifest.json",
            write(root, ".listea/manifest.json", manifest)
        )
        for (key in listOf("p1", "p2", "p3", "p4")) {
            files += ScannedFile(
                "$key.json",
                "prompts/$key.json",
                write(root, "prompts/$key.json", BODY)
            )
        }
        return FolderSnapshot(
            files = files,
            directories = listOf(".listea", "prompts")
        )
    }

    private companion object {
        const val V2_SECTIONS =
            """[{"sectionKey":"warmup","title":"Warm-up"},{"sectionKey":"main","title":"Main"},""" +
                """{"sectionKey":"empty","title":"Empty"}]"""

        val PROTOCOL_V1 = manifest("listea.package/1", sections = false)
        val PROTOCOL_V2 = manifest("listea.package/2", sections = true)

        fun manifest(protocol: String, sections: Boolean): String = """
            {
              "protocol": "$protocol",
              "packageKey": "meals",
              "revision": 1,
              "title": "Meals",
              "prompts": [
                ${prompt("p1", "First", "warmup", protocol)},
                ${prompt("p2", "Second", "main", protocol)},
                ${prompt("p3", "Loose", null, protocol)},
                ${prompt("p4", "Fourth", "warmup", protocol)}
              ],
              "resources": []
              ${if (sections) ",\"sections\": $V2_SECTIONS" else ""}
            }
        """.trimIndent()

        fun prompt(key: String, title: String, section: String?, protocol: String): String {
            val v2 = protocol == "listea.package/2"
            // The attachment field was renamed in v2; writing the other spelling is a diagnostic,
            // not an alias, so the fixture must speak the protocol it declares.
            val files = if (v2) "\"files\": []" else "\"assets\": []"
            val tag = if (section == null || !v2) "" else ",\"section\": \"$section\""
            return """
                {
                  "promptKey": "$key",
                  "kind": "profile",
                  "schema": 1,
                  "response": "choice.single",
                  "title": "$title",
                  "description": "prompts/$key.json",
                  $files
                  $tag
                }
            """.trimIndent()
        }

        val BODY: String = """
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
