package com.example.listea.data

import java.util.UUID

/**
 * Writes one item of the shape a v0.1 List could hold and a v0.2 one cannot make: a Task with no
 * Asset, its own state row, a name in `legacy_manual_tasks`, and a `list_items` row carrying no
 * source path because there is no file for it to point at.
 *
 * It is a fixture rather than a helper on [ListsDao] because the write path it stands for was
 * deleted. Manual-item creation is gone; what is left is items the migration carried across, and a
 * test that needs one has to be holding something a migration left behind. Writing the four rows
 * out is the only honest way to say that — and it is the same four inserts `ListeaDatabase` makes,
 * so a cleanup path that stopped understanding them would fail here rather than quietly find
 * nothing to delete.
 *
 * Everything the caller does not name is left at the value the migration would have written:
 * [taskId] and the public id are minted, [isCompleted] starts false, and `sortOrder` appends. The
 * dead decision columns on `list_items` are written false and empty exactly as a row from the
 * migration carries them.
 *
 * Returns the [taskId], because the Task is the thing cleanup deletes and the thing a test about
 * it has to name.
 */
internal fun ListeaDatabase.writeLegacyManualItem(
    listId: Long,
    title: String,
    now: Long,
    isCompleted: Boolean = false,
    taskId: String = UUID.randomUUID().toString()
): String {
    val items = openHelper.writableDatabase
    items.execSQL(
        "INSERT INTO tasks (id, createdAt) VALUES (?, ?)",
        arrayOf<Any?>(taskId, now)
    )
    items.execSQL(
        "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt, " +
            "completionSource) VALUES (?, ?, 0, '', ?, '')",
        arrayOf<Any?>(taskId, if (isCompleted) 1 else 0, now)
    )
    // Since schema 33 a Task holds 1..N parts. A manual item has no Prompt, so it gets the one
    // part the migration gives every pre-Package Task: a legacy part with nothing attached.
    items.execSQL(
        "INSERT INTO task_parts (taskId, partKey, position, origin, promptKey, required, " +
            "present, availability, updatedAt) VALUES (?, ?, 0, 'legacy', NULL, 1, 1, NULL, ?)",
        arrayOf<Any?>(taskId, "legacy:$taskId", now)
    )
    items.execSQL(
        "INSERT INTO task_part_state (taskId, partKey, response, answeredAt, revision, updatedAt) " +
            "VALUES (?, ?, '', NULL, NULL, ?)",
        arrayOf<Any?>(taskId, "legacy:$taskId", now)
    )
    items.execSQL("INSERT INTO legacy_manual_tasks (taskId) VALUES (?)", arrayOf<Any?>(taskId))
    items.execSQL(
        "INSERT INTO list_items (" +
            "publicId, listId, taskId, title, isCompleted, sortOrder, createdAt, " +
            "sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
            "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions) " +
            "VALUES (?, ?, ?, ?, 0, " +
            "(SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM list_items WHERE listId = ?), ?, " +
            "NULL, NULL, NULL, 0, NULL, NULL, 0, '')",
        arrayOf<Any?>(
            newItemPublicId(manualItemIdentity(listId, title), now),
            listId,
            taskId,
            title,
            listId,
            now
        )
    )
    return taskId
}
