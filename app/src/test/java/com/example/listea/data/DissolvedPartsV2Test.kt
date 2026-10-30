package com.example.listea.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The part payload a dissolved history writes since v0.8.5, held to its own format.
 *
 * Version 2 adds the three facts that say whether a part's answer was ever current: the branch the
 * part sat on, the answer sheet that judged it, and whether the branch had set the answer aside.
 * The golden assertion pins the six fields and their order; the round-trips say the encoding is
 * reversible; the fail-closed cases say a payload this build did not write is refused rather than
 * read for whatever fields happen to line up.
 *
 * Pure strings, no database: this is the half of dissolution that must survive even if the schema
 * around it changes, exactly as [DissolvedRecordsTest] argues for the file codec.
 */
class DissolvedPartsV2Test {

    @Test
    fun `the encoding is exactly what the format describes`() {
        val encoded = encodeDissolvedParts(
            listOf(
                DissolvedPartRecord(partKey = "gate", required = true, response = ""),
                DissolvedPartRecord(
                    partKey = "menu",
                    required = true,
                    response = "line1\tline2\n100%",
                    partJudgment = """[{"id":"builtin.media.part","version":1}]""",
                    condition = """{"prompt":"gate","field":"choice","equals":"yes"}""",
                    stale = true
                )
            )
        )

        assertEquals(
            "2\n" +
                "gate\t1\t\t\t0\t\n" +
                "menu\t1\t[{\"id\":\"builtin.media.part\",\"version\":1}]\t" +
                "{\"prompt\":\"gate\",\"field\":\"choice\",\"equals\":\"yes\"}\t1\t" +
                "line1%09line2%0A100%25",
            encoded
        )
    }

    @Test
    fun `what was encoded is what comes back`() {
        val records = listOf(
            DissolvedPartRecord(partKey = "gate", required = true, response = "answer"),
            DissolvedPartRecord(
                partKey = "menu",
                required = false,
                response = "",
                partJudgment = """[{"id":"builtin.media.part","version":1}]""",
                condition = """{"prompt":"gate","field":"choice","equals":"yes"}""",
                stale = true
            )
        )

        assertEquals(records, decodeDissolvedParts(encodeDissolvedParts(records)))
    }

    @Test
    fun `an empty judgment or condition field is none, not an empty string`() {
        val decoded = decodeDissolvedParts("2\nreceipt\t1\t\t\t0\t")?.single()

        assertNull(decoded?.partJudgment)
        assertNull(decoded?.condition)
        assertEquals(false, decoded?.stale)
    }

    @Test
    fun `a response with a tab or a newline stays one record`() {
        val record = DissolvedPartRecord(
            partKey = "menu",
            required = true,
            response = "a\tb\nc"
        )

        assertEquals(listOf(record), decodeDissolvedParts(encodeDissolvedParts(listOf(record))))
    }

    @Test
    fun `a version this build does not know decodes to nothing rather than to a guess`() {
        assertNull(decodeDissolvedParts("3\nq1\t1\t\t\t0\tanswer"))
        assertNull(decodeDissolvedParts("0\nq1\t1\t\t\t0\tanswer"))
    }

    @Test
    fun `a line with fewer or more fields decodes to nothing`() {
        assertNull(decodeDissolvedParts("2\nq1\t1\t\t0\tanswer"))
        assertNull(decodeDissolvedParts("2\nq1\t1\t\t\t0\tanswer\textra"))
    }

    @Test
    fun `a duplicate part key decodes to nothing`() {
        assertNull(decodeDissolvedParts("2\nq1\t1\t\t\t0\t\nq1\t1\t\t\t0\t"))
    }

    @Test
    fun `an empty payload is no part answers, not a malformed one`() {
        assertEquals(emptyList<DissolvedPartRecord>(), decodeDissolvedParts(""))
    }
}
