package com.kivan.chordhand.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF101114)
    val Surface = Color(0xFF1A1C21)
    val SurfaceHigh = Color(0xFF252830)
    val Text = Color(0xFFECEDEF)
    val TextDim = Color(0xFF8A8F99)
    val TextFaint = Color(0xFF555A63)
    /** Left hand targets. */
    val LeftHand = Color(0xFFFFB74D)
    /** Right hand targets. */
    val RightHand = Color(0xFF4DD0E1)
    val Correct = Color(0xFF66BB6A)
    val Wrong = Color(0xFFEF5350)
    val Chord = Color(0xFFFFD54F)
}

@Composable
fun Fp10Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.RightHand,
            secondary = Palette.LeftHand,
            background = Palette.Background,
            surface = Palette.Surface,
            surfaceVariant = Palette.SurfaceHigh,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}
