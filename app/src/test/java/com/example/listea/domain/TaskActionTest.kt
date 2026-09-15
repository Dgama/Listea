package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the semantic actions is a judgment, and which are just movement.
 *
 * This is the whole of §7 as a type can hold it. Whether the app *honours* the split — that a Task
 * left behind by [TaskAction.NextTask] is left exactly as it was found, and that nothing is
 * delivered on the way past — is a property of the write paths and their transactions, and is
 * pinned where those live. What is pinned here is that the fork exists and is not a matter of
 * remembering, which is what the acceptance criterion "PreviousTask / NextTask append no
 * ReviewEvent and mutate no TaskState" is actually protecting.
 *
 * The stronger half of that guarantee cannot be tested: [TaskAction.isSubmission] is an exhaustive
 * `when`, so a fourth action stops the build until somebody classifies it, and a test can only
 * assert about the actions that exist.
 */
class TaskActionTest {

    @Test
    fun `only completion is a submission`() {
        assertTrue(TaskAction.CompleteTask.isSubmission)

        // "I looked at this and have nothing to say" has to be expressible. If moving on were the
        // same act as judging, it would have to be recorded as one.
        assertFalse(TaskAction.PreviousTask.isSubmission)
        assertFalse(TaskAction.NextTask.isSubmission)
    }

    @Test
    fun `every action writes down a key that will not move`() {
        assertEquals(
            listOf("complete", "next", "previous"),
            listOf(TaskAction.CompleteTask, TaskAction.NextTask, TaskAction.PreviousTask)
                .map { it.key }
                .sorted()
        )
    }

    @Test
    fun `the three actions are separately nameable, so none can stand in for another`() {
        // A surface binds one of these per input slot. If two actions ever collapsed to the same
        // value, a template could no longer offer "judge" and "move on" as different choices, and
        // the distinction §7 rests on would be gone without anything failing.
        val actions = listOf(TaskAction.PreviousTask, TaskAction.NextTask, TaskAction.CompleteTask)

        assertEquals(3, actions.toSet().size)
        assertEquals(3, actions.map { it.key }.toSet().size)
    }
}
