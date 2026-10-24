package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a long press landed on the picture, in the picture's own coordinates.
 *
 * The inverse of a `graphicsLayer`: the image is fitted into the viewport with letterboxing, then
 * scaled about the viewport's centre and translated. Every expectation here is derived from that
 * one sentence rather than from the implementation, so a change to the transform that this function
 * does not follow shows up as a failing point rather than as a slightly different one.
 */
class ImagePointTest {

    private fun at(
        x: Float,
        y: Float,
        width: Float = 100f,
        height: Float = 100f,
        aspect: Float = 1f,
        scale: Float = 1f,
        tx: Float = 0f,
        ty: Float = 0f
    ): ImagePoint? = imagePointAt(x, y, width, height, aspect, scale, tx, ty)

    private fun assertPoint(expectedX: Double, expectedY: Double, actual: ImagePoint?) {
        assertEquals("x", expectedX, actual!!.x, 1e-6)
        assertEquals("y", expectedY, actual.y, 1e-6)
    }

    @Test
    fun `the centre of a square picture in a square viewport is the centre`() {
        assertPoint(0.5, 0.5, at(50f, 50f))
    }

    @Test
    fun `the fitted corners map to the corners and just outside is null`() {
        assertPoint(0.0, 0.0, at(0f, 0f))
        assertPoint(1.0, 1.0, at(100f, 100f))
        assertNull(at(-0.01f, 50f))
        assertNull(at(50f, 100.01f))
    }

    // --- the letterbox --------------------------------------------------------------------------

    @Test
    fun `a square picture in a tall viewport is letterboxed above and below`() {
        // Fitted box 100×100, top edge at y=50.
        assertNull(at(50f, 10f, width = 100f, height = 200f))
        assertPoint(0.5, 0.0, at(50f, 50f, width = 100f, height = 200f))
        assertPoint(0.5, 1.0, at(50f, 150f, width = 100f, height = 200f))
        assertNull(at(50f, 190f, width = 100f, height = 200f))
    }

    @Test
    fun `a wide picture in a square viewport is letterboxed left and right`() {
        // Aspect 2 fits 100×50, top edge at y=25.
        assertNull(at(50f, 10f, aspect = 2f))
        assertPoint(0.5, 0.0, at(50f, 25f, aspect = 2f))
        assertPoint(0.5, 1.0, at(50f, 75f, aspect = 2f))
    }

    @Test
    fun `a tall picture in a wide viewport is letterboxed to the sides`() {
        // Aspect 0.5 fits 50×100, left edge at x=75.
        assertNull(at(60f, 50f, width = 200f, aspect = 0.5f))
        assertPoint(0.0, 0.5, at(75f, 50f, width = 200f, aspect = 0.5f))
        assertPoint(1.0, 0.5, at(125f, 50f, width = 200f, aspect = 0.5f))
    }

    // --- the transform --------------------------------------------------------------------------

    @Test
    fun `a zoomed and translated picture inverts correctly`() {
        // scale 2 about the viewport centre, then translate by (10, −10): the content point (50, 50)
        // sits on screen at (60, 40).
        assertPoint(0.5, 0.5, at(60f, 40f, scale = 2f, tx = 10f, ty = -10f))
        // Screen (50, 50) is content (45, 55), because the translation moves the picture right and
        // up before the scale is undone.
        assertPoint(0.45, 0.55, at(50f, 50f, scale = 2f, tx = 10f, ty = -10f))
    }

    @Test
    fun `a zoomed picture's edge is still an edge`() {
        // Content x=0 is screen x=50−50·2+10 = −40 and content x=1 is 160.
        assertPoint(0.0, 0.5, at(-40f, 50f, scale = 2f, tx = 10f))
        assertPoint(1.0, 0.5, at(160f, 50f, scale = 2f, tx = 10f))
        assertNull(at(-41f, 50f, scale = 2f, tx = 10f))
    }

    // --- what it refuses ------------------------------------------------------------------------

    @Test
    fun `a nonsense viewport or transform is null rather than a division`() {
        assertNull(at(50f, 50f, width = 0f))
        assertNull(at(50f, 50f, height = 0f))
        assertNull(at(50f, 50f, aspect = 0f))
        assertNull(at(50f, 50f, aspect = Float.NaN))
        assertNull(at(50f, 50f, scale = 0f))
        assertNull(at(Float.NaN, 50f))
        assertNull(at(50f, 50f, tx = Float.POSITIVE_INFINITY))
    }
}
