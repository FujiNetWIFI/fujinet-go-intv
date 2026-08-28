package online.fujinet.go.intv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import online.fujinet.go.intv.fujinet.FujiNetWebViewActivity
import online.fujinet.go.intv.ui.SettingsScreen
import online.fujinet.go.intv.ui.theme.FujiNetGoIntvTheme

/**
 * Hosts the settings UI (FujiNet configuration/ECS/Intellivoice/video
 * standard/cartridge/ROM import/haptics) as a centered dialog over a
 * translucent window, so it floats over the running emulator. "Apply &
 * Restart" persists the restart-requiring options and reboots the session;
 * dismissing closes the activity without applying an unconfirmed change.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FujiNetGoIntvTheme {
                SettingsScreen(
                    onApplyRestart = {
                        SessionController.get(applicationContext).restart()
                        finish()
                    },
                    // finish() so the dialog isn't left stacked behind the
                    // web UI for the user to back out through twice.
                    onOpenFujiNet = {
                        startActivity(Intent(this, FujiNetWebViewActivity::class.java))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}
