package com.example.listea

import com.example.listea.data.ScannedFile
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.TemplateVersionRef

/**
 * The importer layer: a pure grouping rule that turns a folder snapshot into planned Tasks.
 *
 * This is the one place Listea decides which raw inputs form a Task, and it is deliberately free of
 * `Context`, SAF and the database. The reason is the same one [FileArrange] and [FolderScan] have
 * hundreds of tests between them: a grouping rule that can be given a [FolderSnapshot] and asked
 * what it would produce is a rule that can be pinned down, and "which files are one Task" is the
 * one decision the whole release exists to make explicit. A `plan` never writes; [ListsDao.runImport]
 * is the only thing that turns a plan into rows.
 *
 * Two rules answer two different questions, and importing something never implies it can be
 * reviewed. file-as-Task admits every file the scanner found — v0.1 parity, no content filter —
 * while folder-as-Task applies the supported-content policy below, because it is new and has no
 * behaviour to preserve. Whether a judgment may be recorded against a Task is a later question,
 * answered by review eligibility, not by the importer.
 */

/** The grouping rules v0.2.5 ships, keyed by the value persisted on `sources.importerKind`. */
enum class ImporterKind(val key: String) {
    FILE_AS_TASK("file-as-task"),
    FOLDER_AS_TASK("folder-as-task");

    companion object {
        fun fromKey(key: String): ImporterKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a Create Tasks run is configured with: the grouping rule, what the Source is called, and
 * whether the folder tree survives into how the Todo is presented.
 *
 * The three travel together because they are one answer to one question — "make Tasks out of this
 * folder, like this" — and are answered in a single dialog. [displayName] is what the user typed, and
 * it names the Source rather than any Task: the Tasks inside keep whatever the importer calls them.
 *
 * [keepTree] is only meaningful for [ImporterKind.FILE_AS_TASK]; [normalizeKeepTree] is what makes
 * that true everywhere it is read, so the stored column and this option cannot disagree.
 *
 * [contentPolicy] is the odd one of the four, and it is here for a reason worth stating: it is not
 * a decision about *this* folder's shape at all, it is the Todo's standing answer to what a change
 * to its Tasks' content means. It travels with the others because it is asked in the same dialog,
 * and it is written back to the Todo afterwards so the next dialog opens on it — see
 * `ListsDao.configureSource`. Its absence from a refresh is not an absence of the question: a
 * refresh's dialog opens on the stored answer and offers it unchanged.
 */
data class ImportOptions(
    val importer: ImporterKind = ImporterKind.FILE_AS_TASK,
    val displayName: String = "",
    val keepTree: Boolean = true,
    val contentPolicy: ContentPolicy = ContentPolicy.Default,

    /**
     * The templates the dialog attached, in the order the user attached them.
     *
     * An instruction *for this run* rather than a record of one, exactly as [contentPolicy] is —
     * see `applyImport`, which passes it into the run rather than reading it back from the row it
     * is about to write. The two travel together for the same reason.
     *
     * **Empty means nothing was attached, and that is a refusal rather than a default.** It is what
     * the dialog will not let a user confirm on, and what a caller with no dialog to show gets: a
     * Task whose files no template governs draws no chips at all, which is legible, rather than a
     * built-in standing in for a choice nobody made, which is not. This field used to be a per-kind
     * tag map with exactly that fallback, and dropping it is the change v0.5.4 makes.
     *
     * A *reference* per entry rather than a copy of the buttons: what a Task was judged under is
     * [TemplateVersionRef], and the buttons it offers are resolved from the template tables at the
     * moment somebody looks — see [TemplateResolver]. That is what lets a template be edited
     * without a re-import, and what makes `…asset-control@1` go on meaning version 1.
     */
    val templates: List<TemplateVersionRef> = emptyList(),

    /**
     * How this Todo's decisions leave the app, chosen here and written back to the row.
     *
     * Defaults to [ExportChoice.Nothing] — *nothing was chosen, so nothing is sent* — and that is
     * deliberately not the same answer as an existing Todo's, which migrates to
     * [ExportKind.WEBHOOK] (v0.5.6 decision 12): preserving what a Todo already did is one
     * question, and what a brand-new Todo should do is another.
     *
     * A single value and never a set. The plural case is real — a List holding Tasks from several
     * Todos exports more than once — but it is a **sum over the Todos a round touched**, computed
     * when the round ends, and not something stored on any row.
     */
    val exportChoice: ExportChoice = ExportChoice.Nothing
)

/**
 * [keepTree] as an importer actually honours it.
 *
 * A folder-as-Task Source produces one Task per child folder and nothing else, so it has no tree to
 * keep: its catalog is flat the moment it is written, whatever the option says. The control that
 * offers it is hidden for that importer, but hiding a control is a presentation fact and cannot be
 * what makes the stored column honest — this is. It is silent by design: the user never had the
 * choice, so there is nothing to report back to them about losing it.
 */
fun normalizeKeepTree(importer: ImporterKind, keepTree: Boolean): Boolean =
    when (importer) {
        ImporterKind.FILE_AS_TASK -> keepTree
        ImporterKind.FOLDER_AS_TASK -> false
    }

/** One file, as an importer wants to attach it to a Task. Root-relative, ordered. */
data class PlannedAsset(
    val rootRelativePath: String,
    val name: String,
    val uri: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val sortOrder: Int
)

/** One Task an importer would produce: what it grouped, what it called it, and its Assets. */
data class PlannedTask(
    val groupPath: String,
    val displayName: String,
    val assets: List<PlannedAsset>
)

/**
 * A thing the importer saw and did not make a Task out of.
 *
 * Not a failure — an import run is [com.example.listea.ImportPlan] even when it carries notices.
 * A notice is the importer doing its job, which is what lets a stray `rubric.txt` stop nothing.
 * [relativePath] is root-relative, the same space [PlannedAsset.rootRelativePath] speaks in.
 */
sealed interface ImportNotice {
    val relativePath: String

    /** A file outside the supported-content policy. Reported, never an Asset. */
    data class UnsupportedFile(override val relativePath: String) : ImportNotice

    /** An immediate child folder holding no supported file. No Task, exactly one notice. */
    data class EmptyFolder(override val relativePath: String) : ImportNotice
}

/** What an importer made of a snapshot: the Tasks it would write, and the things it skipped. */
data class ImportPlan(
    val tasks: List<PlannedTask>,
    val notices: List<ImportNotice>
)

/**
 * Plans Tasks for [snapshot] using [kind].
 *
 * [importRoot] is the SAF-grant-relative path of the folder the snapshot was scanned from (`''` is
 * the grant root). The snapshot's paths are relative to that folder, so every root-relative value
 * this returns is rebased through it; a file at `a.jpg` under an import root of `holiday` is
 * `holiday/a.jpg`, which is the identity every other part of Listea names it by.
 */
fun plan(kind: ImporterKind, importRoot: String, snapshot: FolderSnapshot): ImportPlan =
    when (kind) {
        ImporterKind.FILE_AS_TASK -> planFileAsTask(importRoot, snapshot)
        ImporterKind.FOLDER_AS_TASK -> planFolderAsTask(importRoot, snapshot)
    }

/**
 * One Task per discovered file, one Asset, no content filter.
 *
 * This is what `createFolderList` always did, expressed as a rule instead of a side effect. A file
 * no viewer supports still becomes a Task, because the legacy folder-to-List path never filtered;
 * whether that Task can be reviewed is eligibility's question, not this one's. `directories` is
 * unused — there is nothing this importer can say about a folder.
 */
fun planFileAsTask(importRoot: String, snapshot: FolderSnapshot): ImportPlan = ImportPlan(
    tasks = snapshot.files.map { fileTask(importRoot, it) },
    notices = emptyList()
)

/**
 * Each immediate child folder of the import root is one Task; its supported descendants are its
 * Assets, ordered by relative path; supported files directly under the root are one-Asset Tasks.
 *
 * Nested folders organise Assets and never create Tasks, so entering `alice/papers` later cannot
 * redefine `sample.pdf` as its own Task. A child folder with no supported file produces no Task and
 * one notice, and an unsupported file anywhere is skipped and reported. Supported means picture,
 * video or PDF; PDF is imported and catalogued but is deliberately not reviewable until v0.3.
 */
fun planFolderAsTask(importRoot: String, snapshot: FolderSnapshot): ImportPlan {
    val notices = mutableListOf<ImportNotice>()
    val tasks = mutableListOf<PlannedTask>()

    val rootFiles = snapshot.files.filter { '/' !in it.relativePath }
    val filesInFolders = snapshot.files.filter { '/' in it.relativePath }

    for (file in rootFiles) {
        if (folderAsTaskSupported(file.name)) {
            tasks += fileTask(importRoot, file)
        } else {
            notices += ImportNotice.UnsupportedFile(childRelativePath(importRoot, file.relativePath))
        }
    }

    val childFolders = snapshot.directories.filter { '/' !in it }
    for (folder in childFolders) {
        val inFolder = filesInFolders.filter { it.relativePath.startsWith("$folder/") }
        // Ordered by relative path, the same case-insensitive comparator the scan uses, so the
        // initial Asset order is a property of the importer rather than of whatever order the
        // caller happened to hand it.
        val supported = inFolder.filter { folderAsTaskSupported(it.name) }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, ScannedFile::relativePath)
                    .thenBy(ScannedFile::relativePath)
            )
        inFolder.filterNot { folderAsTaskSupported(it.name) }.forEach {
            notices += ImportNotice.UnsupportedFile(childRelativePath(importRoot, it.relativePath))
        }

        if (supported.isEmpty()) {
            notices += ImportNotice.EmptyFolder(childRelativePath(importRoot, folder))
        } else {
            tasks += PlannedTask(
                groupPath = childRelativePath(importRoot, folder),
                displayName = folder,
                assets = supported.mapIndexed { index, file ->
                    plannedAsset(importRoot, file, index)
                }
            )
        }
    }

    return ImportPlan(
        tasks = tasks.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, PlannedTask::groupPath)
                .thenBy(PlannedTask::groupPath)
        ),
        notices = notices
    )
}

/**
 * The supported-content policy for folder-as-Task: picture and video from [MediaKind], PDF by
 * extension. Everything else — `rubric.txt` — is skipped and reported. Admission is not eligibility:
 * a PDF is imported here and simply not reviewable until a viewer ships.
 */
fun folderAsTaskSupported(name: String): Boolean {
    val kind = mediaKindOf(name)
    return kind == MediaKind.PICTURE || kind == MediaKind.VIDEO || fileExtension(name) == "pdf"
}

private fun fileTask(importRoot: String, file: ScannedFile): PlannedTask = PlannedTask(
    groupPath = childRelativePath(importRoot, file.relativePath),
    displayName = file.name,
    assets = listOf(plannedAsset(importRoot, file, 0))
)

private fun plannedAsset(importRoot: String, file: ScannedFile, sortOrder: Int): PlannedAsset =
    PlannedAsset(
        rootRelativePath = childRelativePath(importRoot, file.relativePath),
        name = file.name,
        uri = file.uri,
        sizeBytes = file.sizeBytes,
        lastModified = file.lastModified,
        sortOrder = sortOrder
    )
