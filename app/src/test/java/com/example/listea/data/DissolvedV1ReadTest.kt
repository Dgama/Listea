package com.example.listea.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A part payload written by v0.8.4, which is still on real devices.
 *
 * Version 1 wrote three fields — key, required, answer — because every part of that era was
 * unconditional and had no answer sheet of its own. Reading it as a version-2 record would shift a
 * field by three and restore an answer onto the wrong part, so the reader branches on the version
 * line and gives the three facts their era's values: no branch, no judgment, not stale.
 *
 * `test/dissolve a Todo and recover it` on a phone upgraded from v0.8.4 is the path this protects.
 */
class DissolvedV1ReadTest {

    @Test
    fun `a version 1 line is read with no branch, no judgment and not stale`() {
        val decoded = decodeDissolvedParts("1\nq1\t1\tanswered\nq2\t0\t")

        assertEquals(
            listOf(
                DissolvedPartRecord(partKey = "q1", required = true, response = "answered"),
                DissolvedPartRecord(partKey = "q2", required = false, response = "")
            ),
            decoded
        )
        assertNull(decoded?.first()?.partJudgment)
        assertNull(decoded?.first()?.condition)
        assertEquals(false, decoded?.first()?.stale)
    }

    @Test
    fun `a version 1 response with a tab or a newline is still one record`() {
        val record = DissolvedPartRecord(partKey = "q1", required = true, response = "a\tb\nc")

        // A version-1 snapshot was written by v0.8.4's escaper, which is the one this build still
        // reads; the three fields stay three because the escaping is unchanged.
        assertEquals(
            listOf(record),
            decodeDissolvedParts(
                "1\nq1\t1\t" + escapeField(record.response)
            )
        )
    }

    @Test
    fun `a version 1 line with the version 2 field count is refused`() {
        assertNull(decodeDissolvedParts("1\nq1\t1\t\t\t0\tanswered"))
    }
}
