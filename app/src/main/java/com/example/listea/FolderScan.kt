package com.example.listea

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.listea.data.ScannedFile

/** Guards against a pathological provider; real folder trees are nowhere near this deep. */
private const val MAX_DEPTH = 64

/**
 * Path of the directory currently being browsed, relative to the selected SAF root.
 * The root itself is "".
 */
fun relativePathOf(stack: List<DirRef>): String = stack.drop(1).joinToString("/") { it.name }

fun childRelativePath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** True when [a] is the same folder as [b] or one of its ancestors. "" is the root. */
fun isAncestorOrSame(a: String, b: String): Boolean =
    a.isEmpty() || a == b || b.startsWith("$a/")

/**
 * True when one scope contains the other — the test "may these two folder scopes stand together".
 *
 * It no longer settles ownership, and it is no longer what makes two imports compatible. Import
 * roots may nest: a Todo over an Upstream and another over a folder inside it are two intentions,
 * and the Tasks they produce are separate even where they read the same files, so nothing refuses
 * a second root over ground a first one covers. The one caller left is the legacy folder-backed
 * List path, which still reads a nested scope as a List to be replaced rather than one to keep
 * beside — see `FolderListRequest`.
 */
fun scopesOverlap(a: String, b: String): Boolean = isAncestorOrSame(a, b) || isAncestorOrSame(b, a)

/**
 * Walks [relativePath] down from the SAF root to the folder a list is linked to.
 * Blocking: call on an IO dispatcher. Returns null if the root grant is gone or any segment of
 * the path no longer exists, so a re-sync can abort before writing anything.
 */
fun resolveLinkedFolder(context: Context, rootUri: Uri, relativePath: String): DocumentFile? =
    runCatching {
        if (!hasPersistedReadAccess(context, rootUri)) return@runCatching null
        var folder = DocumentFile.fromTreeUri(context, rootUri) ?: return@runCatching null
        if (!folder.isDirectory || !folder.canRead()) return@runCatching null
        if (relativePath.isNotEmpty()) {
            for (segment in relativePath.split("/")) {
                folder = folder.listFiles()
                    .firstOrNull { it.isDirectory && it.name == segment }
                    ?: return@runCatching null
            }
        }
        folder
    }.getOrNull()

/**
 * Human-readable source location, e.g. "Listea / 2026-07-11 / bilibili".
 * Blocking: call on an IO dispatcher. Never exposes a content:// URI.
 */
fun sourceFolderLabel(context: Context, rootUri: Uri, relativePath: String): String {
    val rootName = runCatching { DocumentFile.fromTreeUri(context, rootUri)?.name }.getOrNull()
    return buildList {
        add(rootName ?: "Selected folder")
        if (relativePath.isNotEmpty()) addAll(relativePath.split("/"))
    }.joinToString(" / ")
}

/**
 * What one recursive walk of a folder saw: the files beneath it, and the directories beneath it.
 *
 * Both lists are root-relative and in the same deterministic, case-insensitive path order. The
 * directories are what an importer needs to see an immediate child folder that holds no files at
 * all — a fact a file-only scan cannot distinguish from "the folder was never looked at".
 */
data class FolderSnapshot(
    val files: List<ScannedFile>,
    val directories: List<String>
)

/**
 * Every file *and* directory in the subtree rooted at [folderUri], one depth-first walk. Blocking:
 * call on an IO dispatcher.
 *
 * The walk is the same one [scanFolderFiles] has always made; it now records the directories it was
 * visiting anyway rather than throwing them away. Children only ever come from
 * [DocumentFile.listFiles] on a folder already inside the subtree, so the walk cannot escape it,
 * and each child is visited exactly once so files cannot be duplicated. Returns null if any part of
 * the scan fails, so the caller can abort before writing anything.
 */
fun scanFolderTree(context: Context, folderUri: Uri): FolderSnapshot? = runCatching {
    val root = DocumentFile.fromTreeUri(context, folderUri)
    if (root == null || !root.isDirectory || !root.canRead()) return@runCatching null

    val files = mutableListOf<ScannedFile>()
    val directories = mutableListOf<String>()
    val pending = ArrayDeque<Pair<DocumentFile, String>>()
    pending.addLast(root to "")

    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeLast()
        for (child in directory.listFiles()) {
            val name = child.name ?: continue
            val path = childRelativePath(prefix, name)
            if (child.isDirectory) {
                check(path.count { it == '/' } < MAX_DEPTH) { "Folder nesting too deep" }
                directories += path
                pending.addLast(child to path)
            } else {
                files += ScannedFile(
                    name = name,
                    relativePath = path,
                    uri = child.uri.toString(),
                    sizeBytes = child.length(),
                    lastModified = child.lastModified().takeIf { it > 0 }
                )
            }
        }
    }

    FolderSnapshot(
        files = files.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ScannedFile::relativePath)
                .thenBy(ScannedFile::relativePath)
        ),
        directories = directories.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, { path: String -> path })
                .thenBy { path: String -> path }
        )
    )
}.getOrNull()

/**
 * Every file in the subtree rooted at [folderUri], depth first. Blocking: call on an IO dispatcher.
 *
 * Expressed on top of [scanFolderTree] so the two cannot walk a folder differently; the file-only
 * view is the tree walk with its directories dropped. Returns null if any part of the scan fails,
 * so the caller can abort before writing anything.
 */
fun scanFolderFiles(context: Context, folderUri: Uri): List<ScannedFile>? =
    scanFolderTree(context, folderUri)?.files

/**
 * What a whole subtree adds up to.
 *
 * [folders] counts the directories *below* the folder being measured; the folder itself is not one
 * of them, so a root holding two subfolders reports two rather than three.
 */
data class FolderStats(val folders: Int, val files: Int, val totalBytes: Long) {
    /** "12 folders · 348 files · 1.4 GB": the one line the Storage card reports. */
    val summary: String
        get() = listOf(
            "$folders folder" + if (folders == 1) "" else "s",
            "$files file" + if (files == 1) "" else "s",
            formatSize(totalBytes)
        ).joinToString(" · ")
}

/**
 * Totals for the subtree rooted at [folderUri], walked exactly the way [scanFolderFiles] walks it,
 * so the numbers the Storage card reports describe the same tree a List would be built from.
 * Blocking: call on an IO dispatcher.
 *
 * Null when the folder cannot be read at all, which the caller says out loud — reporting an
 * unreadable folder as an empty one would be a lie in the direction that matters.
 */
fun scanFolderStats(context: Context, folderUri: Uri): FolderStats? = runCatching {
    val root = DocumentFile.fromTreeUri(context, folderUri)
    if (root == null || !root.isDirectory || !root.canRead()) return@runCatching null

    var folders = 0
    var files = 0
    var bytes = 0L

    val pending = ArrayDeque<Pair<DocumentFile, Int>>()
    pending.addLast(root to 0)

    while (pending.isNotEmpty()) {
        val (directory, depth) = pending.removeLast()
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                check(depth < MAX_DEPTH) { "Folder nesting too deep" }
                folders++
                pending.addLast(child to depth + 1)
            } else {
                files++
                bytes += child.length()
            }
        }
    }

    FolderStats(folders = folders, files = files, totalBytes = bytes)
}.getOrNull()
