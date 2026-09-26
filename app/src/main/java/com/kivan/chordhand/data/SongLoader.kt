package com.kivan.chordhand.data

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class LoadStep(val label: String) {
    CHORDS("Chords (Ultimate Guitar)"),
    AUDIO("Recording (YouTube)"),
    LYRICS("Synced lyrics (LRCLIB)"),
    STREAM("Audio stream"),
}

/**
 * A song ready to play: the cached data, its aligned timeline and where the audio comes from,
 * the downloaded file when there is one, otherwise a fresh stream URL.
 */
data class LoadedSong(val cached: CachedSong, val timeline: Timeline, val audioUri: String) {
    val hasSyncedLyrics: Boolean get() = cached.lrcCandidates.isNotEmpty()
}

/**
 * Fetches songs and keeps them: once a song has been opened, opening it again reads the saved
 * JSON and the downloaded recording, with no network at all. [refresh] fetches it anew.
 */
class SongLoader(
    private val store: SongStore,
    private val ug: UltimateGuitarSource = UltimateGuitarSource(),
    private val lrclib: LrclibSource = LrclibSource(),
    private val youtube: Lazy<YoutubeAudioSource> = lazy { YoutubeAudioSource() },
    /** Runs the recording downloads, one at a time, while the song already plays from the stream. */
    private val background: Executor = Executors.newSingleThreadExecutor(),
) {
    private val searches = object : LinkedHashMap<String, List<UgSearchResult>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<UgSearchResult>>) = size > 20
    }
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    /** Remembered for the session, so going back to the same results costs nothing. */
    fun search(query: String): List<UgSearchResult> {
        val key = query.trim().lowercase()
        synchronized(searches) { searches[key] }?.let { return it }
        return ug.search(query).also { if (it.isNotEmpty()) synchronized(searches) { searches[key] = it } }
    }

    /** The saved song behind a search result, if it was opened before. */
    fun cachedFor(result: UgSearchResult): CachedSong? = store.findByUgUrl(result.url)

    /**
     * Opens a search result: from the cache when it was opened before, otherwise fetched from all
     * three services, reporting each step before it starts. Blocking.
     */
    fun load(result: UgSearchResult, onStep: (LoadStep) -> Unit): LoadedSong =
        cachedFor(result)?.let { reload(it, onStep) } ?: fetch(result, refresh = false, onStep)

    /** Fetches a saved song again from every service, recording included, keeping its sync. */
    fun refresh(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong =
        fetch(UgSearchResult(cached.artist, cached.song, rating = 0.0, votes = 0, url = cached.ugUrl), refresh = true, onStep)

    private fun fetch(result: UgSearchResult, refresh: Boolean, onStep: (LoadStep) -> Unit): LoadedSong {
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

        val saved = store.load(CachedSong.idFor(artist, song))
        val previous = saved?.takeIf { it.videoUrl == track.videoUrl }
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
        // A refresh re-downloads the recording too; a different recording must never play the old file.
        if (refresh || previous == null) store.deleteAudio(cached)
        store.save(cached)
        return withStream(cached, onStep)
    }

    /** Reopens a saved song: from the downloaded recording, or else a fresh stream URL. */
    fun reload(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        val touched = cached.copy(lastPlayedAt = System.currentTimeMillis())
        store.save(touched)
        val file = store.audioFile(touched)
        if (file.exists()) return LoadedSong(touched, timelineFor(touched), file.toURI().toString())
        return withStream(touched, onStep)
    }

    fun save(cached: CachedSong) = store.save(cached)

    fun recent(): List<CachedSong> = store.recent()

    fun isDownloaded(cached: CachedSong): Boolean = store.audioFile(cached).exists()

    /** Plays from the stream this time, and downloads the recording meanwhile for next time. */
    private fun withStream(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        onStep(LoadStep.STREAM)
        val audio = youtube.value.resolveAudio(cached.videoUrl)
        val target = store.audioFile(cached)
        if (downloading.add(target.path)) {
            background.execute {
                // Best effort: if it fails, the next open streams and tries again.
                try { youtube.value.download(audio.url, target) } catch (_: Exception) {} finally { downloading.remove(target.path) }
            }
        }
        return LoadedSong(cached, timelineFor(cached), audio.url)
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
