package com.example.listea.domain

/**
 * A point on an image, normalized to the image's own dimensions: 0..1 from its top-left.
 *
 * Normalized rather than in pixels, because the same press must mean the same place at any screen
 * size and any zoom level. The sampler maps it onto the decoded bitmap, and a response stores it
 * exactly as it is.
 */
data class ImagePoint(val x: Double, val y: Double)

/**
 * Where a press landed on the image, or null when it landed outside the picture.
 *
 * The inverse of what the review surface does to the picture: the image is fitted into the
 * viewport with letterboxing, then `graphicsLayer` scales it about the viewport's centre and
 * translates it. `screen = centre + (content − centre) · scale + translation`, so the inverse is
 * a subtraction, a division and an un-letterboxing — no Compose, no Android, testable on the JVM.
 *
 * A press in the letterbox is null, not a clamped edge: the background around a portrait picture is
 * not part of the picture, and a colour read there would be a colour of the frame.
 *
 * [contentAspect] is the image's width divided by its height. [scale] and the translations are the
 * values the state currently holds; a non-positive or non-finite input is null rather than a
 * division by zero.
 */
fun imagePointAt(
    tapX: Float,
    tapY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    contentAspect: Float,
    scale: Float,
    translationX: Float,
    translationY: Float
): ImagePoint? {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return null
    if (contentAspect <= 0f || !contentAspect.isFinite()) return null
    if (scale <= 0f || !scale.isFinite()) return null
    if (!tapX.isFinite() || !tapY.isFinite()) return null
    if (!translationX.isFinite() || !translationY.isFinite()) return null

    val viewportAspect = viewportWidth / viewportHeight
    val fittedWidth: Float
    val fittedHeight: Float
    if (viewportAspect > contentAspect) {
        fittedHeight = viewportHeight
        fittedWidth = fittedHeight * contentAspect
    } else {
        fittedWidth = viewportWidth
        fittedHeight = fittedWidth / contentAspect
    }

    val centreX = viewportWidth / 2f
    val centreY = viewportHeight / 2f
    // Undo the layer: translation first, then the scale about the viewport centre.
    val contentX = centreX + (tapX - centreX - translationX) / scale
    val contentY = centreY + (tapY - centreY - translationY) / scale

    val left = centreX - fittedWidth / 2f
    val top = centreY - fittedHeight / 2f
    val nx = (contentX - left) / fittedWidth
    val ny = (contentY - top) / fittedHeight
    if (nx !in 0f..1f || ny !in 0f..1f) return null

    return ImagePoint(nx.toDouble(), ny.toDouble())
}

/**
 * The average of a sampled window, as uppercase `#RRGGBB`, or null for an empty window.
 *
 * Alpha is ignored: the colour is what the image shows, and a decoded bitmap may carry either an
 * opaque or a premultiplied alpha channel depending on how it was decoded. Averaging in sRGB
 * space is not perceptual, and it does not need to be — the purpose is to say *which block* was
 * pressed on a synthetic fixture and to give a photograph a stable, human-readable swatch.
 */
fun averageColor(argbPixels: IntArray): String? {
    if (argbPixels.isEmpty()) return null
    var red = 0L
    var green = 0L
    var blue = 0L
    for (pixel in argbPixels) {
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    val count = argbPixels.size
    return "#" + listOf(red, green, blue)
        .joinToString("") { channel ->
            ((channel / count).toInt()).toString(16).padStart(2, '0')
        }
        .uppercase()
}
