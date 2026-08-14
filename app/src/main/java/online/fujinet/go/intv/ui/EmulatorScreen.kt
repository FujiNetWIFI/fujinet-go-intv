package online.fujinet.go.intv.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import online.fujinet.go.intv.R
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.settings.HwMode
import online.fujinet.go.intv.settings.RomStore
import online.fujinet.go.intv.ui.theme.IntvGreen
import online.fujinet.go.intv.ui.theme.SticYellow

private enum class Overlay { CONTROLLER, ECS_KEYBOARD, NONE }

/**
 * The main app screen: the Intellivision video surface, a thin control bar,
 * and the input overlay. The combined controller (disc + keypad + actions)
 * is the **default** view -- see [Overlay]'s initial value -- with the ECS
 * keyboard reachable as a second overlay only when ECS is enabled and
 * ecs.bin is present.
 */
@Composable
fun EmulatorScreen(
    session: SessionController,
    onOpenFujiNet: () -> Unit,
    onOpenSettings: () -> Unit,
    onShutdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasRoms by remember { mutableStateOf(RomStore.hasSystemRoms(context)) }

    if (!hasRoms) {
        RomGate(onImported = { hasRoms = RomStore.hasSystemRoms(context) }, modifier = modifier)
        return
    }

    var overlay by remember { mutableStateOf(Overlay.CONTROLLER) }
    var controllerSide by remember { mutableIntStateOf(Intv.PAD_LEFT) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var haptics by remember { mutableStateOf(session.keyboardHapticsEnabled) }
    val ecsAvailable = session.settings.ecs != HwMode.OFF && RomStore.hasEcsRom(context)

    // Haptics are toggled in the separate SettingsActivity; re-read on
    // resume so a change there takes effect without restarting the session.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                haptics = session.keyboardHapticsEnabled
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                session.ecsKeysClear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setOverlay(next: Overlay) {
        if (overlay == Overlay.ECS_KEYBOARD && next != Overlay.ECS_KEYBOARD) {
            session.ecsKeysClear()
        }
        overlay = next
    }

    Column(modifier = modifier.fillMaxSize()) {
        ControlBar(
            controllerActive = overlay == Overlay.CONTROLLER,
            ecsActive = overlay == Overlay.ECS_KEYBOARD,
            ecsAvailable = ecsAvailable,
            onToggleController = {
                setOverlay(if (overlay == Overlay.CONTROLLER) Overlay.NONE else Overlay.CONTROLLER)
            },
            onToggleEcs = {
                if (ecsAvailable) {
                    setOverlay(if (overlay == Overlay.ECS_KEYBOARD) Overlay.NONE else Overlay.ECS_KEYBOARD)
                }
            },
            onOpenFujiNet = onOpenFujiNet,
            onOpenSettings = onOpenSettings,
            onShutdown = onShutdown,
        )

        if (landscape && overlay == Overlay.CONTROLLER) {
            // Controls flank the surface in the pillar-box margins so the
            // picture keeps full height.
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IntvDisc(session, controllerSide, haptics)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    IntvSideSelector(controllerSide, ecsAvailable, onSideChange = { controllerSide = it })
                }
                EmulatorSurface(session = session, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IntvKeypad(session, controllerSide)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    IntvActions(session, controllerSide, haptics)
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmulatorSurface(session = session, modifier = Modifier.fillMaxSize())
            }
            when (overlay) {
                Overlay.CONTROLLER -> IntvController(
                    session = session,
                    side = controllerSide,
                    hapticsEnabled = haptics,
                    ecsAvailable = ecsAvailable,
                    onSideChange = { controllerSide = it },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Overlay.ECS_KEYBOARD -> EcsKeyboard(session = session, hapticsEnabled = haptics)
                Overlay.NONE -> {}
            }
        }
    }
}

@Composable
private fun ControlBar(
    controllerActive: Boolean,
    ecsActive: Boolean,
    ecsAvailable: Boolean,
    onToggleController: () -> Unit,
    onToggleEcs: () -> Unit,
    onOpenFujiNet: () -> Unit,
    onOpenSettings: () -> Unit,
    onShutdown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarButton(
            Icons.Filled.SportsEsports, "Controller", Modifier.weight(1f), controllerActive,
            onClick = onToggleController,
        )
        BarButton(
            Icons.Filled.Keyboard, "ECS keyboard", Modifier.weight(1f), ecsActive,
            enabled = ecsAvailable, onClick = onToggleEcs,
        )
        FujiNetBarButton(Modifier.weight(1f), onClick = onOpenFujiNet)
        BarButton(Icons.Filled.Settings, "Settings", Modifier.weight(1f), onClick = onOpenSettings)
        BarButton(Icons.Filled.PowerSettingsNew, "Power off", Modifier.weight(1f), onClick = onShutdown)
    }
}

/** The FujiNet web-UI button: the FujiNet "dot" logo, tinted to the dark
 * green UI accent (Modulate keeps the black centre dot black and the
 * corners transparent, recolouring only the white tile). */
@Composable
private fun FujiNetBarButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.fujinet_toolbar),
            contentDescription = "FujiNet web UI",
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(IntvGreen, BlendMode.Modulate),
        )
    }
}

/**
 * An inactive button is a muted icon on transparent; an active one is a
 * dark-green filled pill with a STIC-yellow icon. This is a deliberate
 * deviation from the family's primary/onPrimary convention (see
 * ui/theme/Theme.kt's own comment): with primary=SticYellow in the dark
 * scheme, following that convention directly would invert the intended
 * visual weight of "controls are green, highlight is yellow."
 */
@Composable
private fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = if (active) {
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(IntvGreen)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> Color(0xFFE8F2E4).copy(alpha = 0.3f)
                    active -> SticYellow
                    else -> Color(0xFFE8F2E4).copy(alpha = 0.75f)
                },
            )
        }
    }
}
