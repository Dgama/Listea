package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Package envelope, planner, and diagnostics.
 *
 * The happy path is the dating fixture in miniature. The rest are the rejections and
 * degradations the protocol promises: a bad envelope writes nothing, a bad Prompt is left out
 * while its neighbours survive, and an unknown kind is stored with its payload so a later build
 * can draw it without asking the producer to bump the revision.
 */
class PackageProtocolTest {

    private val description = """
        {
          "stem": "Alice, 28 — likes hiking and old films.",
          "profile": {"name": "Alice", "age": 28, "bio": "Likes hiking and old films."},
          "photo": "alice-photo",
          "options": [
            {"id": "like", "label": "Like"},
            {"id": "dislike", "label": "Dislike"}
          ]
        }
    """.trimIndent()

    private fun manifest(
        prompts: String = prompt(),
        resources: String = """[{"resourceKey":"alice-photo","kind":"image","member":"media/alice.jpg"}]""",
        extraTop: String = ""
    ) = """
        {
          "protocol": "listea.package/1",
          "packageKey": "dating-demo",
          "revision": 1,
          "title": "Dating demo",
          "prompts": $prompts,
          "resources": $resources
          $extraTop
        }
    """.trimIndent()

    private fun prompt(
        key: String = "alice",
        kind: String = "profile",
        schema: Int = 1,
        response: String = "choice.single",
        description: String = "prompts/alice.json",
        resources: String = """[{"resourceKey":"alice-photo"}]""",
        assets: String = "[]",
        extra: String = ""
    ) = """
        [{
          "promptKey": "$key",
          "kind": "$kind",
          "schema": $schema,
          "response": "$response",
          "title": "Alice, 28",
          "description": "$description",
          "assets": $assets,
          "resources": $resources
          $extra
        }]
    """.trimIndent()

    /** Join several one-Prompt arrays into one array; the JSON remains readable in the test. */
    private fun prompts(vararg arrays: String): String =
        arrays.joinToString(",", "[", "]") { it.trim().removePrefix("[").removeSuffix("]") }

    private fun snapshot(
        manifest: String? = manifest(),
        descriptions: Map<String, String> = mapOf("prompts/alice.json" to description),
        present: Set<String> = setOf(
            "prompts/alice.json", "media/alice.jpg", ".listea/manifest.json"
        )
    ) = PackageSnapshot(
        rootRelativePath = "dating-demo",
        manifestBytes = manifest?.toByteArray(Charsets.UTF_8),
        presentMembers = present,
        readMember = { path -> descriptions[path]?.toByteArray(Charsets.UTF_8) }
    )

    private fun PackagePlan.codes(scope: DiagnosticScope? = null) =
        diagnostics.filter { scope == null || it.scope == scope }.map { it.code }

    // --- the happy path -------------------------------------------------------------------------

    @Test
    fun `the dating manifest plans one reviewable prompt`() {
        val plan = PackageProtocol.plan(snapshot())
        assertTrue(plan.accepted)
        assertEquals("dating-demo", plan.manifest?.packageKey)
        assertEquals(1, plan.manifest?.revision)
        assertEquals(1, plan.prompts.size)
        val prompt = plan.prompts.single()
        assertEquals("alice", prompt.promptKey)
        assertEquals("profile", prompt.kind)
        assertEquals(1, prompt.schema)
        assertEquals(ResponseContract.SINGLE, prompt.response)
        assertEquals(PromptAvailability.Available, prompt.availability)
        assertEquals(listOf("like", "dislike"), prompt.options.map { it.id })
        assertEquals(1, prompt.resources.size)
        assertTrue(plan.resources.single().present)
        assertEquals(64, plan.fingerprint?.length)
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `the stored payload is canonical`() {
        val prompt = PackageProtocol.plan(snapshot()).prompts.single()
        assertFalse(prompt.payload.contains('\n'))
        assertTrue(prompt.payload.startsWith("""{"options":[""") || prompt.payload.startsWith("""{"options":[{"""))
        assertTrue(prompt.payload.contains(""""age":28"""))
    }

    @Test
    fun `manifest field order and whitespace do not change the fingerprint`() {
        val compact = """{"protocol":"listea.package/1","packageKey":"dating-demo","revision":1,""" +
            """"title":"Dating demo","prompts":${prompt()},"resources":[]}"""
        val first = PackageProtocol.plan(snapshot(manifest = manifest(resources = "[]")))
        val second = PackageProtocol.plan(snapshot(manifest = compact))
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `changing a description changes the fingerprint`() {
        val before = PackageProtocol.plan(snapshot())
        val after = PackageProtocol.plan(
            snapshot(descriptions = mapOf("prompts/alice.json" to description.replace("Alice", "Alicia")))
        )
        assertTrue(before.fingerprint != after.fingerprint)
    }

    // --- whole-Package rejection ----------------------------------------------------------------

    @Test
    fun `a duplicate prompt key rejects the whole package`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompts(prompt(key = "alice"), prompt(key = "alice"))
                )
            )
        )
        assertFalse(plan.accepted)
        assertTrue(plan.prompts.isEmpty())
        assertEquals(listOf("duplicate-key"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a duplicate resource key rejects the whole package`() {
        val resources = "[" +
            """{"resourceKey":"alice-photo","kind":"image","member":"media/alice.jpg"},""" +
            """{"resourceKey":"alice-photo","kind":"image","member":"media/alice.jpg"}""" +
            "]"
        val plan = PackageProtocol.plan(snapshot(manifest = manifest(resources = resources)))
        assertFalse(plan.accepted)
        assertEquals(listOf("duplicate-key"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `an unknown protocol rejects the whole package`() {
        // v2 and v3 are known protocols since 0.8.4 and 0.8.5; only a version beyond the build is
        // rejected.
        val plan = PackageProtocol.plan(
            snapshot(manifest = manifest().replace("listea.package/1", "listea.package/4"))
        )
        assertFalse(plan.accepted)
        assertEquals(listOf("protocol-unknown"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `an unknown top-level field rejects the whole package`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest = manifest(extraTop = ",\"future\":true"))
        )
        assertFalse(plan.accepted)
        assertEquals(listOf("unknown-field"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a duplicate JSON key rejects the whole package`() {
        val text = manifest().replace(
            """"revision": 1,""",
            """"revision": 1, "revision": 1,"""
        )
        val plan = PackageProtocol.plan(snapshot(manifest = text))
        assertFalse(plan.accepted)
        assertEquals(listOf("duplicate-key"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a BOM rejects the whole package`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            manifest().toByteArray(Charsets.UTF_8)
        val plan = PackageProtocol.plan(snapshot().let {
            PackageSnapshot(it.rootRelativePath, bytes, it.presentMembers, it.readMember)
        })
        assertFalse(plan.accepted)
        assertEquals(listOf("bom"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `a missing manifest rejects the package`() {
        val plan = PackageProtocol.plan(snapshot(manifest = null))
        assertFalse(plan.accepted)
        assertEquals(listOf("manifest-missing"), plan.codes(DiagnosticScope.PACKAGE))
    }

    @Test
    fun `keys follow the protocol pattern`() {
        assertTrue(PackageProtocol.isKey("a"))
        assertTrue(PackageProtocol.isKey("dating-demo_1.2"))
        assertTrue(PackageProtocol.isKey("a" + "b".repeat(63)))
        assertFalse(PackageProtocol.isKey(""))
        assertFalse(PackageProtocol.isKey("A"))
        assertFalse(PackageProtocol.isKey("1" + "a".repeat(64)))
        assertFalse(PackageProtocol.isKey("_x"))
        assertFalse(PackageProtocol.isKey(".x"))
        assertFalse(PackageProtocol.isKey("a/b"))
    }

    @Test
    fun `member references cannot climb or leave the root`() {
        assertTrue(PackageProtocol.isSafeMember("prompts/alice.json"))
        assertTrue(PackageProtocol.isSafeMember("media/alice photo.jpg"))
        assertFalse(PackageProtocol.isSafeMember("/etc/passwd"))
        assertFalse(PackageProtocol.isSafeMember("../alice.json"))
        assertFalse(PackageProtocol.isSafeMember("prompts/../alice.json"))
        assertFalse(PackageProtocol.isSafeMember("prompts\\alice.json"))
        assertFalse(PackageProtocol.isSafeMember("https://example.com/alice.json"))
        assertFalse(PackageProtocol.isSafeMember("prompts//alice.json"))
        assertFalse(PackageProtocol.isSafeMember("prompts/./alice.json"))
    }

    // --- Prompt-level degradation ---------------------------------------------------------------

    @Test
    fun `an invalid prompt is diagnosed while a valid sibling is planned`() {
        val two = "[" +
            """{"promptKey":"bad","kind":"profile","schema":1,"response":"choice.single","title":"Bad","description":"../bad.json"},""" +
            """{"promptKey":"alice","kind":"profile","schema":1,"response":"choice.single","title":"Alice","description":"prompts/alice.json","resources":[{"resourceKey":"alice-photo"}]}""" +
            "]"
        val plan = PackageProtocol.plan(snapshot(manifest = manifest(prompts = two)))
        assertTrue(plan.accepted)
        assertEquals(listOf("alice"), plan.prompts.map { it.promptKey })
        assertEquals(listOf("unsafe-member"), plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `an unknown kind is stored with its payload but unavailable`() {
        val plan = PackageProtocol.plan(snapshot(manifest = manifest(prompts = prompt(kind = "quiz"))))
        assertTrue(plan.accepted)
        val stored = plan.prompts.single()
        assertEquals(PromptAvailability.Unavailable("unsupported-viewer"), stored.availability)
        assertTrue(stored.payload.contains("Alice"))
        assertEquals(listOf("unsupported-viewer"), plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `choice multiple is stored but unavailable this round`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest = manifest(prompts = prompt(response = "choice.multiple")))
        )
        val stored = plan.prompts.single()
        assertEquals(PromptAvailability.Unavailable("response-unsupported"), stored.availability)
        assertNotNull(stored.payload)
        assertEquals(listOf("response-unsupported"), plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `duplicate option ids make the prompt unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(
                descriptions = mapOf(
                    "prompts/alice.json" to description.replace("\"dislike\"", "\"like\"")
                )
            )
        )
        val stored = plan.prompts.single()
        assertEquals(PromptAvailability.Unavailable("option-invalid"), stored.availability)
        assertTrue("option-invalid" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a malformed description is diagnosed and no Task is planned`() {
        val plan = PackageProtocol.plan(
            snapshot(descriptions = mapOf("prompts/alice.json" to "{"))
        )
        assertTrue(plan.accepted)
        assertTrue(plan.prompts.isEmpty())
        assertEquals(listOf("description-malformed"), plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a missing description file is diagnosed and no Task is planned`() {
        val plan = PackageProtocol.plan(snapshot(descriptions = emptyMap()))
        assertTrue(plan.accepted)
        assertTrue(plan.prompts.isEmpty())
        assertEquals(listOf("description-missing"), plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a resource use with no declaration makes the prompt unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompt(resources = """[{"resourceKey":"ghost"}]""")
                )
            )
        )
        val stored = plan.prompts.single()
        assertEquals(PromptAvailability.Unavailable("resource-unresolved"), stored.availability)
        assertTrue("resource-unresolved" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a declared resource whose file is missing is diagnosed but does not hide the prompt`() {
        val plan = PackageProtocol.plan(snapshot(present = setOf("prompts/alice.json")))
        assertTrue(plan.accepted)
        assertEquals(PromptAvailability.Available, plan.prompts.single().availability)
        assertFalse(plan.resources.single().present)
        assertEquals(listOf("resource-missing"), plan.codes(DiagnosticScope.RESOURCE))
    }

    @Test
    fun `an unsafe asset is diagnosed per prompt rather than rejecting the package`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompt(assets = """[{"member":"../secret.jpg","name":"Secret"}]""")
                )
            )
        )
        assertTrue(plan.accepted)
        assertTrue(plan.prompts.single().assets.isEmpty())
        assertEquals(PromptAvailability.Unavailable("asset-unsafe"), plan.prompts.single().availability)
        assertTrue("asset-unsafe" in plan.codes(DiagnosticScope.PROMPT))
    }

    @Test
    fun `a missing asset is diagnosed and makes the prompt unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(
                manifest = manifest(
                    prompts = prompt(assets = """[{"member":"files/missing.pdf"}]""")
                )
            )
        )
        assertTrue(plan.prompts.single().assets.single().present == false)
        assertEquals(PromptAvailability.Unavailable("asset-missing"), plan.prompts.single().availability)
    }

    @Test
    fun `freeform with a declared option set is unavailable`() {
        val plan = PackageProtocol.plan(
            snapshot(manifest = manifest(prompts = prompt(response = "freeform")))
        )
        val stored = plan.prompts.single()
        assertEquals(PromptAvailability.Unavailable("option-invalid"), stored.availability)
    }

    @Test
    fun `an unsafe resource member rejects the whole package`() {
        val resources = """[{"resourceKey":"alice-photo","kind":"image","member":"../alice.jpg"}]"""
        val plan = PackageProtocol.plan(snapshot(manifest = manifest(resources = resources)))
        assertFalse(plan.accepted)
        assertTrue("unsafe-member" in plan.codes())
    }

    @Test
    fun `descriptionMembers names only readable prompt descriptions`() {
        val members = PackageProtocol.descriptionMembers(manifest().toByteArray(Charsets.UTF_8))
        assertEquals(listOf("prompts/alice.json"), members)
        assertNull(PackageProtocol.descriptionMembers("not json".toByteArray()))
    }
}
