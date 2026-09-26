package com.kivan.chordhand.data

import com.kivan.chordhand.data.listen.HeardLyrics
import com.kivan.chordhand.data.source.LrcCandidate
import com.kivan.chordhand.data.source.UgVersion
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
    /** Semitones the player moved the song to an easier key; the recording's pitch follows. */
    val keyShift: Int = 0,
    /** Every chord sheet of the song on UG, most voted first; [ugUrl] is one of them. */
    val ugVersions: List<UgVersion> = emptyList(),
) {
    val id: String get() = idFor(artist, song)

    companion object {
        fun idFor(artist: String, song: String): String =
            "$artist-$song".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}

/**
 * One JSON file per song under the app's files directory, and next to it in `audio/` the
 * recording itself once it has been downloaded, and in `heard/` what speech recognition heard
 * in it. That is its own file so the player saving the song can never drop it.
 */
class SongStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val audioDir = File(dir, "audio")
    private val heardDir = File(dir, "heard")

    init {
        dir.mkdirs()
        audioDir.mkdirs()
        heardDir.mkdirs()
    }

    fun save(song: CachedSong) {
        val tmp = File(dir, "${song.id}.json.tmp")
        tmp.writeText(json.encodeToString(CachedSong.serializer(), song))
        tmp.renameTo(File(dir, "${song.id}.json"))
    }

    fun load(id: String): CachedSong? =
        File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { json.decodeFromString(CachedSong.serializer(), it.readText()) }.getOrNull() }

    /**
     * The song saved from this Ultimate Guitar tab, if any: the sheet in use, another version of
     * it, or else the same artist and title (a version switched before versions were saved).
     */
    fun findByUgUrl(ugUrl: String, artist: String, song: String): CachedSong? {
        val all = recent()
        return all.firstOrNull { it.ugUrl == ugUrl }
            ?: all.firstOrNull { s -> s.ugVersions.any { it.url == ugUrl } }
            ?: load(CachedSong.idFor(artist, song))
    }

    /** Where the recording of [song] lives once downloaded; check [File.exists]. */
    fun audioFile(song: CachedSong): File = File(audioDir, "${song.id}.m4a")

    fun deleteAudio(song: CachedSong) {
        audioFile(song).delete()
    }

    /** What was heard in the recording [song] uses now; null if not listened to yet. */
    fun loadHeard(song: CachedSong): HeardLyrics? =
        File(heardDir, "${song.id}.json").takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(HeardLyrics.serializer(), it.readText()) }.getOrNull() }
            ?.takeIf { it.videoUrl == song.videoUrl }

    fun saveHeard(song: CachedSong, heard: HeardLyrics) {
        val tmp = File(heardDir, "${song.id}.json.tmp")
        tmp.writeText(json.encodeToString(HeardLyrics.serializer(), heard))
        tmp.renameTo(File(heardDir, "${song.id}.json"))
    }

    fun recent(): List<CachedSong> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(CachedSong.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.lastPlayedAt }
}
