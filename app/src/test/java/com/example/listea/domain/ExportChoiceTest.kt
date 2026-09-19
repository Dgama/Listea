package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vocabulary a stored export choice is written in.
 *
 * These four words are the whole of what a future release has to be able to read, so they are
 * pinned exactly — the same argument `ReviewResponseTest` makes about `selections=`. A golden
 * expectation turns a change to the vocabulary into something somebody wrote down rather than
 * something that happened.
 *
 * The interesting half is not the round trip but the two answers that **fail closed**: a key from a
 * build that is not this one, and `inherit` read off a Todo. Both end in [ExportChoice.Nothing],
 * which is the only safe direction for a rule this build cannot evaluate — see the header of
 * `domain/Exports.kt`. If either ever decoded to a kind, a database written by a different build
 * could make this one send.
 */
class ExportChoiceTest {

    @Test
    fun `every choice encodes to exactly one word`() {
        assertEquals("none", encodeExportChoice(ExportChoice.Nothing))
        assertEquals("inherit", encodeExportChoice(ExportChoice.Inherit))
        assertEquals("webhook", encodeExportChoice(ExportChoice.By(ExportKind.WEBHOOK)))
        assertEquals("txt", encodeExportChoice(ExportChoice.By(ExportKind.TXT)))
    }

    @Test
    fun `no choice encodes to nothing at all`() {
        // The empty string is already spoken for by `sources.assetControls`, where it means
        // *nothing was ever chosen*. A choice that encoded to it would make the two columns
        // indistinguishable to anything that does not know which one it is holding.
        val encoded = listOf(
            ExportChoice.Nothing,
            ExportChoice.Inherit
        ) + ExportKind.entries.map { ExportChoice.By(it) }

        encoded.forEach { assertTrue("$it encoded to nothing", encodeExportChoice(it).isNotEmpty()) }
    }

    @Test
    fun `a List reads back the word it wrote`() {
        val choices = LIST_EXPORT_CHOICES

        choices.forEach { choice ->
            assertEquals(choice, decodeExportChoice(encodeExportChoice(choice), inheritable = true))
        }
    }

    @Test
    fun `a Todo reads back every word it can actually hold`() {
        // `inherit` is excluded rather than special-cased here: it is the word a Todo cannot hold,
        // and it has its own test below.
        val choices = listOf(ExportChoice.Nothing) + ExportKind.entries.map { ExportChoice.By(it) }

        choices.forEach { choice ->
            assertEquals(choice, decodeExportChoice(encodeExportChoice(choice), inheritable = false))
        }
    }

    @Test
    fun `inherit on a Todo is nothing, not a kind`() {
        // A Todo has nothing above it to follow, so *follow the Todos* has no meaning on one. The
        // answer that fails closed is the one that exports nothing; falling back to a kind would
        // turn a word this column cannot mean into an export.
        assertEquals(ExportChoice.Nothing, decodeExportChoice("inherit", inheritable = false))
    }

    @Test
    fun `inherit on a List is inherit`() {
        // The other half of the same word. One function and not two, because the two columns hold
        // the same vocabulary and this is the only word whose meaning depends on which it is.
        assertEquals(ExportChoice.Inherit, decodeExportChoice("inherit", inheritable = true))
    }

    @Test
    fun `a key from another build exports nothing`() {
        listOf("csv", "action", "web", "webhooks", "", "WEBHOOK", "None").forEach { key ->
            assertEquals(
                "an unreadable key must not become an export: $key",
                ExportChoice.Nothing,
                decodeExportChoice(key, inheritable = true)
            )
        }
    }

    @Test
    fun `a missing key exports nothing`() {
        // A row that predates the column, or one whose write was interrupted. Not a crash, and
        // not a kind.
        assertEquals(ExportChoice.Nothing, decodeExportChoice(null, inheritable = true))
        assertEquals(ExportChoice.Nothing, decodeExportChoice(null, inheritable = false))
    }

    @Test
    fun `a key that is a prefix of a kind does not match it`() {
        // `ExportKind.fromKey` compares whole strings. A prefix match would make `web` a webhook
        // and `t` a txt, which is the kind of near-miss that only shows up in a database written
        // by a build that used shorter keys.
        assertNull(ExportKind.fromKey("web"))
        assertNull(ExportKind.fromKey("t"))
        assertNull(ExportKind.fromKey("webhookk"))
        assertNull(ExportKind.fromKey(null))
    }

    @Test
    fun `every kind reads back by its own key`() {
        ExportKind.entries.forEach { kind ->
            assertEquals(kind, ExportKind.fromKey(kind.key))
        }
    }

    @Test
    fun `the List's dropdown names every choice, in one order`() {
        // Four, and the same four every time the screen is drawn: two words that are not kinds,
        // then the kinds in the enum's declaration order.
        assertEquals(
            listOf(ExportChoice.Inherit, ExportChoice.Nothing, ExportChoice.By(ExportKind.WEBHOOK), ExportChoice.By(ExportKind.TXT)),
            LIST_EXPORT_CHOICES
        )
        assertEquals("Same as the Todos", listExportChoiceLabel(ExportChoice.Inherit))
        assertEquals("Export nothing", listExportChoiceLabel(ExportChoice.Nothing))
        assertEquals("Webhook", listExportChoiceLabel(ExportChoice.By(ExportKind.WEBHOOK)))
        assertEquals("Txt", listExportChoiceLabel(ExportChoice.By(ExportKind.TXT)))
    }
}
