# EX30 Keşif Hafızası — Sensor Lab + Dashboard + Driver Display

Son güncelleme: 2026-09-08

Bu dosya, üç ilgili repodaki **kanıtlanmış**, **denenen** ve **başarısız** bulguların
tek referans özetidir. Sanal motor sesi / gaz / RPM aramasında önce buraya bak.

**Repolar**

| Repo | Yol | Rol |
|------|-----|-----|
| EX30 Sensor Lab | `/Users/kadireren/Projects/ex30-sensor-lab` | AAOS tanı, OBD keşif, HCI profil |
| EX30 Dashboard | `/Users/kadireren/Projects/ex30dashboard` | Production AAOS uygulama + sanal ses |
| Driver Display | `kadireren/ex-30-driver-display-private` | MIT referans: OBD haritası, Pi companion |

Sensor Lab, Dashboard'dan **bağımsızdır**; Dashboard koduna dokunulmaz
(`docs/SESSION.md` kuralı).

---

## 1. EX30 VHAL — ne var, ne yok

Kaynak: `ex30dashboard/docs/ex30_vhal_properties.txt`, `Ex30SensorIds.kt`,
`AndroidCarDataSource.kt`, Sensor Lab `VhalCatalog.kt`.

### EX30 Car API'de bildirilen ~30 property (log özeti)

Bunların arasında **gaz pedalı, motor RPM, tork yok**. Doküman son satırı:
*"Pedal, motor RPM, tork listede yok."*

### Dashboard + Sensor Lab'ın gerçekten okuduğu VHAL (üretimde kullanılan)

| Property | Hex / ID | Kullanım |
|----------|----------|----------|
| `PERF_VEHICLE_SPEED_DISPLAY` | `0x11600208` | Gösterge hızı, trip, **sanal ses hız girdisi** |
| `PERF_VEHICLE_SPEED` | `0x11600207` | PERF hız, ses/trip yedek |
| `ABS_VEHICLE_SPEED` | `14880036` | Hız fallback |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | `0x1160030C` | Anlık güç (mW) — **gaz proxy / ses yükü** |
| `EV_BATTERY_LEVEL` | `0x11600309` | SOC (Wh) |
| `INFO_EV_BATTERY_CAPACITY` | `0x11600106` | Kapasite |
| `RANGE_REMAINING` | `0x11600308` | Menzil |
| `ENV_OUTSIDE_TEMPERATURE` | `0x11600703` | Dış sıcaklık |
| `GEAR_SELECTION` | `0x11400400` | Vites |
| `PARKING_BRAKE_ON` | `0x11200402` | Park freni |
| `WHEEL_TICK` | `0x11510306` | Trip mesafe, teker proxy hız/RPM |
| `HV_BATTERY_VOLTAGE` | `14880005` | Trip / güç yedek (V×I) |
| `HV_BATTERY_CURRENT` | `14880002` | Trip / güç yedek |

İzinler: `CAR_SPEED`, `CAR_ENERGY`, `CAR_POWERTRAIN`, `CAR_INFO`, …
Sensor Lab probe için ek: `READ_CAR_PEDALS` (`VhalProbe`).

### VHAL'de okunmayan / EX30'de pratikte yok (kesin)

| Property | Sensor Lab probe | Kullanıcı teyidi |
|----------|------------------|------------------|
| `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE` (`0x1160040F`) | Sensör Keşfi → DESTEKLENMİYOR / İZİN YOK | **Okunmuyor** |
| `ENGINE_RPM` (`0x11600405`) | Sensör Keşfi → DESTEKLENMİYOR / İZİN YOK | **Okunmuyor** |

Sanal ses için **gerçek pedal % veya gerçek motor devri VHAL'den gelmez**.
Dashboard bunu bilerek `SyntheticMotorInputs` ile tanımlar:
*"Yapay motor sesi için gerçekçi besleme — RPM/torque/pedal yok."*

---

## 2. EX30 Dashboard — sanal ses ne denedi, nasıl çalışıyordu

Kaynak: `sound-core/SoundControlModel.kt`, `Ex30SoundEngine.kt`,
`EVOrchestraEngine.kt`, `Ex30MotorPhysics.kt`, `NStyleSoundBridge.kt`.

### Ses motoruna giren gerçek sinyaller (araçta)

1. **Hız:** `PERF_VEHICLE_SPEED_DISPLAY` (kalibre km/h)
2. **Güç (kW):** `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` → normalize;
   yedek: `HV_VOLTAGE × HV_CURRENT`; snapshot'ta `sensorSignedPowerKw` / `signedPowerKw`
3. **Vites:** `GEAR_SELECTION` (P'de güç ses için 0'a zorlanır)
4. **WHEEL_TICK (ikincil):** teker proxy hız, ivme, `wheelRpmProxy` — fizik referansı

### Sanal RPM — gerçek değil, türetilmiş

`SoundControlModel.computeInstant(speedKmh, powerKw, …)`:

- `load` = pozitif güç / max drive kW
- `throttleIntent` = load + güç türevi (pedal değil)
- `virtualRpm` = f(hız, load, intent, coast, regen) — `IDLE_RPM`…`REDLINE_RPM`

`Ex30MotorPhysics.speedKmhToMotorRpm()` fiziksel redüktör modeli; ses katmanında
**ölçüm değil**, harita/tuning.

### Güç filtresi (sesin “ölmesine” yol açabilir)

`AndroidCarDataSource.publishSnapshot()` → `displayPowerKw`:

- Vites **P** → güç **0**
- Güç veya hız **stale** (> ~2,5 s) → güç **0**
- `Ex30Calculations.isPlausiblePowerForDisplay` reddederse → güç **0**

Ses motoru çoğunlukla `sensorSignedPowerKw` (filtre öncesi ham) kullanır;
yine de hız stale / P / plausibility etkileyebilir. Debug'da
`HIGH_SPEED_LOW_POWER` uyarısı var (`EVOrchestraEngine`).

### Commit geçmişi (dashboard, ses)

| Commit / dönem | Ne yapıldı |
|----------------|------------|
| `dc073da` | Mac desktop sound simulator (`sound-core`) |
| `133d9d0` | Granular orchestra motor sesi |
| `b104080` | RPM göstergesi → `virtualRpm` (gerçek RPM değil) |
| `dbef649` | Arcade sim: canlı VHAL hız + güç, ICE vites yok |
| `83b7fda` | Offline 44 kontrollü sound acceptance (araç gerekmez) |
| `633e28a` | Gerçek sürüş audio trace replay doğrulama |
| **`40445f2` (`no sound`)** | Tüm mobil ses kodunu silen commit — **mevcut `main`'de yok**; paralel dal. Kullanıcı: araçta denenen yaklaşım **tatmin etmedi**. |

**Önemli:** Mevcut dashboard `main` (ör. `6a6fcf4`) hâlâ `Ex30SoundEngine` +
`NStyleSoundBridge` içerir. Ses **OBD veya Car Scanner DID'ine bağlı değildir**;
yalnızca yukarıdaki VHAL + türetilmiş `virtualRpm`.

### Desktop / test

- `sound-simulator-desktop`: senaryo S1–S9, manuel hız/güç — araç gerekmez
- `SoundAcceptanceTest`: JVM'de 44 check geçer; gerçek EX30 pedal tepkisi kanıtlamaz

---

## 3. ex-30-driver-display-private — HCI ile ne bulundu

Kaynak: `ex30-companion-runner/docs/pid_map.md`, `obd2/pids.py`,
`scripts/research/candidate_calibration.py`, `aaos_bridge/receiver.py`.

### Dört aşamalı yöntem (referans — doğru HCI akışı)

1. Telefonda Car Scanner + **Bluetooth HCI snoop** → `adb bugreport` → **btsnoop_hci.log**
2. ELM init + ECU header (`ATSH`/`ATCRA`) **replay**
3. DID sweep (komşu aralıklar, türetilmiş CRA formülü)
4. Canlı kalibrasyon (`candidate_calibration.py`) — pedal/hareket vs dashboard

### HCI ile **doğrulanmış** OBD (gerçek araç)

| ECU | DID | Sinyal | Not |
|-----|-----|--------|-----|
| BECM `D01635` | `4801` | HV voltaj | |
| BECM | `4802` | HV akım | Tam gaz ~+213 A, regen ~−61 A |
| BECM | `491B`, `4945`, `496D`, `DD01` | Sıcaklık, SOH, km | |
| ECU-E `D01701` | `FD00`–`FD03` | **Fren basıncı (bar)** | Snoop'ta pedal sanıldı; kalibrasyon **fren** |
| ECU-E | `F40D` | Hız km/h | |
| ECU-E | `2B06`–`2B09` | Teker hızı | |
| VCFRONT `D01601` | `D901` | Gösterge SOC | |
| 11-bit `7E3` | `DD01` | Km | |

### HCI ile aday ama **doğrulanmamış** (pid_map'te yok)

| ECU | DID | Snoop hipotezi |
|-----|-----|----------------|
| ECU-F | `E300`, `E303`, `E304`, … | pedal/fren adayı |
| ECU-E | `2B11`, `FEE7` | yaw/chassis — throttle değil |
| ECU-E | `2B04`, `2B05` | **Sensor Lab tahmini**; referans repoda **yok** |

### Gaz “pedal” o projede nasıl

**Ayrı throttle DID yok.** Pi UI:

```text
throttle_pct = min(power_kw / 100, 1.0)   # POWER_BAR_MAX_KW = 100
```

Güç kaynağı: AAOS `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (30 Hz) veya OBD `4801×4802`.

**Motor RPM:** doğrulanmış OBD/AAOS sinyali yok; companion ses/gauge mantığı da yok.

---

## 4. EX30 Sensor Lab — ne başardık

### Uygulama (sürüm ~1.0.10, commit `fdcbe3e`)

| Alan | Durum |
|------|--------|
| VHAL okuma | Dashboard ile aynı 17 tanım (`VhalCatalog`) |
| OBD bağlantı | IOS-Vlink / `Android-Vlink`, ELM init, BECM link testi |
| OBD polling | ECU batch, türetilmiş HV güç, fren fallback |
| Sensör Keşfi UI | Adım adım: VHAL probe, OBD keşif, motor DID taraması, HCI rehberi |
| `VhalProbe` | Gaz/RPM/güç/hız tek dokunuş testi + özet metin |
| Scanner | Aday izleme, ECU yoklama, 256 DID sweep, profil replay |
| Log / export | CSV/JSONL; Download/EX30SensorLab (user 10 yolları, 1.0.10) |
| Python | `extract_hci_profile.py` (+ unittest); `extract_snooz_profile.py` (Car Scanner binary) |
| Test | 21+ Android unit test, lint, assembleDebug |

### Araçta kanıtlanan OBD (ekran görüntüleri, ~Eylül 2026)

| Sensör | Sonuç |
|--------|--------|
| `224801` BECM HV voltaj | **LIVE** (~244 V — düşük SOC normal) |
| `22F40D` ECU-E hız | **LIVE** (park 0 km/h) |
| `22FD00…` fren ortalaması | **ERROR** (decode; sonraki commit'lerde fallback iyileştirildi) |
| Scanner aday izleme | Çalışıyor; çoğu aday NRC veya sabit |

### Emülatör

- `EX30_Sensor_Lab_AAOS_35`: APK kurulumu OK; gerçek VHAL/OBD kanıtlamaz

---

## 5. EX30 Sensor Lab — ne denedik, ne başarısız

### VHAL probe (Sensör Keşfi → AAOS sensörlerini dene)

| Hedef | Sonuç |
|-------|--------|
| Gaz pedalı `0x1160040F` | **Başarısız** (kullanıcı teyit: okunmuyor) |
| Motor RPM `0x11600405` | **Başarısız** (kullanıcı teyit: okunmuyor) |
| Anlık batarya gücü | Dashboard ile aynı property — **doğrulanması gerekir** (sanal ses asıl aday) |

### Car Scanner HCI — iki farklı yol

| Yol | Araç | Sonuç |
|-----|------|--------|
| **`extract_hci_profile.py`** | bugreport içinde gerçek `btsnoop_hci.log` | Doğru yöntem: AT bağlamı + ECU eşlemesi + yanıtlar |
| **`extract_snooz_profile.py`** | `dumpsys btsnooz` binary `11 00 22 XX YY` | **Yanlış varsayım:** tüm DID → ECU-E; Volvo UDS değil |

### HCI profil replay (EX30 Sensor Lab, araç)

- Mac'te ~10 DID'lik profil (`captures/hci/ex30-profile-latest.json` — oturum dosyası)
- Araçta **profil oynatma:** tüm DID'ler **NRC 31** (request out of range)
- **Sonuç:** Car Scanner binary DID'leri doğrudan replay ile motor sesi bulunamaz;
  referans repodaki gibi **init replay + doğru ECU + kalibrasyon** gerekir

### OBD aday DID'ler (henüz araçta throttle/RPM kanıtı yok)

Sensor Lab `ObdCatalog.candidates`: `2B04`, `2B05`, `2B11`, `FEE7` (ECU-E),
ECU-F `E300` serisi, BECM/VCFRONT sıcaklık adayları — **referans repoda
confirmed değil**.

Motor DID taraması butonu: ECU-E `0x2B00`–`0x2B20` — **henüz pedal/RPM eşlemesi
yapılmadı** (kullanıcı sonuç paylaşmadı).

### Dashboard sanal ses (araç)

- Mevcut mimari: hız + anlık güç + türetilmiş virtualRpm
- Kullanıcı değerlendirmesi: **araçta denendi, istenen sonuç alınamadı**
- Kesin kök neden bu dosyada iddia edilmez; kod tarafında bilinen riskler:
  güç plausibility, P vitesi sıfır, stale hız/güç, AAOS güç işareti kalibrasyonu,
  granular asset / audio focus

### Yanlış izler (tekrar deneme)

| İz | Neden yanlış |
|----|----------------|
| `FD00`–`FD03` = gaz pedalı | Referansta **fren basıncı (bar)** |
| Car Scanner “Engine RPM” ekranı = VHAL `ENGINE_RPM` | EX30'de property yok; app içi/emülasyon olabilir |
| HCI binary DID replay | ECU/header/UDS format uyuşmazlığı → NRC 31 |
| OBD Mode 01 `010C` | EX30'de anlamlı veri beklenmez (UDS `22` dünyası) |

---

## 6. Sanal motor sesi için pratik sonuç (bugünkü bilgi)

Gerçek **throttle pedal signal** ve **motor RPM** EX30 üçüncü parti AAOS
uygulamasına **doğrudan okunmuyor**.

Üç repoda ortak çalışan yol:

1. **Gaz / yük proxy:** `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (VHAL) veya OBD
   BECM `4801`+`4802` → kW
2. **Devir hissi:** `SoundControlModel` benzeri **sentetik `virtualRpm`**
   (hız + güç + intent); veya `WHEEL_TICK` / hız fizik modeli
3. **Yeni DID keşfi:** referans HCI yöntemi — tam btsnoop, replay, pedal
   kalibrasyonu; öncelik ECU-F `E300` ve ECU-E sweep

Sensor Lab'in görevi: bu sinyalleri **ölçmek ve kanıtlamak**; Dashboard ses
motorunu taşımak değil (ayrı repo).

---

## 7. Hızlı komut / dosya referansları

```sh
# Sensor Lab doğrulama
cd /Users/kadireren/Projects/ex30-sensor-lab
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover tools/tests -v

# Doğru HCI profil çıkarma
python3 tools/extract_hci_profile.py bugreport.zip profile.json

# Dashboard VHAL listesi
cat ../ex30dashboard/docs/ex30_vhal_properties.txt

# Driver display PID haritası (gh)
gh api repos/kadireren/ex-30-driver-display-private/contents/ex30-companion-runner/docs/pid_map.md --jq '.content' | base64 -d
```

**İlgili Sensor Lab dosyaları**

- `app/.../vhal/VhalCatalog.kt`, `VhalProbe.kt`, `Ex30VhalIds.kt`
- `app/.../obd/ObdCatalog.kt`, `ObdDecoders.kt`, `ObdPollingController.kt`
- `app/.../scanner/ScannerController.kt`, `ScanProfileParser.kt`
- `tools/extract_hci_profile.py`, `tools/extract_snooz_profile.py`
- `docs/HCI_CAPTURE_TR.md`, `docs/SESSION.md`

**İlgili Dashboard dosyaları**

- `mobile/.../vehicle/AndroidCarDataSource.kt`, `VhalSensorCache.kt`
- `mobile/.../vehicle/SyntheticMotorInputs.kt`
- `mobile/.../sound/Ex30SoundEngine.kt`, `sound-core/.../SoundControlModel.kt`
- `docs/ex30_vhal_properties.txt`

---

## 8. Açık işler (bilinçli boşluklar)

- [ ] Araçta VHAL **anlık güç** CANLI mı, işaret yönü doğru mu (Sensor Lab AAOS ekranı)
- [ ] OBD `4802` pedal tepkisi vs VHAL güç gecikmesi karşılaştırması
- [ ] Tam **btsnoop** HCI oturumu → `extract_hci_profile.py` → ECU'lu replay
- [ ] Motor DID sweep (`2B00`–`2B20`, ECU-F `E3xx`) + pedal 0→%50 kalibrasyon logu
- [ ] Dashboard ses başarısızlığı: kullanıcıdan debug overlay / log (isteğe bağlı kök neden)

Bu maddeler tamamlanınca bu dosyadaki tablolar güncellenmeli; tahmin
yazılmamalı.
