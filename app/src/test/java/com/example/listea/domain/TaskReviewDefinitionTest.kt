package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored half of a Task Review: what it catches, when it commits, whether an answer may be
 * revised, and what a commit does.
 *
 * Pinned character for character for the reason the button encoding is: this text is written to a
 * column and read by a release that will not have this code to consult. The reader is fail-closed —
 * a definition whose meaning had to be guessed is a review somebody's judgment would be recorded
 * under without anybody having chosen it.
 *
 * Version 1 is still read. It wrote `explicit:<control>` and no revisability line; both map to what
 * those rows actually did, which is [CommitPolicy.ConfirmControl] and `revisable = true`.
 */
class TaskReviewDefinitionTest {

    private val viewer = Templates.MediaViewer.ref

    private fun definition(
        accepts: Set<ViewerEventType> = setOf(ViewerEventType.IMAGE_POINT),
        commit: CommitPolicy = CommitPolicy.ConfirmControl("submit"),
        revisable: Boolean = true,
        completes: Boolean = true,
        advance: Boolean = true
    ) = TaskReviewDefinition(
        viewer = viewer,
        accepts = accepts,
        commit = commit,
        revisable = revisable,
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
        assertEquals(CommitPolicy.ConfirmControl(TASK_REVIEW_COMPLETE_CONTROL), seed.commit)
        assertEquals(true, seed.revisable)
    }

    @Test
    fun `the sample review seed round-trips`() {
        val seed = Templates.ImageSampleReview.taskReview!!
        assertEquals(seed, decode(encodeTaskReviewDefinition(seed)))
        assertEquals(setOf(ViewerEventType.IMAGE_POINT), seed.accepts)
        assertEquals(CommitPolicy.ConfirmControl(Templates.IMAGE_SAMPLE_SUBMIT_ID), seed.commit)
    }

    @Test
    fun `the encoded form is exactly this text`() {
        // The canonical order is fixed and the accepted keys are sorted, so two definitions a
        // reader would call the same cannot be told apart by a diff.
        assertEquals(
            listOf(
                "v=2",
                "viewer=builtin.media.viewer@1",
                "accepts=image.point",
                "commit=confirm:control:submit",
                "revisable=true",
                "completes=true",
                "advance=true"
            ).joinToString("\n"),
            encodeTaskReviewDefinition(definition())
        )
    }

    @Test
    fun `every commit form round-trips`() {
        val immediate = definition(commit = CommitPolicy.Immediate(ViewerEventType.CHOICE_SELECT))
        val confirmIntent = definition(commit = CommitPolicy.ConfirmIntent(ViewerEventType.IMAGE_POINT))
        assertEquals(immediate, decode(encodeTaskReviewDefinition(immediate)))
        assertEquals(confirmIntent, decode(encodeTaskReviewDefinition(confirmIntent)))
    }

    @Test
    fun `an irreversible definition round-trips`() {
        val final = definition(revisable = false)
        assertEquals(false, decode(encodeTaskReviewDefinition(final))?.revisable)
    }

    @Test
    fun `an empty accepts line is a review that catches nothing`() {
        assertEquals(
            definition(accepts = emptySet()),
            decode(encodeTaskReviewDefinition(definition(accepts = emptySet())))
        )
    }

    // --- version 1 is still readable ------------------------------------------------------------

    @Test
    fun `a version 1 definition reads as a confirm control that could be revised`() {
        val old = listOf(
            "v=1",
            "viewer=builtin.media.viewer@1",
            "accepts=image.point",
            "commit=explicit:submit",
            "completes=true",
            "advance=true"
        ).joinToString("\n")
        val decoded = decode(old, version = 1)
        assertEquals(CommitPolicy.ConfirmControl("submit"), decoded?.commit)
        assertEquals(true, decoded?.revisable)
        assertEquals(setOf(ViewerEventType.IMAGE_POINT), decoded?.accepts)
    }

    @Test
    fun `version 1 does not accept the version 2 commit spelling`() {
        val old = listOf(
            "v=1",
            "viewer=builtin.media.viewer@1",
            "accepts=",
            "commit=confirm:control:submit",
            "completes=true",
            "advance=true"
        ).joinToString("\n")
        assertNull(decode(old, version = 1))
    }

    @Test
    fun `version 1 does not accept a revisability line it never wrote`() {
        val old = listOf(
            "v=1",
            "viewer=builtin.media.viewer@1",
            "accepts=",
            "commit=explicit:submit",
            "revisable=true",
            "completes=true",
            "advance=true"
        ).joinToString("\n")
        assertNull(decode(old, version = 1))
    }

    // --- what it refuses ------------------------------------------------------------------------

    @Test
    fun `a definition version this build does not know is refused`() {
        val text = encodeTaskReviewDefinition(definition())
        assertNull(decode(text, TASK_REVIEW_DEFINITION_VERSION + 1))
        // And the same answer from inside the text, for a caller that passed the row's version by
        // mistake rather than reading the envelope's.
        assertNull(decode(text.replaceFirst("v=2", "v=3")))
    }

    @Test
    fun `an event type this build does not know is refused`() {
        assertNull(decode(encodeTaskReviewDefinition(definition()).replace("image.point", "choice")))
    }

    @Test
    fun `a commit mode this build does not know is refused`() {
        assertNull(
            decode(
                encodeTaskReviewDefinition(definition())
                    .replace("commit=confirm:control:submit", "commit=checkbox:complete")
            )
        )
    }

    @Test
    fun `an immediate commit names a known intent`() {
        assertNull(
            decode(
                encodeTaskReviewDefinition(definition())
                    .replace("commit=confirm:control:submit", "commit=immediate:not-an-intent")
            )
        )
    }

    @Test
    fun `a blank control id is refused`() {
        assertNull(
            decode(
                encodeTaskReviewDefinition(definition())
                    .replace("commit=confirm:control:submit", "commit=confirm:control:")
            )
        )
    }

    @Test
    fun `every required line is required`() {
        val lines = encodeTaskReviewDefinition(definition()).split('\n')
        for (missing in listOf(
            "viewer=", "accepts=", "commit=", "revisable=", "completes=", "advance="
        )) {
            val text = lines.filterNot { it.startsWith(missing) }.joinToString("\n")
            assertNull("without $missing", decode(text))
        }
    }

    @Test
    fun `a duplicate line is refused`() {
        val text = encodeTaskReviewDefinition(definition())
        assertNull(decode("$text\ncommit=confirm:control:submit"))
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
