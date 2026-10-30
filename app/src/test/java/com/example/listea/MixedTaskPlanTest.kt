package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.DiagnosticScope
import com.example.listea.domain.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which Tasks are **mixed**, and what each of their parts is answered with.
 *
 * A Section may group unlike Prompts, and once it does the Task-level review v0.8.4 stamped can no
 * longer read every page: a form is answered field by field, a card by one tap, a media part by
 * Done. The plan names the `mixed` container for the Task and a built-in judgment for every part —
 * or, where this build has none, imports the part unavailable and says which part and why.
 *
 * The homogeneous answers are pinned too, because they are the promise that a Task shaped like
 * v0.8.4's keeps v0.8.4's stored shape: no container, no part judgments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixedTaskPlanTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a form and a media part share a mixed Task`() {
        val task = oneTask(
            P("gate", GATE_BODY),
            P("menu", mediaBody("Read the menu."), kind = "media", response = "freeform", files = listOf("media/menu.pdf"))
        )

        assertEquals(Templates.MixedStructured.ref, task.taskReview)
        assertEquals(
            listOf(Templates.StructuredForm.ref, Templates.MediaPart.ref),
            task.parts.map { it.judgment }
        )
        assertTrue(task.parts.all { it.availability == null })
    }

    @Test
    fun `a form and a vocabulary card share a mixed Task`() {
        val task = oneTask(
            P("gate", GATE_BODY),
            P("word", vocabularyBody("maison"), kind = "vocabulary", response = "choice.single")
        )

        assertEquals(Templates.MixedStructured.ref, task.taskReview)
        assertEquals(
            listOf(Templates.StructuredForm.ref, Templates.StructuredSingleChoice.ref),
            task.parts.map { it.judgment }
        )
    }

    @Test
    fun `two forms are two sheets`() {
        val task = oneTask(
            P("gate", GATE_BODY),
            P("second", GATE_BODY)
        )

        assertEquals(Templates.MixedStructured.ref, task.taskReview)
        assertEquals(
            listOf(Templates.StructuredForm.ref, Templates.StructuredForm.ref),
            task.parts.map { it.judgment }
        )
    }

    @Test
    fun `two media parts are two sheets`() {
        val task = oneTask(
            P("menu", mediaBody("Read the menu."), kind = "media", response = "freeform", files = listOf("media/menu.pdf")),
            P("receipt", mediaBody("File the receipt."), kind = "media", response = "freeform", files = listOf("media/receipt.pdf"))
        )

        assertEquals(Templates.MixedStructured.ref, task.taskReview)
        assertEquals(
            listOf(Templates.MediaPart.ref, Templates.MediaPart.ref),
            task.parts.map { it.judgment }
        )
    }

    @Test
    fun `a profile and a vocabulary card share one option-tap sheet`() {
        val task = oneTask(
            P("card", profileBody("Alice"), kind = "profile", response = "choice.single"),
            P("word", vocabularyBody("maison"), kind = "vocabulary", response = "choice.single")
        )

        assertNull("one sheet answers both cards", task.taskReview)
        assertTrue(task.parts.all { it.judgment == null })
        assertTrue(task.parts.all { it.availability == null })
    }

    @Test
    fun `a lone form keeps the run's review`() {
        val task = oneTask(P("gate", GATE_BODY))

        assertNull(task.taskReview)
        assertNull(task.parts.single().judgment)
    }

    @Test
    fun `a lone media part keeps the run's review`() {
        val task = oneTask(
            P("menu", mediaBody("Read the menu."), kind = "media", response = "freeform", files = listOf("media/menu.pdf"))
        )

        assertNull(task.taskReview)
        assertNull(task.parts.single().judgment)
    }

    @Test
    fun `a part no built-in judgment answers is imported unavailable`() {
        val plan = planOf(
            P("gate", GATE_BODY),
            P("mystery", """{"stem": "?"}""", kind = "mystery", response = "structured")
        )
        val task = plan.tasks.single()
        val mystery = task.parts.single { it.partKey == "mystery" }

        // The protocol already refused the kind; the judgment pass must not launder that into a
        // page that looks answerable, and must not overwrite the first cause either.
        assertEquals("unsupported-viewer", mystery.availability)
        assertNull(mystery.judgment)
        assertEquals(Templates.StructuredForm.ref, task.parts.first { it.partKey == "gate" }.judgment)

        val unsupported = plan.diagnostics.filter {
            it.scope == DiagnosticScope.PROMPT && it.code == "unsupported-judgment"
        }
        assertEquals(listOf("mystery"), unsupported.map { it.key })
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** One Prompt of a Section: its key, its body, and the envelope that says how it is answered. */
    private data class P(
        val key: String,
        val body: String,
        val kind: String = "form",
        val response: String = "structured",
        val files: List<String> = emptyList()
    )

    private fun oneTask(vararg prompts: P) = planOf(*prompts).tasks.single()

    /** Every Prompt tagged into one Section, imported as one Task per Section. */
    private fun planOf(vararg prompts: P): ImportPlan {
        val fixture = V3PackageFixture(temporary.root)
        for (prompt in prompts) {
            fixture.member("prompts/${prompt.key}.json", prompt.body)
            for (file in prompt.files) fixture.binary(file)
        }
        return planPackages(
            context,
            "meals",
            fixture.snapshot(
                v3Manifest(
                    prompts = prompts.map { prompt ->
                        v3Prompt(
                            key = prompt.key,
                            kind = prompt.kind,
                            response = prompt.response,
                            section = "meal",
                            files = prompt.files
                        )
                    },
                    sections = listOf("meal" to "Meal")
                )
            ),
            composition = CompositionChoice.SECTION
        )
    }
}
