package com.example.listea.data

import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan

/**
 * Both halves of one file's pair: the Task over it, and the Asset it is presented as.
 *
 * The pair is what a write is addressed by since v0.5.1 — a tag belongs to one file under one
 * Task's control — so a fixture that says "mark this file" needs both ids, and importing it the way
 * production does is what makes the Asset exist to name.
 */
internal data class FilePair(val taskId: String, val assetId: String)

/**
 * The pair of one file, minted through the import that is now the only way a Task comes into
 * existence.
 *
 * Test-only. Production writes are addressed by the ids an item already carries; a test that wants
 * to write about "this file" without first walking a List or a folder review imports it the way
 * production does, so the write can be addressed the way production writes are.
 */
internal suspend fun ListsDao.pairOf(
    rootUri: String,
    relativePath: String,
    now: Long = 1000
): FilePair {
    // Reuse is asked for here rather than assumed from registration, because registration stopped
    // reusing anything: it mints a Source per call, on purpose, since a second import over a folder
    // is a second intention. A fixture that minted one per file would put each file in a Todo of
    // its own, which is a shape no caller of this helper is trying to set up.
    val source = sourcesAt(rootUri, "").firstOrNull()
        ?: registerSource(rootUri, "", "", ImporterKind.FILE_AS_TASK, keepTree = true, now = now)
    runImport(
        source.id,
        // The file's own path is the scanned coverage, so importing one file never flags a sibling
        // missing: the coverage bounds the only destructive-looking thing a run does.
        relativePath,
        plan(
            ImporterKind.FILE_AS_TASK,
            "",
            FolderSnapshot(
                listOf(
                    ScannedFile(
                        name = relativePath.substringAfterLast('/'),
                        relativePath = relativePath,
                        uri = "$rootUri/$relativePath"
                    )
                ),
                emptyList()
            )
        ),
        now
    )
    val row = getCatalogTasks(source.id).first { it.groupPath == relativePath }
    // The Asset the row just resolved is the file's own: this helper imports one file, so the row
    // presents exactly it.
    return FilePair(row.taskId, row.assets.single().assetId)
}

/** The Task id of one file, minted the way [pairOf] mints it. */
internal suspend fun ListsDao.taskIdOf(
    rootUri: String,
    relativePath: String,
    now: Long = 1000
): String = pairOf(rootUri, relativePath, now).taskId

/** The Asset id of one file — the other half of the address a tag is written to. */
internal suspend fun ListsDao.assetIdOf(
    rootUri: String,
    relativePath: String,
    now: Long = 1000
): String = pairOf(rootUri, relativePath, now).assetId

/**
 * The Task ids of several files, in the order asked for. Test-only, and the batch twin of
 * [taskIdOf]; it exists so a test can import a folder's worth of files in one pass, the way a
 * folder round used to resolve them before minting was removed.
 */
internal suspend fun ListsDao.taskIdsOf(
    rootUri: String,
    relativePaths: List<String>,
    now: Long = 1000
): List<FileTaskId> = relativePaths.map { FileTaskId(it, taskIdOf(rootUri, it, now)) }
