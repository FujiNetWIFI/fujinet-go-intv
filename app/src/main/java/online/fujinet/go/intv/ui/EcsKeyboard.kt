package online.fujinet.go.intv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.ui.theme.IntvGreen
import online.fujinet.go.intv.ui.theme.IntvOutline
import online.fujinet.go.intv.ui.theme.SticYellow

/**
 * The ECS's on-screen keyboard, transcribed from fujinet-go-intv-desktop's
 * frontends/gnome/ecskbd/ecskbd_window.c layout -- every one of the 48
 * INTVSESSION_ECS_KEY_* ids appears exactly once. Only reachable (see
 * EmulatorScreen.kt) when ECS is enabled and ecs.bin is present.
 *
 * SHIFT and CTRL are latching toggles rather than momentary keys -- a finger
 * can't hold two keys down for a chord on a touchscreen -- relying on
 * intv_host_ecs_key's OR-in-a-bit behaviour to combine them with the next
 * ordinary key tapped.
 */
@Composable
fun EcsKeyboard(
    session: SessionController,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var shiftLatched by remember { mutableStateOf(false) }
    var ctrlLatched by remember { mutableStateOf(false) }

    // Clear every key (including the latches) whenever this composable
    // leaves composition -- overlay hide/switch already routes through
    // EmulatorScreen's own ecsKeysClear() calls, but this is a second,
    // belt-and-braces guard against a stuck matrix bit.
    DisposableEffect(Unit) {
        onDispose {
            if (shiftLatched) session.ecsKey(Intv.ECS_KEY_SHIFT, false)
            if (ctrlLatched) session.ecsKey(Intv.ECS_KEY_CTRL, false)
        }
    }

    val rows = remember {
        listOf(
            listOf(
                Intv.ECS_KEY_ESC to "ESC", Intv.ECS_KEY_1 to "1", Intv.ECS_KEY_2 to "2",
                Intv.ECS_KEY_3 to "3", Intv.ECS_KEY_4 to "4", Intv.ECS_KEY_5 to "5",
                Intv.ECS_KEY_6 to "6", Intv.ECS_KEY_7 to "7", Intv.ECS_KEY_8 to "8",
                Intv.ECS_KEY_9 to "9", Intv.ECS_KEY_0 to "0",
            ),
            listOf(
                Intv.ECS_KEY_Q to "Q", Intv.ECS_KEY_W to "W", Intv.ECS_KEY_E to "E",
                Intv.ECS_KEY_R to "R", Intv.ECS_KEY_T to "T", Intv.ECS_KEY_Y to "Y",
                Intv.ECS_KEY_U to "U", Intv.ECS_KEY_I to "I", Intv.ECS_KEY_O to "O",
                Intv.ECS_KEY_P to "P",
            ),
            listOf(
                Intv.ECS_KEY_A to "A", Intv.ECS_KEY_S to "S", Intv.ECS_KEY_D to "D",
                Intv.ECS_KEY_F to "F", Intv.ECS_KEY_G to "G", Intv.ECS_KEY_H to "H",
                Intv.ECS_KEY_J to "J", Intv.ECS_KEY_K to "K", Intv.ECS_KEY_L to "L",
                Intv.ECS_KEY_SEMI to ";",
            ),
            listOf(
                Intv.ECS_KEY_Z to "Z", Intv.ECS_KEY_X to "X", Intv.ECS_KEY_C to "C",
                Intv.ECS_KEY_V to "V", Intv.ECS_KEY_B to "B", Intv.ECS_KEY_N to "N",
                Intv.ECS_KEY_M to "M", Intv.ECS_KEY_COMMA to ",", Intv.ECS_KEY_PERIOD to ".",
            ),
        )
    }

    Column(modifier = modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((key, label) in row) {
                    EcsKey(label, hapticsEnabled) { pressed -> session.ecsKey(key, pressed) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LatchKey("SHIFT", shiftLatched, hapticsEnabled) {
                shiftLatched = !shiftLatched
                session.ecsKey(Intv.ECS_KEY_SHIFT, shiftLatched)
            }
            LatchKey("CTRL", ctrlLatched, hapticsEnabled) {
                ctrlLatched = !ctrlLatched
                session.ecsKey(Intv.ECS_KEY_CTRL, ctrlLatched)
            }
            EcsKey("LEFT", hapticsEnabled) { pressed -> session.ecsKey(Intv.ECS_KEY_LEFT, pressed) }
            EcsKey("UP", hapticsEnabled) { pressed -> session.ecsKey(Intv.ECS_KEY_UP, pressed) }
            EcsKey("DOWN", hapticsEnabled) { pressed -> session.ecsKey(Intv.ECS_KEY_DOWN, pressed) }
            EcsKey("RIGHT", hapticsEnabled) { pressed -> session.ecsKey(Intv.ECS_KEY_RIGHT, pressed) }
            EcsKey("SPACE", hapticsEnabled, widthDp = 96) { pressed -> session.ecsKey(Intv.ECS_KEY_SPACE, pressed) }
            EcsKey("ENTER", hapticsEnabled, widthDp = 56) { pressed -> session.ecsKey(Intv.ECS_KEY_ENTER, pressed) }
        }
    }
}

@Composable
private fun EcsKey(
    label: String,
    hapticsEnabled: Boolean,
    widthDp: Int = 32,
    onHold: (Boolean) -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    val emit = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val currentHold = rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (held) IntvOutline else IntvGreen)
            .border(1.dp, IntvOutline, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    if (hapticsEnabled) emit()
                    currentHold.value(true)
                    try {
                        awaitRelease()
                    } finally {
                        held = false
                        currentHold.value(false)
                    }
                })
            }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (held) SticYellow else Color(0xFFE8F2E4), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LatchKey(label: String, latched: Boolean, hapticsEnabled: Boolean, onToggle: () -> Unit) {
    val emit = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    Box(
        modifier = Modifier
            .width(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (latched) SticYellow else IntvGreen)
            .border(1.dp, IntvOutline, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (hapticsEnabled) emit()
                    onToggle()
                })
            }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (latched) IntvGreen else Color(0xFFE8F2E4),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
