package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored half of a Task Review: what it catches, when it commits, and what a commit does.
 *
 * Pinned character for character for the reason the button encoding is: this text is written to a
 * column and read by a release that will not have this code to consult. The reader is fail-closed —
 * a definition whose meaning had to be guessed is a review somebody's judgment would be recorded
 * under without anybody having chosen it.
 */
class TaskReviewDefinitionTest {

    private val viewer = Templates.MediaViewer.ref

    private fun definition(
        accepts: Set<ViewerEventType> = setOf(ViewerEventType.IMAGE_POINT),
        commit: String = "submit",
        completes: Boolean = true,
        advance: Boolean = true
    ) = TaskReviewDefinition(
        viewer = viewer,
        accepts = accepts,
        commit = CommitPolicy.Explicit(commit),
        completesTask = completes,
        advanceAfterCommit = advance
    )

    private fun decode(text: String, version: Int = TASK_REVIEW_DEFINITION_VERSION) =
        decodeTaskReviewDefinition(text, version)

    // --- what ships -----------------------------------------------------------------------------

    @Test
    fun `the rapid review seed round-trips`() {
        val seed = Templates.MediaRapidReviewStored.taskReview!!
        assertEquals(seed, decode(encodeTaskReviewDefinition(seed)))
        // Headless: it catches nothing and commits from the completion control.
        assertEquals(emptySet<ViewerEventType>(), seed.accepts)
        assertEquals(
            CommitPolicy.Explicit(TASK_REVIEW_COMPLETE_CONTROL),
            seed.commit
        )
    }

    @Test
    fun `the sample review seed round-trips`() {
        val seed = Templates.ImageSampleReview.taskReview!!
        assertEquals(seed, decode(encodeTaskReviewDefinition(seed)))
        assertEquals(setOf(ViewerEventType.IMAGE_POINT), seed.accepts)
        assertEquals(
            CommitPolicy.Explicit(Templates.IMAGE_SAMPLE_SUBMIT_ID),
            seed.commit
        )
    }

    @Test
    fun `the encoded form is exactly this text`() {
        // The canonical order is fixed and the accepted keys are sorted, so two definitions a
        // reader would call the same cannot be told apart by a diff.
        assertEquals(
            listOf(
                "v=1",
                "viewer=builtin.media.viewer@1",
                "accepts=image.point",
                "commit=explicit:submit",
                "completes=true",
                "advance=true"
            ).joinToString("\n"),
            encodeTaskReviewDefinition(definition())
        )
    }

    @Test
    fun `an empty accepts line is a review that catches nothing`() {
        assertEquals(
            definition(accepts = emptySet()),
            decode(encodeTaskReviewDefinition(definition(accepts = emptySet())))
        )
        assertEquals(emptySet<ViewerEventType>(), decode(encodeTaskReviewDefinition(definition(accepts = emptySet())))?.accepts)
    }

    // --- what it refuses ------------------------------------------------------------------------

    @Test
    fun `a definition version this build does not know is refused`() {
        val text = encodeTaskReviewDefinition(definition())
        assertNull(decode(text, TASK_REVIEW_DEFINITION_VERSION + 1))
        // And the same answer from inside the text, for a caller that passed the row's version by
        // mistake rather than reading the envelope's.
        assertNull(decode(text.replaceFirst("v=1", "v=2")))
    }

    @Test
    fun `an event type this build does not know is refused`() {
        assertNull(decode(encodeTaskReviewDefinition(definition()).replace("image.point", "choice")))
    }

    @Test
    fun `a commit mode this build does not know is refused`() {
        // A checkbox policy is the shape a later build might add; this one only knows `explicit`.
        assertNull(
            decode(
                encodeTaskReviewDefinition(definition())
                    .replace("commit=explicit:submit", "commit=checkbox:complete")
            )
        )
    }

    @Test
    fun `a blank control id is refused`() {
        assertNull(
            decode(encodeTaskReviewDefinition(definition()).replace("commit=explicit:submit", "commit=explicit:"))
        )
    }

    @Test
    fun `every required line is required`() {
        val lines = encodeTaskReviewDefinition(definition()).split('\n')
        for (missing in listOf("viewer=", "accepts=", "commit=", "completes=", "advance=")) {
            val text = lines.filterNot { it.startsWith(missing) }.joinToString("\n")
            assertNull("without $missing", decode(text))
        }
    }

    @Test
    fun `a duplicate line is refused`() {
        val text = encodeTaskReviewDefinition(definition())
        assertNull(decode("$text\ncommit=explicit:submit"))
    }

    @Test
    fun `an unknown line is refused`() {
        assertNull(decode(encodeTaskReviewDefinition(definition()) + "\nnote=anything"))
    }

    @Test
    fun `an empty payload is unreadable, not an empty definition`() {
        assertNull(decode(""))
    }
}
