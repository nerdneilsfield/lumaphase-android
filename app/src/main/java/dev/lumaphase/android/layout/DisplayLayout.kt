package dev.lumaphase.android.layout

import dev.lumaphase.android.layout.PatternLayoutBuilder.ROTATIONS_DEG
import dev.lumaphase.android.protocol.LayoutProfile

enum class LayoutType(val wire: String) {
    OPTC("OPTC"),
    CALIBRATION_TARGET("CALIBRATION_TARGET"),
    FULLSCREEN_DIAGNOSTIC("FULLSCREEN_DIAGNOSTIC"),
}

enum class DiagnosticPattern(val wire: String) {
    WHITE("WHITE"),
    BLACK("BLACK"),
    CHECKERBOARD_2X2("CHECKERBOARD_2X2"),
    VSYNC_BLACK_WHITE("VSYNC_BLACK_WHITE"),
}

data class ReferenceRegion(
    val index: Int,
    val rectPx: RectPx,
    val level: Int,
)

data class CalibrationTarget(
    override val layoutId: String,
    val runLayoutId: String,
    override val drawableWidthPx: Int,
    override val drawableHeightPx: Int,
    override val patternWidthPx: Int,
    override val patternHeightPx: Int,
    override val patternRotationDeg: Int,
    override val patternToDrawable: List<Int>,
    val markers: List<MarkerLayout>,
    val references: List<ReferenceRegion>,
) : DisplayLayout() {
    override val layoutType: LayoutType get() = LayoutType.CALIBRATION_TARGET
}

data class FullscreenDiagnosticLayout(
    override val layoutId: String,
    override val drawableWidthPx: Int,
    override val drawableHeightPx: Int,
    override val patternWidthPx: Int,
    override val patternHeightPx: Int,
    override val patternRotationDeg: Int,
    override val patternToDrawable: List<Int>,
    val diagnosticPattern: DiagnosticPattern,
    val levelLow: Int = 0,
    val levelHigh: Int = 255,
) : DisplayLayout() {
    override val layoutType: LayoutType get() = LayoutType.FULLSCREEN_DIAGNOSTIC
}

/** A real display layout; only [Optc] carries payload modules. */
sealed class DisplayLayout {
    abstract val layoutId: String
    abstract val layoutType: LayoutType
    abstract val drawableWidthPx: Int
    abstract val drawableHeightPx: Int
    abstract val patternWidthPx: Int
    abstract val patternHeightPx: Int
    abstract val patternRotationDeg: Int
    abstract val patternToDrawable: List<Int>

    val optcLayout: PatternLayout?
        get() = (this as? Optc)?.layout

    data class Optc(val layout: PatternLayout) : DisplayLayout() {
        override val layoutId: String get() = layout.layoutId
        override val layoutType: LayoutType get() = LayoutType.OPTC
        override val drawableWidthPx: Int get() = layout.drawableWidthPx
        override val drawableHeightPx: Int get() = layout.drawableHeightPx
        override val patternWidthPx: Int get() = layout.patternWidthPx
        override val patternHeightPx: Int get() = layout.patternHeightPx
        override val patternRotationDeg: Int get() = layout.patternRotationDeg
        override val patternToDrawable: List<Int> get() = layout.patternToDrawable
    }
}

/** Builds the real large-Tag target from the actual RUN layout; no silent downscaling. */
object CalibrationTargetBuilder {
    fun build(layoutId: String, runLayout: PatternLayout): CalibrationTarget {
        require(layoutId.isNotBlank()) { "calibration layout_id must not be blank" }
        require(layoutId != runLayout.layoutId) { "CALIBRATION_TARGET must not reuse RUN layout_id" }
        val runMarkers = runLayout.markers
        require(runMarkers.map { it.id } == listOf(0, 1, 2, 3)) {
            "RUN layout must contain marker IDs 0..3"
        }
        val runCell = runMarkers.first().cellSizePx
        require(runMarkers.all { it.cellSizePx == runCell }) { "RUN marker cell sizes differ" }
        val cell = 3 * runCell
        val patternWidth = runLayout.patternWidthPx
        val patternHeight = runLayout.patternHeightPx
        val margin = 9 * cell
        require(patternWidth - 2 * margin >= 3 && patternHeight - 2 * margin >= 1) {
            "calibration target does not fit at fixed 3x cell=$cell in ${patternWidth}x$patternHeight"
        }
        val quietSquares = listOf(
            RectPx(cell, cell, margin, margin),
            RectPx(patternWidth - margin, cell, patternWidth - cell, margin),
            RectPx(patternWidth - margin, patternHeight - margin, patternWidth - cell, patternHeight - cell),
            RectPx(cell, patternHeight - margin, margin, patternHeight - cell),
        )
        val rotation = runLayout.patternRotationDeg
        val markers = quietSquares.mapIndexed { id, quiet ->
            val outer = RectPx(
                quiet.x0 + cell,
                quiet.y0 + cell,
                quiet.x1 - cell,
                quiet.y1 - cell,
            )
            val bitmap = ArUcoDict4x4_50.bitmap(id)
            val cellRects = ArrayList<RectPx>(bitmap.size * bitmap.size)
            val cellValues = ArrayList<Int>(bitmap.size * bitmap.size)
            for (row in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                for (column in 0 until ArUcoDict4x4_50.MARKER_SIZE_BITS) {
                    cellRects += mapRectToDrawable(
                        rotation, patternWidth, patternHeight, 0, 0,
                        RectPx(
                            outer.x0 + (column + 1) * cell,
                            outer.y0 + (row + 1) * cell,
                            outer.x0 + (column + 2) * cell,
                            outer.y0 + (row + 2) * cell,
                        ),
                    )
                    cellValues += bitmap[row][column]
                }
            }
            MarkerLayout(
                id = id,
                dictionary = ArUcoDict4x4_50.DICTIONARY,
                markerSizeBits = ArUcoDict4x4_50.MARKER_SIZE_BITS,
                borderBits = ArUcoDict4x4_50.BORDER_BITS,
                cellSizePx = cell,
                quietZonePx = cell,
                cornersPx = listOf(
                    PointPx(outer.x0, outer.y0),
                    PointPx(outer.x1, outer.y0),
                    PointPx(outer.x1, outer.y1),
                    PointPx(outer.x0, outer.y1),
                ).map { mapPointToDrawable(rotation, patternWidth, patternHeight, 0, 0, it.x, it.y) },
                quietRectPx = mapRectToDrawable(rotation, patternWidth, patternHeight, 0, 0, quiet),
                outerRectPx = mapRectToDrawable(rotation, patternWidth, patternHeight, 0, 0, outer),
                cellRects = cellRects,
                cellValues = cellValues,
            )
        }
        val referenceWidth = patternWidth - 2 * margin
        val references = listOf(0, 128, 255).mapIndexed { index, level ->
            ReferenceRegion(
                index = index,
                rectPx = mapRectToDrawable(
                    rotation, patternWidth, patternHeight, 0, 0,
                    RectPx(
                        margin + index * referenceWidth / 3,
                        margin,
                        margin + (index + 1) * referenceWidth / 3,
                        patternHeight - margin,
                    ),
                ),
                level = level,
            )
        }
        return CalibrationTarget(
            layoutId = layoutId,
            runLayoutId = runLayout.layoutId,
            drawableWidthPx = runLayout.drawableWidthPx,
            drawableHeightPx = runLayout.drawableHeightPx,
            patternWidthPx = patternWidth,
            patternHeightPx = patternHeight,
            patternRotationDeg = rotation,
            patternToDrawable = patternToDrawableMatrix(rotation, patternWidth, patternHeight, 0, 0).toList(),
            markers = markers,
            references = references,
        )
    }
}

object FullscreenDiagnosticBuilder {
    fun build(
        layoutId: String,
        drawableWidthPx: Int,
        drawableHeightPx: Int,
        patternRotationDeg: Int,
        pattern: DiagnosticPattern,
    ): FullscreenDiagnosticLayout {
        require(layoutId.isNotBlank()) { "diagnostic layout_id must not be blank" }
        require(patternRotationDeg in ROTATIONS_DEG) { "unsupported pattern rotation $patternRotationDeg" }
        val quarter = patternRotationDeg % 180 != 0
        val patternWidth = if (quarter) drawableHeightPx else drawableWidthPx
        val patternHeight = if (quarter) drawableWidthPx else drawableHeightPx
        return FullscreenDiagnosticLayout(
            layoutId = layoutId,
            drawableWidthPx = drawableWidthPx,
            drawableHeightPx = drawableHeightPx,
            patternWidthPx = patternWidth,
            patternHeightPx = patternHeight,
            patternRotationDeg = patternRotationDeg,
            patternToDrawable = patternToDrawableMatrix(
                patternRotationDeg, patternWidth, patternHeight, 0, 0,
            ).toList(),
            diagnosticPattern = pattern,
        )
    }
}
