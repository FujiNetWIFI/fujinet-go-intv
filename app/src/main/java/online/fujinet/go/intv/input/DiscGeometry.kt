package online.fujinet.go.intv.input

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure disc-direction geometry, shared by the touch controller
 * (ui/ControllerPad.kt) and [GameControllerMapper]. Transcribed from
 * fujinet-go-intv-desktop's core/src/disc_geom.c
 * (`intvsession_disc_from_point`) and core/src/gamepad_sdl.c
 * (`intv_disc_from_stick`) -- the two resolutions the desktop build keeps
 * for the two kinds of input, and for the same reasons:
 *
 * - [discDirection16] for a fingertip or mouse pointer, which is exactly
 *   where its owner put it, so all 16 of the positions intv_host.h's disc
 *   encoding supports are worth offering.
 * - [discDirection8] for an analog stick or d-pad, which sits wherever a
 *   spring and a thumb leave it: a half-step sector only +-11.25 degrees
 *   wide catches wobble the player did not intend, and a half-step
 *   OR-combines two adjacent cardinal bits (SSW = 64|32), so a straight
 *   "push left" could register a stray south-ish reading.
 */
object DiscGeometry {
    /** Fraction of the radius treated as dead center (desktop's INTVSESSION_DISC_DEADZONE_FRAC). */
    const val DEADZONE_FRAC = 0.22f

    /** Degrees each of the 16 positions owns (desktop's INTVSESSION_DISC_SECTOR_DEG). */
    const val SECTOR_DEG = 22.5

    /** How many positions the disc has in total. */
    const val POSITIONS = 16

    /**
     * All 16 clock positions: 0=E, 1=ENE, 2=NE, ... 15=ESE, numerically
     * counter-clockwise. [dx]/[dy] are offsets from center in the same
     * units as [radius] (e.g. pixels); +dy is DOWN (screen convention).
     * Returns [Intv.DISC_NONE] inside the deadzone.
     */
    fun discDirection16(dx: Float, dy: Float, radius: Float): Int {
        val degrees = angleOrNull(dx, dy, radius) ?: return Intv.DISC_NONE
        return ((degrees / SECTOR_DEG) + 0.5).toInt() % POSITIONS
    }

    /**
     * The 8 even compass positions only (0=E, 2=NE, 4=N, 6=NW, 8=W, 10=SW,
     * 12=S, 14=SE), never one of the odd half-steps between them. Same
     * arguments and same [Intv.DISC_NONE] result as [discDirection16].
     */
    fun discDirection8(dx: Float, dy: Float, radius: Float): Int {
        val degrees = angleOrNull(dx, dy, radius) ?: return Intv.DISC_NONE
        return (((degrees / 45.0) + 0.5).toInt() % 8) * 2
    }

    /**
     * Compass degrees in [0,360) for a point outside the deadzone, else
     * null. +dy is DOWN, so it is negated to get the ordinary math
     * convention the position numbering is defined in (North = +90).
     */
    private fun angleOrNull(dx: Float, dy: Float, radius: Float): Double? {
        if (radius <= 0f || hypot(dx, dy) < radius * DEADZONE_FRAC) return null
        val degrees = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        return if (degrees < 0) degrees + 360.0 else degrees
    }
}
