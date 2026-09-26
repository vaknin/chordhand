package com.kivan.chordhand.data

import com.kivan.chordhand.data.source.LrcCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything fetched for a song except the audio stream URL, which expires. Re-parsing and
 * re-aligning from this is instant, so the timeline itself is not stored.
 */
@Serializable
data class CachedSong(
    val artist: String,
    val song: String,
    val ugUrl: String,
    val ugContent: String,
    /** Capo plus tuning: how far the written shapes are from what sounds. */
    val transpose: Int,
    val tonality: String? = null,
    val videoUrl: String,
    val videoTitle: String,
    val durationSec: Long,
    /** Ranked best first. */
    val lrcCandidates: List<LrcCandidate>,
    val lrcIndex: Int = 0,
    val offsetMs: Long = 0,
    val lastPlayedAt: Long = 0,
) {
    val id: String get() = idFor(artist, song)

    companion object {
        fun idFor(artist: String, song: String): String =
            "$artist-$song".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}

/** One JSON file per song under the app's files directory. */
class SongStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        dir.mkdirs()
    }

    fun save(song: CachedSong) {
        val tmp = File(dir, "${song.id}.json.tmp")
        tmp.writeText(json.encodeToString(CachedSong.serializer(), song))
        tmp.renameTo(File(dir, "${song.id}.json"))
    }

    fun load(id: String): CachedSong? =
        File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { json.decodeFromString(CachedSong.serializer(), it.readText()) }.getOrNull() }

    fun recent(): List<CachedSong> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(CachedSong.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.lastPlayedAt }
}
