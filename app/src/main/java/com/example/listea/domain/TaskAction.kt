package com.example.listea.domain

/**
 * What a person can ask of the review surface, named for what it means rather than for how it was
 * asked for.
 *
 * A swipe, a button, a keyboard shortcut and an accessibility action all arrive here as one of
 * these, and the domain never learns which. That is not a layering preference: `swipeLeft` in
 * domain code is a decision about *how* to review, and it would either spread into every future
 * surface or be re-decided by each one.
 *
 * Three actions, and the interesting thing about them is not that they exist but that two of them
 * are not judgments. [TaskAction.CompleteTask] commits a review; the other two are motion through
 * the queue, and §7 is emphatic that the difference must be structural — a person who looks at
 * Task A, moves to Task B and leaves A alone has reviewed nothing, and no amount of it looking
 * like progress may make the app record a verdict nobody gave.
 *
 * Assembling these into an interaction — which one a gesture means, which control draws them, what
 * happens after one is invoked — is the Task Review template's business. This type is only the
 * vocabulary those definitions and surfaces share.
 */
sealed interface TaskAction {

    /**
     * A stable name, for anywhere the action has to be written down rather than drawn.
     *
     * Declared rather than read off the class, because a canonical form built from a class name
     * changes when the class is minified — and [TemplateVersion.contentHash] is built from these.
     * A hash that moves between a debug and a release build would report an unchanged definition
     * as edited, or worse, an edited one as unchanged.
     */
    val key: String

    /**
     * Move to the Task before this one.
     *
     * Pure motion. Does not complete, uncomplete, favourite, mark, record or deliver anything: a
     * Task left behind this way is left exactly as it was found, which is a valid outcome rather
     * than an unfinished one.
     */
    data object PreviousTask : TaskAction {
        override val key = "previous"
    }

    /**
     * Move to the Task after this one.
     *
     * Pure motion, on the same terms as [PreviousTask]. Reaching the last Task this way is not
     * completion and does not finish anything — the control simply stops being available there.
     */
    data object NextTask : TaskAction {
        override val key = "next"
    }

    /**
     * Commit a review of this Task, then move on.
     *
     * One action, not two: the advance is this action's *consequence*, which is the only shape in
     * which a queue can be worked through by judging things. It stays distinct from [NextTask]
     * precisely so that advancing without judging is still possible — if completion were the only
     * way forward, "I looked at this and have nothing to say" would have to be recorded as a
     * verdict.
     */
    data object CompleteTask : TaskAction {
        override val key = "complete"
    }
}

/**
 * Whether invoking this action is a review submission.
 *
 * The one question §5 asks of these, and the reason it is a named property rather than the test
 * written out at each call site: the state write and the event append are meant to diverge here
 * and agree nowhere else, and a fork that exists in three places is a fork that will disagree with
 * itself. [TaskAction.CompleteTask] submits; the navigation actions do not, and neither does
 * anything reached from a screen that is not a review surface — which is a fact about the caller,
 * so the write paths keep it, not this.
 *
 * Spelled as an exhaustive `when` rather than a comparison against [TaskAction.CompleteTask], so
 * that adding a fourth action to the interface stops the build until somebody decides which side
 * of the fork it belongs on. A new action that silently arrives as "not a submission" is a new
 * action whose judgments go unrecorded, and it would be found by reading rather than by compiling.
 */
val TaskAction.isSubmission: Boolean get() = when (this) {
    TaskAction.PreviousTask, TaskAction.NextTask -> false
    TaskAction.CompleteTask -> true
}
