"""A minimal RFC 6455 WebSocket server implementation for the standard-library HTTP server."""
from __future__ import annotations

import base64
import hashlib
import os
import socket
import struct
import threading
from typing import Optional, Tuple

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def accept_key(key: str) -> str:
    return base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()


class WebSocket:
    """Wraps an already-upgraded socket. Thread-safe sends; one reader at a time."""

    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.sock.settimeout(None)
        self._lock = threading.Lock()
        self.closed = False

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("socket closed")
            buf += chunk
        return buf

    def recv(self) -> Tuple[int, bytes]:
        """Return (opcode, payload). Handles fragmentation, ping and pong. Opcode 8 means close."""
        message = b""
        msg_op = None
        while True:
            b1, b2 = self._recv_exact(2)
            fin = b1 & 0x80
            op = b1 & 0x0F
            masked = b2 & 0x80
            ln = b2 & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._recv_exact(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._recv_exact(8))[0]
            if ln > 16 * 1024 * 1024:
                self.close(1009)
                raise ConnectionError("frame too large")
            mask = self._recv_exact(4) if masked else None
            data = self._recv_exact(ln) if ln else b""
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if op == 0x8:
                return 8, data
            if op == 0x9:
                self._send_frame(0xA, data)
                continue
            if op == 0xA:
                continue
            if op in (0x1, 0x2):
                msg_op = op
                message = data
            elif op == 0x0:
                message += data
            if fin:
                return msg_op or 1, message

    def _send_frame(self, op: int, data: bytes) -> None:
        header = bytes([0x80 | op])
        n = len(data)
        if n < 126:
            header += bytes([n])
        elif n < 65536:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        with self._lock:
            if self.closed:
                return
            try:
                self.sock.sendall(header + data)
            except OSError:
                self.closed = True
                raise

    def send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8", errors="replace"))

    def send_bytes(self, data: bytes) -> None:
        self._send_frame(0x2, data)

    def close(self, code: int = 1000) -> None:
        if self.closed:
            return
        try:
            self._send_frame(0x8, struct.pack(">H", code))
        except OSError:
            pass
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def handshake(handler) -> Optional[WebSocket]:
    """Perform the upgrade on a BaseHTTPRequestHandler. Returns a WebSocket or None (and a 400 reply)."""
    key = handler.headers.get("Sec-WebSocket-Key")
    if not key or "websocket" not in handler.headers.get("Upgrade", "").lower():
        handler.send_response(400)
        handler.end_headers()
        return None
    resp = ("HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_key(key)}\r\n\r\n")
    handler.wfile.write(resp.encode())
    handler.wfile.flush()
    handler.close_connection = True
    return WebSocket(handler.connection)
