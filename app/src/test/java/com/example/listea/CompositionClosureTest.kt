package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.DiagnosticScope
import com.example.listea.domain.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a v3 condition means once a composition has decided which Prompts share a Task.
 *
 * The protocol has already refused a source that is missing, later, or does not expose the field.
 * The composition's own question is narrower and irreducible: a condition can only read an answer
 * that is **earlier in the same Task**, and no composition may pretend otherwise. So the two
 * answers are pinned side by side — one Task per Section honours the chain, one Task per Prompt
 * imports the dependent parts unavailable and says so — and the dialog's two pre-questions are
 * checked against the same manifests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionClosureTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `one Task per Prompt leaves every conditioned part without its source`() {
        val plan = planPackages(context, "meals", snapshot())

        assertEquals(
            listOf(
                "meals/card1",
                "meals/card2",
                "meals/gate",
                "meals/menu",
                "meals/receipt",
                "meals/solo"
            ),
            plan.tasks.map { it.groupPath }.sorted()
        )
        val menu = plan.tasks.single { it.parts.single().partKey == "menu" }.parts.single()
        assertEquals("condition-source-outside-task", menu.availability)
        // The declaration is kept: the report and a later closed composition both need to know
        // what the part asked for.
        assertEquals("gate", menu.condition?.sourcePartKey)

        val solo = plan.tasks.single { it.parts.single().partKey == "solo" }.parts.single()
        assertEquals("condition-source-outside-task", solo.availability)

        assertEquals(
            listOf("condition-source-outside-task", "condition-source-outside-task"),
            plan.diagnostics.filter { it.scope == DiagnosticScope.PROMPT }.map { it.code }.sorted()
        )
        assertEquals(
            setOf("menu", "solo"),
            plan.diagnostics.filter { it.scope == DiagnosticScope.PROMPT }.mapNotNull { it.key }.toSet()
        )
        // An unconditional part in the same run is untouched.
        assertNull(
            plan.tasks.single { it.parts.single().partKey == "receipt" }.parts.single().availability
        )
    }

    @Test
    fun `one Task per Section keeps a conditioned part with its source`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(),
            composition = CompositionChoice.SECTION
        )

        val meal = plan.tasks.single { it.compositionKey == "section:meal" }
        assertEquals(listOf("gate", "menu", "receipt"), meal.parts.map { it.partKey })
        assertEquals(listOf(0, 1, 2), meal.parts.map { it.position })
        assertEquals(null, meal.parts[1].availability)
        assertEquals(null, meal.parts[2].availability)
        assertEquals("gate", meal.parts[1].condition?.sourcePartKey)

        // The Task mixes a form with media parts, so it names the container and every part its
        // sheet. That is the composition the acceptance Package is judged by.
        assertEquals(Templates.MixedStructured.ref, meal.taskReview)
        assertEquals(Templates.StructuredForm.ref, meal.parts[0].judgment)
        assertEquals(Templates.MediaPart.ref, meal.parts[1].judgment)
        assertEquals(Templates.MediaPart.ref, meal.parts[2].judgment)

        // The untagged dependent stays its own Task, where no source can reach it.
        val solo = plan.tasks.single { it.compositionKey == "prompt:solo" }
        assertEquals("condition-source-outside-task", solo.parts.single().availability)
        assertEquals(Templates.MixedStructured.ref, solo.taskReview)

        assertEquals(
            listOf("condition-source-outside-task"),
            plan.diagnostics.filter { it.scope == DiagnosticScope.PROMPT }.map { it.code }
        )
    }

    @Test
    fun `a homogeneous Section keeps the run's review and no part judgments`() {
        val plan = planPackages(
            context,
            "meals",
            snapshot(),
            composition = CompositionChoice.SECTION
        )

        val cards = plan.tasks.single { it.compositionKey == "section:cards" }
        assertEquals(listOf("card1", "card2"), cards.parts.map { it.partKey })
        assertNull("a homogeneous Task does not override the run", cards.taskReview)
        assertTrue(cards.parts.all { it.judgment == null })
        assertTrue(cards.parts.all { it.availability == null })
    }

    @Test
    fun `a folder that declares a condition is a folder that needs Sections`() {
        assertTrue(folderNeedsSectionComposition(context, snapshot()))

        // The same shape with both conditions removed: nothing asks for the two Prompts to meet.
        val unconditional = snapshot(conditioned = false)
        assertFalse(folderNeedsSectionComposition(context, unconditional))
    }

    @Test
    fun `the dialog offers Section composition for a v3 Package with Sections`() {
        assertTrue(folderOffersSectionComposition(context, "meals", snapshot()))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** One Package: `meal` (gate, menu, receipt), `cards` (two profiles) and an untagged `solo`. */
    private fun snapshot(conditioned: Boolean = true): FolderSnapshot {
        val fixture = V3PackageFixture(temporary.root)
        fixture.member("prompts/gate.json", GATE_BODY)
        fixture.member("prompts/menu.json", mediaBody("Read the menu, then press Done."))
        fixture.member("prompts/receipt.json", mediaBody("File the receipt."))
        fixture.member("prompts/solo.json", profileBody("A card"))
        fixture.member("prompts/card1.json", profileBody("First card"))
        fixture.member("prompts/card2.json", profileBody("Second card"))
        fixture.binary("media/menu.pdf")
        fixture.binary("media/receipt.pdf")
        return fixture.snapshot(
            v3Manifest(
                prompts = listOf(
                    v3Prompt(key = "gate", section = "meal"),
                    v3Prompt(
                        key = "menu",
                        kind = "media",
                        response = "freeform",
                        section = "meal",
                        whenCondition = if (conditioned) GATE_IS_YES else null,
                        files = listOf("media/menu.pdf")
                    ),
                    v3Prompt(
                        key = "receipt",
                        kind = "media",
                        response = "freeform",
                        section = "meal",
                        files = listOf("media/receipt.pdf")
                    ),
                    v3Prompt(
                        key = "solo",
                        kind = "profile",
                        response = "choice.single",
                        whenCondition = if (conditioned) GATE_IS_YES else null
                    ),
                    v3Prompt(
                        key = "card1",
                        kind = "profile",
                        response = "choice.single",
                        section = "cards"
                    ),
                    v3Prompt(
                        key = "card2",
                        kind = "profile",
                        response = "choice.single",
                        section = "cards"
                    )
                ),
                sections = listOf("meal" to "Meal", "cards" to "Cards")
            )
        )
    }
}
