package com.kivan.chordhand.spike

import com.kivan.chordhand.data.source.Http
import com.kivan.chordhand.data.source.LrclibSource
import com.kivan.chordhand.data.source.UltimateGuitarSource
import com.kivan.chordhand.data.source.YoutubeAudioSource
import com.kivan.chordhand.domain.music.ChordSheetParser
import com.kivan.chordhand.domain.music.LrcParser
import com.kivan.chordhand.domain.music.TimelineAligner
import com.kivan.chordhand.domain.music.VoicingPlanner
import com.kivan.chordhand.domain.music.Chord
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Feasibility spike against the live services. Offline runs skip it; run with
 * `./gradlew :app:testDebugUnitTest -Pspike --tests '*PipelineSpikeTest*'`.
 */
class PipelineSpikeTest {
    private val query = System.getProperty("spike.query") ?: "fluorescent adolescent"

    @Test
    fun fullPipeline() {
        assumeTrue(System.getProperty("spike") == "true")

        val ug = UltimateGuitarSource()
        val hit = ug.search(query).first()
        println("UG: ${hit.artist} – ${hit.song} (${hit.votes} votes) ${hit.url}")
        val tab = ug.fetchTab(hit.url)
        println("UG: capo ${tab.capo}, tuning offset ${tab.tuningOffset}, key ${tab.tonality}")

        val yt = YoutubeAudioSource()
        val track = yt.search(tab.artist, tab.song).first()
        println("YT: ${track.title} / ${track.uploader} ${track.durationSec}s ${track.videoUrl}")
        val audio = yt.resolveAudio(track.videoUrl)
        println("YT: ${audio.mimeType} ${audio.bitrate} bps")
        val probe = Request.Builder().url(audio.url).header("Range", "bytes=0-1023")
            .header("User-Agent", Http.DESKTOP_USER_AGENT).build()
        Http.client.newCall(probe).execute().use { println("YT: stream probe HTTP ${it.code}, ${it.body.bytes().size} bytes") }
        val file = File.createTempFile("spike", ".m4a").apply { delete() }
        val started = System.nanoTime()
        yt.download(audio.url, file)
        println("YT: downloaded ${file.length()} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
        file.delete()

        val candidates = LrclibSource.rank(LrclibSource().search(tab.artist, tab.song), track.durationSec.toDouble())
        candidates.forEach {
            println("LRC: #${it.id} ${it.albumName} ${it.duration}s plausibility " +
                "%.2f".format(LrcParser.plausibility(LrcParser.parse(it.syncedLyrics!!))))
        }
        val lrc = LrcParser.parse(candidates.first().syncedLyrics!!)

        val sheet = ChordSheetParser.parse(tab.content, tab.soundingTranspose)
        val timeline = TimelineAligner.align(sheet, lrc, track.durationSec * 1000)
        val voicings = VoicingPlanner.plan(timeline.chords.map { it.chord })
        println("Aligned ${timeline.chords.size} chords, ${"%.0f".format(timeline.anchoredShare * 100)}% anchored to lyrics")
        timeline.chords.forEachIndexed { i, c ->
            val line = sheet.lines[c.lineIndex]
            val v = voicings[i]
            println(
                "%6.2fs %s %-5s LH %-4s RH %-14s | %s".format(
                    c.startMs / 1000.0, if (c.anchored) "*" else " ", c.chord.symbol,
                    v.leftHand.joinToString { Chord.midiName(it) },
                    v.rightHand.joinToString(" ") { Chord.midiName(it) },
                    line.lyric ?: "[${line.section}]",
                )
            )
        }
    }
}
