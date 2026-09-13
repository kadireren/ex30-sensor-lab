# Car Scanner Bluetooth HCI kaydı

Bu işlem EX30 Sensor Lab'ın çalıştığı araç ekranında değil, Car Scanner'ın
kurulu olduğu ayrı Android telefonda yapılır.

1. Telefonda Geliştirici seçeneklerini açın ve **Bluetooth HCI snoop log**
   seçeneğini etkinleştirin.
2. Bluetooth'u kapatıp açın. `Android-Vlink` adaptörünü Car Scanner ile bağlayın.
3. Tek bir amaç için 60-120 saniyelik kayıt alın: örneğin batarya sıcaklıkları,
   motor değerleri veya şarj değerleri. İlgili göstergeleri Car Scanner'da açık
   tutun.
4. Car Scanner bağlı ve veri okumaya devam ederken telefonu USB ile Mac'e bağlayın.
   Bu aşamada Bluetooth'u, uçak modunu veya HCI snoop seçeneğini değiştirmeyin.
5. Android platform-tools ile hemen bugreport alın:

   ```sh
   adb bugreport bugreport-ex30.zip
   ```

6. Salt-okunur profil üretin:

   ```sh
   python3 tools/extract_hci_profile.py bugreport-ex30.zip ex30-profile.json
   ```

7. Bugreport tamamlandıktan sonra Car Scanner bağlantısını kesin. Aynı adaptöre
   iki uygulama aynı anda bağlanmamalıdır. JSON dosyasını EX30 Sensor Lab >
   OBD Scanner > HCI profili içe aktar ile
   seçin. Uygulama yalnız `01xx` ve `22xxxx` sorgularını kabul eder.

Kayıt kişisel araç tanımlayıcıları içerebilir. Araç, `0902` ve `22F190`
yanıtlarını oluşturulan profilde otomatik maskeler; ham bugreport dosyasını
herkese açık paylaşmayın.
