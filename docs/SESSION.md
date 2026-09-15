# EX30 Sensor Lab — Devam Notu

Son güncelleme: 2026-09-15

**Keşif hafızası:** VHAL/OBD/HCI, dashboard sanal ses denemeleri ve referans repo
bulgularının tek özeti → [`DISCOVERY_MEMORY.md`](DISCOVERY_MEMORY.md).

## 2026-09-15 Car Scanner sürüş kaydı / HCI yakalama

- Kullanıcı yaklaşık 10 dakikalık sürüşten sonra Honor AGM3-W09HN tableti
  Car Scanner ve Bluetooth açıkken USB/ADB ile Mac'e bağladı. Bluetooth'u
  kapatmadan `adb bugreport` ve `dumpsys bluetooth_manager` alındı.
- Yerel ham kanıtlar `captures/hci/` altında (gitignore; **commit/push etme**):
  `bugreport-live-20260915.zip`, `dumpsys-bluetooth-live-20260915.txt`,
  `btsnoop-live-20260915.log`, `carscanner-drive-20260915-185400.brc` ve
  `carscanner-installed-20260915.apk`. Bugreport, BRC ve APK kişisel/proprietary
  veri içerebilir; herkese açık paylaşılmamalı.
- Bugreport'ta ayrı `btsnoop_hci.log` yoktu. Bluetooth özetindeki btsnooz
  çözüldü: 10.020 HCI kayıt, yaklaşık 7 dakika 15 saniyelik pencere. BLE ATT
  yazma/bildirimlerinin 4.162'si 15 bayta kırpılmış; TX handle `0x0019`, RX
  handle `0x0016`. `22E...` (245) ve `224...` (305) komut başlangıçları
  görüldü ama tam ECU/DID/yanıt çıkarılamadı. Eski SPP profil çıkarıcı burada
  sıfır sorgu verir; bu kayıt yokluğu değil, BLE + kırpma sınırlaması.
- Car Scanner'ın 18:54 tarihli `.brc` dosyasında yaklaşık 10 dakikalık **canlı
  sayısal örnekler** var: `[VCU] Accelerator pedal PWM signal` 284 örnek,
  ham gösterilen aralık `7–100`; `[IEM] ERAD Motor Speed` 281 örnek,
  `−324–6229`; `[IEM] ERAD Actual Torque` 281 örnek, `0–181`. En yakın
  zamanlı 277 örnekte pedal ile motor hızı/tork arasında yaklaşık `r=0,49/0,50`
  ilişki var. Bu etiketler ve sayılar Car Scanner'ın ölçüm akışına kanıt,
  **doğrudan Sensor Lab DID/decoder doğrulaması değil**. PWM'nin yüzde ölçeği
  ve motor/tork birimleri bağımsız teyit gerektirir.
- Sonraki hedef rastgele blok taramak değil, bu üç Car Scanner sinyalinin tam
  ECU header + `22` DID + cevap baytları + ölçek formülünü edinmek. Tam HCI
  dosyası veya uygulama protokol export'u elde edilirse `extract_hci_profile.py`
  ile profil çıkar; BLE için ATT karakteristik verisini yeniden kurmak gerekebilir.
  Ardından Mac'ten aynı sorguları salt-okunur replay ve pedal/hareket
  kalibrasyonuyla doğrula; **ondan sonra** `CONFIRMED` kataloğa ekle.
- Mevcut `tools/hci_capture_mac.sh` varsayılan olarak Bluetooth'u yeniden
  başlatabildiğinden bu mevcut oturumda **kullanılmadı**. Sonraki yakalamada
  Car Scanner/Bluetooth'u bugreport bitene kadar açık bırak.
- 2026-09-15 takip araştırması: Kaydın `.brc` iç sensör ID'leri
  (`0x00E30D51`, `0x00E30D41`, `0x00E30D40`) ECU header/DID **değil**.
  Car Scanner APK içinde açık PID veritabanı bulunmadı; EX30 companion
  runner'ın `pid_map.md`/`obd2/pids.py` dosyalarında bu üç sinyalin eşlemesi
  yok. Tablet o anda ADB'ye bağlı değildi. O aşamadaki kanıtlarla pedal/RPM/tork
  sorguları ve ölçekleri henüz çıkarılamıyor.
- Uygulamanın HCI kayıt rehberi bugreport tamamlanana kadar Car Scanner ve
  Bluetooth'u açık tutacak biçimde düzeltildi. `extract_hci_profile.py` sıfır
  sorgulu profili artık dosyaya yazmıyor. Python unittest `8/8`, Android
  `testDebugUnitTest lintDebug assembleDebug` başarılı. Canlı sensör kataloğuna
  kanıtsız DID eklenmedi. Sonraki araç ziyaretinde **tam** HCI logu/protokol
  export'u veya Car Scanner'ın sensör başına tam sorgu tanımı gerekli; ardından
  Mac BLE salt-okunur replay + dur/hafif gaz/hareket bağımsız kalibrasyonu.

## 2026-09-15 tam Honor HCI dosyası ve motor sinyali eşlemesi

- Tablet tekrar USB'ye bağlandığında `adb shell ls -lah /data/log/bt` ile
  bugreport'a dahil edilmemiş `btsnoop_hci_20260915_185317.log` bulundu.
  Yerel kopya `captures/hci/btsnoop-full-candidate-20260915.log` (gitignore;
  commit/push etme). 46.316 HCI paketin tümünde included=original; önceki
  `BTSNOOP_LOG_SUMMARY` kırpılmış ayrı bir özetmiş. ADB `setprop
  persist.bluetooth.btsnooplogmode full` reddedildi; mevcut mod boş. Tam
  dosyayı almak için ayar değiştirme/Project Menu gerekmemiş.
- Car Scanner sorgularının sonundaki yanıt-sayısı `1` (`22E3011` vb.) Python
  profil çıkarıcıda DID'den ayrıldı. Aynı oturumun HCI cevapları ve `.brc`
  kayıtları zamanla eşlendi; **284/284 pedal, 281/281 motor devri, 281/281
  tork** değeri tam eşleşti: VCFRONT `D01601 / 22E301`, ECU-F `D01637 /
  22E303` (u16−16384), ECU-F `D01637 / 22E304` (u16−8188).
- `ObdCatalog.motorSignals` ve `ObdDecoders` bu salt-okunur sorguları içerir;
  Motor sensörleri ekranı keşif JSON'u olmadan bunları gösterebilir. Fiziksel
  EX30'da Sensor Lab bağlantısı/replay henüz yapılmadı; ekran bunu belirtir.
  Sonraki araç ziyaretinde üç değeri Car Scanner/araç göstergesiyle **bağımsız
  canlı karşılaştır**, PWM tabanı 7'yi gerçek pedal yüzde 0 sanma. Başarıdan
  sonra ancak sanal motor sesi beslemesine bağla.

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
- Tam HCI dosyası ve üç sorgu/decoder artık bulundu. Birinci öncelik araçta
  Sensor Lab Android-Vlink ile bağımsız canlı karşılaştırmadır; aşağıdaki
  eski saha sihirbazı pasif CAN yedek yoludur.
  Menü 1'de aday çıkarsa menü 1 aynı dört fazla ikinci kez
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
