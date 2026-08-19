package online.fujinet.go.intv.ui

import online.fujinet.go.intv.input.DiscGeometry
import online.fujinet.go.intv.input.Intv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry tests for [DiscGeometry] -- no Android/Robolectric needed. */
class DiscGeometryTest {

    private val radius = 100f

    /** A point on the rim at [deg] compass degrees, in screen (+y down) terms. */
    private fun at(deg: Double): Pair<Float, Float> {
        val rad = Math.toRadians(deg)
        return Pair((radius * Math.cos(rad)).toFloat(), -(radius * Math.sin(rad)).toFloat())
    }

    private fun dir16(deg: Double): Int {
        val (dx, dy) = at(deg)
        return DiscGeometry.discDirection16(dx, dy, radius)
    }

    private fun dir8(deg: Double): Int {
        val (dx, dy) = at(deg)
        return DiscGeometry.discDirection8(dx, dy, radius)
    }

    @Test
    fun deadzoneCentersToNone() {
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection16(0f, 0f, radius))
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection8(0f, 0f, radius))
        val edge = radius * DiscGeometry.DEADZONE_FRAC * 0.5f
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection16(edge, edge, radius))
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection8(edge, edge, radius))
    }

    @Test
    fun degenerateRadiusIsNone() {
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection16(5f, 5f, 0f))
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection8(5f, 5f, 0f))
    }

    // ---- the touch disc: all 16 positions --------------------------------

    @Test
    fun compassPositionsResolve() {
        // Screen +y is down, so "up" (North) is -dy.
        assertEquals(0, DiscGeometry.discDirection16(radius, 0f, radius))
        assertEquals(4, DiscGeometry.discDirection16(0f, -radius, radius))
        assertEquals(8, DiscGeometry.discDirection16(-radius, 0f, radius))
        assertEquals(12, DiscGeometry.discDirection16(0f, radius, radius))
        assertEquals(2, dir16(45.0))
        assertEquals(6, dir16(135.0))
        assertEquals(10, dir16(225.0))
        assertEquals(14, dir16(315.0))
    }

    /** The 8 odd half-steps, which [DiscGeometry.discDirection8] can never reach. */
    @Test
    fun halfStepPositionsResolve() {
        assertEquals(1, dir16(22.5))
        assertEquals(3, dir16(67.5))
        assertEquals(5, dir16(112.5))
        assertEquals(7, dir16(157.5))
        assertEquals(9, dir16(202.5))
        assertEquals(11, dir16(247.5))
        assertEquals(13, dir16(292.5))
        assertEquals(15, dir16(337.5))
    }

    @Test
    fun sectorBoundariesAreHalfwayBetweenPositions() {
        assertEquals(0, dir16(11.0))
        assertEquals(1, dir16(11.5))
        assertEquals(0, dir16(355.0))
        assertEquals(15, dir16(348.0))
    }

    @Test
    fun everyAngleLandsInTheNearestSector() {
        for (tenths in 0 until 3600) {
            val deg = tenths / 10.0
            val dir = dir16(deg)
            assertTrue("angle=$deg gave $dir", dir in 0 until DiscGeometry.POSITIONS)
            var delta = Math.abs(deg - dir * DiscGeometry.SECTOR_DEG)
            if (delta > 180.0) delta = 360.0 - delta // the wrap across East
            assertTrue(
                "angle=$deg resolved to $dir, $delta degrees away",
                delta <= DiscGeometry.SECTOR_DEG / 2 + 1e-9,
            )
        }
    }

    @Test
    fun allSixteenPositionsAreReachable() {
        val seen = BooleanArray(DiscGeometry.POSITIONS)
        for (tenths in 0 until 3600) {
            val dir = dir16(tenths / 10.0)
            if (dir != Intv.DISC_NONE) seen[dir] = true
        }
        for (i in seen.indices) assertTrue("position $i unreachable", seen[i])
    }

    // ---- the stick/d-pad path: 8 positions only ---------------------------

    @Test
    fun stickResolvesTheEightCompassDirections() {
        val expected = mapOf(
            0.0 to 0, 45.0 to 2, 90.0 to 4, 135.0 to 6,
            180.0 to 8, 225.0 to 10, 270.0 to 12, 315.0 to 14,
        )
        for ((deg, dir) in expected) {
            assertEquals("angle=$deg", dir, dir8(deg))
        }
    }

    /**
     * A real stick pushed "west" wobbles a few degrees off-axis; 8-way
     * rounding gives it a full +-22.5 degree catch so that wobble cannot tip
     * it into a half-step (which would OR in a south or north bit).
     */
    @Test
    fun stickNeverReturnsAnOddHalfStep() {
        for (deg in 0 until 360 step 5) {
            val dir = dir8(deg.toDouble())
            if (dir != Intv.DISC_NONE) {
                assertEquals("direction=$dir must be even (angle=$deg)", 0, dir % 2)
            }
        }
        assertEquals(8, dir8(195.0))
        assertEquals(8, dir8(165.0))
    }
}
