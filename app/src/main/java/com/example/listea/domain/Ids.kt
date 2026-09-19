package com.example.listea.domain

import java.util.UUID

/**
 * Identity for the durable domain entities: Task, Asset, ReviewEvent, List, membership and
 * Template.
 *
 * Several types rather than one `Id`, because what v0.2 has to guarantee here is mostly a property
 * of *not confusing them*. "Task ID and Asset ID are never interchangeable" is a sentence a
 * compiler can hold and a reviewer cannot: with one id type, passing the wrong one to a function
 * that takes two is a bug that compiles, and every call site has to be read to rule it out. Here
 * it does not compile. The same goes for a List's id against a membership id — two `Long`s today,
 * which is the confusion `removeItem(listId, itemId)` has to be read carefully to avoid.
 *
 * They are opaque on purpose. An id carries no path, filename, row number or List position, and
 * nothing may read meaning back out of one. That is not tidiness: the moment a Task's identity is
 * derived from where its file sits, moving or renaming that file becomes an identity change, and
 * §5.2 forbids exactly that.
 *
 * Which is why these are UUIDs and not [com.example.listea.data.newItemPublicId] — the other
 * identifier scheme in this codebase, and one that looks similar from a distance. A public id's
 * middle field is an FNV fingerprint *of the file's path under the root*: two ids for one file are
 * deliberately different, and the value is deliberately readable and sortable by a human. It is a
 * good name for one *arrival* of a file on the wire and a disqualifying one for a Task, which has
 * to survive the very moves its fingerprint is computed from. Both schemes stay; §8's adapter is
 * where one becomes the other.
 */

/**
 * Identity of one [Task]. Generated once, when the Task is created, and never rewritten.
 *
 * Not [Task]'s source path, not its title, and not the id of any List that happens to contain it:
 * a Task reached from two Lists, renamed, reordered, completed and reopened is still one Task.
 */
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun new(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}

/**
 * Identity of one [Asset], distinct from its [Task]'s by construction.
 *
 * The two are one-to-one in the media workflow and stay separate anyway. A future Task may hold
 * several Assets, and the whole point of the split is that reassembling, reordering or replacing
 * what is *presented* cannot be mistaken for redefining what is being *judged*.
 */
@JvmInline
value class AssetId(val value: String) {
    companion object {
        fun new(): AssetId = AssetId(UUID.randomUUID().toString())
    }
}

/**
 * Identity of one List — the existing `lists.id` autoincrement, typed.
 *
 * Stable for everything a List does: renaming, reordering, gaining and losing entries, completing
 * and being reopened. It is not Task identity, and a List being deleted says nothing about the
 * Tasks that were in it.
 */
@JvmInline
value class ListId(val value: Long)

/**
 * Identity of one [ListEntry]: a Task's membership in a List.
 *
 * This is the existing `list_items.id`, kept as-is by decision 6. Membership is its own identity
 * space, and the reason to keep the column rather than mint a UUID is that everything that already
 * speaks in row ids is a membership concern — the resume cursor on `lists`, a round's queue
 * snapshot, the webhook's queue scoping. None of those are Task identity, and none of them should
 * have to change to say so.
 */
@JvmInline
value class ListEntryId(val value: Long)

/**
 * Identity of one [ReviewEvent].
 *
 * Its own, and not the Task's: a Task reviewed five times has five events, re-review is valid and
 * expected, and unchecking never removes one.
 */
@JvmInline
value class ReviewEventId(val value: String) {
    companion object {
        fun new(): ReviewEventId = ReviewEventId(UUID.randomUUID().toString())
    }
}

/**
 * Identity of a Template *lineage* — `builtin.media.rapid-review`, and whatever else is defined
 * later.
 *
 * The odd one out among these, and it has changed sides. A lineage is not generated, because its id
 * is a fixed string that has to keep meaning the same thing across releases — [Templates] is where
 * the built-in ones come from, and they are the ones a schema default or an old row can name.
 *
 * **Since v0.5.4 a lineage is also minted, and never from a name.** The release that made a
 * template a stored row mints one wherever there is a configuration that would otherwise be lost —
 * the upgrade gives each Todo's own buttons a lineage of its own — and the id is a fresh UUID while
 * the *name* is taken from the Todo. So the two halves of this comment now have to be read together:
 * a lineage is not derived from anything, and an id that was minted is opaque on purpose, because
 * the name beside it is the part a person may change.
 */
@JvmInline
value class TemplateId(val value: String)

/**
 * A reference to one immutable template *definition*: which lineage, and which version of it.
 *
 * The pair is the identity — a version number on its own names a definition in no particular
 * lineage, and two lineages both at version 1 is the normal case. Persisting this rather than the
 * definition, or rather than a name, is what lets a completed review still be interpreted after
 * the template it was made under has moved on.
 */
data class TemplateVersionRef(val templateId: TemplateId, val version: Int) {

    /** `builtin.media.rapid-review@1`, for logs and for anywhere a single token is easier. */
    override fun toString(): String = "${templateId.value}@$version"
}
