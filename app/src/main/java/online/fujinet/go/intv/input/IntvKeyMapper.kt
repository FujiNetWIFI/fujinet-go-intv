package online.fujinet.go.intv.input

import android.view.KeyEvent

/**
 * Hardware-keyboard -> Intellivision controller mapping, mirroring
 * fujinet-go-intv-desktop's core/src/intv_keymap.c 1:1 (same host keys, same
 * controller assignment) but driven from Android [KeyEvent] keycodes instead
 * of jzIntv's own keysym enum. The touch controller (ui/ControllerPad.kt) is
 * the primary input; this exists for the (uncommon but supported) case of an
 * attached physical keyboard.
 */
object IntvKeyMapper {
    sealed class Mapping {
        data class PadKey(val side: Int, val key: Int) : Mapping()
        data class Disc(val side: Int, val direction: Int) : Mapping()
        data object None : Mapping()
    }

    // Clock positions, matching intv_host.h's disc-position numbering
    // (0 = E, clockwise, even positions only).
    private const val DIR_E = 0
    private const val DIR_NE = 2
    private const val DIR_N = 4
    private const val DIR_NW = 6
    private const val DIR_W = 8
    private const val DIR_SW = 10
    private const val DIR_S = 12
    private const val DIR_SE = 14

    /**
     * Takes a raw [KeyEvent.getKeyCode] rather than a [KeyEvent] itself: the
     * unit tests construct no KeyEvent objects (Android's KeyEvent
     * constructors are unavailable under the default Gradle unit-test stub
     * jar without Robolectric -- see the family's precedent,
     * MsxKeyMapperTest.kt), and KEYCODE_* are compile-time constants so a
     * plain Int is exactly as testable while staying trivial for
     * MainActivity to call with event.keyCode. Caller should ignore
     * [KeyEvent.getRepeatCount] > 0.
     */
    fun map(keyCode: Int): Mapping = when (keyCode) {
        // ---- numeric keypad -> left controller keypad ----------------------
        KeyEvent.KEYCODE_NUMPAD_7 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_1)
        KeyEvent.KEYCODE_NUMPAD_8 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_2)
        KeyEvent.KEYCODE_NUMPAD_9 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_3)
        KeyEvent.KEYCODE_NUMPAD_4 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_4)
        KeyEvent.KEYCODE_NUMPAD_5 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_5)
        KeyEvent.KEYCODE_NUMPAD_6 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_6)
        KeyEvent.KEYCODE_NUMPAD_1 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_7)
        KeyEvent.KEYCODE_NUMPAD_2 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_8)
        KeyEvent.KEYCODE_NUMPAD_3 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_9)
        KeyEvent.KEYCODE_NUMPAD_0 -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_CLEAR)
        KeyEvent.KEYCODE_NUMPAD_DOT -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_0)
        KeyEvent.KEYCODE_NUMPAD_ENTER -> Mapping.PadKey(Intv.PAD_LEFT, Intv.KEY_ENTER)

        // ---- number row -> right controller keypad --------------------------
        KeyEvent.KEYCODE_1 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_1)
        KeyEvent.KEYCODE_2 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_2)
        KeyEvent.KEYCODE_3 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_3)
        KeyEvent.KEYCODE_4 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_4)
        KeyEvent.KEYCODE_5 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_5)
        KeyEvent.KEYCODE_6 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_6)
        KeyEvent.KEYCODE_7 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_7)
        KeyEvent.KEYCODE_8 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_8)
        KeyEvent.KEYCODE_9 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_9)
        KeyEvent.KEYCODE_MINUS -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_CLEAR)
        KeyEvent.KEYCODE_0 -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_0)
        KeyEvent.KEYCODE_EQUALS -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.KEY_ENTER)

        // ---- action buttons -- crossed, matching mapping.c exactly ----------
        KeyEvent.KEYCODE_SHIFT_RIGHT -> Mapping.PadKey(Intv.PAD_LEFT, Intv.ACTION_TOP)
        KeyEvent.KEYCODE_ALT_RIGHT -> Mapping.PadKey(Intv.PAD_LEFT, Intv.ACTION_LOWER_LEFT)
        KeyEvent.KEYCODE_CTRL_RIGHT -> Mapping.PadKey(Intv.PAD_LEFT, Intv.ACTION_LOWER_RIGHT)
        KeyEvent.KEYCODE_SHIFT_LEFT -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.ACTION_TOP)
        KeyEvent.KEYCODE_ALT_LEFT -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.ACTION_LOWER_LEFT)
        KeyEvent.KEYCODE_CTRL_LEFT -> Mapping.PadKey(Intv.PAD_RIGHT, Intv.ACTION_LOWER_RIGHT)

        // ---- arrows -> left disc ---------------------------------------------
        KeyEvent.KEYCODE_DPAD_RIGHT -> Mapping.Disc(Intv.PAD_LEFT, DIR_E)
        KeyEvent.KEYCODE_DPAD_UP -> Mapping.Disc(Intv.PAD_LEFT, DIR_N)
        KeyEvent.KEYCODE_DPAD_LEFT -> Mapping.Disc(Intv.PAD_LEFT, DIR_W)
        KeyEvent.KEYCODE_DPAD_DOWN -> Mapping.Disc(Intv.PAD_LEFT, DIR_S)

        // ---- IJKM/O/U/N/, -> left disc (second binding) ---------------------
        KeyEvent.KEYCODE_K -> Mapping.Disc(Intv.PAD_LEFT, DIR_E)
        KeyEvent.KEYCODE_O -> Mapping.Disc(Intv.PAD_LEFT, DIR_NE)
        KeyEvent.KEYCODE_I -> Mapping.Disc(Intv.PAD_LEFT, DIR_N)
        KeyEvent.KEYCODE_U -> Mapping.Disc(Intv.PAD_LEFT, DIR_NW)
        KeyEvent.KEYCODE_J -> Mapping.Disc(Intv.PAD_LEFT, DIR_W)
        KeyEvent.KEYCODE_N -> Mapping.Disc(Intv.PAD_LEFT, DIR_SW)
        KeyEvent.KEYCODE_M -> Mapping.Disc(Intv.PAD_LEFT, DIR_S)
        KeyEvent.KEYCODE_COMMA -> Mapping.Disc(Intv.PAD_LEFT, DIR_SE)

        // ---- DRWEASZXC -> right disc ------------------------------------------
        KeyEvent.KEYCODE_D -> Mapping.Disc(Intv.PAD_RIGHT, DIR_E)
        KeyEvent.KEYCODE_R -> Mapping.Disc(Intv.PAD_RIGHT, DIR_NE)
        KeyEvent.KEYCODE_E -> Mapping.Disc(Intv.PAD_RIGHT, DIR_N)
        KeyEvent.KEYCODE_W -> Mapping.Disc(Intv.PAD_RIGHT, DIR_NW)
        KeyEvent.KEYCODE_S -> Mapping.Disc(Intv.PAD_RIGHT, DIR_W)
        KeyEvent.KEYCODE_Z -> Mapping.Disc(Intv.PAD_RIGHT, DIR_SW)
        KeyEvent.KEYCODE_X -> Mapping.Disc(Intv.PAD_RIGHT, DIR_S)
        KeyEvent.KEYCODE_C -> Mapping.Disc(Intv.PAD_RIGHT, DIR_SE)

        else -> Mapping.None
    }

    /** Mirrors intvsession_ecs_key_from_keysym; -1 if unmapped. */
    fun mapEcs(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> Intv.ECS_KEY_ESC
        KeyEvent.KEYCODE_NUMPAD_7 -> Intv.ECS_KEY_1
        KeyEvent.KEYCODE_NUMPAD_8 -> Intv.ECS_KEY_2
        KeyEvent.KEYCODE_NUMPAD_9 -> Intv.ECS_KEY_3
        KeyEvent.KEYCODE_NUMPAD_4 -> Intv.ECS_KEY_4
        KeyEvent.KEYCODE_NUMPAD_5 -> Intv.ECS_KEY_5
        KeyEvent.KEYCODE_NUMPAD_6 -> Intv.ECS_KEY_6
        KeyEvent.KEYCODE_NUMPAD_1 -> Intv.ECS_KEY_7
        KeyEvent.KEYCODE_NUMPAD_2 -> Intv.ECS_KEY_8
        KeyEvent.KEYCODE_NUMPAD_3 -> Intv.ECS_KEY_9
        KeyEvent.KEYCODE_NUMPAD_0 -> Intv.ECS_KEY_0
        KeyEvent.KEYCODE_NUMPAD_DOT -> Intv.ECS_KEY_PERIOD
        KeyEvent.KEYCODE_NUMPAD_ENTER -> Intv.ECS_KEY_ENTER
        KeyEvent.KEYCODE_1 -> Intv.ECS_KEY_1
        KeyEvent.KEYCODE_2 -> Intv.ECS_KEY_2
        KeyEvent.KEYCODE_3 -> Intv.ECS_KEY_3
        KeyEvent.KEYCODE_4 -> Intv.ECS_KEY_4
        KeyEvent.KEYCODE_5 -> Intv.ECS_KEY_5
        KeyEvent.KEYCODE_6 -> Intv.ECS_KEY_6
        KeyEvent.KEYCODE_7 -> Intv.ECS_KEY_7
        KeyEvent.KEYCODE_8 -> Intv.ECS_KEY_8
        KeyEvent.KEYCODE_9 -> Intv.ECS_KEY_9
        KeyEvent.KEYCODE_0 -> Intv.ECS_KEY_0
        KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT -> Intv.ECS_KEY_SHIFT
        KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT -> Intv.ECS_KEY_CTRL
        KeyEvent.KEYCODE_DPAD_RIGHT -> Intv.ECS_KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Intv.ECS_KEY_UP
        KeyEvent.KEYCODE_DPAD_LEFT -> Intv.ECS_KEY_LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> Intv.ECS_KEY_DOWN
        KeyEvent.KEYCODE_K -> Intv.ECS_KEY_K
        KeyEvent.KEYCODE_O -> Intv.ECS_KEY_O
        KeyEvent.KEYCODE_I -> Intv.ECS_KEY_I
        KeyEvent.KEYCODE_U -> Intv.ECS_KEY_U
        KeyEvent.KEYCODE_J -> Intv.ECS_KEY_J
        KeyEvent.KEYCODE_N -> Intv.ECS_KEY_N
        KeyEvent.KEYCODE_M -> Intv.ECS_KEY_M
        KeyEvent.KEYCODE_COMMA -> Intv.ECS_KEY_COMMA
        KeyEvent.KEYCODE_D -> Intv.ECS_KEY_D
        KeyEvent.KEYCODE_R -> Intv.ECS_KEY_R
        KeyEvent.KEYCODE_E -> Intv.ECS_KEY_E
        KeyEvent.KEYCODE_W -> Intv.ECS_KEY_W
        KeyEvent.KEYCODE_S -> Intv.ECS_KEY_S
        KeyEvent.KEYCODE_Z -> Intv.ECS_KEY_Z
        KeyEvent.KEYCODE_X -> Intv.ECS_KEY_X
        KeyEvent.KEYCODE_C -> Intv.ECS_KEY_C
        KeyEvent.KEYCODE_Q -> Intv.ECS_KEY_Q
        KeyEvent.KEYCODE_T -> Intv.ECS_KEY_T
        KeyEvent.KEYCODE_Y -> Intv.ECS_KEY_Y
        KeyEvent.KEYCODE_P -> Intv.ECS_KEY_P
        KeyEvent.KEYCODE_A -> Intv.ECS_KEY_A
        KeyEvent.KEYCODE_F -> Intv.ECS_KEY_F
        KeyEvent.KEYCODE_G -> Intv.ECS_KEY_G
        KeyEvent.KEYCODE_H -> Intv.ECS_KEY_H
        KeyEvent.KEYCODE_L -> Intv.ECS_KEY_L
        KeyEvent.KEYCODE_V -> Intv.ECS_KEY_V
        KeyEvent.KEYCODE_B -> Intv.ECS_KEY_B
        KeyEvent.KEYCODE_PERIOD -> Intv.ECS_KEY_PERIOD
        KeyEvent.KEYCODE_SEMICOLON -> Intv.ECS_KEY_SEMI
        KeyEvent.KEYCODE_SPACE -> Intv.ECS_KEY_SPACE
        KeyEvent.KEYCODE_ENTER -> Intv.ECS_KEY_ENTER
        KeyEvent.KEYCODE_DEL -> Intv.ECS_KEY_LEFT
        else -> -1
    }
}
