package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.Fixtures
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.LrcLine
import com.kivan.chordhand.domain.music.LrcParser
import com.kivan.chordhand.domain.music.TimelineAligner
import org.junit.Test

class TimelineAlignerTest {
    @Test fun lrcParsesStampsAndSorts() {
        val lines = LrcParser.parse("[00:18.09] You used\n[01:02.5]Second\n[00:10.00][00:30.00]Twice")
        assertThat(lines.map { it.timeMs }).containsExactly(10_000L, 18_090L, 30_000L, 62_500L).inOrder()
        assertThat(lines[1].text).isEqualTo("You used")
    }

    @Test fun chordTakesTimeOfWordUnderIt() {
        val sheet = ChordSheetParser.parse("[ch]C[/ch]         [ch]G[/ch]\nHello my dear friend\n[ch]Am[/ch]\nGoodbye now")
        val lrc = listOf(LrcLine(10_000, "Hello my dear friend"), LrcLine(14_000, "Goodbye now"), LrcLine(18_000, ""))
        val t = TimelineAligner.align(sheet, lrc, 30_000)
        assertThat(t.chords.map { it.chord.symbol }).containsExactly("C", "G", "Am").inOrder()
        assertThat(t.chords[0].startMs).isEqualTo(10_000)
        // "friend" starts at column 14: 14 chars * 75 ms after the line start; G sits at column 10.
        assertThat(t.chords[1].startMs).isIn(10_600L..11_100L)
        assertThat(t.chords[2].startMs).isEqualTo(14_000)
        assertThat(t.chords.all { it.anchored }).isTrue()
    }

    @Test fun oneLrcLineCanCoverTwoSheetLines() {
        val sheet = ChordSheetParser.parse("[ch]F#m[/ch]\nOh, the boy's a slag\n[ch]B[/ch]\nThe best you ever had")
        val lrc = listOf(LrcLine(33_480, "Oh, the boy's a slag, the best you ever had"), LrcLine(38_000, "Next"))
        val t = TimelineAligner.align(sheet, lrc, 60_000)
        assertThat(t.chords[0].startMs).isEqualTo(33_480)
        assertThat(t.chords[1].startMs).isGreaterThan(34_500)
        assertThat(t.chords[1].anchored).isTrue()
    }

    @Test fun unmatchedChordsAreSpreadBetweenNeighbours() {
        val filled = TimelineAligner.fill(listOf(null, null, 10_000L, null, null, 16_000L, 18_000L, null), 30_000)
        assertThat(filled).containsExactly(6_000L, 8_000L, 10_000L, 12_000L, 14_000L, 16_000L, 18_000L, 20_000L).inOrder()
    }

    @Test fun introBeforeFirstAnchorIsCompressedToFitAfterZero() {
        val filled = TimelineAligner.fill(listOf(null, null, null, 3_000L, 5_000L), 10_000)
        assertThat(filled.first()).isAtLeast(0L)
        assertThat(filled).isInOrder()
    }

    @Test fun badSyncRanksBelowPlausibleOne() {
        val ranked = LrclibSource.rank(LrclibSource.parseSearch(Fixtures.read("lrclib_search_fluorescent.json")), 178.0)
        assertThat(ranked.first().id).isEqualTo(31168608L)
        // 20 entries share two distinct syncs.
        assertThat(ranked.size).isAtMost(3)
    }

    @Test fun fluorescentAdolescentAlignsMostChords() {
        val tab = UltimateGuitarSource.parseTab(Fixtures.read("ug_tab_fluorescent.html"), "u")
        val lrc = LrclibSource.rank(LrclibSource.parseSearch(Fixtures.read("lrclib_search_fluorescent.json")), 184.0).first()
        val t = TimelineAligner.align(ChordSheetParser.parse(tab.content), LrcParser.parse(lrc.syncedLyrics!!), 184_000)
        assertThat(t.anchoredShare).isAtLeast(0.8)
        assertThat(t.chords.map { it.startMs }).isInOrder()
        assertThat(t.lineStartMs).isInOrder()
        assertThat(t.chordAt(0)).isEqualTo(-1)
        assertThat(t.chordAt(t.chords[5].startMs)).isEqualTo(5)
    }
}
