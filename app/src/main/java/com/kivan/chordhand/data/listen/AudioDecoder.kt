package com.kivan.chordhand.data.listen

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Decodes a recording to what speech recognition takes: 16 kHz mono floats. */
object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in ${file.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var mono = FloatArray(1 shl 20)
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            var outputDone = false
            while (!outputDone) {
                var progressed = false
                // Feed every free input buffer, then drain every ready output; wait only when idle.
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, if (progressed) 0 else TIMEOUT_US)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = codec.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (o < 0) continue
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                    if (n + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, n + frames))
                    // Bulk copies: a buffer read per sample makes this ~80× slower.
                    val count = frames * channels
                    if (floatPcm) {
                        if (floats.size < count) floats = FloatArray(count)
                        buf.asFloatBuffer().get(floats, 0, count)
                        for (k in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += floats[k * channels + c]
                            mono[n++] = sum / channels
                        }
                    } else {
                        if (shorts.size < count) shorts = ShortArray(count)
                        buf.asShortBuffer().get(shorts, 0, count)
                        for (k in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += shorts[k * channels + c]
                            mono[n++] = sum / channels / 32768f
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return resample(mono, n, rate, SAMPLE_RATE)
    }

    /**
     * Windowed-sinc resampling, low-passed below the new Nyquist frequency. The kernel is
     * worked out once per phase: 44.1 → 16 kHz repeats every 160 output samples.
     */
    internal fun resample(input: FloatArray, length: Int, from: Int, to: Int): FloatArray {
        if (from == to) return input.copyOf(length)
        val g = gcd(from, to)
        val step = from / g
        val phases = to / g
        val cutoff = minOf(1.0, to.toDouble() / from) * 0.95
        val kernels = Array(phases) { p ->
            val frac = (p.toLong() * step % phases).toDouble() / phases
            val raw = DoubleArray(2 * HALF_TAPS) { t ->
                val x = t - HALF_TAPS + 1 - frac
                val sinc = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                sinc * (0.5 + 0.5 * cos(PI * x / HALF_TAPS))
            }
            val sum = raw.sum()
            FloatArray(raw.size) { (raw[it] / sum).toFloat() }
        }
        val out = FloatArray((length.toLong() * phases / step).toInt())
        for (i in out.indices) {
            val first = (i.toLong() * step / phases).toInt() - HALF_TAPS + 1
            val kernel = kernels[i % phases]
            var acc = 0f
            if (first >= 0 && first + kernel.size <= length) {
                for (t in kernel.indices) acc += input[first + t] * kernel[t]
            } else {
                for (t in kernel.indices) {
                    val k = first + t
                    if (k in 0 until length) acc += input[k] * kernel[t]
                }
            }
            out[i] = acc
        }
        return out
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private const val HALF_TAPS = 16
    private const val TIMEOUT_US = 10_000L
}
