package dev.lumaphase.android.camera

import dev.lumaphase.android.layout.ImportedMarker
import java.util.Locale
import org.opencv.android.OpenCVLoader
import org.opencv.geometry.Geometry
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

data class DoublePoint(val x: Double, val y: Double)

/** The 16 drawable/frame corner pairs in layout-marker order, never image-position order. */
data class CornerMatch(
    val source: List<DoublePoint>,
    val target: List<DoublePoint>,
    val usedMarkerIds: List<Int>,
    val missingMarkerIds: List<Int>,
) {
    val complete: Boolean get() = missingMarkerIds.isEmpty() && source.size == 16
}

data class LocatedScreen(
    val homography: DoubleArray,
    val detectedMarkerIds: List<Int>,
    val missingMarkerIds: List<Int>,
    val match: CornerMatch,
    val reprojectionRmsPx: Double?,
    val idsRows: Int = 0,
    val idsCols: Int = 0,
) : LocateResult

data class LocateFailure(
    val detectedMarkerIds: List<Int>,
    val missingMarkerIds: List<Int>,
    val reason: String,
    val idsRows: Int = 0,
    val idsCols: Int = 0,
) : LocateResult

/**
 * Flattens the detector's id matrix in its own row-major storage order. Both legal shapes
 * Nx1 and 1xN (and the 1-D Mat that Java exposes as one of those) are consumed by total
 * element count, never by rows alone. The id at flat index k belongs to corners[k].
 */
internal fun markerIdsInFlatOrder(rows: Int, cols: Int, values: IntArray): IntArray {
    require(rows >= 0 && cols >= 0) { "id matrix shape must be non-negative" }
    require(rows == 0 || cols == 0 || rows * cols == values.size) {
        "id matrix ${rows}x${cols} does not match ${values.size} values"
    }
    return values
}

sealed interface LocateResult

fun interface FrameLocator {
    fun locate(frame: GrayFrame, markers: List<ImportedMarker>): LocateResult
}

/**
 * Pure matching/matrix helpers for [ScreenLocator]. Keeping these separate lets host JVM tests
 * exercise the 4/4, corner-order and homography arithmetic without loading any native OpenCV
 * library.
 */
object LocatorMath {
    /**
     * Matches each layout marker by ID to the detected marker's own corner order. Markers are
     * consumed in layout order (which is the contract's marker order); neither list is sorted
     * by position in the image. Any missing ID invalidates the whole 4-of-4 match.
     */
    fun matchCorners(
        markers: List<ImportedMarker>,
        detected: Map<Int, DoubleArray>,
    ): CornerMatch {
        val source = ArrayList<DoublePoint>(16)
        val target = ArrayList<DoublePoint>(16)
        val used = ArrayList<Int>(4)
        val missing = ArrayList<Int>(4)
        for (marker in markers) {
            val corners = detected[marker.id]
            if (corners == null || corners.size != 8 || marker.cornersPx.size != 4) {
                missing += marker.id
                continue
            }
            used += marker.id
            marker.cornersPx.forEachIndexed { index, point ->
                source += DoublePoint(point.x.toDouble(), point.y.toDouble())
                target += DoublePoint(corners[2 * index], corners[2 * index + 1])
            }
        }
        return CornerMatch(source, target, used, missing)
    }

    /** Applies a row-major 3x3 homography; null when the projective denominator is degenerate. */
    fun applyHomography(h: DoubleArray, x: Double, y: Double): DoublePoint? {
        require(h.size == 9) { "homography must have 9 values" }
        val w = h[6] * x + h[7] * y + h[8]
        if (!w.isFinite() || kotlin.math.abs(w) < 1e-12) return null
        val u = (h[0] * x + h[1] * y + h[2]) / w
        val v = (h[3] * x + h[4] * y + h[5]) / w
        return if (u.isFinite() && v.isFinite()) DoublePoint(u, v) else null
    }

    /** RMS of the 16 marker-corner residuals; diagnostic only, not an accept/reject gate. */
    fun reprojectionRms(
        h: DoubleArray,
        source: List<DoublePoint>,
        target: List<DoublePoint>,
    ): Double? {
        if (source.isEmpty() || source.size != target.size) return null
        var sum = 0.0
        for (index in source.indices) {
            val mapped = applyHomography(h, source[index].x, source[index].y) ?: return null
            val dx = mapped.x - target[index].x
            val dy = mapped.y - target[index].y
            sum += dx * dx + dy * dy
        }
        return kotlin.math.sqrt(sum / source.size)
    }
}

/**
 * ArUco + homography locator for the back-camera upright grayscale frame.
 *
 * This uses the official OpenCV 5.1.0 Android AAR. OpenCV 5.1 exposes Java homography as
 * `org.opencv.geometry.Geometry.findHomography`; the exact overload was checked against the
 * pinned AAR. The homography is fitted in **frame pixels**, not core's normalized
 * camera coordinates; that simplification is reported and is not equivalent to the Python
 * core's calibrated projection path.
 */
class ScreenLocator : FrameLocator {
    // OpenCVLoader must run before the dictionary/detector constructors touch JNI, so these
    // are lazy and are first evaluated inside locate().
    private val dictionary by lazy { Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50) }
    private val detector by lazy { ArucoDetector(dictionary, DetectorParameters()) }

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library could not be loaded" }
    }

    override fun locate(frame: GrayFrame, markers: List<ImportedMarker>): LocateResult {
        val gray = Mat(frame.height, frame.width, CvType.CV_8UC1)
        val corners = ArrayList<Mat>()
        val ids = Mat()
        try {
            gray.put(0, 0, frame.pixels)
            detector.detectMarkers(gray, corners, ids)
            val detected = HashMap<Int, DoubleArray>()
            val idsRows = ids.rows()
            val idsCols = ids.cols()
            val idCount = ids.total().toInt()
            val idValues = IntArray(idCount)
            if (idCount > 0) {
                // CV_32SC1; Java Mat.get(row, col, int[]) fills the requested data.length
                // starting at (0,0), independent of Nx1 vs 1xN. Its int return is native status,
                // not an element count, so consume the filled array instead.
                if (ids.type() != CvType.CV_32SC1) {
                    return LocateFailure(
                        emptyList(),
                        markers.map { it.id },
                        "IDS_MAT_TYPE_${ids.type()}",
                        idsRows,
                        idsCols,
                    )
                }
                // Mat.get returns 0/native status, not an element count; the bytecode above
                // proves it fills the requested data.length. Consume the filled array as-is.
                ids.get(0, 0, idValues)
                check(markerIdsInFlatOrder(idsRows, idsCols, idValues).size == idCount)
            }
            // Same flat index k in the id array and the corners vector; never index by rows.
            for (index in 0 until idCount) {
                val id = idValues[index]
                if (id !in 0..3) continue
                val cornerMat = corners.getOrNull(index) ?: continue
                if (cornerMat.total() * cornerMat.channels() != 8L) continue
                val floats = FloatArray(8)
                cornerMat.get(0, 0, floats)
                detected[id] = DoubleArray(8) { floats[it].toDouble() }
            }
            val detectedIds = detected.keys.sorted()
            val match = LocatorMath.matchCorners(markers, detected)
            if (!match.complete) {
                return LocateFailure(
                    detectedMarkerIds = detectedIds,
                    missingMarkerIds = match.missingMarkerIds,
                    reason = if (match.missingMarkerIds.isEmpty()) {
                        "MARKER_CORNER_SHAPE_INVALID"
                    } else {
                        "MISSING_MARKERS"
                    },
                    idsRows = idsRows,
                    idsCols = idsCols,
                )
            }

            val source = MatOfPoint2f(*match.source.map { Point(it.x, it.y) }.toTypedArray())
            val target = MatOfPoint2f(*match.target.map { Point(it.x, it.y) }.toTypedArray())
            try {
                val hMat = Geometry.findHomography(source, target, 0) ?: return LocateFailure(
                    detectedIds,
                    emptyList(),
                    "HOMOGRAPHY_FAILED",
                    idsRows,
                    idsCols,
                )
                try {
                    if (hMat.empty()) {
                        return LocateFailure(detectedIds, emptyList(), "HOMOGRAPHY_EMPTY", idsRows, idsCols)
                    }
                    val h = DoubleArray(9)
                    hMat.get(0, 0, h)
                    if (h.any { !it.isFinite() }) {
                        return LocateFailure(detectedIds, emptyList(), "HOMOGRAPHY_NOT_FINITE", idsRows, idsCols)
                    }
                    return LocatedScreen(
                        homography = h,
                        detectedMarkerIds = detectedIds,
                        missingMarkerIds = emptyList(),
                        match = match,
                        reprojectionRmsPx = LocatorMath.reprojectionRms(h, match.source, match.target),
                        idsRows = idsRows,
                        idsCols = idsCols,
                    )
                } finally {
                    hMat.release()
                }
            } finally {
                source.release()
                target.release()
            }
        } catch (error: Throwable) {
            return LocateFailure(
                detectedMarkerIds = emptyList(),
                missingMarkerIds = markers.map { it.id },
                reason = "OPENCV_DETECTION_FAILED: ${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            corners.forEach { it.release() }
            ids.release()
            gray.release()
        }
    }

    companion object {
        fun formatRms(value: Double?): String =
            value?.let { String.format(Locale.ROOT, "%.1f px", it) } ?: "—"
    }
}
