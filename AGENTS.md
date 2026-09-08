# EX30 Sensor Lab

Bağımsız bir Android Automotive (AAOS) tanı uygulaması (`com.kadireren.ex30sensorlab`).
Volvo EX30 üzerinde VHAL ve `Android-Vlink` (ELM327) üzerinden salt-okunur OBD/UDS
sensör doğrulaması yapar. Gradle + Kotlin ile derlenir; ayrıca `tools/` altında
saf-Python bir HCI profil çıkarıcı ve unittest'leri vardır.

Keşif geçmişi (VHAL/OBD/HCI, sanal ses, başarılı ve başarısız denemeler):
[`docs/DISCOVERY_MEMORY.md`](docs/DISCOVERY_MEMORY.md) — gaz/RPM/ses konularında
önce bu dosyaya bak.

## Cursor Cloud specific instructions

Bu ortam bir kez kurulduktan sonra (update script çalıştıktan sonra) aşağıdakiler geçerlidir.

### Derleme / test / lint (standart akış README'de)
- Standart doğrulama komutları `README.md` içindedir:
  `./gradlew testDebugUnitTest lintDebug assembleDebug` ve
  `python3 -m unittest discover tools/tests`.
- Debug APK çıktısı: `app/build/outputs/apk/debug/app-debug.apk`.
- Lint HTML raporu: `app/build/reports/lint-results-debug.html`.
- Signed Play AAB: `./gradlew bundleRelease` →
  `app/build/outputs/bundle/release/app-release.aab`
  (imza: `../ex30dashboard/keystore.properties` mevcutsa).

### Ortam / non-obvious kurulum notları
- **JDK:** Ortamda JDK 21 kuruludur. Proje `sourceCompatibility`/`jvmTarget` olarak
  17 hedefler; Gradle 8.7 + AGP 8.5.2 JDK 21 üzerinde sorunsuz derler. Ayrı bir
  JDK 17 kurmaya gerek yoktur.
- **Android SDK:** `$HOME/android-sdk` altındadır (platform 35, build-tools 35.0.0,
  platform-tools). `ANDROID_HOME`/`ANDROID_SDK_ROOT` interaktif kabuklar için
  `~/.bashrc`'de export edilir. Gradle'ın SDK'yı bulması için `local.properties`
  (gitignore'da) `sdk.dir=$HOME/android-sdk` içerir ve update script tarafından
  yeniden yazılır. `local.properties` olmadan da `ANDROID_HOME` env yeterlidir.
- **Zararsız uyarı:** AGP 8.5.2, `compileSdk = 35` için resmen 34'e kadar test
  edildiğini bildiren bir uyarı verir. Derleme başarılıdır; uyarıyı susturmak için
  sürüm/ayar değiştirmeyin.
- **Configuration cache açık** (`org.gradle.configuration-cache=true`).

### AAOS uygulamasını çalıştırma — ortam kısıtı (ÖNEMLİ)
- Bu Cloud VM'de AAOS emülatörü **çalıştırılamaz**. VM iç içe (nested) sanallaştırma
  kullanır ve emülatör bir VCPU oluşturmaya çalıştığında host çekirdeği
  `kernel BUG at arch/x86/kvm/x86.c:702` (`vmx_vcpu_create`) hatası verir; misafir
  CPU hiç ilerlemez (qemu ~%0.2 CPU'da asılı kalır, `adb` cihazı `offline`).
- x86_64 AAOS imajı donanım hızlandırma (KVM) gerektirir; arm64 imajı ise x86_64
  host'ta Android Emulator tarafından desteklenmez. Yani bu VM'de uygulamayı GUI
  ile çalıştırmanın uygulanabilir bir yolu yoktur.
- Sonuç: Bu ortamda doğrulama = unit test + lint + `assembleDebug` (geçerli APK) +
  Python testleri. Gerçek çalıştırma/GUI testi KVM-yetenekli bir host veya fiziksel
  Volvo EX30 gerektirir (bkz. `docs/SESSION.md`).
- APK'nın geçerli bir AAOS uygulaması olduğu `aapt2 dump badging` ile doğrulanabilir
  (paket `com.kadireren.ex30sensorlab`, launcher `MainActivity`, CAR_* + Bluetooth
  izinleri, `android.car` kütüphanesi).

### Update script (Cloud)
```sh
if [ -d "$HOME/android-sdk" ]; then printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties; fi
```

## Yerel Mac (Apple Silicon) — Desktop agent devam noktası

Cloud agent (`cursor/setup-dev-environment-e519`, PR #1) yalnızca yukarıdaki Cloud
notlarını ekledi. Yerel geliştirme ve emülatör ayrıntıları `docs/SESSION.md`
içindedir. Özet:

- **SDK:** `/Volumes/Harici/Android/sdk` (harici disk takılı olmalı).
- **AAOS AVD:** `EX30_Sensor_Lab_AAOS_35` — `ANDROID_AVD_HOME=/Volumes/Harici/Android/avd`.
- **Emülatörde uygulama user 10'da:** `adb shell am start --user 10 -n com.kadireren.ex30sensorlab/.MainActivity`
- **Play Console:** `android.hardware.type.automotive` manifest'te `required="false"`
  olmalıdır; aksi halde normal kanal yüklemesi reddedilir. `android.car` kütüphanesi
  zaten `required="false"`.

### Play / release
- Release imzası: `../ex30dashboard/keystore.properties` + `ex30-release.jks`.
- `./gradlew bundleRelease` → signed AAB.
