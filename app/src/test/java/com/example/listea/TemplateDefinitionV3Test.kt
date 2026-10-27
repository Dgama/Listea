package com.example.listea

import com.example.listea.domain.CommitPolicy
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.TASK_REVIEW_DEFINITION_VERSION
import com.example.listea.domain.TaskReviewDefinition
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.ViewerEventType
import com.example.listea.domain.decodeTaskReviewDefinition
import com.example.listea.domain.encodeTaskReviewDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The completion policy's place in a stored Task Review definition.
 *
 * Version 3 adds one line to a format that is already on people's devices. The two claims worth
 * pinning are therefore symmetric:
 *
 * - a v3 definition carries its [CompletionPolicy] through encode and decode unchanged;
 * - a v2 or v1 definition, which has no such line, still reads as
 *   [CompletionPolicy.SinglePart] — the rule those definitions meant, because a Task *was* one
 *   decision when they were written.
 *
 * Fail-closed applies to the new line as it does to every other: a v3 payload with no policy, or
 * with a word this build has never written, is not partly read. A review that guessed at a
 * completion rule would complete Tasks under a policy nobody chose.
 */
class TemplateDefinitionV3Test {

    @Test
    fun `the encoding names version three`() {
        assertEquals(3, TASK_REVIEW_DEFINITION_VERSION)
        assertEquals("v=3", encodeTaskReviewDefinition(definition()).lineSequence().first())
    }

    @Test
    fun `every policy survives a v3 round trip`() {
        for (policy in CompletionPolicy.entries) {
            val definition = definition(completion = policy)
            val text = encodeTaskReviewDefinition(definition)

            assertEquals(
                "policy $policy",
                definition,
                decodeTaskReviewDefinition(text, TASK_REVIEW_DEFINITION_VERSION)
            )
        }
    }

    @Test
    fun `the policy is the last line and is spelled as its key`() {
        // Fixed order is what makes two encodings of one definition diffable; a policy that moved
        // when an unrelated field was added would make every stored payload look changed.
        val text = encodeTaskReviewDefinition(definition(completion = CompletionPolicy.AllRequiredParts))

        assertEquals(
            listOf(
                "v=3",
                "viewer=builtin.media.viewer@1",
                "accepts=image.point",
                "commit=confirm:control:submit",
                "revisable=false",
                "completes=true",
                "advance=false",
                "completion=all-required"
            ),
            text.split('\n')
        )
    }

    @Test
    fun `a v2 definition reads as one part`() {
        val text = encodeTaskReviewDefinition(definition(completion = CompletionPolicy.AnyPart))
            .replace("v=3", "v=2")
            .lineSequence()
            .filterNot { it.startsWith("completion=") }
            .joinToString("\n")

        val decoded = decodeTaskReviewDefinition(text, version = 2)

        assertEquals(definition(completion = CompletionPolicy.SinglePart), decoded)
    }

    @Test
    fun `a v1 definition reads as one part and stays revisable`() {
        val text = listOf(
            "v=1",
            "viewer=builtin.media.viewer@1",
            "accepts=image.point",
            "commit=explicit:submit",
            "completes=true",
            "advance=true"
        ).joinToString("\n")

        val decoded = decodeTaskReviewDefinition(text, version = 1)

        assertEquals(CompletionPolicy.SinglePart, decoded?.completion)
        assertEquals(CommitPolicy.ConfirmControl("submit"), decoded?.commit)
        // v1 had no revisability line, and the behaviour was "a second commit is accepted".
        assertEquals(true, decoded?.revisable)
    }

    @Test
    fun `a v3 definition with no policy line is unreadable`() {
        val text = encodeTaskReviewDefinition(definition())
            .lineSequence()
            .filterNot { it.startsWith("completion=") }
            .joinToString("\n")

        assertNull(decodeTaskReviewDefinition(text, TASK_REVIEW_DEFINITION_VERSION))
    }

    @Test
    fun `a policy this build has never written is unreadable`() {
        val text = encodeTaskReviewDefinition(definition())
            .replace("completion=single-part", "completion=triage")

        assertNull(decodeTaskReviewDefinition(text, TASK_REVIEW_DEFINITION_VERSION))
    }

    @Test
    fun `a policy line in a v2 payload is an unknown field and is refused`() {
        // The version decides which lines are legal: a v2 payload that carries a v3 line has not
        // been written by this build, and reading part of it would be inventing the rest.
        val text = encodeTaskReviewDefinition(definition()).replace("v=3", "v=2")

        assertNull(decodeTaskReviewDefinition(text, version = 2))
    }

    @Test
    fun `a version this build does not know is unreadable`() {
        val text = encodeTaskReviewDefinition(definition())

        assertNull(decodeTaskReviewDefinition(text, version = 4))
    }

    @Test
    fun `the version line must agree with the version asked for`() {
        val text = encodeTaskReviewDefinition(definition())

        assertNull(decodeTaskReviewDefinition(text.replace("v=3", "v=2"), version = 3))
    }

    private fun definition(
        completion: CompletionPolicy = CompletionPolicy.SinglePart
    ) = TaskReviewDefinition(
        viewer = TemplateVersionRef(TemplateId("builtin.media.viewer"), 1),
        accepts = setOf(ViewerEventType.IMAGE_POINT),
        commit = CommitPolicy.ConfirmControl("submit"),
        revisable = false,
        completesTask = true,
        advanceAfterCommit = false,
        completion = completion
    )
}
