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
 * System ROMs are classified by **exact byte size**, not filename or
 * extension -- SAF display names are arbitrary, but exec.bin/grom.bin/
 * ecs.bin have fixed, known sizes (see Intv.EXEC_SIZE/GROM_SIZE/ECS_SIZE).
 */
object MediaImport {

    sealed class RomImportResult {
        data class Success(val fileName: String) : RomImportResult()
        data class WrongSize(val actualSize: Long) : RomImportResult()
        object ReadFailed : RomImportResult()
    }

    fun importSystemRom(context: Context, uri: Uri): RomImportResult {
        val romsDir = RomStore.romsDir(context)
        val temp = File(romsDir, ".import-tmp")
        val copied = copyTo(context, uri, temp) ?: return RomImportResult.ReadFailed

        val target = when (copied.length()) {
            Intv.EXEC_SIZE.toLong() -> "exec.bin"
            Intv.GROM_SIZE.toLong() -> "grom.bin"
            Intv.ECS_SIZE.toLong() -> "ecs.bin"
            else -> {
                copied.delete()
                return RomImportResult.WrongSize(copied.length())
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
     */
    fun importCartridge(context: Context, uri: Uri): File? {
        val name = displayName(context, uri) ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("rom", "bin", "int", "cfg")) return null
        val dest = File(RomStore.cartsDir(context), name)
        return copyTo(context, uri, dest)
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
