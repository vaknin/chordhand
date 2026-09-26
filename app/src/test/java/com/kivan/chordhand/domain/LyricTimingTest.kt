package com.kivan.chordhand.domain

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.listen.LyricListener
import com.kivan.chordhand.domain.music.HeardWord
import com.kivan.chordhand.domain.music.LrcLine
import com.kivan.chordhand.domain.music.LyricTiming
import com.kivan.chordhand.domain.music.LyricTiming.HEARD_LAG_MS
import org.junit.Test

class LyricTimingTest {
    private val lrc = listOf(LrcLine(10_000, "You used to get it in your fishnets"), LrcLine(14_000, "Now you only get it"), LrcLine(20_000, ""))

    @Test fun withNothingHeardWordsAreSpreadByCharacters() {
        val words = LyricTiming.words(lrc, emptyList(), 30_000)
        assertThat(words.first().ms).isEqualTo(10_000)
        // "fishnets" starts at column 27: 27 × 75 ms.
        assertThat(words.first { it.norm == "fishnets" }.ms).isEqualTo(10_000 + 27 * 75)
    }

    @Test fun heardWordsTakeTheirOwnTimeLessTheLag() {
        val heard = listOf(HeardWord("You", 9_700), HeardWord("used", 10_300), HeardWord("fishnets", 12_900), HeardWord("Now", 14_500))
        val words = LyricTiming.words(lrc, heard, 30_000)
        assertThat(words.first { it.norm == "you" }.ms).isEqualTo(9_700 - HEARD_LAG_MS)
        assertThat(words.first { it.norm == "fishnets" }.ms).isEqualTo(12_900 - HEARD_LAG_MS)
        assertThat(words.first { it.norm == "now" }.ms).isEqualTo(14_500 - HEARD_LAG_MS)
        // Unheard words sit between the heard ones, in order.
        val times = words.take(8).map { it.ms }
        assertThat(times).isInOrder()
        assertThat(words.first { it.norm == "your" }.ms).isIn((10_100L + 1)..(12_700L - 1))
    }

    @Test fun aHeardWordFarFromItsLineIsIgnored() {
        // "get" heard 30 s later is the same word sung elsewhere.
        val heard = listOf(HeardWord("You", 10_200), HeardWord("used", 10_500), HeardWord("get", 45_000))
        val words = LyricTiming.words(lrc, heard, 60_000)
        assertThat(words.first { it.norm == "get" }.ms).isLessThan(14_000)
    }

    @Test fun wordsAfterTheLastHeardOneFollowTheSingersPace() {
        val heard = listOf(HeardWord("Now", 14_200), HeardWord("you", 14_500), HeardWord("only", 14_800))
        val words = LyricTiming.words(lrc, heard, 30_000)
        val get = words.first { it.norm == "get" && it.ms > 14_000 }.ms
        val it = words.last { it.norm == "it" }.ms
        // "only" is two syllables at 300 ms each; "get", one.
        assertThat(get).isEqualTo(14_600 + 600)
        assertThat(it).isEqualTo(get + 300)
    }

    @Test fun withNoLrcTheHeardWordsAreTheLyrics() {
        val words = LyricTiming.words(emptyList(), listOf(HeardWord("Hello", 5_000), HeardWord("♪", 6_000)), 30_000)
        assertThat(words.map { it.norm to it.ms }).containsExactly("hello" to 5_000 - HEARD_LAG_MS)
    }

    @Test fun syllables() {
        assertThat(LyricTiming.syllables("fishnets")).isEqualTo(2)
        assertThat(LyricTiming.syllables("gentle")).isEqualTo(2)
        assertThat(LyricTiming.syllables("love")).isEqualTo(1)
        assertThat(LyricTiming.syllables("I'm")).isEqualTo(1)
    }

    @Test fun parsesNativeOutput() {
        val heard = LyricListener.parse("1200\tYou\n1450\tused\n\nbad line\n1600\t\n")
        assertThat(heard).containsExactly(HeardWord("You", 1_200), HeardWord("used", 1_450)).inOrder()
    }
}
