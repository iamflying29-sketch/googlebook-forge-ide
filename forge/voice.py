"""Voice input for the chat when Forge runs in a browser: whisper.cpp in the Linux VM turns a short WAV recording
into text. In the Forge app, Android's own speech recognizer is used instead and this module is not involved.

whisper-cli runs once per recording with a time limit, then exits, so it holds no memory between uses."""
from __future__ import annotations

import glob
import os
import re
import subprocess
import threading
from typing import Optional

LIB = os.path.expanduser("~/.local/lib/forge-voice")
MODELS = os.path.expanduser("~/.forge-ide/models")
# Best first. small.en is more accurate, base.en is faster; Forge uses the best one that is installed.
MODEL_NAMES = ("ggml-small.en.bin", "ggml-base.en.bin", "ggml-base.bin", "ggml-tiny.en.bin")
VOICE_MIN_VM_MB = 900
VOICE_MIN_HOST_MB = 2600
MAX_WAV_BYTES = 16_000 * 2 * 180 + 1024   # three minutes of 16 kHz mono 16-bit audio
WORK = os.path.expanduser("~/.forge-ide/voice")
_lock = threading.Lock()   # one recognition at a time; the work file is reused, never deleted


def binary() -> Optional[str]:
    for p in (os.path.join(LIB, "src", "build", "bin", "whisper-cli"), os.path.join(LIB, "whisper-cli")):
        if os.access(p, os.X_OK):
            return p
    return None


def model() -> Optional[str]:
    for name in MODEL_NAMES:
        for d in (MODELS, os.path.join(LIB, "models")):
            p = os.path.join(d, name)
            if os.path.isfile(p) and os.path.getsize(p) > 10_000_000:
                return p
    found = sorted(glob.glob(os.path.join(MODELS, "ggml-*.bin")))
    return found[0] if found else None


def status() -> dict:
    return {"available": bool(binary() and model()), "binary": bool(binary()),
            "model": os.path.basename(model() or "") or None}


def transcribe(wav: bytes, language: str = "en", timeout: float = 90.0) -> str:
    """Transcribe a 16 kHz mono 16-bit WAV file. Raises RuntimeError with a readable message on failure."""
    exe, mdl = binary(), model()
    if not exe or not mdl:
        raise RuntimeError("Voice input in the browser needs whisper.cpp and a speech model, and they are not "
                           "installed. In the Forge app, voice input uses Android's speech recognizer instead.")
    if len(wav) < 1000 or wav[:4] != b"RIFF":
        raise RuntimeError("The recording is empty or not a WAV file.")
    if len(wav) > MAX_WAV_BYTES:
        raise RuntimeError("The recording is longer than three minutes.")
    with _lock:
        os.makedirs(WORK, exist_ok=True)
        path = os.path.join(WORK, "last.wav")
        with open(path, "wb") as fh:
            fh.write(wav)
        cmd = ["nice", "-n", "5", exe, "-m", mdl, "-f", path, "-t", str(min(4, os.cpu_count() or 2)), "-nt", "-np",
               "-l", "en" if mdl.endswith(".en.bin") else (language or "auto")]
        try:
            # run() reads stdout and stderr while whisper writes them and kills it at the time limit.
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=WORK)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Speech recognition took too long and was stopped.") from None
        if r.returncode != 0:
            last = ((r.stderr or "").strip().splitlines() or [""])[-1]
            raise RuntimeError(("Speech recognition failed: " + last[:300]) if last else "Speech recognition failed.")
        text = " ".join(line.strip() for line in r.stdout.splitlines() if line.strip())
        # whisper marks silence and noise with bracketed tags; drop them.
        text = re.sub(r"\[(?:BLANK_AUDIO|MUSIC|NOISE|SILENCE|INAUDIBLE)[^\]]*\]|\((?:silence|music|noise)\)", "", text,
                      flags=re.I)
        return " ".join(text.split())
