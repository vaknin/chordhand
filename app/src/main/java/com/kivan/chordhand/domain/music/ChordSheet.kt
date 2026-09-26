package com.kivan.chordhand.domain.music

/** A chord written over a lyric line, [column] characters from the start of the line. */
data class PlacedChord(val column: Int, val chord: Chord)

/**
 * One line of the sheet as it will be shown: the lyric (null for a chord-only line such as an
 * intro), the chords above it, and the section it belongs to.
 */
data class SheetLine(
    val section: String?,
    val lyric: String?,
    val chords: List<PlacedChord>,
)

data class ChordSheet(val lines: List<SheetLine>) {
    val chordCount: Int get() = lines.sumOf { it.chords.size }
}

/**
 * Reads Ultimate Guitar's tab markup: `[ch]Am[/ch]` marks a chord, `[tab]…[/tab]` wraps a chord
 * line with the lyric line under it, `[Verse 1]` on its own line starts a section. Guitar
 * tablature (`e|---0---|`) is skipped.
 */
object ChordSheetParser {
    private val SECTION = Regex("""^\s*\[([^\[\]]+)]\s*$""")
    private val CHORD_TAG = Regex("""\[ch](.*?)\[/ch]""")
    private val TAB_STRING = Regex("""^\s*[eBGDAEbgda]?\s*\|[-0-9|hpbr/\\~x ]*$""")
    /** A fingering chart line: "Em7     0-2-2-0-3-3" or "G  320033". */
    private val CHORD_DIAGRAM = Regex("""^\s*\S+\s+([x0-9]{1,2}[- ]?){6}\s*$""", RegexOption.IGNORE_CASE)
    /** What may stand between chords on a chord-only line: bars, repeat marks, N.C. */
    private val CHORD_LINE_NOISE = Regex("""^(\s|[|:\-/().,*]|x\s*\d+|\d+\s*x|N\.?C\.?)*$""", RegexOption.IGNORE_CASE)

    fun parse(content: String, capo: Int = 0): ChordSheet {
        val raw = content.replace("\r", "").replace("[tab]", "").replace("[/tab]", "").lines()
        val out = mutableListOf<SheetLine>()
        var section: String? = null
        var pending: List<PlacedChord>? = null

        fun flushPending() {
            pending?.let { out += SheetLine(section, null, it) }
            pending = null
        }

        for (line in raw) {
            if (line.isBlank()) {
                flushPending()
                continue
            }
            val header = if ("[ch]" in line) null else SECTION.matchEntire(line)
            if (header != null) {
                flushPending()
                section = header.groupValues[1].trim()
                continue
            }
            if ((TAB_STRING.matches(line) && line.count { it == '-' } > 4) || CHORD_DIAGRAM.matches(line)) {
                flushPending()
                continue
            }

            val (text, chords) = stripChords(line, capo)
            val isChordLine = chords.isNotEmpty() && CHORD_LINE_NOISE.matches(text)
            when {
                isChordLine -> {
                    flushPending()
                    pending = chords
                }
                chords.isNotEmpty() -> {
                    // Chords written inline inside the lyric: [ch]Am[/ch]Hello there.
                    flushPending()
                    out += SheetLine(section, text.trimEnd(), chords)
                }
                else -> {
                    out += SheetLine(section, text.trimEnd(), pending ?: emptyList())
                    pending = null
                }
            }
        }
        flushPending()
        return ChordSheet(out)
    }

    /**
     * Removes the `[ch]` tags and returns the plain line plus each chord's column in it. A chord
     * written inline takes no room in the lyric, so its column is where the lyric continues.
     */
    private fun stripChords(line: String, capo: Int): Pair<String, List<PlacedChord>> {
        val plain = StringBuilder()
        val chords = mutableListOf<PlacedChord>()
        var last = 0
        for (m in CHORD_TAG.findAll(line)) {
            plain.append(line, last, m.range.first)
            val symbol = m.groupValues[1]
            ChordParser.parse(symbol)?.let { chords += PlacedChord(plain.length, it.transpose(capo)) }
            // Keep the chord's width on a chord-only line so later columns still line up with
            // the lyric below; inline chords are removed after classification.
            plain.append(" ".repeat(symbol.length))
            last = m.range.last + 1
        }
        plain.append(line.substring(last))
        val text = plain.toString()
        if (chords.isEmpty() || CHORD_LINE_NOISE.matches(text)) return text to chords
        return removeInline(line, capo)
    }

    /** For a lyric with chords inline, the chords take no columns in the lyric. */
    private fun removeInline(line: String, capo: Int): Pair<String, List<PlacedChord>> {
        val plain = StringBuilder()
        val chords = mutableListOf<PlacedChord>()
        var last = 0
        for (m in CHORD_TAG.findAll(line)) {
            plain.append(line, last, m.range.first)
            ChordParser.parse(m.groupValues[1])?.let { chords += PlacedChord(plain.length, it.transpose(capo)) }
            last = m.range.last + 1
        }
        plain.append(line.substring(last))
        return plain.toString() to chords
    }
}
