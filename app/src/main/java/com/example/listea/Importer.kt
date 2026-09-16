package com.example.listea

import com.example.listea.data.ScannedFile

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
