# EX30 Sensor Lab

Bağımsız bir Android Automotive (AAOS) tanı uygulaması (`com.kadireren.ex30sensorlab`).
Volvo EX30 üzerinde VHAL ve `Android-Vlink` (ELM327) üzerinden salt-okunur OBD/UDS
sensör doğrulaması yapar. Gradle + Kotlin ile derlenir; ayrıca `tools/` altında
saf-Python bir HCI profil çıkarıcı ve unittest'leri vardır.

## Cursor Cloud specific instructions

Bu ortam bir kez kurulduktan sonra (update script çalıştıktan sonra) aşağıdakiler geçerlidir.

### Derleme / test / lint (standart akış README'de)
- Standart doğrulama komutları `README.md` içindedir:
  `./gradlew testDebugUnitTest lintDebug assembleDebug` ve
  `python3 -m unittest discover tools/tests`.
- Debug APK çıktısı: `app/build/outputs/apk/debug/app-debug.apk`.
- Lint HTML raporu: `app/build/reports/lint-results-debug.html`.

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
