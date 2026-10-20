"""File encodings and line endings for the editor (Cursor's "Change File Encoding" and "Change End of Line Sequence").

A file is read as bytes and decoded: a byte order mark decides (UTF-8 with BOM, UTF-16 LE or BE); otherwise UTF-8 when
the bytes are valid UTF-8, and ISO 8859-1 when they are not, because that decoding keeps every byte, so saving the
file again never changes a byte the editor did not touch. The editor works with "\n" line ends; the file's own line
ending (LF or CRLF, whichever the file uses most) is reported and used again when the file is saved.
"""
import codecs
from typing import List, Optional, Tuple

# Forge's encoding ids, the codec that reads and writes them, and the name Cursor shows.
ENCODINGS: List[Tuple[str, str, str]] = [
    ("utf8", "utf-8", "UTF-8"),
    ("utf8bom", "utf-8", "UTF-8 with BOM"),
    ("utf16le", "utf-16-le", "UTF-16 LE"),
    ("utf16be", "utf-16-be", "UTF-16 BE"),
    ("windows1252", "cp1252", "Western (Windows 1252)"),
    ("iso88591", "iso-8859-1", "Western (ISO 8859-1)"),
    ("iso885915", "iso-8859-15", "Western (ISO 8859-15)"),
    ("macroman", "mac_roman", "Western (Mac Roman)"),
    ("cp437", "cp437", "DOS (CP 437)"),
    ("cp850", "cp850", "Western European DOS (CP 850)"),
    ("windows1250", "cp1250", "Central European (Windows 1250)"),
    ("iso88592", "iso-8859-2", "Central European (ISO 8859-2)"),
    ("windows1251", "cp1251", "Cyrillic (Windows 1251)"),
    ("koi8r", "koi8-r", "Cyrillic (KOI8-R)"),
    ("koi8u", "koi8-u", "Cyrillic (KOI8-U)"),
    ("iso88595", "iso-8859-5", "Cyrillic (ISO 8859-5)"),
    ("windows1253", "cp1253", "Greek (Windows 1253)"),
    ("iso88597", "iso-8859-7", "Greek (ISO 8859-7)"),
    ("windows1254", "cp1254", "Turkish (Windows 1254)"),
    ("iso88599", "iso-8859-9", "Turkish (ISO 8859-9)"),
    ("windows1255", "cp1255", "Hebrew (Windows 1255)"),
    ("iso88598", "iso-8859-8", "Hebrew (ISO 8859-8)"),
    ("windows1256", "cp1256", "Arabic (Windows 1256)"),
    ("iso88596", "iso-8859-6", "Arabic (ISO 8859-6)"),
    ("windows1257", "cp1257", "Baltic (Windows 1257)"),
    ("iso88594", "iso-8859-4", "Baltic (ISO 8859-4)"),
    ("windows1258", "cp1258", "Vietnamese (Windows 1258)"),
    ("windows874", "cp874", "Thai (Windows 874)"),
    ("shiftjis", "shift_jis", "Japanese (Shift JIS)"),
    ("eucjp", "euc-jp", "Japanese (EUC-JP)"),
    ("gbk", "gbk", "Simplified Chinese (GBK)"),
    ("gb18030", "gb18030", "Simplified Chinese (GB 18030)"),
    ("big5hkscs", "big5hkscs", "Traditional Chinese (Big5 HKSCS)"),
    ("big5", "big5", "Traditional Chinese (Big5)"),
    ("euckr", "euc-kr", "Korean (EUC-KR)"),
]
_BY_ID = {e[0]: e for e in ENCODINGS}


def choices() -> List[dict]:
    return [{"id": i, "name": n} for i, _, n in ENCODINGS]


def name_of(enc: str) -> str:
    e = _BY_ID.get(enc)
    return e[2] if e else enc


def guess(data: bytes) -> str:
    """The encoding of a file's bytes: its byte order mark, else UTF-8 when valid, else ISO 8859-1 (keeps every byte)."""
    if data.startswith(codecs.BOM_UTF8):
        return "utf8bom"
    if data.startswith(codecs.BOM_UTF16_LE):
        return "utf16le"
    if data.startswith(codecs.BOM_UTF16_BE):
        return "utf16be"
    try:
        data.decode("utf-8")
        return "utf8"
    except UnicodeDecodeError:
        return "iso88591"


def decode(data: bytes, enc: Optional[str] = None) -> Tuple[str, str, str, bool]:
    """(text with "\n" line ends, encoding id, "LF" or "CRLF", lossless). lossless is False when bytes did not fit the
    encoding and were shown as replacement characters (saving would then change them, so the editor warns)."""
    enc = enc if enc in _BY_ID else guess(data)
    codec = _BY_ID[enc][1]
    body = data
    if enc == "utf8bom" and body.startswith(codecs.BOM_UTF8):
        body = body[len(codecs.BOM_UTF8):]
    elif enc == "utf16le" and body.startswith(codecs.BOM_UTF16_LE):
        body = body[2:]
    elif enc == "utf16be" and body.startswith(codecs.BOM_UTF16_BE):
        body = body[2:]
    try:
        text = body.decode(codec)
        lossless = True
    except UnicodeDecodeError:
        text = body.decode(codec, errors="replace")
        lossless = False
    crlf = text.count("\r\n")
    lf = text.count("\n") - crlf
    eol = "CRLF" if crlf > lf else "LF"
    return text.replace("\r\n", "\n"), enc, eol, lossless


def encode(text: str, enc: str, eol: str) -> bytes:
    """The bytes to write. Raises ValueError (with a sentence for the editor) when a character has no place in the
    encoding."""
    enc = enc if enc in _BY_ID else "utf8"
    body = text.replace("\r\n", "\n")
    if eol == "CRLF":
        body = body.replace("\n", "\r\n")
    codec = _BY_ID[enc][1]
    try:
        out = body.encode(codec)
    except UnicodeEncodeError as e:
        bad = body[e.start:e.end]
        line = body.count("\n", 0, e.start) + 1
        raise ValueError(f"{name_of(enc)} has no character for {bad!r} on line {line}. Save it with UTF-8, or remove the character.")
    if enc == "utf8bom":
        out = codecs.BOM_UTF8 + out
    elif enc == "utf16le":
        out = codecs.BOM_UTF16_LE + out
    elif enc == "utf16be":
        out = codecs.BOM_UTF16_BE + out
    return out
