# Reader's Recorder

A black-and-white, text-only voice recorder for Android, in the
[Reader's](https://github.com/funkypitt/readers-launcher) family — and an answer to the
Pixel Recorder that keeps your recordings off Google's servers. Record a memo, a lecture or a
conversation with one tap; if you want, the recording goes to **your own cloud folder**
(WebDAV: Infomaniak kDrive, Nextcloud…), where **your own computer** cleans the sound,
normalises its loudness and transcribes it with WhisperX. The transcript and the cleaned
audio come back to the phone.

**By default nothing leaves the phone.** The cloud folder is optional, set up in three
questions (server — for kDrive, just the number in its web address —, username, password).

## The phone

* One list, newest first: title, length, kind, where it stands (on this phone · to upload ·
  in the cloud · cleaned · transcribed). The one frequent action, **● record**, is an inverted
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
* Settings: the cloud folder (or "forget it — recordings stay here"), the language spoken
  (the phone's language, English, or detected), the default kind, the look.
* **Widget** for any launcher: "● record" with the latest recording under it; while
  recording, a live Chronometer and ■. **Reader's Launcher tile** ("recorder"): the same, and
  its text opens the latest recording so the transcript is one tap away.
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
   conversation when `HF_TOKEN` is set → `<base>.txt` (paragraphs) and `<base>.segments.json`.

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
