package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1 compatibility half of the v2 grammar.
 *
 * A v1 Package has no Sections and calls a Prompt's attachments `assets`; both remain readable
 * exactly as they were before 0.8.4. These tests are the guard against the v2 reader quietly
 * reinterpreting an old manifest: every v1 shape keeps its old meaning, and every v2-only spelling
 * is a diagnostic rather than an alias.
 */
class PackageProtocolV1CompatTest {

    private val body = """
        {
          "stem": "Alice, 28 — likes old films.",
          "options": [
            {"id": "like", "label": "Like"},
            {"id": "dislike", "label": "Dislike"}
          ]
        }
    """.trimIndent()

    private fun manifest(prompts: String = prompt(), extra: String = "") = """
        {
          "protocol": "listea.package/1",
          "packageKey": "dating-demo",
          "revision": 3,
          "title": "Dating demo",
          "prompts": $prompts,
          "resources": []
          $extra
        }
    """.trimIndent()

    private fun prompt(
        assets: String = """[{"member":"media/alice.jpg","name":"Alice"}]""",
        extra: String = ""
    ) = """
        [{
          "promptKey": "alice",
          "kind": "profile",
          "schema": 1,
          "response": "choice.single",
          "title": "Alice",
          "description": "prompts/alice.json",
          "assets": $assets
          $extra
        }]
    """.trimIndent()

    private fun snapshot(
        manifest: String,
        descriptions: Map<String, String> = mapOf("prompts/alice.json" to body),
        present: Set<String> = setOf(
            ".listea/manifest.json", "prompts/alice.json", "media/alice.jpg"
        )
    ) = PackageSnapshot(
        rootRelativePath = "dating-demo",
        manifestBytes = manifest.toByteArray(Charsets.UTF_8),
        presentMembers = present,
        readMember = { path -> descriptions[path]?.toByteArray(Charsets.UTF_8) }
    )

    private fun PackagePlan.codes(scope: DiagnosticScope? = null) =
        diagnostics.filter { scope == null || it.scope == scope }.map { it.code }

    @Test
    fun `a v1 package plans with no sections`() {
        val plan = PackageProtocol.plan(snapshot(manifest()))
        assertTrue(plan.accepted)
        assertTrue(plan.sections.isEmpty())
        assertNull(plan.prompts.single().section)
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `a v1 prompt's assets keep their meaning`() {
        val plan = PackageProtocol.plan(snapshot(manifest()))
        val asset = plan.prompts.single().assets.single()
        assertEquals("media/alice.jpg", asset.member)
        assertEquals("Alice", asset.name)
        assertTrue(asset.present)
        assertEquals(ResponseContract.SINGLE, plan.prompts.single().response)
        assertEquals(PromptAvailability.Available, plan.prompts.single().availability)
    }

    @Test
    fun `a v1 prompt cannot carry a section tag`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest(prompts = prompt(extra = ",\"section\": \"drinks\"")))
        )
        assertTrue(plan.accepted)
        assertEquals(
            PromptAvailability.Unavailable("section-in-v1"),
            plan.prompts.single().availability
        )
        assertTrue("section-in-v1" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `the newest protocol this build writes is v3`() {
        assertEquals("listea.package/3", PackageProtocol.PROTOCOL)
        assertEquals("listea.package/1", PackageProtocol.PROTOCOL_V1)
        assertEquals("listea.package/2", PackageProtocol.PROTOCOL_V2)
        assertEquals("listea.package/3", PackageProtocol.PROTOCOL_V3)
    }

    @Test
    fun `a v1 manifest's fingerprint still covers its whole envelope`() {
        val before = PackageProtocol.plan(snapshot(manifest()))
        val titleChanged = PackageProtocol.plan(
            snapshot(manifest().replace("\"title\": \"Dating demo\"", "\"title\": \"Dates\""))
        )
        assertFalse(before.fingerprint == titleChanged.fingerprint)
    }
}
