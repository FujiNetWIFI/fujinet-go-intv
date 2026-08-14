package online.fujinet.go.intv.settings

import android.content.Context
import online.fujinet.go.intv.input.Intv

/** Mirrors INTVSESSION_HW_* (jzIntv's own tri-state ECS/Intellivoice toggle). */
enum class HwMode(val value: Int, val label: String) {
    AUTO(Intv.HW_AUTO, "Auto"),
    OFF(Intv.HW_OFF, "Off"),
    ON(Intv.HW_ON, "On");

    companion object {
        fun from(value: Int): HwMode = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/** Mirrors INTVSESSION_VIDEO_*. */
enum class VideoStandard(val value: Int, val label: String) {
    NTSC(Intv.VIDEO_NTSC, "NTSC (60 Hz)"),
    PAL(Intv.VIDEO_PAL, "PAL (50 Hz)");

    companion object {
        fun from(value: Int): VideoStandard = entries.firstOrNull { it.value == value } ?: NTSC
    }
}

/**
 * The single source of truth for restart-requiring machine options (ECS,
 * Intellivoice, video standard, selected cartridge) plus live haptics
 * toggles. Plain SharedPreferences, matching the rest of the FujiNet Go
 * family -- no DataStore, and deliberately not backed by the native
 * settings.ini the shared core also has: every option here is
 * restart-required, so there is no live-read path an INI store would help
 * with, and a session always receives a fully-populated
 * EmulatorNative.nativeStartSession() call built from this store rather than
 * reading through the native side's own settings key/value store.
 */
class MachineSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var ecs: HwMode
        get() = HwMode.from(prefs.getInt(KEY_ECS, Intv.HW_AUTO))
        set(value) = prefs.edit().putInt(KEY_ECS, value.value).apply()

    var ivoice: HwMode
        get() = HwMode.from(prefs.getInt(KEY_IVOICE, Intv.HW_AUTO))
        set(value) = prefs.edit().putInt(KEY_IVOICE, value.value).apply()

    var video: VideoStandard
        get() = VideoStandard.from(prefs.getInt(KEY_VIDEO, Intv.VIDEO_NTSC))
        set(value) = prefs.edit().putInt(KEY_VIDEO, value.value).apply()

    /** Absolute path to the currently selected cartridge, or "" for none
     * (boots the embedded FujiNet config ROM). */
    var cartPath: String
        get() = prefs.getString(KEY_CART_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CART_PATH, value).apply()

    /** Display name shown in Settings; kept alongside the path since SAF
     * display names don't round-trip from a filesystem path. */
    var cartName: String
        get() = prefs.getString(KEY_CART_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CART_NAME, value).apply()

    fun ejectCart() {
        prefs.edit().putString(KEY_CART_PATH, "").putString(KEY_CART_NAME, "").apply()
    }

    // Haptics apply live (no session restart) -- see SettingsScreen.kt.
    var keyboardHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    private companion object {
        const val PREFS_NAME = "fujiintv"
        const val KEY_ECS = "ecs"
        const val KEY_IVOICE = "ivoice"
        const val KEY_VIDEO = "video_standard"
        const val KEY_CART_PATH = "cart_path"
        const val KEY_CART_NAME = "cart_name"
        const val KEY_HAPTICS = "haptics"
    }
}
