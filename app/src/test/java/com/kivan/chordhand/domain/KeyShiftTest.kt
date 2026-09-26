package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.domain.music.ChordParser
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.EasiestKey
import com.kivan.chordhand.domain.music.Key
import com.kivan.chordhand.domain.music.LrcLine
import com.kivan.chordhand.domain.music.TimelineAligner
import com.kivan.chordhand.domain.music.transposed
import org.junit.Test

class KeyShiftTest {
    private fun chords(vararg symbols: String) = symbols.map { requireNotNull(ChordParser.parse(it)) }

    @Test fun transposedMovesEveryChordAndKeepsTheTimes() {
        val sheet = ChordSheetParser.parse("[ch]Eb[/ch]      [ch]Bb[/ch]\nHello my dear friend\n[ch]Cm[/ch]\nGoodbye now")
        val t = TimelineAligner.align(sheet, listOf(LrcLine(10_000, "Hello my dear friend"), LrcLine(14_000, "Goodbye now")), 30_000)
        val c = t.transposed(-3, preferFlats = false)
        assertThat(c.chords.map { it.chord.symbol }).containsExactly("C", "G", "Am").inOrder()
        assertThat(c.chords.map { it.startMs }).isEqualTo(t.chords.map { it.startMs })
        assertThat(c.words).isEqualTo(t.words)
        assertThat(t.transposed(0, preferFlats = true)).isSameInstanceAs(t)
    }

    @Test fun spellingFollowsTheNewKey() {
        val g = Key(7, minor = false)
        assertThat(g.transpose(1).name).isEqualTo("Ab")
        assertThat(g.transpose(-1).name).isEqualTo("F#")
        assertThat(Key(9, minor = true).transpose(-2).name).isEqualTo("Gm")
        assertThat(Key(9, minor = true).transpose(-3).name).isEqualTo("F#m")
    }

    @Test fun keyOfTakesTheTonalityThatFitsTheSoundingChords() {
        // Capo 2: "G" shapes sound as A.
        val sounding = chords("A", "D", "E", "A")
        assertThat(Key.of("G", transpose = 2, chords = sounding)).isEqualTo(Key(9, minor = false))
        assertThat(Key.of("A", transpose = 2, chords = sounding)).isEqualTo(Key(9, minor = false))
        assertThat(Key.of(null, transpose = 0, chords = chords("Em", "C", "G"))).isEqualTo(Key(4, minor = true))
        assertThat(Key.of("nonsense", transpose = 0, chords = emptyList())).isNull()
    }

    @Test fun easiestKeyLeavesAWhiteKeySongAlone() {
        assertThat(EasiestKey.suggest(chords("C", "Am", "F", "G"))).isEqualTo(0)
    }

    @Test fun easiestKeyPrefersAllWhiteKeysOverASmallerShift() {
        // Fluorescent Adolescent: -4 gives C–Am–Dm–G–F–Em, no black key; +1 would need B♭.
        assertThat(EasiestKey.suggest(chords("E", "C#m", "F#m", "B", "A", "G#m"))).isEqualTo(-4)
    }

    @Test fun easiestKeyMovesBMajorToFewerBlackKeys() {
        val b = chords("B", "E", "F#", "G#m")
        val shift = EasiestKey.suggest(b)
        assertThat(shift).isAnyOf(1, -4) // C or G
        assertThat(EasiestKey.blackShare(b.map { it.transpose(shift) })).isLessThan(EasiestKey.blackShare(b))
    }
}
