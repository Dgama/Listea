package com.example.listea.data

/**
 * The Task-level facts the v0.6.0 payload needs, read in one query.
 *
 * [displayName] is null for a Task no Todo owns — a manual item, or a legacy Task that predates the
 * catalog. The payload falls back to the title on the row that reached the Task; an empty string
 * here would be a name the user never gave.
 *
 * [assetControls] is null for the same reason: with no import there is no attachment, which the
 * payload renders as an empty `templates` array rather than as an error.
 */
data class PayloadTaskRow(
    val taskId: String,
    val publicId: String,
    val displayName: String?,
    val assetControls: String?
)

/**
 * One Asset as the payload sees it: its name, where its file sits, and the pair's decisions.
 *
 * [relativePath] is the Asset's own stored path, relative to the Upstream's linked root. The payload
 * derives its folder from this and never from the List or the round, which is what makes the value
 * independent of how the Task was packaged.
 *
 * [selections] is null only for an Asset whose state row is missing, which the write paths do not
 * produce; the assembly treats it as no decisions rather than inventing some.
 */
data class PayloadAssetRow(
    val taskId: String,
    val assetId: String,
    val publicId: String,
    val relativePath: String?,
    val sourceMissing: Boolean,
    val sortOrder: Int,
    val selections: String?
)
