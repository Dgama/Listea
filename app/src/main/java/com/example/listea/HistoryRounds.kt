package com.example.listea

import com.example.listea.data.ExportRecordSummary

/**
 * One delivery round, as History reads it: the exports it produced and what became of them together.
 *
 * **Why group at all.** The storage shape is one row per export, because one row has one method, one
 * address and one verdict. But a round is the thing the user did, and a round over a List whose
 * Todos disagree produces several exports in the same breath. Read row by row, that round looks
 * like several unrelated things that happened a second apart; read as a round, it answers the
 * question people actually have — *did that review go out?* — once.
 *
 * [key] is the round id the delivery minted, or, for a row written before rounds existed, the row
 * itself. The legacy case is deliberately a round of one rather than a shared *unknown* round: two
 * old rows are not known to belong together, and inventing a bond between them would say they did.
 *
 * [at] is the newest time in the round, which is what a resend moves: sending a kept payload again
 * is news about the round, so the round rises to the top of History where the user just acted.
 * [event] and [listTitle] come from that same newest row; every row of a round carries them, so the
 * choice only matters if a future version ever disagrees with itself.
 */
data class HistoryRound(
    val key: String,
    val records: List<ExportRecordSummary>,
    val at: Long,
    val event: String,
    val listTitle: String,
    val exportCount: Int,
    val outcome: RoundOutcome
)

/**
 * What a whole round did, in one word, for the collapsed card.
 *
 * [Unknown] is not a failure and not a success: it is a row (or a whole round) whose outcome string
 * this build cannot name — a record from a newer version, or a corrupted one. It reads neutrally
 * and is kept out of the counts, the same failing-open rule the per-row tone uses. A row with a
 * known outcome beside one with an unknown outcome still counts as what the known one says.
 */
enum class RoundOutcome(val label: String) {
    /** At least one export left the device, and none failed. Written counts, as it does per row. */
    Delivered("Delivered"),

    /** Something left and something failed. The round went out, but not all of it. */
    Partial("Partly delivered"),

    /** Every known verdict is a failure, or a text file that could not be written. */
    Failed("Failed"),

    /**
     * The round was kept but nothing was attempted: declined, deferred, switched off, empty.
     * Distinct from [Failed] because there is a payload waiting in History and nothing to go and fix.
     */
    NotSent("Not sent"),

    /** No row carries an outcome this build can read. Neutral. */
    Unknown("Outcome not recorded")
}

/** The id that ties a record to the exports the same delivery produced. */
fun roundKey(record: ExportRecordSummary): String =
    record.roundId.ifEmpty { "row:${record.id}" }

/**
 * Groups [records] into rounds, newest round first, rows within a round newest first.
 *
 * The input order is not trusted: resending one row of an old round moves that row's time, and the
 * round must move with it. Ties break on row id so the result is stable, and insertion order of the
 * group map preserves the sorted order without a second comparison.
 */
fun historyRounds(records: List<ExportRecordSummary>): List<HistoryRound> {
    val newestFirst = records.sortedWith(
        compareByDescending<ExportRecordSummary> { it.at }.thenByDescending { it.id }
    )
    val grouped = LinkedHashMap<String, MutableList<ExportRecordSummary>>()
    for (record in newestFirst) {
        grouped.getOrPut(roundKey(record)) { mutableListOf() } += record
    }
    return grouped.map { (key, rows) ->
        val lead = rows.first()
        HistoryRound(
            key = key,
            records = rows,
            at = lead.at,
            event = lead.event,
            listTitle = lead.listTitle,
            exportCount = rows.size,
            outcome = roundOutcome(rows)
        )
    }
}

/**
 * Every known verdict folded into one.
 *
 * Delivered and Written are the same fact at this altitude — the round left the device — so one of
 * either beside failures is [RoundOutcome.Partial]; failures alone are [Failed]; anything else
 * known (switched off, declined, deferred, empty) is [NotSent]. A round with *both* a delivery and
 * a not-sent row is [Delivered]: something went, and the row that did not was a choice or an
 * emptiness, not a break — the child rows still say which.
 */
private fun roundOutcome(records: List<ExportRecordSummary>): RoundOutcome {
    val known = records.mapNotNull { outcomeOf(it.outcome) }
    if (known.isEmpty()) return RoundOutcome.Unknown
    val delivered = known.count {
        it == NoticeOutcome.SENT || it == NoticeOutcome.WRITTEN
    }
    val failed = known.count { it == NoticeOutcome.FAILED }
    return when {
        delivered > 0 && failed > 0 -> RoundOutcome.Partial
        failed > 0 -> RoundOutcome.Failed
        delivered > 0 -> RoundOutcome.Delivered
        else -> RoundOutcome.NotSent
    }
}

/** Tolerant by name, because the column is a string and a newer version may have written it. */
private fun outcomeOf(stored: String): NoticeOutcome? =
    NoticeOutcome.entries.firstOrNull { it.name == stored.trim() }
