package online.fujinet.go.intv

import java.io.File
import online.fujinet.go.intv.input.Intv
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaImportClassifyTest {

    @Test
    fun canonicalDumpsClassifyToTheirSlot() {
        assertEquals(
            MediaImport.RomImportResult.Success("exec.bin"),
            MediaImport.classify(Intv.EXEC_SIZE.toLong(), Intv.EXEC_CRC32),
        )
        assertEquals(
            MediaImport.RomImportResult.Success("grom.bin"),
            MediaImport.classify(Intv.GROM_SIZE.toLong(), Intv.GROM_CRC32),
        )
        assertEquals(
            MediaImport.RomImportResult.Success("ecs.bin"),
            MediaImport.classify(Intv.ECS_SIZE.toLong(), Intv.ECS_CRC32),
        )
    }

    @Test
    fun eightKCartridgeIsRejectedNotWrittenToExecSlot() {
        // Any 8192-byte file that is not the canonical EXEC dump -- e.g. a
        // cartridge -- must be rejected, naming the slot it would have hit.
        val result = MediaImport.classify(Intv.EXEC_SIZE.toLong(), 0xDEADBEEFL)
        assertEquals(
            MediaImport.RomImportResult.WrongCrc("exec.bin", 0xDEADBEEFL),
            result,
        )
    }

    @Test
    fun unknownSizeIsWrongSize() {
        assertEquals(
            MediaImport.RomImportResult.WrongSize(4096L),
            MediaImport.classify(4096L, Intv.EXEC_CRC32),
        )
    }

    @Test
    fun memoryMapSidecarsAreNotBootableCartridges() {
        // A .cfg handed to jzIntv as the cart path exits the process, so the
        // picker has to tell the two apart -- whatever case the name carries.
        assertEquals(true, MediaImport.isMemoryMap(File("/carts/Pole Position.cfg")))
        assertEquals(true, MediaImport.isMemoryMap(File("/carts/POLE POSITION.CFG")))
        assertEquals(false, MediaImport.isMemoryMap(File("/carts/Pole Position.bin")))
        assertEquals(false, MediaImport.isMemoryMap(File("/carts/config.rom")))
    }

    @Test
    fun crcOfWrongSlotDoesNotCrossMatch() {
        // grom's CRC presented at exec's size must not classify as anything.
        assertEquals(
            MediaImport.RomImportResult.WrongCrc("exec.bin", Intv.GROM_CRC32),
            MediaImport.classify(Intv.EXEC_SIZE.toLong(), Intv.GROM_CRC32),
        )
    }
}
