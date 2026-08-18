package online.fujinet.go.intv.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import online.fujinet.go.intv.MediaImport
import online.fujinet.go.intv.R
import online.fujinet.go.intv.settings.RomStore
import online.fujinet.go.intv.ui.theme.IntvGreen
import online.fujinet.go.intv.ui.theme.SticYellow

/**
 * Shown in place of the emulator surface until exec.bin and grom.bin are
 * present -- release builds ship no system ROMs (see COMPLIANCE.md), so this
 * is every user's first-run screen unless they built with -PintvRoms=true.
 * [onImported] is called after each import attempt so the caller can
 * re-check [RomStore.hasSystemRoms] and start the session once satisfied.
 */
@Composable
fun RomGate(onImported: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val status = remember(refreshToken) { RomStore.status(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val result = MediaImport.importSystemRom(context, uri)) {
            is MediaImport.RomImportResult.Success -> {
                Toast.makeText(context, "Imported ${result.fileName}", Toast.LENGTH_SHORT).show()
            }
            is MediaImport.RomImportResult.WrongSize -> {
                Toast.makeText(
                    context,
                    "Not a recognized ROM (${result.actualSize} bytes; expected 8192, 2048, or 24576)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            is MediaImport.RomImportResult.WrongCrc -> {
                Toast.makeText(
                    context,
                    "Right size for ${result.wouldBe} but not the known dump -- " +
                        "cartridges are imported from Settings, not here",
                    Toast.LENGTH_LONG,
                ).show()
            }
            MediaImport.RomImportResult.ReadFailed -> {
                Toast.makeText(context, "Could not read the selected file", Toast.LENGTH_SHORT).show()
            }
        }
        refreshToken++
        onImported()
    }

    Column(
        modifier = modifier.fillMaxSize().background(IntvGreen).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.fujinet_toolbar),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Text(
            "Intellivision system ROMs required",
            style = MaterialTheme.typography.titleLarge,
            color = SticYellow,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            "Import exec.bin and grom.bin from your own dumps to boot. " +
                "ecs.bin is optional -- only needed to enable ECS in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFE8F2E4),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(modifier = Modifier.padding(bottom = 16.dp)) {
            items(status) { rom ->
                val label = if (rom.present) "✓ ${rom.name} (${rom.expectedSize} B)" else "✗ ${rom.name} (expects ${rom.expectedSize} B)"
                Text(
                    label,
                    color = if (rom.present) SticYellow else androidx.compose.ui.graphics.Color(0xFFE8F2E4),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        Button(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text("Import ROMs…")
        }
    }
}
