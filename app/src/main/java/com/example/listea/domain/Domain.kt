package com.example.listea.domain

/**
 * The core entities of the review model, as plain Kotlin.
 *
 * Nothing here knows about Room, Compose, SAF, `org.json` or any HTTP shape. That is not purity for
 * its own sake: the ambiguity v0.2 exists to remove was created by one type standing for a file, a
 * stored row and a queue entry at once, and the way to keep the replacement unambiguous is to give
 * it no way to speak in any of those terms. Source references, templates and payloads meet these
 * types at their boundaries, through values defined here or beside them.
 *
 * The shapes follow §2's schema deliberately, down to which column lives on which entity, so that
 * the persistence step is a translation rather than a redesign. What they add over the schema is
 * the part SQL cannot state: that identity is opaque and never moves (see [TaskId] and friends),
 * that current state belongs to the Task, and that history is append-only.
 */

/**
 * Where an Asset's content lives: the SAF grant it is under, and its path beneath that grant.
 *
 * A source reference, never an identity. The same file visited from a folder review, from one List
 * and from another is one [Asset] with one [AssetId], and this is what all three resolve through to
 * find it — the pair the schema makes unique on `assets`.
 *
 * Deliberately a plain pair of strings rather than a `Uri`, so nothing downstream can start
 * treating a grant as domain state. `list_items.rootRelativePath` on one side and `ReviewItem.target`
 * on the other are the legacy spellings of this same pair, translated at the boundary.
 */
data class SourceRef(val rootUri: String, val relativePath: String)

/**
 * What kind of content an Asset holds.
 *
 * Four members, and [OTHER] is not a placeholder for laziness: a Task must be representable when
 * its content is something no viewer claims yet, and the alternative — refusing to construct the
 * Asset, or guessing a kind — turns "Listea cannot show you this yet" into "this file is gone".
 *
 * There is no resolver here, and that is still the decision rather than an omission. §6 keeps kind
 * out of storage, and this package states what a kind *is* without ever being the place that works
 * one out. Freezing a resolver here would give this package an opinion about images, and this is
 * the package that is supposed to have none.
 *
 * The resolver was written in v0.5.0, and not where this note originally said it would be. The note
 * anticipated name-and-mime, next to `MediaPreview`; what exists is `assetKindOf`, name-only, in
 * `FileArrange.kt` beside `mediaKindOf`. The reason is that the two answers are needed in places
 * that have no bytes to hand and cannot afford to look: a todo's import stamps a kind onto every
 * Task it creates, and the dialog that starts that run asks about the kinds a folder holds before
 * anything has been opened. `previewKindOf` needs a `Context` and a content-provider call, and says
 * so itself — blocking, IO dispatcher. A kind resolved from mime is a kind that cannot be stamped
 * identically at import time and re-derived at review time, and an import record that disagrees with
 * the screen is the drift this note was warning about, arrived at from the other direction.
 *
 * So the divergence from `previewKindOf` is deliberate and stays: preview answers *can I draw these
 * bytes*, with the bytes in hand; kind answers *which control governs this file*, and must be
 * answerable without them. `assetViewerAvailable` is now expressed in terms of `assetKindOf`, which
 * is what collapsed the two name-based opinions into one — before v0.5.0 there were two.
 *
 * Not to be confused with `MediaKind`, which is the *filter* vocabulary and answers a different
 * question: what may the user narrow a listing to. That one has a `TEXT` member for files Listea
 * can filter but cannot present, so the two sets agree on pictures and videos and diverge
 * everywhere else — on purpose.
 */
enum class AssetKind(val key: String) {
    IMAGE("image"),
    VIDEO("video"),

    /**
     * A document the viewer can render and whose text it can name.
     *
     * A kind of its own rather than [OTHER], because a template's scope is what decides which
     * tools a file offers: scoping a PDF template to `OTHER` would also hand it every text file and
     * archive this build cannot render. The kind is name-based like the other two, so an import
     * can stamp it before anything is opened; the MIME type still decides how the bytes are drawn.
     */
    PDF("pdf"),
    OTHER("other");

    companion object {
        /** The kind a stored [key] names, or null for a key this build has never written. */
        fun fromKey(key: String?): AssetKind? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * The unit that gets judged: an opaque identity, and when it arrived.
 *
 * That is the whole of it, and the smallness is the point. A Task has no title (titles stay with
 * the entry or the folder listing — a known v0.2 limitation), no path, no List, and no kind. It
 * carries nothing that could be read as permission to delete it when something else changes; see
 * `legacy_manual_tasks` for the one place a Task's lifetime is tied to membership, which is a
 * closed compatibility case that names its own rows rather than a rule the model implies.
 */
data class Task(val id: TaskId, val createdAt: Long)

/**
 * Content presented through a compatible viewer.
 *
 * Read-only by construction: no completion, no favourite, no custom actions, no review count. Every
 * one of those belongs to [TaskState] on the Task, which is what makes a folder review and a List
 * review reaching the same file agree about it without either being told to. [sourceMissing] is the
 * exception that proves it — presence is a fact about the content, not a judgment about it.
 */
data class Asset(
    val id: AssetId,
    val taskId: TaskId,
    val sortOrder: Int,
    val source: SourceRef?,
    val sourceMissing: Boolean = false
)

/**
 * What is true about a Task right now.
 *
 * One row per Task, always present, which is why the read paths lose the `CASE`/`COALESCE`
 * projection they need today: there is no longer a state that has to be invented when a file has
 * never been looked at. A Task with no state row is a Task that does not exist.
 *
 * [isCompleted] is the existing `checked`, keeping its meaning — "I have dealt with this in
 * Listea" — and staying independent of the other two. It says nothing about how the decision was
 * reached, which is what lets a List Detail tidy-up and a review submission write the same field
 * while only one of them is a judgment.
 *
 * [customActions] holds action ids, not labels and not wire values, for the reason it always has:
 * an action can be renamed without rewriting a row, and an id that no longer matches a configured
 * action is kept rather than dropped.
 */
data class TaskState(
    val taskId: TaskId,
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,
    val customActions: Set<String> = emptySet(),
    val updatedAt: Long
)

/**
 * Membership: one Task's place in one List.
 *
 * The entry is ordered and dated, because ordering and position are review-context concerns and
 * always were. It references a [TaskId] and never stands in for one, so the same Task can sit in
 * two Lists, leaving one, or outliving both without any of that being an identity event.
 *
 * [id] is the membership identity — what `lists.reviewCurrentItemId` and a round's queue snapshot
 * continue to speak in. The legacy `publicId` is deliberately absent: it belongs to the
 * compatibility boundary (§8) and putting it here would invite exactly the confusion §5.2 rules
 * out, since a public id names one arrival of an item on the wire and a Task outlives any number
 * of those.
 */
data class ListEntry(
    val id: ListEntryId,
    val listId: ListId,
    val taskId: TaskId,
    val sortOrder: Int,
    val createdAt: Long
)

/**
 * What kind of entry a [ReviewEvent] is.
 *
 * One value in v0.2, and the enum exists anyway because the column does. The architecture leaves
 * room for entries that are not submissions, and a TEXT column with a single member now is cheaper
 * than a migration on the day one is needed — a version of the table that cannot hold anything but
 * a submission is a version that has to be rebuilt to hold anything else.
 */
enum class ReviewEventKind(val key: String) {
    /** A person was presented with the Task and committed a response to it. */
    SUBMITTED("submitted")
}

/**
 * Where a review was submitted from.
 *
 * Provenance, not structure: it is how an event is read back later, and it records nothing about
 * the Task that the Task did not already know. The two cases are the two ways v0.2 can present a
 * Task for review, which is what makes "the same file resolved twice" auditable rather than merely
 * asserted.
 */
sealed interface ReviewContext {
    data class InList(val listId: ListId) : ReviewContext

    data class InFolder(val rootUri: String, val relativePath: String) : ReviewContext
}

/**
 * One judgment, appended when somebody submitted it.
 *
 * Not a change log for [TaskState], and the distinction is the whole of §5. Current state moves for
 * many reasons that are nobody's verdict — an administrative correction, a bulk edit, a source file
 * going missing, a migration backfill — and records nothing. An event means a person was shown the
 * Task through a Task Review interaction and committed a response to it.
 *
 * The two cannot be derived from one another, which is why this type holds its own
 * [TemplateVersionRef] rather than pointing at whatever the List uses now: a review means what it
 * meant under the definition that was in force, and a later version of that template must not be
 * able to reinterpret it.
 *
 * Append-only. [TaskState.isCompleted] may be false while several of these exist, and clearing it
 * removes none of them.
 */
data class ReviewEvent(
    val id: ReviewEventId,
    val taskId: TaskId,
    val at: Long,
    val kind: ReviewEventKind,
    val template: TemplateVersionRef,
    val response: ReviewResponse,
    val context: ReviewContext? = null
)
