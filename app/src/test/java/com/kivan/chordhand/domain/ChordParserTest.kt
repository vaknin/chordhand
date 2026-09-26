package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.domain.music.ChordParser
import org.junit.Test

class ChordParserTest {
    private fun pcs(symbol: String) = ChordParser.parse(symbol)!!.pitchClasses.map { Chord.noteName(it) }.toSet()

    @Test fun majorAndMinorTriads() {
        assertThat(pcs("E")).containsExactly("E", "G#", "B")
        assertThat(pcs("C#m")).containsExactly("C#", "E", "G#")
        assertThat(pcs("Bbm")).containsExactly("A#", "C#", "F")
    }

    @Test fun seventhsAndExtensions() {
        assertThat(ChordParser.parse("G7")!!.intervals).containsExactly(0, 4, 7, 10)
        assertThat(ChordParser.parse("Cmaj7")!!.intervals).containsExactly(0, 4, 7, 11)
        assertThat(ChordParser.parse("Em7")!!.intervals).containsExactly(0, 3, 7, 10)
        assertThat(ChordParser.parse("Cadd9")!!.intervals).containsExactly(0, 4, 7, 2)
        assertThat(ChordParser.parse("Bm7b5")!!.intervals).containsExactly(0, 3, 6, 10)
        assertThat(ChordParser.parse("Bdim7")!!.intervals).containsExactly(0, 3, 6, 9)
        assertThat(ChordParser.parse("Am(maj7)")!!.intervals).containsExactly(0, 3, 7, 11)
        assertThat(ChordParser.parse("D9")!!.intervals).containsExactly(0, 4, 7, 10, 2)
    }

    @Test fun susAndPowerChords() {
        assertThat(ChordParser.parse("Dsus4")!!.intervals).containsExactly(0, 5, 7)
        assertThat(ChordParser.parse("Asus2")!!.intervals).containsExactly(0, 2, 7)
        assertThat(ChordParser.parse("A7sus4")!!.intervals).containsExactly(0, 10, 5, 7)
        assertThat(ChordParser.parse("E5")!!.intervals).containsExactly(0, 7)
    }

    @Test fun slashChordKeepsBass() {
        val c = ChordParser.parse("G/F#")!!
        assertThat(c.root).isEqualTo(7)
        assertThat(c.bass).isEqualTo(6)
        assertThat(c.symbol).isEqualTo("G/F#")
    }

    @Test fun rejectsNonChords() {
        listOf("Chorus", "x2", "N.C.", "Hello", "Am7xyz", "").forEach {
            assertThat(ChordParser.parse(it)).isNull()
        }
    }

    @Test fun simplifiedIsATriad() {
        assertThat(ChordParser.parse("Dsus4")!!.simplified().symbol).isEqualTo("D")
        assertThat(ChordParser.parse("Em7")!!.simplified().symbol).isEqualTo("Em")
        assertThat(ChordParser.parse("Cmaj7")!!.simplified().intervals).containsExactly(0, 4, 7)
        assertThat(ChordParser.parse("Bm7b5")!!.simplified().symbol).isEqualTo("Bdim")
        assertThat(ChordParser.parse("G/F#")!!.simplified().symbol).isEqualTo("G/F#")
    }

    @Test fun transposeForCapo() {
        assertThat(ChordParser.parse("Em7")!!.transpose(2).symbol).isEqualTo("F#m7")
        assertThat(ChordParser.parse("G/F#")!!.transpose(2).symbol).isEqualTo("A/G#")
        assertThat(ChordParser.parse("Bb")!!.transpose(-1).symbol).isEqualTo("A")
    }
}
