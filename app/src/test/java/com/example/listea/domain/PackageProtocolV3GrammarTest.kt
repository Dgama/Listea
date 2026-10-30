package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `listea.package/3`: a condition between Prompts, the media Prompt, and the Viewer-owned response
 * contract question.
 *
 * The condition is resolved in a second pass, because it names an earlier Prompt and asks that
 * Prompt's Viewer which fields it exposes. Every rule below is fail-closed: a target whose
 * condition cannot be honoured is planned but unavailable, so the report names it instead of
 * silently treating it as unconditional.
 */
class PackageProtocolV3GrammarTest {

    private val gateBody = """
        {
          "title": "Did you eat?",
          "fields": [
            {"key": "ate", "type": "choice.single", "label": "Did you eat?",
             "options": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]}
          ]
        }
    """.trimIndent()

    private val menuBody = """{"stem": "Read the menu, then press Done."}"""

    private val profileBody = """
        {
          "stem": "A card",
          "profile": {"name": "Alice"},
          "options": [{"id": "like", "label": "Like"}, {"id": "pass", "label": "Pass"}]
        }
    """.trimIndent()

    private fun prompt(
        key: String,
        kind: String = "form",
        response: String = "structured",
        description: String = "prompts/$key.json",
        files: String = "[]",
        whenCondition: String? = null
    ) = """
        {
          "promptKey": "$key",
          "kind": "$kind",
          "schema": 1,
          "response": "$response",
          "title": "Prompt $key",
          "description": "$description",
          "files": $files
          ${if (whenCondition == null) "" else ",\"when\": $whenCondition"}
        }
    """.trimIndent()

    private fun prompts(vararg arrays: String): String =
        arrays.joinToString(",", "[", "]") { it.trim().removePrefix("[").removeSuffix("]") }

    private fun manifest(
        prompts: String,
        protocol: String = "listea.package/3"
    ) = """
        {
          "protocol": "$protocol",
          "packageKey": "meals",
          "revision": 1,
          "title": "Meals",
          "prompts": $prompts,
          "resources": []
        }
    """.trimIndent()

    private fun snapshot(
        manifest: String,
        descriptions: Map<String, String> = mapOf(
            "prompts/gate.json" to gateBody,
            "prompts/menu.json" to menuBody
        ),
        present: Set<String> = setOf(
            ".listea/manifest.json",
            "prompts/gate.json",
            "prompts/menu.json",
            "media/menu.pdf"
        )
    ) = PackageSnapshot(
        rootRelativePath = "meals",
        manifestBytes = manifest.toByteArray(Charsets.UTF_8),
        presentMembers = present,
        readMember = { path -> descriptions[path]?.toByteArray(Charsets.UTF_8) }
    )

    private fun PackagePlan.codes(scope: DiagnosticScope? = null) =
        diagnostics.filter { scope == null || it.scope == scope }.map { it.code }

    private val menuCondition = """{"prompt":"gate","field":"ate","equals":"yes"}"""

    private fun gateAndMenu(condition: String = menuCondition, menuFiles: String = """[{"member":"media/menu.pdf","name":"Menu"}]""") =
        snapshot(
            manifest(
                prompts = prompts(
                    prompt(key = "gate"),
                    prompt(
                        key = "menu",
                        kind = "media",
                        response = "freeform",
                        files = menuFiles,
                        whenCondition = condition
                    )
                )
            )
        )

    @Test
    fun `a v3 manifest reads the gate, the media part and its condition`() {
        val plan = PackageProtocol.plan(gateAndMenu())
        assertTrue(plan.accepted)
        assertTrue(plan.diagnostics.isEmpty())
        assertTrue(plan.hasConditions)

        val gate = plan.prompts.first { it.promptKey == "gate" }
        assertEquals(PromptAvailability.Available, gate.availability)
        assertNull(gate.condition)

        val menu = plan.prompts.first { it.promptKey == "menu" }
        assertEquals(ResponseContract.FREEFORM, menu.response)
        assertEquals(Viewers.Media.kind, menu.kind)
        assertEquals(PromptAvailability.Available, menu.availability)
        assertEquals(
            PartCondition("gate", "ate", PartConditionMatch.Equals, listOf("yes")),
            menu.condition
        )
    }

    @Test
    fun `a media prompt without files is unavailable`() {
        val plan = PackageProtocol.plan(gateAndMenu(menuFiles = "[]"))
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("media-file-missing"),
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a media prompt without a stem is unavailable`() {
        val plan = PackageProtocol.plan(
            gateAndMenu().let { snapshot ->
                PackageSnapshot(
                    rootRelativePath = snapshot.rootRelativePath,
                    manifestBytes = snapshot.manifestBytes,
                    presentMembers = snapshot.presentMembers,
                    readMember = { path ->
                        if (path == "prompts/menu.json") "{}".toByteArray(Charsets.UTF_8)
                        else snapshot.readMember(path)
                    }
                )
            }
        )
        assertEquals(
            PromptAvailability.Unavailable("option-invalid"),
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a media prompt answered by a choice is unsupported`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "menu",
                            kind = "media",
                            response = "choice.single",
                            files = """[{"member":"media/menu.pdf"}]"""
                        )
                    )
                ),
                descriptions = mapOf(
                    "prompts/menu.json" to """
                        {
                          "stem": "Menu",
                          "options": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}]
                        }
                    """.trimIndent()
                )
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("response-unsupported"),
            plan.prompts.single().availability
        )
    }

    @Test
    fun `a media source exposes no field`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "menu",
                            kind = "media",
                            response = "freeform",
                            files = """[{"member":"media/menu.pdf"}]"""
                        ),
                        prompt(
                            key = "receipt",
                            kind = "media",
                            response = "freeform",
                            files = """[{"member":"media/menu.pdf"}]""",
                            whenCondition = """{"prompt":"menu","field":"choice","equals":"x"}"""
                        )
                    )
                ),
                descriptions = mapOf(
                    "prompts/menu.json" to menuBody,
                    "prompts/receipt.json" to menuBody
                ),
                present = setOf(
                    ".listea/manifest.json",
                    "prompts/menu.json",
                    "prompts/receipt.json",
                    "media/menu.pdf"
                )
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-field-unknown"),
            plan.prompts.first { it.promptKey == "receipt" }.availability
        )
        assertEquals(
            PromptAvailability.Available,
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a form field is a valid condition field`() {
        val plan = PackageProtocol.plan(gateAndMenu())
        assertTrue("condition-field-unknown" !in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a condition naming a field the form does not expose is unavailable`() {
        val plan = PackageProtocol.plan(
            gateAndMenu(condition = """{"prompt":"gate","field":"what","equals":"yes"}""")
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-field-unknown"),
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `an unknown source is unavailable`() {
        val plan = PackageProtocol.plan(
            gateAndMenu(condition = """{"prompt":"ghost","field":"ate","equals":"yes"}""")
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-source-unknown"),
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a forward or self reference is unavailable`() {
        val forward = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "menu",
                            kind = "media",
                            response = "freeform",
                            files = """[{"member":"media/menu.pdf"}]""",
                            whenCondition = """{"prompt":"gate","field":"ate","equals":"yes"}"""
                        ),
                        prompt(key = "gate")
                    )
                )
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-source-not-earlier"),
            forward.prompts.first { it.promptKey == "menu" }.availability
        )

        val self = PackageProtocol.plan(
            gateAndMenu(condition = """{"prompt":"menu","field":"ate","equals":"yes"}""")
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-source-not-earlier"),
            self.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a malformed condition is unavailable`() {
        val plan = PackageProtocol.plan(
            gateAndMenu(
                condition = """{"prompt":"gate","field":"ate","equals":"yes","in":["yes"]}"""
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("condition-invalid"),
            plan.prompts.first { it.promptKey == "menu" }.availability
        )
    }

    @Test
    fun `a v2 manifest cannot declare when`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(key = "gate"),
                        prompt(
                            key = "card",
                            kind = "profile",
                            response = "choice.single",
                            description = "prompts/card.json",
                            whenCondition = """{"prompt":"gate","field":"ate","equals":"yes"}"""
                        )
                    ),
                    protocol = "listea.package/2"
                ),
                descriptions = mapOf(
                    "prompts/gate.json" to gateBody,
                    "prompts/card.json" to profileBody
                ),
                present = setOf(
                    ".listea/manifest.json",
                    "prompts/gate.json",
                    "prompts/card.json"
                )
            )
        )
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("when-in-v2"),
            plan.prompts.first { it.promptKey == "card" }.availability
        )
    }

    @Test
    fun `a v2 manifest cannot declare a media kind`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "menu",
                            kind = "media",
                            response = "freeform",
                            files = """[{"member":"media/menu.pdf"}]"""
                        )
                    ),
                    protocol = "listea.package/2"
                )
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("media-in-v2"),
            plan.prompts.single().availability
        )
    }

    @Test
    fun `a v3 prompt cannot use the v1 assets key`() {
        val text = prompt(key = "gate").replace(
            "\"files\": []",
            "\"assets\": []"
        )
        val plan = PackageProtocol.plan(
            snapshot(manifest(prompts = prompts(text)))
        )
        assertEquals(
            PromptAvailability.Unavailable("assets-in-v3"),
            plan.prompts.single().availability
        )
    }

    @Test
    fun `editing a condition changes the fingerprint`() {
        val before = PackageProtocol.plan(gateAndMenu())
        val after = PackageProtocol.plan(
            gateAndMenu(condition = """{"prompt":"gate","field":"ate","in":["yes","no"]}""")
        )
        assertTrue(before.fingerprint != after.fingerprint)
    }

    @Test
    fun `v3 matches the response against the viewer's full contract set`() {
        val single = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "card",
                            kind = "profile",
                            response = "choice.single",
                            description = "prompts/card.json"
                        )
                    )
                ),
                descriptions = mapOf("prompts/card.json" to profileBody),
                present = setOf(".listea/manifest.json", "prompts/card.json")
            )
        )
        assertEquals(
            PromptAvailability.Available,
            single.prompts.single().availability
        )

        val multiple = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "card",
                            kind = "profile",
                            response = "choice.multiple",
                            description = "prompts/card.json"
                        )
                    )
                ),
                descriptions = mapOf("prompts/card.json" to profileBody),
                present = setOf(".listea/manifest.json", "prompts/card.json")
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("response-unsupported"),
            multiple.prompts.single().availability
        )
    }

    @Test
    fun `v2 keeps its hard-coded response rule`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest(
                    prompts = prompts(
                        prompt(
                            key = "card",
                            kind = "profile",
                            response = "choice.multiple",
                            description = "prompts/card.json"
                        )
                    ),
                    protocol = "listea.package/2"
                ),
                descriptions = mapOf("prompts/card.json" to profileBody),
                present = setOf(".listea/manifest.json", "prompts/card.json")
            )
        )
        assertEquals(
            PromptAvailability.Unavailable("response-unsupported"),
            plan.prompts.single().availability
        )
    }

    @Test
    fun `a condition target keeps its condition even when unavailable`() {
        val plan = PackageProtocol.plan(
            gateAndMenu(condition = """{"prompt":"ghost","field":"ate","equals":"yes"}""")
        )
        val menu = plan.prompts.first { it.promptKey == "menu" }
        assertFalse(menu.condition == null)
        assertEquals("ghost", menu.condition?.sourcePartKey)
    }
}
