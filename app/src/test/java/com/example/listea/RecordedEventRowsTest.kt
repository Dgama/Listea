package com.example.listea

import com.example.listea.domain.ViewerEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Info sheet's Recorded section: what a template caught, listed beside the marks it already
 * carries.
 *
 * The wording is pinned here because it is the only place a committed event is explained to a
 * reviewer. Two things matter: a section exists even when nothing has been caught — otherwise a
 * Submit control looks inert for no stated reason — and the rows name the file when the Task has
 * more than one picture, because a point on a photograph is not a fact about the Task at large.
 */
class RecordedEventRowsTest {

    private val photo = "content://media/photo-1"

    @Test
    fun `nothing caught says so rather than hiding the section`() {
        val rows = recordedEventRows(emptyList())

        assertEquals(1, rows.size)
        assertEquals("Recorded", rows.single().label)
        assertEquals("None", rows.single().value)
    }

    @Test
    fun `one point reads as a point, with its colour and where it was taken`() {
        val rows = recordedEventRows(
            listOf(ViewerEvent.ImagePoint(0.25, 0.5, "#1E88E5", photo)),
            mapOf(photo to "blue-block.png")
        )

        assertEquals(2, rows.size)
        assertEquals("Recorded", rows[0].label)
        assertEquals("1 event", rows[0].value)
        assertEquals("Image point", rows[1].label)
        assertEquals("(0.25, 0.50) · #1E88E5\nblue-block.png", rows[1].value)
    }

    @Test
    fun `several points are numbered so a row can be named`() {
        val rows = recordedEventRows(
            listOf(
                ViewerEvent.ImagePoint(0.1, 0.2, "#43A047", photo),
                ViewerEvent.ImagePoint(0.7, 0.8, "#E53935", photo)
            ),
            mapOf(photo to "a.png")
        )

        assertEquals("2 events", rows[0].value)
        assertEquals("Point 1", rows[1].label)
        assertEquals("Point 2", rows[2].label)
        assertEquals("(0.10, 0.20) · #43A047\na.png", rows[1].value)
    }

    @Test
    fun `a file the Task no longer holds leaves the point alone`() {
        val rows = recordedEventRows(
            listOf(ViewerEvent.ImagePoint(0.5, 0.5, "#FFFFFF", "content://media/gone")),
            mapOf(photo to "a.png")
        )

        assertEquals("(0.50, 0.50) · #FFFFFF", rows[1].value)
    }
}
