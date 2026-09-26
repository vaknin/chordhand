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
- **Sync**: ±0.2 s when the lyrics run early or late. It is saved per song.
- **Skip intro**: next to the countdown to the first chord; lands 1.5 s before it.
- **Offline after the first open**: the chords, lyrics and recording (~3 MB) are saved, so a song
  opens again with no network. The refresh button on a saved song fetches it all again.
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

## Layout

- `data/midi`, `domain/model/MidiEvent.kt`, `ConnectionState.kt`, `MidiRepository.kt`: copied
  from Oblique, a separate piano app of mine, and repackaged.
- `data/source`: Ultimate Guitar, LRCLIB and YouTube sources. `data/SongLoader.kt`,
  `data/SongStore.kt`: the load pipeline and the cache: JSON in `filesDir/songs`, the
  downloaded recordings in `filesDir/songs/audio`.
- `domain/music`: the chord parser, sheet parser, LRC parser, aligner, voicing planner and chord
  matcher. `domain/practice`: scoring.
- `ui/search`, `ui/player`: the Compose screens. The app is landscape only.

Licensed GPL-3.0, as NewPipeExtractor requires. Personal project; scraping Ultimate Guitar may break
whenever they change their site.
