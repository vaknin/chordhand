package com.kivan.chordhand.domain.music

import kotlin.math.abs

/** MIDI notes for each hand. */
data class Voicing(val leftHand: List<Int>, val rightHand: List<Int>) {
    val all: List<Int> get() = leftHand + rightHand
}

/**
 * Chooses what each hand plays. The left hand takes the bass note in the octave below C3; the
 * right hand plays the chord in close position, picking the inversion nearest the previous
 * chord so the hand moves as little as possible (voice leading).
 */
object VoicingPlanner {
    const val LH_LOW = 36 // C2
    const val RH_CENTER = 64 // E4
    private const val RH_LOWEST = 53 // F3
    private const val RH_HIGHEST_START = 67 // G4
    private const val MAX_RH_NOTES = 4

    fun plan(chords: List<Chord>): List<Voicing> {
        var previous: List<Int>? = null
        return chords.map { chord ->
            val rh = bestRightHand(rightHandPitchClasses(chord), previous)
            previous = rh
            Voicing(listOf(LH_LOW + chord.bass), rh)
        }
    }

    /** The chord tones for the right hand, dropping the fifth first when there are too many. */
    internal fun rightHandPitchClasses(chord: Chord): List<Int> {
        var intervals = chord.intervals
        if (intervals.size > MAX_RH_NOTES) intervals = intervals.filter { it != 7 }
        if (intervals.size > MAX_RH_NOTES) intervals = intervals.take(MAX_RH_NOTES)
        return intervals.map { (chord.root + it) % 12 }.distinct()
    }

    private fun bestRightHand(pitchClasses: List<Int>, previous: List<Int>?): List<Int> {
        val sorted = pitchClasses.sorted()
        val candidates = sorted.indices.flatMap { inversion ->
            val order = sorted.drop(inversion) + sorted.take(inversion)
            (RH_LOWEST..RH_HIGHEST_START).filter { it % 12 == order.first() }.map { stack(order, it) }
        }
        return candidates.minBy { cost(it, previous) }
    }

    /** Stacks the pitch classes upward from [lowest], each note the nearest one above the last. */
    private fun stack(order: List<Int>, lowest: Int): List<Int> {
        val notes = mutableListOf(lowest)
        for (pc in order.drop(1)) {
            var n = notes.last() + 1
            while (n % 12 != pc) n++
            notes += n
        }
        return notes
    }

    private fun cost(v: List<Int>, previous: List<Int>?): Double {
        if (previous == null) return abs(v.average() - RH_CENTER)
        val drift = abs(v.average() - RH_CENTER) * 0.35
        // Movement: each note to the nearest note of the previous chord, both ways.
        val movement = v.sumOf { n -> previous.minOf { abs(it - n) } } +
            previous.sumOf { p -> v.minOf { abs(it - p) } }
        return movement + drift
    }
}

enum class MatchResult { HIT, PARTIAL, MISS }

data class MatchDetail(
    val result: MatchResult,
    /** Held notes that belong to the chord. */
    val correct: Set<Int>,
    /** Held notes that don't. */
    val wrong: Set<Int>,
)

/**
 * Decides whether the keys held down make the target chord. Any inversion and octave counts;
 * the lowest held note must be the bass. In Easy mode extra notes from the full chord (the 7th
 * of a G7, say) are fine; in Full mode every note of the right-hand voicing is required.
 */
object ChordMatcher {
    fun match(held: Set<Int>, target: Chord, full: Chord, easy: Boolean): MatchDetail {
        if (held.isEmpty()) return MatchDetail(MatchResult.MISS, emptySet(), emptySet())
        val required = if (easy) target.pitchClasses else VoicingPlanner.rightHandPitchClasses(target).toSet()
        val allowed = target.pitchClasses + target.bass + if (easy) full.pitchClasses + full.bass else emptySet()
        val correct = held.filter { it % 12 in allowed }.toSet()
        val wrong = held - correct
        val heldPcs = held.map { it % 12 }.toSet()
        val bassOk = held.min() % 12 == target.bass
        val result = when {
            wrong.isEmpty() && bassOk && heldPcs.containsAll(required) -> MatchResult.HIT
            correct.isNotEmpty() -> MatchResult.PARTIAL
            else -> MatchResult.MISS
        }
        return MatchDetail(result, correct, wrong)
    }
}
