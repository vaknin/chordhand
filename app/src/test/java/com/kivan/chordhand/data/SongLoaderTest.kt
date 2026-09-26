package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.source.AudioStreamUrl
import com.kivan.chordhand.data.source.LrcCandidate
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.UgSearchResult
import com.kivan.chordhand.data.source.UgTab
import com.kivan.chordhand.data.source.UgVersion
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
        every { download(any(), any(), any()) } answers { secondArg<File>().writeText("m4a") }
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
        every { youtube.download(any(), any(), any()) } throws RuntimeException("offline")
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
        verify(exactly = 2) { ug.fetchTab(any()); youtube.search(any(), any()); youtube.download(any(), any(), any()) }
        assertThat(refreshed.cached.offsetMs).isEqualTo(-800)
    }

    @Test fun refreshToADifferentRecordingDropsTheSyncAndTheOldFile() {
        val first = load()
        loader.save(first.cached.copy(offsetMs = -800))
        every { youtube.download(any(), any(), any()) } throws RuntimeException("offline")
        videoUrl = "https://youtube.example/v2"
        val refreshed = loader.refresh(store.load(first.cached.id)!!) {}
        assertThat(refreshed.cached.offsetMs).isEqualTo(0)
        assertThat(loader.isDownloaded(refreshed.cached)).isFalse()
    }

    @Test fun stepsRunLyricsBeforeTheRecording() {
        val steps = mutableListOf<LoadStep>()
        loader.load(result) { steps += it }
        assertThat(steps).containsExactly(LoadStep.CHORDS, LoadStep.LYRICS, LoadStep.AUDIO, LoadStep.STREAM).inOrder()
    }

    @Test fun keyShiftSurvivesARefreshToADifferentRecording() {
        val first = load()
        loader.save(first.cached.copy(keyShift = -3))
        videoUrl = "https://youtube.example/v2"
        val refreshed = loader.refresh(store.load(first.cached.id)!!) {}
        assertThat(refreshed.cached.keyShift).isEqualTo(-3)
    }

    @Test fun downloadProgressIsReportedAndClearedWhenDone() {
        every { youtube.download(any(), any(), any()) } answers {
            thirdArg<(Long, Long) -> Unit>()(1, 2)
            assertThat(loader.downloads.value.values.single()).isEqualTo(DownloadState.Running(0.5f))
            secondArg<File>().writeText("m4a")
        }
        load()
        assertThat(loader.downloads.value).isEmpty()
    }

    @Test fun failedDownloadIsReported() {
        every { youtube.download(any(), any(), any()) } throws RuntimeException("offline")
        val song = load()
        assertThat(loader.downloads.value[song.cached.id]).isEqualTo(DownloadState.Failed)
    }

    private val lrc = LrcCandidate(1, duration = 180.0, syncedLyrics = "[00:10.00]You used to get it in your fishnets\n[00:15.00]Now you only get it in your nightdress")
    private val v2 = "https://tabs.example/fa-v2"
    private val withVersions = result.copy(versions = listOf(UgVersion(result.url, 4.8, 900), UgVersion(v2, 4.5, 40)))

    @Test fun aBadlyAlignedSheetGivesWayToABetterVersion() {
        every { lrclib.search(any(), any()) } returns listOf(lrc)
        every { ug.fetchTab(result.url) } returns UgTab(result.artist, result.song, result.url, "[ch]E[/ch]\nSomething else entirely", 0, 0, "E")
        every { ug.fetchTab(v2) } returns UgTab(result.artist, result.song, v2, "[ch]E[/ch]\nYou used to get it in your fishnets", 0, 0, "E")
        val song = loader.load(withVersions) {}
        assertThat(song.cached.ugUrl).isEqualTo(v2)
        assertThat(song.cached.ugVersions.map { it.url }).containsExactly(result.url, v2).inOrder()
        // Found again through the most voted version's search result.
        assertThat(loader.cachedFor(withVersions)?.ugUrl).isEqualTo(v2)
    }

    @Test fun aSearchResultGivesASavedSongItsVersions() {
        load()
        val again = loader.load(withVersions) {}
        assertThat(again.cached.ugVersions).hasSize(2)
        assertThat(store.load(again.cached.id)?.ugVersions).hasSize(2)
    }

    @Test fun aWellAlignedSheetIsKeptWithoutFetchingOthers() {
        every { lrclib.search(any(), any()) } returns listOf(lrc)
        loader.load(withVersions) {}
        verify(exactly = 0) { ug.fetchTab(v2) }
    }

    @Test fun switchVersionKeepsSyncAndKey() {
        every { ug.fetchTab(v2) } returns UgTab(result.artist, result.song, v2, "[ch]A[/ch]\nOther", 2, 0, "G")
        val first = load().cached.copy(offsetMs = 400, keyShift = 2)
        val switched = loader.switchVersion(first, v2)
        assertThat(switched.ugUrl).isEqualTo(v2)
        assertThat(switched.transpose).isEqualTo(2)
        assertThat(switched.offsetMs).isEqualTo(400)
        assertThat(switched.keyShift).isEqualTo(2)
        assertThat(store.load(first.id)?.ugUrl).isEqualTo(v2)
    }

    @Test fun pickTrackPrefersTheAlbumRecording() {
        val tracks = listOf(
            YoutubeTrack("live", "Fluorescent Adolescent (Live at Glastonbury)", "", 190),
            YoutubeTrack("long", "Fluorescent Adolescent", "", 420),
            YoutubeTrack("album", "Fluorescent Adolescent", "", 178),
        )
        assertThat(SongLoader.pickTrack(tracks, "Fluorescent Adolescent", 180.0)?.videoUrl).isEqualTo("album")
        // With no lyrics to go by, the live version still loses; then YouTube's order decides.
        assertThat(SongLoader.pickTrack(tracks, "Fluorescent Adolescent", null)?.videoUrl).isEqualTo("long")
    }

    @Test fun pickTrackDoesNotPenaliseAWordInTheSongsOwnName() {
        val tracks = listOf(
            YoutubeTrack("other", "Something Else", "", 200),
            YoutubeTrack("song", "Live Forever (Remastered)", "", 276),
        )
        assertThat(SongLoader.pickTrack(tracks, "Live Forever", 275.0)?.videoUrl).isEqualTo("song")
    }

    @Test fun expectedDurationIsTheMedianOfTheSyncedEntries() {
        val entries = listOf(170.0, 180.0, 400.0).mapIndexed { i, d -> LrcCandidate(i.toLong(), duration = d, syncedLyrics = "[00:01.00]x") } +
            LrcCandidate(9, duration = 999.0, syncedLyrics = null)
        assertThat(SongLoader.expectedDurationSec(entries)).isEqualTo(180.0)
        assertThat(SongLoader.expectedDurationSec(emptyList())).isNull()
    }

    @Test fun searchesAreRememberedForTheSession() {
        loader.search("fluorescent adolescent")
        loader.search(" Fluorescent Adolescent ")
        verify(exactly = 1) { ug.search(any()) }
    }
}
