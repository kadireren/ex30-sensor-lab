#!/usr/bin/env python3
"""Mac + IOS-Vlink ile EX30 throttle keşfi için salt-okunur saha sihirbazı.

Birincil yol ELM327 ATMA ile pasif CAN dinleyip dört pedal fazını karşılaştırır.
Yedek yol yalnız UDS 22F190 okuyarak cevap veren ECU adreslerini keşfeder.
Bleak yalnız araçta BLE bağlantısı kurulurken yüklenir; analiz/test saf Python'dır.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import datetime as dt
import os
import re
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


DEFAULT_ADDRESS = "6DAE797C-D699-E0A3-8515-80D156FF31F8"
RX_UUID = "00002af0-0000-1000-8000-00805f9b34fb"
TX_UUID = "00002af1-0000-1000-8000-00805f9b34fb"
KNOWN_ECU_LOW16 = (
    0x1601, 0x1635, 0x1637, 0x1640, 0x1645, 0x1650, 0x1660, 0x1665,
    0x1670, 0x1680, 0x16E0, 0x16F0, 0x1700, 0x1701, 0x1710, 0x1720,
    0x1780, 0x1790, 0x17A0,
)
PHASES = (
    ("BOS", "Gaz pedalına hiç dokunma; pedal yüzde 0 olsun."),
    ("YUZDE25", "Gaz pedalına yaklaşık dörtte bir bas ve basılı tut."),
    ("YUZDE50", "Gaz pedalına yaklaşık yarıya kadar bas ve basılı tut."),
    ("BIRAK", "Ayağını gazdan tamamen çek; pedal yeniden yüzde 0 olsun."),
)
IGNORED_MONITOR_TEXT = (
    "SEARCHING", "STOPPED", "BUFFER FULL", "NO DATA", "CAN ERROR",
    "BUS ERROR", "UNABLE TO CONNECT", "ERROR", "OK", "?",
)


@dataclass(frozen=True)
class CanFrame:
    can_id: str
    payload: bytes


@dataclass(frozen=True)
class Candidate:
    can_id: str
    payload_length: int
    start_byte: int
    width: int
    byte_order: str
    rest: float
    pedal25: float
    pedal50: float
    release: float
    score: float
    samples: tuple[int, int, int, int]


def parse_can_frames(text: str) -> list[CanFrame]:
    """ELM ATMA satırlarını 11/29-bit CAN kimliği ve ham veriye ayırır."""
    frames: list[CanFrame] = []
    for original in text.upper().replace(">", "\n").splitlines():
        line = original.strip()
        if not line or any(marker in line for marker in IGNORED_MONITOR_TEXT):
            continue
        tokens = re.findall(r"[0-9A-F]+", line)
        if not tokens:
            continue
        can_id = tokens[0]
        data_tokens = tokens[1:]
        if len(can_id) not in (3, 8):
            compact = re.sub(r"[^0-9A-F]", "", line)
            if len(compact) >= 10:
                can_id, compact_data = compact[:8], compact[8:]
                data_tokens = [compact_data]
            else:
                continue
        if data_tokens and len(data_tokens[0]) == 1:
            declared = int(data_tokens[0], 16)
            possible = "".join(data_tokens[1:])
            if len(possible) == declared * 2:
                data_tokens = data_tokens[1:]
        payload_hex = "".join(data_tokens)
        if not payload_hex or len(payload_hex) % 2 or not re.fullmatch(r"[0-9A-F]+", payload_hex):
            continue
        try:
            payload = bytes.fromhex(payload_hex)
        except ValueError:
            continue
        if 0 < len(payload) <= 64:
            frames.append(CanFrame(can_id, payload))
    return frames


def _median_values(frames: list[CanFrame], start: int, width: int, order: str) -> list[int]:
    return [
        int.from_bytes(frame.payload[start:start + width], order)
        for frame in frames
        if len(frame.payload) >= start + width
    ]


def _mad(values: list[int]) -> float:
    if not values:
        return float("inf")
    median = statistics.median(values)
    return statistics.median(abs(value - median) for value in values)


def rank_candidates(frames_by_phase: dict[str, list[CanFrame]], limit: int = 40) -> list[Candidate]:
    """0 -> yüzde25 -> yüzde50 -> 0 desenine uyan byte/word alanlarını sıralar."""
    required = tuple(name for name, _ in PHASES)
    grouped: dict[str, dict[tuple[str, int], list[CanFrame]]] = {
        phase: defaultdict(list) for phase in required
    }
    for phase in required:
        for frame in frames_by_phase.get(phase, []):
            grouped[phase][(frame.can_id, len(frame.payload))].append(frame)
    common = set(grouped[required[0]])
    for phase in required[1:]:
        common &= set(grouped[phase])

    candidates: list[Candidate] = []
    for can_id, payload_length in sorted(common):
        for width in (1, 2):
            orders = ("big",) if width == 1 else ("big", "little")
            for start in range(payload_length - width + 1):
                for order in orders:
                    series = [
                        _median_values(grouped[phase][(can_id, payload_length)], start, width, order)
                        for phase in required
                    ]
                    if any(len(values) < 3 for values in series):
                        continue
                    medians = [float(statistics.median(values)) for values in series]
                    rest, pedal25, pedal50, release = medians
                    direction = 1 if pedal50 >= rest else -1
                    rise25 = direction * (pedal25 - rest)
                    rise50 = direction * (pedal50 - pedal25)
                    span = direction * (pedal50 - rest)
                    release_error = abs(release - rest)
                    noise = sum(_mad(values) for values in series) / 4.0
                    minimum_span = 2 if width == 1 else 8
                    if rise25 <= 0 or rise50 <= 0 or span < minimum_span:
                        continue
                    if release_error > max(2.0, span * 0.20):
                        continue
                    if noise > max(2.0, span * 0.25):
                        continue
                    balance = min(rise25, rise50) / max(rise25, rise50)
                    score = span * (0.5 + balance) / (1.0 + noise + release_error)
                    candidates.append(Candidate(
                        can_id, payload_length, start, width, order,
                        rest, pedal25, pedal50, release, score,
                        tuple(len(values) for values in series),
                    ))
    candidates.sort(key=lambda item: item.score, reverse=True)
    return candidates[:limit]


def derive_ecu(low16: int) -> tuple[str, str, str]:
    if not 0x1601 <= low16 <= 0x17FF:
        raise ValueError("ECU adresi 1601..17FF dışında")
    header = f"D0{low16:04X}"
    rx_filter = f"{0x1EC02E80 + ((low16 - 0x1601) << 13):08X}"
    return header, rx_filter, f"1D{header}"


class ElmBle:
    def __init__(self, client):
        self.client = client
        self.buffer = bytearray()
        self.prompt = asyncio.Event()
        self.monitor_chunks: list[bytes] | None = None

    def receive(self, _characteristic, value: bytearray) -> None:
        chunk = bytes(value)
        self.buffer.extend(chunk)
        if self.monitor_chunks is not None:
            self.monitor_chunks.append(chunk)
        if b">" in chunk or b">" in self.buffer:
            self.prompt.set()

    async def command(self, command: str, timeout: float = 5.0) -> str:
        self.buffer.clear()
        self.prompt.clear()
        await self.client.write_gatt_char(TX_UUID, (command + "\r").encode("ascii"), response=False)
        try:
            await asyncio.wait_for(self.prompt.wait(), timeout)
        except asyncio.TimeoutError:
            return "TIMEOUT"
        return self.buffer.decode("ascii", errors="replace").replace(">", "").strip()

    async def monitor(self, seconds: float, start_percent: float, end_percent: float) -> str:
        self.buffer.clear()
        self.prompt.clear()
        self.monitor_chunks = []
        await self.client.write_gatt_char(TX_UUID, b"ATMA\r", response=False)
        steps = max(1, int(seconds * 4))
        for step in range(steps):
            await asyncio.sleep(seconds / steps)
            percent = start_percent + (end_percent - start_percent) * (step + 1) / steps
            print(f"\r%{percent:5.1f}  CAN kaydı sürüyor...", end="", flush=True)
        await self.client.write_gatt_char(TX_UUID, b" ", response=False)
        try:
            await asyncio.wait_for(self.prompt.wait(), 2.0)
        except asyncio.TimeoutError:
            await self.client.write_gatt_char(TX_UUID, b"\r", response=False)
            await asyncio.sleep(0.5)
        chunks = self.monitor_chunks
        self.monitor_chunks = None
        print()
        return b"".join(chunks or []).decode("ascii", errors="replace")


async def open_elm():
    try:
        from bleak import BleakClient, BleakScanner
    except ImportError as exc:
        raise RuntimeError("Bleak kurulu değil. Önce: python3 -m pip install bleak") from exc
    address = os.environ.get("EX30_VLINK_ADDRESS", DEFAULT_ADDRESS)
    print("IOS-Vlink aranıyor...", flush=True)
    client = BleakClient(address, timeout=15.0)
    try:
        await client.connect()
    except Exception:
        devices = await BleakScanner.discover(timeout=8.0)
        matches = [device for device in devices if any(
            name in (device.name or "").upper() for name in ("VLINK", "VGATE", "ICAR", "OBD")
        )]
        if not matches:
            raise RuntimeError("IOS-Vlink bulunamadı; araç açık ve başka telefon bağlantısı kapalı olmalı")
        client = BleakClient(matches[0], timeout=15.0)
        await client.connect()
    adapter = ElmBle(client)
    await client.start_notify(RX_UUID, adapter.receive)
    return client, adapter


async def initialize_monitor(adapter: ElmBle) -> None:
    commands = ("ATZ", "ATE0", "ATL1", "ATS1", "ATH1", "ATCAF0", "ATAR")
    for index, command in enumerate(commands, 1):
        response = await adapter.command(command, 7.0 if command == "ATZ" else 4.0)
        print(f"Hazırlık {index}/{len(commands)} · {command}: {response.splitlines()[-1:]}", flush=True)
        if command == "ATZ":
            await asyncio.sleep(1.5)


async def detect_monitor_protocol(adapter: ElmBle, root: Path) -> int | None:
    """Önce 29-bit, veri yoksa 11-bit 500 kbit pasif CAN akışını dener."""
    for protocol in (7, 6):
        print(f"Pasif CAN yetenek kontrolü · protokol {protocol}...", flush=True)
        await adapter.command(f"ATSP{protocol}", 4.0)
        await adapter.command("ATAR", 4.0)
        raw = await adapter.monitor(2.0, 0.0, 0.0)
        (root / f"on_kontrol_protokol_{protocol}.txt").write_text(raw, encoding="utf-8")
        frames = parse_can_frames(raw)
        print(f"Protokol {protocol}: {len(frames)} çerçeve")
        if frames:
            return protocol
    return None


def output_directory(prefix: str) -> Path:
    root = Path.home() / "Desktop" / f"{prefix}_{dt.datetime.now():%Y%m%d_%H%M%S}"
    root.mkdir(parents=True, exist_ok=False)
    return root


def write_frames(path: Path, frames_by_phase: dict[str, list[CanFrame]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("faz", "sira", "can_id", "uzunluk", "payload"))
        for phase, frames in frames_by_phase.items():
            for index, frame in enumerate(frames, 1):
                writer.writerow((phase, index, frame.can_id, len(frame.payload), frame.payload.hex().upper()))


def write_candidates(path: Path, candidates: list[Candidate]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow((
            "sira", "can_id", "payload_uzunlugu", "baslangic_byte", "genislik",
            "byte_sirasi", "bos", "yuzde25", "yuzde50", "birak", "skor", "ornek_sayilari",
        ))
        for index, item in enumerate(candidates, 1):
            writer.writerow((
                index, item.can_id, item.payload_length, item.start_byte, item.width,
                item.byte_order, item.rest, item.pedal25, item.pedal50, item.release,
                f"{item.score:.3f}", "/".join(map(str, item.samples)),
            ))


async def run_can_capture(seconds: float) -> int:
    print("\nHAM CAN THROTTLE TESTİ")
    print("Araç P'de, park freni açık ve tamamen sabit olmalı. Hareket ETME.")
    input("Mac sabit, Vgate takılı ve araç açık olduğunda ENTER: ")
    root = output_directory("EX30_Throttle_CAN")
    client, adapter = await open_elm()
    frames_by_phase: dict[str, list[CanFrame]] = {}
    try:
        await initialize_monitor(adapter)
        protocol = await detect_monitor_protocol(adapter, root)
        if protocol is None:
            print("\n29-bit ve 11-bit pasif CAN akışı alınamadı.")
            print("OBD ağ geçidi ATMA'yı kapatıyor olabilir; ana menüden 2'yi çalıştır.")
            print(f"Ön kontrol kayıtları: {root}")
            return 2
        print(f"Ham kayıt protokol {protocol} ile yapılacak.")
        for index, (phase, instruction) in enumerate(PHASES):
            print("\n" + "=" * 72)
            print(f"AŞAMA {index + 1}/4 · {phase}: {instruction}")
            input("Pedal hazırsa ENTER; kayıt bitene kadar konumu değiştirme: ")
            for remaining in range(3, 0, -1):
                print(f"{remaining}...", flush=True)
                await asyncio.sleep(1)
            raw = await adapter.monitor(seconds, index * 25.0, (index + 1) * 25.0)
            (root / f"{index + 1}_{phase}.txt").write_text(raw, encoding="utf-8")
            frames = parse_can_frames(raw)
            frames_by_phase[phase] = frames
            print(f"{len(frames)} CAN çerçevesi ayrıldı.")
        write_frames(root / "can_frames.csv", frames_by_phase)
        candidates = rank_candidates(frames_by_phase)
        write_candidates(root / "throttle_candidates.csv", candidates)
        if not any(frames_by_phase.values()):
            print("\nHam CAN verisi gelmedi. OBD ağ geçidi ATMA akışını kapatıyor olabilir.")
            print("Ana menüden 2 numaralı hızlı ECU keşfini çalıştır.")
            return 2
        if candidates:
            print("\n%100 TAMAMLANDI · en güçlü adaylar")
            for item in candidates[:10]:
                print(
                    f"{item.can_id} byte {item.start_byte} {item.width * 8}-bit {item.byte_order}: "
                    f"{item.rest:g} → {item.pedal25:g} → {item.pedal50:g} → {item.release:g} "
                    f"(skor {item.score:.1f})"
                )
            print("Adayları doğrulamadan uygulamaya sensör olarak ekleme.")
        else:
            print("\n%100 TAMAMLANDI · pedal deseni gösteren güvenilir CAN alanı bulunmadı.")
            print("Ana menüden 2 numaralı hızlı ECU keşfini çalıştır.")
        print(f"Kayıt klasörü: {root}")
        return 0
    finally:
        await client.disconnect()


def ecu_addresses(scope: str) -> list[int]:
    return list(KNOWN_ECU_LOW16) if scope == "quick" else list(range(0x1601, 0x1800))


async def run_ecu_scan(scope: str) -> int:
    addresses = ecu_addresses(scope)
    label = "HIZLI" if scope == "quick" else "TAM"
    print(f"\n{label} ECU KEŞFİ · {len(addresses)} adres · yalnız 22F190 okuması")
    print("Araç P'de, park freni açık ve tamamen sabit kalmalı.")
    input("Koşullar uygunsa ENTER: ")
    root = output_directory(f"EX30_ECU_Kesif_{label}")
    client, adapter = await open_elm()
    positives: list[tuple[str, str]] = []
    try:
        for command in ("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATM0", "ATAT1", "ATSP7", "ATCP1D"):
            await adapter.command(command, 7.0 if command == "ATZ" else 4.0)
            if command == "ATZ":
                await asyncio.sleep(1.5)
        output = root / "ecu_scan.csv"
        with output.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(("sira", "yuzde", "header", "rx_filter", "durum"))
            for index, low16 in enumerate(addresses, 1):
                header, rx_filter, flow_header = derive_ecu(low16)
                for command in (
                    f"ATSH{header}", f"ATCRA{rx_filter}", f"ATFCSH{flow_header}",
                    "ATFCSD300000", "ATFCSM1",
                ):
                    await adapter.command(command, 2.0)
                response = await adapter.command("22F190", 2.5)
                clean = re.sub(r"[^0-9A-F]", "", response.upper())
                status = "POZITIF" if "62F190" in clean else (
                    "NRC" if "7F22" in clean else "YANIT_YOK"
                )
                if status == "POZITIF":
                    positives.append((header, rx_filter))
                percent = index * 100.0 / len(addresses)
                writer.writerow((index, f"{percent:.1f}", header, rx_filter, status))
                handle.flush()
                print(f"\r%{percent:5.1f}  {header}  {status:<9}", end="", flush=True)
        print("\n\n%100 TAMAMLANDI")
        if positives:
            print("Cevap veren ECU'lar:")
            for header, rx_filter in positives:
                print(f"  {header} · RX {rx_filter}")
        else:
            print("Cevap veren ECU bulunmadı.")
        if scope == "quick":
            print("Throttle ECU görünmediyse ana menüde 3 ile tam taramayı çalıştır.")
        print(f"Kayıt: {output}")
        return 0
    finally:
        await client.disconnect()


def print_hci_guide() -> int:
    print("""
CAR SCANNER HCI YEDEK PLANI

1. Android geliştirici seçeneklerinde Bluetooth HCI snoop'u aç.
2. Gerekirse telefonu bir kez yeniden başlat; Bluetooth AÇIK kalsın.
3. Vgate'i EX30'a tak, Car Scanner ile bağlan ve throttle ekranını aç.
4. Tek oturumda pedal yüzde 0 → yaklaşık yüzde 25 → yüzde 50 → yüzde 0 yap.
5. Car Scanner bağlı ve veri okumaya devam ederken hemen bugreport oluştur.
6. Bugreport tamamen bitene kadar Car Scanner'ı ve BLUETOOTH'U KAPATMA.
7. Sonra ZIP'i Mac'e kopyala. Projede şu araç ECU+DID bağlamını çıkarır:
   python3 tools/extract_hci_profile.py BUGREPORT.zip ex30-throttle-profile.json

Önemli: Bluetooth'u kapatmak kaydı eksiltebilir veya döndürebilir. HCI ZIP
oluşana kadar açık kalmalı. Çıktıda yalnız salt-okunur Mode 01 ve UDS 22
sorguları tutulur; 22F190 kimlik yanıtı maskelenir.
""".strip())
    return 0


def choose_mode() -> tuple[str, str]:
    print("""
EX30 THROTTLE KEŞİF PAKETİ

1 — Ham CAN pedal testi (önce bunu çalıştır, yaklaşık 40 saniye)
2 — Hızlı ECU keşfi (ham CAN veri vermezse)
3 — Tam ECU keşfi (hızlı tarama yetmezse; adaptöre göre uzun sürebilir)
4 — Car Scanner HCI doğru kayıt rehberi
""".strip())
    choice = input("Seçim [1]: ").strip() or "1"
    return {
        "1": ("can", "quick"),
        "2": ("ecu", "quick"),
        "3": ("ecu", "full"),
        "4": ("hci", "quick"),
    }.get(choice, ("can", "quick"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Mac BLE EX30 throttle keşif sihirbazı")
    parser.add_argument("--mode", choices=("menu", "can", "ecu", "hci"), default="menu")
    parser.add_argument("--scope", choices=("quick", "full"), default="quick")
    parser.add_argument("--capture-seconds", type=float, default=6.0)
    args = parser.parse_args(argv)
    mode, scope = (args.mode, args.scope)
    if mode == "menu":
        mode, scope = choose_mode()
    try:
        if mode == "can":
            return asyncio.run(run_can_capture(max(3.0, args.capture_seconds)))
        if mode == "ecu":
            return asyncio.run(run_ecu_scan(scope))
        return print_hci_guide()
    except KeyboardInterrupt:
        print("\nKullanıcı durdurdu; mevcut kayıt dosyaları korunur.")
        return 130
    except Exception as exc:
        print(f"\nHATA: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
