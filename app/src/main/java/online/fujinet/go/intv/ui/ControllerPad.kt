package online.fujinet.go.intv.ui

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.input.DiscGeometry
import online.fujinet.go.intv.input.Intv
import online.fujinet.go.intv.ui.theme.IntvGreen
import online.fujinet.go.intv.ui.theme.IntvOutline
import online.fujinet.go.intv.ui.theme.SticYellow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The combined Intellivision hand controller -- disc + 12-key keypad + 3
 * action buttons -- exactly as it appears on the real Master Component
 * controller, and the app's default view (see EmulatorScreen.kt's initial
 * Overlay value). [side] selects which logical controller this widget
 * drives (Intv.PAD_LEFT/RIGHT, or the ECS's second pair when [ecsAvailable]);
 * [onSideChange] is invoked by the side selector pill.
 *
 * Portrait convenience composite; the landscape layout (controls flanking
 * the surface in the pillarbox margins) composes [IntvDisc], [IntvKeypad],
 * [IntvActions] and [IntvSideSelector] directly instead -- see
 * EmulatorScreen.kt.
 */
@Composable
fun IntvController(
    session: SessionController,
    side: Int,
    hapticsEnabled: Boolean,
    ecsAvailable: Boolean,
    onSideChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IntvDisc(session, side, hapticsEnabled)
            IntvKeypad(session, side)
        }
        Spacer(Modifier.height(8.dp))
        IntvActions(session, side, hapticsEnabled)
        Spacer(Modifier.height(6.dp))
        IntvSideSelector(side, ecsAvailable, onSideChange)
    }
}

/**
 * The 16-way disc. Emits [SessionController.padDisc] only on direction
 * change (and -1 on release), snapped to the 8 compass positions via
 * [DiscGeometry.discDirection] -- see that object for why the 8 odd
 * half-steps are never emitted. A single owning pointer, so a second finger
 * landing on the keypad can never steal the disc mid-drag.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IntvDisc(
    session: SessionController,
    side: Int,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var pointerId by remember { mutableStateOf<Int?>(null) }
    var direction by remember { mutableIntStateOf(Intv.DISC_NONE) }
    val emit = rememberFujiHaptic(FujiHapticPattern.JoystickTick)

    fun apply(px: Float, py: Float) {
        val cx = padSize.width / 2f
        val cy = padSize.height / 2f
        val radius = min(padSize.width, padSize.height) / 2f
        val dir = DiscGeometry.discDirection(px - cx, py - cy, radius)
        if (dir != direction) {
            direction = dir
            if (hapticsEnabled && dir != Intv.DISC_NONE) emit()
            session.padDisc(side, dir)
        }
    }

    fun reset() {
        pointerId = null
        if (direction != Intv.DISC_NONE) {
            direction = Intv.DISC_NONE
            session.padDisc(side, Intv.DISC_NONE)
        }
    }

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(IntvGreen)
            .border(1.5.dp, IntvOutline, CircleShape)
            .onSizeChanged { padSize = it }
            .pointerInteropFilter { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (pointerId == null) {
                            pointerId = e.getPointerId(e.actionIndex)
                            apply(e.getX(e.actionIndex), e.getY(e.actionIndex))
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pid = pointerId ?: return@pointerInteropFilter false
                        val idx = e.findPointerIndex(pid)
                        if (idx < 0) {
                            reset()
                            return@pointerInteropFilter true
                        }
                        apply(e.getX(idx), e.getY(idx))
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (e.getPointerId(e.actionIndex) == pointerId) reset()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        reset()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(diameter)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f
            val hubR = r * DiscGeometry.DEADZONE_FRAC

            fun pt(angleDeg: Double, radius: Float): Offset {
                val rad = angleDeg * PI / 180.0
                // +y is screen-down; angleDeg is CCW-from-East in the
                // visual (up-is-up) sense, matching DiscGeometry -- negate
                // sin to convert back to screen coordinates.
                return Offset(
                    (cx + radius * cos(rad)).toFloat(),
                    (cy - radius * sin(rad)).toFloat(),
                )
            }

            // Active wedge: a 45deg fan centered on the current direction,
            // drawn as a filled polygon (a handful of line segments rather
            // than an arc, to sidestep Canvas's clockwise-from-3-o'clock
            // angle convention entirely).
            if (direction != Intv.DISC_NONE) {
                val center = direction * 22.5
                val path = Path().apply {
                    moveTo(cx, cy)
                    val steps = 6
                    for (i in 0..steps) {
                        val a = center - 22.5 + (45.0 * i / steps)
                        val p = pt(a, r)
                        lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, color = SticYellow.copy(alpha = 0.85f))
            }

            // 16 spokes at the half-step boundaries (matching the desktop
            // keypad window's outline), plus the 8 compass labels' tick
            // marks slightly heavier.
            for (i in 0 until 16) {
                val a = i * 22.5 - 11.25 // boundary between half-steps
                val inner = pt(a, hubR)
                val outer = pt(a, r)
                drawLine(IntvOutline, inner, outer, strokeWidth = 1.5f)
            }
            drawCircle(IntvOutline, radius = hubR, center = Offset(cx, cy), style = Stroke(width = 2f))
            drawCircle(IntvOutline.copy(alpha = 0.5f), radius = r - 1f, center = Offset(cx, cy), style = Stroke(width = 2f))
        }
    }
}

/** 3x4 keypad grid: `1 2 3 / 4 5 6 / 7 8 9 / CLEAR 0 ENTER`. */
@Composable
fun IntvKeypad(
    session: SessionController,
    side: Int,
    modifier: Modifier = Modifier,
    keySize: Dp = 44.dp,
) {
    val rows = listOf(
        listOf(Intv.KEY_1 to "1", Intv.KEY_2 to "2", Intv.KEY_3 to "3"),
        listOf(Intv.KEY_4 to "4", Intv.KEY_5 to "5", Intv.KEY_6 to "6"),
        listOf(Intv.KEY_7 to "7", Intv.KEY_8 to "8", Intv.KEY_9 to "9"),
        listOf(Intv.KEY_CLEAR to "C", Intv.KEY_0 to "0", Intv.KEY_ENTER to "E"),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in row) {
                    KeypadKey(label, keySize) { pressed -> session.padKey(side, key, pressed) }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, size: Dp, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    val currentHold = rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(if (held) IntvOutline else IntvGreen)
            .border(1.dp, IntvOutline, RoundedCornerShape(8.dp))
            // Keyed on Unit so an unrelated recomposition never restarts the
            // gesture (which would release the held key mid-press).
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    currentHold.value(true)
                    try {
                        awaitRelease()
                    } finally {
                        held = false
                        currentHold.value(false)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (held) SticYellow else Color(0xFFE8F2E4), style = MaterialTheme.typography.titleMedium)
    }
}

/** ACTION_TOP as a wide pill above ACTION_LOWER_LEFT/RIGHT circles -- both
 * physical top side buttons are electrically one signal on real hardware. */
@Composable
fun IntvActions(
    session: SessionController,
    side: Int,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val emit = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val onHaptic = { if (hapticsEnabled) emit() }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ActionButton(
            "TOP", width = 128.dp, height = 32.dp, shape = RoundedCornerShape(50),
            onHaptic = onHaptic,
        ) { pressed -> session.padKey(side, Intv.ACTION_TOP, pressed) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ActionButton("L", width = 48.dp, height = 48.dp, shape = CircleShape, onHaptic = onHaptic) { pressed ->
                session.padKey(side, Intv.ACTION_LOWER_LEFT, pressed)
            }
            ActionButton("R", width = 48.dp, height = 48.dp, shape = CircleShape, onHaptic = onHaptic) { pressed ->
                session.padKey(side, Intv.ACTION_LOWER_RIGHT, pressed)
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    width: Dp,
    height: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onHaptic: () -> Unit,
    onHold: (Boolean) -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    val currentHaptic = rememberUpdatedState(onHaptic)
    val currentHold = rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(if (held) IntvOutline else IntvGreen)
            .border(1.dp, IntvOutline, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    currentHaptic.value()
                    currentHold.value(true)
                    try {
                        awaitRelease()
                    } finally {
                        held = false
                        currentHold.value(false)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (held) SticYellow else Color(0xFFE8F2E4), style = MaterialTheme.typography.labelSmall)
    }
}

/** A compact segmented pill choosing which logical controller the on-screen
 * widget drives: P1/P2, extended to P3/P4 (the ECS's second pair) when ECS
 * is enabled. Styled after the family's JoystickModeToggle. */
@Composable
fun IntvSideSelector(
    side: Int,
    ecsAvailable: Boolean,
    onSideChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = if (ecsAvailable) {
        listOf(Intv.PAD_LEFT to "P1", Intv.PAD_RIGHT to "P2", Intv.PAD_ECS_LEFT to "P3", Intv.PAD_ECS_RIGHT to "P4")
    } else {
        listOf(Intv.PAD_LEFT to "P1", Intv.PAD_RIGHT to "P2")
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, IntvOutline.copy(alpha = 0.6f), RoundedCornerShape(50)),
    ) {
        for ((value, label) in options) {
            val selected = value == side
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) IntvGreen else Color(0xFFE8F2E4),
                modifier = Modifier
                    .pointerInput(value) { detectTapGestures(onTap = { onSideChange(value) }) }
                    .background(if (selected) SticYellow else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
