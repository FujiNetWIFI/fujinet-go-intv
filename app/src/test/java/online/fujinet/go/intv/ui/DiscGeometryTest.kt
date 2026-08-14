package online.fujinet.go.intv.ui

import online.fujinet.go.intv.input.DiscGeometry
import online.fujinet.go.intv.input.Intv
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure geometry tests for [DiscGeometry.discDirection] -- no Android/Robolectric needed. */
class DiscGeometryTest {

    private val radius = 100f

    @Test
    fun deadzoneCentersToNone() {
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection(0f, 0f, radius))
        val edge = radius * DiscGeometry.DEADZONE_FRAC * 0.5f
        assertEquals(Intv.DISC_NONE, DiscGeometry.discDirection(edge, edge, radius))
    }

    @Test
    fun eastIsZero() {
        assertEquals(0, DiscGeometry.discDirection(radius, 0f, radius))
    }

    @Test
    fun northIsFour() {
        // Screen +y is down, so "up" (North) is -dy.
        assertEquals(4, DiscGeometry.discDirection(0f, -radius, radius))
    }

    @Test
    fun westIsEight() {
        assertEquals(8, DiscGeometry.discDirection(-radius, 0f, radius))
    }

    @Test
    fun southIsTwelve() {
        assertEquals(12, DiscGeometry.discDirection(0f, radius, radius))
    }

    @Test
    fun allEightCompassDirectionsResolve() {
        val expected = mapOf(
            0.0 to 0, 45.0 to 2, 90.0 to 4, 135.0 to 6,
            180.0 to 8, 225.0 to 10, 270.0 to 12, 315.0 to 14,
        )
        for ((deg, dir) in expected) {
            val rad = Math.toRadians(deg)
            val dx = (radius * Math.cos(rad)).toFloat()
            val dy = -(radius * Math.sin(rad)).toFloat()
            assertEquals("angle=$deg", dir, DiscGeometry.discDirection(dx, dy, radius))
        }
    }

    @Test
    fun neverReturnsAnOddHalfStep() {
        for (deg in 0 until 360 step 5) {
            val rad = Math.toRadians(deg.toDouble())
            val dx = (radius * Math.cos(rad)).toFloat()
            val dy = -(radius * Math.sin(rad)).toFloat()
            val dir = DiscGeometry.discDirection(dx, dy, radius)
            if (dir != Intv.DISC_NONE) {
                assertEquals("direction=$dir must be even (angle=$deg)", 0, dir % 2)
            }
        }
    }
}
