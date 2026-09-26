package com.kivan.chordhand.data

import com.kivan.chordhand.data.source.AudioStreamUrl
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.SourceException
import com.kivan.chordhand.data.source.UgSearchResult
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.data.source.YoutubeAudioSource
import com.kivan.chordhand.data.source.YoutubeTrack
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.LrcParser
import com.kivan.chordhand.domain.music.Timeline
import com.kivan.chordhand.domain.music.TimelineAligner

enum class LoadStep(val label: String) {
    CHORDS("Chords (Ultimate Guitar)"),
    AUDIO("Recording (YouTube)"),
    LYRICS("Synced lyrics (LRCLIB)"),
    STREAM("Audio stream"),
}

/** A song ready to play: the cached data, its aligned timeline and a fresh stream URL. */
data class LoadedSong(val cached: CachedSong, val timeline: Timeline, val audio: AudioStreamUrl) {
    val hasSyncedLyrics: Boolean get() = cached.lrcCandidates.isNotEmpty()
}

class SongLoader(
    private val store: SongStore,
    private val ug: UltimateGuitarSource = UltimateGuitarSource(),
    private val lrclib: LrclibSource = LrclibSource(),
    private val youtube: Lazy<YoutubeAudioSource> = lazy { YoutubeAudioSource() },
) {
    fun search(query: String): List<UgSearchResult> = ug.search(query)

    /** Fetches everything for a search result, reporting each step before it starts. Blocking. */
    fun load(result: UgSearchResult, onStep: (LoadStep) -> Unit): LoadedSong {
        onStep(LoadStep.CHORDS)
        val tab = ug.fetchTab(result.url)
        val artist = tab.artist.ifBlank { result.artist }
        val song = tab.song.ifBlank { result.song }

        onStep(LoadStep.AUDIO)
        val track = pickTrack(youtube.value.search(artist, song), song)
            ?: throw SourceException("No recording of \"$song\" found on YouTube")

        onStep(LoadStep.LYRICS)
        val candidates = runCatching { LrclibSource.rank(lrclib.search(artist, song), track.durationSec.toDouble()) }
            .getOrDefault(emptyList())

        val previous = store.load(CachedSong.idFor(artist, song))?.takeIf { it.videoUrl == track.videoUrl }
        val cached = CachedSong(
            artist = artist,
            song = song,
            ugUrl = tab.url,
            ugContent = tab.content,
            transpose = tab.soundingTranspose,
            tonality = tab.tonality,
            videoUrl = track.videoUrl,
            videoTitle = track.title,
            durationSec = track.durationSec,
            lrcCandidates = candidates,
            // Keep a sync correction made on an earlier visit to the same recording.
            offsetMs = previous?.offsetMs ?: 0,
            lrcIndex = previous?.lrcIndex?.takeIf { it < candidates.size } ?: 0,
            lastPlayedAt = System.currentTimeMillis(),
        )
        store.save(cached)
        return withStream(cached, onStep)
    }

    /** Reopens a cached song: only the stream URL is fetched again. */
    fun reload(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        val touched = cached.copy(lastPlayedAt = System.currentTimeMillis())
        store.save(touched)
        return withStream(touched, onStep)
    }

    fun save(cached: CachedSong) = store.save(cached)

    fun recent(): List<CachedSong> = store.recent()

    private fun withStream(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        onStep(LoadStep.STREAM)
        val audio = youtube.value.resolveAudio(cached.videoUrl)
        return LoadedSong(cached, timelineFor(cached), audio)
    }

    companion object {
        fun timelineFor(cached: CachedSong): Timeline {
            val sheet = ChordSheetParser.parse(cached.ugContent, cached.transpose)
            val lrc = cached.lrcCandidates.getOrNull(cached.lrcIndex)?.syncedLyrics?.let(LrcParser::parse).orEmpty()
            return TimelineAligner.align(sheet, lrc, cached.durationSec * 1000)
        }

        /** The first result whose title names the song, so covers and reaction videos lose. */
        internal fun pickTrack(tracks: List<YoutubeTrack>, song: String): YoutubeTrack? {
            val wanted = song.lowercase().filter { it.isLetterOrDigit() }
            return tracks.firstOrNull { it.title.lowercase().filter { c -> c.isLetterOrDigit() }.contains(wanted) && it.durationSec > 30 }
                ?: tracks.firstOrNull()
        }
    }
}
