package online.fujinet.go.intv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import online.fujinet.go.intv.fujinet.FujiNetWebViewActivity
import online.fujinet.go.intv.input.GameControllerMapper
import online.fujinet.go.intv.input.IntvKeyMapper
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.ui.EmulatorScreen
import online.fujinet.go.intv.ui.theme.FujiNetGoIntvTheme

/**
 * FujiNet Go Intv main screen: the Intellivision display plus the on-screen
 * controller (disc + keypad + action buttons) and a control bar. The native
 * layer (jzIntv + the in-process FujiNet runtime over FujiBusPacket-over-
 * BoIP) is owned by [EmulatorSessionService] (a foreground service) so it
 * keeps running across activity changes (e.g. the FujiNet web admin) and
 * while backgrounded. The session itself is a process singleton; the Power
 * button stops both.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: SessionController

    // Routes a Bluetooth/USB game controller to the left hand controller's
    // disc + action buttons.
    private val gamepad by lazy {
        GameControllerMapper(
            side = Intv.PAD_LEFT,
            onDisc = { direction -> session.padDisc(Intv.PAD_LEFT, direction) },
            onAction = { key, pressed -> session.padKey(Intv.PAD_LEFT, key, pressed) },
        )
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == EmulatorSessionService.ACTION_SHUTDOWN) {
            shutdown()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hold clocks steady over a long emulation session (thermals
        // permitting) rather than letting DVFS oscillate the 60/50Hz loop
        // off schedule -- see session_runtime.cpp's ADPF PerfHint for the
        // complementary per-thread hint.
        window.setSustainedPerformanceMode(true)
        session = SessionController.get(applicationContext)

        maybeRequestNotificationPermission()
        EmulatorSessionService.start(this)

        setContent {
            FujiNetGoIntvTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EmulatorScreen(
                        session = session,
                        onOpenFujiNet = ::openFujiNet,
                        onOpenSettings = ::openSettings,
                        onShutdown = ::shutdown,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == EmulatorSessionService.ACTION_SHUTDOWN) {
            shutdown()
        }
    }

    override fun onPause() {
        super.onPause()
        // A key held at the moment focus moves elsewhere (e.g. the user
        // switches to another app) would otherwise stay stuck down in the
        // emulated ECS matrix -- see SessionController.ecsKeysClear's own
        // comment.
        if (::session.isInitialized) session.ecsKeysClear()
    }

    private fun openFujiNet() {
        startActivity(Intent(this, FujiNetWebViewActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Stop the emulator + FujiNet and close the app. */
    private fun shutdown() {
        EmulatorSessionService.shutdown(this)
        finishAndRemoveTask()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::session.isInitialized && gamepad.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::session.isInitialized) {
            // Game controller first, then a hardware keyboard. A TV remote's
            // D-pad is claimed by neither, so it falls through to Compose
            // focus navigation.
            if (gamepad.onKey(event)) return true
            if (routeHardwareKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Route a physical keyboard key to the emulated controller via [IntvKeyMapper]. */
    private fun routeHardwareKey(event: KeyEvent): Boolean {
        if (!event.isFromPhysicalKeyboard()) return false
        // A D-pad cluster event drives Compose focus navigation only when it
        // comes from a real D-pad device (a TV remote / gamepad, marked
        // SOURCE_DPAD); see isDpadNavigation().
        if (isDpadNavigation(event)) return false
        if (event.repeatCount > 0) return false

        val mapping = IntvKeyMapper.map(event.keyCode)
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        return when (mapping) {
            is IntvKeyMapper.Mapping.PadKey -> {
                session.padKey(mapping.side, mapping.key, pressed)
                true
            }
            is IntvKeyMapper.Mapping.Disc -> {
                session.padDisc(mapping.side, if (pressed) mapping.direction else Intv.DISC_NONE)
                true
            }
            IntvKeyMapper.Mapping.None -> false
        }
    }

    private fun KeyEvent.isFromPhysicalKeyboard(): Boolean {
        val d = device ?: return false
        return !d.isVirtual &&
            d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
    }

    private fun isDpadNavigation(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        else -> false
    }

    // No session.stop() here: the foreground service owns the session's
    // lifetime so it survives this activity being finished. Stopping is
    // explicit, via the Power button -> shutdown().
}
