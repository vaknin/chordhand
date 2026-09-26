package com.kivan.chordhand.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.chordhand.data.LoadStep
import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.ui.common.ConnectionChip
import com.kivan.chordhand.ui.common.TransportIcons
import com.kivan.chordhand.ui.theme.Palette

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    connection: ConnectionState,
    onScan: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        keyboard?.hide()
        vm.search()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Song, artist — e.g. fluorescent adolescent") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = search, enabled = !state.searching) { Text("Search") }
            Spacer(Modifier.width(12.dp))
            ConnectionChip(connection, onScan)
        }

        state.error?.let {
            Text(it, color = Palette.Wrong, modifier = Modifier.padding(top = 8.dp))
        }

        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            when {
                state.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.results.isNotEmpty() -> LazyColumn {
                    val saved = state.recent.associateBy { it.ugUrl }
                    items(state.results) { r ->
                        val song = saved[r.url]
                        SongRow(
                            r.song, r.artist, "${r.votes} votes · ★ %.1f".format(r.rating),
                            saved = song?.let { it.id in state.offline },
                            onRefresh = song?.let { { vm.refresh(it, onOpenPlayer) } },
                        ) { vm.open(r, onOpenPlayer) }
                    }
                }
                state.recent.isNotEmpty() -> LazyColumn {
                    item {
                        Text("Recent", style = MaterialTheme.typography.titleSmall, color = Palette.TextDim,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(state.recent) { s ->
                        SongRow(
                            s.song, s.artist, s.tonality?.let { "Key $it" } ?: "",
                            saved = s.id in state.offline,
                            onRefresh = { vm.refresh(s, onOpenPlayer) },
                        ) { vm.open(s, onOpenPlayer) }
                    }
                }
                else -> Text(
                    "Search for a song. It gets the chords from Ultimate Guitar, the recording from YouTube " +
                        "and synced lyrics from LRCLIB, then shows each hand what to play.",
                    color = Palette.TextDim,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.6f),
                )
            }
        }
    }

    state.loading?.let { LoadingDialog(it, vm::cancelLoading) }
}

/**
 * One song. [saved] is null for a song never opened, false when it is saved but the recording
 * still streams, true when it opens with no network. [onRefresh] fetches a saved song anew.
 */
@Composable
private fun SongRow(
    title: String,
    artist: String,
    detail: String,
    saved: Boolean? = null,
    onRefresh: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(artist, color = Palette.TextDim)
        Spacer(Modifier.weight(1f))
        Text(detail, color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall)
        saved?.let {
            Spacer(Modifier.width(12.dp))
            Text(if (it) "Offline" else "Saved", color = Palette.Correct.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) {
                Icon(TransportIcons.Refresh, contentDescription = "Fetch again", tint = Palette.TextDim, modifier = Modifier.size(20.dp))
            }
        }
    }
    HorizontalDivider(color = Palette.SurfaceHigh)
}

@Composable
private fun LoadingDialog(loading: LoadingState, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text(loading.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadStep.entries.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            when {
                                step in loading.done -> Text("✓", color = Palette.Correct)
                                step == loading.step -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else -> Text("·", color = Palette.TextFaint)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(step.label, color = if (step == loading.step) Palette.Text else Palette.TextDim)
                    }
                }
            }
        },
    )
}
