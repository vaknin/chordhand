package com.kivan.chordhand.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kivan.chordhand.AppGraph
import com.kivan.chordhand.data.CachedSong
import com.kivan.chordhand.data.LoadedSong
import com.kivan.chordhand.data.listen.ListenState
import com.kivan.chordhand.data.source.Http
import com.kivan.chordhand.domain.model.MidiEvent
import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.domain.music.EasiestKey
import com.kivan.chordhand.domain.music.Key
import com.kivan.chordhand.domain.music.MatchDetail
import com.kivan.chordhand.domain.music.Timeline
import com.kivan.chordhand.domain.music.TimedChord
import com.kivan.chordhand.domain.music.TimedWord
import com.kivan.chordhand.domain.music.Voicing
import com.kivan.chordhand.domain.music.VoicingPlanner
import com.kivan.chordhand.domain.music.transposed
import com.kivan.chordhand.domain.practice.PracticeSession
import com.kivan.chordhand.domain.practice.PracticeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** What is fixed for one song, sheet, lyric sync, key and difficulty; rebuilt when any of them changes. */
data class SongView(
    val title: String,
    val artist: String,
    /** The key as played, after the key shift. */
    val key: String?,
    /** The timeline in the key as played. */
    val timeline: Timeline,
    /** The chords as played: simplified in Easy mode. */
    val chords: List<Chord>,
    val voicings: List<Voicing>,
    val chordsByLine: Map<Int, List<TimedChord>>,
    val wordsByLine: Map<Int, List<TimedWord>>,
    val lrcCount: Int,
    /** Song time to jump to from the intro, or null when the song starts right away. */
    val introSkipMs: Long?,
    /** The key shift with the fewest black keys. */
    val easiestShift: Int,
    /** Which UG version of the sheet is in use (0-based), of [sheetCount]. */
    val sheetIndex: Int,
    val sheetCount: Int,
)

data class PlayerUiState(
    val songMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val speed: Float = 1f,
    val waitMode: Boolean = false,
    /** Paused by wait mode until this chord is played. */
    val waitingFor: Int? = null,
    val easy: Boolean = true,
    val offsetMs: Long = 0,
    val lrcIndex: Int = 0,
    /** Semitones from the recorded key; the audio is pitch-shifted to match. */
    val keyShift: Int = 0,
    /** Another sheet version is being fetched. */
    val loadingSheet: Boolean = false,
    /** Timing the lyrics to the recording, while it runs and once it finished this session. */
    val listen: ListenState? = null,
    /** The words are timed by what was heard in the recording, not by LRC alone. */
    val wordsHeard: Boolean = false,
    /** A passing message, such as a failed sheet switch; playing goes on. */
    val notice: String? = null,
    /** Where the recording is; [songMs] is this shifted by the lyrics sync. */
    val positionMs: Long = 0,
    /** The chord sounding now, -1 before the first. */
    val chordIndex: Int = -1,
    val held: Set<Int> = emptySet(),
    val match: MatchDetail? = null,
    /** Chords played right so far. */
    val hits: Set<Int> = emptySet(),
    val summary: PracticeSummary? = null,
    val error: String? = null,
)

/**
 * Everything behind the player screen: playback, the clock, the keys held down, wait mode and
 * scoring. Lives exactly as long as the screen; [release] stops the audio.
 */
@OptIn(UnstableApi::class)
class PlayerController(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loaded: LoadedSong = checkNotNull(AppGraph.currentSong) { "No song selected" }
    private var cached: CachedSong = loaded.cached
    /** The timeline in the recorded key; [SongView.timeline] is it after the key shift. */
    private var baseTimeline: Timeline = loaded.timeline

    private val _state = MutableStateFlow(
        PlayerUiState(
            durationMs = cached.durationSec * 1000, offsetMs = cached.offsetMs, lrcIndex = cached.lrcIndex, keyShift = cached.keyShift,
            wordsHeard = AppGraph.songLoader.isHeard(cached),
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _view = MutableStateFlow(buildView())
    val view: StateFlow<SongView> = _view.asStateFlow()

    private var session = newSession()
    private val midiHeld = mutableSetOf<Int>()
    private val touchHeld = mutableSetOf<Int>()
    /** A chord the player chose to skip past in wait mode by pressing play. */
    private var skipWaitFor: Int? = null

    private val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            // The downloaded file when there is one, the stream otherwise.
            DefaultMediaSourceFactory(DefaultDataSource.Factory(app, DefaultHttpDataSource.Factory().setUserAgent(Http.DESKTOP_USER_AGENT)))
        )
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(loaded.audioUri))
            playbackParameters = playbackFor(_state.value)
            prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = "Playback failed (${error.errorCodeName}). Reopen the song to fetch a fresh stream.") }
                }
            })
        }

    init {
        scope.launch {
            AppGraph.midi.midiEvents.collect { event ->
                when (event) {
                    is MidiEvent.NoteOn -> if (event.velocity > 0) midiHeld += event.note else midiHeld -= event.note
                    is MidiEvent.NoteOff -> midiHeld -= event.note
                    is MidiEvent.ControlChange -> Unit
                }
                tick()
            }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(16)
            }
        }
        scope.launch {
            AppGraph.songLoader.listens.collect { all ->
                val listen = all[cached.id]
                val justHeard = listen == ListenState.Done && !_state.value.wordsHeard
                _state.update { it.copy(listen = listen, wordsHeard = it.wordsHeard || listen == ListenState.Done) }
                if (justHeard) {
                    // The words now follow the recording itself; a correction made for LRC would shift them off.
                    setOffset(0)
                    baseTimeline = AppGraph.songLoader.timelineFor(cached)
                    rebuild()
                }
            }
        }
    }

    private fun buildView(): SongView {
        val s = _state.value
        val home = Key.of(cached.tonality, cached.transpose, baseTimeline.chords.map { it.chord })
        val key = home?.transpose(s.keyShift)
        val timeline = baseTimeline.transposed(s.keyShift, preferFlats = key?.usesFlats ?: false)
        val chords = timeline.chords.map { if (s.easy) it.chord.simplified() else it.chord }
        val versions = cached.ugVersions
        return SongView(
            title = cached.song,
            artist = cached.artist,
            key = key?.name,
            timeline = timeline,
            chords = chords,
            voicings = VoicingPlanner.plan(chords),
            chordsByLine = timeline.chords.groupBy { it.lineIndex },
            wordsByLine = timeline.words.groupBy { it.lineIndex },
            lrcCount = cached.lrcCandidates.size,
            introSkipMs = timeline.firstCueMs?.let { it - INTRO_LEAD_IN_MS }?.takeIf { it > 0 },
            easiestShift = EasiestKey.suggest(baseTimeline.chords.map { if (s.easy) it.chord.simplified() else it.chord }),
            sheetIndex = versions.indexOfFirst { it.url == cached.ugUrl }.coerceAtLeast(0),
            sheetCount = versions.size.coerceAtLeast(1),
        )
    }

    private fun rebuild() {
        _view.value = buildView()
        resetSession()
    }

    private fun playbackFor(s: PlayerUiState) = PlaybackParameters(s.speed, 2f.pow(s.keyShift / 12f))

    private fun resetSession() {
        session = newSession()
        _state.update { it.copy(hits = emptySet()) }
    }

    private fun newSession(): PracticeSession {
        val v = _view.value
        return PracticeSession(v.timeline, v.chords, v.timeline.chords.map { it.chord }, _state.value.easy)
    }

    private fun tick() {
        val s = _state.value
        val songMs = player.currentPosition - s.offsetMs
        val timeline = _view.value.timeline
        val held = midiHeld + touchHeld
        var waitingFor = s.waitingFor

        if (s.waitMode && waitingFor == null && player.isPlaying) {
            // Stop just before the change so the chord never sounds unplayed.
            val upcoming = timeline.chordAt(songMs + 60)
            if (upcoming >= 0 && !session.isHit(upcoming) && upcoming != skipWaitFor) {
                player.pause()
                waitingFor = upcoming
            }
        }
        val match = session.update(held, songMs, waitingFor)
        if (waitingFor != null && session.isHit(waitingFor)) {
            waitingFor = null
            player.play()
        }

        _state.update {
            it.copy(
                songMs = songMs,
                positionMs = player.currentPosition,
                durationMs = if (player.duration > 0) player.duration else it.durationMs,
                playing = player.isPlaying || player.playWhenReady,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                waitingFor = waitingFor,
                chordIndex = timeline.chordAt(songMs),
                held = held,
                match = match,
                hits = if (session.hitCount != it.hits.size) session.hitIndices() else it.hits,
            )
        }
    }

    fun togglePlay() {
        val s = _state.value
        when {
            s.waitingFor != null -> {
                skipWaitFor = s.waitingFor
                _state.update { it.copy(waitingFor = null) }
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun restart() {
        player.seekTo(0)
        resetSession()
        skipWaitFor = null
        _state.update { it.copy(waitingFor = null, summary = null) }
    }

    /** Jumps to just before the first chord or sung word, keeping play or pause as it was. */
    fun skipIntro() {
        val target = _view.value.introSkipMs ?: return
        if (target <= _state.value.songMs) return
        player.seekTo((target + _state.value.offsetMs).coerceAtLeast(0))
        tick()
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed.coerceIn(0.5f, 1f)) }
        // Speed and pitch are set together: setting the speed alone would drop the key shift.
        player.playbackParameters = playbackFor(_state.value)
    }

    fun setKeyShift(shift: Int) {
        val clamped = shift.coerceIn(EasiestKey.RANGE)
        if (clamped == _state.value.keyShift) return
        _state.update { it.copy(keyShift = clamped) }
        player.playbackParameters = playbackFor(_state.value)
        persist(cached.copy(keyShift = clamped))
        rebuild()
    }

    fun easiestKey() = setKeyShift(_view.value.easiestShift)

    /** Swaps in the next UG version of the sheet; the recording, lyrics, sync and key stay. */
    fun nextSheet() {
        val versions = cached.ugVersions
        if (versions.size < 2 || _state.value.loadingSheet) return
        val url = versions[(_view.value.sheetIndex + 1) % versions.size].url
        _state.update { it.copy(loadingSheet = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppGraph.songLoader.switchVersion(cached, url) } }
            _state.update { it.copy(loadingSheet = false) }
            result.onSuccess { sheet ->
                // Only the sheet: a sync or key change made while it loaded stays.
                persist(cached.copy(ugUrl = sheet.ugUrl, ugContent = sheet.ugContent, transpose = sheet.transpose, tonality = sheet.tonality))
                baseTimeline = AppGraph.songLoader.timelineFor(cached)
                rebuild()
            }.onFailure { e -> showNotice("Couldn't fetch another sheet: ${e.message}") }
        }
    }

    private fun showNotice(text: String) {
        _state.update { it.copy(notice = text) }
        scope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    fun setWaitMode(on: Boolean) {
        val wasWaiting = _state.value.waitingFor != null
        skipWaitFor = null
        _state.update { it.copy(waitMode = on, waitingFor = null) }
        if (wasWaiting) player.play()
    }

    fun setEasy(easy: Boolean) {
        _state.update { it.copy(easy = easy) }
        rebuild()
    }

    fun nudgeOffset(deltaMs: Long) = setOffset(_state.value.offsetMs + deltaMs)

    private fun setOffset(offsetMs: Long) {
        _state.update { it.copy(offsetMs = offsetMs) }
        persist(cached.copy(offsetMs = offsetMs))
    }

    fun nextLyricSync() {
        val count = cached.lrcCandidates.size
        if (count < 2) return
        val index = (cached.lrcIndex + 1) % count
        persist(cached.copy(lrcIndex = index, offsetMs = 0))
        _state.update { it.copy(lrcIndex = index, offsetMs = 0) }
        baseTimeline = AppGraph.songLoader.timelineFor(cached)
        rebuild()
    }

    fun touchNote(note: Int, down: Boolean) {
        if (down) touchHeld += note else touchHeld -= note
        tick()
    }

    fun finish() {
        player.pause()
        _state.update { it.copy(summary = session.summary(), waitingFor = null) }
    }

    fun dismissSummary() = _state.update { it.copy(summary = null) }

    private fun persist(updated: CachedSong) {
        cached = updated
        scope.launch(Dispatchers.IO) { runCatching { AppGraph.songLoader.save(updated) } }
    }

    fun release() {
        scope.cancel()
        player.release()
    }

    companion object {
        /** Enough time to find the keys before the first chord after skipping the intro. */
        const val INTRO_LEAD_IN_MS = 1500L
        private const val NOTICE_MS = 4000L
    }
}
