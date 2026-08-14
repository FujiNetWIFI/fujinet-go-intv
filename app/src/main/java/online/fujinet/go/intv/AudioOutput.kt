package online.fujinet.go.intv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import online.fujinet.go.intv.core.EmulatorNative
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Streams jzIntv's audio to an AudioTrack as a "game" audio source.
 *
 * jzIntv's PSG mixer produces mono (there is no stereo signal to fake --
 * see core/jzintv/intv_audio.h); intv_host_android.cpp's FillAudioStereo
 * duplicates it to both channels at the JNI edge so this class stays
 * byte-identical to the rest of the family bar SAMPLE_RATE. A high-priority
 * feeder thread pulls *full* blocks via the blocking
 * [EmulatorNative.nativeFillAudio] and writes them with `WRITE_BLOCKING`, so
 * AudioTrack is always handed a complete, real-time-paced buffer -- no
 * partial/choppy writes, and a producer hiccup degrades to a brief silence
 * pad (in the native fill) rather than a stutter.
 */
class AudioOutput {
    private companion object {
        // Matches INTVSESSION_AUDIO_RATE / INTV_AUDIO_RATE (config.h's
        // DEFAULT_AUDIO_HZ) -- see core/jzintv/intv_audio.h.
        const val SAMPLE_RATE = 48000
        const val BYTES_PER_FRAME = 4 // stereo * 16-bit
        const val TAG = "FujiIntvAudio"
    }

    @Volatile private var running = false
    private var feeder: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else (SAMPLE_RATE / 20) * BYTES_PER_FRAME }

        // ~50ms track buffer: enough to ride out frame-pacing jitter without
        // adding noticeable latency.
        val trackBufferBytes = max(minBuf, (SAMPLE_RATE / 20) * BYTES_PER_FRAME)
        // Transfer one emulator producer burst's worth per pull (~11ms,
        // matching intv_host.c's -B512), bounded by half the track buffer.
        val transferFrames = max(
            SAMPLE_RATE / 100,
            min(trackBufferBytes / BYTES_PER_FRAME / 2, SAMPLE_RATE / 60),
        )
        val transferSamples = transferFrames * 2

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBufferBytes)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        track = newTrack

        Log.i(TAG, "start trackBuffer=$trackBufferBytes transferSamples=$transferSamples")
        EmulatorNative.nativeAudioSetActive(true)
        running = true
        newTrack.play()

        feeder = thread(name = "intv-audio") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(transferSamples)
            while (running) {
                try {
                    EmulatorNative.nativeFillAudio(buffer) // blocks for a full block
                    if (!running) break
                    newTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    Log.e(TAG, "audio feeder error", t)
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        EmulatorNative.nativeAudioSetActive(false) // unblock a waiting fill
        feeder?.join(500)
        feeder = null
        track?.run {
            runCatching { pause(); flush(); stop() }
            release()
        }
        track = null
    }
}
