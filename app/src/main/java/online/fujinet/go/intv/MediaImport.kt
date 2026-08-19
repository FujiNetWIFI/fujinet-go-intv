package online.fujinet.go.intv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.settings.RomStore

/**
 * Storage Access Framework import for Intellivision system ROMs and
 * cartridges. Modelled on fujinet-go-msdos's MediaImport.kt: copy the picked
 * document into app-private storage, then classify it.
 *
 * System ROMs are classified by **exact byte size and CRC32**, not filename
 * or extension -- SAF display names are arbitrary, and size alone can't tell
 * an 8 KB cartridge from exec.bin (an earlier size-only version of this code
 * would silently corrupt the EXEC slot with such a pick). The system images
 * each have a single canonical revision, so an exact CRC match is safe.
 */
object MediaImport {

    sealed class RomImportResult {
        data class Success(val fileName: String) : RomImportResult()
        data class WrongSize(val actualSize: Long) : RomImportResult()
        /** Right size for [wouldBe], but not the known dump -- likely a cartridge. */
        data class WrongCrc(val wouldBe: String, val actualCrc: Long) : RomImportResult()
        object ReadFailed : RomImportResult()
    }

    /** Pure size+CRC classification, split out so it is unit-testable. */
    internal fun classify(size: Long, crc: Long): RomImportResult {
        val (name, expectedCrc) = when (size) {
            Intv.EXEC_SIZE.toLong() -> "exec.bin" to Intv.EXEC_CRC32
            Intv.GROM_SIZE.toLong() -> "grom.bin" to Intv.GROM_CRC32
            Intv.ECS_SIZE.toLong() -> "ecs.bin" to Intv.ECS_CRC32
            else -> return RomImportResult.WrongSize(size)
        }
        if (crc != expectedCrc) return RomImportResult.WrongCrc(name, crc)
        return RomImportResult.Success(name)
    }

    fun importSystemRom(context: Context, uri: Uri): RomImportResult {
        val romsDir = RomStore.romsDir(context)
        val temp = File(romsDir, ".import-tmp")
        val copied = copyTo(context, uri, temp) ?: return RomImportResult.ReadFailed

        val verdict = classify(copied.length(), crc32Of(copied))
        val target = when (verdict) {
            is RomImportResult.Success -> verdict.fileName
            else -> {
                copied.delete()
                return verdict
            }
        }
        val dest = File(romsDir, target)
        dest.delete()
        if (!copied.renameTo(dest)) {
            copied.copyTo(dest, overwrite = true)
            copied.delete()
        }
        return RomImportResult.Success(target)
    }

    /**
     * Copies a cartridge (.rom/.bin/.int) or a memory-map sidecar (.cfg)
     * into the carts directory under its SAF display name. A raw .bin uses
     * jzIntv's default memory map unless a same-basename .cfg sits beside
     * it -- callers importing a .cfg should name it to match an already-
     * imported cartridge.
     *
     * A returned .cfg is *not* a cartridge: see [isMemoryMap].
     */
    fun importCartridge(context: Context, uri: Uri): File? {
        val name = displayName(context, uri) ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("rom", "bin", "int", "cfg")) return null
        val dest = File(RomStore.cartsDir(context), name)
        return copyTo(context, uri, dest)
    }

    /**
     * True when [file] is a memory-map sidecar rather than a bootable image.
     *
     * A .cfg must never be handed to the emulator as the cartridge: jzIntv
     * takes the cart path as its trailing positional argument, finds no
     * companion ROM for a bare .cfg, and calls exit(1) -- which on Android is
     * a silent process kill with no crash dialog and nothing in the log.
     */
    fun isMemoryMap(file: File): Boolean = file.extension.equals("cfg", ignoreCase = true)

    private fun crc32Of(file: File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun copyTo(context: Context, uri: Uri, dest: File): File? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest
        } catch (_: Exception) {
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment
    }
}
