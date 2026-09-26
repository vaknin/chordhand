package com.kivan.chordhand.domain.music

import kotlin.math.abs

/** A song's home key: the tonic pitch class and whether it is minor. */
data class Key(val tonic: Int, val minor: Boolean) {
    /** Sharps or flats, as the key signature writes them. */
    val usesFlats: Boolean
        get() = if (minor) tonic in MINOR_FLAT_TONICS else tonic in MAJOR_FLAT_TONICS

    fun transpose(semitones: Int): Key = copy(tonic = Math.floorMod(tonic + semitones, 12))

    val name: String get() = Chord.noteName(tonic, usesFlats) + if (minor) "m" else ""

    companion object {
        // F, Bb, Eb, Ab, Db major; Dm, Gm, Cm, Fm, Bbm, Ebm. F#/Gb and its relative stay sharp.
        private val MAJOR_FLAT_TONICS = setOf(5, 10, 3, 8, 1)
        private val MINOR_FLAT_TONICS = setOf(2, 7, 0, 5, 10, 3)

        /**
         * The key of [chords] (as sounding). UG's tonality is taken as is when its tonic is one of
         * the song's chord roots, or shifted by [transpose] (capo and tuning) when that fits better;
         * failing both, the first chord is the key.
         */
        fun of(tonality: String?, transpose: Int, chords: List<Chord>): Key? {
            val roots = chords.groupingBy { it.root }.eachCount()
            val written = tonality?.let(ChordParser::parse)?.let { Key(it.root, it.isMinor) }
            val candidates = listOfNotNull(written, written?.transpose(transpose))
            return candidates.maxByOrNull { roots[it.tonic] ?: 0 }?.takeIf { (roots[it.tonic] ?: 0) > 0 }
                ?: chords.firstOrNull()?.let { Key(it.root, it.isMinor) }
        }
    }
}

/**
 * The same timeline in another key: every chord moves by [semitones] and is spelled with flats
 * when [preferFlats]. Times, words and lines stay as they are.
 */
fun Timeline.transposed(semitones: Int, preferFlats: Boolean): Timeline {
    if (semitones == 0) return this
    return copy(chords = chords.map { it.copy(chord = it.chord.transpose(semitones).copy(preferFlats = preferFlats)) })
}

/**
 * Suggests the key shift that keeps the hands on the white keys: the share of black keys across
 * every voicing, plus a small cost per semitone because the recording sounds worse the further
 * its pitch is shifted. Ties go to the smaller shift.
 */
object EasiestKey {
    val RANGE = -5..6
    private const val COST_PER_SEMITONE = 0.01
    private val BLACK = setOf(1, 3, 6, 8, 10)

    fun suggest(chords: List<Chord>, range: IntRange = RANGE): Int {
        if (chords.isEmpty()) return 0
        return range.sortedBy { abs(it) }.minBy { shift -> cost(chords, shift) }
    }

    internal fun blackShare(chords: List<Chord>): Double {
        val notes = VoicingPlanner.plan(chords).flatMap { it.all }
        return notes.count { it % 12 in BLACK }.toDouble() / notes.size.coerceAtLeast(1)
    }

    private fun cost(chords: List<Chord>, shift: Int): Double =
        blackShare(chords.map { it.transpose(shift) }) + abs(shift) * COST_PER_SEMITONE
}
