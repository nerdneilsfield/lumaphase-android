package dev.lumaphase.android.render

import dev.lumaphase.android.timing.RenderStats
import java.util.UUID

/**
 * Identity and counters of one dynamic preview run.
 *
 * A run spans Surface rebuilds: recreating the Surface allocates a new
 * `surface_instance_id` only, while `run_id`, the render sequence and the application's
 * `native_frame_id` values keep counting and the counters carry over. EGL frame ids are
 * scoped to a single Surface and are never carried across, so they stay with the pending
 * queue on the render thread rather than here.
 *
 * The preview host owns exactly one of these per preview, so starting a new preview starts a
 * new run whose first dynamic state is sequence 0. There is no registry and no other state.
 */
class DynamicRunState {
    val runId: String = "run-${UUID.randomUUID()}"

    /** Continuation across Surface rebuilds; written on the render thread through [publish]. */
    var nextRenderSeq: ULong = 0uL
        private set
    var nextNativeFrameId: Long = 0L
        private set
    var carriedStats: RenderStats = RenderStats.EMPTY
        private set

    internal fun publish(nextRenderSeq: ULong, nextNativeFrameId: Long, stats: RenderStats) {
        this.nextRenderSeq = nextRenderSeq
        this.nextNativeFrameId = nextNativeFrameId
        this.carriedStats = stats
    }

    /**
     * An explicitly started new session begins its dynamic sequence at zero and counts its
     * frames from zero (contract section 1). Nothing else resets it: a pause or a Surface
     * rebuild continues the same run.
     */
    fun resetForNewSession() {
        nextRenderSeq = 0uL
        nextNativeFrameId = 0L
        carriedStats = RenderStats.EMPTY
    }
}
