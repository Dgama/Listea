package com.example.listea.domain

/**
 * What a person committed when they submitted a Task review.
 *
 * Held separately from [TaskState] because it is a different kind of fact. TaskState says what is
 * true now and can be edited by anyone from anywhere; a response is what was submitted, at one
 * moment, under one template version, and is never edited. The two often agree — a completed Task
 * whose latest response says `completed = true` — and the model must not depend on that, because
 * re-reviewing a Task that is already complete appends a second response while the state does not
 * move at all.
 *
 * **Explicitly not a webhook body.** §8 freezes the wire format and this is not it. The payload
 * carries *wire values* — what a settings page currently calls each action — while a response
 * carries the *ids* the Task is marked with, so renaming an action changes every future payload and
 * no stored response. Modelled on the domain rather than on the wire so that a second template can
 * answer with something a webhook has never had a field for.
 */
sealed interface ReviewResponse {

    /**
     * The rapid-media answer: dealt with or not, and what it was marked with.
     *
     * A snapshot, not a set of changes — it says what was true at submission, which is why it
     * carries the favourite and action values even though toggling either one is not itself a
     * submission. Their latest values ride along with the next judgment about the Task.
     */
    data class MediaReview(
        val completed: Boolean,
        val favorite: Boolean,
        val customActions: Set<String>
    ) : ReviewResponse
}

/**
 * The form a response is stored in — `review_events.response`, one text column.
 *
 * Canonical, meaning the same response always produces the same bytes. Two submissions a person
 * would call identical must not be distinguishable by a diff, or every reader of this column ends
 * up normalising before it can compare, and a test that pins the bytes is worthless.
 *
 * Action ids are sorted, and blanks are dropped going in, for the same reasons
 * [com.example.listea.data.encodeActionIds] does it on the item rows: a set that varies with the
 * order somebody tapped things
 * looks changed when nothing has. Sorting by plain string order is enough — the ids are generated,
 * not typed, so nothing here depends on a locale's idea of alphabetical.
 *
 * Hand-written rather than JSON, for two reasons that both matter. `org.json` is an Android class
 * and this package does not depend on Android; and the wire format's own encoder escapes a solidus
 * and orders its fields, neither of which has anything to say about what a stored response is.
 * Formatting this like the payload would be the first step back toward the two being the same
 * thing.
 *
 * There is no decoder yet, and its absence is deliberate rather than an oversight: nothing in v0.2
 * reads a response back — review count is `COUNT(*)`, and §5.4 rules out a history screen — so a
 * parser would have no caller but its own test. The format is held to its meaning by the golden
 * encoding test instead, which is the same footing the webhook body stands on.
 */
fun ReviewResponse.encode(): String = when (this) {
    is ReviewResponse.MediaReview -> listOf(
        "completed=$completed",
        "favorite=$favorite",
        "actions=${customActions.filter { it.isNotBlank() }.sorted().joinToString(",")}"
    ).joinToString(";")
}

/**
 * The one thing a committed response moves in [TaskState]: whether the Task is complete.
 *
 * The rest of a response is a snapshot — [ReviewResponse.MediaReview.favorite] and its
 * [ReviewResponse.MediaReview.customActions] record what the Task was marked with at the moment
 * somebody judged it, and toggling either of those is not itself a submission (§5). Writing them
 * back from a response would quietly turn a record of a judgment into a second way to make one.
 *
 * Spelled as an exhaustive `when` rather than read off a member, for the reason
 * [TaskAction.isSubmission] is: a response type that arrives without being classified would append
 * its event and leave the state exactly as it was, and a review that is on record but did not
 * complete anything is the kind of disagreement the state/event split exists to make impossible.
 * Naming the answer here means adding one stops the build instead.
 */
val ReviewResponse.completesTask: Boolean get() = when (this) {
    is ReviewResponse.MediaReview -> completed
}
