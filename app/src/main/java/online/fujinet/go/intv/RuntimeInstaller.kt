package online.fujinet.go.intv

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Stages the bundled runtime trees from APK assets into the writable
 * directory tree the native layer reads/mutates:
 *
 *   assets/fujinet/{fnconfig.ini, data/, SD/} -> <filesDir>/intv/fujinet
 *   assets/intv-roms/{exec,grom,ecs}.bin       -> <filesDir>/intv/roms
 *     (dev builds only, -PintvRoms=true -- see app/build.gradle.kts and
 *     COMPLIANCE.md; the asset dir is simply absent in a release build)
 *
 * <filesDir>/intv is passed to EmulatorNative.nativeStartSession as both
 * config_dir and data_dir; paths_android.c's paths_init derives roms/,
 * fujinet/ and settings.ini from it, so this directory layout is not
 * arbitrary -- it must match that derivation exactly.
 */
class RuntimeInstaller(private val context: Context) {

    data class Paths(
        val filesDir: String,
        val romsDir: String,
        val cartsDir: String,
    )

    fun install(): Paths {
        val root = File(context.filesDir, "intv")
        val fujinetRoot = File(root, "fujinet")
        if (!File(fujinetRoot, "fnconfig.ini").exists()) {
            copyAssetDir("fujinet", fujinetRoot)
        }

        val romsDir = File(root, "roms").apply { mkdirs() }
        // Dev-only, -PintvRoms=true builds: stage exec.bin/grom.bin/ecs.bin
        // from assets/intv-roms if present -- but only ever fill in what's
        // missing, so a user-imported ROM always wins over the compiled-in
        // one (same rule the native intv_host_provision_roms table applies).
        copyAssetIfMissing("intv-roms/exec.bin", File(romsDir, "exec.bin"))
        copyAssetIfMissing("intv-roms/grom.bin", File(romsDir, "grom.bin"))
        copyAssetIfMissing("intv-roms/ecs.bin", File(romsDir, "ecs.bin"))

        val cartsDir = File(root, "carts").apply { mkdirs() }

        return Paths(
            filesDir = root.absolutePath,
            romsDir = romsDir.absolutePath,
            cartsDir = cartsDir.absolutePath,
        )
    }

    private fun copyAssetIfMissing(assetPath: String, dest: File) {
        if (dest.exists()) return
        try {
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            // Absent asset (release build, or no -PintvRoms) -- expected; the
            // ROM gate (ui/RomGate.kt) handles prompting the user to import.
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val assets: AssetManager = context.assets
        val entries = try {
            assets.list(assetPath) ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        if (entries.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // Absent asset; skip silently.
            }
            return
        }
        dest.mkdirs()
        for (entry in entries) {
            copyAssetDir("$assetPath/$entry", File(dest, entry))
        }
    }
}
