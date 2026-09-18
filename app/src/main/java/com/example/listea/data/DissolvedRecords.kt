package com.example.listea.data

/**
 * One file's record, as a dissolved history keeps it: the path the file was at, and what was marked
 * on it.
 *
 * A path and not an Asset id, which is the whole of why this type exists. Ids are minted per import
 * — `UUID.randomUUID().toString()` — so the Asset a record was made against is gone by the time a
 * recovery runs, and its id would match nothing. The path is what the import itself matches on to
 * keep an Asset's identity across a re-run of the same folder, so it is the one name both sides of a
 * dissolution can agree on.
 */
data class DissolvedFileRecord(
    val relativePath: String,
    val decisions: AssetDecisions
)

/**
 * The version of the encoding below, stored beside every payload that uses it.
 *
 * The move `review_events` makes with `templateId` and `templateVersion`: a payload is never read
 * without knowing what wrote it. A reader that does not recognise a version says so instead of
 * guessing at a shape a later build invented — see [decodeDissolvedRecords].
 */
const val DISSOLVED_RECORDS_VERSION = 1

/**
 * A dissolved Task's files, as one string.
 *
 * One line per file, three tab-separated fields:
 *
 * ```
 * <escaped relativePath> \t <1|0 isFavorite> \t <encodedActionIds>
 * ```
 *
 * Lines are joined by `\n`, and `''` means the Task had no file to record — which is an answer and
 * not a gap: a manual item has no Asset, so it has no pair and nothing to carry.
 *
 * The action ids use [encodeActionIds], the same encoding `task_asset_state.customActions` holds, so
 * a record inside a payload is spelled exactly as the record it came from.
 *
 * **Why the path is escaped.** A path is a user's filename, and a filename may contain a tab, a
 * newline, or a `%`. Left alone, the first two would break the field and line separators — a file
 * appearing as two records, or as a record with fields shifted — and the third would make the
 * escaping itself ambiguous. All three are encoded, and [unescapeDissolvedPath] decodes them in one
 * left-to-right pass so a `%25` can never be mistaken for the start of a `%0A`.
 */
fun encodeDissolvedRecords(records: List<DissolvedFileRecord>): String =
    records.joinToString("\n") { record ->
        val favorite = if (record.decisions.isFavorite) "1" else "0"
        "${escapeDissolvedPath(record.relativePath)}\t$favorite\t" +
            encodeActionIds(record.decisions.customActions)
    }

/**
 * The files a payload holds, or `null` when this build cannot read it.
 *
 * **A version it does not know is `null`, never a guess**, and so is a payload whose lines do not
 * have the shape this file describes. The caller restores the Task's completion and no marks, which
 * is a partial restore rather than a wrong one — and the same answer a history written before this
 * encoding existed gets.
 *
 * `''` is an empty list and not `null`: a Task with no file is a fact the snapshot recorded, and the
 * two must not be conflated on the way back.
 */
fun decodeDissolvedRecords(encoded: String, version: Int): List<DissolvedFileRecord>? {
    if (version != DISSOLVED_RECORDS_VERSION) return null
    if (encoded.isEmpty()) return emptyList()

    return encoded.split("\n").map { line ->
        val fields = line.split("\t")
        // Strictly three. A line this file did not write is a payload this build does not
        // understand, and reading the fields it happens to have would be inventing the rest.
        if (fields.size != 3) return null
        DissolvedFileRecord(
            relativePath = unescapeDissolvedPath(fields[0]),
            decisions = AssetDecisions(
                isFavorite = fields[1] == "1",
                customActions = decodeActionIds(fields[2])
            )
        )
    }
}

/** `%` first, or the escapes this function writes would themselves be escaped. */
private fun escapeDissolvedPath(path: String): String = buildString(path.length) {
    for (character in path) {
        when (character) {
            '%' -> append("%25")
            '\n' -> append("%0A")
            '\t' -> append("%09")
            else -> append(character)
        }
    }
}

/**
 * One pass, left to right, so decoding is the exact inverse of the encoding above.
 *
 * A `%` that starts nothing this file wrote decodes to itself rather than throwing: a filename is
 * the user's, and one containing a stray `%` is not a reason for their history to be unreadable.
 */
private fun unescapeDissolvedPath(escaped: String): String {
    if ('%' !in escaped) return escaped

    val out = StringBuilder(escaped.length)
    var i = 0
    while (i < escaped.length) {
        val character = escaped[i]
        val decoded = if (character == '%' && i + 3 <= escaped.length) {
            when (escaped.substring(i + 1, i + 3)) {
                "25" -> '%'
                "0A" -> '\n'
                "09" -> '\t'
                else -> null
            }
        } else {
            null
        }
        if (decoded == null) {
            out.append(character)
            i++
        } else {
            out.append(decoded)
            i += 3
        }
    }
    return out.toString()
}
