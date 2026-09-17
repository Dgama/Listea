package com.example.listea

import com.example.listea.data.AssetEntity
import com.example.listea.data.TaskImportEntity

/**
 * What a Todo has said a change to one of its Tasks' Asset sets means.
 *
 * A file the user deleted outside Listea is a change Listea cannot make go away, and every Todo
 * covering that folder has to answer it. [KEEP] is the answer that changes nothing, which is why it
 * is the default everywhere; [RESET] is the answer the chef actually wanted, and [DROP] is the one
 * that reaches a Task's lifetime.
 *
 * Stored as its [key] on `sources.contentPolicy`, in the same shape [ImporterKind] is stored, so a
 * value written by one build reads back the same in the next and an unknown string is a value this
 * build does not have rather than a crash. [label] is the other half: the words a user reads, named
 * once here rather than wherever a screen happens to need them, so the chooser that asks the
 * question and the Todo card that reports the answer cannot call the same tier two things.
 */
enum class ContentPolicy(val key: String, val label: String) {
    /**
     * The Task and its judgment stand; material that has gone is flagged missing and nothing else
     * happens. This is what every Listea before v0.4.0 step 2 did, and it stays the default for
     * exactly that reason — see the rule in `docs/settings.md`.
     */
    KEEP("keep", "Keep it"),

    /**
     * The Task's identity stands and its *current state* is cleared. A Task whose content moved is
     * not the same thing to judge, so carrying its old tick forward is the mistake this tier exists
     * to avoid — but nothing about *which* Task it is has changed, so its history and every List
     * that holds it are untouched.
     */
    RESET("reset", "Start it over"),

    /**
     * The Task's identity does not survive the change: it is deleted, with the List memberships
     * 2a's machinery takes with it, and the run mints a fresh Task in its place.
     *
     * **This is the only tier that reaches lifetime, and it is admitted only as a named policy.** A
     * program that deleted a Task because it noticed something about the Task's structure would be
     * deriving a lifetime from structure, which `ARCHITECTURE.md` forbids in three places. A user
     * who says "when this content moves, start over" is stating an intention about a Task they own,
     * and that is a thing the model must be able to represent. The difference is who said it.
     */
    DROP("drop", "Replace the Task");

    companion object {
        /** What a Todo starts as, and what an unreadable stored value falls back to. */
        val Default = KEEP

        fun fromKey(key: String?): ContentPolicy =
            entries.firstOrNull { it.key == key } ?: Default
    }
}

/** What a run actually does to one Task, once a policy has met that Task's own shape. */
enum class ContentDecision {
    KEEP,
    RESET,
    DROP
}

/**
 * The tier a run applies to one Task: **pure, and the only place this rule is written down**.
 *
 * [added] and [missing] are counts of Assets this run would add to and take from the Task, and
 * [assetCount] is how many Assets the Task holds as the run finds it. The answer is a
 * [ContentDecision] rather than a [ContentPolicy] because the two are not always the same thing:
 * the policy is what the user asked for, and this is what the Task's own shape allows.
 *
 * **A Task nothing changed about is [ContentDecision.KEEP] under every policy**, and that is the
 * first rule rather than a case at the bottom. All three tiers describe what a *change* means, so
 * a run that finds the material exactly as it left it has nothing to answer — and the pin matters
 * more than it looks: an Asset flagged missing on an earlier run is not a change on this one, or a
 * Todo under `reset` would clear its Tasks' judgments every time it was refreshed.
 *
 * **`drop` on a single-Asset Task is [ContentDecision.RESET].** This is the one place the rule
 * reads how many Assets a Task has, and it is worth being exact about what that number is doing
 * here: it never *chooses* the policy — the user did — it only decides which of two ways of
 * honouring the same intention is available. A file-as-Task Task is its file; dropping it would
 * mint a Task holding the same single Asset, under a new id, with the judgment and the List
 * memberships gone, which is loss with nothing bought. So the content has moved, the Task has not,
 * and clearing the judgment is what the user's answer means here. A Task holding several Assets is
 * a group, and a group whose membership changed really is a different group.
 *
 * [assetCount] is the count *as the run finds it*, before anything is written. That is what makes
 * a folder-as-Task Task that went 2 → 0 droppable: it is a group that changed, not an empty Task,
 * which is the distinction [ContentPolicy.DROP] rests on.
 */
fun decideContentDecision(
    policy: ContentPolicy,
    added: Int,
    missing: Int,
    assetCount: Int
): ContentDecision {
    if (added == 0 && missing == 0) return ContentDecision.KEEP

    return when (policy) {
        ContentPolicy.KEEP -> ContentDecision.KEEP
        ContentPolicy.RESET -> ContentDecision.RESET
        ContentPolicy.DROP ->
            if (assetCount > 1) ContentDecision.DROP else ContentDecision.RESET
    }
}

/**
 * One of a Todo's Tasks, and what the run about to happen would do to it.
 *
 * The unit both readers of a change work in: [decideContentDecision] is asked about one of these,
 * and a Todo's freshness is "does any of these say something happened". One computation with two
 * readers is the point — a card that said "up to date" while the run it describes was about to
 * reset three Tasks would be two answers to one question.
 *
 * [planned] is the Task this run's plan makes at [groupPath], or null when the plan makes none —
 * which is what a Task whose content has all gone looks like from here, and is the case that never
 * reaches a plan-driven loop. [added] and [missing] count only Assets *inside the coverage that was
 * scanned*: an Asset outside it was not looked at, and nothing about it has changed.
 */
data class TaskChange(
    val taskId: String,
    val groupPath: String,
    val planned: PlannedTask?,
    val added: Int,
    val missing: Int,
    val assetCount: Int
) {
    /** Whether this run has anything to say about this Task at all. */
    val hasChange: Boolean get() = added > 0 || missing > 0
}

/**
 * Every Task a Todo holds, paired with what a run over [coverage] would change about it.
 *
 * **Pure, and the shape `FileArrange.kt` and `QuickReview.kt` already establish**: the DAO hands it
 * rows it has already read and acts on the answer. Which Tasks exist, what the plan made of them
 * and which Assets have gone are three reads, and the arithmetic over them is the part worth
 * pinning down — that a Task nothing touched reports nothing, that a Task outside the scanned
 * folder is not a Task whose content went missing, and that an Asset already flagged missing is
 * not a change on the run that follows.
 *
 * [imports] and [assets] are the Todo's own rows. An Asset with no `relativePath` is one this build
 * cannot place, and is left out of both counts rather than treated as missing: the reads that fill
 * [AssetEntity.relativePath] treat a null as "not a file under this root", and deleting or clearing
 * a Task over a path nobody can name would be acting on a guess.
 *
 * [plannedPaths] is every path the plan names, across *all* of the Todo's Tasks rather than the one
 * being looked at, and the width is deliberate: a file the plan still makes somewhere has not gone
 * missing, it has moved, and moving is a regrouping the Source settles as its own contradiction
 * before any of this is reached. Reading the narrower set here would call that a disappearance and
 * hand the user a policy decision about a file that is still very much there.
 */
fun taskChanges(
    coverage: String,
    imports: List<TaskImportEntity>,
    assets: List<AssetEntity>,
    planned: List<PlannedTask>,
    plannedPaths: Set<String>
): List<TaskChange> {
    val plannedByPath = planned.associateBy { it.groupPath }
    val heldByTask = assets.filter { it.relativePath != null }.groupBy { it.taskId }

    return imports.map { import ->
        val held = heldByTask[import.taskId].orEmpty()
        val heldPaths = held.mapNotNull { it.relativePath }.toSet()
        val task = plannedByPath[import.groupPath]

        // Only what this run actually looked at can be said to have changed. An Asset outside the
        // coverage was not scanned, so its absence from the plan says nothing, and one already
        // flagged missing was acknowledged by an earlier run and is not news now.
        val missing = held.count { asset ->
            val path = asset.relativePath
            path != null && isAncestorOrSame(coverage, path) &&
                path !in plannedPaths && !asset.sourceMissing
        }
        val added = task?.assets?.count { it.rootRelativePath !in heldPaths } ?: 0

        TaskChange(
            taskId = import.taskId,
            groupPath = import.groupPath,
            planned = task,
            added = added,
            missing = missing,
            assetCount = held.size
        )
    }
}

/**
 * Whether a run over [coverage] would change anything under this Todo — the question a Todo that
 * follows its upstream asks before deciding to run at all.
 *
 * Task *creation* counts, and it is the half a per-Task comparison cannot see: a file that appeared
 * in the folder is a planned Task with no row, which is a change to the Todo and not a change to
 * any Task in it. Everything the scan found and the Todo already holds reports nothing, which is
 * what makes this safe to ask on every entry rather than on a schedule.
 */
fun hasSourceChanges(changes: List<TaskChange>, planned: List<PlannedTask>): Boolean =
    planned.any { task -> changes.none { it.groupPath == task.groupPath } } ||
        changes.any { it.hasChange }
