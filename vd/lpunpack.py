#!/usr/bin/env python3
"""lpunpack.py: extracts the logical partitions (system, system_ext, product, ...) from an Android "super" partition,
following AOSP's documented liblp format (part of Forge IDE). It only reads the input.

    lpunpack.py <image> <output folder> [--offset BYTES] [--only name,name]

--offset is where the super partition starts inside a disk image (Google's emulator images keep it in GPT
partition 2). Linear extents are copied and zero extents are written as holes.
"""
import argparse
import os
import struct
import sys

GEOMETRY_MAGIC = 0x616C4467
HEADER_MAGIC = 0x414C5030
RESERVED = 4096          # LP_PARTITION_RESERVED_BYTES
GEOMETRY_SIZE = 4096     # LP_METADATA_GEOMETRY_SIZE
SECTOR = 512


def read_at(f, offset: int, size: int) -> bytes:
    f.seek(offset)
    data = f.read(size)
    if len(data) != size:
        raise ValueError(f"short read at {offset}")
    return data


def table(blob: bytes, desc: tuple, fmt: str) -> list:
    offset, count, entry_size = desc
    return [struct.unpack_from(fmt, blob, offset + i * entry_size) for i in range(count)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("out")
    ap.add_argument("--offset", type=int, default=0)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    only = {x for x in a.only.split(",") if x}
    with open(a.image, "rb") as f:
        magic, _size = struct.unpack("<II", read_at(f, a.offset + RESERVED, 8))
        if magic != GEOMETRY_MAGIC:
            print("No super partition geometry at this offset.", file=sys.stderr)
            return 1
        hdr_off = a.offset + RESERVED + 2 * GEOMETRY_SIZE   # metadata slot 0
        head = read_at(f, hdr_off, 256)
        magic, major, minor, header_size = struct.unpack_from("<IHHI", head, 0)
        if magic != HEADER_MAGIC:
            print("No metadata header in slot 0.", file=sys.stderr)
            return 1
        tables_size = struct.unpack_from("<I", head, 44)[0]
        descs = [struct.unpack_from("<III", head, 80 + 12 * i) for i in range(4)]   # partitions, extents, groups, devices
        tables = read_at(f, hdr_off + header_size, tables_size)
        parts = table(tables, descs[0], "<36sIIII")
        extents = table(tables, descs[1], "<QIQI")
        os.makedirs(a.out, exist_ok=True)
        for name_raw, _attrs, first, num, _group in parts:
            name = name_raw.split(b"\0", 1)[0].decode()
            if only and name not in only:
                continue
            if num == 0:
                print(f"{name}: empty, skipped")
                continue
            path = os.path.join(a.out, name + ".img")
            total = 0
            with open(path, "wb") as out:
                for sectors, ttype, tdata, _src in extents[first:first + num]:
                    length = sectors * SECTOR
                    if ttype == 0:   # linear: copy from the super partition
                        f.seek(a.offset + tdata * SECTOR)
                        left = length
                        while left:
                            chunk = f.read(min(left, 1 << 20))
                            if not chunk:
                                raise ValueError(f"{name}: extent runs past the end of the image")
                            out.write(chunk)
                            left -= len(chunk)
                    else:            # zero extent: leave a hole
                        out.seek(length, os.SEEK_CUR)
                    total += length
                out.truncate(total)
            print(f"{name}: {total // (1 << 20)} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
