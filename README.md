# EX30 Sensor Lab

Volvo EX30 üzerinde AAOS VHAL ve Vgate iCar Pro 2S (`Android-Vlink`) üzerinden
salt-okunur OBD/UDS sensör doğrulaması için bağımsız Android Automotive tanı
uygulaması.

## Ekranlar

- **AAOS Verileri:** 14 VHAL property (`VhalCatalog`; `WHEEL_TICK` dahil).
- **OBD Verileri:** EX30'da canlı doğrulanmış ECU/DID değerleri; çözülemeyen fren basıncı sorguları listeden çıkarıldı. Üstte yalnız log export.
  Bağlantı ana menüden; ekrana girince otomatik okuma.
- **Sensör Keşfi / Motor sensörleri:** HCI replay, kalibrasyon, EX30'da canlı doğrulanmış gaz PWM, ERAD motor devri ve tork DID
  LIVE (Faz 1–3).
- **Ana menü:** OBD'ye bağlan (Android-Vlink öncelikli), Diğer cihazlar, OBD
  kes, uygulamadan çıkış.

Tarayıcı yalnız `READY/ON`, hız 0 ve park freni aktifken çalışır. ECU'ya veri
yazan UDS servisleri kabul edilmez.

## Derleme

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover tools/tests
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

HCI yakalama adımları: [docs/HCI_CAPTURE_TR.md](docs/HCI_CAPTURE_TR.md)

Keşif özeti (VHAL, OBD, HCI, sanal ses): [docs/DISCOVERY_MEMORY.md](docs/DISCOVERY_MEMORY.md)

## Mac throttle keşfi

Araçta IOS-Vlink ile birincil pasif CAN testi, yedek ECU keşfi ve doğru HCI
kayıt rehberi tek salt-okunur sihirbazdadır:

```sh
./tools/EX30_Throttle_Kesif.command
```

Önce ham CAN seçeneği çalıştırılır. Araç P'de ve park freni açıkken ekranın
istediği `%0 → %25 → %50 → %0` pedal aşamaları uygulanır. Araç hareket
ettirilmez. Pasif akış ağ geçidinde kapalıysa hızlı ECU keşfi, gerekirse tam
ECU keşfi seçilir. Çıktılar masaüstünde zaman damgalı klasöre yazılır.

Araç Mac'inde `bleak` daha önce kurulmamışsa sihirbaz yalnız şu kullanıcı
kurulumunu ister; proje bağımlılıklarına paket eklemez:

```sh
python3 -m pip install bleak
```

## Kaynak ve lisans

EX30 ECU adresleri, DID'ler ve doğrulama yöntemi MIT lisanslı
`ifmeidan/ex-30-driver-display` çalışmasından uyarlanmıştır. Kaynak bildirimleri
`NOTICE` ve `LICENSE` dosyalarında korunur.
