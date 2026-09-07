#!/usr/bin/env python3
"""Extract EX30 scan profile from Android dumpsys btsnooz (Car Scanner binary framing)."""

from __future__ import annotations

import argparse
import base64
import json
import re
import struct
import sys
import zlib
from collections import OrderedDict
from pathlib import Path

DEFAULT_ECU = {
    "name": "ECU-E",
    "protocol": 7,
    "header": "D01701",
    "priority": "1D",
    "rxFilter": "1EE02E80",
    "flowControlHeader": "1DD01701",
    "flowControlData": "300000",
    "flowControlMode": 1,
}


def extract_snooz_blob(text: str) -> bytes:
    match = re.search(
        r"--- BEGIN:BTSNOOP_LOG_SUMMARY \(\d+ bytes in\) ---\n(.*?)\n--- END:BTSNOOP_LOG_SUMMARY ---",
        text,
        re.S,
    )
    if not match:
        raise ValueError("dumpsys çıktısında BTSNOOP_LOG_SUMMARY yok")
    return base64.b64decode(re.sub(r"\s+", "", match.group(1)))


def decompress_snooz(snooz: bytes) -> bytes:
    if len(snooz) < 10:
        raise ValueError("btsnooz verisi çok kısa")
    return zlib.decompress(snooz[9:])


def extract_binary_queries(decompressed: bytes) -> OrderedDict[str, int]:
    """Car Scanner IOS-Vlink binary frame: 11 00 22 <did_hi> <did_lo> ..."""
    counts: OrderedDict[str, int] = OrderedDict()
    for match in re.finditer(rb"\x11\x00\x22(.)(.)", decompressed, re.S):
        did = f"{match.group(1).hex()}{match.group(2).hex()}".upper()
        counts[did] = counts.get(did, 0) + 1
    return counts


def build_profile(counts: OrderedDict[str, int], source: str) -> dict:
    queries = []
    for did, count in counts.items():
        queries.append(
            {
                "service": "22",
                "did": did,
                "observedResponse": "",
                "observationCount": count,
                "ecu": dict(DEFAULT_ECU),
            }
        )
    return {
        "profileVersion": 1,
        "sourceSession": source,
        "queries": queries,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="dumpsys btsnooz → ex30-profile.json (Car Scanner binary)")
    parser.add_argument("input", type=Path, help="dumpsys bluetooth_manager dosyası")
    parser.add_argument("output", type=Path, help="çıktı JSON profil")
    args = parser.parse_args(argv)

    text = args.input.read_text(encoding="utf-8", errors="replace")
    snooz = extract_snooz_blob(text)
    decompressed = decompress_snooz(snooz)
    counts = extract_binary_queries(decompressed)
    if not counts:
        print("Hata: Car Scanner binary UDS sorgusu bulunamadı", file=sys.stderr)
        return 1

    profile = build_profile(counts, args.input.name)
    args.output.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(profile['queries'])} sorgu yazıldı: {args.output}")
    for query in profile["queries"][:12]:
        print(f"  22{query['did']} x{query['observationCount']}")
    if len(profile["queries"]) > 12:
        print(f"  … +{len(profile['queries']) - 12} daha")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
