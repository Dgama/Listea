package com.example.listea

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.example.listea.domain.ImagePoint
import com.example.listea.domain.averageColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The longest side a decoded image is allowed to be while a point is being sampled.
 *
 * The press is reported as a fraction of the picture, so the sample does not need the original
 * resolution — and a review queue may hold a 48-megapixel photograph whose full decode would be an
 * out-of-memory crash on the one gesture that is meant to be cheap. 1024 keeps a 5×5 window well
 * inside the block structure of anything a person could aim at, synthetic blocks included.
 */
private const val MAX_SAMPLE_DIMENSION = 1024

/** Half-width of the averaged window: a 5×5 box around the pressed pixel. */
private const val WINDOW_RADIUS = 2

/**
 * The average colour around [point] on the image at [uri], as uppercase `#RRGGBB`, or null when
 * the file cannot be decoded.
 *
 * Decoding happens off the main thread and on a bounded copy. The window is clamped at the edges
 * rather than rejected: a press in the outermost pixel is still a press on the picture.
 *
 * The fraction is measured against the picture *as the viewer drew it*, so an EXIF-rotated JPEG has
 * to be sampled upright. API 28+ decodes through [ImageDecoder], which applies the file's
 * orientation; below that the bitmap is the stored one and a rotated photograph samples the wrong
 * pixel, which is the pre-28 limitation the plan records. The acceptance fixture is a PNG, where
 * the two readings agree.
 */
suspend fun sampleImageColor(
    context: Context,
    uri: String,
    point: ImagePoint
): String? = withContext(Dispatchers.IO) {
    val bitmap = decodeBounded(context, uri) ?: return@withContext null
    try {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return@withContext null

        val centreX = (point.x * width).toInt().coerceIn(0, width - 1)
        val centreY = (point.y * height).toInt().coerceIn(0, height - 1)
        val left = (centreX - WINDOW_RADIUS).coerceAtLeast(0)
        val top = (centreY - WINDOW_RADIUS).coerceAtLeast(0)
        val right = (centreX + WINDOW_RADIUS).coerceAtMost(width - 1)
        val bottom = (centreY + WINDOW_RADIUS).coerceAtMost(height - 1)
        val windowWidth = right - left + 1
        val windowHeight = bottom - top + 1

        val pixels = IntArray(windowWidth * windowHeight)
        bitmap.getPixels(pixels, 0, windowWidth, left, top, windowWidth, windowHeight)
        averageColor(pixels)
    } finally {
        bitmap.recycle()
    }
}

/**
 * The image at [uri], upright where the platform can make it so, downsampled so its longest side is
 * at most [MAX_SAMPLE_DIMENSION].
 *
 * Null when the file cannot be opened or is not an image this build can decode — the caller treats
 * that as *nothing was recorded* rather than as an error to show.
 */
private fun decodeBounded(context: Context, uri: String): Bitmap? {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeOriented(context, parsed)
    } else {
        decodeStored(context, parsed)
    }
}

/**
 * API 28+: one decode whose header pass supplies the bounds.
 *
 * [ImageDecoder] applies the file's EXIF orientation, so the bitmap has the shape the viewer drew.
 * It allocates hardware bitmaps by default, which refuse `getPixels`; the sampler reads pixels, so
 * it asks for a software one.
 */
private fun decodeOriented(context: Context, uri: Uri): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
    }
}.getOrNull()

/**
 * Below API 28: two passes over the stream, bounds first, then a power-of-two downsample.
 *
 * [BitmapFactory] does not apply EXIF orientation, so a rotated photograph is sampled in its stored
 * orientation. The plan records that; the viewer's own rotation support is where it would move.
 */
private fun decodeStored(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val openedForBounds = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        }
    }.getOrNull() ?: return null
    if (openedForBounds != true || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()
}

/**
 * The power-of-two [BitmapFactory] sample size that keeps the longest side at or under
 * [MAX_SAMPLE_DIMENSION].
 *
 * `ImageDecoder.setTargetSampleSize` uses the same scale, so one function keeps both decoders from
 * disagreeing about how much detail a sample needs.
 */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= MAX_SAMPLE_DIMENSION) sample *= 2
    return sample
}
