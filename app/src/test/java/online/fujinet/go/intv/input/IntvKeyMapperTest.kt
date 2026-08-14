package online.fujinet.go.intv.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntvKeyMapperTest {

    @Test
    fun numberRowMapsToRightKeypad() {
        val mapping = IntvKeyMapper.map(KeyEvent.KEYCODE_5) as IntvKeyMapper.Mapping.PadKey
        assertEquals(Intv.PAD_RIGHT, mapping.side)
        assertEquals(Intv.KEY_5, mapping.key)
    }

    @Test
    fun numpadMapsToLeftKeypad() {
        val mapping = IntvKeyMapper.map(KeyEvent.KEYCODE_NUMPAD_7) as IntvKeyMapper.Mapping.PadKey
        assertEquals(Intv.PAD_LEFT, mapping.side)
        assertEquals(Intv.KEY_1, mapping.key)
    }

    @Test
    fun arrowsMapToLeftDiscCompassPositions() {
        val up = IntvKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP) as IntvKeyMapper.Mapping.Disc
        assertEquals(Intv.PAD_LEFT, up.side)
        assertEquals(4, up.direction) // North

        val left = IntvKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT) as IntvKeyMapper.Mapping.Disc
        assertEquals(8, left.direction) // West
    }

    @Test
    fun actionButtonsAreCrossedBetweenControllers() {
        val rightShift = IntvKeyMapper.map(KeyEvent.KEYCODE_SHIFT_RIGHT) as IntvKeyMapper.Mapping.PadKey
        assertEquals(Intv.PAD_LEFT, rightShift.side)
        assertEquals(Intv.ACTION_TOP, rightShift.key)

        val leftShift = IntvKeyMapper.map(KeyEvent.KEYCODE_SHIFT_LEFT) as IntvKeyMapper.Mapping.PadKey
        assertEquals(Intv.PAD_RIGHT, leftShift.side)
        assertEquals(Intv.ACTION_TOP, leftShift.key)
    }

    @Test
    fun unmappedKeyIsNone() {
        val mapping = IntvKeyMapper.map(KeyEvent.KEYCODE_F1)
        assertTrue(mapping is IntvKeyMapper.Mapping.None)
    }

    @Test
    fun ecsShiftCollapsesBothSides() {
        assertEquals(Intv.ECS_KEY_SHIFT, IntvKeyMapper.mapEcs(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(Intv.ECS_KEY_SHIFT, IntvKeyMapper.mapEcs(KeyEvent.KEYCODE_SHIFT_RIGHT))
    }
}
