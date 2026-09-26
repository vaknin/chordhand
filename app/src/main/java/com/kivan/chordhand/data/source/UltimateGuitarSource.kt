package com.kivan.chordhand.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class UgSearchResult(
    val artist: String,
    val song: String,
    val rating: Double,
    val votes: Int,
    val url: String,
)

data class UgTab(
    val artist: String,
    val song: String,
    val url: String,
    /** Raw `[ch]`/`[tab]` markup. */
    val content: String,
    val capo: Int,
    /** Semitones the guitar is tuned away from standard (-1 for half a step down). */
    val tuningOffset: Int,
    val tonality: String?,
) {
    /** How far the written chord shapes are from the pitches you hear. */
    val soundingTranspose: Int get() = capo + tuningOffset
}

/**
 * Ultimate Guitar has no public API. Its pages carry their data as JSON in the `data-content`
 * attribute of `.js-store`. The mobile layout of the search page leaves the results out, so the
 * requests look like desktop Chrome.
 */
class UltimateGuitarSource(private val http: OkHttpClient = Http.client) {

    fun search(query: String): List<UgSearchResult> {
        val url = "https://www.ultimate-guitar.com/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "title")
            .addQueryParameter("value", query)
            .build()
        return parseSearch(get(url.toString()))
    }

    fun fetchTab(url: String): UgTab = parseTab(get(url), url)

    private fun get(url: String): String {
        val request = Request.Builder().url(url).headers(Http.desktopBrowserHeaders).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SourceException("Ultimate Guitar answered ${response.code}")
            return response.body.string()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun store(html: String): JsonObject {
            val raw = Jsoup.parse(html).selectFirst(".js-store")?.attr("data-content")
                ?: throw SourceException("Ultimate Guitar page has no data (blocked or redesigned)")
            return json.parseToJsonElement(raw).jsonObject
        }

        private fun JsonElement.path(vararg keys: String): JsonElement? =
            keys.fold(this as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

        private fun JsonElement?.str(): String? = (this?.jsonPrimitive)?.takeIf { it.isString }?.content

        /** Chord sheets only, best voted first, one per song (the most voted version). */
        fun parseSearch(html: String): List<UgSearchResult> {
            val results = store(html).path("store", "page", "data", "results")?.jsonArray
                ?: throw SourceException("Ultimate Guitar search returned no results block")
            return results.map { it.jsonObject }
                .filter { it["type"].str() == "Chords" }
                .mapNotNull { r ->
                    UgSearchResult(
                        artist = r["artist_name"].str() ?: return@mapNotNull null,
                        song = r["song_name"].str() ?: return@mapNotNull null,
                        rating = r["rating"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        votes = r["votes"]?.jsonPrimitive?.intOrNull ?: 0,
                        url = r["tab_url"].str() ?: return@mapNotNull null,
                    )
                }
                .sortedByDescending { it.votes }
                .distinctBy { it.artist.lowercase() to it.song.lowercase() }
        }

        fun parseTab(html: String, url: String): UgTab {
            val data = store(html).path("store", "page", "data")
                ?: throw SourceException("Ultimate Guitar tab page has no data")
            val content = data.path("tab_view", "wiki_tab", "content").str()
                ?: throw SourceException("Ultimate Guitar tab has no content (Pro-only tab?)")
            val meta = data.path("tab_view", "meta")
            return UgTab(
                artist = data.path("tab", "artist_name").str().orEmpty(),
                song = data.path("tab", "song_name").str().orEmpty(),
                url = url,
                content = content,
                capo = meta?.path("capo")?.jsonPrimitive?.intOrNull ?: 0,
                tuningOffset = tuningOffset(meta?.path("tuning", "value").str()),
                tonality = data.path("tab", "tonality_name").str()?.ifBlank { null },
            )
        }

        private val NOTE = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

        /**
         * Reads the A string (second from the bottom) of a tuning like "Eb Ab Db Gb Bb Eb": it
         * moves with whole-tuning changes but not with drop tunings, which only touch the low string.
         */
        internal fun tuningOffset(tuning: String?): Int {
            val a = tuning?.trim()?.split(Regex("\\s+"))?.getOrNull(1) ?: return 0
            val base = NOTE[a.take(1).uppercase()] ?: return 0
            val pc = base + when (a.drop(1)) { "#" -> 1; "b" -> -1; else -> 0 }
            // Nearest way from A (9): tunings go down at most a few semitones.
            val diff = Math.floorMod(pc - 9, 12)
            return if (diff > 6) diff - 12 else diff
        }
    }
}
