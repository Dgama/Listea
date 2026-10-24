package com.example.listea

import com.example.listea.data.StoredTemplateKind
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.CommitPolicy
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TASK_REVIEW_COMPLETE_CONTROL
import com.example.listea.domain.TASK_REVIEW_DEFINITION_VERSION
import com.example.listea.domain.TaskReviewDefinition
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEventType
import com.example.listea.domain.encodeTaskReviewDefinition
import com.example.listea.domain.encodeTemplateScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split between the two things a template can be, as the pickers ask for them.
 *
 * One table holds both kinds, and the resolver is where they part: *what may govern this file* and
 * *how are these Tasks judged* are different questions, and a list answering both would offer a
 * review in the asset-control step. The failing-closed rule reaches here too — a review whose
 * definition this build cannot read is absent rather than offered with guessed policies.
 */
class TemplateResolverReviewTest {

    private val definition = TaskReviewDefinition(
        viewer = Templates.MediaViewer.ref,
        accepts = setOf(ViewerEventType.IMAGE_POINT),
        commit = CommitPolicy.ConfirmControl(TASK_REVIEW_COMPLETE_CONTROL),
        revisable = true,
        completesTask = true,
        advanceAfterCommit = true
    )

    private fun lineage(id: String, name: String = id) =
        TemplateEntity(id = id, name = name, createdAt = 0)

    private fun control(id: String, version: Int = 1) = TemplateVersionEntity(
        templateId = id,
        version = version,
        scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(com.example.listea.domain.AssetKind.IMAGE))),
        buttons = "",
        buttonsVersion = 1,
        createdAt = 0
    )

    private fun review(
        id: String,
        version: Int = 1,
        definitionText: String = encodeTaskReviewDefinition(definition),
        definitionVersion: Int = TASK_REVIEW_DEFINITION_VERSION
    ) = TemplateVersionEntity(
        templateId = id,
        version = version,
        scope = encodeTemplateScope(TemplateScope(emptyList())),
        buttons = "",
        buttonsVersion = 1,
        createdAt = 0,
        kind = StoredTemplateKind.TASK_REVIEW,
        definition = definitionText,
        definitionVersion = definitionVersion
    )

    private fun resolver(vararg rows: Pair<TemplateEntity, List<TemplateVersionEntity>>) =
        TemplateResolver.of(
            templates = rows.map { it.first },
            versions = rows.flatMap { it.second }
        )

    @Test
    fun `each kind is offered only where it belongs`() {
        val resolver = resolver(
            lineage("control", "Media control") to listOf(control("control")),
            lineage("review", "Sample a point") to listOf(review("review"))
        )

        assertEquals(listOf("Media control"), resolver.attachable.map { it.name })
        assertEquals(listOf("Sample a point"), resolver.attachableReviews.map { it.name })
    }

    @Test
    fun `the review picker offers the highest readable version of a lineage`() {
        val resolver = resolver(
            lineage("review", "Review") to listOf(
                review("review", version = 1),
                review("review", version = 2),
                // Written by a later build: skipped, and it does not hide version 2 beneath it.
                review("review", version = 3, definitionVersion = TASK_REVIEW_DEFINITION_VERSION + 1)
            )
        )

        assertEquals(2, resolver.attachableReviews.single().ref.version)
    }

    @Test
    fun `a review whose definition this build cannot read is not offered`() {
        val resolver = resolver(
            lineage("review", "Review") to listOf(
                review("review", definitionText = "nonsense")
            )
        )

        assertTrue(resolver.attachableReviews.isEmpty())
        assertNull(resolver.reviewForRef(TemplateVersionRef(TemplateId("review"), 1)))
        assertTrue(resolver.attachable.isEmpty())
    }

    @Test
    fun `reviewFor reads a stored review by its encoded attachment`() {
        val resolver = resolver(lineage("review") to listOf(review("review", version = 2)))
        val expected = resolver.attachableReviews.single()

        assertEquals(expected, resolver.reviewFor("review\t2"))
    }

    @Test
    fun `a reference to an asset control is not a review`() {
        val resolver = resolver(lineage("control") to listOf(control("control")))

        assertNull(resolver.reviewFor("control\t1"))
        assertNull(resolver.reviewForRef(TemplateVersionRef(TemplateId("control"), 1)))
    }

    @Test
    fun `no attachment is no review`() {
        assertNull(TemplateResolver.Empty.reviewFor(""))
        assertTrue(TemplateResolver.Empty.attachableReviews.isEmpty())
    }

    @Test
    fun `the shipped reviews resolve even when their seed rows are absent`() {
        // The built-in is a definition in code as well as a row, and the review surface falls back
        // to that copy. Reading it here too keeps the built-in nameable in a database the seed
        // callback has not visited — which is what makes a write path independent of its timing.
        for (seed in Templates.StoredTaskReviews) {
            assertEquals(seed, TemplateResolver.Empty.reviewForRef(seed.ref))
            assertEquals(
                seed,
                TemplateResolver.Empty.reviewFor(encodeTemplateAttachments(listOf(seed.ref)))
            )
        }
    }

    @Test
    fun `a user's review gets no such fallback`() {
        val ref = TemplateVersionRef(TemplateId("someone-elses"), 1)
        assertNull(TemplateResolver.Empty.reviewForRef(ref))
    }
}
