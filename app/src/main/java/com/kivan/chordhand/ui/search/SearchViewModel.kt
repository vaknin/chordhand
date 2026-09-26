package com.kivan.chordhand.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kivan.chordhand.AppGraph
import com.kivan.chordhand.data.CachedSong
import com.kivan.chordhand.data.LoadStep
import com.kivan.chordhand.data.LoadedSong
import com.kivan.chordhand.data.source.UgSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoadingState(val title: String, val step: LoadStep?, val done: Set<LoadStep>)

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<UgSearchResult> = emptyList(),
    val recent: List<CachedSong> = emptyList(),
    /** Ids of the saved songs whose recording is downloaded, so they open with no network. */
    val offline: Set<String> = emptySet(),
    val loading: LoadingState? = null,
    val error: String? = null,
)

private const val LOADING_DIALOG_DELAY_MS = 250L

class SearchViewModel : ViewModel() {
    private val loader = AppGraph.songLoader
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        refreshRecent()
    }

    fun refreshRecent() {
        viewModelScope.launch {
            val (recent, offline) = withContext(Dispatchers.IO) {
                val recent = runCatching { loader.recent() }.getOrDefault(emptyList())
                recent to recent.filter(loader::isDownloaded).mapTo(HashSet()) { it.id }
            }
            _state.update { it.copy(recent = recent, offline = offline) }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q, error = null) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, results = emptyList()) }
            val result = withContext(Dispatchers.IO) { runCatching { loader.search(q) } }
            _state.update {
                it.copy(
                    searching = false,
                    results = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { e -> "Search failed: ${e.message}" }
                        ?: if (result.getOrNull().isNullOrEmpty()) "No chord sheets found for \"$q\"" else null,
                )
            }
        }
    }

    fun open(result: UgSearchResult, onReady: () -> Unit) =
        load("${result.song} – ${result.artist}", onReady) { step -> loader.load(result, step) }

    fun open(song: CachedSong, onReady: () -> Unit) =
        load("${song.song} – ${song.artist}", onReady) { step -> loader.reload(song, step) }

    /** Fetches a saved song again from every service, for when the sheet or recording is wrong. */
    fun refresh(song: CachedSong, onReady: () -> Unit) =
        load("${song.song} – ${song.artist}", onReady) { step -> loader.refresh(song, step) }

    private fun load(title: String, onReady: () -> Unit, block: ((LoadStep) -> Unit) -> LoadedSong) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(error = null) }
            // A saved song opens in a blink; only show the steps when there is something to wait for.
            val dialog = launch {
                delay(LOADING_DIALOG_DELAY_MS)
                _state.update { it.copy(loading = it.loading ?: LoadingState(title, null, emptySet())) }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    block { step ->
                        // Steps before this one were done, or came from the cache.
                        val loading = LoadingState(title, step, LoadStep.entries.filter { it < step }.toSet())
                        _state.update { it.copy(loading = loading) }
                    }
                }
            }
            dialog.cancel()
            result.onSuccess { song ->
                AppGraph.currentSong = song
                _state.update { it.copy(loading = null) }
                onReady()
            }.onFailure { e ->
                _state.update { it.copy(loading = null, error = "Couldn't load $title: ${e.message}") }
            }
        }
    }

    fun cancelLoading() {
        job?.cancel()
        _state.update { it.copy(loading = null) }
    }
}
