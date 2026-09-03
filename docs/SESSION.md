# EX30 Sensor Lab — Devam Notu

Son güncelleme: 2026-09-03

## Amaç ve değişmez sınırlar

- Bu repo, mevcut `ex30dashboard` uygulamasından bağımsız Android Automotive
  tanı uygulamasıdır. `ex30dashboard` içinde değişiklik yapılmamalıdır.
- Uygulama kimliği `com.kadireren.ex30sensorlab`, adı `EX30 Sensor Lab`.
- Üç ekran vardır: `AAOS Verileri`, `OBD Verileri`, `OBD Scanner`.
- OBD tarafı yalnız salt-okunur ELM `AT`, Mode `01` ve UDS `22` komutlarına
  izin verir. `10`, `11`, `14`, `2E`, `31` ve ECU'ya yazan tüm servisler kod
  seviyesinde reddedilmelidir.
- Scanner yalnız kontak READY/ON, hız `<0,5 km/h` ve park freni aktifken
  çalışır; koşul kaybolursa tarama kesilir.
- Yeni üçüncü taraf runtime bağımlılığı ekleme. Volvo logosu/marka görseli
  ekleme. Referans kodun MIT bildirimi `LICENSE` ve `NOTICE` içinde korunur.

## Git ve teslim durumu

- Yerel repo: `/Users/kadireren/Projects/ex30-sensor-lab`
- GitHub: `https://github.com/kadireren/ex30-sensor-lab` — private
- Ana dal: `main`
- Bu nottan önceki son uygulama commit'i:
  `af67956954fe2ce68fb81136d47c9e08c700a036`
  (`Allow installation on AAOS emulator images`).
- Bu not oluşturulmadan hemen önce çalışma ağacı temiz ve `main` ile
  `origin/main` eşitti.
- İlk uygulama commit'i: `cf7ccb9756e8ccc96de264e1c0ffc0ade78e6128`.

## Uygulanan yapı

- `vhal/`: 13 doğrulanmış VHAL property + ham `WHEEL_TICK`; destek/izin,
  ham ve dönüştürülmüş değer, örnek yaşı/gecikme, hedef ve gerçek Hz; güç yönü
  kullanıcı kalibrasyonu.
- `obd/`: Classic Bluetooth SPP ile eşleştirilmiş `Android-Vlink` bağlantısı,
  secure bağlantı ve insecure fallback, tek komut kuyruğu, `>` prompt okuma,
  timeout sonrası tek kontrollü yeniden bağlantı ve ECU bağlamını geri yükleme.
- `obd/ObdCatalog.kt`: doğrulanmış BECM, VCFRONT, ECU-E ve adaptör değerleri;
  fren birleşik sorgusu beş ret sonrasında ayrı sorgulara düşer; odak modu var.
- `scanner/`: aday PID izleme, kısa ECU `F190` yoklaması, seçilen ECU'da en
  fazla 256 DID taraması, yaklaşık 3 sorgu/sn, canlı ilerleme ve NRC etiketi.
- `scanner/ScanProfileParser.kt`: HCI profilini izin listesinden geçirir;
  oynatma kullanıcı eylemi olmadan başlamaz ve önce inceleme penceresi gösterir.
- `logging/`: CSV ve JSONL oturum kaydı, ELM başlatma/ECU geçişleri dahil ham
  protokol; `0902` ve `22F190` yanıtları paylaşımda maskelenir.
- `tools/extract_hci_profile.py`: yalnız Python standart kütüphanesiyle ham
  `btsnoop_hci.log` veya Android bugreport ZIP'inden sürümlü JSON profil üretir.

## Doğrulanan komutlar

SDK harici disktedir. Derleme için:

```sh
cd /Users/kadireren/Projects/ex30-sensor-lab
ANDROID_HOME=/Volumes/Harici/Android/sdk ./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover tools/tests -v
```

Son sonuçlar:

- Android: 13 unit test geçti; `lintDebug` ve `assembleDebug` başarılı.
- Python: 2 test geçti.
- `assembleDebugAndroidTest` daha önce başarıyla derlendi.
- Fiziksel cihaz olmadığı için `connectedDebugAndroidTest` çalıştırılmadı.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Bilinen zararsız uyarı: AGP 8.5.2, compileSdk 35 için resmen en fazla 34'e
  kadar test edildiğini bildiriyor. Derleme başarılı; sırf uyarıyı gizlemek
  için sürüm/ayar değiştirilmedi.

## Harici diskte AAOS emülatörü

- SDK: `/Volumes/Harici/Android/sdk`
- AVD ana dizini: `/Volumes/Harici/Android/avd`
- AVD: `EX30_Sensor_Lab_AAOS_35`
- İmaj: `system-images;android-35-ext15;android-automotive;arm64-v8a`
- İmaj yaklaşık 5,6 GB ve Apple Silicon için ARM64'tür.
- Komut satırı araçları:
  `/Volumes/Harici/Android/sdk/cmdline-tools/latest`

Başlatma:

```sh
ANDROID_HOME=/Volumes/Harici/Android/sdk \
ANDROID_AVD_HOME=/Volumes/Harici/Android/avd \
/Volumes/Harici/Android/sdk/emulator/emulator \
@EX30_Sensor_Lab_AAOS_35 -no-snapshot-load -gpu host -no-boot-anim
```

Boot kontrolü, kurulum ve çalıştırma:

```sh
/Volumes/Harici/Android/sdk/platform-tools/adb shell getprop sys.boot_completed
/Volumes/Harici/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/Volumes/Harici/Android/sdk/platform-tools/adb shell am start -W -n com.kadireren.ex30sensorlab/.MainActivity
```

İlk denemede manifestte `android.car` shared library `required="true"` olduğu
için imaj `INSTALL_FAILED_MISSING_SHARED_LIBRARY` verdi. Çalışan
`ex30dashboard` ile aynı uyumluluk ayarı kullanılarak `required="false"`
yapıldı. Sonrasında APK kurulumu `Success`, aktivite başlangıcı `Status: ok`
verdi. Bluetooth runtime izni ADB ile verildi ve `dumpsys window`, uygulamanın
`MainActivity` ekranını user 10 üzerinde odakta gösterdi.

AAOS emülatörü çoklu ekran sunduğu için düz `adb exec-out screencap -p`
uyarı metniyle birlikte geçersiz PNG üretti. Doğru display ID seçilerek görsel
kontrol tekrarlanmalı. Emülatör daha sonra kapandı; bu not yazılırken ADB cihaz
listesi boştu.

## Sıradaki işler

1. Emülatörü yeniden başlat; doğru display ID ile üç ana menü kartını ve ekran
   geçişlerini görsel olarak doğrula.
2. Emülatörde `connectedDebugAndroidTest` çalıştır. Emülatör gerçek Volvo VHAL
   veya fiziksel Bluetooth SPP davranışını kanıtlamaz.
3. EX30 üzerinde `Android-Vlink` ile `ATI`, BECM `4801/491B`, hız, SOC,
   kilometre, sıcaklık ve fren okumalarını Car Scanner/araç göstergesiyle
   karşılaştır.
4. Güç işaretini hızlanma ve lift-off regen ile kalibre et.
5. Scanner'ın hareket başladığında durduğunu ve JSONL içinde hiçbir yazma
   komutu bulunmadığını doğrula.
6. Araçta en az 30 dakika açık ekran bağlantı/donma/komut çakışması testi yap.
7. Ayrı Android telefondan alınan HCI kaydı geldiğinde
   `docs/HCI_CAPTURE_TR.md` akışını kullan; ham bugreport içinde VIN olabileceği
   için dosyayı herkese açık paylaşma.

## Çalışma kuralları

- Önce `git status`, ilgili gerçek türler ve metot imzalarını incele.
- Yalnız hedef dosyaları stage et; `git add -A` kullanma.
- Değişiklikten sonra en az unit test, lint ve debug APK derlemesini çalıştır.
- Push istenirse önce `git fetch origin main` ve
  `git rev-list --left-right --count HEAD...origin/main` ile ayrışmayı kontrol
  et; push sonrasında yerel ve uzak commit hash'lerini karşılaştır.
