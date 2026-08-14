package online.fujinet.go.intv.core

import android.view.Surface

/**
 * JNI bridge to libintvcore.so (jzIntv 20200712 + the FujiNet patch, staged
 * from fujinet-go-intv-desktop, wrapped by intv_host_android.cpp/
 * session_runtime.cpp).
 *
 * jzIntv paces itself (core/jzintv/intv_host.c's -r1 real-time throttle); the
 * native render thread is a pure presenter woken once per completed STIC
 * frame (see session_runtime.cpp's header comment) -- there is no host-side
 * frame loop to drive from Kotlin.
 */
object EmulatorNative {
    init {
        // libfujinet.so is dlopen'd by the native layer on demand; we only
        // load our own core, which statically links jzIntv.
        System.loadLibrary("intvcore")
    }

    /**
     * [filesDir] is passed as both the native session's config_dir and
     * data_dir (see paths_android.c's paths_init): the native side derives
     * roms/, fujinet/, and settings.ini from it, so RuntimeInstaller must
     * stage FujiNet's assets into exactly `<filesDir>/fujinet` to match.
     *
     * ecs/ivoice: INTVSESSION_HW_* (0=Auto 1=Off 2=On), always passed
     * explicitly from MachineSettingsStore (the native settings.ini store is
     * inert on Android -- see that class's own comment). video:
     * INTVSESSION_VIDEO_* (0=NTSC 1=PAL). cartPath "" boots the embedded
     * FujiNet config ROM.
     */
    external fun nativeStartSession(
        filesDir: String,
        cartPath: String,
        ecs: Int,
        ivoice: Int,
        video: Int,
    ): Boolean

    external fun nativeStopSession()
    external fun nativeIsRunning(): Boolean
    external fun nativeLastError(): String

    external fun nativeAttachSurface(surface: Surface)
    external fun nativeDetachSurface()
    external fun nativeRequestReset()

    /** Exact-size probes; no session/ROM directory creation required. */
    external fun nativeHasSystemRoms(romsDir: String): Boolean
    external fun nativeHasEcsRom(romsDir: String): Boolean

    /** side/key: see input/Intv.kt (mirrors intvsession_pad_side/_key). */
    external fun nativePadKey(side: Int, key: Int, pressed: Boolean)

    /** direction: 0..15 (even positions only are ever sent), or -1 to center. */
    external fun nativePadDisc(side: Int, direction: Int)

    /** key: see input/Intv.kt (mirrors intvsession_ecs_key, 0..47). */
    external fun nativeEcsKey(key: Int, pressed: Boolean)
    external fun nativeEcsKeysClear()

    /**
     * Blocks until [out] can be filled with a full block of interleaved
     * stereo signed-16 samples (48000 Hz -- jzIntv's mono mix duplicated to
     * both channels natively), silence-padding on underrun. Returns the
     * count written (always [out].size).
     */
    external fun nativeFillAudio(out: ShortArray): Int

    /** Toggle audio drain; pass false on shutdown to unblock a waiting fill. */
    external fun nativeAudioSetActive(active: Boolean)

    external fun nativeFujiNetRunning(): Boolean
    external fun nativeFujiNetWebUiUrl(): String
    external fun nativeFujiNetCopyLog(): String
}
