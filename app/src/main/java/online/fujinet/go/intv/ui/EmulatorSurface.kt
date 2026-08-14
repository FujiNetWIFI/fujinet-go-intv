package online.fujinet.go.intv.ui

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import online.fujinet.go.intv.SessionController
import online.fujinet.go.intv.settings.VideoStandard
import online.fujinet.go.intv.ui.theme.IntvGreen

/**
 * The Intellivision's STIC delivers a fixed 160x200 buffer (INTVSESSION_FB_
 * WIDTH/HEIGHT), but that is a pixel *count*, not the picture's aspect --
 * STIC pixels are not square. Presenting the buffer at 4:3 (the real
 * console's output on a TV) is what makes the picture look right; the
 * buffer geometry set in session_runtime.cpp's PresentTo is the source
 * pixel grid, this ratio is the *display* aspect. Internal so a landscape
 * layout can size its flanking controls to the pillar-box margins.
 */
internal const val FRAME_RATIO = 4f / 3f

/**
 * Hosts the Intellivision video output. The native layer renders jzIntv's
 * XRGB8888 frames into a [Surface] (session_runtime.cpp's presenter thread);
 * the surface is obtained from a [TextureView]'s [SurfaceTexture].
 *
 * A TextureView, not a SurfaceView, matching the rest of the family: a
 * SurfaceView lives in its own compositor layer outside the view hierarchy,
 * which has been observed to occlude toolbar chrome on some API levels. A
 * TextureView draws inline as an ordinary view and can never occlude
 * siblings above or below it.
 */
@Composable
fun EmulatorSurface(
    session: SessionController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val pillarboxed = maxWidth / maxHeight > FRAME_RATIO
        val surfaceModifier = if (pillarboxed) {
            Modifier.fillMaxHeight().aspectRatio(FRAME_RATIO)
        } else {
            Modifier.fillMaxWidth().aspectRatio(FRAME_RATIO)
        }

        if (pillarboxed) {
            val accent = IntvGreen.copy(alpha = 0.3f)
            val barWidth = (maxWidth - maxHeight * FRAME_RATIO) / 2
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(barWidth)
                    .background(Brush.horizontalGradient(listOf(Color.Black, accent))),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(barWidth)
                    .background(Brush.horizontalGradient(listOf(accent, Color.Black))),
            )
        }

        AndroidView(
            modifier = surfaceModifier,
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val s = Surface(texture)
                            surface = s
                            s.requestFrameRate(session.targetFps())
                            session.attachSurface(s)
                            session.startIfNeeded()
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            surface?.requestFrameRate(session.targetFps())
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            session.detachSurface()
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

/**
 * Matches the persisted video standard: 60fps NTSC or 50fps PAL, so
 * NTSC/PAL and the compositor's presentation cadence never disagree (a PAL
 * machine presented at 60 would judder in a way that reads as "slow
 * emulator" -- see session_runtime.cpp's ADPF target, which moves with the
 * same setting).
 */
private fun SessionController.targetFps(): Float =
    if (settings.video == VideoStandard.PAL) 50f else 60f

// Tell the compositor this surface produces a fixed frame rate. On a 120Hz /
// variable-refresh phone this makes the panel present at a matching multiple
// instead of judder-mapping content onto an unrelated refresh rate.
private fun Surface.requestFrameRate(fps: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isValid) {
        setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}
