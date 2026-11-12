package dev.lumaphase.android.layout

/** Real drawable rectangles for the typed display targets; no protocol modules are invented. */
fun calibrationPaintRects(target: CalibrationTarget): List<LevelRect> {
    val rects = ArrayList<LevelRect>()
    for (marker in target.markers) {
        rects += LevelRect(marker.quietRectPx, MARKER_WHITE_LEVEL)
        rects += LevelRect(marker.outerRectPx, MARKER_BLACK_LEVEL)
        marker.cellRects.forEachIndexed { index, cell ->
            rects += LevelRect(
                cell,
                if (marker.cellValues[index] == 1) MARKER_WHITE_LEVEL else MARKER_BLACK_LEVEL,
            )
        }
    }
    target.references.forEach { rects += LevelRect(it.rectPx, it.level) }
    return rects
}

/**
 * Fullscreen diagnostic rectangles in drawable pixels. [nativeFrameId] is the identity
 * allocated for this submission; VSYNC_BLACK_WHITE uses its integer parity, not render_seq
 * or a confirmed-present rank. The background is cleared black by RectRenderer.
 */
fun fullscreenDiagnosticRects(
    layout: FullscreenDiagnosticLayout,
    nativeFrameId: Long,
): List<LevelRect> = when (layout.diagnosticPattern) {
    DiagnosticPattern.WHITE -> listOf(
        LevelRect(RectPx(0, 0, layout.drawableWidthPx, layout.drawableHeightPx), 255),
    )
    DiagnosticPattern.BLACK -> listOf(
        LevelRect(RectPx(0, 0, layout.drawableWidthPx, layout.drawableHeightPx), 0),
    )
    DiagnosticPattern.VSYNC_BLACK_WHITE -> listOf(
        LevelRect(
            RectPx(0, 0, layout.drawableWidthPx, layout.drawableHeightPx),
            if (nativeFrameId % 2L == 0L) 255 else 0,
        ),
    )
    DiagnosticPattern.CHECKERBOARD_2X2 -> {
        val halfWidth = layout.patternWidthPx / 2
        val halfHeight = layout.patternHeightPx / 2
        listOf(
            RectPx(0, 0, halfWidth, halfHeight),
            RectPx(halfWidth, halfHeight, layout.patternWidthPx, layout.patternHeightPx),
        ).map { logical ->
            mapRectToDrawable(
                layout.patternRotationDeg,
                layout.patternWidthPx,
                layout.patternHeightPx,
                0,
                0,
                logical,
            )
        }.map { LevelRect(it, 255) }
    }
}
