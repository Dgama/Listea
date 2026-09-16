package com.example.listea.data

/**
 * The Task id of one file, minting the Task if nothing has yet needed one.
 *
 * Test-only. Production writes are addressed by the Task id an item already carries; a test that
 * wants to write about "this file" without first walking a List or a folder review resolves it the
 * way a folder review does, so the write can be addressed by Task id like production writes are.
 */
internal suspend fun ListsDao.taskIdOf(
    rootUri: String,
    relativePath: String,
    now: Long = 1000
): String = ensureTasksForFiles(rootUri, listOf(relativePath), now).single().taskId
