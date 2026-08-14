package online.fujinet.go.intv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.ui.theme.IntvGreen
import online.fujinet.go.intv.ui.theme.IntvMist
import online.fujinet.go.intv.ui.theme.SticYellow

/**
 * The ECS's on-screen keyboard. Implemented the same way as the rest of the
 * family's on-screen keyboards (MsxKeyboard.kt / CocoKeyboard.kt /
 * AppleKeyboard.kt: a shared row-data model, a focus-ring-aware KeyBox
 * building block, a modifiers holder class threaded through both the
 * stacked and landscape-split layouts, and a CompositionLocal for haptics/
 * key size), recoloured to this app's dark-green/STIC-yellow palette
 * instead of copying another target's colors.
 *
 * Uses the CoCo shape (press-and-hold KeyBox, not MSX's tap-emit-both):
 * the ECS's keyboard is a live 7x8 scan matrix (core/jzintv/intv_host.h),
 * so a key needs to be genuinely held down for as long as the finger (or a
 * focused D-pad OK) is down, the same reasoning CoCo's 60Hz matrix scan has.
 *
 * Layout transcribed from fujinet-go-intv-desktop's
 * frontends/gnome/ecskbd/ecskbd_window.c -- every one of the 48
 * INTVSESSION_ECS_KEY_* ids appears exactly once across DIGIT_ROW/ROW1/
 * ROW2/ROW3 plus the hand-built SHIFT/CTRL/arrows/SPACE/ENTER row.
 */

private data class EKey(val label: String, val code: Int, val weight: Float = 1f)

private val DIGIT_ROW = listOf(
    EKey("ESC", Intv.ECS_KEY_ESC, 1.4f),
    EKey("1", Intv.ECS_KEY_1), EKey("2", Intv.ECS_KEY_2), EKey("3", Intv.ECS_KEY_3),
    EKey("4", Intv.ECS_KEY_4), EKey("5", Intv.ECS_KEY_5), EKey("6", Intv.ECS_KEY_6),
    EKey("7", Intv.ECS_KEY_7), EKey("8", Intv.ECS_KEY_8), EKey("9", Intv.ECS_KEY_9),
    EKey("0", Intv.ECS_KEY_0),
)
private val ROW1 = listOf(
    EKey("Q", Intv.ECS_KEY_Q), EKey("W", Intv.ECS_KEY_W), EKey("E", Intv.ECS_KEY_E),
    EKey("R", Intv.ECS_KEY_R), EKey("T", Intv.ECS_KEY_T), EKey("Y", Intv.ECS_KEY_Y),
    EKey("U", Intv.ECS_KEY_U), EKey("I", Intv.ECS_KEY_I), EKey("O", Intv.ECS_KEY_O),
    EKey("P", Intv.ECS_KEY_P),
)
private val ROW2 = listOf(
    EKey("A", Intv.ECS_KEY_A), EKey("S", Intv.ECS_KEY_S), EKey("D", Intv.ECS_KEY_D),
    EKey("F", Intv.ECS_KEY_F), EKey("G", Intv.ECS_KEY_G), EKey("H", Intv.ECS_KEY_H),
    EKey("J", Intv.ECS_KEY_J), EKey("K", Intv.ECS_KEY_K), EKey("L", Intv.ECS_KEY_L),
    EKey(";", Intv.ECS_KEY_SEMI),
)
private val ROW3 = listOf(
    EKey("Z", Intv.ECS_KEY_Z), EKey("X", Intv.ECS_KEY_X), EKey("C", Intv.ECS_KEY_C),
    EKey("V", Intv.ECS_KEY_V), EKey("B", Intv.ECS_KEY_B), EKey("N", Intv.ECS_KEY_N),
    EKey("M", Intv.ECS_KEY_M), EKey(",", Intv.ECS_KEY_COMMA), EKey(".", Intv.ECS_KEY_PERIOD),
)

// digit row + ROW1 + ROW2 + (SHIFT+ROW3) + (CTRL+arrows+SPACE+ENTER)
private const val SPLIT_ROWS = 5
private val MIN_HALF_WIDTH = 150.dp
private val MAX_SPLIT_KEY_HEIGHT = 60.dp

private val LocalKeyHaptic = staticCompositionLocalOf<() -> Unit> { {} }
private val LocalKeyHeight = staticCompositionLocalOf<Dp?> { null }
private val LocalKeyFont = staticCompositionLocalOf<TextUnit?> { null }

@Composable
private fun compactKeyboard(): Boolean = LocalConfiguration.current.screenHeightDp < 480

/**
 * SHIFT/CTRL are latching locks (a finger can't hold a chord on a
 * touchscreen), relying on intv_host_ecs_key's OR-in-a-bit behaviour to
 * combine them with the next ordinary key tapped. Shared by reference
 * between the stacked keyboard and both landscape-split halves, same as
 * the family's *KeyboardModifiers classes, so a lock toggled on one flank
 * is reflected on the other.
 */
private class EcsKeyboardModifiers(private val session: SessionController) {
    var shift by mutableStateOf(false)
    var ctrl by mutableStateOf(false)

    fun toggleShift() {
        shift = !shift
        session.ecsKey(Intv.ECS_KEY_SHIFT, shift)
    }

    fun toggleCtrl() {
        ctrl = !ctrl
        session.ecsKey(Intv.ECS_KEY_CTRL, ctrl)
    }

    /** Resync local lock state after a native-side clear (session.ecsKeysClear()). */
    fun clear() {
        if (shift) { shift = false; session.ecsKey(Intv.ECS_KEY_SHIFT, false) }
        if (ctrl) { ctrl = false; session.ecsKey(Intv.ECS_KEY_CTRL, false) }
    }
}

@Composable
private fun rememberEcsKeyboardModifiers(session: SessionController): EcsKeyboardModifiers {
    val mods = remember(session) { EcsKeyboardModifiers(session) }
    // Leaving composition (overlay switch) and backgrounding (onPause) both
    // clear the native matrix (see EmulatorScreen.kt's ecsKeysClear calls);
    // mirror that here so a lock key doesn't render latched after either.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mods, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) mods.clear()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mods.clear()
        }
    }
    return mods
}

/** Ordinary key: press-and-hold, mirrors CocoKeyboard.kt's KeyBox exactly
 * (focus ring + onKeyEvent OK for D-pad/TV navigation, pointerInput press/
 * awaitRelease for touch), recoloured to green/STIC-yellow. */
@Composable
private fun RowScope.KeyBox(
    label: String,
    weight: Float,
    key: Any,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val compact = compactKeyboard()
    val h = LocalKeyHeight.current ?: if (compact) 28.dp else 40.dp
    val fsize = LocalKeyFont.current ?: if (compact) 11.sp else 13.sp
    val haptic = LocalKeyHaptic.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val container = if (focused) SticYellow else IntvGreen
    val content = if (focused) IntvGreen else IntvMist
    Box(
        modifier = Modifier
            .weight(weight)
            .height(h)
            .clip(shape)
            .background(container)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev -> handleOkKey(ev, onPress, onRelease) }
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    haptic()
                    onPress()
                    try {
                        awaitRelease()
                    } finally {
                        onRelease()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = fsize, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Latching lock key (SHIFT/CTRL): tap toggles, fill reflects [active]. */
@Composable
private fun RowScope.ModKey(label: String, weight: Float, active: Boolean, onToggle: () -> Unit) {
    val compact = compactKeyboard()
    val h = LocalKeyHeight.current ?: if (compact) 28.dp else 40.dp
    val fsize = LocalKeyFont.current ?: if (compact) 11.sp else 13.sp
    val haptic = LocalKeyHaptic.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val container = if (focused || active) SticYellow else IntvGreen
    val content = if (focused || active) IntvGreen else IntvMist
    Box(
        modifier = Modifier
            .weight(weight)
            .height(h)
            .clip(shape)
            .background(container)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (isOkKey(ev) && ev.type == KeyEventType.KeyUp) {
                    haptic(); onToggle(); true
                } else {
                    false
                }
            }
            .pointerInput(label) {
                detectTapGestures(onTap = { haptic(); onToggle() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = fsize, textAlign = TextAlign.Center, maxLines = 1)
    }
}

private fun isOkKey(event: ComposeKeyEvent): Boolean =
    event.key == ComposeKey.DirectionCenter || event.key == ComposeKey.Enter ||
        event.key == ComposeKey.NumPadEnter

private fun handleOkKey(
    event: ComposeKeyEvent,
    onPress: () -> Unit,
    onRelease: () -> Unit,
): Boolean {
    if (!isOkKey(event)) return false
    return when (event.type) {
        KeyEventType.KeyDown -> { onPress(); true }
        KeyEventType.KeyUp -> { onRelease(); true }
        else -> false
    }
}

@Composable
private fun RowScope.EcsPlainKey(k: EKey, session: SessionController) {
    KeyBox(
        label = k.label,
        weight = k.weight,
        key = k.code,
        onPress = { session.ecsKey(k.code, true) },
        onRelease = { session.ecsKey(k.code, false) },
    )
}

/** Portrait / stacked keyboard, full width below the emulator surface. */
@Composable
fun EcsKeyboard(
    session: SessionController,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
) {
    val mods = rememberEcsKeyboardModifiers(session)
    val emitHaptic = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val onHaptic = { if (hapticsEnabled) emitHaptic() }

    CompositionLocalProvider(LocalKeyHaptic provides onHaptic) {
        Column(modifier = modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (k in DIGIT_ROW) EcsPlainKey(k, session)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (k in ROW1) EcsPlainKey(k, session)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (k in ROW2) EcsPlainKey(k, session)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ModKey("SHIFT", 1.5f, mods.shift) { mods.toggleShift() }
                for (k in ROW3) EcsPlainKey(k, session)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ModKey("CTRL", 1.3f, mods.ctrl) { mods.toggleCtrl() }
                KeyBox("←", 0.8f, Intv.ECS_KEY_LEFT,
                    { session.ecsKey(Intv.ECS_KEY_LEFT, true) }, { session.ecsKey(Intv.ECS_KEY_LEFT, false) })
                KeyBox("↑", 0.8f, Intv.ECS_KEY_UP,
                    { session.ecsKey(Intv.ECS_KEY_UP, true) }, { session.ecsKey(Intv.ECS_KEY_UP, false) })
                KeyBox("↓", 0.8f, Intv.ECS_KEY_DOWN,
                    { session.ecsKey(Intv.ECS_KEY_DOWN, true) }, { session.ecsKey(Intv.ECS_KEY_DOWN, false) })
                KeyBox("→", 0.8f, Intv.ECS_KEY_RIGHT,
                    { session.ecsKey(Intv.ECS_KEY_RIGHT, true) }, { session.ecsKey(Intv.ECS_KEY_RIGHT, false) })
                KeyBox("SPACE", 3f, Intv.ECS_KEY_SPACE,
                    { session.ecsKey(Intv.ECS_KEY_SPACE, true) }, { session.ecsKey(Intv.ECS_KEY_SPACE, false) })
                KeyBox("ENTER", 1.6f, Intv.ECS_KEY_ENTER,
                    { session.ecsKey(Intv.ECS_KEY_ENTER, true) }, { session.ecsKey(Intv.ECS_KEY_ENTER, false) })
            }
        }
    }
}

/**
 * Landscape: the keyboard splits into two halves flanking the emulator
 * surface in the pillar-box margins, exactly like the family's
 * LandscapeSplitKeyboard (MsxKeyboard.kt / CocoKeyboard.kt). Falls back to
 * the stacked layout above the surface when the flanks would be too narrow
 * to be usable.
 */
@Composable
fun LandscapeSplitEcsKeyboard(
    session: SessionController,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
) {
    val mods = rememberEcsKeyboardModifiers(session)
    val emitHaptic = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val onHaptic = { if (hapticsEnabled) emitHaptic() }

    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        val sideWidth = (maxWidth - maxHeight * FRAME_RATIO) / 2
        if (sideWidth < MIN_HALF_WIDTH) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    EmulatorSurface(session = session, modifier = Modifier.fillMaxSize())
                }
                EcsKeyboard(session = session, hapticsEnabled = hapticsEnabled)
            }
        } else {
            val gap = 3.dp
            val keyH = ((maxHeight - gap * (SPLIT_ROWS - 1)) / SPLIT_ROWS).coerceAtMost(MAX_SPLIT_KEY_HEIGHT)
            val font: TextUnit = 12.sp
            CompositionLocalProvider(
                LocalKeyHaptic provides onHaptic,
                LocalKeyHeight provides keyH,
                LocalKeyFont provides font,
            ) {
                Row(Modifier.fillMaxSize()) {
                    EcsKeyboardHalf(true, mods, session, Modifier.width(sideWidth))
                    EmulatorSurface(session = session, modifier = Modifier.weight(1f).fillMaxHeight())
                    EcsKeyboardHalf(false, mods, session, Modifier.width(sideWidth))
                }
            }
        }
    }
}

@Composable
private fun EcsKeyboardHalf(
    left: Boolean,
    mods: EcsKeyboardModifiers,
    session: SessionController,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (k in half(DIGIT_ROW, left)) EcsPlainKey(k, session)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (k in half(ROW1, left)) EcsPlainKey(k, session)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (k in half(ROW2, left)) EcsPlainKey(k, session)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (left) {
                ModKey("SHIFT", 1.5f, mods.shift) { mods.toggleShift() }
                for (k in ROW3.take(4)) EcsPlainKey(k, session) // Z X C V
            } else {
                for (k in ROW3.drop(4)) EcsPlainKey(k, session) // B N M , .
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (left) {
                ModKey("CTRL", 1.3f, mods.ctrl) { mods.toggleCtrl() }
                KeyBox("←", 1f, Intv.ECS_KEY_LEFT,
                    { session.ecsKey(Intv.ECS_KEY_LEFT, true) }, { session.ecsKey(Intv.ECS_KEY_LEFT, false) })
                KeyBox("↑", 1f, Intv.ECS_KEY_UP,
                    { session.ecsKey(Intv.ECS_KEY_UP, true) }, { session.ecsKey(Intv.ECS_KEY_UP, false) })
                KeyBox("↓", 1f, Intv.ECS_KEY_DOWN,
                    { session.ecsKey(Intv.ECS_KEY_DOWN, true) }, { session.ecsKey(Intv.ECS_KEY_DOWN, false) })
                KeyBox("→", 1f, Intv.ECS_KEY_RIGHT,
                    { session.ecsKey(Intv.ECS_KEY_RIGHT, true) }, { session.ecsKey(Intv.ECS_KEY_RIGHT, false) })
            } else {
                KeyBox("SPACE", 2f, Intv.ECS_KEY_SPACE,
                    { session.ecsKey(Intv.ECS_KEY_SPACE, true) }, { session.ecsKey(Intv.ECS_KEY_SPACE, false) })
                KeyBox("ENTER", 1.6f, Intv.ECS_KEY_ENTER,
                    { session.ecsKey(Intv.ECS_KEY_ENTER, true) }, { session.ecsKey(Intv.ECS_KEY_ENTER, false) })
            }
        }
    }
}

private fun <T> half(list: List<T>, left: Boolean): List<T> {
    val mid = (list.size + 1) / 2
    return if (left) list.take(mid) else list.drop(mid)
}
