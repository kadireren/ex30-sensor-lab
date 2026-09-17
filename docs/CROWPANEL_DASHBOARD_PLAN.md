# EX30 CrowPanel bağımsız gösterge planı

Bu belge, EX30 Sensor Lab'de araç üzerinde doğrulanan salt-okunur OBD/UDS
verilerini bağımsız bir ESP32-S3 gösterge ekranına taşıma planıdır. Proje
`ex30dashboard` Android uygulamasını kullanmayacak; CrowPanel, OBD adaptörüne
doğrudan Bluetooth LE ile bağlanacaktır.

## Sabitlenen mimari

```text
Volvo EX30 OBD-II
       │
       ▼
Vgate iCar Pro 2S (ELM327 v2.3)
       │  Bluetooth LE / IOS-Vlink
       ▼
Elecrow CrowPanel Advance 7" ESP32-S3
       │
       ▼
800×480 LVGL dokunmatik gösterge
```

- Ekran: Elecrow CrowPanel Advance 7", model `DIS02170A`.
- SoC: ESP32-S3-WROOM-1-N16R8, 240 MHz, 16 MB flash, 8 MB OPI PSRAM.
- Panel: 800×480 IPS, kapasitif dokunmatik, SC7277.
- Kart: fotoğraftaki işlev anahtarı tablosuna göre V1.3/V1.4/V1.5 ailesi.
  Bu sürümler aynı V1.3 yazılım ve pin düzenini kullanır.
- OBD: Vgate iCar Pro 2S. `Android-Vlink` Classic Bluetooth yolu
  kullanılmayacak; ESP32-S3 yalnız BLE desteklediği için `IOS-Vlink`
  kullanılacak.
- İlk geliştirme Windows bilgisayarda veya USB-A → USB-C dönüştürücü/hub ile
  Mac mini/MacBook üzerinde yapılabilir.

## Doğrulanmış BLE taşıması

Sensor Lab'in 2026-09-13 Mac saha testinde iCar Pro 2S ile aşağıdaki BLE yolu
canlı olarak çalıştı:

| Alan | Değer |
|---|---|
| BLE cihaz adı | `IOS-Vlink` |
| GATT servis | `000018F0-0000-1000-8000-00805F9B34FB` |
| RX / notify | `00002AF0-0000-1000-8000-00805F9B34FB` |
| TX / write | `00002AF1-0000-1000-8000-00805F9B34FB` |
| Komut sonu | ASCII `\r` |
| Yanıt sonu | ELM prompt `>` |

ESP32, BLE central/client olacaktır. TX karakteristiğine ELM komutlarını
yazacak, RX bildirimlerini bir tamponda birleştirip `>` görülünce cevabı
tamamlanmış sayacaktır. Aynı anda yalnız bir komut beklemede olacaktır.

## Bilgisayar bağlantısı notu

- CrowPanel doğrudan USB-C → USB-C ile Mac'e bağlandığında güç aldı fakat USB
  olarak enumerate olmadı.
- Kutudan çıkan USB-A → USB-C kablo ve hub/dönüştürücü ile kart `USB Serial`
  (`VID 1A86`, `PID 7522`) olarak göründü.
- WCH CH34x macOS sürücüsü kuruldu ve port
  `/dev/cu.wchusbserial1330` olarak oluştu.
- CrowPanel ile harici `XCERIA PRO SSD` aynı hub üzerinden başlatıldığında SSD
  bir defa 0 bayt göründü. Panel geliştirilirken SSD ile aynı güçsüz hub kolu
  kullanılmamalı. Panel ayrı porttan veya haricî beslemeli hub'dan beslenmeli.

## Yazılım tabanı

Başlangıç için üreticinin V1.3/V1.4/V1.5 örneği temel alınacak. İlk hedef
üretici bağımlılıklarını sabitleyerek tekrar üretilebilir bir firmware
oluşturmaktır.

- Dil: C++
- İlk geliştirme ortamı: Arduino IDE veya PlatformIO; proje kalıcı hale
  gelirken PlatformIO tercih edilir.
- Kart: `ESP32S3 Dev Module`
- Flash: 16 MB
- PSRAM: OPI PSRAM
- Partition: Huge APP
- Grafik: LVGL 9.1.0
- Ekran: LovyanGFX 1.2.25 ve Elecrow 7" sürücü/pin yapılandırması
- Dokunmatik: GT911/üretici sürücüsü; gerçek kart örneğiyle doğrulanacak
- Ekran yönü: yatay 800×480

Elecrow'un yayımladığı kaynak ile fabrika firmware'i arasında akıcılık farkı
raporlanmıştır. İlk ekran testinde yırtılma/titreme görülürse RGB panel zamanlama,
PSRAM hızı, çift tampon ve LVGL flush yolu ölçülmeden tasarıma geçilmemelidir.

## Salt-okunur ELM/UDS çekirdeği

Başlangıç komutları Sensor Lab ile aynı olacaktır:

```text
ATZ
ATE0
ATE0
ATL0
ATS0
ATH1
ATM0
ATAT1
```

ECU bağlamı değiştirilirken yalnız salt-okunur sorgular kullanılacaktır:

```text
ATSP7
ATSH<header>
ATCP1D
ATCRA<rx-filter>
ATFCSH<flow-control-header>
ATFCSD300000
ATFCSM1
```

| ECU | TX header | RX filter | Flow-control header |
|---|---|---|---|
| BECM | `D01635` | `1EC6AE80` | `1DD01635` |
| VCFRONT | `D01601` | `1EC02E80` | `1DD01601` |
| ECU-E | `D01701` | `1EE02E80` | `1DD01701` |
| ECU-F | `D01637` | `1EC6EE80` | `1DD01637` |

İlk bağlantı testi BECM üzerinde `224801` ve `22491B` olacaktır. Pozitif UDS
cevabı `62<DID>` ile başlamalıdır. Yazma, servis, reset ve rutin komutları
firmware'e alınmayacaktır.

Kaynak gerçekleri:

- Katalog ve ECU bağlamları: `app/src/main/java/.../obd/ObdCatalog.kt`
- ELM başlangıç/geçiş sırası: `app/src/main/java/.../obd/ElmProtocol.kt`
- Decoder formülleri: `app/src/main/java/.../obd/ObdDecoders.kt`
- Saha kanıtı: `docs/DISCOVERY_MEMORY.md`

## MVP sensörleri ve sorgu planı

İlk sürümde bütün sensörleri hızlı sorgulamak yerine sürüş için gereken küçük
bir küme kullanılacaktır. ELM tek sorguyu sıralı işlediği ve ECU geçişleri
maliyetli olduğu için sorgular ECU'ya göre gruplanacaktır.

| Öncelik | Değer | ECU / DID | Decoder | Hedef |
|---:|---|---|---|---:|
| 1 | Araç hızı | ECU-E `22F40D` | `u8`, km/h | 5 Hz |
| 1 | Gaz pedalı PWM | VCFRONT `22E301` | `u8`; bırakılmış taban yaklaşık 7 | 8–10 Hz |
| 1 | ERAD motor devri | ECU-F `22E303` | `u16 - 16384`, rpm | 8–10 Hz |
| 1 | ERAD gerçek tork | ECU-F `22E304` | `u16 - 8188`, Nm | 8–10 Hz |
| 2 | Gösterge SOC | BECM `224801` | `(u16/500 × 1.0625) - 3.125` | 1 Hz |
| 2 | HV voltajı | BECM `224803` | `u16 / 100`, V | 2 Hz |
| 2 | HV akımı | BECM `224802` | `(u16 - 16384) × 0.1`, A | 2 Hz |
| 2 | HV güç | `4803 × 4802` | `V × A / 1000`, kW | 2 Hz |
| 3 | Batarya ort. sıcaklık | BECM `22491B` | `u16/100 - 50`, °C | 0.2 Hz |
| 3 | Batarya SOH | BECM `22496D` | `u32 × 0.01`, % | 0.1 Hz |
| 3 | 12 V besleme | `ATRV` | ELM metin voltajı | 0.1 Hz |

Gerçek ulaşılabilir hız, BLE/ELM gecikmesi ölçüldükten sonra ayarlanacaktır.
Bir ECU grubunda birkaç sorgu tamamlanmadan başka ECU'ya geçilmemelidir.

## Görsel hedef ve ekranlar

Amaç, tanı aracı görünümü değil, orijinal araç kadranı kalitesinde sade ve
akıcı bir gösterge üretmektir. Ana sürüş bilgisi büyük, ikincil bilgi küçük
olacaktır. Dokunmatik yatay kaydırma veya alt kenar sayfa göstergesiyle ekranlar
değiştirilecektir.

1. **Ana sürüş**
   - Ortada büyük dijital hız
   - Çevresinde güç/rejenerasyon yayı
   - SOC ve bağlantı durumu
   - Küçük RPM ve tork
2. **Performans**
   - Büyük ERAD RPM kadranı
   - Tork, pedal PWM, HV güç ve rejenerasyon
   - Kısa süreli tepe değerleri
3. **Batarya**
   - SOC, HV voltajı, HV akımı, güç
   - Ortalama/maksimum sıcaklık ve SOH
   - Şarj/deşarj yönü
4. **Teknik**
   - Teker hızları, hücre min/max voltajı, soğutma değerleri ve 12 V
   - Bu sayfa daha düşük sorgu hızında çalışabilir

İlk grafik geliştirmesi OBD olmadan demo veri üreticisiyle yapılacaktır. Böylece
animasyon, tipografi ve sayfa geçişleri masa başında tamamlanabilir.

## Uygulama aşamaları

### A. Donanımı doğrula

- [ ] Windows veya Mac'te seri portu aç.
- [ ] Elecrow'un zamanlı seri çıktı örneğini derle ve yükle.
- [ ] Ekran renk testi, dokunmatik koordinat testi ve PSRAM testi çalıştır.
- [ ] Fabrika firmware'inin geri yükleme dosyalarını yerelde yedekle.

### B. Yeni firmware iskeleti

- [ ] Ayrı proje/repo oluştur: önerilen ad `ex30-crowpanel-dashboard`.
- [ ] Elecrow pin/sürücü dosyalarını kaynağı belirtilerek sabitle.
- [ ] LVGL görevini, ekran flush tamponlarını ve dokunmatik girişini kur.
- [ ] Seri log, hata ekranı ve demo veri modu ekle.

### C. İlk kaliteli ekran

- [ ] 800×480 ana sürüş ekranını demo veriyle oluştur.
- [ ] 30 fps hedefinde animasyon ve bellek kullanımını ölç.
- [ ] Gündüz/gece parlaklığı ve tema altyapısı ekle.
- [ ] Dokunmatik sayfa geçişini doğrula.

### D. BLE ve ELM

- [ ] `IOS-Vlink` tarama, bağlantı ve GATT discovery uygula.
- [ ] Notify tamponu + `>` prompt ayrıştırıcısı yaz.
- [ ] `ATI` ve ELM init komutlarıyla masa başı bağlantı testi yap.
- [ ] Kopma, timeout ve kontrollü yeniden bağlanmayı ekle.

### E. Araçta kademeli test

- [ ] Önce yalnız BECM `224801` ile SOC oku.
- [ ] Araç dururken hız `22F40D` ve motor sensörlerini doğrula.
- [ ] Kısa sürüşte hız/RPM/tork/pedal değerlerini Sensor Lab ile karşılaştır.
- [ ] Sorgu hızlarını ölç; ECU grup zamanlayıcısını ayarla.
- [ ] Veri bayatladığında değeri dondurmak yerine belirgin `--`/bağlantı durumu göster.

### F. Araç içi ürünleştirme

- [ ] Ayrı, kararlı 5 V/2 A araç beslemesi seç.
- [ ] Kontak/uyku davranışı ve OBD adaptörünün otomatik uyanmasını test et.
- [ ] Açılış süresi, BLE yeniden bağlanma ve uzun süreli çalışma testi yap.
- [ ] Görüşü engellemeyen montaj ve kablo güzergâhı hazırla.

## Sonraki oturumun ilk işi

1. Paneli Windows'a veya Mac'e USB-A → USB-C yoluyla bağla.
2. Mac kullanılıyorsa `/dev/cu.wchusbserial*` portunu doğrula; paneli SSD ile
   aynı güçsüz hub koluna bağlama.
3. Elecrow V1.3/V1.4/V1.5 seri çıktı örneğini derleyip karta yükle.
4. Başarılı yüklemeden sonra ayrı `ex30-crowpanel-dashboard` projesini oluştur.
