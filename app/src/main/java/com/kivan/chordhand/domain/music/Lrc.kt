package com.kivan.chordhand.domain.music

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val STAMP = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    /** Parses `[mm:ss.xx] text` lines; a line with several stamps is repeated at each. */
    fun parse(lrc: String): List<LrcLine> = lrc.lines().flatMap { line ->
        val stamps = STAMP.findAll(line).toList()
        if (stamps.isEmpty()) return@flatMap emptyList()
        val text = line.substring(stamps.last().range.last + 1).trim()
        stamps.map { m ->
            val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
            LrcLine(ms, text)
        }
    }.sortedBy { it.timeMs }

    /**
     * How believable the timing is, 0..1: the share of sung lines that leave a singable amount
     * of time before the next line. Many LRCLIB entries copy one bad sync where lines are 0.7 s
     * apart; those score low.
     */
    fun plausibility(lines: List<LrcLine>): Double {
        var judged = 0
        var ok = 0
        for (i in 0 until lines.size - 1) {
            val chars = lines[i].text.length
            if (chars < 8) continue
            val gapSec = (lines[i + 1].timeMs - lines[i].timeMs) / 1000.0
            if (gapSec > 12) continue
            judged++
            if (chars / gapSec.coerceAtLeast(0.01) <= MAX_CHARS_PER_SEC) ok++
        }
        return if (judged == 0) 0.0 else ok.toDouble() / judged
    }

    /** Fast singing is about 15 characters a second; 22 leaves room for fast verses. */
    private const val MAX_CHARS_PER_SEC = 22.0
}
