package online.fujinet.go.intv.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Maps a Bluetooth/USB game controller to one Intellivision hand controller
 * (the disc + three action buttons -- there is no gamepad-to-keypad-digit
 * mapping, matching the desktop's own gamepad_sdl.c). The left stick and the
 * d-pad (as AXIS_HAT motion or KEYCODE_DPAD_* keys) both drive the 8-way
 * disc via [DiscGeometry.discDirection]; A/X, B/Y and the shoulder buttons
 * map to the three action buttons.
 */
class GameControllerMapper(
    private val side: Int = Intv.PAD_LEFT,
    private val deadzone: Float = DEFAULT_DEADZONE,
    private val onDisc: (direction: Int) -> Unit,
    private val onAction: (key: Int, pressed: Boolean) -> Unit,
) {
    private var stickX = 0f
    private var stickY = 0f
    private var hatX = 0f
    private var hatY = 0f
    private var lastDirection = Intv.DISC_NONE

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromController() || event.action != MotionEvent.ACTION_MOVE) return false
        stickX = event.getAxisValue(MotionEvent.AXIS_X)
        stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        pushDirection()
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!event.isFromController()) return false
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X -> {
                onAction(Intv.ACTION_TOP, pressed); return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                onAction(Intv.ACTION_LOWER_LEFT, pressed); return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                onAction(Intv.ACTION_LOWER_RIGHT, pressed); return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                onAction(Intv.ACTION_LOWER_LEFT, pressed); return true
            }
            else -> return false
        }
    }

    /** Recenter the disc (e.g. when a controller disconnects). */
    fun reset() {
        stickX = 0f; stickY = 0f; hatX = 0f; hatY = 0f
        lastDirection = Intv.DISC_NONE
        onDisc(Intv.DISC_NONE)
    }

    private fun pushDirection() {
        val x = if (abs(stickX) >= deadzone) stickX else hatX
        val y = if (abs(stickY) >= deadzone) stickY else hatY
        // Treat the unit stick circle as radius 1; DiscGeometry's own
        // deadzone fraction (0.22) is smaller than a typical stick deadzone,
        // but pushDirection already zeroed out sub-deadzone axis values above.
        val direction = DiscGeometry.discDirection(x, y, 1f)
        if (direction != lastDirection) {
            lastDirection = direction
            onDisc(direction)
        }
    }

    private fun MotionEvent.isFromController(): Boolean =
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD

    private fun KeyEvent.isFromController(): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private companion object {
        const val DEFAULT_DEADZONE = 0.35f
    }
}
