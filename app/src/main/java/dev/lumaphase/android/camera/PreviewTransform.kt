package dev.lumaphase.android.camera

import android.graphics.Matrix
import android.view.TextureView

/**
 * Display-only uniform transform for the live `TextureView` preview.
 *
 * TextureView presents the camera buffer in its natural (sensor-rotated) orientation, not in
 * the display rotation. The pure [PreviewMatrix] composition takes the natural content, applies
 * the current display rotation and one uniform fit scale, and returns an affine matrix. The
 * view is sized to [CameraPreviewInfo.uprightSize] by the caller, so live preview and frozen
 * upright image share aspect/FOV; any remaining space is a centred letterbox rather than a
 * non-uniform stretch.
 *
 * The transform is display-only. Touches, overlays and manual corners use
 * [CameraPreviewGeometry.fit] / [CameraPreviewGeometry.viewToFrame] on the upright frame.
 */
object PreviewTransform {
    fun apply(
        textureView: TextureView,
        preview: CameraPreviewInfo,
    ) {
        if (textureView.width <= 0 || textureView.height <= 0) return
        val affine = PreviewMatrix.viewAdditive(preview, textureView.width, textureView.height)
        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                affine.m00, affine.m01, affine.m02,
                affine.m10, affine.m11, affine.m12,
                0f, 0f, 1f,
            ),
        )
        textureView.setTransform(matrix)
    }
}
