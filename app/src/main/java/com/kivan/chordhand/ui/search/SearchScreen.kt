package com.kivan.chordhand.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.chordhand.data.LoadStep
import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.ui.common.ConnectionChip
import com.kivan.chordhand.ui.theme.Palette

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    connection: ConnectionState,
    onScan: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Song, artist — e.g. fluorescent adolescent") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = vm::search, enabled = !state.searching) { Text("Search") }
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
                    items(state.results) { r ->
                        SongRow(r.song, r.artist, "${r.votes} votes · ★ %.1f".format(r.rating)) {
                            vm.open(r, onOpenPlayer)
                        }
                    }
                }
                state.recent.isNotEmpty() -> LazyColumn {
                    item {
                        Text("Recent", style = MaterialTheme.typography.titleSmall, color = Palette.TextDim,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(state.recent) { s ->
                        SongRow(s.song, s.artist, s.tonality?.let { "Key $it" } ?: "") { vm.open(s, onOpenPlayer) }
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

@Composable
private fun SongRow(title: String, artist: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(artist, color = Palette.TextDim)
            Spacer(Modifier.weight(1f))
            Text(detail, color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall)
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
