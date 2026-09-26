package com.kivan.chordhand.domain.music

import kotlinx.serialization.Serializable
import kotlin.math.min

/** A word speech recognition heard in the recording, and when. */
@Serializable
data class HeardWord(val text: String, val ms: Long)

/** A word of the synced lyrics, normalized for matching, and when it is sung. */
data class LyricWord(val norm: String, val ms: Long)

/**
 * When each word of the synced lyrics is sung.
 *
 * LRC only times lines, and some lines by a second or two off. With nothing more, the words
 * are spread over their line at a typical singing speed. With the words heard in the recording,
 * each lyric word that was heard within a second of its line takes the heard time, and the rest
 * are placed between those by syllables, at the singer's own pace. Measured on Fluorescent
 * Adolescent against the isolated vocals, that brings the mean error from 0.30 s to 0.18 s.
 */
object LyricTiming {
    /** Seconds per character when a line's words are spread over it with nothing heard. */
    const val SEC_PER_CHAR = 0.075
    /**
     * whisper.cpp's DTW time marks about where a token ends; it was 0.2 s after the sung word
     * on the vocals it was measured on.
     */
    const val HEARD_LAG_MS = 200L
    /** How far a heard word may be from its LRC line and still count: LRC lines can be 1–2 s off. */
    private const val WINDOW_MS = 1000L
    private const val DEFAULT_MS_PER_SYLLABLE = 250.0
    private const val MIN_ANCHOR_GAP_MS = 50L
    private val WORD = Regex("""\S+""")

    fun normalize(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }

    fun words(lrc: List<LrcLine>, heard: List<HeardWord>, durationMs: Long): List<LyricWord> {
        val lines = lineWords(lrc, durationMs)
        val heardWords = heard.mapNotNull { h ->
            normalize(h.text).takeIf { it.isNotEmpty() }?.let { LyricWord(it, (h.ms - HEARD_LAG_MS).coerceAtLeast(0)) }
        }
        if (lines.isEmpty()) return heardWords
        if (heardWords.isEmpty()) return lines.flatMap(::spreadByCharacters)
        return placeByHeard(lines, heardWords)
    }

    private class Word(val norm: String, val column: Int, val syllables: Int)
    private class Line(val startMs: Long, val nextMs: Long, val text: String, val words: List<Word>)

    private fun lineWords(lrc: List<LrcLine>, durationMs: Long): List<Line> =
        lrc.mapIndexedNotNull { i, line ->
            if (line.text.isBlank()) return@mapIndexedNotNull null
            val words = WORD.findAll(line.text).mapNotNull { m ->
                val n = normalize(m.value)
                if (n.isEmpty()) null else Word(n, m.range.first, syllables(m.value))
            }.toList()
            if (words.isEmpty()) null else Line(line.timeMs, lrc.getOrNull(i + 1)?.timeMs ?: durationMs, line.text, words)
        }

    private fun spreadByCharacters(line: Line): List<LyricWord> {
        val msPerChar = min((line.nextMs - line.startMs).toDouble() / line.text.length.coerceAtLeast(1), SEC_PER_CHAR * 1000)
            .coerceAtLeast(0.0)
        return line.words.map { LyricWord(it.norm, line.startMs + (it.column * msPerChar).toLong()) }
    }

    private fun placeByHeard(lines: List<Line>, heard: List<LyricWord>): List<LyricWord> {
        val flat = lines.flatMapIndexed { li, line -> line.words.map { li to it } }
        val pairs = TimelineAligner.lcs(flat.map { it.second.norm }, heard.map { it.norm })

        // A heard word far from its line matched the same word sung elsewhere; one out of order too.
        val anchor = arrayOfNulls<Long>(flat.size)
        var last = Long.MIN_VALUE
        for ((w, h) in pairs) {
            val line = lines[flat[w].first]
            val ms = heard[h].ms
            if (ms < line.startMs - WINDOW_MS || ms > line.nextMs + WINDOW_MS) continue
            if (ms < last + MIN_ANCHOR_GAP_MS) continue
            anchor[w] = ms
            last = ms
        }

        // The singer's pace, from neighbouring heard words of one line.
        val rates = flat.indices.mapNotNull { w ->
            val a = anchor[w] ?: return@mapNotNull null
            val b = anchor.getOrNull(w + 1) ?: return@mapNotNull null
            if (flat[w + 1].first != flat[w].first || b <= a) null else (b - a).toDouble() / flat[w].second.syllables
        }.sorted()
        val msPerSyllable = rates.getOrNull(rates.size / 2) ?: DEFAULT_MS_PER_SYLLABLE

        val out = LongArray(flat.size)
        var offset = 0
        for (line in lines) {
            val ids = offset until offset + line.words.size
            offset += line.words.size
            val known = ids.filter { anchor[it] != null }
            if (known.isEmpty()) {
                // Nothing heard: the LRC start, then the singer's pace, squeezed into the line.
                val total = line.words.sumOf { it.syllables }
                val per = min(msPerSyllable, (line.nextMs - line.startMs).toDouble() / (total + 1))
                var acc = 0
                for (w in ids) {
                    out[w] = line.startMs + (acc * per).toLong()
                    acc += flat[w].second.syllables
                }
                continue
            }
            known.forEach { out[it] = anchor[it]!! }
            for (w in known.first() - 1 downTo ids.first) {
                out[w] = out[w + 1] - (flat[w].second.syllables * msPerSyllable).toLong()
            }
            for (w in known.last() + 1..ids.last) {
                out[w] = out[w - 1] + (flat[w - 1].second.syllables * msPerSyllable).toLong()
            }
            known.zipWithNext().forEach { (a, b) ->
                val span = (a until b).sumOf { flat[it].second.syllables }
                var acc = 0
                for (w in a + 1 until b) {
                    acc += flat[w - 1].second.syllables
                    out[w] = out[a] + (out[b] - out[a]) * acc / span
                }
            }
        }
        return flat.mapIndexed { w, (_, word) -> LyricWord(word.norm, out[w].coerceAtLeast(0)) }
    }

    /** Vowel groups, less a silent final e: close enough to pace words by. */
    internal fun syllables(word: String): Int {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return 1
        var n = VOWELS.findAll(w).count()
        if (w.endsWith("e") && !w.endsWith("le") && !w.endsWith("ee") && n > 1) n--
        return n.coerceAtLeast(1)
    }

    private val VOWELS = Regex("[aeiouy]+")
}
