# Chordhand

Play along with real songs on the Roland FP-10. Search a song and the app fetches the chords
(Ultimate Guitar), the recording (YouTube, via NewPipeExtractor) and time-synced lyrics
(LRCLIB). It lines the chords up with the recording, then shows what each hand plays and checks
over Bluetooth MIDI whether you played it.

An MVP. The question it answers: is this fun, and does it teach?

## What it does

- Chords over lyrics, karaoke-style, with the chord sounding now highlighted.
- Left hand: the bass note in the C2 octave. Right hand: the chord in close position, with the
  inversion picked to move the hand as little as possible from the previous chord.
- A keyboard strip from C2 to C6 with the targets (amber = left hand, cyan = right hand), dots
  on the next chord's keys shortly before the change, and your keys in green or red.
- **Wait for me**: the song pauses at each change until you play the chord.
- **Easy chords**: triads only (Em7 → Em, Dsus4 → D). Speed 50–100 %, pitch kept.
- **Key**: move the song up to 5 semitones down or 6 up; the chords, keys and scoring follow and
  the recording is pitch-shifted to match (tempo kept). **Easiest** picks the key with the fewest
  black keys, with a small cost per semitone because the shifted audio sounds worse further out.
  Saved per song.
- **Words timed by ear**: once a recording is downloaded, the phone listens to it with
  whisper.cpp (base.en, a 60 MB model fetched the first time) and times each sung word from
  what it hears; LRCLIB only times whole lines, and some of those a second or two off. It runs
  in the background, about 1.5 min per song on a Pixel 8, once per recording; the player shows
  its progress under Lyrics sync, and the words switch over when it's done.
- **Sync**: ±0.2 s when the lyrics run early or late. It is saved per song, and reset when the
  words get timed by ear.
- **Sheet versions**: UG usually has several chord sheets per song. The most voted one is used
  unless under 60 % of its chords line up with sung words; then the next two are tried and the
  best aligned wins. "Sheet 2/5" in the player switches by hand, keeping sync and key.
- **Recording choice**: the synced lyrics are fetched first, and their median length picks the
  YouTube result closest to the album recording; titles saying live, cover, remix, sped up and
  the like lose unless the song's own name says it.
- **Skip intro**: next to the countdown to the first chord; lands 1.5 s before it.
- **Offline after the first open**: the chords, lyrics and recording (~3 MB) are saved, so a song
  opens again with no network. Its row shows the download's progress, then Offline. The refresh
  button on a saved song fetches it all again.
- A summary at the end: the share of chords played, the timing, and the three chord changes
  missed most.
- Touch the on-screen keys to try it without the piano.

## Build and run

```bash
./gradlew :app:testDebugUnitTest      # offline unit tests (fixtures in app/src/test/resources)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Live check of the whole pipeline (UG → YouTube → LRCLIB → timeline) from the laptop:

```bash
./gradlew :app:testDebugUnitTest -Pspike --tests '*PipelineSpikeTest*' --rerun
```

## Findings from the spike (2026-09-26)

- UG hides search results from mobile user-agents; requests use desktop Chrome headers.
- LRCLIB's `/api/get` answer is often a badly synced copy. `/api/search` returns many entries
  that share one sync, so candidates are ranked by how singable the line spacing is
  (`LrcParser.plausibility`), then by duration.
- The sheet and the LRC split lines differently, so the alignment is done word by word
  (`TimelineAligner`). Fluorescent Adolescent: 73 chords, 89 % anchored to a sung word.
- The YouTube Music recording can differ in length from the LRC entry, so a constant offset is
  expected; that is what Sync is for.

## Findings on lyric timing (2026-09-26)

Measured on Fluorescent Adolescent against whisper small on the vocals isolated with Demucs
(itself within about 0.14 s of the truth), mean error per word:

| Timing | Error |
|---|---|
| LRC line start, words spread at 75 ms per character | 0.30 s |
| The same, spread by syllables instead | 0.33 s |
| wav2vec2-base forced alignment on the full mix | 0.6 s (music drowns it) |
| LRC lines + whisper base.en heard words (`LyricTiming`) | 0.18 s |

- Every heard word within a second of its LRC line takes the heard time, less 0.2 s: DTW
  timestamps mark about where a token ends. The rest are placed by syllables at the singer's
  pace, measured from the heard words.
- Prompting whisper with the song's lyrics helps; whisper tiny is too inaccurate.
- On a Pixel 8: 4 threads (more spill onto the small cores and run 3–4× slower); beam search,
  since greedy decoding sometimes drops a whole 30 s window of singing. Decoding the AAC
  recording takes ~18 s of that, mostly Android's per-buffer codec overhead.

## Layout

- `data/midi`, `domain/model/MidiEvent.kt`, `ConnectionState.kt`, `MidiRepository.kt`: copied
  from Oblique, a separate piano app of mine, and repackaged.
- `data/source`: Ultimate Guitar, LRCLIB and YouTube sources. `data/SongLoader.kt`,
  `data/SongStore.kt`: the load pipeline and the cache: JSON in `filesDir/songs`, the
  downloaded recordings in `filesDir/songs/audio`.
- `data/listen`, `src/main/cpp`: the recording decoded to 16 kHz and transcribed by whisper.cpp
  v1.9.4 (fetched by CMake at a pinned hash) through JNI; arm64 only.
- `domain/music`: the chord parser, sheet parser, LRC parser, word timing, aligner, voicing
  planner and chord matcher. `domain/practice`: scoring.
- `ui/search`, `ui/player`: the Compose screens. The app is landscape only.

Licensed GPL-3.0, as NewPipeExtractor requires. Personal project; scraping Ultimate Guitar may break
whenever they change their site.
