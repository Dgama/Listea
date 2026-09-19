package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `the media asset control is exactly this definition`() {
        assertEquals(
            "34b23eec98b81a41cf4debbfe1c65b1e1e3f14cd84aa075bcc1580d5cc4538a6",
            Templates.MediaAssetControl.contentHash
        )
    }

    @Test
    fun `adding the asset control did not move the two hashes that already had reviews`() {
        // The two golden hashes above are asserted by name, so this would fail anyway — but it says
        // something those do not: that a third definition was added to the registry without the
        // canonical text of the first two changing. `Templates.all` is iterated by nothing that
        // hashes, so the risk is a shared helper edited for the new arm and quietly reused.
        assertEquals(
            "dda1048958f4fdcee634f36e4ccfd993d910cf5a56883c40f6b4379c7f0c47fd",
            Templates.MediaViewer.contentHash
        )
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
    fun `the three kinds are genuinely different templates`() {
        val viewer = Templates.MediaViewer
        val review = Templates.MediaRapidReview
        val control = Templates.MediaAssetControl

        // One presents content, one judges it, one records what is worth saying about it. Nothing is
        // shared but the idea of a version, which is what keeps media assumptions out of the review
        // model and review assumptions out of a future PDF viewer.
        assertEquals(TemplateKind.ASSET_VIEWER, viewer.definition.kind)
        assertEquals(TemplateKind.TASK_REVIEW, review.definition.kind)
        assertEquals(TemplateKind.ASSET_CONTROL, control.definition.kind)
        assertEquals(3, setOf(viewer.ref, review.ref, control.ref).size)
    }

    @Test
    fun `the asset control claims exactly the kinds a configuration may be attached to`() {
        val control = Templates.MediaAssetControl.definition as TemplateDefinition.AssetControl

        // Images and video share one control in this release, which is what the app already did —
        // and `OTHER` is absent, so a file no control governs cannot be given a configuration for
        // one. That is the same fact `assetViewerAvailable` states from the other side.
        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), control.compatibleKinds)
        assertTrue(control.favourite)
    }

    @Test
    fun `the asset control's hash covers both of its fields`() {
        // The arm added to `canonicalText` for this definition is the only thing standing between
        // two controls that differ in a compatible kind, or in whether they carry `★`, hashing
        // alike — and a review event names one of them forever.
        val base = Templates.MediaAssetControl
        val control = base.definition as TemplateDefinition.AssetControl

        val edits = mapOf(
            "a compatible kind" to base.copy(
                definition = control.copy(compatibleKinds = setOf(AssetKind.IMAGE))
            ),
            "the favourite flag" to base.copy(
                definition = control.copy(favourite = false)
            ),
            "the display name" to base.copy(displayName = "${base.displayName} II"),
            "the version" to base.copy(ref = base.ref.copy(version = 2))
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
        assertEquals(3, Templates.all.map { it.ref }.toSet().size)
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

    // --- the definition and the stored seed are held together here ----------------------------------

    @Test
    fun `the seeded version is the built-in definition's own reference and name`() {
        // Two halves of one template, and the pair that must not drift: the reference is what a
        // record written under this control names forever, and the name is what a person reads in
        // Settings. A seed that minted its own id would make every mark made before this release
        // name a button that no longer exists.
        assertEquals(Templates.MediaAssetControl.ref, Templates.AssetControlSeed.ref)
        assertEquals(Templates.MediaAssetControl.displayName, Templates.AssetControlSeed.name)
    }

    @Test
    fun `the seed carries the favourite exactly when the definition offers it`() {
        // An equivalence, asserted in both directions rather than as "the button is there". A
        // definition that stopped offering `★` while the seed kept offering it is a template drawing
        // a star nothing can record; the other way round is a control that silently lost the one
        // action no user configured, and which nothing would report until somebody looked for it.
        val definition = Templates.MediaAssetControl.definition as TemplateDefinition.AssetControl
        val seeded = Templates.AssetControlSeed.buttons
            .firstOrNull { it.id == Templates.FAVOURITE_BUTTON_ID }

        assertEquals(definition.favourite, seeded != null)
        assertNotNull("the seeded ★", seeded)
        assertEquals(ButtonType.BOOLEAN, seeded!!.type)
        assertEquals(Templates.FAVOURITE_BUTTON_ID, seeded.recordValue)
    }

    @Test
    fun `the seed's scope is exactly the kinds the definition is compatible with`() {
        // The scope is what the resolver will admit a file by, so the claim worth pinning is the one
        // a file is actually matched against rather than the rule's text. `OTHER` is the telling
        // case: it is in the enum, no definition claims it, and a scope built from a wrong field
        // would admit it.
        val definition = Templates.MediaAssetControl.definition as TemplateDefinition.AssetControl
        val scope = Templates.AssetControlSeed.scope

        assertEquals(TemplateScope.ofKinds(definition.compatibleKinds), scope)
        assertTrue(scope.admits(AssetKind.IMAGE))
        assertTrue(scope.admits(AssetKind.VIDEO))
        assertTrue(!scope.admits(AssetKind.OTHER))
    }

    @Test
    fun `the seeded tags are the two slots the app has always offered`() {
        // The ids are the ones `newActionId` reserves and the ones every mark made before this
        // release already names. Read through the seed rather than restated, because Settings now
        // derives its default actions from here — a tag whose id moved would make every existing
        // mark point at a button that is no longer offered.
        assertEquals(
            listOf("custom1", "custom2"),
            Templates.DefaultTagButtons.map { it.id }
        )
        assertEquals(
            listOf(ButtonType.TAG, ButtonType.TAG),
            Templates.DefaultTagButtons.map { it.type }
        )
        assertEquals(
            Templates.DefaultTagButtons,
            Templates.AssetControlSeed.buttons.filter { it.id != Templates.FAVOURITE_BUTTON_ID }
        )
    }
}
