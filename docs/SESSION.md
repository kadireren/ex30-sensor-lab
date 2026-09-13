# EX30 Sensor Lab — Devam Notu

Son güncelleme: 2026-09-13

**Keşif hafızası:** VHAL/OBD/HCI, dashboard sanal ses denemeleri ve referans repo
bulgularının tek özeti → [`DISCOVERY_MEMORY.md`](DISCOVERY_MEMORY.md).

## 2026-09-13 fiziksel EX30 / Mac BLE saha sonucu

- IOS-Vlink'e Mac'ten BLE bağlantısı ve salt-okunur UDS `22` sorguları
  doğrulandı. ECU-F `E3xx`, ECU-D `EExx`, ECU-E `2Bxx`/`F4xx`/`FExx` ve BECM
  `48xx` bloklarında 256'şar DID tarandı; pozitifler pedal ve hareket fazlarıyla
  kalibre edildi.
- Doğrudan gaz pedalı yüzdesi veya gerçek motor RPM bulunmadı. Eski ECU-F,
  ECU-D ve ECU-E gaz/RPM adayları fazları izlemediği için katalogdan çıkarıldı.
- `F40D` araç hızı ve `2B06–2B09` dört teker hızı fiziksel hareketle
  doğrulandı. BECM `4802`, duruşta `1,8–1,9 A`, kısa harekette `19,9 A` tepe
  verdi; yük proxy'si `4801 × 4802` türetilmiş kW olarak kalır.
- BECM `489E` hareketle güçlü değişti ancak anlamı/ölçeği bilinmiyor; yalnız
  `CANDIDATE`, çalışan sensör olarak sunulmamalı.
- VCFRONT `D901` canlı cevabı `00000064` olduğundan SOC decoder'ı dört baytlı
  veriyi okuyacak şekilde düzeltildi ve regresyon testi eklendi.
- Bu değişikliklerle ve throttle keşif aracıyla Python testleri `7/7`, Android
  unit testleri `30/30` geçti; `lintDebug` ve `assembleDebug` başarılı oldu.
  Güncel debug APK ve lint raporu 2026-09-13 14:32'de üretildi.
- Ayrıntılı tarama sayıları, pozitif DID'ler ve CSV kanıt adları
  [`DISCOVERY_MEMORY.md`](DISCOVERY_MEMORY.md) içindedir.

### Sonraki araç ziyareti için hazır throttle paketi

- `tools/mac_ble_throttle_discovery.py`: Mac + IOS-Vlink BLE üzerinden çalışan
  salt-okunur saha sihirbazı. Ekranda adım, geri sayım ve tamamlanma yüzdesi
  gösterir; kayıtları masaüstünde zaman damgalı klasöre yazar.
- `tools/EX30_Throttle_Kesif.command`: çift tıklanabilir proje başlatıcısı.
  Masaüstünde aynı isimli kısa yol proje başlatıcısını çalıştırır.
- Menü 1, araç P'de ve sabitken `ATMA` ile önce 29-bit, sonra gerekirse 11-bit
  pasif CAN yeteneğini sınar. Akış varsa `%0 → %25 → %50 → %0` fazlarını
  altışar saniye kaydeder; byte ve 16-bit alanları tekrar edilebilir pedal
  desenine göre sıralayıp `throttle_candidates.csv` üretir.
- Menü 2, ham CAN ağ geçidinde kapalıysa bilinen/olası 19 ECU adresini yalnız
  `22F190` ile yoklar. Menü 3, gerekirse `D01601–D017FF` aralığındaki 511 ECU
  adresini tarar. Kimlik yanıtı kayda yazılmaz; yalnız pozitif/NRC/yanıt-yok
  durumu saklanır.
- Menü 4, Car Scanner bağlı ve veri okumaya devam ederken bugreport alma
  sırasını gösterir. Bugreport bitene kadar Car Scanner ve Bluetooth
  kapatılmamalıdır.
- Sonraki sıra: önce menü 1. Aday çıkarsa menü 1 aynı dört fazla ikinci kez
  çalıştırılmalı; doğrudan `CONFIRMED` kataloğa eklenmemeli. Ham CAN yoksa menü
  2, throttle ECU görünmezse menü 3, tanı yolu da sonuçsuzsa menü 4.

## 2026-09-09 UI ve VHAL sadeleştirme

- **Android-Vlink otomatik bağlantı** (`ObdPreferredDevice`): ana menü «OBD'ye
  bağlan» önce eşleşmiş veya kayıtlı MAC dener; yoksa kısa tarama. Uygulama
  açılışında (izin varsa) sessiz otomatik deneme. «Diğer cihazlar» manuel listeyi
  açar.
- **Motor keşif (Faz 1–3, v1.0.11):** HCI replay → kalibrasyon → Motor sensörleri
  LIVE; kalıcı `Download/EX30SensorLab/discovered_sensors.json`.
- **VHAL katalog:** AAOS/emülatörde desteklenmeyen üç property kaldırıldı:
  `HV_BATTERY_VOLTAGE`, `HV_BATTERY_CURRENT`, `ABS_VEHICLE_SPEED`. `VhalCatalog`
  artık **14** sensör (HV voltaj/akım OBD `4801`/`4802` üzerinden okunmaya devam).
- **Ana menü:** «OBD bağlantısını kes» ve «Uygulamadan çıkış» (`finishAffinity`)
  eklendi; OBD kesme/bağlanma ana menüden yönetilir.
- **OBD Verileri ekranı:** üst kontrol satırında yalnız «Download'a aktar» kaldı;
  bağlıyken ekrana girince otomatik polling başlar. Odak modu liste dokunuşu ile
  sürer.

## 2026-09-08 güvenilirlik iyileştirmeleri

- `ElmProtocol` sorguları ve bağlantı doğrulaması aynı kilit altında
  serileştirildi; polling ve scanner ECU ayarları artık birbirine karışamaz.
- Polling çalışmalarına nesil kimliği eklendi. Durdurulan eski döngü, hızlı
  yeniden başlatma sonrasında yeni çalışmanın bayrağıyla devam edemez.
- Polling durumu yalnız kendi executor'ında sıfırlanıyor; UI iş parçacığından
  koleksiyon temizleme yarışı kaldırıldı.
- HCI profil tekrarı, ECU bağlamı olmayan standart Mode 01 sorgularını da
  gerçekten gönderiyor.
- HCI profil boyutu/sorgu sayısı ile servis, DID, protokol ve ECU alanları
  sınırlandı.
- Türetilmiş HV güç ve ayrı fren kanalları, 1 saniyeden eski veya birbiriyle
  zaman uyumsuz ham örneklerden artık hesaplanmıyor.
- Uygulama arka plana geçince scanner ve polling duruyor; OBD ekranlarına geri
  dönüldüğünde normal polling yeniden başlıyor, scanner otomatik başlamıyor.
- Eşzamanlı iki ECU sorgusunun seri kaldığını doğrulayan unit test eklendi.
- Çözülemeyen veya hata veren OBD sorguları için 1 saniyelik kontrollü tekrar
  gecikmesi eklendi; odak modu kullanıcı isteğiyle en fazla yaklaşık 30
  sorgu/sn (33 ms aralık) olacak şekilde ayarlandı.
- Profil sorgusu ve ECU taşıma alanı kuralları saf Kotlin politikasına ayrıldı
  ve iki unit test ile doğrulandı.
- AAOS/OBD ayrıntı listesi saniyede bir yenileniyor; veri akışı tamamen
  kesildiğinde son örnek ekranda yanlışlıkla süresiz `LIVE` kalmıyor.
- Kullanıcı tercihi: genel ELM `AT` komut kabulü korunacak, odak modu yaklaşık
  30 sorgu/sn olacak, log gizliliği ve depolama akışı değiştirilmeyecek.
- Kullanıcının onayladığı koyu tanı arayüzü uygulandı: ortak üst çubuk,
  durum alanı, renk kodlu ana menü kartları, düğmeler ve sensör kartları aynı
  görsel dilde birleştirildi. İşlevsel akışlar değiştirilmedi.
- Doğrulama: 21 Android unit test + 2 Python testi geçti; `lintDebug` ve
  `assembleDebug` başarılı. Fiziksel EX30/Android-Vlink testi hâlâ gereklidir.

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

- `vhal/`: 14 VHAL property (`VhalCatalog`; `WHEEL_TICK` dahil); destek/izin,
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

Son sonuçlar (2026-09-13):

- Android: 30 unit test geçti; `lintDebug` ve `assembleDebug` başarılı.
- Python: 7 test geçti.
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
