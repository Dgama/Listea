package com.example.listea

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guide's two levels, through the counter that opens it.
 *
 * A queue of Tasks is not a flat list any more: each row may hide its own pages behind an arrow,
 * and the row's own tap is the other move — the Task's first page. The two have to be separate
 * controls, because one is "go to this Task" and the other is "show me what is in it"; a test that
 * only checks the rows appear would not notice them collapsing back into one gesture.
 *
 * Composed bare rather than through [MediaShell]: the counter is the whole surface under test, and
 * the popup is anchored to it.
 */
@RunWith(AndroidJUnit4::class)
class MediaQueueGuideMenuTest {

    @get:Rule
    val rule = createComposeRule()

    private var jump: Pair<Int, Int?>? = null

    private fun queue() = MediaQueueJump(
        entries = listOf(
            MediaQueueEntry(
                label = "Task A",
                detail = "camera/Task A",
                pages = listOf(
                    MediaQueueEntry("a1.jpg"),
                    MediaQueueEntry("a2.jpg"),
                    MediaQueueEntry("add-on Field notes")
                )
            ),
            MediaQueueEntry(label = "Task B", pages = listOf(MediaQueueEntry("b1.jpg")))
        ),
        current = 0,
        currentPage = 0,
        onJump = { entry, page -> jump = entry to page }
    )

    private fun show() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    MediaQueueCounter(
                        counter = "1(1/3)/2",
                        queue = queue(),
                        maxWidth = 320.dp,
                        height = 48.dp
                    )
                }
            }
        }
    }

    @Test
    fun theCounterOpensTheTasksAndTheArrowOpensOneTasksPages() {
        show()

        rule.onNodeWithText("1(1/3)/2").performClick()
        rule.onNodeWithText("Task A").assertIsDisplayed()
        rule.onNodeWithText("Task B").assertIsDisplayed()
        rule.onNodeWithText("a1.jpg").assertDoesNotExist()

        rule.onNodeWithContentDescription("Show pages of Task A").performClick()
        rule.onNodeWithText("a1.jpg").assertIsDisplayed()
        rule.onNodeWithText("a2.jpg").assertIsDisplayed()
        rule.onNodeWithText("add-on Field notes").assertIsDisplayed()
        rule.onNodeWithText("Task B").assertDoesNotExist()

        rule.onNodeWithText("a2.jpg").performClick()
        assertEquals(0 to 1, jump)
    }

    @Test
    fun aTasksOwnTapGoesToItsFirstPageAndNamesNoPage() {
        show()

        rule.onNodeWithText("1(1/3)/2").performClick()
        rule.onNodeWithText("Task B").performClick()

        assertEquals(1 to null, jump)
    }

    @Test
    fun thePageHeaderStepsBackOutToTheTasks() {
        show()

        rule.onNodeWithText("1(1/3)/2").performClick()
        rule.onNodeWithContentDescription("Show pages of Task A").performClick()
        rule.onNodeWithText("Task A").performClick()

        rule.onNodeWithText("Task B").assertIsDisplayed()
        rule.onNodeWithText("a1.jpg").assertDoesNotExist()
    }
}
