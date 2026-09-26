package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.domain.music.ChordMatcher
import com.kivan.chordhand.domain.music.ChordParser
import com.kivan.chordhand.domain.music.MatchResult
import com.kivan.chordhand.domain.music.VoicingPlanner
import org.junit.Test

class VoicingAndMatcherTest {
    private fun chord(s: String) = ChordParser.parse(s)!!

    @Test fun leftHandPlaysBassInSecondOctave() {
        val v = VoicingPlanner.plan(listOf(chord("E"), chord("G/F#")))
        assertThat(v[0].leftHand).containsExactly(40) // E2
        assertThat(v[1].leftHand).containsExactly(42) // F#2
    }

    @Test fun rightHandMovesLittleBetweenChords() {
        val v = VoicingPlanner.plan(listOf("C", "Am", "F", "G").map(::chord))
        v.zipWithNext().forEach { (a, b) ->
            val moved = a.rightHand.zip(b.rightHand).sumOf { (x, y) -> kotlin.math.abs(x - y) }
            assertThat(moved).isAtMost(6)
        }
        v.forEach { assertThat(it.rightHand.min()).isAtLeast(53) }
    }

    @Test fun rightHandDropsTheFifthOfBigChords() {
        val pcs = VoicingPlanner.rightHandPitchClasses(chord("D9"))
        assertThat(pcs).hasSize(4)
        assertThat(pcs).doesNotContain(9) // A, the fifth of D
    }

    @Test fun anyInversionWithBassBelowIsAHit() {
        val am = chord("Am")
        // A2 in the left hand, C4 E4 A4 in the right.
        assertThat(ChordMatcher.match(setOf(45, 60, 64, 69), am, am, easy = true).result).isEqualTo(MatchResult.HIT)
        // Right hand only, lowest note still A.
        assertThat(ChordMatcher.match(setOf(57, 60, 64), am, am, easy = true).result).isEqualTo(MatchResult.HIT)
    }

    @Test fun wrongBassOrMissingToneIsPartial() {
        val am = chord("Am")
        assertThat(ChordMatcher.match(setOf(48, 57, 64), am, am, easy = true).result).isEqualTo(MatchResult.PARTIAL)
        assertThat(ChordMatcher.match(setOf(45, 60), am, am, easy = true).result).isEqualTo(MatchResult.PARTIAL)
    }

    @Test fun foreignNoteIsNotAHit() {
        val am = chord("Am")
        val detail = ChordMatcher.match(setOf(45, 60, 64, 69, 66), am, am, easy = true)
        assertThat(detail.result).isEqualTo(MatchResult.PARTIAL)
        assertThat(detail.wrong).containsExactly(66)
    }

    @Test fun easyAcceptsTheSeventhFullRequiresIt() {
        val g7 = chord("G7")
        val easy = g7.simplified()
        val triad = setOf(43, 59, 62, 67)
        val withSeventh = triad + 65
        assertThat(ChordMatcher.match(withSeventh, easy, g7, easy = true).result).isEqualTo(MatchResult.HIT)
        assertThat(ChordMatcher.match(triad, g7, g7, easy = false).result).isEqualTo(MatchResult.PARTIAL)
        assertThat(ChordMatcher.match(withSeventh, g7, g7, easy = false).result).isEqualTo(MatchResult.HIT)
    }
}
