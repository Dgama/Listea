package com.example.listea

import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewPart
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.PartApplicability
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a Task's page list says, when the counter has no queue behind its outer number.
 *
 * The order is the Task's own, because the inner arrows step the same list and a jump must land
 * where the arrows would have walked to. Each part contributes its Content page and then its files;
 * a Task of one part names that page "Question" and says nothing else. The last row is the add-on —
 * a page of the Task, but not a file the source holds, so its label has to say which of the two it
 * is before anything else.
 */
class MediaQueuePageEntriesTest {

    @Test
    fun `the files come first, in the Task's own order`() {
        val entries = reviewPageEntries(
            parts = listOf(
                part(listOf(asset("photo-1.jpg"), asset("photo-2.jpg"), asset("clip.mp4")))
            ),
            addOn = null
        )

        assertEquals(
            listOf("photo-1.jpg", "photo-2.jpg", "clip.mp4"),
            entries.map { it.label }
        )
        assertEquals(
            "a file's row in a one-part Task has nothing but its name to say",
            listOf(null, null, null),
            entries.map { it.detail }
        )
    }

    @Test
    fun `the add-on page follows the files and its label names it an add-on`() {
        val entries = reviewPageEntries(
            parts = listOf(part(listOf(asset("photo-1.jpg")))),
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
            parts = listOf(part(listOf(asset("photo-1.jpg")))),
            addOn = null
        )

        assertEquals(listOf("photo-1.jpg"), entries.map { it.label })
    }

    @Test
    fun `a Task of several parts numbers its questions and marks the part on every row`() {
        // Repeated "Question" rows would be rows a reader cannot tell apart, so each Content page
        // is numbered against the others. The part boundary is drawn on the files too, so the run
        // of pictures under the second question reads as that question's material.
        val entries = reviewPageEntries(
            parts = listOf(
                part(
                    assets = listOf(asset("photo-1.jpg")),
                    key = "p1",
                    position = 0,
                    payloadKind = "profile"
                ),
                part(
                    assets = listOf(asset("photo-2.jpg")),
                    key = "p2",
                    position = 1,
                    payloadKind = "vocabulary"
                )
            ),
            addOn = null
        )

        assertEquals(
            listOf("Question 1 of 2", "photo-1.jpg", "Question 2 of 2", "photo-2.jpg"),
            entries.map { it.label }
        )
        assertEquals(
            listOf("Part 1 of 2", "Part 1 of 2", "Part 2 of 2", "Part 2 of 2"),
            entries.map { it.detail }
        )
    }

    @Test
    fun `a page waiting on an earlier answer keeps its place and says so beside its part`() {
        // A masked page is still a page the walk passes: the list is a guide, not a filter. Its
        // row says why, and the part boundary it already carried stays in front of that.
        val entries = reviewPageEntries(
            parts = listOf(
                part(listOf(asset("photo-1.jpg")), key = "p1", position = 0, payloadKind = "profile"),
                part(
                    listOf(asset("photo-2.jpg")),
                    key = "p2",
                    position = 1,
                    payloadKind = "profile",
                    applicability = PartApplicability.Pending
                )
            ),
            addOn = null
        )

        assertEquals(
            listOf("Question 1 of 2", "photo-1.jpg", "Question 2 of 2", "photo-2.jpg"),
            entries.map { it.label }
        )
        assertEquals(
            listOf(
                "Part 1 of 2",
                "Part 1 of 2",
                "Part 2 of 2 · Waiting for an earlier answer",
                "Part 2 of 2 · Waiting for an earlier answer"
            ),
            entries.map { it.detail }
        )
    }

    @Test
    fun `a page the branch does not need says so and the one-part Task gains no boundary`() {
        val entries = reviewPageEntries(
            parts = listOf(
                part(
                    listOf(asset("photo-1.jpg")),
                    key = "menu",
                    position = 0,
                    payloadKind = "profile",
                    applicability = PartApplicability.Inactive
                )
            ),
            addOn = null
        )

        assertEquals(listOf("Question", "photo-1.jpg"), entries.map { it.label })
        assertEquals(listOf("Not needed", "Not needed"), entries.map { it.detail })
    }

    private fun part(
        assets: List<ReviewAsset>,
        key: String = "p1",
        position: Int = 0,
        payloadKind: String? = null,
        applicability: PartApplicability = PartApplicability.Active
    ) = ReviewPart(
        partKey = key,
        position = position,
        promptKey = payloadKind?.let { key },
        payloadKind = payloadKind,
        payloadSchema = payloadKind?.let { 1 },
        payloadRevision = payloadKind?.let { 1 },
        assets = assets,
        applicability = applicability
    )

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
