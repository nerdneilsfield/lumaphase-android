package dev.lumaphase.android.session

import dev.lumaphase.android.render.DynamicRunState
import java.io.File

/**
 * The one session running in this process, if there is one.
 *
 * It lives outside the Activity on purpose: recreating the Activity must not silently start
 * a second session or mix a fresh identity into a running one, so a recreated Activity
 * attaches to whatever is already running. It holds exactly one controller, its directory,
 * its run state and the single phase-timer owner; there is no registry, no service and no
 * lookup by key.
 */
object ActiveSession {
    @Volatile
    var controller: SessionController? = null
        private set

    @Volatile
    var directory: File? = null
        private set

    @Volatile
    var runState: DynamicRunState? = null
        private set

    /**
     * The one owner of the phase timer. Kept here so a recreated Activity continues the same
     * run instead of arming a second chain, and so the drain completion finishes on whichever
     * Activity is in charge when it arrives.
     */
    @Volatile
    var runHost: PreviewRunHost? = null
        private set

    /** The run state of the active session, created once and shared across Activity instances. */
    fun ensureRunState(): DynamicRunState = runState ?: DynamicRunState().also { runState = it }

    /**
     * Layout identities, kept here so a recreated Activity continues the running session
     * instead of reusing a layout id that an older segment already references.
     */
    @Volatile
    var layoutCounter: Int = 0

    /**
     * Identity of the layout currently registered: drawable size, pattern rotation and the
     * requested layout choice. A change to any of them is a new immutable layout object and a
     * new segment; the old layout object is never overwritten.
     */
    @Volatile
    var currentLayoutKey: String? = null

    @Volatile
    var currentLayoutId: String = ""

    fun begin(controller: SessionController, directory: File, runState: DynamicRunState) {
        this.controller = controller
        this.directory = directory
        this.runState = runState
        runHost = null
        layoutCounter = 0
        currentLayoutKey = null
        currentLayoutId = ""
    }

    /** Installs the phase-timer owner of the session that is starting. */
    fun adoptRunHost(host: PreviewRunHost) {
        runHost = host
    }

    fun clear() {
        controller = null
        directory = null
        runState = null
        runHost = null
    }
}
