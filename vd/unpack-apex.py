#!/usr/bin/env python3
"""unpack-apex.py: prepares the system packages (APEXes) of a Google system image for a container (part of Forge IDE).

    unpack-apex.py <apex folder> <output folder>

Google ships most packages compressed: a compressed APEX (named .capex, or sometimes .apex) is a zip with an
"original_apex" entry, which Android unpacks on the first boot after checking the unpacked file's SELinux label.
Redroid's SELinux library, which Forge uses, does not report labels, so the packages are unpacked here in the same
way. The input is only read.

Prints "leave <file name>" for each compressed package (the image must not take it from the apex folder) and
"use <file name>" for its unpacked copy in the output folder.
"""
import argparse
import os
import shutil
import sys
import zipfile


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apex_dir")
    ap.add_argument("out")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for name in sorted(os.listdir(a.apex_dir)):
        if not name.endswith((".apex", ".capex")):
            continue
        path = os.path.join(a.apex_dir, name)
        try:
            with zipfile.ZipFile(path) as z:
                if "original_apex" not in z.namelist():
                    continue   # already a plain package
                out = os.path.join(a.out, name.rsplit(".", 1)[0] + ".apex")
                if not (os.path.exists(out) and os.path.getsize(out) > 0):
                    with z.open("original_apex") as src, open(out + ".part", "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
                    os.replace(out + ".part", out)
        except zipfile.BadZipFile:
            continue
        print(f"leave {name}")
        print(f"use {os.path.basename(out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
