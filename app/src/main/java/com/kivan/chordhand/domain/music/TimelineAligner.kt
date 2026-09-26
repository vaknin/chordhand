package com.kivan.chordhand.domain.music

import kotlin.math.abs
import kotlin.math.min

data class TimedChord(
    val index: Int,
    val startMs: Long,
    val chord: Chord,
    val lineIndex: Int,
    val column: Int,
    /** True when the time came from a matched lyric word, false when it was interpolated. */
    val anchored: Boolean,
)

/** A word of a sheet line and when it is sung; [ms] is null when no lyric matched it. */
data class TimedWord(val lineIndex: Int, val start: Int, val end: Int, val ms: Long?)

data class Timeline(
    val sheet: ChordSheet,
    val chords: List<TimedChord>,
    val words: List<TimedWord>,
    /** Start of each sheet line, same order as [ChordSheet.lines]; never decreasing. */
    val lineStartMs: List<Long>,
    val durationMs: Long,
) {
    val anchoredShare: Double
        get() = if (chords.isEmpty()) 0.0 else chords.count { it.anchored }.toDouble() / chords.size

    fun chordEndMs(i: Int): Long = chords.getOrNull(i + 1)?.startMs ?: durationMs

    /** Index of the chord sounding at [ms], or -1 before the first one. */
    fun chordAt(ms: Long): Int {
        var lo = 0
        var hi = chords.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chords[mid].startMs <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    fun lineAt(ms: Long): Int = lineStartMs.indexOfLast { it <= ms }.coerceAtLeast(0)
}

/**
 * Gives every chord on the sheet a time in the recording, using the time-synced lyrics.
 *
 * Works on words, not lines, because the sheet and the LRC split lines differently ("Oh, the
 * boy's a slag / The best you ever had" is one LRC line). The two word sequences are aligned
 * with a fuzzy longest-common-subsequence; each chord takes the time of the word it sits over.
 * Chords with no matched word (intros, solos, unmatched lines) are spread evenly between their
 * timed neighbours.
 */
object TimelineAligner {
    /** Seconds per character when a line's words are spread over its duration. */
    private const val SEC_PER_CHAR = 0.075
    private const val DEFAULT_CHORD_MS = 2000L
    private const val MIN_CHORD_GAP_MS = 150L
    private val WORD = Regex("""\S+""")

    private data class LrcWord(val norm: String, val ms: Long)
    private data class SheetWord(val lineIndex: Int, val start: Int, val end: Int, val norm: String)

    fun align(sheet: ChordSheet, lrc: List<LrcLine>, durationMs: Long): Timeline {
        val sheetWords = sheetWords(sheet)
        val lrcWords = lrcWords(lrc, durationMs)
        val match = lcs(sheetWords.map { it.norm }, lrcWords.map { it.norm })

        val wordMs = arrayOfNulls<Long>(sheetWords.size)
        match.forEach { (s, l) -> wordMs[s] = lrcWords[l].ms }
        val words = sheetWords.mapIndexed { i, w -> TimedWord(w.lineIndex, w.start, w.end, wordMs[i]) }
        val wordsByLine = words.groupBy { it.lineIndex }

        // Place each chord at the timed word it sits over.
        data class Slot(val lineIndex: Int, val column: Int, val chord: Chord, var ms: Long?)
        val slots = sheet.lines.flatMapIndexed { li, line ->
            line.chords.map { pc -> Slot(li, pc.column, pc.chord, anchorTime(wordsByLine[li], pc.column)) }
        }

        // A matched word far out of order is a wrong match; drop anchors that go backwards.
        var lastKept = Long.MIN_VALUE
        for (s in slots) {
            val t = s.ms ?: continue
            if (t < lastKept + MIN_CHORD_GAP_MS) s.ms = null else lastKept = t
        }

        val anchored = slots.map { it.ms != null }
        val times = fill(slots.map { it.ms }, durationMs)
        val chords = slots.mapIndexed { i, s ->
            TimedChord(i, times[i], s.chord, s.lineIndex, s.column, anchored[i])
        }
        return Timeline(sheet, chords, words, lineStarts(sheet, chords, wordsByLine), durationMs)
    }

    internal fun normalize(word: String): String =
        word.lowercase().filter { it.isLetterOrDigit() }

    private fun sheetWords(sheet: ChordSheet): List<SheetWord> =
        sheet.lines.flatMapIndexed { li, line ->
            val lyric = line.lyric ?: return@flatMapIndexed emptyList()
            WORD.findAll(lyric).mapNotNull { m ->
                val n = normalize(m.value)
                if (n.isEmpty()) null else SheetWord(li, m.range.first, m.range.last + 1, n)
            }.toList()
        }

    private fun lrcWords(lrc: List<LrcLine>, durationMs: Long): List<LrcWord> =
        lrc.flatMapIndexed { i, line ->
            if (line.text.isBlank()) return@flatMapIndexed emptyList()
            val nextMs = lrc.getOrNull(i + 1)?.timeMs ?: durationMs
            val msPerChar = min(
                (nextMs - line.timeMs).toDouble() / line.text.length.coerceAtLeast(1),
                SEC_PER_CHAR * 1000,
            ).coerceAtLeast(0.0)
            WORD.findAll(line.text).mapNotNull { m ->
                val n = normalize(m.value)
                if (n.isEmpty()) null else LrcWord(n, line.timeMs + (m.range.first * msPerChar).toLong())
            }.toList()
        }

    private fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        if (min(a.length, b.length) < 4 || abs(a.length - b.length) > 1) return false
        return levenshtein(a, b) <= 1
    }

    /** Pairs (sheet index, lrc index) of a longest common subsequence under [sameWord]. */
    internal fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (sameWord(a[i], b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                sameWord(a[i], b[j]) && dp[i][j] == dp[i + 1][j + 1] + 1 -> { out += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Time of a chord at [column]: the word under it, shifted by the columns between them. */
    private fun anchorTime(lineWords: List<TimedWord>?, column: Int): Long? {
        val timed = lineWords?.filter { it.ms != null }.orEmpty()
        if (timed.isEmpty()) return null
        val word = timed.lastOrNull { it.start <= column } ?: timed.first()
        val shift = (column - word.start).coerceIn(-8, 12)
        return (word.ms!! + shift * SEC_PER_CHAR * 1000).toLong().coerceAtLeast(0)
    }

    /** Fills the null times: evenly between timed neighbours, at the typical chord length outside them. */
    internal fun fill(times: List<Long?>, durationMs: Long): List<Long> {
        val n = times.size
        if (n == 0) return emptyList()
        val known = times.indices.filter { times[it] != null }
        if (known.isEmpty()) return List(n) { durationMs * it / n }

        val gaps = known.zipWithNext().filter { (a, b) -> b == a + 1 }.map { (a, b) -> times[b]!! - times[a]!! }
        val typical = gaps.sorted().getOrNull(gaps.size / 2) ?: DEFAULT_CHORD_MS
        val out = LongArray(n)
        known.forEach { out[it] = times[it]!! }

        val first = known.first()
        val firstMs = times[first]!!
        val step = if (first * typical <= firstMs) typical else firstMs / (first + 1)
        for (k in 0 until first) out[k] = firstMs - (first - k) * step

        known.zipWithNext().forEach { (a, b) ->
            for (k in a + 1 until b) out[k] = out[a] + (out[b] - out[a]) * (k - a) / (b - a)
        }

        val last = known.last()
        val room = durationMs - out[last]
        val tail = n - 1 - last
        val tailStep = if (tail == 0 || (tail + 1) * typical <= room) typical else room / (tail + 1)
        for (k in last + 1 until n) out[k] = out[last] + (k - last) * tailStep
        return out.toList()
    }

    private fun lineStarts(sheet: ChordSheet, chords: List<TimedChord>, wordsByLine: Map<Int, List<TimedWord>>): List<Long> {
        val chordsByLine = chords.groupBy { it.lineIndex }
        val raw = sheet.lines.indices.map { li ->
            val candidates = wordsByLine[li].orEmpty().mapNotNull { it.ms } + chordsByLine[li].orEmpty().map { it.startMs }
            candidates.minOrNull()
        }
        // Lines with nothing timed start where the previous one did; the result never decreases.
        var running = 0L
        return raw.map { t ->
            running = maxOf(running, t ?: running)
            running
        }
    }
}
