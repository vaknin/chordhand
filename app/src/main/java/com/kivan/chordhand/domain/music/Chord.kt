package com.kivan.chordhand.domain.music

/**
 * A chord symbol reduced to what the hands need: a root, the intervals above it, and the bass.
 * Pitch classes are 0..11 with C = 0.
 */
data class Chord(
    val root: Int,
    /** Semitones above the root, 0 first, no duplicates. */
    val intervals: List<Int>,
    /** Pitch class of a slash bass (C/G), or null when the root is the bass. */
    val slashBass: Int?,
    /** The suffix as written (m7, sus4, add9), kept for display. */
    val suffix: String,
    val preferFlats: Boolean,
) {
    val bass: Int get() = slashBass ?: root

    val pitchClasses: Set<Int> get() = intervals.map { (root + it) % 12 }.toSet()

    val isMinor: Boolean get() = 3 in intervals && 4 !in intervals

    /**
     * The three-note version a beginner plays: major, minor, diminished or augmented triad.
     * Sus and power chords become major, extensions are dropped, the slash bass is kept.
     */
    fun simplified(): Chord {
        val third = if (isMinor) 3 else 4
        val fifth = when {
            6 in intervals && 7 !in intervals && third == 3 -> 6
            8 in intervals && 7 !in intervals && third == 4 -> 8
            else -> 7
        }
        val easySuffix = when {
            third == 3 && fifth == 6 -> "dim"
            third == 3 -> "m"
            fifth == 8 -> "aug"
            else -> ""
        }
        return copy(intervals = listOf(0, third, fifth), suffix = easySuffix)
    }

    fun transpose(semitones: Int): Chord = copy(
        root = Math.floorMod(root + semitones, 12),
        slashBass = slashBass?.let { Math.floorMod(it + semitones, 12) },
    )

    val symbol: String
        get() = buildString {
            append(noteName(root, preferFlats))
            append(suffix)
            slashBass?.let { append('/').append(noteName(it, preferFlats)) }
        }

    override fun toString(): String = symbol

    companion object {
        private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

        fun noteName(pitchClass: Int, preferFlats: Boolean = false): String =
            (if (preferFlats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pitchClass, 12)]

        /** C4 = 60. */
        fun midiName(note: Int, preferFlats: Boolean = false): String =
            noteName(note % 12, preferFlats) + (note / 12 - 1)
    }
}

object ChordParser {
    private val SYMBOL = Regex("""^([A-G])([#b♯♭]?)([^/]*)(?:/([A-G])([#b♯♭]?))?$""")
    private val NATURAL = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** Returns null for anything that is not a chord symbol (N.C., x2, lyrics). */
    fun parse(text: String): Chord? {
        val m = SYMBOL.matchEntire(text.trim()) ?: return null
        val (letter, accidental, rawSuffix, bassLetter, bassAccidental) = m.destructured
        val intervals = parseSuffix(rawSuffix) ?: return null
        val root = pitch(letter[0], accidental)
        val bass = if (bassLetter.isNotEmpty()) pitch(bassLetter[0], bassAccidental) else null
        return Chord(
            root = root,
            intervals = intervals,
            slashBass = bass?.takeIf { it != root },
            suffix = rawSuffix,
            preferFlats = accidental == "b" || accidental == "♭",
        )
    }

    private fun pitch(letter: Char, accidental: String): Int {
        val base = NATURAL.getValue(letter)
        return Math.floorMod(
            when (accidental) {
                "#", "♯" -> base + 1
                "b", "♭" -> base - 1
                else -> base
            },
            12,
        )
    }

    /**
     * Reads the suffix left to right: the triad quality first (m, dim, aug, sus, 5), then
     * extensions (6, 7, maj7, 9, 11, 13, add9, b5, #5). Returns null for text it can't read, so
     * words like "Chorus" are never mistaken for a chord.
     */
    internal fun parseSuffix(raw: String): List<Int>? {
        var s = raw.replace("(", "").replace(")", "").replace("♭", "b").replace("♯", "#")
        var third: Int? = 4
        var fifth = 7
        var diminished = false
        val extra = sortedSetOf<Int>()

        fun eat(prefix: String): Boolean =
            if (s.startsWith(prefix)) { s = s.removePrefix(prefix); true } else false

        when {
            eat("maj") || eat("M") || eat("Δ") -> {
                // A leading "maj" is the major-seventh family (maj7, maj9), not a quality.
                s = "maj$s"
            }
            eat("min") || eat("mi") || (s.startsWith("m") && eat("m")) || eat("-") -> third = 3
            eat("dim") || eat("°") || eat("o") -> { third = 3; fifth = 6; diminished = true }
            eat("aug") || eat("+") -> fifth = 8
        }

        while (s.isNotEmpty()) {
            when {
                eat("maj13") -> extra += listOf(11, 2, 9)
                eat("maj11") -> extra += listOf(11, 2, 5)
                eat("maj9") -> extra += listOf(11, 2)
                eat("maj7") || eat("Maj7") || eat("M7") || eat("maj") -> extra += 11
                eat("add9") || eat("add2") -> extra += 2
                eat("add11") || eat("add4") -> extra += 5
                eat("sus2") -> third = null.also { extra += 2 }
                eat("sus4") || eat("sus") -> third = null.also { extra += 5 }
                eat("13") -> extra += listOf(if (11 in extra) 11 else 10, 2, 9)
                eat("11") -> extra += listOf(if (11 in extra) 11 else 10, 2, 5)
                eat("9") -> extra += listOf(if (11 in extra) 11 else 10, 2)
                eat("7") -> extra += if (diminished) 9 else 10
                eat("6") -> extra += 9
                eat("5") -> if (raw.trimStart().startsWith("5")) third = null else return null
                eat("b5") || eat("-5") -> fifth = 6
                eat("#5") || eat("+5") -> fifth = 8
                eat("b9") -> extra += 1
                eat("#9") -> extra += 3
                eat("#11") -> extra += 6
                eat("b13") -> extra += 8
                eat("/") -> return null
                else -> return null
            }
        }
        // "m7b5" reads as minor + 7 + b5, while "dim7" is the diminished seventh (a 6th above).
        return buildList {
            add(0)
            third?.let { add(it) }
            add(fifth)
            extra.forEach { if (it !in this) add(it) }
        }
    }
}
