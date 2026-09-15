#!/usr/bin/env python3
"""Extract read-only ELM327/UDS queries from Android Bluetooth HCI snoop logs.

Accepts an Android bugreport ZIP or a raw btsnoop_hci.log. It intentionally
exports only Mode 01 and UDS 22 queries. No external Python packages are used.
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

HEADER = b"btsnoop\0"
COMMAND_RE = re.compile(rb"(?i)(AT[A-Z0-9]+|22(?:[0-9A-F]{4})+(?:[0-9A-F])?|01[0-9A-F]{2}(?:[0-9A-F])?|0902)\r")
READ_RE = re.compile(r"^(22)([0-9A-F]{4}(?:[0-9A-F]{4})*)([0-9A-F])?$|^(01)([0-9A-F]{2})([0-9A-F])?$")


@dataclass
class Record:
    index: int
    flags: int
    timestamp: int
    packet: bytes

    @property
    def direction(self) -> int:
        return self.flags & 1


@dataclass
class Context:
    protocol: int = 7
    header: str = ""
    priority: str = "1D"
    rx_filter: str = ""
    fc_header: str = ""
    fc_data: str = "300000"
    fc_mode: int = 1

    def as_json(self) -> dict:
        names = {"D01635": "BECM", "D01601": "VCFRONT", "D01650": "ECU-D", "D01701": "ECU-E", "D01637": "ECU-F"}
        return {
            "name": names.get(self.header, f"HCI-{self.header or 'UNKNOWN'}"),
            "protocol": self.protocol,
            "header": self.header,
            "priority": self.priority,
            "rxFilter": self.rx_filter,
            "flowControlHeader": self.fc_header,
            "flowControlData": self.fc_data,
            "flowControlMode": self.fc_mode,
        }


@dataclass
class Observation:
    service: str
    did: str
    context: Context
    count: int = 0
    response: str = ""


def load_snoop(path: Path) -> bytes:
    if not zipfile.is_zipfile(path):
        return path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        candidates = [name for name in archive.namelist() if name.lower().endswith("btsnoop_hci.log")]
        if not candidates:
            raise ValueError("Bugreport ZIP içinde btsnoop_hci.log bulunamadı")
        return archive.read(sorted(candidates, key=len)[0])


def parse_records(data: bytes) -> list[Record]:
    if len(data) < 16 or data[:8] != HEADER:
        raise ValueError("Geçerli btsnoop başlığı bulunamadı")
    version, _datalink = struct.unpack_from(">II", data, 8)
    if version != 1:
        raise ValueError(f"Desteklenmeyen btsnoop sürümü: {version}")
    records: list[Record] = []
    offset = 16
    index = 0
    while offset + 24 <= len(data):
        original_len, included_len, flags, _drops, timestamp = struct.unpack_from(">IIIIQ", data, offset)
        offset += 24
        if included_len > original_len or offset + included_len > len(data):
            break
        records.append(Record(index, flags, timestamp, data[offset:offset + included_len]))
        offset += included_len
        index += 1
    return records


def command_events(records: list[Record]) -> tuple[int, list[tuple[int, str]]]:
    by_direction: dict[int, list[tuple[int, str]]] = {0: [], 1: []}
    tails = {0: b"", 1: b""}
    for record in records:
        direction = record.direction
        previous_tail = tails[direction]
        searchable = previous_tail + record.packet
        for match in COMMAND_RE.finditer(searchable):
            if match.end() <= len(previous_tail):
                continue
            command = match.group(1).decode("ascii").upper()
            by_direction[direction].append((record.index, command))
        tails[direction] = searchable[-80:]
    outbound = max(by_direction, key=lambda key: len(by_direction[key]))
    deduped: list[tuple[int, str]] = []
    for event in by_direction[outbound]:
        if not deduped or event != deduped[-1]:
            deduped.append(event)
    return outbound, deduped


def printable_response(records: list[Record], direction: int, start: int, end: int) -> str:
    payload = b"".join(record.packet for record in records if record.direction == direction and start <= record.index < end)
    runs = re.findall(rb"[ -~]{2,}", payload)
    text = " ".join(run.decode("ascii", errors="ignore") for run in runs)
    return re.sub(r"\s+", " ", text).strip()[:512]


def apply_at(context: Context, command: str) -> None:
    if command.startswith("ATSP") and command[4:].isdigit():
        context.protocol = int(command[4:])
    elif command.startswith("ATSH"):
        context.header = command[4:]
    elif command.startswith("ATCP"):
        context.priority = command[4:]
    elif command.startswith("ATCRA"):
        context.rx_filter = command[5:]
    elif command.startswith("ATFCSH"):
        context.fc_header = command[6:]
    elif command.startswith("ATFCSD"):
        context.fc_data = command[6:]
    elif command.startswith("ATFCSM") and command[6:].isdigit():
        context.fc_mode = int(command[6:])


def extract_profile(records: list[Record], source_session: str) -> dict:
    outbound, events = command_events(records)
    context = Context()
    observations: dict[tuple, Observation] = collections.OrderedDict()
    for position, (record_index, command) in enumerate(events):
        if command.startswith("AT"):
            apply_at(context, command)
            continue
        match = READ_RE.match(command)
        if not match:
            continue
        service = match.group(1) or match.group(4)
        did = match.group(2) or match.group(5)
        if service == "22" and not context.header:
            continue
        next_index = events[position + 1][0] if position + 1 < len(events) else len(records)
        response = printable_response(records, 1 - outbound, record_index, next_index)
        if command in {"0902", "22F190"}:
            response = "[REDACTED]"
        snapshot = Context(**context.__dict__)
        key = (service, did, snapshot.protocol, snapshot.header, snapshot.rx_filter)
        observation = observations.get(key)
        if observation is None:
            observation = Observation(service, did, snapshot)
            observations[key] = observation
        observation.count += 1
        if response and not observation.response:
            observation.response = response
    return {
        "profileVersion": 1,
        "sourceSession": source_session,
        "queries": [
            {
                "service": item.service,
                "did": item.did,
                "observedResponse": item.response,
                "observationCount": item.count,
                "ecu": item.context.as_json() if item.service == "22" else None,
            }
            for item in observations.values()
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Car Scanner HCI kaydından salt-okunur EX30 sorgu profili çıkarır")
    parser.add_argument("input", type=Path, help="Android bugreport ZIP veya btsnoop_hci.log")
    parser.add_argument("output", type=Path, help="Yazılacak JSON profil")
    args = parser.parse_args(argv)
    try:
        records = parse_records(load_snoop(args.input))
        profile = extract_profile(records, args.input.name)
        if not profile["queries"]:
            raise ValueError("Tam salt-okunur sorgu bulunamadı; kırpılmış/eksik HCI kaydından profil üretilemez")
        args.output.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{len(profile['queries'])} salt-okunur sorgu yazıldı: {args.output}")
        return 0
    except Exception as exc:
        print(f"Hata: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
