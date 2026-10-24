package com.example.listea.data

import com.example.listea.ImportNotice
import com.example.listea.domain.PackageDiagnostic

/**
 * The shape of one import run's answer, returned to the caller and shown, then dropped.
 *
 * A run is a [ImportPlan] turned into rows by [ListsDao.runImport]. What survives it is the Source
 * row's `lastImportAt` / `lastImportStatus` and the `lastSeenAt` on each provenance row; this value
 * is the detail a result screen shows and no table keeps. There is deliberately no import history
 * table — a run history is a feature nothing in v0.2.5 reads, and adding one now is the "while we
 * are here" failure the release was written to avoid.
 */

/** Whether a run wrote everything it was asked to, or something had to be left out. */
enum class ImportOutcome(val key: String) {
    /** No conflicts, and every planned Task for the covered scope was written. */
    COMPLETE("complete"),

    /** The transaction committed, but at least one planned Task was not written. */
    PARTIAL("partial"),

    /** The Source was gone, so nothing was attempted. */
    FAILED("failed")
}

/**
 * A planned Task an import run could not write, and why.
 *
 * [relativePath] names the file whose locator collided; [ownerLabel] says what already owns it, in
 * words the report can put in front of a user; [taskId] is the existing Task the file belongs to,
 * so the report can say which Task would have been merged or split. A conflict is never resolved by
 * minting, moving or deleting — the rest of the run proceeds, and the affected Task is simply not
 * written.
 *
 * **Another Source holding a file is deliberately not one of these.** Two Todos over one folder are
 * two intentions, and the Tasks they plan over the same picture are meant to be separate; a run
 * meeting another Source's Task writes beside it. What is left here is a Source disagreeing with
 * itself — a file it already holds under a Task its own grouping rule no longer produces — and a
 * legacy Task a folder-as-Task run would have to fold a reviewed identity into.
 */
data class ImportConflict(
    val relativePath: String,
    val ownerLabel: String,
    val taskId: String
)

/**
 * A folder-to-List import that did not write every planned Task, naming the Source it reused.
 *
 * A partial run is a *report* everywhere else — the caller shows the conflicts and keeps what was
 * written — and this is the one path where that is not good enough. `createFolderList` has already
 * promised a List over these exact files, and a List of some of them is not the thing that was
 * asked for, so it takes the whole transaction down with it rather than committing a short one.
 *
 * The cause is nearly always the same one, and it is not a fault: the folder already belongs to a
 * Source whose own grouping rule owns those files, so the per-file Tasks this path plans collide
 * with locators that are already taken. Nothing can be done about that from here — the caller can
 * only say so — which is why the covering Source travels out with the failure so the message can
 * name it and point at where those Tasks *can* be picked.
 */
class FolderListImportConflict(val sourceRelativePath: String) :
    IllegalStateException("Import for \"$sourceRelativePath\" did not complete")

/** What a run did, and what it left out. */
data class ImportResult(
    val outcome: ImportOutcome,
    val tasksCreated: Int = 0,
    val tasksAdopted: Int = 0,
    val tasksReconciled: Int = 0,
    /**
     * How many Tasks took their judgment from a dissolved Todo's history rather than starting fresh.
     *
     * Part of the run's own answer rather than a separate report, because a recovery happens inside
     * the run's transaction: the run wrote these Tasks and the history is what the user asked them
     * to be born knowing. Splitting it out would mean a run that could succeed while the thing that
     * justified it silently did not.
     */
    val tasksRecovered: Int = 0,
    /**
     * How many of the Tasks above had their judgment cleared because their content moved.
     *
     * A reset Task is counted in [tasksReconciled] as well, because that is what happened to it —
     * its Assets were brought up to date and it kept its identity. This is the second fact about the
     * same Task, and the report needs both: *"2 Tasks updated, one of them started over"* is the
     * honest sentence, and either count alone would say something the run did not do.
     */
    val tasksReset: Int = 0,
    /**
     * How many Tasks were replaced rather than updated, under a `drop` policy.
     *
     * Not counted in [tasksCreated] even though a fresh Task takes each one's place, and not in
     * [tasksReconciled] even though they stood where a reconciled Task would have. A drop is the one
     * outcome that removes something from the user's world — a Task id, and every List membership
     * that id held — so it is reported on its own line rather than folded into a count that reads
     * like bookkeeping.
     */
    val tasksDropped: Int = 0,
    val assetsCreated: Int = 0,
    val assetsUpdated: Int = 0,
    val assetsMarkedMissing: Int = 0,
    val conflicts: List<ImportConflict> = emptyList(),
    val notices: List<ImportNotice> = emptyList(),

    /**
     * How many Packages this run advanced — a new checkpoint or a higher revision.
     *
     * A Package whose fingerprint is unchanged is neither written nor counted: it was seen and it
     * said nothing new, which is the ordinary result of following an upstream.
     */
    val packagesWritten: Int = 0,

    /** How many Packages were refused, by planning or by reconciliation, and wrote nothing. */
    val packagesRejected: Int = 0,

    /**
     * Everything the Package planner had to say, in the order it found it.
     *
     * Kept as diagnostics rather than notices because they are not all the same kind of fact: a
     * rejected envelope explains why nothing was imported, while a missing optional photo is a
     * note about a Prompt that was. The report groups them by whether a Package was written.
     */
    val packageDiagnostics: List<PackageDiagnostic> = emptyList()
)
