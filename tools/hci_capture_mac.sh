#!/usr/bin/env bash
# Mac-side HCI capture helper for EX30 Sensor Lab.
# Waits for adb device, enables HCI snoop, pulls bugreport, extracts ex30-profile.json.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/captures/hci"
mkdir -p "$OUT_DIR"

ADB="${ADB:-adb}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BUGREPORT="$OUT_DIR/bugreport-ex30-$STAMP.zip"
PROFILE="$OUT_DIR/ex30-profile-$STAMP.json"
LATEST_PROFILE="$OUT_DIR/ex30-profile-latest.json"

echo "== EX30 HCI capture (Mac) =="
echo "Proje: $ROOT"
echo "Çıktı klasörü: $OUT_DIR"
echo

wait_for_device() {
  local seconds="${1:-300}"
  echo "ADB cihazı bekleniyor (${seconds}s)…"
  local i=0
  while (( i < seconds )); do
    if "$ADB" get-state >/dev/null 2>&1; then
      echo "Bağlı cihaz:"
      "$ADB" devices -l
      return 0
    fi
    if (( i % 15 == 0 )); then
      echo "  … hâlâ bekleniyor (${i}s). Tablette USB hata ayıklama + RSA onayı gerekli."
    fi
    sleep 3
    i=$((i + 3))
  done
  echo "HATA: ${seconds}s içinde adb cihazı görülmedi." >&2
  return 1
}

enable_hci_snoop() {
  echo
  echo "HCI snoop etkinleştiriliyor…"
  "$ADB" shell settings put secure bluetooth_hci_log 1 >/dev/null 2>&1 || true
  "$ADB" shell settings put global bluetooth_hci_log 1 >/dev/null 2>&1 || true
  echo "  secure=$("$ADB" shell settings get secure bluetooth_hci_log)"
  echo "  global=$("$ADB" shell settings get global bluetooth_hci_log)"
  echo "Bluetooth yeniden başlatılıyor (snoop dosyası için)…"
  "$ADB" shell svc bluetooth disable >/dev/null 2>&1 || true
  sleep 3
  "$ADB" shell svc bluetooth enable >/dev/null 2>&1 || true
  sleep 4
}

verify_btsnoop_in_zip() {
  local zip="$1"
  if unzip -l "$zip" 2>/dev/null | rg -q "btsnoop_hci\\.log"; then
    return 0
  fi
  echo
  echo "HATA: Bugreport içinde btsnoop_hci.log yok." >&2
  echo "Tablette şunları yapın, sonra scripti tekrar çalıştırın:" >&2
  echo "  1) Geliştirici seçenekleri → Bluetooth HCI snoop log AÇIK" >&2
  echo "  2) Bluetooth kapat/aç" >&2
  echo "  3) Car Scanner + Android-Vlink ile bağlan; RPM/gaz ekranları açık 60–90 sn" >&2
  echo "  4) Car Scanner bağlantısını kes" >&2
  echo "  5) USB ile Mac'e bağlıyken: ./tools/hci_capture_mac.sh" >&2
  return 1
}

pull_bugreport() {
  echo
  echo "Bugreport alınıyor (1–3 dk sürebilir)…"
  "$ADB" bugreport "$BUGREPORT"
  echo "Bugreport: $BUGREPORT"
  verify_btsnoop_in_zip "$BUGREPORT"
}

extract_profile() {
  echo
  echo "Profil çıkarılıyor…"
  python3 "$ROOT/tools/extract_hci_profile.py" "$BUGREPORT" "$PROFILE"
  cp -f "$PROFILE" "$LATEST_PROFILE"
  echo "Profil: $PROFILE"
  echo "Son profil: $LATEST_PROFILE"
  python3 - <<PY
import json
from pathlib import Path
p = Path("$PROFILE")
data = json.loads(p.read_text())
queries = data.get("queries", [])
print(f"Sorgu sayısı: {len(queries)}")
for q in queries[:12]:
    ctx = q.get("context") or q.get("ecu") or {}
    print(f"  - {ctx.get('name','?')} {q.get('service','')} {q.get('did','')}")
if len(queries) > 12:
    print(f"  … +{len(queries)-12} daha")
PY
}

main() {
  wait_for_device "${HCI_WAIT_SECONDS:-600}"
  if [[ "${HCI_ENABLE_SNOOP:-1}" == "1" ]]; then
    enable_hci_snoop
    echo
    echo "ÖNEMLİ: Car Scanner + OBD oturumunu tablette yapın, sonra Enter'a basın."
    echo "(OBD yoksa scripti durdurun: Ctrl+C)"
    read -r -p "Car Scanner oturumu bitti mi? [Enter devam / n çık] " ans
    if [[ "${ans,,}" == "n" ]]; then
      echo "Çıkılıyor — OBD oturumu sonrası tekrar çalıştırın."
      exit 2
    fi
  fi
  pull_bugreport
  extract_profile
  echo
  echo "Tamam. EX30 Sensor Lab > Sensör Keşfi > HCI profili içe aktar"
  echo "Dosya: $LATEST_PROFILE"
}

main "$@"
