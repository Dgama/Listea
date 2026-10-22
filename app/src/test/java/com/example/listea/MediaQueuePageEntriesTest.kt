package com.example.listea

import com.example.listea.data.ReviewAsset
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a Task's page list says, when the counter has no queue behind its outer number.
 *
 * The order is the Task's own, because the inner arrows step the same list and a jump must land
 * where the arrows would have walked to. The last row is the add-on — a page of the Task, but not a
 * file the source holds, so its label has to say which of the two it is before anything else.
 */
class MediaQueuePageEntriesTest {

    @Test
    fun `the files come first, in the Task's own order`() {
        val entries = reviewPageEntries(
            assets = listOf(asset("photo-1.jpg"), asset("photo-2.jpg"), asset("clip.mp4")),
            addOn = null
        )

        assertEquals(
            listOf("photo-1.jpg", "photo-2.jpg", "clip.mp4"),
            entries.map { it.label }
        )
        assertEquals(
            "a file's row has nothing but its name to say",
            listOf(null, null, null),
            entries.map { it.detail }
        )
    }

    @Test
    fun `the add-on page follows the files and its label names it an add-on`() {
        val entries = reviewPageEntries(
            assets = listOf(asset("photo-1.jpg")),
            addOn = addOn("Field notes")
        )

        assertEquals(
            listOf("photo-1.jpg", "add-on Field notes"),
            entries.map { it.label }
        )
    }

    @Test
    fun `a Task with no add-on page lists only its files`() {
        val entries = reviewPageEntries(
            assets = listOf(asset("photo-1.jpg")),
            addOn = null
        )

        assertEquals(listOf("photo-1.jpg"), entries.map { it.label })
    }

    private fun asset(name: String) = ReviewAsset(
        sourceUri = "content://fixture/$name",
        name = name,
        sourceMissing = false
    )

    private fun addOn(name: String) = StoredAddOnVersion(
        ref = AddOnVersionRef(AddOnId("add-on-fixture"), version = 1),
        name = name,
        questions = emptyList()
    )
}
