package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.Fixtures
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.domain.music.ChordSheetParser
import org.junit.Test

class ChordSheetParserTest {
    @Test fun chordLineAttachesToLyricBelowAtItsColumn() {
        val sheet = ChordSheetParser.parse(
            """
            [Verse 1]
            [tab]                           [ch]E[/ch]
            You used to get it in your fishnets[/tab]
            """.trimIndent()
        )
        val line = sheet.lines.single()
        assertThat(line.section).isEqualTo("Verse 1")
        assertThat(line.lyric).isEqualTo("You used to get it in your fishnets")
        assertThat(line.chords.single().column).isEqualTo(27)
        assertThat(line.chords.single().chord.symbol).isEqualTo("E")
    }

    @Test fun chordOnlyLineWithRepeatMarkBecomesItsOwnLine() {
        val sheet = ChordSheetParser.parse("[Intro]\n[ch]Em7[/ch]   [ch]G[/ch]   x4\n\n[Verse]\nHello")
        assertThat(sheet.lines).hasSize(2)
        assertThat(sheet.lines[0].lyric).isNull()
        assertThat(sheet.lines[0].chords.map { it.chord.symbol }).containsExactly("Em7", "G").inOrder()
        assertThat(sheet.lines[1].chords).isEmpty()
    }

    @Test fun inlineChordsTakeNoColumns() {
        val line = ChordSheetParser.parse("[ch]Am[/ch]Hello [ch]F[/ch]there").lines.single()
        assertThat(line.lyric).isEqualTo("Hello there")
        assertThat(line.chords.map { it.column }).containsExactly(0, 6).inOrder()
    }

    @Test fun skipsTablatureAndFingeringCharts() {
        val sheet = ChordSheetParser.parse(
            "[tab]Em7     0-2-2-0-3-3\nG       3-x-0-0-3-3[/tab]\n" +
                "e|-----------------|\nE|-0-------0-2-4---|\n[Verse]\nLyric"
        )
        assertThat(sheet.lines.map { it.lyric }).containsExactly("Lyric")
    }

    @Test fun fluorescentAdolescentFixture() {
        val tab = UltimateGuitarSource.parseTab(Fixtures.read("ug_tab_fluorescent.html"), "u")
        val sheet = ChordSheetParser.parse(tab.content, tab.soundingTranspose)
        assertThat(sheet.chordCount).isAtLeast(20)
        assertThat(sheet.lines.first { it.lyric != null }.lyric).isEqualTo("You used to get it in your fishnets")
        assertThat(sheet.lines.flatMap { it.chords }.map { it.chord.symbol }.toSet())
            .containsAtLeast("E", "C#m", "F#m", "B")
    }

    @Test fun wonderwallCapoSoundsTwoSemitonesUp() {
        val tab = UltimateGuitarSource.parseTab(Fixtures.read("ug_tab_wonderwall.html"), "u")
        assertThat(tab.capo).isEqualTo(2)
        val sheet = ChordSheetParser.parse(tab.content, tab.soundingTranspose)
        val symbols = sheet.lines.flatMap { it.chords }.map { it.chord.symbol }.toSet()
        assertThat(symbols).containsAtLeast("F#m7", "A", "Esus4", "B7sus4", "Dadd9")
        assertThat(sheet.lines.none { it.lyric?.contains("0-2-2") == true }).isTrue()
    }
}
