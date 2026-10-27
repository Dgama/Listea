package com.example.listea

import com.example.listea.domain.Templates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a Task Review row says it catches.
 *
 * Two built-ins catch no viewer events for opposite reasons: the media review records the Task's
 * own file decisions, and the form review records a structured answer the Form Viewer produced.
 * The line has to read the Viewer, or the form template is described as a pen for files it never
 * touches.
 */
class TemplateLabelsTest {

    @Test
    fun `the form review names its answer rather than file decisions`() {
        val form = requireNotNull(Templates.StructuredForm.taskReview)

        assertEquals(
            "Headless template · records the form's answer",
            reviewCatchLine(form)
        )
    }

    @Test
    fun `the media review still records the Task's file decisions`() {
        val media = requireNotNull(Templates.MediaRapidReviewStored.taskReview)

        assertEquals(
            "Headless template · records the Task's file decisions",
            reviewCatchLine(media)
        )
    }

    @Test
    fun `a review that catches an event names it`() {
        val choice = requireNotNull(Templates.StructuredSingleChoice.taskReview)

        assertEquals("Headless template · catches a single choice", reviewCatchLine(choice))
    }
}
