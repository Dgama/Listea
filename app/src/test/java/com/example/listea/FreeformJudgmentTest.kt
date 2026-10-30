package com.example.listea

import com.example.listea.domain.Templates
import com.example.listea.domain.Viewers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which judgments answer with nothing on record, and which viewer id says so.
 *
 * The media part's Done is answered-empty: the files, their marks and their actions are the
 * record. Two places read the judgment's viewer for it — the page, to draw the Done, and the write
 * boundary, to accept the commit — and they must agree on one reference: the **payload capability**
 * (`builtin.viewer.media`), never the asset viewer beside it (`builtin.media.viewer`). A part
 * stamped with the asset viewer drew a Done and then had every press refused, because the page
 * asked one definition and the answer was checked against another.
 */
class FreeformJudgmentTest {

    @Test
    fun `the media part's Done is a freeform judgment`() {
        assertTrue(isFreeformJudgment(Templates.MediaPart.taskReview))
    }

    @Test
    fun `the media part names the payload capability the write boundary checks`() {
        assertEquals(Viewers.Media.ref, Templates.MediaPart.taskReview?.viewer)
    }

    @Test
    fun `a judgment that catches a point is answered, not freeform`() {
        assertFalse(isFreeformJudgment(Templates.ImageSampleReview.taskReview))
    }

    @Test
    fun `a form page is answered on its own page, not by a Done`() {
        assertFalse(isFreeformJudgment(Templates.StructuredForm.taskReview))
    }

    @Test
    fun `the mixed container draws no page, so it is never a page's freeform judgment`() {
        assertFalse(isFreeformJudgment(Templates.MixedStructured.taskReview))
    }

    @Test
    fun `a part whose judgment cannot be resolved is not freeform, it is blank`() {
        assertFalse(isFreeformJudgment(null))
    }
}
