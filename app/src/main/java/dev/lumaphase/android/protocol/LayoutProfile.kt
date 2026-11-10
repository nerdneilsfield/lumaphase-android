package dev.lumaphase.android.protocol

/**
 * OPTC-1 optical layout profile. The module counts are fixed by the shared contract:
 * F68 keeps modules 0-67, C52 keeps modules 0-51.
 */
enum class LayoutProfile(val moduleCount: Int) {
    F68(68),
    C52(52),
}
