package com.kivan.chordhand.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.domain.music.MatchResult
import com.kivan.chordhand.domain.practice.PracticeSummary
import com.kivan.chordhand.ui.common.ConnectionChip
import com.kivan.chordhand.ui.theme.Palette

/** How long before a change the next chord's keys get their dots. */
private const val NEXT_HINT_MS = 1500L

@Composable
fun PlayerScreen(vm: PlayerController, connection: ConnectionState, onScan: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Toolbar(vm, view, state, connection, onScan, onBack)
        Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            ChordPanel(vm, view, state, Modifier.width(250.dp).fillMaxHeight())
            Spacer(Modifier.width(16.dp))
            LyricsPanel(view, state, Modifier.weight(1f))
        }
        val shown = if (state.chordIndex >= 0) state.chordIndex else 0
        val voicing = view.voicings.getOrNull(shown)
        val nextIndex = shown + if (state.chordIndex >= 0) 1 else 0
        val showNext = state.chordIndex >= 0 && nextIndex in view.voicings.indices &&
            view.timeline.chords[nextIndex].startMs - state.songMs <= NEXT_HINT_MS
        val next = view.voicings.getOrNull(nextIndex)?.takeIf { showNext }
        KeyboardStrip(
            leftHand = voicing?.leftHand.orEmpty(),
            rightHand = voicing?.rightHand.orEmpty(),
            nextLeftHand = next?.leftHand.orEmpty(),
            nextRightHand = next?.rightHand.orEmpty(),
            held = state.held,
            correct = state.match?.correct.orEmpty(),
            wrong = state.match?.wrong.orEmpty(),
            onTouch = vm::touchNote,
        )
    }

    state.summary?.let { SummaryDialog(it, onAgain = vm::restart, onClose = { vm.dismissSummary(); onBack() }) }
    state.error?.let {
        AlertDialog(
            onDismissRequest = onBack,
            confirmButton = { TextButton(onClick = onBack) { Text("Back") } },
            title = { Text("Can't play") },
            text = { Text(it) },
        )
    }
}

@Composable
private fun Toolbar(
    vm: PlayerController,
    view: SongView,
    state: PlayerUiState,
    connection: ConnectionState,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = onBack) { Text("←") }
        Column {
            Text(view.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull(view.artist, view.key?.let { "key $it" }).joinToString(" · "), color = Palette.TextDim,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Labeled("Speed") {
            TextButton(onClick = { vm.setSpeed(state.speed - 0.05f) }) { Text("−") }
            Text("${(state.speed * 100).toInt()}%")
            TextButton(onClick = { vm.setSpeed(state.speed + 0.05f) }) { Text("+") }
        }
        Labeled("Wait for me") { Switch(state.waitMode, vm::setWaitMode) }
        Labeled("Easy chords") { Switch(state.easy, vm::setEasy) }
        Labeled("Sync") {
            TextButton(onClick = { vm.nudgeOffset(-100) }) { Text("−") }
            Text("%+.1fs".format(state.offsetMs / 1000.0))
            TextButton(onClick = { vm.nudgeOffset(100) }) { Text("+") }
            FilledTonalButton(onClick = vm::syncNextLineNow) { Text("Next line starts now") }
        }
        if (view.lrcCount > 1) TextButton(onClick = vm::nextLyricSync) { Text("Lyrics ${state.lrcIndex + 1}/${view.lrcCount}") }
        TextButton(onClick = vm::finish) { Text("Finish") }
        ConnectionChip(connection, onScan)
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Surface).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        content()
    }
}

@Composable
private fun ChordPanel(vm: PlayerController, view: SongView, state: PlayerUiState, modifier: Modifier) {
    val chords = view.timeline.chords
    val index = state.chordIndex
    val shown = if (index >= 0) index else 0
    val nextIndex = if (index >= 0) index + 1 else 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (chords.isEmpty()) {
            Text("No chords found on this sheet", color = Palette.Wrong)
            return@Column
        }
        val nowColor = when (state.match?.result) {
            MatchResult.HIT -> Palette.Correct
            else -> Palette.Chord
        }
        Text(if (index >= 0) "NOW" else "FIRST CHORD", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
        Text(view.chords[shown].symbol, fontSize = 52.sp, fontWeight = FontWeight.Black, color = nowColor, lineHeight = 54.sp)
        if (state.waitingFor != null) {
            Text("Play it to continue", color = Palette.LeftHand, fontWeight = FontWeight.SemiBold)
        }
        view.voicings.getOrNull(shown)?.let { v ->
            Text("LH  " + v.leftHand.joinToString(" ") { Chord.midiName(it) }, color = Palette.LeftHand, fontWeight = FontWeight.SemiBold)
            Text("RH  " + v.rightHand.joinToString(" ") { Chord.midiName(it) }, color = Palette.RightHand, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        if (nextIndex in chords.indices) {
            val startsIn = (chords[nextIndex].startMs - state.songMs).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("NEXT ", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
                Text(view.chords[nextIndex].symbol, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Chord.copy(alpha = 0.7f))
                Text("  in %.1fs".format(startsIn / 1000.0), color = Palette.TextDim)
            }
            val from = if (index >= 0) chords[index].startMs else 0L
            val span = (chords[nextIndex].startMs - from).coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { ((state.songMs - from).toFloat() / span).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Chord,
                trackColor = Palette.SurfaceHigh,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::restart) { Text("⏮", fontSize = 22.sp) }
            TextButton(onClick = { vm.seekBy(-5000) }) { Text("−5s") }
            FilledTonalButton(onClick = vm::togglePlay) {
                Text(if (state.playing) "Pause" else if (state.waitingFor != null) "Skip" else "Play", fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "%d:%02d".format(state.songMs.coerceAtLeast(0) / 60_000, state.songMs.coerceAtLeast(0) / 1000 % 60),
                color = if (state.buffering) Palette.LeftHand else Palette.TextDim,
            )
        }
    }
}

@Composable
private fun SummaryDialog(summary: PracticeSummary, onAgain: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { FilledTonalButton(onClick = onAgain) { Text("Play again") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Other song") } },
        title = { Text("How it went") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Chords played: ${summary.chordsHit} of ${summary.chordsReached} (${(summary.hitShare * 100).toInt()}%)",
                    fontWeight = FontWeight.SemiBold,
                )
                summary.meanTimingErrorMs?.let {
                    val lean = when {
                        summary.earlyShare > 0.65 -> ", mostly early"
                        summary.earlyShare < 0.35 -> ", mostly late"
                        else -> ""
                    }
                    Text("Timing: ${it} ms off on average$lean")
                }
                if (summary.worstChanges.isNotEmpty()) {
                    Text("Changes to practise:", color = Palette.TextDim)
                    summary.worstChanges.forEach {
                        Text("${it.from} → ${it.to}   missed ${it.misses} of ${it.attempts}", color = Palette.LeftHand)
                    }
                } else if (summary.chordsReached > 0) {
                    Text("No change missed. Try it with Easy chords off, or faster.", color = Palette.Correct)
                }
            }
        },
        containerColor = Palette.Surface,
        titleContentColor = Color.White,
    )
}
