package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.RectPx

/** The four manual-corner source points, in dynamic-rect TL/TR/BR/BL order. */
object ManualGeometry {
    fun dynamicCorners(rect: RectPx): List<DoublePoint> = listOf(
        DoublePoint(rect.x0.toDouble(), rect.y0.toDouble()),
        DoublePoint(rect.x1.toDouble(), rect.y0.toDouble()),
        DoublePoint(rect.x1.toDouble(), rect.y1.toDouble()),
        DoublePoint(rect.x0.toDouble(), rect.y1.toDouble()),
    )
}
