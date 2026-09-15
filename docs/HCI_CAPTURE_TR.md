# Car Scanner Bluetooth HCI kaydı

Bu işlem EX30 Sensor Lab'ın çalıştığı araç ekranında değil, Car Scanner'ın
kurulu olduğu ayrı Android telefonda yapılır.

1. Telefonda Geliştirici seçeneklerini açın ve **Bluetooth HCI snoop log**
   seçeneğini etkinleştirin.
2. Bluetooth'u kapatıp açın. `Android-Vlink` adaptörünü Car Scanner ile bağlayın.
3. Tek bir amaç için 60-120 saniyelik kayıt alın: örneğin batarya sıcaklıkları,
   motor değerleri veya şarj değerleri. İlgili göstergeleri Car Scanner'da açık
   tutun.
4. Honor AGM3-W09HN tablette tam dosya bugreport'a eklenmeyebilir. Oturumdan
   sonra tableti USB ile Mac'e bağlayıp **önce** erişilebilir tam dosyayı arayın:

   ```sh
   adb shell ls -lah /data/log/bt
   adb pull /data/log/bt/btsnoop_hci_YYYYMMDD_HHMMSS.log ex30-full-hci.log
   ```

   `YYYYMMDD_HHMMSS` yerine listede görülen gerçek dosya adını yazın. Bu
   tablette 2026-09-15'te `/data/log/bt/` altında tam dosya bulundu.
5. Ayrı dosya yoksa Car Scanner bağlı ve veri okumaya devam ederken, Bluetooth'u
   değiştirmeden Android platform-tools ile bugreport alın:

   ```sh
   adb bugreport bugreport-ex30.zip
   ```

6. Salt-okunur profil üretin:

   ```sh
   python3 tools/extract_hci_profile.py ex30-full-hci.log ex30-profile.json
   ```

   Ayrı dosya yok ve ZIP içinde tam `btsnoop_hci.log` varsa çıkarıcıya ZIP'i
   verin. Yalnız `BTSNOOP_LOG_SUMMARY` varsa profil üretmeyin: paketler kırpılmıştır.
7. Kayıt tamamlandıktan sonra Car Scanner bağlantısını kesin. Aynı adaptöre
   iki uygulama aynı anda bağlanmamalıdır. JSON dosyasını EX30 Sensor Lab >
   OBD Scanner > HCI profili içe aktar ile
   seçin. Uygulama yalnız `01xx` ve `22xxxx` sorgularını kabul eder.

Kayıt kişisel araç tanımlayıcıları içerebilir. Araç, `0902` ve `22F190`
yanıtlarını oluşturulan profilde otomatik maskeler; ham bugreport dosyasını
herkese açık paylaşmayın.
