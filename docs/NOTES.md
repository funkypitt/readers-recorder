# Reader's Recorder — notes

Reference material moved out of the README: how the phone works inside, the two builds, and the
workstation worker of the private build.

## The phone

* One list, newest first: the title, and under it in small dim type the date and time, the
  length, the kind, where it stands (on this phone · to upload · in the cloud · cleaned ·
  transcribed). A title is automatic — the date and time, then the transcript's first words
  once it exists — unless you typed one (rename; a blank rename goes back to automatic). The one frequent action, **● record**, is an inverted
  row at the bottom of the list; the widget and the launcher tile do the same in one tap.
* Recording page: the running time, large; a level line; the kind (memo, lecture,
  conversation — tap to change; a conversation gets its speakers told apart); pause and ■ stop.
  Recording runs in a foreground service (type microphone) under a wake lock, with a silent
  notification carrying pause and stop, so the screen can go off. AAC 128 kb/s, 48 kHz mono;
  lectures and conversations use the phone's *unprocessed* audio source when it offers one,
  so the cleaning starts from the raw signal.
* A recording's page: play/pause with a position rule (tap to seek), the transcript in
  paragraphs (speakers labelled for a conversation), rename, share the audio or the
  transcript, delete (here and in the cloud).
* A long press on a row offers what the recording's page offers — rename, share the audio,
  share the transcript, delete — plus "select several…": rows become boxes to tick, the
  bottom row deletes them all (here and in the cloud folder).
* A long press on "● record" (once a cloud folder exists) asks, for that one recording, who
  will transcribe it: this phone, or my computer through the cloud folder — handy to compare
  the two on the same kind of material. The choice is shown in the recording's status line.
* Settings: who transcribes (this phone · my computer through the cloud folder · nobody), the
  transcription quality on the phone — normal (Whisper small, 190 MB, the default) or high
  quality, much slower (large-v3-turbo, 574 MB) —, the cleaned copy, **the main points written
  on the phone** (off; the first tap fetches the 1.93 GB model, and a phone with less than about
  6 GB of memory is told plainly that it cannot hold it), the cloud folder (or "forget it — recordings stay
  here"), the language spoken (the phone's language, English, or detected), the default kind,
  the look.
* On the phone, `ProcessService` (a foreground service with a progress notification) works in
  pieces, so memory stays flat whatever the length of the recording. MediaCodec decodes the
  file five minutes at a time, each piece resampled to 16 kHz and handed to whisper.cpp, whose
  model is loaded once for the whole recording (two arm64 builds, one with fp16 arithmetic
  chosen at runtime). Each piece is primed with a short, well-punctuated sentence in the
  language spoken and the end of the previous piece, so the text keeps full sentences, commas
  and capitals across pieces. The listening copy is streamed too, in two passes a minute at a
  time: the first measures the loudness of the high-passed signal, the second applies the same
  high-pass and a constant gain to −19 LUFS and encodes AAC. A recording transcribed on the phone is
  uploaded with its `.txt`, so the workstation worker leaves it alone.
* **The main points, written here** (`summary/`): once the option is on, every transcript made
  on this phone gets a list of points beside it (`<id>.resume.txt`), shown above the transcript
  and exported to the cloud folder with it.
  [llama.cpp](https://github.com/ggml-org/llama.cpp) is vendored in beside whisper.cpp — its own
  ggml, its own library, nothing shared — and runs Qwen2.5 3B Instruct (Q4_K_M, 1.93 GB, fetched
  once) on the processor alone, in a 4096-token context with small batches. The transcript is
  read in pieces of about 900 words, each piece asked for its points, and the notes are merged
  into eight. The instruction asks for **one thing only**: measured beforehand on the same
  interview, a model this size understands what it reads but cannot follow "a summary, then
  points" — asked for points alone, it obeys. The prompt goes through the model's own chat
  template, without which a chat model answers beside the question.
  Three guards, each learned the hard way: a phone too small is refused the option outright
  rather than killed mid-answer; an attempt is counted **before** it is made, so a summary that
  takes the application down with it can never restart the same recording for ever (two goes,
  then the recording is left alone until "write the main points" is chosen by hand); and a
  failure is always silent — a recording keeps its transcript whatever happens here.
* **Widgets** for any launcher: "● record" with the latest recording under it (while
  recording, a live Chronometer and ■); and **listen**: one recording at a time, newest
  first, ▶ / ❚❚ plays and pauses, ‹ › step to the more recent and the older ones (a standard
  widget cannot be swiped), the title opens it. **Reader's Launcher tiles**: "recorder" (the
  same record button) and "recordings" (listen, swiping from the latest to the older ones).
* One player for the whole app (`PlayerService`, foreground mediaPlayback, audio focus,
  pauses when headphones are pulled out): what plays from the widget or the launcher tile also
  shows on the recording's page and in the notification. Starting a recording stops playback.
* English, French, German, Spanish, Portuguese, Russian.

## Two audiences

One code base, two builds, chosen by the `audience` flavour dimension:

* **`publique`** — the build published on F-Droid. A cloud folder is an **export**: the phone
  uploads the recording, the transcript it made itself, its cleaned listening copy
  (`<base>_nettoye.m4a`) and the points it wrote (`<base>.resume.txt`), and never fetches
  anything back. "Who transcribes" offers this phone or nobody. The main points written on the
  phone are offered here too: they need nothing but the phone.
* **`prive`** — never published. Adds what only a workstation can serve: cleaning, transcription
  and the summary by the computer behind the WebDAV folder (`worker/recorder_worker.py`), the
  per-recording chooser on a long press, and fetching the results back.

The private build keeps the same application id and its version code stays **500 ahead**
(`baseVersionCode + 500`), so an F-Droid release of the public build can never land on the phone
as an update and quietly remove those features.

```
./gradlew assemblePubliqueRelease     # F-Droid
./gradlew assemblePriveRelease        # the phone of whoever runs the worker
```

## The workstation: `worker/recorder_worker.py`

Watches the recordings folder — the kDrive client's local mirror (`~/kDrive/Recordings`) or
the WebDAV folder directly — and for every `<base>.m4a` the phone has dropped:

1. **cleans** it with `nettoyer.py` from the traduction toolkit (hum notches, DeepFilterNet
   or afftdn with a DNSMOS quality gate, linear loudness normalisation to −19 LUFS mono),
   falling back to an ffmpeg `afftdn` + `loudnorm` chain if the toolkit is missing →
   `<base>_nettoye.mp3` (+ `<base>_nettoyage.json`, the report);
2. **transcribes** the cleaned audio with WhisperX (large-v3 by default, GPU, shared toolkit
   lock, resident Ollama models purged first), aligned; speaker diarization for a
   conversation when `HF_TOKEN` is set → `<base>.txt` (paragraphs broken only where a sentence has ended, after a pause or past ~600 characters —
the same rule as on the phone) and `<base>.segments.json`.

3. **summarises** the transcript with a local model through Ollama (`gemma4:31b` by default,
   `--resume-model` to change it, `--no-resume` to skip): a three to five sentence summary then
   the main points, in the transcript's language → `<base>.resume.txt`. A long transcript is
   summarised in pieces of `--resume-chunk-words` words, then the pieces are merged. The summary
   comes after the transcript is written, so a model that is absent, busy or slow never costs a
   recording its transcript. `--resume-missing` writes the summaries of transcripts already there.

`<base>.txt` is the "done" mark; `<base>.busy` guards work in progress; `<base>.error.txt`
carries a failure (the phone shows its first line; `--retry-errors` retries).

```
~/miniconda3/envs/interview/bin/python worker/recorder_worker.py --folder ~/kDrive/Recordings
worker/install.sh        # systemd user service, 60 s polling
```

## Note on the main points (2026-09-20)

The summary step has since been rewritten in
[readers-speech](https://github.com/funkypitt/readers-speech): each piece becomes a short
paragraph, the paragraphs give the theme in two sentences, then the points are written a few
parts at a time (at most 12). "Merged into eight" above describes the first version. Likewise,
the model file is 2.1 GB as downloaded (1.93 GiB).
