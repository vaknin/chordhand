package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.Fixtures
import com.kivan.chordhand.data.source.SourceException
import com.kivan.chordhand.data.source.UltimateGuitarSource
import org.junit.Assert.assertThrows
import org.junit.Test

class UltimateGuitarSourceTest {
    @Test fun searchKeepsOneChordSheetPerSongMostVotedFirst() {
        val results = UltimateGuitarSource.parseSearch(Fixtures.read("ug_search_fluorescent.html"))
        val first = results.first()
        assertThat(first.artist).isEqualTo("Arctic Monkeys")
        assertThat(first.song).isEqualTo("Fluorescent Adolescent")
        assertThat(first.votes).isEqualTo(2197)
        assertThat(first.url).endsWith("-510133")
        assertThat(results.map { it.artist.lowercase() to it.song.lowercase() }).containsNoDuplicates()
    }

    @Test fun tabReadsKeyAndCapo() {
        val tab = UltimateGuitarSource.parseTab(Fixtures.read("ug_tab_fluorescent.html"), "u")
        assertThat(tab.tonality).isEqualTo("E")
        assertThat(tab.capo).isEqualTo(0)
        assertThat(tab.content).contains("[ch]C#m[/ch]")
    }

    @Test fun tuningOffsetFromTheAString() {
        assertThat(UltimateGuitarSource.tuningOffset("E A D G B E")).isEqualTo(0)
        assertThat(UltimateGuitarSource.tuningOffset("Eb Ab Db Gb Bb Eb")).isEqualTo(-1)
        assertThat(UltimateGuitarSource.tuningOffset("D A D G B E")).isEqualTo(0)
        assertThat(UltimateGuitarSource.tuningOffset("D G C F A D")).isEqualTo(-2)
        assertThat(UltimateGuitarSource.tuningOffset(null)).isEqualTo(0)
    }

    @Test fun blockedPageIsAClearError() {
        assertThrows(SourceException::class.java) { UltimateGuitarSource.parseSearch("<html>Just a moment...</html>") }
    }
}
