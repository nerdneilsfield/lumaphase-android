package dev.lumaphase.android.recognition

import dev.lumaphase.android.camera.DoublePoint
import dev.lumaphase.android.camera.FrameLocator
import dev.lumaphase.android.camera.GrayFrame
import dev.lumaphase.android.camera.LocateFailure
import dev.lumaphase.android.camera.LocatedScreen
import dev.lumaphase.android.camera.ScreenLocator
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.RectPx
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.geometry.Geometry

enum class RecognitionStatus { NOT_LOCATED, NO_DECODE, DECODED }

/**
 * One screen band's decode. [decode] is null when the band is explicitly not decodable
 * (missing module or missing reference levels); no placeholder level is inserted.
 */
data class BandReading(
    val bandIndex: Int,
    val decode: PayloadDecode?,
    val missingModules: List<Int>,
    val referenceMissing: Boolean,
    val reason: String?,
)

data class RecognitionReading(
    val located: Boolean,
    val detectedMarkerIds: List<Int>,
    val missingMarkerIds: List<Int>,
    val geometryErrorPx: Double?,
    val sample: SampleResult?,
    val bands: List<BandReading>,
    val status: RecognitionStatus,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    /** Drawable -> upright-frame homography, exposed for overlay/diagnostics. */
    val homography: DoubleArray?,
    /** Detected marker corner groups in frame pixels; manual mode holds the dynamic quad. */
    val markerFrameCorners: List<List<DoublePoint>>,
    val manual: Boolean = false,
    val failureReason: String? = null,
    /** Raw ArUco ids matrix shape as reported by ScreenLocator, for diagnostics. */
    val locatorIdsRows: Int = 0,
    val locatorIdsCols: Int = 0,
)

/**
 * One-frame recognition pipeline: locate the four markers, sample the imported layout, and
 * decode each independent band with the existing [PayloadDecoder].
 *
 * `RecognitionStatus.DECODED` only means the decoder produced a result; it is not a CRC PASS.
 * The UI must inspect each [PayloadDecode.crcStatus]/[PayloadDecode.runnerStatus].
 *
 * Missing modules/references yield `NO_DECODE` with an explicit reason; no 0.5 fill and no
 * synthetic single-row aggregation. Manual four-corner mode reuses the same sampler/decoder with
 * a homography from the layout's dynamic rectangle to four operator points.
 */
class RecognitionPipeline(
    private val locator: FrameLocator = ScreenLocator(),
    private val homographyEstimator: (List<DoublePoint>, List<DoublePoint>) -> DoubleArray? =
        ::estimateManualHomography,
) {
    fun analyze(frame: GrayFrame, layout: ImportedLayout): RecognitionReading {
        val located = try {
            locator.locate(frame, layout.markers)
        } catch (error: Throwable) {
            return failure(frame, "LOCATOR_FAILED: ${error.message ?: error.javaClass.simpleName}")
        }
        if (located is LocateFailure) {
            return RecognitionReading(
                located = false,
                detectedMarkerIds = located.detectedMarkerIds,
                missingMarkerIds = located.missingMarkerIds,
                geometryErrorPx = null,
                sample = null,
                bands = emptyList(),
                status = RecognitionStatus.NOT_LOCATED,
                frameWidthPx = frame.width,
                frameHeightPx = frame.height,
                homography = null,
                markerFrameCorners = emptyList(),
                failureReason = located.reason,
                locatorIdsRows = located.idsRows,
                locatorIdsCols = located.idsCols,
            )
        }
        val screen = located as LocatedScreen
        val sample = try {
            FrameSampler.sample(frame, layout, screen.homography)
        } catch (error: Throwable) {
            return failure(
                frame,
                "SAMPLER_FAILED: ${error.message ?: error.javaClass.simpleName}",
                located = true,
                detected = screen.detectedMarkerIds,
                missing = screen.missingMarkerIds,
                rms = screen.reprojectionRmsPx,
                homography = screen.homography,
                markerCorners = screen.match.target.chunked(4),
                status = RecognitionStatus.NO_DECODE,
                idsRows = screen.idsRows,
                idsCols = screen.idsCols,
            )
        }
        return buildReading(
            frame = frame,
            layout = layout,
            sample = sample,
            detected = screen.detectedMarkerIds,
            missing = screen.missingMarkerIds,
            rms = screen.reprojectionRmsPx,
            homography = screen.homography,
            markerCorners = screen.match.target.chunked(4),
            manual = false,
            idsRows = screen.idsRows,
            idsCols = screen.idsCols,
        )
    }

    /**
     * Manual four-corner fallback. [targetPoints] are the four taps in upright-frame pixels
     * for the dynamic rectangle TL/TR/BR/BL; they are not marker corners and no pattern
     * rotation is applied again (the imported rect is already rotated).
     */
    fun analyzeManual(
        frame: GrayFrame,
        layout: ImportedLayout,
        targetPoints: List<DoublePoint>,
    ): RecognitionReading {
        if (targetPoints.size != 4) {
            return failure(frame, "MANUAL_CORNERS_INCOMPLETE")
        }
        val source = dev.lumaphase.android.camera.ManualGeometry.dynamicCorners(layout.dynamicRectPx)
        val homography = try {
            homographyEstimator(source, targetPoints)
        } catch (error: Throwable) {
            null
        }
        if (homography == null || homography.any { !it.isFinite() }) {
            return failure(frame, "MANUAL_HOMOGRAPHY_INVALID")
        }
        val sample = try {
            FrameSampler.sample(frame, layout, homography)
        } catch (error: Throwable) {
            return failure(
                frame,
                "SAMPLER_FAILED: ${error.message ?: error.javaClass.simpleName}",
                located = true,
                homography = homography,
                markerCorners = listOf(targetPoints),
                status = RecognitionStatus.NO_DECODE,
            )
        }
        return buildReading(
            frame = frame,
            layout = layout,
            sample = sample,
            detected = emptyList(),
            missing = emptyList(),
            rms = null,
            homography = homography,
            markerCorners = listOf(targetPoints),
            manual = true,
            idsRows = 0,
            idsCols = 0,
        )
    }

    private fun buildReading(
        frame: GrayFrame,
        layout: ImportedLayout,
        sample: SampleResult,
        detected: List<Int>,
        missing: List<Int>,
        rms: Double?,
        homography: DoubleArray,
        markerCorners: List<List<DoublePoint>>,
        manual: Boolean,
        idsRows: Int,
        idsCols: Int,
    ): RecognitionReading {
        val bands = sample.bands.map { band ->
            if (band.levels == null) {
                BandReading(
                    bandIndex = band.bandIndex,
                    decode = null,
                    missingModules = band.missingModules,
                    referenceMissing = band.referenceMissing,
                    reason = band.reason,
                )
            } else {
                val decoded = try {
                    PayloadDecoder.decode(
                        band.levels,
                        layout.profile,
                        PayloadDecoder.DEFAULT_THRESHOLD,
                    )
                } catch (error: Throwable) {
                    null
                }
                BandReading(
                    bandIndex = band.bandIndex,
                    decode = decoded,
                    missingModules = band.missingModules,
                    referenceMissing = band.referenceMissing,
                    reason = if (decoded == null) "DECODER_ERROR" else band.reason,
                )
            }
        }
        return RecognitionReading(
            located = true,
            detectedMarkerIds = detected,
            missingMarkerIds = missing,
            geometryErrorPx = rms,
            sample = sample,
            bands = bands,
            status = if (bands.any { it.decode != null }) {
                RecognitionStatus.DECODED
            } else {
                RecognitionStatus.NO_DECODE
            },
            frameWidthPx = frame.width,
            frameHeightPx = frame.height,
            homography = homography,
            markerFrameCorners = markerCorners,
            manual = manual,
            failureReason = bands.firstOrNull { it.decode == null }?.reason,
            locatorIdsRows = idsRows,
            locatorIdsCols = idsCols,
        )
    }

    private companion object {
        fun estimateManualHomography(
            source: List<DoublePoint>,
            target: List<DoublePoint>,
        ): DoubleArray? {
            val sourceMat = MatOfPoint2f(*source.map { Point(it.x, it.y) }.toTypedArray())
            val targetMat = MatOfPoint2f(*target.map { Point(it.x, it.y) }.toTypedArray())
            return try {
                val hMat = Geometry.findHomography(sourceMat, targetMat, 0)
                try {
                    if (hMat == null || hMat.empty()) null
                    else DoubleArray(9).also { hMat.get(0, 0, it) }
                } finally {
                    hMat?.release()
                }
            } finally {
                sourceMat.release()
                targetMat.release()
            }
        }
    }

    private fun failure(
        frame: GrayFrame,
        reason: String,
        located: Boolean = false,
        status: RecognitionStatus = RecognitionStatus.NOT_LOCATED,
        detected: List<Int> = emptyList(),
        missing: List<Int> = emptyList(),
        rms: Double? = null,
        homography: DoubleArray? = null,
        markerCorners: List<List<DoublePoint>> = emptyList(),
        idsRows: Int = 0,
        idsCols: Int = 0,
    ): RecognitionReading = RecognitionReading(
        located = located,
        detectedMarkerIds = detected,
        missingMarkerIds = missing,
        geometryErrorPx = rms,
        sample = null,
        bands = emptyList(),
        status = status,
        frameWidthPx = frame.width,
        frameHeightPx = frame.height,
        homography = homography,
        markerFrameCorners = markerCorners,
        failureReason = reason,
        locatorIdsRows = idsRows,
        locatorIdsCols = idsCols,
    )
}
