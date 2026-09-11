# Reader's Recorder

A black-and-white, text-only voice recorder for Android, in the
[Reader's](https://github.com/funkypitt/readers-launcher) family — and an answer to the
Pixel Recorder that keeps your recordings off Google's servers. Record a memo, a lecture or a
conversation with one tap; if you want, the recording goes to **your own cloud folder**
(WebDAV: Infomaniak kDrive, Nextcloud…), where **your own computer** cleans the sound,
normalises its loudness and transcribes it with WhisperX. The transcript and the cleaned
audio come back to the phone.

**By default nothing leaves the phone — and the phone transcribes by itself**, with
[whisper.cpp](https://github.com/ggerganov/whisper.cpp) vendored in (quantised `base`,
`small` or `medium` model, fetched once, 57–539 MB), plus a light-touch cleaned copy (60 Hz
high-pass, constant gain to −19 LUFS, no compression). The cloud folder is optional, set up in
three questions (server — for kDrive, just the number in its web address —, username,
password), with a step-by-step guide in the settings; it hands the work to your computer's
much bigger model.

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
  quality, much slower (large-v3-turbo, 574 MB) —, the cleaned copy, the cloud folder (or "forget it — recordings stay
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
* **Widgets** for any launcher: "● record" with the latest recording under it (while
  recording, a live Chronometer and ■); and **listen**: one recording at a time, newest
  first, ▶ / ❚❚ plays and pauses, ‹ › step to the more recent and the older ones (a standard
  widget cannot be swiped), the title opens it. **Reader's Launcher tiles**: "recorder" (the
  same record button) and "recordings" (listen, swiping from the latest to the older ones).
* One player for the whole app (`PlayerService`, foreground mediaPlayback, audio focus,
  pauses when headphones are pulled out): what plays from the widget or the launcher tile also
  shows on the recording's page and in the notification. Starting a recording stops playback.
* English, French, German, Spanish, Portuguese, Russian.

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

`<base>.txt` is the "done" mark; `<base>.busy` guards work in progress; `<base>.error.txt`
carries a failure (the phone shows its first line; `--retry-errors` retries).

```
~/miniconda3/envs/interview/bin/python worker/recorder_worker.py --folder ~/kDrive/Recordings
worker/install.sh        # systemd user service, 60 s polling
```

## Building the app

```
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
```

minSdk 26, targetSdk 34. MIT.

## Crédits / Credits

© 2026 Pierre Gallaz. Développé avec [Claude Code](https://claude.com/claude-code) (Anthropic).
Licence MIT, voir `LICENSE`.

© 2026 Pierre Gallaz. Developed with [Claude Code](https://claude.com/claude-code) (Anthropic).
MIT licence, see `LICENSE`.
