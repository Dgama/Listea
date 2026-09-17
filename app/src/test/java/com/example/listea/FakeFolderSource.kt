package com.example.listea

import android.net.Uri
import com.example.listea.data.ScannedFile

/**
 * A folder source with no SAF behind it.
 *
 * Robolectric ships no DocumentsProvider, so the real one returns null for everything: the scan
 * fails first and the ViewModel reports an unreadable folder rather than whatever the test meant to
 * reach. This stands in for the device — a folder with what the test says is in it — and it is the
 * one seam that makes the ViewModel testable at the level its decisions are actually made.
 *
 * [rootName] is what the provider says about the picked folder itself, which is a different question
 * from its path: the grant root's relative path is empty, so a folder can be named only by asking.
 * Null is a folder it will not name. A folder deeper than the root is named after its own last
 * segment, as a real provider names it.
 */
internal class FakeFolderSource(
    private val files: List<FolderFile> = emptyList(),
    private val directories: List<String> = emptyList(),
    private val rootName: String? = null
) : FolderSource {

    override fun readDirectFiles(rootUri: String, folderPath: String): List<FolderFile> = files

    override fun folderName(rootUri: String, folderPath: String): String? =
        if (folderPath.isEmpty()) rootName else folderPath.substringAfterLast('/')

    override fun scanTree(rootUri: String, folderPath: String): FolderSnapshot = FolderSnapshot(
        files = files.map {
            ScannedFile(
                name = it.name,
                relativePath = it.rootRelativePath,
                uri = it.uri,
                sizeBytes = it.sizeBytes,
                lastModified = it.lastModified
            )
        },
        directories = directories
    )

    override fun scanFiles(folderUri: Uri): List<ScannedFile> =
        scanTree(folderUri.toString(), "").files
}
