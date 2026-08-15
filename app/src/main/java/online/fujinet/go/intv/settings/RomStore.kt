package online.fujinet.go.intv.settings

import android.content.Context
import java.io.File
import online.fujinet.go.intv.core.EmulatorNative

/**
 * System-ROM / cartridge directory layout and presence checks. Mirrors the
 * layout RuntimeInstaller.install() produces (<filesDir>/intv/{roms,carts}),
 * computed independently here (rather than round-tripped through JNI) so the
 * "System ROMs required" gate (ui/RomGate.kt) can check status before a
 * session -- and therefore the native roms_dir -- exists.
 */
object RomStore {
    fun romsDir(context: Context): File =
        File(File(context.filesDir, "intv"), "roms").apply { mkdirs() }.also { stageEmbeddedRoms(context, it) }

    // Dev-only, -PintvRoms=true builds ship exec.bin/grom.bin/ecs.bin under
    // assets/intv-roms; release builds ship none (see COMPLIANCE.md). This
    // must run before hasSystemRoms()/status() are read anywhere -- both
    // RomGate (the pre-session check) and SessionController.startIfNeeded()
    // (which historically only staged via RuntimeInstaller.install() *after*
    // confirming ROMs were present) otherwise deadlock: nothing ever copies
    // the bundled ROMs out of assets in the first place. Only fills in what's
    // missing, so a user-imported ROM always wins over the compiled-in one.
    private fun stageEmbeddedRoms(context: Context, dir: File) {
        fun copyIfMissing(name: String) {
            val dest = File(dir, name)
            if (dest.exists()) return
            try {
                context.assets.open("intv-roms/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // Absent asset (release build, or no -PintvRoms) -- expected.
            }
        }
        copyIfMissing("exec.bin")
        copyIfMissing("grom.bin")
        copyIfMissing("ecs.bin")
    }

    fun cartsDir(context: Context): File =
        File(File(context.filesDir, "intv"), "carts").apply { mkdirs() }

    fun hasSystemRoms(context: Context): Boolean =
        EmulatorNative.nativeHasSystemRoms(romsDir(context).absolutePath)

    fun hasEcsRom(context: Context): Boolean =
        EmulatorNative.nativeHasEcsRom(romsDir(context).absolutePath)

    data class RomStatus(val name: String, val expectedSize: Int, val present: Boolean, val actualSize: Long)

    fun status(context: Context): List<RomStatus> {
        val dir = romsDir(context)
        fun check(name: String, size: Int): RomStatus {
            val f = File(dir, name)
            return RomStatus(name, size, f.isFile && f.length() == size.toLong(), if (f.isFile) f.length() else 0)
        }
        return listOf(
            check("exec.bin", online.fujinet.go.intv.input.Intv.EXEC_SIZE),
            check("grom.bin", online.fujinet.go.intv.input.Intv.GROM_SIZE),
            check("ecs.bin", online.fujinet.go.intv.input.Intv.ECS_SIZE),
        )
    }

    fun cartridges(context: Context): List<File> =
        cartsDir(context).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("rom", "bin", "int") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
}
