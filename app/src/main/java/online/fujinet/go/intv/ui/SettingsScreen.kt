package online.fujinet.go.intv.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import online.fujinet.go.intv.MediaImport
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.settings.HwMode
import online.fujinet.go.intv.settings.RomStore
import online.fujinet.go.intv.settings.VideoStandard

/**
 * Settings: ECS / Intellivoice (tri-state Auto/Off/On, matching jzIntv's own
 * cart-metadata-driven default -- see MachineSettingsStore's own comment on
 * why this is not collapsed to a two-state switch), NTSC/PAL (radio toggle),
 * cartridge selection, system-ROM status/import, and haptics. Presented as a
 * centered dialog over the running emulator, like the rest of the family.
 * "Apply & Restart" persists every restart-requiring option and reboots the
 * session (jzIntv is a process-wide singleton -- see session_runtime.cpp);
 * haptics apply live regardless.
 */
@Composable
fun SettingsScreen(
    onApplyRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val session = remember { SessionController.get(context) }
    val settings = session.settings

    var ecs by remember { mutableStateOf(settings.ecs) }
    var ivoice by remember { mutableStateOf(settings.ivoice) }
    var video by remember { mutableStateOf(settings.video) }
    var haptics by remember { mutableStateOf(session.keyboardHapticsEnabled) }
    var cartName by remember { mutableStateOf(settings.cartName) }
    var cartPath by remember { mutableStateOf(settings.cartPath) }
    var romStatus by remember { mutableStateOf(RomStore.status(context)) }

    val ecsRomPresent = RomStore.hasEcsRom(context)

    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val result = MediaImport.importSystemRom(context, uri)) {
            is MediaImport.RomImportResult.Success ->
                Toast.makeText(context, "Imported ${result.fileName}", Toast.LENGTH_SHORT).show()
            is MediaImport.RomImportResult.WrongSize ->
                Toast.makeText(context, "Not a recognized ROM (${result.actualSize} bytes)", Toast.LENGTH_LONG).show()
            is MediaImport.RomImportResult.WrongCrc ->
                Toast.makeText(
                    context,
                    "Right size for ${result.wouldBe} but not the known dump -- use the cartridge picker for game ROMs",
                    Toast.LENGTH_LONG,
                ).show()
            MediaImport.RomImportResult.ReadFailed ->
                Toast.makeText(context, "Could not read the selected file", Toast.LENGTH_SHORT).show()
        }
        romStatus = RomStore.status(context)
    }

    val cartPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = MediaImport.importCartridge(context, uri)
        if (file != null) {
            cartName = file.name
            cartPath = file.absolutePath
        } else {
            Toast.makeText(context, "Expected a .rom/.bin/.int/.cfg file", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                settings.ecs = ecs
                settings.ivoice = ivoice
                settings.video = video
                settings.cartPath = cartPath
                settings.cartName = cartName
                onApplyRestart()
            }) { Text("Apply & Restart") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("FujiNet Go Intv — Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("ECS", style = MaterialTheme.typography.titleSmall)
                HwMode.entries.forEach { mode ->
                    val disabled = mode != HwMode.OFF && !ecsRomPresent
                    RadioRow(mode.label, ecs == mode, enabled = !disabled) { ecs = mode }
                }
                if (!ecsRomPresent) {
                    Text(
                        "Unavailable: ecs.bin not found in the ROM directory",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Intellivoice", style = MaterialTheme.typography.titleSmall)
                HwMode.entries.forEach { mode ->
                    RadioRow(mode.label, ivoice == mode) { ivoice = mode }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Video standard", style = MaterialTheme.typography.titleSmall)
                VideoStandard.entries.forEach { standard ->
                    RadioRow(standard.label, video == standard) { video = standard }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Cartridge", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (cartName.isNotEmpty()) cartName else "(none -- FujiNet config ROM)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { cartPicker.launch(arrayOf("*/*")) }) { Text("Change…") }
                    if (cartPath.isNotEmpty()) {
                        TextButton(onClick = { cartName = ""; cartPath = "" }) { Text("Eject") }
                    }
                }
                Text(
                    "A .bin without a matching .cfg uses jzIntv's default memory map.",
                    style = MaterialTheme.typography.labelSmall,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("System ROMs", style = MaterialTheme.typography.titleSmall)
                romStatus.forEach { rom ->
                    Text(
                        "${if (rom.present) "✓" else "✗"} ${rom.name} (${rom.expectedSize} B)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { romPicker.launch(arrayOf("*/*")) }) { Text("Import…") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Haptics", style = MaterialTheme.typography.titleSmall)
                ToggleRow("Controller haptics", haptics) {
                    haptics = it
                    session.keyboardHapticsEnabled = it
                }
            }
        },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
