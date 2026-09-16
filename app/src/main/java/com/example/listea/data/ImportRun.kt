package com.example.listea.data

import com.example.listea.ImportNotice

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
 */
data class ImportConflict(
    val relativePath: String,
    val ownerLabel: String,
    val taskId: String
)

/** What a run did, and what it left out. */
data class ImportResult(
    val outcome: ImportOutcome,
    val tasksCreated: Int = 0,
    val tasksAdopted: Int = 0,
    val tasksReconciled: Int = 0,
    val assetsCreated: Int = 0,
    val assetsUpdated: Int = 0,
    val assetsMarkedMissing: Int = 0,
    val conflicts: List<ImportConflict> = emptyList(),
    val notices: List<ImportNotice> = emptyList()
)

/**
 * The answer to "register this Source", which is not always a registration.
 *
 * Registration is rejected when another real Source's import root overlaps the requested one, by
 * the same `scopesOverlap` rule folder-backed Lists have always used — that is what keeps ownership
 * a settled question rather than a race. An already-registered `(rootUri, relativePath)` is not an
 * error either: it is the same Source, returned rather than re-inserted.
 */
sealed interface RegisterSourceResult {
    data class Registered(val source: SourceEntity) : RegisterSourceResult

    /** The covering Source named in the rejection message, so the user knows which root to reuse. */
    data class Overlaps(val covering: SourceEntity) : RegisterSourceResult
}
