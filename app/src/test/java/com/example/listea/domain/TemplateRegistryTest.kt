package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped templates, and the mechanism that keeps them from changing under reviews that
 * already happened.
 *
 * The two golden hashes at the top are the point of the file. §6 asks for immutability to be
 * *enforced by a test* rather than intended, and this is how: a review event names
 * `builtin.media.rapid-review@1` forever, so if version 1's definition can be edited in place, a
 * completed review retroactively comes to mean something else. The digest makes that edit fail
 * here, and the only way past it is to add a version — which is the right answer and now the
 * cheapest one.
 *
 * Everything else is the registry being a registry: two kinds that are genuinely different, a
 * review template that composes a viewer rather than copying it, and a lookup that survives
 * meeting a reference it has never heard of.
 */
class TemplateRegistryTest {

    @Test
    fun `the media viewer is exactly this definition`() {
        assertEquals(
            "dda1048958f4fdcee634f36e4ccfd993d910cf5a56883c40f6b4379c7f0c47fd",
            Templates.MediaViewer.contentHash
        )
    }

    @Test
    fun `the rapid media review is exactly this definition`() {
        assertEquals(
            "96c4349da11514f3f6fe4a389511593673d8b96020c4b7cdadd20c04f52e5ce2",
            Templates.MediaRapidReview.contentHash
        )
    }

    @Test
    fun `the hash covers every part of a definition, so no edit slips through`() {
        val base = Templates.MediaRapidReview
        val review = base.definition as TemplateDefinition.TaskReview

        val edits = mapOf(
            "the version" to base.copy(ref = base.ref.copy(version = 2)),
            "the display name" to base.copy(displayName = "${base.displayName} II"),
            "the kind" to base.copy(
                definition = TemplateDefinition.AssetViewer(setOf(AssetKind.IMAGE))
            ),
            "an action" to base.copy(
                definition = review.copy(actions = review.actions - TaskAction.NextTask)
            ),
            "the composed viewer" to base.copy(
                definition = review.copy(
                    viewerTemplate = TemplateVersionRef(TemplateId("builtin.media.viewer"), 2)
                )
            )
        )

        edits.forEach { (part, edited) ->
            assertNotEquals(
                "editing $part left the hash alone, so a review could be reinterpreted",
                base.contentHash,
                edited.contentHash
            )
        }
    }

    @Test
    fun `the order a definition happens to list things in is not part of what it says`() {
        val review = Templates.MediaRapidReview.definition as TemplateDefinition.TaskReview
        val otherOrder = review.copy(
            actions = linkedSetOf(
                TaskAction.CompleteTask,
                TaskAction.PreviousTask,
                TaskAction.NextTask
            )
        )

        // Same definition, different iteration order. Canonicalising before hashing is what stops a
        // reshuffle of a source file from reading as a change to what a review meant.
        assertNotEquals(review.actions.toList(), otherOrder.actions.toList())
        assertEquals(
            Templates.MediaRapidReview.contentHash,
            Templates.MediaRapidReview.copy(definition = otherOrder).contentHash
        )
    }

    @Test
    fun `the two kinds are genuinely different templates`() {
        val viewer = Templates.MediaViewer
        val review = Templates.MediaRapidReview

        // One presents content, the other judges it. Nothing is shared but the idea of a version,
        // which is what keeps media assumptions out of the review model and review assumptions out
        // of a future PDF viewer.
        assertEquals(TemplateKind.ASSET_VIEWER, viewer.definition.kind)
        assertEquals(TemplateKind.TASK_REVIEW, review.definition.kind)
        assertNotEquals(viewer.ref, review.ref)
    }

    @Test
    fun `the review template composes the viewer by reference, not by copy`() {
        val review = Templates.MediaRapidReview.definition as TemplateDefinition.TaskReview

        // A reference, so a later viewer version does not silently change what this review template
        // was written against — and so the two can move independently.
        assertEquals(Templates.MediaViewer.ref, review.viewerTemplate)
    }

    @Test
    fun `the review template offers both a way to judge and a way to move on`() {
        val review = Templates.MediaRapidReview.definition as TemplateDefinition.TaskReview

        assertTrue(review.actions.contains(TaskAction.CompleteTask))
        assertTrue(review.actions.any { !it.isSubmission })

        // All three, and no more: a template is a vocabulary, and one offering an action nothing
        // can invoke is a definition that has to be honoured forever for no reason.
        assertEquals(
            listOf("complete", "next", "previous"),
            review.actions.map { it.key }.sorted()
        )
    }

    @Test
    fun `the viewer claims only what this build can actually render`() {
        val viewer = Templates.MediaViewer.definition as TemplateDefinition.AssetViewer

        // Images and video are what MediaPreview draws. Claiming a kind nothing can present would
        // offer a viewer that renders nothing; claiming fewer would refuse content that works.
        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), viewer.compatibleKinds)
    }

    @Test
    fun `every template resolves by the reference that names it`() {
        Templates.all.forEach { version ->
            assertEquals(version, Templates.byRef(version.ref))
        }
        assertEquals(2, Templates.all.map { it.ref }.toSet().size)
    }

    @Test
    fun `a reference from another build resolves to nothing rather than throwing`() {
        // A `lists` row written by a later release is a thing that can exist. Failing to read the
        // List because this build has never heard of its template would turn a version skew into
        // data loss.
        // A version of a template this build knows, that it does not have.
        assertNull(Templates.byRef(TemplateVersionRef(TemplateId("builtin.media.rapid-review"), 9)))

        // A lineage altogether: either defined after this build, or removed from a later one.
        assertNull(Templates.byRef(TemplateVersionRef(TemplateId("builtin.identity.review"), 1)))
    }

    @Test
    fun `the review template is the one the schema defaults a List to`() {
        // The default in the v13 `lists` table is a literal string. If the registry ever moved off
        // it, every List that never chose a template would point at nothing.
        assertEquals("builtin.media.rapid-review", Templates.MediaRapidReview.ref.templateId.value)
        assertEquals(1, Templates.MediaRapidReview.ref.version)
        assertEquals("builtin.media.viewer", Templates.MediaViewer.ref.templateId.value)
    }
}
