package com.kivan.chordhand.data.listen

import android.util.Log
import com.kivan.chordhand.data.source.Http
import com.kivan.chordhand.data.source.SourceException
import com.kivan.chordhand.domain.music.HeardWord
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** What speech recognition heard in one recording; [videoUrl] says which. */
@Serializable
data class HeardLyrics(val videoUrl: String, val words: List<HeardWord>)

/** Timing the lyrics to a recording, per song id. Songs with no entry are not being worked on. */
sealed interface ListenState {
    /** The speech model is downloading, the first time only. */
    data class FetchingModel(val fraction: Float) : ListenState
    data class Listening(val fraction: Float) : ListenState
    data object Done : ListenState
    data object Failed : ListenState
}

/**
 * Listens to a downloaded recording with whisper.cpp (base.en) to hear when each word is sung.
 * The model, about 60 MB, is downloaded once into [modelDir].
 */
class LyricListener(private val modelDir: File, private val http: OkHttpClient = Http.client) {
    private val model = File(modelDir, MODEL_FILE)

    /** Blocking: downloads the model if needed, decodes the recording and transcribes it. */
    fun listen(audio: File, prompt: String, onModel: (Float) -> Unit, onProgress: (Float) -> Unit): List<HeardWord> {
        ensureModel(onModel)
        val started = System.nanoTime()
        val pcm = AudioDecoder.decode(audio)
        val decoded = System.nanoTime()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
        val bytes = WhisperNative.transcribe(
            model.path, pcm, prompt.take(MAX_PROMPT_CHARS), threads,
            object : WhisperNative.Listener {
                override fun onProgress(percent: Int) = onProgress(percent / 100f)
            },
        ) ?: throw SourceException("Speech recognition failed")
        Log.i(TAG, "decoded ${pcm.size / AudioDecoder.SAMPLE_RATE} s in ${(decoded - started) / 1_000_000} ms, heard in ${(System.nanoTime() - decoded) / 1_000_000} ms")
        return parse(String(bytes, Charsets.UTF_8))
    }

    private fun ensureModel(onProgress: (Float) -> Unit) {
        if (model.length() == MODEL_BYTES) return
        modelDir.mkdirs()
        val part = File(modelDir, "$MODEL_FILE.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw SourceException("Speech model download failed: HTTP ${response.code}")
                part.outputStream().use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        onProgress(done.toFloat() / MODEL_BYTES)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != MODEL_SHA256) throw SourceException("Speech model download was corrupt")
            if (!part.renameTo(model)) throw SourceException("Couldn't save the speech model")
        } finally {
            part.delete()
        }
    }

    companion object {
        private const val TAG = "chordhand-listen"
        private const val MODEL_FILE = "ggml-base.en-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE"
        private const val MODEL_BYTES = 59_721_011L
        private const val MODEL_SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
        /** A Pixel 8 has four fast cores; spilling onto the small ones makes whisper 3–4× slower. */
        private const val MAX_THREADS = 4
        /** whisper keeps at most half its 448-token context for the prompt; this fits in it. */
        private const val MAX_PROMPT_CHARS = 600

        internal fun parse(text: String): List<HeardWord> = text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val ms = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            line.substring(tab + 1).trim().takeIf { it.isNotEmpty() }?.let { HeardWord(it, ms) }
        }.toList()
    }
}
