"""Screenshots and images in the chat.

Llama 3.1 reads only text, so every attached image is turned into text it can use:
- OCR (tesseract) reads the words in the image: code, error messages, labels.
- A small vision model through Ollama (default qwen3-vl:2b) describes what the image shows.
Results are cached per image. If the chat model itself can see images, the image is also passed natively.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import subprocess
import time
from typing import Any, Callable, Dict, Optional

MAX_IMAGE_BYTES = 15 * 1024 * 1024
TYPES = {b"\x89PNG\r\n\x1a\n": ("png", "image/png"), b"\xff\xd8\xff": ("jpg", "image/jpeg"),
         b"GIF87a": ("gif", "image/gif"), b"GIF89a": ("gif", "image/gif")}

VISION_PROMPT = ("You are helping a programmer who cannot see this image. Describe it precisely and briefly: which "
                 "application, page or dialog it shows; transcribe any error messages, warnings, code and other important "
                 "text exactly; and point out anything that looks wrong or unusual. Use at most 200 words.")


def sniff(data: bytes) -> Optional[tuple]:
    for magic, info in TYPES.items():
        if data.startswith(magic):
            return info
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ("webp", "image/webp")
    return None


def image_size(data: bytes) -> Optional[tuple]:
    """(width, height) for PNG and JPEG without any image library."""
    try:
        if data.startswith(b"\x89PNG"):
            return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
        if data.startswith(b"\xff\xd8"):
            i = 2
            while i < len(data) - 9:
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker in (0xC0, 0xC1, 0xC2):
                    return int.from_bytes(data[i + 7:i + 9], "big"), int.from_bytes(data[i + 5:i + 7], "big")
                i += 2 + int.from_bytes(data[i + 2:i + 4], "big")
    except Exception:  # noqa: BLE001
        pass
    return None


class ImageStore:
    def __init__(self, folder: str):
        self.folder = folder
        os.makedirs(folder, exist_ok=True)

    def save(self, data: bytes, name: str = "") -> dict:
        if len(data) > MAX_IMAGE_BYTES:
            raise ValueError(f"The image is too large ({len(data) // (1024 * 1024)} MB). The limit is 15 MB.")
        kind = sniff(data)
        if not kind:
            raise ValueError("That file is not a PNG, JPEG, GIF or WebP image.")
        ext, mime = kind
        img_id = hashlib.sha256(data).hexdigest()[:20]
        path = os.path.join(self.folder, f"{img_id}.{ext}")
        if not os.path.exists(path):
            tmp = path + ".tmp"
            with open(tmp, "wb") as fh:
                fh.write(data)
            os.replace(tmp, path)
        size = image_size(data)
        meta = {"id": img_id, "ext": ext, "mime": mime, "bytes": len(data), "name": (name or "")[:120],
                "width": size[0] if size else None, "height": size[1] if size else None}
        mp = os.path.join(self.folder, f"{img_id}.meta.json")
        if not os.path.exists(mp):
            with open(mp, "w", encoding="utf-8") as fh:
                json.dump(meta, fh)
        return meta

    def find(self, img_id: str) -> Optional[tuple]:
        if not img_id or not all(c in "0123456789abcdef" for c in img_id):
            return None
        for ext, mime in (("png", "image/png"), ("jpg", "image/jpeg"), ("gif", "image/gif"), ("webp", "image/webp")):
            p = os.path.join(self.folder, f"{img_id}.{ext}")
            if os.path.exists(p):
                return p, mime
        return None

    def cached_analysis(self, img_id: str) -> dict:
        try:
            with open(os.path.join(self.folder, f"{img_id}.analysis.json"), "r", encoding="utf-8") as fh:
                return json.load(fh)
        except (OSError, json.JSONDecodeError):
            return {}

    def save_analysis(self, img_id: str, data: dict) -> None:
        p = os.path.join(self.folder, f"{img_id}.analysis.json")
        tmp = p + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False)
        os.replace(tmp, p)


def run_ocr(path: str, timeout: float = 60.0) -> str:
    if not shutil.which("tesseract"):
        return ""
    try:
        r = subprocess.run(["tesseract", path, "stdout", "-l", "eng", "--psm", "3"], capture_output=True, text=True,
                           timeout=timeout, errors="replace")
    except (subprocess.TimeoutExpired, OSError):
        return ""
    lines = [l.rstrip() for l in r.stdout.splitlines()]
    text = "\n".join(l for l in lines if l.strip())
    return text[:8000]


def describe_with_vision(ollama, model: str, path: str, keep_alive: str, timeout: float = 300.0,
                         prompt: str = VISION_PROMPT) -> str:
    with open(path, "rb") as fh:
        b64 = base64.b64encode(fh.read()).decode()
    body = {"model": model, "stream": False, "keep_alive": keep_alive, "think": False,
            "messages": [{"role": "user", "content": prompt, "images": [b64]}],
            "options": {"temperature": 0.1, "num_ctx": 4096, "num_predict": 450}}
    r = ollama._json("POST", "/api/chat", body, timeout=timeout)
    return str((r.get("message") or {}).get("content", "")).strip()
