package online.fujinet.go.intv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The Intellivision console's dark green shell -- also the launcher icon
// background (tools/icons/make-icons.py) and the UI accent for controls,
// buttons and toolbar icons per the user's brief.
val IntvGreen = Color(0xFF1E4912)

// The bright yellow the STIC can generate: default_ntsc palette index 6 in
// jzIntv (fujinet-go-intv-desktop/core/jzintv-generated/src/gfx/palette.c),
// used as the highlight/active color throughout.
val SticYellow = Color(0xFFFFFF01)

private val IntvInk = Color(0xFF0A1409)      // near-black, green-shifted
val IntvMist = Color(0xFFE8F2E4)             // off-white, used for legends/icons
val IntvOutline = Color(0xFF2E6B1C)

// Contrast note: #1E4912 is ~9:1 against white but only ~1.6:1 against
// black. Rather than fight that, the two brand colors swap accent roles
// between schemes -- yellow-on-green in dark (the app's normal state, since
// the emulator surface and window background are black), green-on-yellow-ish
// in light -- so both stay WCAG-AA instead of one silently failing contrast.
// ControlBar and the controller widgets deliberately do NOT follow
// primary/onPrimary directly; see EmulatorScreen.kt / ControllerPad.kt for
// the explicit green-fill/yellow-highlight rule that matches the user's
// brief regardless of scheme.

private val DarkColors = darkColorScheme(
    primary = SticYellow,
    onPrimary = IntvGreen,
    primaryContainer = IntvGreen,
    onPrimaryContainer = SticYellow,
    secondary = IntvOutline,
    background = IntvInk,
    surface = IntvGreen,
    onSurface = IntvMist,
    outline = IntvOutline,
)

private val LightColors = lightColorScheme(
    primary = IntvGreen,
    onPrimary = SticYellow,
    primaryContainer = SticYellow,
    onPrimaryContainer = IntvGreen,
    secondary = IntvOutline,
)

@Composable
fun FujiNetGoIntvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
