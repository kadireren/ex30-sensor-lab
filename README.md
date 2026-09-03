# EX30 Sensor Lab

Volvo EX30 üzerinde AAOS VHAL ve Vgate iCar Pro 2S (`Android-Vlink`) üzerinden
salt-okunur OBD/UDS sensör doğrulaması için bağımsız Android Automotive tanı
uygulaması.

## Ekranlar

- **AAOS Verileri:** Araçta doğrulanmış 13 VHAL property ve `WHEEL_TICK`.
- **OBD Verileri:** Doğrulanmış EX30 ECU/DID değerleri ve odak modu.
- **OBD Scanner:** Aday izleme, kısa ECU yoklama, en fazla 256 DID taraması ve
  HCI profil tekrarı.

Tarayıcı yalnız `READY/ON`, hız 0 ve park freni aktifken çalışır. ECU'ya veri
yazan UDS servisleri kabul edilmez.

## Derleme

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover tools/tests
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

HCI yakalama adımları: [docs/HCI_CAPTURE_TR.md](docs/HCI_CAPTURE_TR.md)

## Kaynak ve lisans

EX30 ECU adresleri, DID'ler ve doğrulama yöntemi MIT lisanslı
`ifmeidan/ex-30-driver-display` çalışmasından uyarlanmıştır. Kaynak bildirimleri
`NOTICE` ve `LICENSE` dosyalarında korunur.
