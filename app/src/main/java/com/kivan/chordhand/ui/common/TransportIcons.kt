package com.kivan.chordhand.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few icons the app needs, drawn on a 24×24 grid like Material's, so the app
 * doesn't need an icon library.
 */
object TransportIcons {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    val Play = icon("Play") {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }

    val Pause = icon("Pause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }

    /** Back to the start of the song: a bar and a triangle pointing left. */
    val Restart = icon("Restart") {
        moveTo(6f, 6f); lineTo(8f, 6f); lineTo(8f, 18f); lineTo(6f, 18f); close()
        moveTo(18f, 6f); lineTo(18f, 18f); lineTo(9.5f, 12f); close()
    }

    /** Fetch again: Material's circular arrow. */
    val Refresh = ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08" +
                    "c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
            ),
            fill = SolidColor(Color.Black),
        )
        .build()

    /** Jump ahead, for skipping the intro: two triangles pointing right. */
    val Forward = icon("Forward") {
        moveTo(4f, 6f); lineTo(12.5f, 12f); lineTo(4f, 18f); close()
        moveTo(13f, 6f); lineTo(21.5f, 12f); lineTo(13f, 18f); close()
    }
}
