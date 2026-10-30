package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadPartRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.PartApplicability
import com.example.listea.domain.PartCondition
import com.example.listea.domain.PartConditionMatch
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.encode
import com.example.listea.domain.encodePartCondition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export v7: a part says **whether it was asked**, not only what was answered.
 *
 * Version 7 is a superset of v6's `parts[]`: four keys — `judgment`, `when`, `applicability` and
 * `stale` — sit between `required` and `result`, and nothing else about the body moves. The tests
 * pin three things a receiver is entitled to rely on:
 *
 * - the version is claimed only when a part actually carries a condition or a judgment, so a
 *   v0.8.4-shaped round stays v6 and its bytes do not change;
 * - the key order is the contract, and each key is present on every part (`null` and `false`
 *   included) when the version promises it;
 * - `applicability` is the same derivation the review surface and the write path read, computed
 *   from the gate's answer and fail-closed on a condition this build cannot parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportV7Test {

    // --- version --------------------------------------------------------------------------------

    @Test
    fun `a conditional part makes the round v7`() {
        val mapping = mapExport(payload(task(parts = listOf(part("menu", condition = GATE_IS_YES)))))

        assertEquals(7, mapping.version)
    }

    @Test
    fun `a part with its own judgment makes the round v7`() {
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part(
                            "menu",
                            judgment = ExportTemplateView("builtin.media.part", "Media Part", 1)
                        )
                    )
                )
            )
        )

        assertEquals(7, mapping.version)
    }

    @Test
    fun `a round with no branch facts stays v6`() {
        val mapping = mapExport(payload(task(parts = twoParts(), hasMultipleParts = true)))

        assertEquals(6, mapping.version)
        val part = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
        assertEquals(
            listOf("partKey", "promptKey", "kind", "schema", "revision", "required", "result", "assets"),
            part.keys().asSequence().toList()
        )
    }

    // --- key order ------------------------------------------------------------------------------

    @Test
    fun `a v7 part writes judgment when applicability stale before result`() {
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part(
                            "menu",
                            judgment = ExportTemplateView("builtin.media.part", null, 1),
                            condition = GATE_IS_YES,
                            applicability = PartApplicability.Inactive,
                            stale = true
                        )
                    )
                )
            )
        )
        val part = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)

        assertEquals(
            listOf(
                "partKey", "promptKey", "kind", "schema", "revision", "required",
                "judgment", "when", "applicability", "stale", "result", "assets"
            ),
            part.keys().asSequence().toList()
        )
    }

    // --- values ---------------------------------------------------------------------------------

    @Test
    fun `a judgment is an identity, not a copy of its definition`() {
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part(
                            "menu",
                            judgment = ExportTemplateView("builtin.media.part", "Media Part", 1)
                        )
                    )
                )
            )
        )
        val part = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)

        assertEquals(
            listOf("id", "version"),
            part.getJSONObject("judgment").keys().asSequence().toList()
        )
        assertEquals("builtin.media.part", part.getJSONObject("judgment").getString("id"))
        assertEquals(1, part.getJSONObject("judgment").getInt("version"))
    }

    @Test
    fun `an unconditional part says null judgment and null when`() {
        // A v7 round because another part carries a judgment: the four keys are present on every
        // part, so the unconditional one says its nulls rather than omitting them.
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part("receipt"),
                        part("menu", judgment = ExportTemplateView("builtin.media.part", null, 1))
                    ),
                    hasMultipleParts = true
                )
            )
        )
        val part = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)

        assertTrue(part.isNull("judgment"))
        assertTrue(part.isNull("when"))
        assertEquals("active", part.getString("applicability"))
        assertFalse(part.getBoolean("stale"))
    }

    @Test
    fun `an equals condition is spelled prompt field equals`() {
        val mapping = mapExport(payload(task(parts = listOf(part("menu", condition = GATE_IS_YES)))))
        val condition = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("when")

        assertEquals(
            listOf("prompt", "field", "equals"),
            condition.keys().asSequence().toList()
        )
        assertEquals("gate", condition.getString("prompt"))
        assertEquals("choice", condition.getString("field"))
        assertEquals("yes", condition.getString("equals"))
    }

    @Test
    fun `an in condition is spelled prompt field in`() {
        val condition = PartCondition(
            sourcePartKey = "gate",
            field = "choice",
            match = PartConditionMatch.In,
            values = listOf("yes", "maybe")
        )
        val mapping = mapExport(payload(task(parts = listOf(part("menu", condition = condition)))))
        val json = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("when")

        assertEquals(listOf("prompt", "field", "in"), json.keys().asSequence().toList())
        assertEquals(
            listOf("yes", "maybe"),
            (0 until json.getJSONArray("in").length()).map { json.getJSONArray("in").getString(it) }
        )
    }

    @Test
    fun `applicability is the wire word and stale is the part's flag`() {
        val mapping = mapExport(
            payload(
                task(
                    parts = listOf(
                        part(
                            "menu",
                            condition = GATE_IS_YES,
                            applicability = PartApplicability.Inactive,
                            stale = true,
                            result = ExportPartResult.Choice("yes", "Yes")
                        )
                    )
                )
            )
        )
        val part = bodyOf(mapping)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)

        assertEquals("inactive", part.getString("applicability"))
        assertTrue(part.getBoolean("stale"))
        // A set-aside answer is kept: it is a true account of what was answered while the branch
        // was open, and the version says so rather than silently dropping it.
        assertEquals("yes", part.getJSONObject("result").getJSONObject("choice").getString("id"))
    }

    // --- the assembly, from rows ----------------------------------------------------------------

    @Test
    fun `applicability is derived from the gate's answer the same way the review surface reads it`() {
        assertEquals(
            PartApplicability.Pending,
            assembledApplicability(gateResponse = null)
        )
        assertEquals(
            PartApplicability.Active,
            assembledApplicability(gateResponse = choice("yes"))
        )
        assertEquals(
            PartApplicability.Inactive,
            assembledApplicability(gateResponse = choice("no"))
        )
    }

    @Test
    fun `an unreadable condition exports null when and still claims v7`() {
        val parts = assembledParts(
            gateResponse = choice("yes"),
            menuCondition = """{"prompt":"gate","field":"choice","equals":"yes","bogus":1}"""
        )
        val view = parts.single { it.partKey == "menu" }

        assertNull(view.condition)
        assertTrue(view.unreadable)
        // Applicability is the branch's state, not presentability: the condition could not be read,
        // so the evaluator has nothing to close the part with — and the app refuses the page on its
        // availability, which the v7 body has no key for. The version still says a branch fact was
        // declared, so the round is not silently demoted to a v6 body that cannot mention one.
        assertEquals(PartApplicability.Active, view.applicability)
        assertEquals(
            7,
            mapExport(payload(task(parts = parts, hasMultipleParts = true))).version
        )
    }

    @Test
    fun `a part's own judgment is resolved from the stored reference`() {
        val view = assembledParts(
            gateResponse = choice("yes"),
            menuJudgment = encodeTemplateAttachments(
                listOf(TemplateVersionRef(TemplateId("builtin.media.part"), 1))
            )
        ).single { it.partKey == "menu" }

        assertEquals("builtin.media.part", view.judgment?.id)
        assertEquals(1, view.judgment?.version)
        // This build seeds no template by that id, so the identity carries no name.
        assertNull(view.judgment?.name)
    }

    @Test
    fun `an inactive answered part keeps its result and its stale flag`() {
        val views = assembledParts(
            gateResponse = choice("no"),
            menuResponse = menuChoice("yes"),
            menuStale = true
        )
        val menu = views.single { it.partKey == "menu" }

        assertEquals(PartApplicability.Inactive, menu.applicability)
        assertTrue(menu.stale)
        assertEquals("yes", (menu.result as ExportPartResult.Choice).id)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun bodyOf(mapping: ExportMapping) = JSONObject(buildWebhookPayload(mapping))

    /**
     * The gate's answer as the gate actually stores it: a `form` part answers as structured fields,
     * and a condition on `choice` reads the field, not an invented option event.
     */
    private fun choice(value: String) = ReviewResponse.StructuredReview(
        completed = true,
        fields = listOf(FieldAnswer("choice", "choice.single", value))
    ).encode()

    /** A choice-event answer, the shape a profile or vocabulary part stores. */
    private fun menuChoice(value: String) = ReviewResponse.PrimaryReview(
        completed = true,
        events = listOf(ViewerEvent.Choice(optionId = value, label = value))
    ).encode()

    private fun assembledApplicability(gateResponse: String?): PartApplicability =
        assembledParts(gateResponse).single { it.partKey == "menu" }.applicability

    /** [exportTaskViews] over a gate, a conditional media part and an unconditional receipt. */
    private fun assembledParts(
        gateResponse: String? = null,
        menuResponse: String? = null,
        menuCondition: String = encodePartCondition(GATE_IS_YES),
        menuJudgment: String? = null,
        menuStale: Boolean = false
    ): List<ExportPartView> {
        val rows = listOf(
            ListItemEntity(
                id = 1,
                publicId = "ROW-1",
                listId = 1,
                taskId = "task-1",
                title = "Conditional",
                isCompleted = false,
                sortOrder = 0,
                createdAt = 0
            )
        )
        val parts = listOf(
            PayloadPartRow(
                taskId = "task-1", partKey = "gate", position = 0, promptKey = "gate",
                required = true, present = true, kind = "form", schemaVersion = 1, revision = 1,
                response = gateResponse
            ),
            PayloadPartRow(
                taskId = "task-1", partKey = "menu", position = 1, promptKey = "menu",
                required = true, present = true, kind = "media", schemaVersion = 1, revision = 1,
                response = menuResponse, condition = menuCondition, partJudgment = menuJudgment,
                stale = menuStale
            ),
            PayloadPartRow(
                taskId = "task-1", partKey = "receipt", position = 2, promptKey = "receipt",
                required = true, present = true, kind = "media", schemaVersion = 1, revision = 1
            )
        )

        return exportTaskViews(
            rows = rows,
            tasks = mapOf("task-1" to PayloadTaskRow("task-1", "TASK-1", "Conditional", null)),
            assetsByTask = emptyMap(),
            resolver = TemplateResolver.Empty,
            partsByTask = mapOf("task-1" to parts)
        ).single().parts
    }

    private fun payload(vararg tasks: ExportTaskView) = ExportPayload(
        event = "export",
        listId = 1,
        listTitle = "List",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1,
        tasks = tasks.toList()
    )

    private fun task(
        publicId: String = "task-public",
        parts: List<ExportPartView> = emptyList(),
        hasMultipleParts: Boolean = false
    ) = ExportTaskView(
        publicId = publicId,
        title = "Task",
        isCompleted = false,
        templates = emptyList(),
        assets = emptyList(),
        parts = parts,
        hasMultipleParts = hasMultipleParts
    )

    private fun twoParts(): List<ExportPartView> = listOf(part("q1"), part("q2"))

    private fun part(
        key: String,
        condition: PartCondition? = null,
        judgment: ExportTemplateView? = null,
        applicability: PartApplicability = PartApplicability.Active,
        stale: Boolean = false,
        result: ExportPartResult? = null
    ) = ExportPartView(
        partKey = key,
        promptKey = key,
        kind = "media",
        schema = 1,
        revision = 1,
        required = true,
        judgment = judgment,
        condition = condition,
        applicability = applicability,
        stale = stale,
        result = result,
        assets = emptyList()
    )

    private companion object {
        val GATE_IS_YES = PartCondition(
            sourcePartKey = "gate",
            field = "choice",
            match = PartConditionMatch.Equals,
            values = listOf("yes")
        )
    }
}
