package com.example.listea

import com.example.listea.data.ListEntity
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line that describes a webhook on a folder card, a list row and the List detail header.
 *
 * It carries two facts that have nothing to do with each other — whether the webhook is armed,
 * and what happened last time it fired — and the point of these is that neither ever hides the
 * other. A list that delivered and was then switched off still has a delivery worth reporting.
 *
 * Since the second decline pass the *what happened* half has three answers rather than two, and
 * the third one is the reason this file grew: a round can be kept unsent, which is neither a
 * success nor a failure, and a card that called it either would be telling the user something
 * untrue about a payload that is still sitting in the history.
 */
class WebhookStatusLabelTest {

    private fun label(
        enabled: Boolean,
        status: String? = null,
        code: Int? = null
    ) = exportStatusLabel(ExportChoice.By(ExportKind.WEBHOOK), enabled, status, code)

    @Test
    fun `an armed webhook reports what it did last`() {
        assertEquals("Webhook · On · Never exported", label(enabled = true))
        assertEquals(
            "Webhook · On · Success",
            label(enabled = true, status = DeliveryStatus.SUCCESS.name)
        )
        assertEquals(
            "Webhook · On · Failed · HTTP 500",
            label(enabled = true, status = DeliveryStatus.FAILED.name, code = 500)
        )
    }

    @Test
    fun `switching a webhook off does not erase what it already sent`() {
        assertEquals(
            "Webhook · Off · Success",
            label(enabled = false, status = DeliveryStatus.SUCCESS.name)
        )
        assertEquals(
            "Webhook · Off · Failed · HTTP 500",
            label(enabled = false, status = DeliveryStatus.FAILED.name, code = 500)
        )
    }

    @Test
    fun `a webhook that was never armed and never fired says both`() {
        assertEquals("Webhook · Off · Never exported", label(enabled = false))
    }

    @Test
    fun `a failure with no status code still says it failed`() {
        assertEquals(
            "Webhook · On · Failed",
            label(enabled = true, status = DeliveryStatus.FAILED.name)
        )
    }

    /**
     * *Not sent* is a third answer, and it must not be read as either of the other two.
     *
     * A round that was kept unsent — declined in the dialog, or set aside because the user was in
     * the middle of another List's review — is not a delivery that succeeded and not one that
     * failed. Calling it *Failed* sends the user to look for a broken URL that is not broken;
     * calling it *Success* says a payload left that is still sitting in the history.
     */
    @Test
    fun `a round that was kept rather than sent says so`() {
        assertEquals(
            "Webhook · On · Not sent",
            label(enabled = true, status = DeliveryStatus.NOT_SENT.name)
        )
        // And the switch still reads independently of it, as it does for the other two.
        assertEquals(
            "Webhook · Off · Not sent",
            label(enabled = false, status = DeliveryStatus.NOT_SENT.name)
        )
    }

    /**
     * The List detail screen's fuller line, which is where the user goes to find out what happened.
     *
     * Three branches and not two, for the same reason as above. The time is formatted from the
     * record rather than invented, so this asserts the shape and the verdict rather than a clock.
     */
    @Test
    fun `the List's own line distinguishes unsent from failed`() {
        fun line(status: String?, code: Int? = null, error: String? = null) = exportLabel(
            ListEntity(
                title = "Demo",
                createdAt = 0,
                lastDeliveryAt = 0,
                lastDeliveryStatus = status,
                lastDeliveryCode = code,
                lastDeliveryError = error
            )
        )

        assertEquals("Last export: never exported", exportLabel(ListEntity(title = "Demo", createdAt = 0)))
        assertTrue(line(DeliveryStatus.SUCCESS.name).startsWith("Last export: Success · "))
        assertTrue(line(DeliveryStatus.NOT_SENT.name).startsWith("Last export: Not sent · "))
        // A failure still carries its reason, and is still the only one that does.
        assertTrue(line(DeliveryStatus.FAILED.name, code = 500).startsWith("Last export: Failed · HTTP 500 · "))
        assertTrue(line(DeliveryStatus.FAILED.name, error = "Host not found").startsWith("Last export: Failed · Host not found · "))
    }
}
