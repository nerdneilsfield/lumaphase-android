package dev.lumaphase.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.lumaphase.android.layout.PatternLayout

/**
 * Builds the identity block the stationary phases show in place of the dynamic area:
 * `OPTC-1`, the full session id, the platform name and the phase label, as the contract
 * requires for the session and end cards.
 *
 * The bitmap is built once per phase change at the layout's own pixel size, so the card
 * costs one texture upload and one textured quad, not a per-frame text render. Anti-aliasing
 * here is deliberate and does not touch the optical code: this block is not a module area.
 */
object CardBitmap {
    const val MID_GREY_LEVEL = 128

    fun buildIdentityBitmap(
        layout: PatternLayout,
        sessionId: String,
        platform: String,
        label: String,
    ): Bitmap {
        val width = maxOf(1, layout.dynamicRectPx.widthPx)
        val height = maxOf(1, layout.dynamicRectPx.heightPx)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val lines = listOf(PLATFORM_PROTOCOL, sessionId, platform, label)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        // The longest line decides the font size, measured with the real font, so the full
        // session UUID is never clipped by the bitmap edge (contract section 5).
        val widest = lines.maxOf { line ->
            paint.textSize = MAX_TEXT_SIZE
            paint.measureText(line)
        }
        val textSize = maxOf(
            MIN_TEXT_SIZE,
            minOf(MAX_TEXT_SIZE, (width * FIT_FRACTION) * MAX_TEXT_SIZE / maxOf(widest, 1f)),
        )
        // A single line must also leave room for the four lines plus the line spacing.
        val heightLimit = height / (lines.size * LINE_SPACING)
        val fitted = minOf(textSize, heightLimit).coerceAtLeast(MIN_TEXT_SIZE)
        paint.textSize = fitted
        val lineHeight = fitted * LINE_SPACING
        var baseline = height / 2f - lineHeight * (lines.size - 1) / 2f
        for (line in lines) {
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        return bitmap
    }

    /** Contract section 5 fixes the protocol name printed on the cards. */
    const val PLATFORM_PROTOCOL = "OPTC-1"

    private const val MAX_TEXT_SIZE = 256f
    private const val MIN_TEXT_SIZE = 8f

    /** The widest line is allowed to take this fraction of the canvas width. */
    private const val FIT_FRACTION = 0.92f
    private const val LINE_SPACING = 1.4f
}
