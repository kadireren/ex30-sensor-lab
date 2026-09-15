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

### Canlı kalibrasyondan sonra kalan aday

| ECU | DID | Canlı gözlem |
|-----|-----|-------------|
| BECM | `489E` | Dururken yaklaşık `0x000Bxxxx`, hareket sırasında `0x007Exxxx` seviyesine kadar değişti; anlamı ve ölçeği bilinmiyor, **confirmed değil** |

ECU-F `E3xx`, ECU-D `EExx`, ECU-E `2Bxx`/`F4xx`/`FExx` içindeki eski
gaz/RPM adayları 2026-09-13 canlı boş–hafif–orta–bırak ve hareket
kalibrasyonlarında pedal yüzdesini izlemediği için `ObdCatalog.candidates`
listesinden çıkarıldı.

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
| VHAL okuma | Dashboard kanıtlı 14 tanım (`VhalCatalog`; HV V/ A ve ABS hız VHAL'den kaldırıldı) |
| OBD bağlantı | IOS-Vlink / `Android-Vlink`, ELM init, BECM link testi |
| OBD polling | ECU batch, türetilmiş HV güç; çözülemeyen fren basıncı güncel katalogdan çıkarıldı |
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
| `22FD00…` fren ortalaması | **ERROR** (decode; sonraki fallback denemeleri de kullanımını doğrulamadı, güncel OBD ekranından çıkarıldı) |
| Scanner aday izleme | Çalışıyor; çoğu aday NRC veya sabit |

### 2026-09-13 Mac BLE canlı tarama ve kalibrasyon

- Mac, IOS-Vlink'e BLE üzerinden doğrudan bağlandı: ELM327 v2.3; servis
  `000018f0`, RX notify `00002af0`, TX write `00002af1`. Mode 01 `0100` için
  `NO DATA` alındı; EX30 UDS `22` sorguları çalıştı.
- Doğrudan canlı okumalar: 12 V `14,2 V`, BECM HV yaklaşık `276 V`, HV akım
  yaklaşık `1,5 A`, ortalama batarya sıcaklığı `29 °C`, SOH `%100`, araç hızı
  dururken `0 km/h`, VCFRONT SOC `%100`.
- VCFRONT `D901` gerçek cevabı dört baytlı `00000064` oldu. SOC decoder'ı tüm
  veri alanını okuyacak şekilde düzeltildi; eski ilk-bayt davranışı `%0`
  gösterebiliyordu.

Tam 256-DID taramalar:

| ECU / aralık | Pozitif | NRC / veri yok | Sonuç |
|--------------|--------:|----------------:|-------|
| ECU-F `E300–E3FF` | 36 | 220 / 0 | 29 sensör adayı ve hızlı eski adaylar gaz fazlarını izlemedi |
| ECU-D `EE00–EEFF` | 62 | 188 / 6 | 54 sabit; küçük değişenler pedal oranıyla sıralı değildi |
| ECU-E `2B00–2BFF` | 12 | 239 / 5 | `2B06–2B09` teker hızları doğrulandı; `2B11` şasi dinamiği |
| ECU-E `F400–F4FF` | 5 | 246 / 5 | `F40D` hız doğrulandı; diğer dört değer gazda sabit |
| ECU-E `FE00–FEFF` | 5 | 246 / 5 | `FEE0` kimlik/tarih; diğerleri pedal değil |
| BECM `4800–48FF` | 11 | 230 / 15 | `489E` hareket adayı; ilk adresler ECU ısınırken veri vermedi |

Hareket kalibrasyonu:

- `F40D` ve `2B06–2B09`, ileri/geri hareketin mutlak hızını birlikte izledi.
- `2B1A` duruşta `000100`, D/R sırasında `000400`; yön değil sürüş-durumu
  adayı. `2B20` durum geçişleri gösterdi. İkisi de pedal/RPM değil.
- BECM `4802`, ilk duruşta `1,8–1,9 A`, harekette `19,9 A` tepe ve son
  duruşta yeniden `1,8–1,9 A` verdi. Gaz/yük için doğrulanmış en iyi OBD proxy
  `4801 × 4802` türetilmiş kW olmaya devam ediyor.
- Bu 2026-09-13 Mac DID taramalarında doğrudan gaz pedalı yüzdesi ve gerçek
  motor RPM bulunmadı; 2026-09-15 Car Scanner kaydı için aşağıya bak.

Kanıt CSV'leri kullanıcı masaüstünde `EX30_*.csv` adlarıyla saklandı; temel
dosyalar: `EX30_ECUF_E300_E3FF_20260913_130717.csv`,
`EX30_ECU-D_EE00_EEFF_20260913_131441.csv`,
`EX30_ECU-E_2B00_2BFF_20260913_132053.csv`,
`EX30_ECU-E_hareket_20260913_132514.csv`,
`EX30_ECU-E_F400_F4FF_20260913_132921.csv`,
`EX30_ECU-E_FE00_FEFF_20260913_133241.csv` ve
`EX30_BECM-KISA_hareket_20260913_133738.csv`.

### 2026-09-15 Car Scanner uzun sürüş kaydı — yeni kanıt

Honor tablette Car Scanner ve Bluetooth açık tutulurken bugreport alındı.
Bugreport içinde tam `btsnoop_hci.log` dosyası yok; `dumpsys bluetooth_manager`
özetindeki btsnooz 10.020 HCI pakete çözüldü. Yaklaşık 7 dakika 15 saniyelik
BLE pencerenin ATT verileri 15 bayta kırpılmış: `22E...` ve `224...` yalnız
ilk üç karakter olarak görünür, tam DID veya cevabı çıkarmaya yetmez.

Car Scanner'ın aynı gün 18:54 tarihli `.brc` sürüş kaydı ise yaklaşık 10
dakikalık sayısal örnekleri saklıyor:

| Car Scanner etiketi | Örnek | Kayıttaki aralık | Kanıt düzeyi |
|---------------------|------:|-----------------:|-------------|
| `[VCU] Accelerator pedal PWM signal` | 284 | 7–100 | Car Scanner canlı değer; PWM ölçeği/DID bilinmiyor |
| `[IEM] ERAD Motor Speed` | 281 | −324–6229 | Car Scanner canlı değer; birim/DID bilinmiyor |
| `[IEM] ERAD Actual Torque` | 281 | 0–181 | Car Scanner canlı değer; birim/DID bilinmiyor |

277 yakın zamanlı örnekte pedal değeri ile motor hızı/tork arasında yaklaşık
`r=0,49/0,50` korelasyon var; sürüş karışık olduğu için bu tek başına decoder
formülünü doğrulamaz. **Yeni sonuç:** sinyaller Car Scanner'da görünür ve
kaydedilir. Önceki “gaz/RPM yok” sonucu yalnız Sensor Lab'in VHAL ve
2026-09-13'te denenen ECU/DID sorguları için geçerlidir. Uygulamaya gerçek
sensör eklemek için tam ECU/DID, cevap baytları, ölçek ve bağımsız tekrar
gerekir. Ham kanıtlar gitignore'daki `captures/hci/` altındadır; bugreport/BRC
herkese açık paylaşılmamalıdır.

#### 2026-09-15 düzeltme — tam Honor HCI dosyası bulundu

Bugreport ZIP tam dosyayı içermiyordu; tablette erişilebilir
`/data/log/bt/btsnoop_hci_20260915_185317.log` dosyası bulundu ve
`captures/hci/btsnoop-full-candidate-20260915.log` olarak yerel alındı
(gitignore; paylaşma). Dosya 46.316 HCI kayıt içerir; **46.316/46.316
pakette included length = original length**, yani payload kırpılmamış.
`persist.bluetooth.btsnooplogmode` boş ve ADB ile `full` yazma girişimi
reddedildi; `btsnooppacketchunksize` ayarı kullanılmadı. Tam dosya
mevcutken Honor Project Menu/verbose ayarına gerek yok.

Car Scanner ELM sorgularının sonundaki tek haneli yanıt-sayısı (`22E3011`)
profil çıkarıcıda DID'den ayrıldı. HCI cevapları ile aynı oturumdaki `.brc`
zaman-değer kayıtları karşılaştırıldı; şu eşlemeler **tüm eşleşen örneklerde
bire bir** çıktı:

| Sinyal | ECU TX / RX | Sorgu | Cevap verisi | Eşleşme |
|--------|-------------|-------|--------------|---------:|
| Gaz pedalı PWM sinyali | `D01601` / `1EC02E80` | `22E301` | u8 = Car Scanner gösterimi; bırakıldığında taban 7, yüzde-0 pedal sanma | 284/284 |
| ERAD Motor Speed | `D01637` / `1EC6EE80` | `22E303` | u16 − 16384 = Car Scanner motor hızı | 281/281 |
| ERAD Actual Torque | `D01637` / `1EC6EE80` | `22E304` | u16 − 8188 = Car Scanner torku | 281/281 |

İlk eşleşen örnekler: `62E30115` → 21; `62E303504B` → 4171;
`62E3041FFC` → 0. Son kayıtta motor hızı −324…6229, tork 0…181.
Bu **ECU/DID/Car Scanner ölçeğini** doğrular. Ardından kullanıcı üç sinyalin
Sensor Lab ile fiziksel EX30'da canlı çalıştığını doğruladı. Üç sorgu
yerleşik salt-okunur `CONFIRMED` kataloğa ve Motor sensörleri ekranına eklendi;
bekleyen canlı test uyarısı kaldırıldı. PWM'nin 7 tabanı doğrudan pedal yüzdesi
olarak yorumlanmaz.

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
| **`extract_hci_profile.py`** | gerçek `btsnoop_hci.log` (`/data/log/bt/` veya ZIP) | Doğru yöntem: AT bağlamı + ECU eşlemesi + yanıtlar |
| **`extract_snooz_profile.py`** | `dumpsys btsnooz` binary `11 00 22 XX YY` | **Yanlış varsayım:** tüm DID → ECU-E; Volvo UDS değil |

### HCI profil replay (EX30 Sensor Lab, araç)

- Mac'te ~10 DID'lik profil (`captures/hci/ex30-profile-latest.json` — oturum dosyası)
- Araçta **profil oynatma:** tüm DID'ler **NRC 31** (request out of range)
- **Sonuç:** Car Scanner binary DID'leri doğrudan replay ile motor sesi bulunamaz;
  referans repodaki gibi **init replay + doğru ECU + kalibrasyon** gerekir

### OBD aday DID'ler (2026-09-15 öncesi tarihsel not)

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
| Car Scanner “Engine RPM” ekranı = VHAL `ENGINE_RPM` | EX30'de property yok; `[IEM] ERAD Motor Speed` için ECU-F `22E303` ayrı OBD yolu bulundu |
| HCI binary DID replay | ECU/header/UDS format uyuşmazlığı → NRC 31 |
| OBD Mode 01 `010C` | EX30'de anlamlı veri beklenmez (UDS `22` dünyası) |

---

## 6. Sanal motor sesi için pratik sonuç (bugünkü bilgi)

Sensor Lab'in VHAL yolunda gaz pedalı ve gerçek motor RPM okunmuyor. OBD
yolunda 2026-09-15 tam HCI + Car Scanner kaydı, gaz pedalı PWM `22E301`,
ERAD motor hızı `22E303` ve tork `22E304` ECU/DID/ölçeklerini bire bir
eşledi; kullanıcı ardından üçünün Sensor Lab ile EX30'da canlı çalıştığını
doğruladı. Uygulama bunları `CONFIRMED` katalogda sunuyor. PWM'nin 7
tabanı gerçek pedal yüzde 0/7 yorumuyla karıştırılmamalı.

Üç repoda ortak çalışan yol:

1. **Gaz / yük proxy:** `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (VHAL) veya OBD
   BECM `4801`+`4802` → kW
2. **Devir hissi:** `SoundControlModel` benzeri **sentetik `virtualRpm`**
   (hız + güç + intent); veya `WHEEL_TICK` / hız fizik modeli
3. **Gerçek OBD motor sinyalleri:** yukarıdaki üç DID; araçta Sensor Lab canlı
   karşılaştırması başarılı olursa sanal ses beslemesi için değerlendir

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
- [x] OBD `4802` hareket/yük tepkisi (1,8–1,9 A taban, 19,9 A kısa tepe)
- [x] Tam **btsnoop** HCI dosyası `/data/log/bt/` altından alındı ve ECU'lu profil çıkarıldı
- [x] 2026-09-15 Car Scanner `.brc` sürüş kaydı ve kırpılmış BLE HCI özeti
  alındı; pedal PWM, motor hız ve tork sayısal örnekleri görüldü.
- [x] Bu üç sinyalin ECU header + DID + cevap baytları + Car Scanner ölçeği
  HCI/BRC eşlemesiyle bulundu (284/284, 281/281, 281/281).
- [x] Sensor Lab'in Android-Vlink bağlantısıyla araçta üç DID'in canlı çalışması
  kullanıcı tarafından doğrulandı; karşılaştırma sayısal kayıtları henüz belgeye eklenmedi.
- [ ] Mac `ATMA` pasif CAN `%0 → %25 → %50 → %0` korelasyonu; 29-bit veri
  yoksa 11-bit otomatik denenir (`tools/mac_ble_throttle_discovery.py`)
- [ ] Pasif CAN yoksa hızlı 19-adres ECU keşfi; gerekirse `D01601–D017FF`
  tam tarama. Yalnız salt-okunur `22F190`, kimlik cevabı kaydedilmez.
- [x] Motor DID sweep (ECU-E `2Bxx`/`F4xx`/`FExx`, ECU-F `E3xx`, ECU-D
  `EExx`, BECM `48xx`) + pedal/hareket kalibrasyonu; doğrudan pedal/RPM yok
- [ ] Dashboard ses başarısızlığı: kullanıcıdan debug overlay / log (isteğe bağlı kök neden)

Bu maddeler tamamlanınca bu dosyadaki tablolar güncellenmeli; tahmin
yazılmamalı.
