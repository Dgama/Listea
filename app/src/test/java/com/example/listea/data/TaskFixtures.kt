package com.example.listea.data

import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan

/**
 * The Task id of one file, minted through the import that is now the only way a Task comes into
 * existence.
 *
 * Test-only. Production writes are addressed by the Task id an item already carries; a test that
 * wants to write about "this file" without first walking a List or a folder review imports it the
 * way production does, so the write can be addressed by Task id like production writes are.
 */
internal suspend fun ListsDao.taskIdOf(
    rootUri: String,
    relativePath: String,
    now: Long = 1000
): String {
    val source = when (val result = registerSource(rootUri, "", "", ImporterKind.FILE_AS_TASK, now)) {
        is RegisterSourceResult.Registered -> result.source
        is RegisterSourceResult.Overlaps -> result.covering
    }
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
    return getCatalogTasks(source.id).first { it.groupPath == relativePath }.taskId
}

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
