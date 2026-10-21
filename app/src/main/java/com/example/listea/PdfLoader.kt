package com.example.listea

import android.content.Context
import androidx.core.net.toUri
import dev.nucleusframework.pdfium.PdfError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * How a PDF is read off disk, bounded, and made identifiable.
 *
 * **The cap is a product decision, not a memory heuristic.** A PDF is read whole because the
 * renderer takes bytes (`PdfReaderState.open(byte[], String)`), which means a 500 MB scan would be
 * a 500 MB heap allocation before the first page appears. 64 MiB is stated in the acceptance
 * criteria and in the viewer's own failure message, so the number lives here once.
 *
 * **The fingerprint is the same bytes, hashed in the same pass.** It is what lets a stored mark
 * decide whether it still points at the document it was written on; hashing after reading would
 * read the file twice for no benefit, and hashing separately would be one more place for the two
 * to disagree.
 */
internal const val PdfSizeLimitBytes: Long = 64L * 1024 * 1024

/** What a read produced, in the vocabulary the viewer shows. */
internal sealed interface PdfLoadFailure {
    val headline: String

    data object TooLarge : PdfLoadFailure {
        override val headline: String = "This PDF is too large to open (64 MB limit)"
    }

    data object Password : PdfLoadFailure {
        override val headline: String = "PDF is password-protected"
    }

    data object Damaged : PdfLoadFailure {
        override val headline: String = "PDF is damaged"
    }

    data object Unreadable : PdfLoadFailure {
        override val headline: String = "PDF could not be opened"
    }
}

internal class LoadedPdf(val bytes: ByteArray, val fingerprint: String)

internal sealed interface PdfLoadResult {
    data class Loaded(val pdf: LoadedPdf) : PdfLoadResult
    data class Failed(val failure: PdfLoadFailure) : PdfLoadResult
}

/**
 * Reads the document behind [uri], or says why it could not.
 *
 * Blocking: call on `Dispatchers.IO`. The provider's declared length is used when it has one —
 * refusing before a byte is read — and a stream without a length is read at most one byte past the
 * cap, which is enough to answer *too large* without trusting an absent number. Every stream is
 * closed on both paths.
 *
 * Password and damage are the renderer's answers rather than this function's: only opening the
 * bytes can tell a locked file from a broken one. [pdfFailureOf] maps that answer into the same
 * vocabulary, and the viewer reports either identically.
 */
internal fun readPdf(context: Context, uri: String): PdfLoadResult {
    val parsed = runCatching { uri.toUri() }.getOrNull()
        ?: return PdfLoadResult.Failed(PdfLoadFailure.Unreadable)
    val resolver = context.contentResolver

    val declaredLength = runCatching {
        resolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    }.getOrNull()
    if (declaredLength != null && declaredLength > PdfSizeLimitBytes) {
        return PdfLoadResult.Failed(PdfLoadFailure.TooLarge)
    }

    val input = runCatching { resolver.openInputStream(parsed) }.getOrNull()
        ?: return PdfLoadResult.Failed(PdfLoadFailure.Unreadable)

    return input.use { stream ->
        when (val bounded = readBounded(stream, declaredLength)) {
            BoundedRead.TooLarge -> PdfLoadResult.Failed(PdfLoadFailure.TooLarge)
            BoundedRead.Unreadable -> PdfLoadResult.Failed(PdfLoadFailure.Unreadable)
            is BoundedRead.Data -> PdfLoadResult.Loaded(
                LoadedPdf(bounded.bytes, bounded.fingerprint)
            )
        }
    }
}

/** The pure half of [readPdf], so the size policy can be tested without a device. */
internal sealed interface BoundedRead {
    class Data(val bytes: ByteArray, val fingerprint: String) : BoundedRead
    data object TooLarge : BoundedRead
    data object Unreadable : BoundedRead
}

/**
 * Reads at most [limit] bytes and hashes what it read.
 *
 * [declaredLength] is only used to pre-size the buffer and to fail before reading when it already
 * says the file is too large. It is never trusted as the size: a provider may report -1, or a
 * number that is not the number of bytes that follow.
 */
internal fun readBounded(
    input: InputStream,
    declaredLength: Long?,
    limit: Long = PdfSizeLimitBytes
): BoundedRead {
    if (declaredLength != null && declaredLength > limit) return BoundedRead.TooLarge

    val digest = MessageDigest.getInstance("SHA-256")
    val initial = (declaredLength ?: 64L * 1024L).coerceIn(0L, limit).toInt()
    val output = ByteArrayOutputStream(initial)
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = try {
            input.read(chunk)
        } catch (_: Exception) {
            return BoundedRead.Unreadable
        }
        if (read < 0) break
        total += read
        if (total > limit) return BoundedRead.TooLarge
        output.write(chunk, 0, read)
        digest.update(chunk, 0, read)
    }
    val bytes = output.toByteArray()
    return BoundedRead.Data(bytes, digest.digest().toHex())
}

/**
 * The SHA-256 of the bytes behind [uri], or null when they cannot be read within the cap.
 *
 * Export asks this of every Asset that carries marks, to decide whether each stored passage still
 * describes the file being sent. It is **the same hash** [readPdf] computes — same algorithm, same
 * bytes, same cap — but it does not keep the bytes: an export of a folder of PDFs must not hold
 * every one of them in memory at once. Blocking: call on `Dispatchers.IO`.
 *
 * A file that is missing, unreadable, or larger than the viewer would open answers null, and the
 * marks that depend on it are omitted. That is the honest answer: their anchors describe a document
 * this build cannot compare them against.
 */
internal fun fingerprintPdf(context: Context, uri: String): String? {
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
    val resolver = context.contentResolver
    val declaredLength = runCatching {
        resolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    }.getOrNull()
    if (declaredLength != null && declaredLength > PdfSizeLimitBytes) return null

    val input = runCatching { resolver.openInputStream(parsed) }.getOrNull() ?: return null
    return input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = try {
                stream.read(chunk)
            } catch (_: Exception) {
                return null
            }
            if (read < 0) break
            total += read
            if (total > PdfSizeLimitBytes) return null
            digest.update(chunk, 0, read)
        }
        digest.digest().toHex()
    }
}

/** The renderer's opening error as the viewer's vocabulary, or null when it opened. */
internal fun pdfFailureOf(error: PdfError?): PdfLoadFailure? = when (error) {
    null -> null
    is PdfError.PasswordRequired -> PdfLoadFailure.Password
    is PdfError.InvalidFormat -> PdfLoadFailure.Damaged
    is PdfError.Io -> PdfLoadFailure.Unreadable
    is PdfError.NativeFailure -> PdfLoadFailure.Damaged
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
