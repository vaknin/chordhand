package com.kivan.chordhand

import android.content.Context
import com.kivan.chordhand.data.LoadedSong
import com.kivan.chordhand.data.SongLoader
import com.kivan.chordhand.data.SongStore
import com.kivan.chordhand.data.midi.BleMidiManagerDataSource
import com.kivan.chordhand.data.midi.MidiRepositoryImpl
import com.kivan.chordhand.domain.repository.MidiRepository
import java.io.File

/** App-wide singletons; no DI framework for an app this size. */
object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    val midi: MidiRepository by lazy { MidiRepositoryImpl(BleMidiManagerDataSource(appContext)) }

    val songLoader: SongLoader by lazy { SongLoader(SongStore(File(appContext.filesDir, "songs"))) }

    /** The song handed from the search screen to the player. */
    @Volatile var currentSong: LoadedSong? = null
}
