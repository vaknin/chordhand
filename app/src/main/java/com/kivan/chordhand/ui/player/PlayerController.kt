package com.kivan.chordhand.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kivan.chordhand.AppGraph
import com.kivan.chordhand.data.CachedSong
import com.kivan.chordhand.data.LoadedSong
import com.kivan.chordhand.data.SongLoader
import com.kivan.chordhand.data.source.Http
import com.kivan.chordhand.domain.model.MidiEvent
import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.domain.music.MatchDetail
import com.kivan.chordhand.domain.music.Timeline
import com.kivan.chordhand.domain.music.TimedChord
import com.kivan.chordhand.domain.music.TimedWord
import com.kivan.chordhand.domain.music.Voicing
import com.kivan.chordhand.domain.music.VoicingPlanner
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

/** What is fixed for one song, lyric sync and difficulty; rebuilt when any of them changes. */
data class SongView(
    val title: String,
    val artist: String,
    val key: String?,
    val timeline: Timeline,
    /** The chords as played: simplified in Easy mode. */
    val chords: List<Chord>,
    val voicings: List<Voicing>,
    val chordsByLine: Map<Int, List<TimedChord>>,
    val wordsByLine: Map<Int, List<TimedWord>>,
    val lrcCount: Int,
    /** Song time to jump to from the intro, or null when the song starts right away. */
    val introSkipMs: Long?,
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

    private val _state = MutableStateFlow(PlayerUiState(durationMs = cached.durationSec * 1000, offsetMs = cached.offsetMs, lrcIndex = cached.lrcIndex))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _view = MutableStateFlow(buildView(loaded.timeline, easy = true))
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
    }

    private fun buildView(timeline: Timeline, easy: Boolean): SongView {
        val chords = timeline.chords.map { if (easy) it.chord.simplified() else it.chord }
        return SongView(
            title = cached.song,
            artist = cached.artist,
            key = cached.tonality,
            timeline = timeline,
            chords = chords,
            voicings = VoicingPlanner.plan(chords),
            chordsByLine = timeline.chords.groupBy { it.lineIndex },
            wordsByLine = timeline.words.groupBy { it.lineIndex },
            lrcCount = cached.lrcCandidates.size,
            introSkipMs = timeline.firstCueMs?.let { it - INTRO_LEAD_IN_MS }?.takeIf { it > 0 },
        )
    }

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
        val clamped = speed.coerceIn(0.5f, 1f)
        player.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    fun setWaitMode(on: Boolean) {
        val wasWaiting = _state.value.waitingFor != null
        skipWaitFor = null
        _state.update { it.copy(waitMode = on, waitingFor = null) }
        if (wasWaiting) player.play()
    }

    fun setEasy(easy: Boolean) {
        _state.update { it.copy(easy = easy) }
        _view.value = buildView(_view.value.timeline, easy)
        resetSession()
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
        _view.value = buildView(SongLoader.timelineFor(cached), _state.value.easy)
        resetSession()
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
    }
}
