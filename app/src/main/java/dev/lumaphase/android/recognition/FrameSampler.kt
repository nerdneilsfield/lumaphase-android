package dev.lumaphase.android.recognition

import dev.lumaphase.android.camera.DoublePoint
import dev.lumaphase.android.camera.GrayFrame
import dev.lumaphase.android.camera.LocatorMath
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.ModuleRect
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SampleMode { GRID, SINGLE_ROW }

/**
 * One independently sampled screen band. Grid layouts have exactly one band; a single-row
 * layout keeps its thin bands separate. [levels] is null unless every module and all four
 * reference modules were present for this band; there is deliberately no 0.5 placeholder.
 */
data class SampleBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

data class BandSample(
    val bandIndex: Int,
    val rawMeans: Map<Int, Double>,
    val pixelCounts: Map<Int, Int>,
    val sampleBounds: Map<Int, SampleBounds>,
    val levels: DoubleArray?,
    val missingModules: List<Int>,
    val referenceMissing: Boolean,
    val reason: String?,
)

data class SampleResult(
    val mode: SampleMode,
    val moduleBandCounts: List<Int>,
    val bands: List<BandSample>,
    /** Projected module corners in upright-frame pixels, for an overlay/diagnostic consumer. */
    val moduleFrameCorners: Map<Int, List<DoublePoint>>,
)

/**
 * Pure-Kotlin sampler ported from the relevant core `sampling.py` rules, with the requested
 * single-row correction:
 *
 * - Grid: central `sampling_inner_fraction` (0.6) width and height, one band.
 * - Single row: central 60% of the **short** module axis; the long axis is split at the
 *   `sample()` layer by shared logical bands, not per module. For pattern rotation 0/180 the
 *   long axis is drawable y (vertical stripes); for 90/270 it is drawable x (horizontal
 *   stripes). Every module's band k therefore covers the same logical band and bands remain
 *   independent (no cross-band synthesis).
 * - Positions: grid centers, capped at 8; homography maps drawable coordinates to upright frame
 *   pixels; repeated pixels are deduplicated before mean.
 * - Normalization: reference modules 0/3 are low and 1/2 high, per band, never global
 *   P10/P90 and never the layout's display levels.
 * - Missing modules/reference make the band explicitly not decodable. No 0.5 fill is allowed
 *   to turn it into a hard PASS.
 *
 * The sampler intentionally does not model black/saturation levels: Android has no camera
 * profile here, so `saturated = value >= 255` and every pixel is usable.
 */
object FrameSampler {
    const val INNER_FRACTION = 0.6
    const val BAND_HEIGHT_PX = 2.0
    const val MINIMUM_MODULE_WIDTH_PX = 4.0
    const val MAX_POSITIONS = 8
    const val SATURATION_LEVEL = 255

    private enum class LongAxis { VERTICAL, HORIZONTAL }

    private data class LegacyPlan(
        val axis: LongAxis,
        val bandCount: Int,
        val ranges: List<Pair<Double, Double>>,
    )

    fun sample(
        frame: GrayFrame,
        layout: ImportedLayout,
        homography: DoubleArray,
    ): SampleResult {
        require(layout.modules.size == layout.profile.moduleCount) {
            "layout modules do not match ${layout.profile.name}"
        }
        val mode = if (layout.geometry?.isGrid == true) SampleMode.GRID else SampleMode.SINGLE_ROW
        val legacyPlan = if (mode == SampleMode.SINGLE_ROW) {
            legacyPlan(layout, homography)
        } else {
            null
        }
        val moduleBands = LinkedHashMap<Int, List<ModuleBand>>()
        val moduleCorners = LinkedHashMap<Int, List<DoublePoint>>()
        for (module in layout.modules) {
            val sampled = sampleModule(frame, module, homography, mode, legacyPlan)
            moduleBands[module.index] = sampled.bands
            moduleCorners[module.index] = sampled.corners
        }

        val bandCount = moduleBands.values.maxOfOrNull { it.size } ?: 0
        val bands = ArrayList<BandSample>(bandCount)
        for (bandIndex in 0 until bandCount) {
            val raw = LinkedHashMap<Int, Double>()
            val counts = LinkedHashMap<Int, Int>()
            val bounds = LinkedHashMap<Int, SampleBounds>()
            for (module in layout.modules) {
                val band = moduleBands[module.index]?.firstOrNull { it.bandIndex == bandIndex }
                if (band != null) {
                    raw[module.index] = band.rawMean
                    counts[module.index] = band.pixelCount
                    bounds[module.index] = band.bounds
                }
            }
            val missing = (0 until layout.profile.moduleCount).filter { it !in raw }
            val reference = referenceLevels(raw)
            val levels = if (missing.isEmpty() && reference != null) {
                val (low, high) = reference
                DoubleArray(layout.profile.moduleCount) { index ->
                    (raw.getValue(index) - low) / (high - low)
                }.takeIf { values -> values.all { it.isFinite() } }
            } else {
                null
            }
            val reason = when {
                levels != null -> null
                missing.isNotEmpty() -> "MISSING_MODULES"
                reference == null -> "REFERENCE_LEVELS_UNAVAILABLE"
                else -> "NON_FINITE_LEVELS"
            }
            bands += BandSample(
                bandIndex = bandIndex,
                rawMeans = raw,
                pixelCounts = counts,
                sampleBounds = bounds,
                levels = levels,
                missingModules = missing,
                referenceMissing = reference == null,
                reason = reason,
            )
        }
        return SampleResult(
            mode = mode,
            moduleBandCounts = layout.modules.map { moduleBands[it.index]?.size ?: 0 },
            bands = bands,
            moduleFrameCorners = moduleCorners,
        )
    }

    /**
     * One common long-axis band grid for the legacy single row. The pattern rotation decides
     * whether the original tall stripe is now vertical (0/180) or horizontal (90/270), so we
     * never split a rotated stripe along a fixed drawable y.
     */
    private fun legacyPlan(layout: ImportedLayout, homography: DoubleArray): LegacyPlan {
        val axis = if (layout.patternRotationDeg % 180 == 0) {
            LongAxis.VERTICAL
        } else {
            LongAxis.HORIZONTAL
        }
        val dynamic = layout.dynamicRectPx
        val corners = listOf(
            DoublePoint(dynamic.x0.toDouble(), dynamic.y0.toDouble()),
            DoublePoint(dynamic.x1.toDouble(), dynamic.y0.toDouble()),
            DoublePoint(dynamic.x1.toDouble(), dynamic.y1.toDouble()),
            DoublePoint(dynamic.x0.toDouble(), dynamic.y1.toDouble()),
        ).map { LocatorMath.applyHomography(homography, it.x, it.y) }
        val projectedWidth = if (corners.any { it == null }) {
            Double.NaN
        } else {
            val p = corners.map { it!! }
            (distance(p[0], p[1]) + distance(p[2], p[3])) / 2.0
        }
        val projectedHeight = if (corners.any { it == null }) {
            Double.NaN
        } else {
            val p = corners.map { it!! }
            (distance(p[3], p[0]) + distance(p[2], p[1])) / 2.0
        }
        val long = if (axis == LongAxis.VERTICAL) projectedHeight else projectedWidth
        val bandCount = if (long.isFinite()) {
            max(1, (long / BAND_HEIGHT_PX).roundToInt())
        } else {
            1
        }
        val start = if (axis == LongAxis.VERTICAL) dynamic.y0.toDouble() else dynamic.x0.toDouble()
        val end = if (axis == LongAxis.VERTICAL) dynamic.y1.toDouble() else dynamic.x1.toDouble()
        // Band index follows the original logical long axis, not raw drawable direction:
        // 0 ascending y; 180 descending y; 90 descending x; 270 ascending x.
        val ascending = when (layout.patternRotationDeg) {
            0, 270 -> true
            90, 180 -> false
            else -> true
        }
        val ranges = List(bandCount) { index ->
            val firstOffset = index.toDouble() * (end - start) / bandCount
            val secondOffset = (index + 1).toDouble() * (end - start) / bandCount
            if (ascending) {
                (start + firstOffset) to (start + secondOffset)
            } else {
                (end - secondOffset) to (end - firstOffset)
            }
        }
        return LegacyPlan(axis, bandCount, ranges)
    }

    private data class ModuleBand(
        val bandIndex: Int,
        val rawMean: Double,
        val pixelCount: Int,
        val bounds: SampleBounds,
    )
    private data class SampledModule(val bands: List<ModuleBand>, val corners: List<DoublePoint>)

    private fun sampleModule(
        frame: GrayFrame,
        module: ModuleRect,
        homography: DoubleArray,
        mode: SampleMode,
        legacyPlan: LegacyPlan?,
    ): SampledModule {
        val rect = module.rectPx
        val cornerPoints = listOf(
            DoublePoint(rect.x0.toDouble(), rect.y0.toDouble()),
            DoublePoint(rect.x1.toDouble(), rect.y0.toDouble()),
            DoublePoint(rect.x1.toDouble(), rect.y1.toDouble()),
            DoublePoint(rect.x0.toDouble(), rect.y1.toDouble()),
        )
        val projected = cornerPoints.map { LocatorMath.applyHomography(homography, it.x, it.y) }
        if (projected.any { it == null }) return SampledModule(emptyList(), emptyList())
        val pixels = projected.map { it!! }
        val projectedWidth = (distance(pixels[0], pixels[1]) + distance(pixels[2], pixels[3])) / 2.0
        val projectedHeight = (distance(pixels[3], pixels[0]) + distance(pixels[2], pixels[1])) / 2.0
        if (!projectedWidth.isFinite() || !projectedHeight.isFinite()) {
            return SampledModule(emptyList(), pixels)
        }
        val edgeLength = minOf(
            distance(pixels[0], pixels[1]),
            distance(pixels[1], pixels[2]),
            distance(pixels[2], pixels[3]),
            distance(pixels[3], pixels[0]),
        )
        val samples = ArrayList<ModuleBand>(legacyPlan?.bandCount ?: 1)
        if (mode == SampleMode.GRID) {
            val innerX0 = rect.x0 + 0.5 * (1.0 - INNER_FRACTION) * rect.widthPx
            val innerX1 = rect.x1 - 0.5 * (1.0 - INNER_FRACTION) * rect.widthPx
            val innerY0 = rect.y0 + 0.5 * (1.0 - INNER_FRACTION) * rect.heightPx
            val innerY1 = rect.y1 - 0.5 * (1.0 - INNER_FRACTION) * rect.heightPx
            val nx = max(1, min(4, (projectedWidth / 2.0).roundToInt()))
            val ny = max(1, min(4, (projectedHeight / 2.0).roundToInt()))
            sampleBand(
                frame, homography, 0, innerX0, innerY0, innerX1, innerY1, nx, ny,
                edgeLength, samples,
            )
            return SampledModule(samples, pixels)
        }

        val plan = legacyPlan ?: return SampledModule(emptyList(), pixels)
        val limitingSize = if (plan.axis == LongAxis.VERTICAL) projectedWidth else projectedHeight
        val shortInnerX0 = rect.x0 + 0.5 * (1.0 - INNER_FRACTION) * rect.widthPx
        val shortInnerX1 = rect.x1 - 0.5 * (1.0 - INNER_FRACTION) * rect.widthPx
        val shortInnerY0 = rect.y0 + 0.5 * (1.0 - INNER_FRACTION) * rect.heightPx
        val shortInnerY1 = rect.y1 - 0.5 * (1.0 - INNER_FRACTION) * rect.heightPx
        val bandProjected = if (plan.axis == LongAxis.VERTICAL) {
            projectedHeight / plan.bandCount
        } else {
            projectedWidth / plan.bandCount
        }
        for ((bandIndex, range) in plan.ranges.withIndex()) {
            val bandX0: Double
            val bandX1: Double
            val bandY0: Double
            val bandY1: Double
            if (plan.axis == LongAxis.VERTICAL) {
                bandX0 = shortInnerX0
                bandX1 = shortInnerX1
                bandY0 = max(range.first, rect.y0.toDouble())
                bandY1 = min(range.second, rect.y1.toDouble())
                if (bandY1 <= bandY0) continue
                val nx = max(1, min(4, (projectedWidth / 2.0).roundToInt()))
                val ny = max(1, min(2, (bandProjected / 2.0).roundToInt()))
                sampleBand(
                    frame, homography, bandIndex, bandX0, bandY0, bandX1, bandY1, nx, ny,
                    limitingSize, samples,
                )
            } else {
                bandX0 = max(range.first, rect.x0.toDouble())
                bandX1 = min(range.second, rect.x1.toDouble())
                bandY0 = shortInnerY0
                bandY1 = shortInnerY1
                if (bandX1 <= bandX0) continue
                val nx = max(1, min(4, (bandProjected / 2.0).roundToInt()))
                val ny = max(1, min(2, (projectedHeight / 2.0).roundToInt()))
                sampleBand(
                    frame, homography, bandIndex, bandX0, bandY0, bandX1, bandY1, nx, ny,
                    limitingSize, samples,
                )
            }
        }
        return SampledModule(samples, pixels)
    }

    private fun sampleBand(
        frame: GrayFrame,
        homography: DoubleArray,
        bandIndex: Int,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        nx: Int,
        ny: Int,
        limitingSize: Double,
        output: MutableList<ModuleBand>,
    ) {
        val points = gridPoints(x0, y0, x1, y1, nx, ny)
        if (points.isEmpty()) return
        val framePoints = points.mapNotNull { point ->
            LocatorMath.applyHomography(homography, point.first, point.second)
        }
        if (framePoints.size != points.size) return
        val seen = HashSet<Int>()
        val values = ArrayList<Int>(framePoints.size)
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (point in framePoints) {
            val col = floor(point.x + 0.5).toInt()
            val row = floor(point.y + 0.5).toInt()
            if (col < 0 || col >= frame.width || row < 0 || row >= frame.height) continue
            val key = row * frame.width + col
            if (!seen.add(key)) continue
            val value = frame.pixels[key].toInt() and 0xFF
            if (value < SATURATION_LEVEL) {
                values += value
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
        }
        if (values.isEmpty() || limitingSize < MINIMUM_MODULE_WIDTH_PX) return
        output += ModuleBand(
            bandIndex = bandIndex,
            rawMean = values.average(),
            pixelCount = values.size,
            bounds = SampleBounds(minX, minY, maxX, maxY),
        )
    }

    private fun referenceLevels(rawMeans: Map<Int, Double>): Pair<Double, Double>? {
        val low0 = rawMeans[0] ?: return null
        val low3 = rawMeans[3] ?: return null
        val high1 = rawMeans[1] ?: return null
        val high2 = rawMeans[2] ?: return null
        val low = (low0 + low3) / 2.0
        val high = (high1 + high2) / 2.0
        return if (high != low && high.isFinite() && low.isFinite()) low to high else null
    }

    private fun gridPoints(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        nx: Int,
        ny: Int,
    ): List<Pair<Double, Double>> {
        val xs = cellCenters(x0, x1, nx)
        val ys = cellCenters(y0, y1, ny)
        val all = ArrayList<Pair<Double, Double>>(nx * ny)
        for (y in ys) for (x in xs) all += x to y
        if (all.size <= MAX_POSITIONS) return all
        return List(MAX_POSITIONS) { index ->
            val position = if (MAX_POSITIONS == 1) 0.0 else {
                index.toDouble() * (all.size - 1) / (MAX_POSITIONS - 1)
            }
            all[floor(position + 0.5).toInt().coerceIn(0, all.lastIndex)]
        }
    }

    private fun cellCenters(start: Double, end: Double, count: Int): List<Double> {
        if (count <= 0) return emptyList()
        val step = (end - start) / count
        return List(count) { index -> start + step * (index + 0.5) }
    }

    private fun distance(a: DoublePoint, b: DoublePoint): Double = hypot(a.x - b.x, a.y - b.y)
}
