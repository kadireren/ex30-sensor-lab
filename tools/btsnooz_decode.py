#!/usr/bin/env python3
"""Decode Android btsnooz (dumpsys BTSNOOP_LOG_SUMMARY) into btsnoop_hci.log."""

from __future__ import annotations

import argparse
import base64
import re
import struct
import sys
import zlib
from pathlib import Path

BTSNOOP_HEADER = b"btsnoop\x00\x00\x00\x00\x01\x00\x00\x03\xea"

TYPE_IN_EVT = 0x10
TYPE_IN_ACL = 0x11
TYPE_IN_SCO = 0x12
TYPE_OUT_CMD = 0x20
TYPE_OUT_ACL = 0x21
TYPE_OUT_SCO = 0x22


def type_to_hci(packet_type: int) -> bytes:
    if packet_type == TYPE_OUT_CMD:
        return b"\x01"
    if packet_type in (TYPE_IN_ACL, TYPE_OUT_ACL):
        return b"\x02"
    if packet_type in (TYPE_IN_SCO, TYPE_OUT_SCO):
        return b"\x03"
    if packet_type == TYPE_IN_EVT:
        return b"\x04"
    return b"\x00"


def direction_flags(packet_type: int) -> int:
    if packet_type in (TYPE_OUT_CMD, TYPE_OUT_ACL, TYPE_OUT_SCO):
        return 0
    return 1


def write_btsnoop_record(
    out: BinaryIO,
    packet: bytes,
    flags: int,
    timestamp_us: int,
    original_length: int | None = None,
) -> None:
    out.write(struct.pack(">IIIIQ", original_length or len(packet), len(packet), flags, 0, timestamp_us))
    out.write(packet)


def decode_snooz_v1(decompressed: bytes, last_timestamp_ms: int, out: BinaryIO) -> None:
    offset = 0
    timestamp_us = last_timestamp_ms + 0x00DC_DDB3_0F2F_8000
    while offset + 7 <= len(decompressed):
        length, delta_time_ms, _packet_type = struct.unpack_from("=HIb", decompressed, offset)
        if length < 1 or offset + 7 + length - 1 > len(decompressed):
            break
        timestamp_us -= delta_time_ms
        offset += 7 + length - 1
    offset = 0
    while offset + 7 <= len(decompressed):
        length, delta_time_ms, packet_type = struct.unpack_from("=HIb", decompressed, offset)
        offset += 7
        data_length = length - 1
        if data_length < 0 or offset + data_length > len(decompressed):
            break
        timestamp_us += delta_time_ms
        packet = type_to_hci(packet_type) + decompressed[offset : offset + data_length]
        offset += data_length
        write_btsnoop_record(out, packet, direction_flags(packet_type), timestamp_us)


def decode_snooz_v2(decompressed: bytes, last_timestamp_ms: int, out: BinaryIO) -> None:
    offset = 0
    timestamp_us = last_timestamp_ms + 0x00DC_DDB3_0F2F_8000
    while offset + 9 <= len(decompressed):
        included_length, _packet_length, delta_time_ms, _packet_type = struct.unpack_from(
            "=HHIb", decompressed, offset
        )
        if included_length < 1 or offset + 9 + included_length - 1 > len(decompressed):
            break
        timestamp_us -= delta_time_ms
        offset += 9 + included_length - 1
    offset = 0
    while offset + 9 <= len(decompressed):
        included_length, packet_length, delta_time_ms, packet_type = struct.unpack_from(
            "=HHIb", decompressed, offset
        )
        offset += 9
        data_length = included_length - 1
        if data_length < 0 or offset + data_length > len(decompressed):
            break
        timestamp_us += delta_time_ms
        packet = type_to_hci(packet_type) + decompressed[offset : offset + data_length]
        offset += data_length
        write_btsnoop_record(
            out,
            packet,
            direction_flags(packet_type),
            timestamp_us,
            original_length=packet_length,
        )


def decode_snooz(snooz: bytes, out: BinaryIO) -> None:
    if len(snooz) < 10:
        raise ValueError("btsnooz verisi çok kısa")
    version, last_timestamp_ms = struct.unpack_from("=bQ", snooz)
    decompressed = zlib.decompress(snooz[9:])
    out.write(BTSNOOP_HEADER)
    if version == 1:
        decode_snooz_v1(decompressed, last_timestamp_ms, out)
    elif version == 2:
        decode_snooz_v2(decompressed, last_timestamp_ms, out)
    else:
        raise ValueError(f"Desteklenmeyen btsnooz sürümü: {version}")


def extract_btsnooz_from_dumpsys(text: str) -> bytes:
    match = re.search(
        r"--- BEGIN:BTSNOOP_LOG_SUMMARY \(\d+ bytes in\) ---\n(.*?)\n--- END:BTSNOOP_LOG_SUMMARY ---",
        text,
        re.S,
    )
    if not match:
        raise ValueError("dumpsys çıktısında BTSNOOP_LOG_SUMMARY yok")
    payload = re.sub(r"\s+", "", match.group(1))
    return base64.b64decode(payload)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="btsnooz → btsnoop_hci.log dönüştürücü")
    parser.add_argument("input", type=Path, help="dumpsys bluetooth_manager metin dosyası veya ham btsnooz")
    parser.add_argument("output", type=Path, help="çıktı btsnoop_hci.log")
    args = parser.parse_args(argv)

    raw = args.input.read_bytes()
    if raw.startswith(b"---") or b"BTSNOOP_LOG_SUMMARY" in raw:
        snooz = extract_btsnooz_from_dumpsys(raw.decode("utf-8", errors="replace"))
    else:
        snooz = raw

    with args.output.open("wb") as out:
        decode_snooz(snooz, out)

    size = args.output.stat().st_size
    print(f"btsnoop yazıldı: {args.output} ({size} byte)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
