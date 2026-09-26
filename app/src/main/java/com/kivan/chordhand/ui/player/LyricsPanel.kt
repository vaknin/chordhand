package com.kivan.chordhand.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.chordhand.ui.theme.Palette

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 19.sp, lineHeight = 22.sp)

/**
 * The sheet around the current moment, chords over lyrics in a fixed-width font so they line up
 * as on the chord sheet. The chord sounding now is highlighted; past chords turn green when
 * they were played and red when not; sung words light up.
 */
@Composable
fun LyricsPanel(view: SongView, state: PlayerUiState, modifier: Modifier = Modifier) {
    val lines = view.timeline.sheet.lines
    if (lines.isEmpty()) return
    val current = view.timeline.lineAt(state.songMs)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (li in (current - 1)..(current + 3)) {
            if (li !in lines.indices) continue
            val emphasis = when {
                li < current -> 0.35f
                li == current -> 1f
                else -> 0.7f
            }
            Text(chordRow(view, state, li, emphasis), style = Mono, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
            Text(lyricRow(view, state, li, emphasis), style = Mono, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
        }
    }
}

private fun chordRow(view: SongView, state: PlayerUiState, li: Int, emphasis: Float): AnnotatedString = buildAnnotatedString {
    for (tc in view.chordsByLine[li].orEmpty()) {
        val column = maxOf(tc.column, if (length == 0) 0 else length + 1)
        append(" ".repeat(column - length))
        val color = when {
            tc.index == state.chordIndex -> Palette.Chord
            tc.index < state.chordIndex && tc.index in state.hits -> Palette.Correct
            tc.index < state.chordIndex -> Palette.Wrong
            else -> Palette.Chord.copy(alpha = 0.75f)
        }
        val current = tc.index == state.chordIndex
        pushStyle(
            SpanStyle(
                color = color.copy(alpha = color.alpha * if (current) 1f else emphasis),
                fontWeight = if (current) FontWeight.Black else FontWeight.Bold,
                background = if (current) Palette.Chord.copy(alpha = 0.18f) else Color.Transparent,
            )
        )
        append(view.chords[tc.index].symbol)
        pop()
    }
}

private fun lyricRow(view: SongView, state: PlayerUiState, li: Int, emphasis: Float): AnnotatedString = buildAnnotatedString {
    val line = view.timeline.sheet.lines[li]
    val lyric = line.lyric
    if (lyric == null) {
        pushStyle(SpanStyle(color = Palette.TextFaint.copy(alpha = emphasis)))
        append(line.section?.let { "[$it]" } ?: "♪")
        pop()
        return@buildAnnotatedString
    }
    append(lyric)
    addStyle(SpanStyle(color = Palette.TextDim.copy(alpha = emphasis)), 0, lyric.length)
    for (w in view.wordsByLine[li].orEmpty()) {
        val sung = w.ms != null && w.ms <= state.songMs
        if (sung) addStyle(SpanStyle(color = Palette.Text.copy(alpha = emphasis)), w.start, w.end)
    }
}
