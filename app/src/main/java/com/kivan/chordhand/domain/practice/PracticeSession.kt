package com.kivan.chordhand.domain.practice

import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.domain.music.ChordMatcher
import com.kivan.chordhand.domain.music.MatchDetail
import com.kivan.chordhand.domain.music.MatchResult
import com.kivan.chordhand.domain.music.Timeline
import kotlin.math.abs

/** A chord change in the song and how often it was missed. */
data class ChangeStat(val from: String, val to: String, val attempts: Int, val misses: Int)

data class PracticeSummary(
    val chordsReached: Int,
    val chordsHit: Int,
    /** Mean absolute timing error of the hits, null with no hits. */
    val meanTimingErrorMs: Long?,
    /** Share of hits that came early (negative error). */
    val earlyShare: Double,
    val worstChanges: List<ChangeStat>,
) {
    val hitShare: Double get() = if (chordsReached == 0) 0.0 else chordsHit.toDouble() / chordsReached
}

/**
 * Scores one play-through. Each chord counts once: the first time the held keys make it, from
 * [EARLY_MS] before its start until the next chord's window opens. [chords] are the targets as
 * played (simplified in Easy mode); [fullChords] are the written chords.
 */
class PracticeSession(
    private val timeline: Timeline,
    private val chords: List<Chord>,
    private val fullChords: List<Chord>,
    private val easy: Boolean,
) {
    private val hitErrorMs = arrayOfNulls<Long>(chords.size)
    private var furthestIndex = -1

    fun isHit(index: Int): Boolean = hitErrorMs.getOrNull(index) != null

    var hitCount: Int = 0
        private set

    fun hitIndices(): Set<Int> = hitErrorMs.indices.filter { hitErrorMs[it] != null }.toSet()

    /** The chord being aimed at: the one sounding, or the next one when it is less than [EARLY_MS] away. */
    fun targetIndex(songMs: Long): Int = timeline.chordAt(songMs + EARLY_MS)

    /**
     * Call on every change of the held keys and on every frame; returns the match against the
     * target. [forcedTarget] overrides it, for wait mode holding the song at one chord.
     */
    fun update(held: Set<Int>, songMs: Long, forcedTarget: Int? = null): MatchDetail? {
        val sounding = timeline.chordAt(songMs)
        if (sounding > furthestIndex) furthestIndex = sounding
        val target = forcedTarget ?: targetIndex(songMs)
        if (target < 0) return null
        val detail = ChordMatcher.match(held, chords[target], fullChords[target], easy)
        if (detail.result == MatchResult.HIT && hitErrorMs[target] == null) {
            hitErrorMs[target] = songMs - timeline.chords[target].startMs
            hitCount++
            if (target > furthestIndex) furthestIndex = target
        }
        return detail
    }

    fun summary(): PracticeSummary {
        val reached = furthestIndex + 1
        val hits = (0 until reached).mapNotNull { hitErrorMs[it] }
        val changes = (1 until reached)
            .groupBy { chords[it - 1].symbol to chords[it].symbol }
            .map { (pair, idx) -> ChangeStat(pair.first, pair.second, idx.size, idx.count { hitErrorMs[it] == null }) }
            .filter { it.from != it.to && it.misses > 0 }
            .sortedWith(compareByDescending<ChangeStat> { it.misses.toDouble() / it.attempts }.thenByDescending { it.misses })
            .take(3)
        return PracticeSummary(
            chordsReached = reached,
            chordsHit = hits.size,
            meanTimingErrorMs = hits.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average()?.toLong(),
            earlyShare = if (hits.isEmpty()) 0.0 else hits.count { it < 0 }.toDouble() / hits.size,
            worstChanges = changes,
        )
    }

    companion object {
        const val EARLY_MS = 250L
    }
}
