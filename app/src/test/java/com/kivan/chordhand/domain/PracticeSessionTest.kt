package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.LrcLine
import com.kivan.chordhand.domain.music.MatchResult
import com.kivan.chordhand.domain.music.TimelineAligner
import com.kivan.chordhand.domain.practice.PracticeSession
import org.junit.Test

class PracticeSessionTest {
    // Am at 10 s, F at 12 s, Am at 14 s, F at 16 s.
    private val timeline = TimelineAligner.align(
        ChordSheetParser.parse("[ch]Am[/ch]\nOne two\n[ch]F[/ch]\nThree four\n[ch]Am[/ch]\nFive six\n[ch]F[/ch]\nSeven eight"),
        listOf(LrcLine(10_000, "One two"), LrcLine(12_000, "Three four"), LrcLine(14_000, "Five six"), LrcLine(16_000, "Seven eight")),
        20_000,
    )
    private val chords = timeline.chords.map { it.chord }
    private val am = setOf(45, 60, 64, 69)
    private val f = setOf(41, 60, 65, 69)

    @Test fun hitRecordsTimingErrorOnce() {
        val s = PracticeSession(timeline, chords, chords, easy = true)
        assertThat(s.update(am, 10_100)!!.result).isEqualTo(MatchResult.HIT)
        s.update(am, 10_500)
        assertThat(s.isHit(0)).isTrue()
        assertThat(s.summary().meanTimingErrorMs).isEqualTo(100)
    }

    @Test fun slightlyEarlyCountsForTheNextChord() {
        val s = PracticeSession(timeline, chords, chords, easy = true)
        s.update(f, 11_850)
        assertThat(s.isHit(1)).isTrue()
        assertThat(s.summary().earlyShare).isEqualTo(1.0)
    }

    @Test fun summaryNamesTheMissedChange() {
        val s = PracticeSession(timeline, chords, chords, easy = true)
        s.update(am, 10_000)
        s.update(emptySet(), 12_500) // missed F
        s.update(am, 14_000)
        s.update(emptySet(), 16_500) // missed F again
        val summary = s.summary()
        assertThat(summary.chordsReached).isEqualTo(4)
        assertThat(summary.chordsHit).isEqualTo(2)
        assertThat(summary.worstChanges.single().let { "${it.from}→${it.to} ${it.misses}/${it.attempts}" }).isEqualTo("Am→F 2/2")
    }

    @Test fun beforeTheFirstChordThereIsNoTarget() {
        val s = PracticeSession(timeline, chords, chords, easy = true)
        assertThat(s.update(am, 1_000)).isNull()
        assertThat(s.summary().chordsReached).isEqualTo(0)
    }
}
