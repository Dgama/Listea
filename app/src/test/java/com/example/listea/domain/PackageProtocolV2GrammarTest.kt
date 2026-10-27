package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `listea.package/2`: the Section registry, the renamed Prompt field, and the structured contract.
 *
 * v2 is a superset of v1's envelope with three vocabulary changes, and each one has a shape that
 * must not be read the other way round: a `sections` array in a v1 manifest, `files` in v1, and
 * `assets` in v2 are all disagreements with the declared protocol, not aliases. The tests below
 * pin the reading, the diagnostics, and the fingerprint rule that a Section's *labels* are not
 * content but its *keys* are.
 */
class PackageProtocolV2GrammarTest {

    private val choiceBody = """
        {
          "stem": "Which card?",
          "options": [
            {"id": "like", "label": "Like"},
            {"id": "dislike", "label": "Dislike"}
          ]
        }
    """.trimIndent()

    private val formBody = """
        {
          "title": "Meal",
          "fields": [
            {"key": "ate", "type": "choice.single", "label": "Did you eat?",
             "options": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]},
            {"key": "what", "type": "text", "label": "What?", "when": {"field": "ate", "equals": "yes"}}
          ]
        }
    """.trimIndent()

    private fun prompt(
        key: String = "alice",
        kind: String = "profile",
        response: String = "choice.single",
        description: String = "prompts/alice.json",
        files: String = "[]",
        section: String? = null,
        extra: String = ""
    ) = """
        {
          "promptKey": "$key",
          "kind": "$kind",
          "schema": 1,
          "response": "$response",
          "title": "Prompt $key",
          "description": "$description",
          "files": $files
          ${if (section == null) "" else ",\"section\": \"$section\""}
          $extra
        }
    """.trimIndent()

    private fun prompts(vararg arrays: String): String =
        arrays.joinToString(",", "[", "]") { it.trim().removePrefix("[").removeSuffix("]") }

    private fun manifest(
        prompts: String,
        sections: String? = null,
        protocol: String = "listea.package/2"
    ) = """
        {
          "protocol": "$protocol",
          "packageKey": "meals",
          "revision": 1,
          "title": "Meals",
          "prompts": $prompts,
          "resources": []
          ${if (sections == null) "" else ",\"sections\": $sections"}
        }
    """.trimIndent()

    private fun snapshot(
        manifest: String,
        descriptions: Map<String, String> = mapOf("prompts/alice.json" to choiceBody),
        present: Set<String> = setOf(".listea/manifest.json", "prompts/alice.json")
    ) = PackageSnapshot(
        rootRelativePath = "meals",
        manifestBytes = manifest.toByteArray(Charsets.UTF_8),
        presentMembers = present,
        readMember = { path -> descriptions[path]?.toByteArray(Charsets.UTF_8) }
    )

    private fun PackagePlan.codes(scope: DiagnosticScope? = null) =
        diagnostics.filter { scope == null || it.scope == scope }.map { it.code }

    @Test
    fun `a v2 manifest reads sections and tags a prompt`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(prompt(section = "drinks")),
                    sections = """[{"sectionKey":"drinks","title":"Drinks","order":2}]"""
                )
            )
        )
        assertTrue(plan.accepted)
        val section = plan.sections.single()
        assertEquals("drinks", section.sectionKey)
        assertEquals("Drinks", section.title)
        assertEquals(2, section.order)
        assertEquals("drinks", plan.prompts.single().section)
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `an untagged v2 prompt stays its own`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest(prompts = prompts(prompt()), sections = "[]"))
        )
        assertTrue(plan.accepted)
        assertEquals(null, plan.prompts.single().section)
        assertTrue(plan.sections.isEmpty())
    }

    @Test
    fun `a section order defaults to its registry position`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a","title":"A"},{"sectionKey":"b","title":"B"}]"""
                )
            )
        )
        assertEquals(listOf(0, 1), plan.sections.map { it.order })
    }

    @Test
    fun `files fill the asset list in v2`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(
                        prompt(files = """[{"member":"media/alice.jpg","name":"Alice"}]""")
                    )
                ),
                present = setOf(
                    ".listea/manifest.json", "prompts/alice.json", "media/alice.jpg"
                )
            )
        )
        assertTrue(plan.accepted)
        val asset = plan.prompts.single().assets.single()
        assertEquals("media/alice.jpg", asset.member)
        assertEquals("Alice", asset.name)
        assertEquals(PromptAvailability.Available, plan.prompts.single().availability)
    }

    @Test
    fun `a v1 manifest with sections is rejected as a version disagreement`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"drinks","title":"Drinks"}]""",
                    protocol = "listea.package/1"
                )
            )
        )
        assertFalse(plan.accepted)
        assertEquals(listOf("section-in-v1"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a v1 prompt with files is unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = """
                        [{
                          "promptKey": "alice", "kind": "profile", "schema": 1,
                          "response": "choice.single", "title": "Alice",
                          "description": "prompts/alice.json",
                          "files": []
                        }]
                    """.trimIndent(),
                    protocol = "listea.package/1"
                )
            )
        )
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("files-in-v1"),
            plan.prompts.single().availability
        )
        assertTrue("files-in-v1" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a v2 prompt with assets is unavailable`() {
        val text = prompt().replace("\"files\": []", "\"assets\": []")
        val plan = PackageProtocol.plan(snapshot(manifest(prompts(prompts(text)))))
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("assets-in-v2"),
            plan.prompts.single().availability
        )
    }

    @Test
    fun `a prompt naming an undeclared section is unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest(prompts = prompts(prompt(section = "ghost")), sections = "[]"))
        )
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("section-unknown"),
            plan.prompts.single().availability
        )
        assertTrue("section-unknown" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a duplicate section key rejects the package`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a","title":"A"},{"sectionKey":"a","title":"Again"}]"""
                )
            )
        )
        assertFalse(plan.accepted)
        assertTrue("section-duplicate" in plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a section without a title rejects the package`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a"}]"""
                )
            )
        )
        assertFalse(plan.accepted)
        assertTrue("section-invalid" in plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a section with an unknown field rejects the package`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a","title":"A","colour":"red"}]"""
                )
            )
        )
        assertFalse(plan.accepted)
        assertTrue("section-invalid" in plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a structured prompt is available under the form viewer`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(
                        prompt(
                            key = "meal",
                            kind = "form",
                            response = "structured",
                            description = "prompts/meal.json"
                        )
                    )
                ),
                descriptions = mapOf("prompts/meal.json" to formBody),
                present = setOf(".listea/manifest.json", "prompts/meal.json")
            )
        )
        assertTrue(plan.accepted)
        val stored = plan.prompts.single()
        assertEquals(ResponseContract.STRUCTURED, stored.response)
        assertEquals(PromptAvailability.Available, stored.availability)
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `a section title or order change does not change the fingerprint`() {
        val before = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt(section = "drinks")),
                    sections = """[{"sectionKey":"drinks","title":"Drinks","order":1}]"""
                )
            )
        )
        val after = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt(section = "drinks")),
                    sections = """[{"sectionKey":"drinks","title":"Beverages","order":9}]"""
                )
            )
        )
        assertEquals(before.fingerprint, after.fingerprint)
    }

    @Test
    fun `reordering and adding a section key are handled separately`() {
        val one = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a","title":"A"},{"sectionKey":"b","title":"B"}]"""
                )
            )
        )
        val reordered = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"b","title":"B"},{"sectionKey":"a","title":"A"}]"""
                )
            )
        )
        // The registry's display order is not content: the same keys are the same membership.
        assertEquals(one.fingerprint, reordered.fingerprint)

        val added = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt()),
                    sections = """[{"sectionKey":"a","title":"A"},{"sectionKey":"b","title":"B"},""" +
                        """{"sectionKey":"c","title":"C"}]"""
                )
            )
        )
        assertTrue(one.fingerprint != added.fingerprint)
    }

    @Test
    fun `a section registry limit rejects the package`() {
        val entries = (0 until PackageProtocol.MAX_SECTIONS + 1).joinToString(",", "[", "]") {
            """{"sectionKey":"s$it","title":"S$it"}"""
        }
        val plan = PackageProtocol.plan(
            snapshot(manifest = manifest(prompts = prompts(prompt()), sections = entries))
        )
        assertFalse(plan.accepted)
        assertTrue("limit-exceeded" in plan.codes(DiagnosticScope.PACKAGE))
    }
}
