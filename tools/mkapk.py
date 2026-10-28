#!/usr/bin/env python3
"""Build a signed-ready APK zip with correct zipalign-style alignment.

Reads an APK produced by `aapt2 link` (manifest + resources), adds classes.dex,
stores resources.arsc uncompressed on a 4-byte boundary and native libraries on
a 16 KB boundary. No external zip/zipalign binaries needed.
"""
import argparse
import struct
import sys
import time
import zipfile
import zlib

LOCAL_ALIGN = {"resources.arsc": 4}


def align_of(name):
    if name == "resources.arsc":
        return 4
    if name.startswith("lib/") and name.endswith(".so"):
        return 16384
    return 0


def dos_time(t):
    year, month, day, hour, minute, second = t[:6]
    if year < 1980:
        year, month, day, hour, minute, second = 1980, 1, 1, 0, 0, 0
    d = ((year - 1980) << 9) | (month << 5) | day
    tm = (hour << 11) | (minute << 5) | (second // 2)
    return tm, d


def read_zip(path):
    entries = []
    with zipfile.ZipFile(path) as zf:
        for zi in zf.infolist():
            if zi.is_dir():
                continue
            entries.append({
                "name": zi.filename,
                "data": zf.read(zi.filename),
                "method": zi.compress_type,
                "date": zi.date_time,
                "attr": (zi.external_attr >> 16) or 0o100644,
            })
    return entries


def build(entries, out_path):
    body = bytearray()
    central = bytearray()
    written = {}

    for e in entries:
        name = e["name"].encode("utf-8")
        data = e["data"]
        align = align_of(e["name"])
        method = e["method"]
        if align:
            method = zipfile.ZIP_STORED
        if method == zipfile.ZIP_DEFLATED:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
        else:
            payload = data
        crc = zlib.crc32(data) & 0xFFFFFFFF
        tm, dm = dos_time(e["date"])
        flags = 0x0800  # UTF-8 names

        offset = len(body)
        base = offset + 30 + len(name)
        pad = 0
        if align:
            pad = (-base) % align
            if 0 < pad < 4:
                pad += align
        extra = b""
        if pad:
            extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)

        body += struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, method,
                            tm, dm, crc, len(payload), len(data), len(name), len(extra))
        body += name + extra + payload

        central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, flags, method,
                               tm, dm, crc, len(payload), len(data), len(name),
                               0, 0, 0, 0, e["attr"] << 16, offset)
        central += name
        written[e["name"]] = (offset, method, len(extra))

    cd_offset = len(body)
    body += central
    body += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries),
                        len(central), cd_offset, 0)

    with open(out_path, "wb") as f:
        f.write(body)

    # sanity check alignment
    with open(out_path, "rb") as f:
        raw = f.read()
    ok = True
    for name, (offset, method, extralen) in written.items():
        namelen = len(name.encode("utf-8"))
        start = offset + 30 + namelen + extralen
        need = align_of(name)
        if need and start % need != 0:
            print("ALIGN FAIL:", name, start, need, file=sys.stderr)
            ok = False
        if name == "resources.arsc" and method != 0:
            print("ERROR: resources.arsc is compressed", file=sys.stderr)
            ok = False
    if not ok:
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--dex", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    entries = read_zip(args.base)
    with open(args.dex, "rb") as f:
        dex = f.read()
    entries.append({
        "name": "classes.dex",
        "data": dex,
        "method": zipfile.ZIP_DEFLATED,
        "date": tuple(time.localtime()[:6]),
        "attr": 0o100644,
    })
    build(entries, args.out)
    print("Wrote", args.out)


if __name__ == "__main__":
    main()
