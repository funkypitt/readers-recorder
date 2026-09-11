#!/usr/bin/env python3
"""
Reader's Recorder — the workstation's side.

Watches the recordings folder (the kDrive mirror on this machine, or a WebDAV folder
directly) and, for every recording the phone has dropped there, does what the phone
cannot: cleans the sound (nettoyer.py from the traduction toolkit — hum notches,
DeepFilterNet or afftdn, linear loudness normalisation, DNSMOS quality gate),
then transcribes it with WhisperX on the GPU. It leaves, beside `<base>.m4a`:

    <base>_nettoye.mp3     the cleaned, loudness-normalised audio (the phone fetches it)
    <base>_nettoyage.json  nettoyer.py's report
    <base>.txt             the transcript, in paragraphs — speakers labelled for a conversation
    <base>.segments.json   the timed segments
    <base>.error.txt       only when something failed (the phone shows its first line)

A recording is "done" when `<base>.txt` exists. `<base>.busy` marks work in progress so
two workers never take the same file. The phone's `<base>.json` carries the title, the
kind (memo / lecture / conversation) and the language hint.

    recorder_worker.py --folder ~/kDrive/Recordings            # the kDrive client's mirror
    recorder_worker.py --webdav https://ID.connect.kdrive.infomaniak.com/Recordings --user … --password …
    recorder_worker.py --once                                   # one pass, then exit

Run it with the toolkit's interpreter: ~/miniconda3/envs/interview/bin/python.
"""

import argparse
import base64
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, unquote, urljoin

TOOLKIT = Path(os.environ.get("TRADUCTION_DIR", "~/code/traduction")).expanduser()
AUDIO_EXTS = {".m4a", ".mp3", ".wav", ".ogg", ".opus", ".flac", ".aac"}
DEFAULT_MODEL = "large-v3"

# ── GPU sharing with the rest of the toolkit (same lock file, same purge) ─────
_GPU_LOCK_PATH = os.path.expanduser("~/.cache/traduction_gpu.lock")
_gpu_lock_fh = None


def acquire_gpu_lock():
    global _gpu_lock_fh
    if _gpu_lock_fh is not None:
        return
    os.makedirs(os.path.dirname(_GPU_LOCK_PATH), exist_ok=True)
    _gpu_lock_fh = open(_GPU_LOCK_PATH, "w")
    try:
        fcntl.flock(_gpu_lock_fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        log("GPU busy with another toolkit task — waiting for the lock")
        fcntl.flock(_gpu_lock_fh, fcntl.LOCK_EX)


def release_gpu_lock():
    global _gpu_lock_fh
    if _gpu_lock_fh is not None:
        fcntl.flock(_gpu_lock_fh, fcntl.LOCK_UN)
        _gpu_lock_fh.close()
        _gpu_lock_fh = None


def free_vram_mib():
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10)
        return int(out.stdout.strip().splitlines()[0])
    except Exception:
        return 0


def free_gpu_for_task(min_free_mib=6000, timeout=60.0):
    """Unload any resident Ollama model, then wait until the VRAM really is free."""
    try:
        ps = subprocess.run(["ollama", "ps"], capture_output=True, text=True, timeout=10)
        for line in ps.stdout.splitlines()[1:]:
            parts = line.split()
            if parts:
                subprocess.run(["ollama", "stop", parts[0]], capture_output=True, timeout=30)
    except Exception:
        pass
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            out = subprocess.run(["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                                 capture_output=True, text=True, timeout=10)
            free = int(out.stdout.strip().splitlines()[0])
        except Exception:
            return
        if free >= min_free_mib:
            return
        time.sleep(1.5)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# ── the two ways to reach the folder ──────────────────────────────────────────
class LocalFolder:
    def __init__(self, path):
        self.root = Path(path).expanduser()
        self.root.mkdir(parents=True, exist_ok=True)

    def names(self):
        return {p.name for p in self.root.iterdir() if p.is_file()}

    def fetch(self, name, dest):
        shutil.copy(self.root / name, dest)

    def read_text(self, name):
        return (self.root / name).read_text(encoding="utf-8")

    def store(self, name, src):
        tmp = self.root / (name + ".part")
        shutil.copy(src, tmp)
        tmp.replace(self.root / name)

    def write_text(self, name, text):
        tmp = self.root / (name + ".part")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(self.root / name)

    def remove(self, name):
        (self.root / name).unlink(missing_ok=True)


class WebDavFolder:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/") + "/"
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self._req("MKCOL", "", allow={405, 301})

    def _req(self, method, name, data=None, headers=None, allow=()):
        req = urllib.request.Request(self.url + quote(name), data=data, method=method)
        req.add_header("Authorization", self.auth)
        req.add_header("User-Agent", "readers-recorder-worker")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            if e.code in allow:
                return e.code, b""
            raise

    def names(self):
        body = b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'
        _, xml = self._req("PROPFIND", "", data=body, headers={"Depth": "1", "Content-Type": "application/xml"})
        out = set()
        for resp in ET.fromstring(xml).iter("{DAV:}response"):
            href = resp.findtext("{DAV:}href") or ""
            if resp.find(".//{DAV:}collection") is not None:
                continue
            out.add(unquote(href.rstrip("/").rsplit("/", 1)[-1]))
        return out

    def fetch(self, name, dest):
        _, data = self._req("GET", name)
        Path(dest).write_bytes(data)

    def read_text(self, name):
        _, data = self._req("GET", name)
        return data.decode("utf-8")

    def store(self, name, src):
        self._req("PUT", name, data=Path(src).read_bytes(), headers={"Content-Type": "application/octet-stream"})

    def write_text(self, name, text):
        self._req("PUT", name, data=text.encode("utf-8"), headers={"Content-Type": "text/plain; charset=utf-8"})

    def remove(self, name):
        self._req("DELETE", name, allow={404})


# ── the work ──────────────────────────────────────────────────────────────────
def clean(src, workdir, args):
    """nettoyer.py → <stem>_nettoye.mp3 + report; ffmpeg loudnorm as the fallback."""
    script = TOOLKIT / "nettoyer.py"
    out = workdir / f"{src.stem}_nettoye.mp3"
    if script.exists() and not args.no_nettoyer:
        cmd = [sys.executable, str(script), str(src), "-o", str(workdir), "--moteur", args.moteur]
        if args.cpu:
            cmd.append("--cpu")
        r = subprocess.run(cmd, cwd=TOOLKIT, capture_output=True, text=True, timeout=3 * 3600)
        if r.returncode == 0 and out.exists():
            return out, workdir / f"{src.stem}_nettoyage.json"
        log(f"nettoyer.py failed ({r.returncode}); falling back to ffmpeg loudnorm\n{r.stderr[-800:]}")
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-ac", "1", "-ar", "48000",
           "-af", "highpass=f=60,afftdn=nr=10:nf=-30:tn=1,loudnorm=I=-19:TP=-1.5:LRA=11",
           "-codec:a", "libmp3lame", "-q:a", "0", str(out)]
    subprocess.run(cmd, check=True, timeout=3600)
    return out, None


def paragraphs(segments, speakers):
    """Segments → text, with the phone's rule. A break only where a sentence has ended: after a
    pause of 1.2 s once the paragraph has some body, or as soon as it passes ~600 characters;
    a runaway sentence is cut at ~1,200 characters; a change of speaker always starts a new one."""
    out, cur, cur_speaker, last_end = [], [], None, None
    for seg in segments:
        text = (seg.get("text") or "").strip()
        if not text:
            continue
        spk = seg.get("speaker") if speakers else None
        gap = (seg["start"] - last_end) if last_end is not None else 0
        body = " ".join(cur)
        ended = bool(re.search(r"[.!?…][\"'»”’)]*\s*$", body))
        if cur and (spk != cur_speaker or (ended and gap >= 1.2 and len(body) >= 250)
                    or (ended and len(body) >= 600) or len(body) >= 1200):
            out.append((cur_speaker, " ".join(cur)))
            cur = []
        cur.append(text)
        cur_speaker = spk
        last_end = seg["end"]
    if cur:
        out.append((cur_speaker, " ".join(cur)))
    lines = []
    for spk, text in out:
        lines.append((f"{label(spk)}: " if speakers and spk else "") + text)
    return "\n\n".join(lines) + "\n"


def label(spk):
    # SPEAKER_00 → Speaker 1
    try:
        return "Speaker %d" % (int(str(spk).rsplit("_", 1)[-1]) + 1)
    except Exception:
        return str(spk)


def transcribe(audio, language, diarize, args):
    import torch
    import whisperx
    device = "cuda" if torch.cuda.is_available() and not args.cpu else "cpu"
    # The CPU path needs neither the toolkit's GPU lock nor the VRAM purge. On the GPU, the
    # toolkit lock serialises the big models — but a long job (a days-long training run)
    # can hold it while leaving plenty of VRAM: WhisperX needs ~4 GB, so when that much is
    # free we go ahead beside it instead of waiting for the lock.
    locked = False
    if device == "cuda":
        if free_vram_mib() >= args.vram_needed:
            log(f"  GPU has {free_vram_mib()} MiB free — running beside the other task")
        else:
            acquire_gpu_lock(); locked = True
            free_gpu_for_task(min_free_mib=args.vram_needed)
    try:
        compute = "float16" if device == "cuda" else "int8"
        model = whisperx.load_model(args.model, device, compute_type=compute, language=language or None)
        wav = whisperx.load_audio(str(audio))
        try:
            result = model.transcribe(wav, batch_size=16 if device == "cuda" else 4, language=language or None)
        except IndexError:
            # The VAD found no speech at all (a silent recording): whisperx's pipeline trips on an empty batch.
            return [], language or "en", False
        lang = result.get("language") or language or "en"
        if not result.get("segments"):
            return [], lang, False
        try:
            align_model, meta = whisperx.load_align_model(language_code=lang, device=device)
            result = whisperx.align(result["segments"], align_model, meta, wav, device, return_char_alignments=False)
        except Exception as e:
            log(f"no alignment for '{lang}': {e}")
        speakers = False
        if diarize:
            token = args.hf_token or os.environ.get("HF_TOKEN")
            if token:
                try:
                    from whisperx.diarize import DiarizationPipeline
                    dia = DiarizationPipeline(use_auth_token=token, device=device)
                    result = whisperx.assign_word_speakers(dia(wav), result)
                    speakers = True
                except Exception as e:
                    log(f"diarization skipped: {e}")
            else:
                log("conversation, but no HF_TOKEN for diarization — plain transcript")
        del model
        if device == "cuda":
            torch.cuda.empty_cache()
        return result["segments"], lang, speakers
    finally:
        if locked:
            release_gpu_lock()


def process(folder, name, names, args):
    base = Path(name).stem
    meta = {}
    if f"{base}.json" in names:
        try:
            meta = json.loads(folder.read_text(f"{base}.json"))
        except Exception:
            pass
    kind = meta.get("kind", "memo")
    language = (meta.get("language") or args.language or "").strip() or None
    log(f"▶ {name}  ({kind}, language {language or 'detected'})")
    folder.write_text(f"{base}.busy", time.strftime("%Y-%m-%d %H:%M:%S"))
    work = Path(tempfile.mkdtemp(prefix="recorder_"))
    try:
        src = work / name
        folder.fetch(name, src)
        t0 = time.time()
        cleaned, report = clean(src, work, args)
        log(f"  cleaned in {time.time() - t0:.0f} s")
        folder.store(f"{base}_nettoye.mp3", cleaned)
        if report and report.exists():
            folder.store(f"{base}_nettoyage.json", report)
        t0 = time.time()
        # Transcribe the ORIGINAL: Whisper was trained on noisy speech and reads it well, while a
        # denoiser tuned for listening can strip exactly the cues it relies on (seen on a test
        # excerpt: the cleaned file gave hallucinations, the raw one a clean transcript).
        segments, lang, speakers = transcribe(src, language, kind == "conversation", args)
        log(f"  transcribed in {time.time() - t0:.0f} s ({lang}, {len(segments)} segments)")
        text = paragraphs(segments, speakers) if segments else "(no speech detected)\n"
        folder.write_text(f"{base}.segments.json", json.dumps(
            {"language": lang, "kind": kind, "speakers": speakers,
             "segments": [{"start": round(s["start"], 2), "end": round(s["end"], 2), "text": s.get("text", "").strip(),
                           **({"speaker": s.get("speaker")} if speakers else {})} for s in segments]},
            ensure_ascii=False, indent=1))
        folder.write_text(f"{base}.txt", text)
        folder.remove(f"{base}.error.txt")
        log(f"✓ {base}.txt")
    except Exception as e:
        import traceback
        folder.write_text(f"{base}.error.txt", f"{type(e).__name__}: {e}\n\n{traceback.format_exc()}")
        log(f"✗ {name}: {e}")
    finally:
        folder.remove(f"{base}.busy")
        shutil.rmtree(work, ignore_errors=True)


def pending(names):
    out = []
    for n in sorted(names):
        p = Path(n)
        if p.suffix.lower() not in AUDIO_EXTS or p.stem.endswith("_nettoye"):
            continue
        if f"{p.stem}.txt" in names or f"{p.stem}.busy" in names or f"{p.stem}.error.txt" in names:
            continue
        out.append(n)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--folder", default=None, help="local recordings folder (e.g. the kDrive mirror ~/kDrive/Recordings)")
    ap.add_argument("--webdav", default=None, help="WebDAV folder URL instead of a local folder")
    ap.add_argument("--user", default=os.environ.get("RECORDER_USER"))
    ap.add_argument("--password", default=os.environ.get("RECORDER_PASSWORD"))
    ap.add_argument("--model", default=DEFAULT_MODEL, help="WhisperX model (default large-v3)")
    ap.add_argument("--language", default="", help="language hint when the phone gave none")
    ap.add_argument("--moteur", default="dfn", choices=["auto", "dfn", "mossformer2", "afftdn"], help="nettoyer.py engine")
    ap.add_argument("--no-nettoyer", action="store_true", help="ffmpeg loudnorm only")
    ap.add_argument("--cpu", action="store_true")
    ap.add_argument("--vram-needed", type=int, default=6000, help="MiB of free VRAM that lets the worker run beside another GPU task")
    ap.add_argument("--hf-token", default=None, help="Hugging Face token for speaker diarization (or HF_TOKEN)")
    ap.add_argument("--interval", type=int, default=60, help="seconds between two looks at the folder")
    ap.add_argument("--once", action="store_true", help="one pass, then exit")
    ap.add_argument("--retry-errors", action="store_true", help="also retry recordings that failed before")
    args = ap.parse_args()

    if args.webdav:
        if not (args.user and args.password):
            sys.exit("--webdav needs --user and --password (or RECORDER_USER / RECORDER_PASSWORD)")
        folder = WebDavFolder(args.webdav, args.user, args.password)
    else:
        folder = LocalFolder(args.folder or "~/kDrive/Recordings")
    log(f"watching {args.webdav or folder.root}")
    while True:
        try:
            names = folder.names()
            if args.retry_errors:
                for n in [x for x in names if x.endswith(".error.txt")]:
                    folder.remove(n)
                names = folder.names()
            for name in pending(names):
                process(folder, name, names, args)
                names = folder.names()
        except Exception as e:
            log(f"folder unreachable: {e}")
        if args.once:
            break
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
