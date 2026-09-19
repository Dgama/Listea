package com.example.listea

import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the webhook history shows about a delivery it kept, and what the two dialogs around a
 * delivery say about one.
 *
 * The keeping itself is a database write and is exercised on device; these pin the strings, which
 * are the part that has to agree across three places — the question asked before a send, the
 * result shown after it, and the record left behind. A record whose wording reads differently
 * from the notice that produced it would look like a second, different event.
 */
class WebhookHistoryTest {

    private fun notice(
        event: String,
        outcome: NoticeOutcome,
        defaultWebhook: Boolean = false,
        kind: ExportKind = ExportKind.WEBHOOK
    ) = WebhookNotice(
            event = event,
            listTitle = "2026-07-11",
            kind = kind,
            host = "example.com",
            itemCount = 4,
            outcome = outcome,
            httpCode = if (outcome == NoticeOutcome.SENT) 200 else 500,
            error = null,
            defaultWebhook = defaultWebhook,
            at = 0
        )

    private fun confirmation(
        defaultWebhook: Boolean,
        event: String = EVENT_REVIEW_EXITED,
        listTitle: String = "2026-07-11"
    ) = WebhookConfirmation(
        event = event,
        listTitle = listTitle,
        host = "example.com",
        itemCount = 4,
        defaultWebhook = defaultWebhook
    )

    @Test
    fun `an event is named the same live and in the history`() {
        listOf(
            EVENT_LIST_COMPLETED,
            EVENT_EXPORT_TEST,
            EVENT_QUICK_REVIEW_COMPLETED,
            EVENT_REVIEW_EXITED
        ).forEach { event ->
            assertEquals(
                notice(event, NoticeOutcome.FAILED).eventLabel,
                webhookEventLabel(event)
            )
        }
    }

    @Test
    fun `an event from a future version is shown rather than swallowed`() {
        assertEquals("list.something.new", webhookEventLabel("list.something.new"))
    }

    @Test
    fun `the detail a record carries is the words the user was given`() {
        // A record's detail is the notice's outcome label verbatim, so these must stay readable
        // on their own, away from the dialog that first said them.
        assertEquals("Failed · HTTP 500", notice(EVENT_REVIEW_EXITED, NoticeOutcome.FAILED).outcomeLabel)
        // One sentence for both cases since v0.5.6: a List has no webhook of its own to switch
        // off, so a round that came through one was being told to go and change a control the
        // release had removed.
        listOf(true, false).forEach { defaultWebhook ->
            assertEquals(
                "Nothing was sent · the default webhook is switched off",
                notice(
                    EVENT_QUICK_REVIEW_COMPLETED,
                    NoticeOutcome.DISABLED,
                    defaultWebhook = defaultWebhook
                ).outcomeLabel
            )
        }
    }

    @Test
    fun `a successful delivery is kept in words of its own`() {
        // The history holds successes now, so "Sent" has to read as a verdict rather than as the
        // absence of a failure.
        val sent = notice(EVENT_LIST_COMPLETED, NoticeOutcome.SENT)

        assertEquals("Webhook sent", sent.headline)
        assertEquals("Success · HTTP 200", sent.outcomeLabel)
    }

    @Test
    fun `a declined delivery reads as a choice, not as a failure`() {
        val declined = notice(EVENT_REVIEW_EXITED, NoticeOutcome.DECLINED)

        assertEquals("Not sent", declined.headline)
        assertEquals("Nothing was sent · you chose not to send it", declined.outcomeLabel)
        // Nothing left the device, so it must not be counted as an attempt. That is a fact about
        // the *payload*, not about the record: since the second decline pass a declined round is
        // written against its List as `NOT_SENT`, which says the round exists and did not go
        // without claiming a delivery was tried. `attempted` still decides which of the two
        // sentences a *sender* gets, and a decline has none to give.
        assertFalse(declined.attempted)
    }

    /**
     * The app declining on the user's behalf says so in its own words.
     *
     * A List that completed while a round was open on another List is kept unsent and never shown —
     * the user was not there to be asked, and a dialog about one List over the review of another is
     * the interruption the whole branch exists to remove. Which makes the history the *only* place
     * this is ever read, and borrowing the decline's line there would tell the user they chose
     * something they were never offered. *Not sent* is shared with a decline, because that much is
     * the same fact; the reason underneath is not.
     */
    @Test
    fun `a round the app kept for the user names its own reason`() {
        val deferred = notice(EVENT_LIST_COMPLETED, NoticeOutcome.DEFERRED)

        assertEquals("Not sent", deferred.headline)
        assertEquals("Nothing was sent · you were in another review", deferred.outcomeLabel)
        assertFalse(deferred.attempted)
        assertFalse(deferred.succeeded)
        // And it is not the decline's sentence, which is the whole of the change.
        assertFalse(deferred.outcomeLabel == notice(EVENT_LIST_COMPLETED, NoticeOutcome.DECLINED).outcomeLabel)
    }

    /**
     * The defect this pins was found on the device, not here: a text export that could not be
     * written produced a dialog titled *Webhook not sent*, and a detail line naming the webhook
     * URL's host — for a round that was never going to open a socket. The mechanism is a fact the
     * round knows before it starts, so a notice that guesses it is a notice that lies about which
     * of the user's two configurations to go and look at.
     */
    @Test
    fun `a failed export names the mechanism that failed, not the one the app used to have`() {
        val txt = notice(EVENT_REVIEW_EXITED, NoticeOutcome.FAILED, kind = ExportKind.TXT)
        val hook = notice(EVENT_REVIEW_EXITED, NoticeOutcome.FAILED, kind = ExportKind.WEBHOOK)

        assertEquals("Txt not written", txt.headline)
        assertEquals("Webhook not sent", hook.headline)
    }

    @Test
    fun `a txt export that worked is described as a file, not as a delivery`() {
        val written = notice(EVENT_REVIEW_EXITED, NoticeOutcome.WRITTEN, kind = ExportKind.TXT)

        assertEquals("Txt written", written.headline)
        // No HTTP code on it, and none invented: a file has a name, which is what `location`
        // carries, and an outcome label that made one up would be reporting a status nobody sent.
        assertEquals("Success", written.outcomeLabel)
        assertTrue(written.succeeded)
    }

    /**
     * The hole v0.5.6 shipped, and the cheapest half of the fix the user chose.
     *
     * A round whose every Todo is set to export nothing used to return in silence: no file, no
     * POST, no record and no word. Quick Review's promise is that finishing a round *produces a
     * result*, so a round that produced none had to say so — otherwise it is indistinguishable
     * from one that exported and failed to report. It is not a failure and must not read as one,
     * and it is not an attempt, so it is not recorded against a list as a delivery.
     */
    @Test
    fun `a round with nothing configured to export says so rather than staying silent`() {
        val idle = notice(EVENT_QUICK_REVIEW_COMPLETED, NoticeOutcome.UNCONFIGURED)

        assertEquals("Nothing exported", idle.headline)
        assertEquals(
            "Nothing was sent · no Todo this round covers is set to export",
            idle.outcomeLabel
        )
        assertFalse(idle.attempted)
        assertFalse(idle.succeeded)
    }

    @Test
    fun `every outcome has something to say, including ones added later`() {
        NoticeOutcome.entries.forEach { outcome ->
            val described = notice(EVENT_LIST_COMPLETED, outcome)
            assertTrue(outcome.name, described.headline.isNotBlank())
            assertTrue(outcome.name, described.outcomeLabel.isNotBlank())
        }
    }

    @Test
    fun `the confirmation names the one webhook a payload can go to`() {
        // Quick Review and a List both post through the app default since v0.5.6 — the List's own
        // URL is gone — so the question must not offer a choice of destinations that no longer
        // exists. The device pass caught it saying "this list's webhook" over a round bound for
        // the default one.
        listOf(true, false).forEach { defaultWebhook ->
            assertEquals(
                "the default webhook",
                confirmation(defaultWebhook = defaultWebhook).destinationLabel
            )
        }
    }

    @Test
    fun `the confirmation sizes the payload the same way the progress dialog does`() {
        val progress = WebhookProgress(
            event = EVENT_REVIEW_EXITED,
            listTitle = "2026-07-11",
            host = "example.com",
            itemCount = 4,
            defaultWebhook = false
        )

        assertEquals("Review left - 4 items - example.com", progress.summaryLabel)
        // The asking dialog does not repeat the event inside its facts line — its first line is a
        // sentence that already names it — so the two are compared on the part they share.
        assertEquals("4 items - example.com", confirmation(defaultWebhook = false).factsLabel)
    }

    /**
     * Every dialog says what caused it, and the sentence is true at both moments a round reports.
     *
     * The device pass is what this comes from: a completion of one List asked for permission over
     * the review of another, and the dialog opened on the bare word *Demo* with nothing saying what
     * Demo had to do with what the user was doing. The subject was never missing; the *event* was,
     * and a reader dropped into the middle of a sentence needs both.
     *
     * `review.exited` is the one that has to be phrased carefully: it is emitted both when a queue
     * finishes on screen and when one is backed out of, and the completion screen — where the
     * dialog most often appears — is the finished case. A sentence reading *you left* would be
     * wrong every time it is raised there, which is why this one says the review *ended*.
     */
    @Test
    fun `a dialog says what caused the export, and to what`() {
        assertEquals(
            "The List Demo just completed",
            webhookTriggerLabel(EVENT_LIST_COMPLETED, "Demo")
        )
        assertEquals(
            "The review of export test ended",
            webhookTriggerLabel(EVENT_REVIEW_EXITED, "export test")
        )
        assertEquals(
            "The Quick Review of 2026-09-11 finished",
            webhookTriggerLabel(EVENT_QUICK_REVIEW_COMPLETED, "2026-09-11")
        )
        // A folder round's subject is the folder, which is what its payload is named after too.
        assertEquals(
            "The Quick Review of 2026-09-11 finished",
            confirmation(
                defaultWebhook = false,
                event = EVENT_QUICK_REVIEW_COMPLETED,
                listTitle = "2026-09-11"
            ).triggerLabel
        )
    }

    @Test
    fun `an event from a future version still says what it was about`() {
        // Total, like the label it falls back to: a sentence the app does not have words for must
        // still name the thing the dialog is about rather than showing an empty line.
        assertEquals(
            "list.archived: Demo",
            webhookTriggerLabel("list.archived", "Demo")
        )
    }

    @Test
    fun `a payload with one item is not described in the plural`() {
        assertEquals("1 item", countOfItems(1))
        assertEquals("0 items", countOfItems(0))
        assertEquals("42 items", countOfItems(42))
    }

    @Test
    fun `a record is named by the whole moment it happened, not just the time of day`() {
        // Records outlive the day they were made, so "16:40:05" would stop telling them apart.
        assertTrue(
            webhookTimestampLabel(0).matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""))
        )
    }
}
