package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where an accepted commit lands on a Task that holds several questions.
 *
 * The rule is *the next part's first page, then the Add-on, then the next Task*. [ReviewPagesTest]
 * pins the single-part cases; what a second part added is the middle of the page list, where one
 * part's files sit between its question and the next question. Those files belong to the judgment
 * just made, so a commit from anywhere inside part one must reach part two's question rather than
 * the next file it happens to find.
 *
 * The pages below are the shape a section Task really has:
 *
 * ```
 * 0 C1  1 a  2 b  |  3 C2  4 c  |  5 C3  |  6 Add-on
 * ```
 *
 * Every assertion names a page, so a change to the arrival rule is legible as a number moving
 * rather than as a paragraph of intent.
 */
class CommitArrivalPartsTest {

    private val pages = listOf(
        ReviewPage.Content("task-1", "q1"),
        assetPage("q1", "a.jpg", 0),
        assetPage("q1", "b.jpg", 1),
        ReviewPage.Content("task-1", "q2"),
        assetPage("q2", "c.jpg", 2),
        ReviewPage.Content("task-1", "q3"),
        ReviewPage.AddOn
    )

    @Test
    fun `a commit from a question goes straight to the next question`() {
        // The files between the two questions belong to q1; the judgment was made before the
        // commit, so walking them again would show the answered part rather than the next one.
        assertEquals(CommitArrival.Page(3), arrival(currentPage = 0))
    }

    @Test
    fun `a commit from a part's file still goes to the next question`() {
        assertEquals(CommitArrival.Page(3), arrival(currentPage = 1))
        assertEquals(CommitArrival.Page(3), arrival(currentPage = 2))
    }

    @Test
    fun `a commit from the middle question skips its own file`() {
        assertEquals(CommitArrival.Page(5), arrival(currentPage = 3))
        assertEquals(CommitArrival.Page(5), arrival(currentPage = 4))
    }

    @Test
    fun `the last question reaches the add-on`() {
        assertEquals(CommitArrival.Page(6), arrival(currentPage = 5))
    }

    @Test
    fun `a commit on the add-on page leaves the Task`() {
        assertEquals(CommitArrival.NextTask, arrival(currentPage = 6))
    }

    @Test
    fun `without an add-on the last question leaves the Task`() {
        val withoutAddOn = pages.filterNot { it is ReviewPage.AddOn }

        assertEquals(
            CommitArrival.NextTask,
            commitArrival(advanceAfterCommit = true, pages = withoutAddOn, currentPage = 5)
        )
    }

    @Test
    fun `a commit inside the last part's files leaves the Task`() {
        val lastPartOnly = listOf(
            ReviewPage.Content("task-1", "q1"),
            assetPage("q1", "a.jpg", 0),
            assetPage("q1", "b.jpg", 1)
        )

        assertEquals(
            CommitArrival.NextTask,
            commitArrival(advanceAfterCommit = true, pages = lastPartOnly, currentPage = 1)
        )
    }

    @Test
    fun `answering a part does not advance when the template says stay`() {
        // The confirmation-step templates keep the reviewer on the page they committed; that is
        // their choice about their own step, and the inner arrow still reaches the next part.
        for (page in 0..6) {
            assertNull(
                "page $page",
                commitArrival(advanceAfterCommit = false, pages = pages, currentPage = page)
            )
        }
    }

    @Test
    fun `a Task of questions with no files follows the same page order`() {
        // A section Task whose Prompts have no files: every page is a question, then the add-on.
        val questions = listOf(
            ReviewPage.Content("task-1", "q1"),
            ReviewPage.Content("task-1", "q2"),
            ReviewPage.Content("task-1", "q3"),
            ReviewPage.AddOn
        )

        assertEquals(
            CommitArrival.Page(1),
            commitArrival(advanceAfterCommit = true, pages = questions, currentPage = 0)
        )
        assertEquals(
            CommitArrival.Page(2),
            commitArrival(advanceAfterCommit = true, pages = questions, currentPage = 1)
        )
        assertEquals(
            CommitArrival.Page(3),
            commitArrival(advanceAfterCommit = true, pages = questions, currentPage = 2)
        )
        assertEquals(
            CommitArrival.NextTask,
            commitArrival(advanceAfterCommit = true, pages = questions, currentPage = 3)
        )
    }

    private fun arrival(currentPage: Int): CommitArrival? =
        commitArrival(advanceAfterCommit = true, pages = pages, currentPage = currentPage)

    private fun assetPage(partKey: String, name: String, index: Int) = ReviewPage.Asset(
        partKey = partKey,
        asset = com.example.listea.data.ReviewAsset(
            sourceUri = "content://fixture/$name",
            name = name,
            sourceMissing = false
        ),
        assetIndex = index
    )
}
