package online.fujinet.go.intv.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 *
 * The control bar's reset button is two controls in one, matching what the
 * console's own front-panel RESET can and cannot do: a tap is the switch
 * itself ([onResetGame], soft reset with the cart left mapped), a press-and-
 * hold is the way back to the FujiNet config ROM ([onResetToConfig]).
 */
@Composable
fun EmulatorScreen(
    session: SessionController,
    onResetGame: () -> Unit,
    onResetToConfig: () -> Unit,
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
            hapticsEnabled = haptics,
            onResetGame = onResetGame,
            onResetToConfig = onResetToConfig,
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
        } else if (landscape && overlay == Overlay.ECS_KEYBOARD) {
            // The keyboard splits into two halves flanking the surface in
            // the pillar-box margins, same shape as the rest of the family's
            // LandscapeSplitKeyboard.
            LandscapeSplitEcsKeyboard(
                session = session,
                hapticsEnabled = haptics,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
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
    hapticsEnabled: Boolean,
    onToggleController: () -> Unit,
    onToggleEcs: () -> Unit,
    onResetGame: () -> Unit,
    onResetToConfig: () -> Unit,
    onOpenSettings: () -> Unit,
    onShutdown: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = rememberFujiHaptic(FujiHapticPattern.KeyPress)

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
        BarButton(
            Icons.Filled.RestartAlt, "Reset (hold for FujiNet config)", Modifier.weight(1f),
            onClick = onResetGame,
            // A hold hands the machine back to the config ROM, which blanks
            // the screen for as long as the restart takes: without the pulse
            // and the toast it reads as a tap that didn't register.
            onLongClick = {
                if (hapticsEnabled) haptic()
                Toast.makeText(context, "Resetting to FujiNet config…", Toast.LENGTH_SHORT).show()
                onResetToConfig()
            },
        )
        BarButton(Icons.Filled.Settings, "Settings", Modifier.weight(1f), onClick = onOpenSettings)
        BarButton(Icons.Filled.PowerSettingsNew, "Power off", Modifier.weight(1f), onClick = onShutdown)
    }
}

/**
 * An inactive button is a muted icon on transparent; an active one is a
 * dark-green filled pill with a STIC-yellow icon. This is a deliberate
 * deviation from the family's primary/onPrimary convention (see
 * ui/theme/Theme.kt's own comment): with primary=SticYellow in the dark
 * scheme, following that convention directly would invert the intended
 * visual weight of "controls are green, highlight is yellow."
 *
 * Material3's TextButton takes no long-click, so a button that wants one
 * (the reset button) is built from a Box + combinedClickable instead, sized
 * to TextButton's own 40dp minimum so the row's geometry doesn't shift
 * between the two paths. Same shape as FujiNet Go 800's ShellIconButton.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    if (onLongClick == null) {
        TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) {
            BarButtonContent(icon, contentDescription, active, enabled)
        }
        return
    }

    Box(
        modifier = modifier
            // TextButton's own touch-target enforcement, minimum height and
            // shape, in the order its Surface applies them, so the reset
            // button sits and ripples exactly like the four beside it.
            .minimumInteractiveComponentSize()
            .heightIn(min = ButtonDefaults.MinHeight)
            .clip(ButtonDefaults.textShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BarButtonContent(icon, contentDescription, active, enabled)
    }
}

@Composable
private fun BarButtonContent(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean,
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
