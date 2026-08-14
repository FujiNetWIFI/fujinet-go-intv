package online.fujinet.go.intv.input

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure disc-direction geometry, shared by the touch controller
 * (ui/ControllerPad.kt) and [GameControllerMapper]. Transcribed from
 * fujinet-go-intv-desktop's frontends/gnome/keypad/keypad_window.c
 * (direction_from_point): snaps to the 8 compass positions only -- the 8 odd
 * half-steps intv_host.h's disc encoding also supports are deliberately
 * never emitted here, matching the desktop keypad window's own reasoning (a
 * drag sweeping through fine angles would otherwise spray spurious
 * half-step positions).
 */
object DiscGeometry {
    /** Fraction of the radius treated as dead center (desktop's DISC_DEADZONE_FRAC). */
    const val DEADZONE_FRAC = 0.22f

    /**
     * [dx]/[dy] are offsets from center in the same units as [radius] (e.g.
     * pixels); +dy is DOWN (screen convention). Returns one of the 8 even
     * clock positions (0=E, 2=NE, 4=N, 6=NW, 8=W, 10=SW, 12=S, 14=SE), or
     * [Intv.DISC_NONE] inside the deadzone.
     */
    fun discDirection(dx: Float, dy: Float, radius: Float): Int {
        if (radius <= 0f || hypot(dx, dy) < radius * DEADZONE_FRAC) return Intv.DISC_NONE
        var degrees = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (degrees < 0) degrees += 360.0
        return (((degrees / 45.0) + 0.5).toInt() % 8) * 2
    }
}
