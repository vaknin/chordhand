package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.source.AudioStreamUrl
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.UgSearchResult
import com.kivan.chordhand.data.source.UgTab
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.data.source.YoutubeAudioSource
import com.kivan.chordhand.data.source.YoutubeTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SongLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val result = UgSearchResult("Arctic Monkeys", "Fluorescent Adolescent", 4.8, 900, "https://tabs.example/fa")
    private val ug = mockk<UltimateGuitarSource> {
        every { search(any()) } returns listOf(result)
        every { fetchTab(result.url) } returns UgTab(result.artist, result.song, result.url, "[ch]E[/ch]\nYou used to get it", 0, 0, "E")
    }
    private val lrclib = mockk<LrclibSource> { every { search(any(), any()) } returns emptyList() }
    private var videoUrl = "https://youtube.example/v1"
    private val youtube = mockk<YoutubeAudioSource> {
        every { search(any(), any()) } answers { listOf(YoutubeTrack(videoUrl, "Fluorescent Adolescent", "Arctic Monkeys", 180)) }
        every { resolveAudio(any()) } returns AudioStreamUrl("https://stream.example/a.m4a", "audio/mp4", 128_000)
        every { download(any(), any()) } answers { secondArg<File>().writeText("m4a") }
    }
    private val store by lazy { SongStore(tmp.root) }
    private val loader by lazy { SongLoader(store, ug, lrclib, lazyOf(youtube), background = { it.run() }) }

    private fun load(r: UgSearchResult = result) = loader.load(r) {}

    @Test fun firstOpenFetchesStreamsAndDownloadsForNextTime() {
        val song = load()
        assertThat(song.audioUri).isEqualTo("https://stream.example/a.m4a")
        verify(exactly = 1) { ug.fetchTab(any()); youtube.search(any(), any()); lrclib.search(any(), any()) }
        assertThat(loader.isDownloaded(song.cached)).isTrue()
    }

    @Test fun secondOpenOfTheSameResultNeedsNoNetwork() {
        load()
        val steps = mutableListOf<LoadStep>()
        val again = loader.load(result) { steps += it }
        assertThat(steps).isEmpty()
        assertThat(again.audioUri).startsWith("file:")
        verify(exactly = 1) { ug.fetchTab(any()); youtube.search(any(), any()); youtube.resolveAudio(any()) }
    }

    @Test fun savedSongWithoutRecordingOnlyResolvesTheStream() {
        every { youtube.download(any(), any()) } throws RuntimeException("offline")
        load()
        val steps = mutableListOf<LoadStep>()
        loader.load(result) { steps += it }
        assertThat(steps).containsExactly(LoadStep.STREAM)
        verify(exactly = 1) { ug.fetchTab(any()) }
        verify(exactly = 2) { youtube.resolveAudio(any()) }
    }

    @Test fun refreshRefetchesEverythingAndKeepsTheSyncForTheSameRecording() {
        val first = load()
        loader.save(first.cached.copy(offsetMs = -800))
        val refreshed = loader.refresh(store.load(first.cached.id)!!) {}
        verify(exactly = 2) { ug.fetchTab(any()); youtube.search(any(), any()); youtube.download(any(), any()) }
        assertThat(refreshed.cached.offsetMs).isEqualTo(-800)
    }

    @Test fun refreshToADifferentRecordingDropsTheSyncAndTheOldFile() {
        val first = load()
        loader.save(first.cached.copy(offsetMs = -800))
        every { youtube.download(any(), any()) } throws RuntimeException("offline")
        videoUrl = "https://youtube.example/v2"
        val refreshed = loader.refresh(store.load(first.cached.id)!!) {}
        assertThat(refreshed.cached.offsetMs).isEqualTo(0)
        assertThat(loader.isDownloaded(refreshed.cached)).isFalse()
    }

    @Test fun searchesAreRememberedForTheSession() {
        loader.search("fluorescent adolescent")
        loader.search(" Fluorescent Adolescent ")
        verify(exactly = 1) { ug.search(any()) }
    }
}
