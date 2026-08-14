package online.fujinet.go.intv

import android.content.Context
import android.util.Log
import android.view.Surface
import online.fujinet.go.intv.core.EmulatorNative
import online.fujinet.go.intv.settings.MachineSettingsStore
import online.fujinet.go.intv.settings.RomStore
import kotlin.concurrent.thread

/**
 * Owns the lifetime of one Intellivision session: stages the FujiNet runtime
 * assets, starts the native emulator (jzIntv, patched with the FujiNet
 * fujibus/BoIP mailbox) and the in-process FujiNet runtime (joined over
 * FujiBusPacket-over-BoIP on loopback TCP 65503, where the FujiNet runtime
 * listens and jzIntv's --fujinet device connects out), streams audio, and
 * forwards input.
 *
 * A process-wide singleton so the activity and the foreground service share
 * one running session, mirroring the other FujiNet Go targets. jzIntv owns a
 * single process-wide machine instance (core/jzintv/intv_host.h), so every
 * option change is stop-then-start, never a live reconfiguration.
 */
class SessionController private constructor(private val context: Context) {

    @Volatile private var started = false
    @Volatile private var paths: RuntimeInstaller.Paths? = null
    private val audio = AudioOutput()
    private val lock = Any()

    val settings = MachineSettingsStore(context)

    var keyboardHapticsEnabled: Boolean
        get() = settings.keyboardHapticsEnabled
        set(value) { settings.keyboardHapticsEnabled = value }

    fun startIfNeeded() {
        if (!RomStore.hasSystemRoms(context)) {
            // The ROM gate (ui/RomGate.kt) is responsible for prompting
            // import and re-calling startIfNeeded(); starting without
            // exec.bin/grom.bin would just fail in intvsession_start.
            return
        }
        synchronized(lock) {
            if (started) return
            started = true
        }
        thread(name = "intv-bootstrap") { launch() }
    }

    private fun launch() {
        try {
            val installer = RuntimeInstaller(context.applicationContext)
            val p = paths ?: installer.install().also { paths = it }
            val ok = EmulatorNative.nativeStartSession(
                p.filesDir,
                settings.cartPath,
                settings.ecs.value,
                settings.ivoice.value,
                settings.video.value,
            )
            if (!ok) {
                Log.e(TAG, "Failed to start session: ${EmulatorNative.nativeLastError()}")
                synchronized(lock) { started = false }
                return
            }
            audio.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start session", t)
            synchronized(lock) { started = false }
        }
    }

    fun stop() {
        synchronized(lock) { if (!started) return }
        audio.stop()
        EmulatorNative.nativeStopSession()
        synchronized(lock) { started = false }
    }

    /**
     * Restart so a changed machine option (ECS/Intellivoice/video standard)
     * or cartridge takes effect. Runs off the caller's thread: stop() blocks
     * until the native session has fully torn down (the jzIntv thread joins
     * -- see core/jzintv/intv_host.c's intv_host_stop), which must not
     * happen on the UI thread.
     */
    fun restart() {
        thread(name = "intv-restart") {
            stop()
            startIfNeeded()
        }
    }

    fun attachSurface(surface: Surface) = EmulatorNative.nativeAttachSurface(surface)
    fun detachSurface() = EmulatorNative.nativeDetachSurface()
    fun reset() = EmulatorNative.nativeRequestReset()

    val fujiNetWebUiUrl: String get() = EmulatorNative.nativeFujiNetWebUiUrl()
    val fujiNetRunning: Boolean get() = EmulatorNative.nativeFujiNetRunning()

    // --- controller (disc + keypad + action buttons; see ui/ControllerPad.kt) --
    fun padKey(side: Int, key: Int, pressed: Boolean) = EmulatorNative.nativePadKey(side, key, pressed)
    fun padDisc(side: Int, direction: Int) = EmulatorNative.nativePadDisc(side, direction)

    // --- ECS keyboard (see ui/EcsKeyboard.kt) -----------------------------
    fun ecsKey(key: Int, pressed: Boolean) = EmulatorNative.nativeEcsKey(key, pressed)

    /**
     * Releases every ECS keyboard matrix bit. Must be called on overlay
     * hide, overlay switch, onPause, and session stop -- a key held at the
     * moment focus moves elsewhere would otherwise stay stuck down in the
     * emulated 7x8 matrix (core/jzintv/intv_host.h's own warning).
     */
    fun ecsKeysClear() = EmulatorNative.nativeEcsKeysClear()

    companion object {
        @Volatile private var instance: SessionController? = null
        private const val TAG = "FujiIntv"

        fun get(context: Context): SessionController =
            instance ?: synchronized(this) {
                instance ?: SessionController(context.applicationContext).also { instance = it }
            }
    }
}
