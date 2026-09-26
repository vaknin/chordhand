package com.kivan.chordhand

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.chordhand.ui.player.PlayerController
import com.kivan.chordhand.ui.player.PlayerScreen
import com.kivan.chordhand.ui.search.SearchScreen
import com.kivan.chordhand.ui.search.SearchViewModel
import com.kivan.chordhand.ui.theme.Fp10Theme
import com.kivan.chordhand.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val searchVm: SearchViewModel by viewModels()

    private val blePermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) AppGraph.midi.startScanning()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        // The phone sits on the music stand for the whole song.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Every pixel of height goes to the sheet and the keys; swipe from an edge to see the bars.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            Fp10Theme {
                val connection by AppGraph.midi.connectionState.collectAsStateWithLifecycle()
                var playing by rememberSaveable { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize(), color = Palette.Background, contentColor = Palette.Text) {
                  Box(Modifier.fillMaxSize().displayCutoutPadding()) {
                    if (playing && AppGraph.currentSong != null) {
                        val controller = remember { PlayerController(application) }
                        DisposableEffect(controller) { onDispose { controller.release() } }
                        val back = {
                            playing = false
                            searchVm.refreshRecent()
                        }
                        BackHandler(onBack = back)
                        PlayerScreen(controller, connection, ::scan, back)
                    } else {
                        SearchScreen(searchVm, connection, ::scan) { playing = true }
                    }
                  }
                }
            }
        }
        scan()
    }

    private fun scan() {
        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) AppGraph.midi.startScanning() else requestPermissions.launch(missing.toTypedArray())
    }
}
