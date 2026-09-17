package com.example.listea

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.example.listea.data.ScannedFile

/**
 * Every read of the user's folders, behind one seam.
 *
 * There are exactly three shapes of read, and they are three methods because the callers genuinely
 * want three different things: a browsed folder's own files, a whole subtree for an import plan, and
 * a List's linked folder rescanned. All three go through SAF and none of them writes.
 *
 * The seam exists because a ViewModel test has no way to give SAF a folder — Robolectric ships no
 * DocumentsProvider, so `hasPersistedReadAccess` sees an empty grant list and every read returns
 * null. Without this, the consent gate can only be tested one level below where it lives, at the
 * write boundary, which is exactly the gap the v0.2.5 acceptance review named (N2): a gate tested
 * against the DAO proves the DAO writes nothing, not that the ViewModel asks first.
 */
interface FolderSource {

    /** The direct files of a browsed folder. Null when the folder cannot be read. */
    fun readDirectFiles(rootUri: String, folderPath: String): List<FolderFile>?

    /**
     * The folder's own name, for naming a Source after it. Null when it cannot be read.
     *
     * A fourth read rather than a field on one of the others, because nothing else wants it: an
     * import plan works in relative paths, and a listing names children rather than the folder they
     * are in. It exists so that adding an Upstream can say "2026-09-11" instead of making the user
     * type their own folder's name into the Create Tasks dialog to replace a placeholder.
     */
    fun folderName(rootUri: String, folderPath: String): String?

    /** The whole subtree of a folder, for an import plan. Null when the folder cannot be read. */
    fun scanTree(rootUri: String, folderPath: String): FolderSnapshot?

    /** The recursive files of a folder already resolved to a SAF document. Null when unreadable. */
    fun scanFiles(folderUri: Uri): List<ScannedFile>?
}

/** The real thing: [resolveLinkedFolder] and the walkers, over the device's actual SAF grants. */
class SafFolderSource(private val context: Context) : FolderSource {

    override fun readDirectFiles(rootUri: String, folderPath: String): List<FolderFile>? {
        val folder = resolveLinkedFolder(context, rootUri.toUri(), folderPath) ?: return null
        val contents = readFolder(context, folder.uri) ?: return null
        return folderFilesOf(folderPath, contents)
    }

    override fun folderName(rootUri: String, folderPath: String): String? =
        resolveLinkedFolder(context, rootUri.toUri(), folderPath)
            ?.let { readFolder(context, it.uri)?.folderName }
            ?.takeIf { it.isNotBlank() }

    override fun scanTree(rootUri: String, folderPath: String): FolderSnapshot? =
        resolveLinkedFolder(context, rootUri.toUri(), folderPath)
            ?.let { scanFolderTree(context, it.uri) }

    override fun scanFiles(folderUri: Uri): List<ScannedFile>? = scanFolderFiles(context, folderUri)
}
