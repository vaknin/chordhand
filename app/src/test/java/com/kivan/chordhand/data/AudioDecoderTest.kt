package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.listen.AudioDecoder
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioDecoderTest {
    @Test fun resamplingKeepsATonesAmplitudeAndLength() {
        val from = 44_100
        val input = FloatArray(from) { (0.5 * sin(2 * PI * 440 * it / from)).toFloat() }
        val out = AudioDecoder.resample(input, input.size, from, 16_000)
        assertThat(out.size).isEqualTo(16_000)
        val peak = out.drop(100).dropLast(100).maxOf { abs(it) }
        assertThat(peak.toDouble()).isWithin(0.02).of(0.5)
    }

    @Test fun resamplingRemovesWhatTheNewRateCannotHold() {
        val from = 44_100
        // 12 kHz is above the 8 kHz Nyquist frequency of 16 kHz audio.
        val input = FloatArray(from) { (0.5 * sin(2 * PI * 12_000 * it / from)).toFloat() }
        val out = AudioDecoder.resample(input, input.size, from, 16_000)
        assertThat(out.drop(100).dropLast(100).maxOf { abs(it) }.toDouble()).isLessThan(0.05)
    }
}
