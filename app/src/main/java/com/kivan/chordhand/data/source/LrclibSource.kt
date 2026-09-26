package com.kivan.chordhand.data.source

import com.kivan.chordhand.domain.music.LrcParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

@Serializable
data class LrcCandidate(
    val id: Long,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val duration: Double = 0.0,
    val syncedLyrics: String? = null,
)

/** Time-synced lyrics from lrclib.net (free, no key). */
class LrclibSource(private val http: OkHttpClient = Http.client) {

    fun search(artist: String, track: String): List<LrcCandidate> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", track)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "chordhand (personal piano practice app, github.com/vaknin/chordhand)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("LRCLIB answered ${response.code}")
            return parseSearch(response.body.string())
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseSearch(body: String): List<LrcCandidate> =
            json.decodeFromString<List<LrcCandidate>>(body)

        /**
         * Synced candidates, best first. Many entries are copies of one bad sync, so believable
         * timing ranks first (in steps of 0.1), then the duration closest to the audio, then
         * distinct syncs only.
         */
        fun rank(candidates: List<LrcCandidate>, audioDurationSec: Double?): List<LrcCandidate> =
            candidates
                .filter { !it.syncedLyrics.isNullOrBlank() }
                .map { it to LrcParser.plausibility(LrcParser.parse(it.syncedLyrics!!)) }
                .sortedWith(
                    compareByDescending<Pair<LrcCandidate, Double>> { (it.second * 10).toInt() }
                        .thenBy { (c, _) -> audioDurationSec?.let { abs(c.duration - it) } ?: 0.0 }
                )
                .map { it.first }
                .distinctBy { it.syncedLyrics }
    }
}
