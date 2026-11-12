package dev.lumaphase.android.layout

import dev.lumaphase.android.protocol.LayoutProfile

/**
 * The geometry half of an OPTC-1 layout: how the fixed 68 or 52 module array is placed inside
 * the dynamic area. It is deliberately separate from [LayoutProfile], which is only the
 * payload family the encoder knows about (contract sections 3.1 and 3.3).
 *
 * The two single-row values keep the original layout available. The four grid values are the
 * CR-001 geometries; the grid rows and columns are always the unrotated logical grid, and the
 * whole grid is rotated afterwards with the rest of the pattern.
 */
enum class GeometryProfile(
    val wire: String,
    val layoutProfile: LayoutProfile,
    val gridRows: Int,
    val gridCols: Int,
) {
    F68("F68", LayoutProfile.F68, 1, 68),
    C52("C52", LayoutProfile.C52, 1, 52),
    F68_G6X12("F68-G6x12", LayoutProfile.F68, 6, 12),
    C52_G5X12("C52-G5x12", LayoutProfile.C52, 5, 12),
    F68_G9X8("F68-G9x8", LayoutProfile.F68, 9, 8),
    C52_G9X6("C52-G9x6", LayoutProfile.C52, 9, 6),
    ;

    val moduleCount: Int get() = layoutProfile.moduleCount
    val paddingCount: Int get() = gridRows * gridCols - moduleCount
    val isGrid: Boolean get() = gridRows > 1

    init {
        require(gridRows > 0 && gridCols > 0) { "grid must be positive: $wire" }
        require(gridCols % 2 == 0) { "grid columns must be even: $wire" }
        require(paddingCount >= 0) { "grid $wire cannot hold $moduleCount modules" }
    }

    companion object {
        fun legacy(profile: LayoutProfile): GeometryProfile = when (profile) {
            LayoutProfile.F68 -> F68
            LayoutProfile.C52 -> C52
        }

        /** Android portrait default: 9x8 for F68, 9x6 for C52. */
        fun portraitGrid(profile: LayoutProfile): GeometryProfile = when (profile) {
            LayoutProfile.F68 -> F68_G9X8
            LayoutProfile.C52 -> C52_G9X6
        }

        /** The landscape counterpart, used when the drawable is wider than it is tall. */
        fun landscapeGrid(profile: LayoutProfile): GeometryProfile = when (profile) {
            LayoutProfile.F68 -> F68_G6X12
            LayoutProfile.C52 -> C52_G5X12
        }

        fun forWire(value: String): GeometryProfile? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What the settings screen offers. A grid choice is resolved from the actual Surface/drawable
 * orientation, so the default vertical grids switch to their landscape counterparts without the
 * operator selecting a second name. The payload family is fixed by the choice and is never
 * inferred from a free-floating profile field.
 */
enum class LayoutChoice(
    val wire: String,
    val layoutProfile: LayoutProfile,
    private val grid: Boolean,
    /** Keep the same payload grid, but expand the four locating tags into the free margins. */
    val largeTags: Boolean = false,
) {
    F68_SINGLE("F68-single", LayoutProfile.F68, false),
    C52_SINGLE("C52-single", LayoutProfile.C52, false),
    F68_GRID("F68-grid", LayoutProfile.F68, true),
    C52_GRID("C52-grid", LayoutProfile.C52, true),
    F68_GRID_LARGE("F68-grid-large", LayoutProfile.F68, true, true),
    C52_GRID_LARGE("C52-grid-large", LayoutProfile.C52, true, true),
    ;

    /** Human-readable selection label that names both the geometry and its payload. */
    val label: String
        get() = when (this) {
            F68_SINGLE -> "F68 · 1×68"
            C52_SINGLE -> "C52 · 1×52"
            F68_GRID -> "F68 grid · 9×8 / 6×12"
            C52_GRID -> "C52 grid · 9×6 / 5×12"
            F68_GRID_LARGE -> "F68 大Tag · 9×8 / 6×12"
            C52_GRID_LARGE -> "C52 大Tag · 9×6 / 5×12"
        }

    /**
     * Resolves the actual geometry from the real Surface/drawable size, before any marker block
     * is subtracted and independently of `pattern_rotation_deg`: a target whose height is at
     * least its width gets the 9-row grids (a square counts as portrait), a wider target gets
     * the 12-column grids. The rotation only transforms the resulting geometry; it never
     * changes this choice or the module order.
     */
    fun geometryFor(drawableWidthPx: Int, drawableHeightPx: Int): GeometryProfile = when {
        !grid -> GeometryProfile.legacy(layoutProfile)
        drawableHeightPx >= drawableWidthPx -> GeometryProfile.portraitGrid(layoutProfile)
        else -> GeometryProfile.landscapeGrid(layoutProfile)
    }

    companion object {
        fun forWire(value: String?): LayoutChoice? = entries.firstOrNull { it.wire == value }

        /** The single-row choice for a legacy intent that only carries a profile name. */
        fun legacy(profile: LayoutProfile): LayoutChoice = when (profile) {
            LayoutProfile.F68 -> F68_SINGLE
            LayoutProfile.C52 -> C52_SINGLE
        }
    }
}
