package com.example.listea

import com.example.listea.data.ExportRecordSummary
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How History folds rows back into rounds.
 *
 * The grouping is pure, and that is the point: a round's identity and its aggregate verdict are
 * rules about records, not drawing code, and the device walk's *did that review go out?* question
 * must be answerable the same way in a test. The cases below pin the three things that can go
 * wrong in the fold — legacy rows being invented into a shared round, a resend not moving its
 * round, and an unknown outcome dragging a known verdict down with it.
 */
class HistoryRoundsTest {

    @Test
    fun `legacy rows with no round id are each a round of one`() {
        val rounds = historyRounds(listOf(record(1), record(2)))

        assertEquals(2, rounds.size)
        assertEquals(listOf("row:2", "row:1"), rounds.map { it.key })
        assertEquals(listOf(1, 1), rounds.map { it.exportCount })
        assertEquals(listOf(2L, 1L), rounds.map { it.records.single().id })
    }

    @Test
    fun `rows sharing a round id are one round, whatever their method`() {
        val rounds = historyRounds(
            listOf(
                record(1, roundId = "r", kind = ExportKind.WEBHOOK.key),
                record(2, roundId = "r", kind = ExportKind.TXT.key),
                record(3, roundId = "s")
            )
        )

        assertEquals(listOf("s", "r"), rounds.map { it.key })
        val shared = rounds.single { it.key == "r" }
        assertEquals(2, shared.exportCount)
        assertEquals(listOf(2L, 1L), shared.records.map { it.id })
    }

    @Test
    fun `a resent row lifts its round to the top`() {
        val first = record(1, roundId = "a", at = 100)
        val second = record(2, roundId = "b", at = 200)
        assertEquals(listOf("b", "a"), historyRounds(listOf(first, second)).map { it.key })

        // Resend rewrites `at` on one row; the round it belongs to is where the user just acted.
        val resent = first.copy(at = 300)
        val rounds = historyRounds(listOf(resent, second))
        assertEquals(listOf("a", "b"), rounds.map { it.key })
        assertEquals(listOf(300L, 200L), rounds.map { it.at })
    }

    @Test
    fun `a round's time is its newest row`() {
        val round = historyRounds(
            listOf(record(1, roundId = "r", at = 100), record(2, roundId = "r", at = 150))
        ).single()

        assertEquals(150L, round.at)
        assertEquals(listOf(2L, 1L), round.records.map { it.id })
    }

    @Test
    fun `every known outcome aggregates`() {
        assertEquals(RoundOutcome.Delivered, roundOf(NoticeOutcome.SENT))
        assertEquals(RoundOutcome.Delivered, roundOf(NoticeOutcome.WRITTEN))
        assertEquals(RoundOutcome.Failed, roundOf(NoticeOutcome.FAILED))
        assertEquals(
            RoundOutcome.Partial,
            roundOf(NoticeOutcome.SENT, NoticeOutcome.FAILED)
        )
        assertEquals(
            RoundOutcome.NotSent,
            roundOf(NoticeOutcome.DISABLED, NoticeOutcome.DECLINED, NoticeOutcome.DEFERRED)
        )
        assertEquals(
            RoundOutcome.NotSent,
            roundOf(NoticeOutcome.EMPTY)
        )
        // A delivery beside a choice that did not send is still a delivery: the child rows say
        // which one was which, and the round did go out.
        assertEquals(
            RoundOutcome.Delivered,
            roundOf(NoticeOutcome.WRITTEN, NoticeOutcome.DECLINED)
        )
    }

    @Test
    fun `an unknown outcome reads neutrally and does not outvote a known one`() {
        val unknown = "SOMETHING_FROM_A_NEWER_BUILD"
        assertEquals(RoundOutcome.Unknown, roundOf(unknown))
        assertEquals(RoundOutcome.Delivered, roundOf(NoticeOutcome.SENT.name, unknown))
        assertEquals(RoundOutcome.Failed, roundOf(NoticeOutcome.FAILED.name, unknown))
        assertEquals(RoundOutcome.NotSent, roundOf(NoticeOutcome.DISABLED.name, unknown))
    }

    @Test
    fun `the header counts exports and rounds separately only when they differ`() {
        assertEquals("1 export", historyCountLabel(1, 1))
        assertEquals("2 exports · 1 round", historyCountLabel(2, 1))
        assertEquals("3 exports · 2 rounds", historyCountLabel(3, 2))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun roundOf(vararg outcomes: String): RoundOutcome =
        historyRounds(
            outcomes.mapIndexed { index, outcome ->
                record(index + 1L, roundId = "r", outcome = outcome, at = index.toLong())
            }
        ).single().outcome

    private fun roundOf(vararg outcomes: NoticeOutcome): RoundOutcome =
        roundOf(*outcomes.map { it.name }.toTypedArray())

    private fun record(
        id: Long,
        roundId: String = "",
        outcome: String = NoticeOutcome.SENT.name,
        at: Long = id,
        event: String = EVENT_LIST_COMPLETED,
        listTitle: String = "Demo",
        kind: String = ExportKind.WEBHOOK.key,
        webhookUrl: String = "https://example.com/hook"
    ) = ExportRecordSummary(
        id = id,
        event = event,
        listTitle = listTitle,
        itemCount = 3,
        at = at,
        outcome = outcome,
        detail = "Success · HTTP 200",
        kind = kind,
        webhookUrl = webhookUrl,
        roundId = roundId
    )
}
