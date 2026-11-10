package dev.lumaphase.android.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.view.TextureView
import kotlin.math.max

/**
 * Display-only transform for the live `TextureView` preview.
 *
 * TextureView already presents the sensor output upright in the device's natural orientation;
 * it does not apply the current display rotation. This helper applies that display rotation and
 * scales the preview to fill the view. The transform is never used to map touches or analyse
 * pixels: frames are rotated independently in [GrayFrameExtractor], so the recognition
 * coordinates do not depend on this matrix or on a guessed sign.
 */
object PreviewTransform {
    fun apply(
        textureView: TextureView,
        previewWidthPx: Int,
        previewHeightPx: Int,
        displayRotationDeg: Int,
    ) {
        if (textureView.width <= 0 || textureView.height <= 0 ||
            previewWidthPx <= 0 || previewHeightPx <= 0
        ) {
            return
        }
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val bufferRect = RectF(0f, 0f, previewWidthPx.toFloat(), previewHeightPx.toFloat())
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight / previewHeightPx.toFloat(),
            viewWidth / previewWidthPx.toFloat(),
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(-displayRotationDeg.toFloat(), centerX, centerY)
        textureView.setTransform(matrix)
    }
}
