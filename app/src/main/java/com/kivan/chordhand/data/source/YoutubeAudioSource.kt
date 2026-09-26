package com.kivan.chordhand.data.source

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File

data class YoutubeTrack(val videoUrl: String, val title: String, val uploader: String, val durationSec: Long)

data class AudioStreamUrl(val url: String, val mimeType: String?, val bitrate: Int)

/**
 * Finds the song on YouTube and resolves a playable audio-only stream, the way NewPipe does.
 * Stream URLs expire after a few hours, so only [YoutubeTrack.videoUrl] is worth caching.
 */
class YoutubeAudioSource(private val http: OkHttpClient = Http.client) {
    init {
        synchronized(YoutubeAudioSource::class) {
            if (NewPipe.getDownloader() == null) NewPipe.init(OkHttpDownloader(http))
        }
    }

    /** YouTube Music "songs" first (the album recording), plain videos as a fallback. */
    fun search(artist: String, title: String): List<YoutubeTrack> {
        val query = "$artist $title"
        val songs = searchWith(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        return songs.ifEmpty { searchWith(query, YoutubeSearchQueryHandlerFactory.VIDEOS) }
    }

    fun resolveAudio(videoUrl: String): AudioStreamUrl {
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val progressive = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = progressive.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: progressive.maxByOrNull { it.averageBitrate }
            ?: throw SourceException("No playable audio stream for $videoUrl")
        return AudioStreamUrl(best.content, best.format?.mimeType, best.averageBitrate)
    }

    /**
     * Saves the stream at [url] to [target], atomically: the file appears only once complete.
     * Fetched in ranges, as NewPipe does, because YouTube throttles one long request.
     */
    fun download(url: String, target: File) {
        val part = File(target.path + ".part")
        try {
            part.outputStream().use { out ->
                var from = 0L
                var total = Long.MAX_VALUE
                while (from < total) {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", Http.DESKTOP_USER_AGENT)
                        .header("Range", "bytes=$from-${from + DOWNLOAD_CHUNK - 1}")
                        .build()
                    http.newCall(request).execute().use { response ->
                        when (response.code) {
                            // The server ignored the range: the body is the whole file.
                            200 -> { response.body.byteStream().copyTo(out); total = 0 }
                            206 -> {
                                total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                    ?: throw SourceException("Download of $url gave no length")
                                val read = response.body.byteStream().copyTo(out)
                                if (read == 0L) throw SourceException("Download of $url stopped at $from of $total bytes")
                                from += read
                            }
                            else -> throw SourceException("Download of $url failed: HTTP ${response.code}")
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw SourceException("Couldn't save ${target.name}")
        } finally {
            part.delete()
        }
    }

    private fun searchWith(query: String, filter: String): List<YoutubeTrack> {
        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { YoutubeTrack(it.url, it.name, it.uploaderName.orEmpty(), it.duration) }
    }
}

private const val DOWNLOAD_CHUNK = 1L shl 20

private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Http.DESKTOP_USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("YouTube rate limit", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }
}
