package dev.lumaphase.android.camera

import kotlin.math.cos
import kotlin.math.sin

/** Row-major 2x3 affine matrix from natural camera content to view pixels. */
data class Affine2D(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
) {
    fun map(x: Float, y: Float): Pair<Float, Float> =
        (m00 * x + m01 * y + m02) to (m10 * x + m11 * y + m12)
}

/**
 * Pure composition of the TextureView preview transform. TextureView's default content is the
 * sensor-rotated **natural** buffer; [CameraPreviewGeometry.fit] is used to fit the final
 * upright content. The one uniform scale and display rotation therefore map natural content
 * to the view without an axis-dependent scale.
 *
 * This is the arithmetic that `PreviewTransform` feeds to android.graphics.Matrix; tests can
 * assert singular values and corner bounds without loading Android graphics on the host.
 */
object PreviewMatrix {
    fun compose(
        preview: CameraPreviewInfo,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ): Affine2D {
        require(viewWidthPx > 0 && viewHeightPx > 0) { "view size must be positive" }
        val upright = preview.uprightSize
        val fit = CameraPreviewGeometry.fit(
            viewWidthPx.toFloat(),
            viewHeightPx.toFloat(),
            upright.width.toFloat(),
            upright.height.toFloat(),
        )
        val radians = Math.toRadians(-preview.displayRotationDeg.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val m00 = fit.scale * c
        val m01 = -fit.scale * s
        val m10 = fit.scale * s
        val m11 = fit.scale * c
        val centerX = viewWidthPx / 2f
        val centerY = viewHeightPx / 2f
        val naturalCenterX = preview.naturalSize.width / 2f
        val naturalCenterY = preview.naturalSize.height / 2f
        val tx = centerX - (m00 * naturalCenterX + m01 * naturalCenterY)
        val ty = centerY - (m10 * naturalCenterX + m11 * naturalCenterY)
        return Affine2D(m00, m01, tx, m10, m11, ty)
    }
    /**
     * TextureView.setTransform applies its matrix on top of the default "buffer fills the
     * view" mapping D = diag(vw/nw, vh/nh), not instead of it. If [compose] is the desired
     * natural->view map N, the additive matrix is M = N ∘ D^-1: column 0 is scaled by
     * nw/vw, column 1 by nh/vh, while translation is unchanged.
     */
    fun viewAdditive(
        preview: CameraPreviewInfo,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ): Affine2D {
        val n = compose(preview, viewWidthPx, viewHeightPx)
        val nw = preview.naturalSize.width.toFloat()
        val nh = preview.naturalSize.height.toFloat()
        val sx = nw / viewWidthPx.toFloat()
        val sy = nh / viewHeightPx.toFloat()
        return Affine2D(
            m00 = n.m00 * sx,
            m01 = n.m01 * sy,
            m02 = n.m02,
            m10 = n.m10 * sx,
            m11 = n.m11 * sy,
            m12 = n.m12,
        )
    }
}
