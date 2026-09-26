package com.kivan.chordhand.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.chordhand.domain.music.Chord
import com.kivan.chordhand.ui.theme.Palette

private const val LOW = 36 // C2
private const val HIGH = 84 // C6
private val BLACK_PCS = setOf(1, 3, 6, 8, 10)
private fun isBlack(note: Int) = note % 12 in BLACK_PCS

/** Key rectangles for a width and height, white keys first so black keys draw (and hit-test) on top. */
private class KeyGeometry(size: Size) {
    val whites = (LOW..HIGH).filter { !isBlack(it) }
    val whiteWidth = size.width / whites.size
    val rects: Map<Int, Rect> = buildMap {
        whites.forEachIndexed { i, n -> put(n, Rect(i * whiteWidth, 0f, (i + 1) * whiteWidth, size.height)) }
        val bw = whiteWidth * 0.62f
        for (n in LOW..HIGH) if (isBlack(n)) {
            val leftWhite = whites.indexOf(n - 1)
            val x = (leftWhite + 1) * whiteWidth
            put(n, Rect(x - bw / 2, 0f, x + bw / 2, size.height * 0.62f))
        }
    }

    fun noteAt(p: Offset): Int? =
        rects.entries.firstOrNull { (n, r) -> isBlack(n) && r.contains(p) }?.key
            ?: rects.entries.firstOrNull { (n, r) -> !isBlack(n) && r.contains(p) }?.key
}

/**
 * The keys from C2 to C6: the left-hand and right-hand notes to play, dots on the next chord's
 * notes, and the keys held down, green when they belong to the chord and red when not. Touching
 * the keys plays notes too, for trying the app without the piano.
 */
@Composable
fun KeyboardStrip(
    leftHand: List<Int>,
    rightHand: List<Int>,
    nextLeftHand: List<Int>,
    nextRightHand: List<Int>,
    held: Set<Int>,
    correct: Set<Int>,
    wrong: Set<Int>,
    onTouch: (note: Int, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val measurer = rememberTextMeasurer()
    val touch = rememberUpdatedState(onTouch)
    val labelStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                val geometry = KeyGeometry(Size(size.width.toFloat(), size.height.toFloat()))
                val down = mutableMapOf<PointerId, Int>()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            val before = down[change.id]
                            val now = if (change.pressed) geometry.noteAt(change.position) else null
                            if (before != now) {
                                before?.let { touch.value(it, false) }
                                now?.let { touch.value(it, true) }
                                if (now == null) down.remove(change.id) else down[change.id] = now
                            }
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val g = KeyGeometry(size)
        fun fill(n: Int): Color = when {
            n in wrong -> Palette.Wrong
            n in correct -> Palette.Correct
            n in held -> Color(0xFF9E9E9E)
            n in leftHand -> Palette.LeftHand
            n in rightHand -> Palette.RightHand
            isBlack(n) -> Color(0xFF1C1C1F)
            else -> Color(0xFFE9E9EC)
        }
        for (pass in 0..1) for ((n, r) in g.rects) {
            if (isBlack(n) != (pass == 1)) continue
            drawRoundRect(fill(n), r.topLeft + Offset(1f, 0f), Size(r.width - 2f, r.height), CornerRadius(6f, 6f))
            val hint = when (n) {
                in nextLeftHand -> Palette.LeftHand
                in nextRightHand -> Palette.RightHand
                else -> null
            }
            if (hint != null && n !in leftHand && n !in rightHand) {
                drawCircle(hint, radius = r.width * 0.18f, center = Offset(r.center.x, r.bottom - r.width * 0.35f))
            }
            val label = when {
                n in leftHand || n in rightHand -> Chord.noteName(n % 12)
                n % 12 == 0 -> Chord.midiName(n)
                else -> null
            }
            label?.let { drawLabel(measurer, it, r, labelStyle, bright = n in leftHand || n in rightHand, black = isBlack(n)) }
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    r: Rect,
    style: TextStyle,
    bright: Boolean,
    black: Boolean,
) {
    val color = when {
        bright -> Color(0xFF101114)
        black -> Color(0xFFBBBBBB)
        else -> Color(0xFF8A8F99)
    }
    val layout = measurer.measure(text, style.copy(color = color))
    val y = if (bright) r.bottom - layout.size.height - r.width * 0.6f else r.bottom - layout.size.height - 6f
    drawText(layout, topLeft = Offset(r.center.x - layout.size.width / 2f, y))
}
