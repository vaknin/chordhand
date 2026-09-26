package com.kivan.chordhand.data

import com.kivan.chordhand.data.listen.HeardLyrics
import com.kivan.chordhand.data.listen.ListenState
import com.kivan.chordhand.data.listen.LyricListener
import com.kivan.chordhand.data.source.LrcCandidate
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.SourceException
import com.kivan.chordhand.data.source.UgSearchResult
import com.kivan.chordhand.data.source.UgTab
import com.kivan.chordhand.data.source.UgVersion
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.data.source.YoutubeAudioSource
import com.kivan.chordhand.data.source.YoutubeTrack
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.HeardWord
import com.kivan.chordhand.domain.music.LrcParser
import com.kivan.chordhand.domain.music.Timeline
import com.kivan.chordhand.domain.music.TimelineAligner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/** In the order they run: the loading dialog marks every step before the current one done. */
enum class LoadStep(val label: String) {
    CHORDS("Chords (Ultimate Guitar)"),
    LYRICS("Synced lyrics (LRCLIB)"),
    AUDIO("Recording (YouTube)"),
    STREAM("Audio stream"),
}

/** A recording being saved for offline use. Finished downloads have no entry. */
sealed interface DownloadState {
    data class Running(val fraction: Float) : DownloadState
    data object Failed : DownloadState
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
    /** Times the lyrics to each downloaded recording; null leaves them timed by LRC alone. */
    private val listener: LyricListener? = null,
    private val listening: Executor = Executors.newSingleThreadExecutor(),
) {
    private val searches = object : LinkedHashMap<String, List<UgSearchResult>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<UgSearchResult>>) = size > 20
    }
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** The recordings downloading or failed, by song id. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val _listens = MutableStateFlow<Map<String, ListenState>>(emptyMap())
    /** Songs whose lyrics are being timed to the recording, or were this session, by song id. */
    val listens: StateFlow<Map<String, ListenState>> = _listens.asStateFlow()

    /** Remembered for the session, so going back to the same results costs nothing. */
    fun search(query: String): List<UgSearchResult> {
        val key = query.trim().lowercase()
        synchronized(searches) { searches[key] }?.let { return it }
        return ug.search(query).also { if (it.isNotEmpty()) synchronized(searches) { searches[key] = it } }
    }

    /** The saved song behind a search result, if it was opened before. */
    fun cachedFor(result: UgSearchResult): CachedSong? = store.findByUgUrl(result.url, result.artist, result.song)

    /**
     * Opens a search result: from the cache when it was opened before, otherwise fetched from all
     * three services, reporting each step before it starts. Blocking.
     */
    fun load(result: UgSearchResult, onStep: (LoadStep) -> Unit): LoadedSong {
        // A search result brings the current list of sheet versions; songs saved before had none.
        val cached = cachedFor(result)?.let { if (result.versions.isEmpty()) it else it.copy(ugVersions = result.versions) }
        return cached?.let { reload(it, onStep) } ?: fetch(result, refresh = false, onStep)
    }

    /**
     * Fetches a saved song again from every service, recording included, keeping its sync, key
     * and the sheet version it uses.
     */
    fun refresh(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong =
        fetch(UgSearchResult(cached.artist, cached.song, rating = 0.0, votes = 0, url = cached.ugUrl, versions = cached.ugVersions), refresh = true, onStep)

    private fun fetch(result: UgSearchResult, refresh: Boolean, onStep: (LoadStep) -> Unit): LoadedSong {
        onStep(LoadStep.CHORDS)
        var tab = ug.fetchTab(result.url)
        val artist = tab.artist.ifBlank { result.artist }
        val song = tab.song.ifBlank { result.song }
        val saved = store.load(CachedSong.idFor(artist, song))

        // Lyrics first: their length tells the album recording from live versions and long cuts.
        onStep(LoadStep.LYRICS)
        val lyrics = runCatching { lrclib.search(artist, song) }.getOrDefault(emptyList())

        onStep(LoadStep.AUDIO)
        val track = pickTrack(youtube.value.search(artist, song), song, expectedDurationSec(lyrics))
            ?: throw SourceException("No recording of \"$song\" found on YouTube")
        val candidates = LrclibSource.rank(lyrics, track.durationSec.toDouble())

        val versions = result.versions.ifEmpty { saved?.ugVersions.orEmpty() }
        // On a refresh the sheet stays the one the player chose.
        if (!refresh) tab = bestSheet(tab, versions, candidates.firstOrNull(), track.durationSec)

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
            // The key suits the player's hands, whatever the recording.
            keyShift = saved?.keyShift ?: 0,
            ugVersions = versions,
        )
        // A refresh re-downloads the recording too; a different recording must never play the old file.
        if (refresh || previous == null) store.deleteAudio(cached)
        store.save(cached)
        return withStream(cached, onStep)
    }

    /**
     * The most voted sheet, unless it lines up badly with the lyrics: then the next versions are
     * tried and the one with the most chords anchored to a sung word wins.
     */
    private fun bestSheet(top: UgTab, versions: List<UgVersion>, lrc: LrcCandidate?, durationSec: Long): UgTab {
        val lyrics = lrc?.syncedLyrics ?: return top
        fun score(tab: UgTab): Double {
            val timeline = align(tab.content, tab.soundingTranspose, lyrics, durationSec)
            return if (timeline.chords.isEmpty()) -1.0 else timeline.anchoredShare
        }
        var best = top
        var bestScore = score(top)
        if (bestScore >= GOOD_ALIGNMENT) return top
        for (v in versions.filter { it.url != top.url }.take(EXTRA_VERSIONS_TRIED)) {
            val tab = runCatching { ug.fetchTab(v.url) }.getOrNull() ?: continue
            val s = score(tab)
            if (s > bestScore) {
                best = tab
                bestScore = s
            }
        }
        return best
    }

    /** Puts another UG version of the sheet in place of the current one; the rest stays. Blocking. */
    fun switchVersion(cached: CachedSong, url: String): CachedSong {
        val tab = ug.fetchTab(url)
        return cached.copy(ugUrl = url, ugContent = tab.content, transpose = tab.soundingTranspose, tonality = tab.tonality)
            .also(store::save)
    }

    /** Reopens a saved song: from the downloaded recording, or else a fresh stream URL. */
    fun reload(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        val touched = cached.copy(lastPlayedAt = System.currentTimeMillis())
        store.save(touched)
        val file = store.audioFile(touched)
        if (file.exists()) {
            listenIfNeeded(touched)
            return LoadedSong(touched, timelineFor(touched), file.toURI().toString())
        }
        return withStream(touched, onStep)
    }

    fun save(cached: CachedSong) = store.save(cached)

    fun recent(): List<CachedSong> = store.recent()

    fun isDownloaded(cached: CachedSong): Boolean = store.audioFile(cached).exists()

    /** Whether the song's words are timed by listening to its recording. */
    fun isHeard(cached: CachedSong): Boolean = store.loadHeard(cached) != null

    /** Plays from the stream this time, and downloads the recording meanwhile for next time. */
    private fun withStream(cached: CachedSong, onStep: (LoadStep) -> Unit): LoadedSong {
        onStep(LoadStep.STREAM)
        val audio = youtube.value.resolveAudio(cached.videoUrl)
        val target = store.audioFile(cached)
        val id = cached.id
        val start = synchronized(_downloads) {
            (_downloads.value[id] !is DownloadState.Running).also { if (it) _downloads.update { m -> m + (id to DownloadState.Running(0f)) } }
        }
        if (start) {
            background.execute {
                // Best effort: if it fails, the next open streams and tries again.
                try {
                    youtube.value.download(audio.url, target) { done, total ->
                        _downloads.update { it + (id to DownloadState.Running(done.toFloat() / total)) }
                    }
                    _downloads.update { it - id }
                    listenIfNeeded(cached)
                } catch (_: Exception) {
                    _downloads.update { it + (id to DownloadState.Failed) }
                }
            }
        }
        return LoadedSong(cached, timelineFor(cached), audio.url)
    }

    /** The timeline, with the words timed to the recording once it has been listened to. */
    fun timelineFor(cached: CachedSong): Timeline = timelineFor(cached, store.loadHeard(cached)?.words.orEmpty())

    /**
     * Listens to the downloaded recording in the background, once per recording, to hear when
     * each word is sung. The sync correction is reset then: it was for the LRC timing.
     */
    private fun listenIfNeeded(cached: CachedSong) {
        val listener = listener ?: return
        val audio = store.audioFile(cached)
        if (!audio.exists() || store.loadHeard(cached) != null) return
        val id = cached.id
        val start = synchronized(_listens) {
            val s = _listens.value[id]
            (s !is ListenState.FetchingModel && s !is ListenState.Listening)
                .also { if (it) _listens.update { m -> m + (id to ListenState.Listening(0f)) } }
        }
        if (!start) return
        listening.execute {
            try {
                val words = listener.listen(
                    audio, promptFor(cached),
                    onModel = { f -> _listens.update { it + (id to ListenState.FetchingModel(f)) } },
                    onProgress = { f -> _listens.update { it + (id to ListenState.Listening(f)) } },
                )
                store.saveHeard(cached, HeardLyrics(cached.videoUrl, words))
                store.load(id)?.let { store.save(it.copy(offsetMs = 0)) }
                _listens.update { it + (id to ListenState.Done) }
            } catch (_: Exception) {
                _listens.update { it + (id to ListenState.Failed) }
            }
        }
    }

    companion object {
        /** From this share of chords anchored to a sung word, other sheet versions aren't tried. */
        private const val GOOD_ALIGNMENT = 0.6
        private const val EXTRA_VERSIONS_TRIED = 2

        fun timelineFor(cached: CachedSong, heard: List<HeardWord>): Timeline =
            align(cached.ugContent, cached.transpose, cached.lrcCandidates.getOrNull(cached.lrcIndex)?.syncedLyrics, cached.durationSec, heard)

        private fun align(ugContent: String, transpose: Int, syncedLyrics: String?, durationSec: Long, heard: List<HeardWord> = emptyList()): Timeline {
            val sheet = ChordSheetParser.parse(ugContent, transpose)
            val lrc = syncedLyrics?.let(LrcParser::parse).orEmpty()
            return TimelineAligner.align(sheet, lrc, durationSec * 1000, heard)
        }

        /**
         * The words the recording likely contains, to prompt speech recognition with: the synced
         * lyrics' distinct lines, or the sheet's lyrics without them.
         */
        internal fun promptFor(cached: CachedSong): String {
            val lrc = cached.lrcCandidates.getOrNull(cached.lrcIndex)?.syncedLyrics?.let(LrcParser::parse).orEmpty()
            val lines = lrc.map { it.text }.ifEmpty {
                ChordSheetParser.parse(cached.ugContent, cached.transpose).lines.mapNotNull { it.lyric }
            }
            return lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
        }

        /** The median length of the synced lyrics entries: what the album recording should last. */
        internal fun expectedDurationSec(lyrics: List<LrcCandidate>): Double? {
            val durations = lyrics.filter { !it.syncedLyrics.isNullOrBlank() && it.duration > 0 }.map { it.duration }.sorted()
            return durations.getOrNull(durations.size / 2)
        }

        /** Words in a video title that mark something other than the album recording. */
        private val OTHER_TAKE =
            Regex("\\b(live|cover|remix|sped up|slowed|karaoke|instrumental|acoustic|extended|8d|reverb|nightcore)\\b")

        /**
         * The likeliest album recording: the title names the song, has no word like "live" or
         * "cover" that the song's own name lacks, and lasts close to [expectedSec]. Ties go to the
         * earlier result, so YouTube's own ranking still counts.
         */
        internal fun pickTrack(tracks: List<YoutubeTrack>, song: String, expectedSec: Double? = null): YoutubeTrack? {
            fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val wanted = letters(song)
            val songTakes = OTHER_TAKE.findAll(song.lowercase()).map { it.value }.toSet()
            fun cost(t: YoutubeTrack): Double {
                val title = t.title.lowercase()
                val named = if (letters(title).contains(wanted)) 0.0 else 100.0
                val otherTakes = OTHER_TAKE.findAll(title).map { it.value }.filter { it !in songTakes }.distinct().count() * 10.0
                // A point per 10 s off.
                val length = expectedSec?.let { abs(t.durationSec - it) / 10 } ?: 0.0
                return named + otherTakes + length
            }
            return tracks.filter { it.durationSec > 30 }.minByOrNull(::cost) ?: tracks.firstOrNull()
        }
    }
}
