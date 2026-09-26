package com.kivan.chordhand.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.ui.theme.Palette

/** Piano connection status; tapping it scans again when not connected. */
@Composable
fun ConnectionChip(state: ConnectionState, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        is ConnectionState.Connected -> state.deviceName to Palette.Correct
        ConnectionState.Scanning -> "Looking for the piano…" to Palette.LeftHand
        ConnectionState.BluetoothOff -> "Bluetooth is off" to Palette.Wrong
        ConnectionState.Disconnected -> "Connect piano" to Palette.TextDim
        is ConnectionState.Error -> "Retry: ${state.message}" to Palette.Wrong
    }
    AssistChip(
        onClick = { if (state !is ConnectionState.Connected) onScan() },
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
        modifier = modifier,
    )
}
