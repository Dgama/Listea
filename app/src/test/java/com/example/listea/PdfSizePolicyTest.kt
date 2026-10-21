package com.example.listea

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap that keeps a PDF from being a heap allocation, and the fingerprint taken while reading.
 *
 * The number in the viewer's failure message is [PdfSizeLimitBytes], so this file is where the
 * boundary is held: at the limit a file opens, one byte over it does not, and a provider that
 * reports no length is still bounded rather than trusted. The limit is a parameter on the pure
 * reader so these cases can run on a few bytes instead of allocating 64 MiB each.
 */
class PdfSizePolicyTest {

    @Test
    fun `the product cap is 64 mebibytes`() {
        assertEquals(64L * 1024 * 1024, PdfSizeLimitBytes)
    }

    @Test
    fun `a file exactly at the limit passes`() {
        val bytes = ByteArray(16) { it.toByte() }

        val result = readBounded(ByteArrayInputStream(bytes), declaredLength = 16, limit = 16)

        assertTrue(result is BoundedRead.Data)
        assertEquals(16, (result as BoundedRead.Data).bytes.size)
    }

    @Test
    fun `one byte over the limit fails`() {
        val bytes = ByteArray(17)

        val result = readBounded(ByteArrayInputStream(bytes), declaredLength = 17, limit = 16)

        assertTrue(result is BoundedRead.TooLarge)
    }

    @Test
    fun `a declared length over the limit fails before a byte is read`() {
        val result = readBounded(ByteArrayInputStream(ByteArray(0)), declaredLength = 17, limit = 16)

        assertTrue(result is BoundedRead.TooLarge)
    }

    @Test
    fun `a stream with no declared length is still bounded`() {
        // The provider answered -1 or the descriptor could not be opened. Reading stops one byte
        // past the limit rather than growing until memory runs out.
        val result = readBounded(ByteArrayInputStream(ByteArray(64)), declaredLength = null, limit = 16)

        assertTrue(result is BoundedRead.TooLarge)
    }

    @Test
    fun `the fingerprint is the SHA-256 of exactly the bytes read`() {
        val bytes = "a small PDF, in principle".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val result = readBounded(ByteArrayInputStream(bytes), declaredLength = bytes.size.toLong(), limit = 1024)

        assertEquals(expected, (result as BoundedRead.Data).fingerprint)
        assertEquals(bytes.toList(), result.bytes.toList())
    }
}
