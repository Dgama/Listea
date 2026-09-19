package com.example.listea.data

import com.example.listea.domain.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The encoding a dissolved history keeps its files in, held to its own format.
 *
 * Pure strings, so no database and no Robolectric — and that is the point of the file existing
 * separately: this codec is the one part of v0.5.2 that must not depend on anything else working.
 *
 * Two kinds of test live here and they are doing different jobs. The round-trips say the encoding is
 * reversible; the **golden** assertions say what it *is*, because a format that only ever round-trips
 * is a format that can be changed by accident. A payload written by this build is read by the next
 * one, so the bytes are a contract and are pinned as one.
 *
 * The awkward paths are the reason the escaping exists at all: a filename is the user's, and a tab or
 * a newline in one would otherwise be indistinguishable from the separators this format is made of.
 * Each of them is asserted to survive as *one* record with its own name, which is what a silent
 * split would break while every round-trip of an ordinary path went on passing.
 */
class DissolvedRecordsTest {

    @Test
    fun `the encoding is exactly what the format describes`() {
        // The contract, written down. One line per file and two fields: the escaped path, then
        // `encodeActionIds`' spelling of the marks — sorted and comma-joined — so a record in a
        // payload reads the same as the one it came from.
        assertEquals(
            "Camera/a.jpg\tcustom1,custom2\nCamera/b.jpg\t",
            encodeDissolvedRecords(
                listOf(
                    DissolvedFileRecord("Camera/a.jpg", AssetDecisions(setOf("custom2", "custom1"))),
                    DissolvedFileRecord("Camera/b.jpg", AssetDecisions())
                )
            )
        )
    }

    @Test
    fun `what was encoded is what comes back`() {
        val records = listOf(
            DissolvedFileRecord(
                "Camera/a.jpg",
                AssetDecisions(setOf("custom1", Templates.FAVOURITE_BUTTON_ID))
            ),
            DissolvedFileRecord("Camera/b.mp4", AssetDecisions(setOf("cust5", "cust4")))
        )

        assertEquals(
            records,
            decodeDissolvedRecords(encodeDissolvedRecords(records), DISSOLVED_RECORDS_VERSION)
        )
    }

    @Test
    fun `the order the files were recorded in is the order they come back`() {
        // Asset order, which is the order the review surface pages in. A decode that sorted — by
        // path, say — would reorder a Task's pages behind a restore.
        val records = listOf(
            DissolvedFileRecord("z/last.jpg", AssetDecisions(setOf(Templates.FAVOURITE_BUTTON_ID))),
            DissolvedFileRecord("a/first.jpg", AssetDecisions())
        )

        assertEquals(
            records,
            decodeDissolvedRecords(encodeDissolvedRecords(records), DISSOLVED_RECORDS_VERSION)
        )
    }

    @Test
    fun `a Task with no file encodes as nothing and decodes as no files`() {
        // `''` is an answer — a manual item has no Asset — and it must not read as a payload this
        // build failed to understand, which is what `null` means. The two are asserted apart.
        assertEquals("", encodeDissolvedRecords(emptyList()))
        assertEquals(emptyList<DissolvedFileRecord>(), decodeDissolvedRecords("", DISSOLVED_RECORDS_VERSION))
    }

    @Test
    fun `a filename with a tab in it is one record, not two fields`() {
        val records = listOf(
            DissolvedFileRecord("odd/na\tme.jpg", AssetDecisions(setOf("custom1")))
        )

        assertEquals(
            records,
            decodeDissolvedRecords(encodeDissolvedRecords(records), DISSOLVED_RECORDS_VERSION)
        )
    }

    @Test
    fun `a filename with a newline in it is one record, not two`() {
        // The separator a split would lose silently: two records back where the user has one file,
        // each of them a fragment of the name. The count is asserted as well as the equality,
        // because "one record came back" is the half a round-trip of a mangled pair could hide.
        val records = listOf(
            DissolvedFileRecord("odd/na\nme.jpg", AssetDecisions(setOf("custom2")))
        )

        val decoded = decodeDissolvedRecords(encodeDissolvedRecords(records), DISSOLVED_RECORDS_VERSION)
        assertEquals(1, decoded?.size)
        assertEquals(records, decoded)
    }

    @Test
    fun `a filename containing an escape is not decoded twice`() {
        // The failure a naive replace-`%25`-last decoder has: `%250A` decodes to `%0A` and then to a
        // newline. One left-to-right pass is what makes the two unambiguous, and this is the case
        // that says so.
        val records = listOf(
            DissolvedFileRecord("odd/100%0A literal.jpg", AssetDecisions(setOf("custom1"))),
            DissolvedFileRecord("odd/100%.jpg", AssetDecisions())
        )

        assertEquals(
            records,
            decodeDissolvedRecords(encodeDissolvedRecords(records), DISSOLVED_RECORDS_VERSION)
        )
    }

    @Test
    fun `a version this build does not know decodes to nothing rather than to a guess`() {
        val encoded = encodeDissolvedRecords(
            listOf(DissolvedFileRecord("Camera/a.jpg", AssetDecisions(setOf("custom1"))))
        )

        assertNull(decodeDissolvedRecords(encoded, DISSOLVED_RECORDS_VERSION + 1))
        assertNull(decodeDissolvedRecords(encoded, 0))
    }

    @Test
    fun `a version 1 line is read, and its favourite flag becomes the button that carries it`() {
        // Version 1 wrote three fields, the middle one a flag, and it is still on real devices. The
        // flag is not dropped on the way in: it becomes the id `★` has everywhere else, so a history
        // written by an older build restores the star it recorded rather than losing it. The line is
        // asserted whole because that is the claim — one record, one id, and no third field left
        // over as a mark.
        assertEquals(
            listOf(
                DissolvedFileRecord(
                    "Camera/a.jpg",
                    AssetDecisions(setOf("custom1", Templates.FAVOURITE_BUTTON_ID))
                )
            ),
            decodeDissolvedRecords("Camera/a.jpg\t1\tcustom1", 1)
        )
    }

    @Test
    fun `a payload this build did not write decodes to nothing rather than to what it can salvage`() {
        // A line whose field count is not the one the version it names writes is a shape this file
        // does not describe: two fields for the current version, three for the flag-bearing one.
        // Reading the fields it happens to have would be inventing the rest, so the whole payload is
        // refused and the caller restores completion alone.
        assertNull(decodeDissolvedRecords("Camera/a.jpg", DISSOLVED_RECORDS_VERSION))
        assertNull(decodeDissolvedRecords("Camera/a.jpg\tcustom1\textra", DISSOLVED_RECORDS_VERSION))
        assertNull(decodeDissolvedRecords("Camera/a.jpg\t1", 1))
        assertNull(decodeDissolvedRecords("Camera/a.jpg\t1\tcustom1\textra", 1))
        assertNull(decodeDissolvedRecords("Camera/a.jpg\tcustom1\nbroken", DISSOLVED_RECORDS_VERSION))
    }
}
