package com.kadireren.ex30sensorlab.vhal

import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import com.kadireren.ex30sensorlab.vhal.Ex30VhalIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE
import com.kadireren.ex30sensorlab.vhal.Ex30VhalIds.ENGINE_RPM
import java.util.Locale

data class VhalProbeResult(
    val label: String,
    val propertyHex: String,
    val status: String,
    val rawValue: String,
    val displayValue: String,
    val note: String,
)

object VhalProbe {
    private data class Target(
        val id: Int,
        val label: String,
        val soundRole: String,
        val note: String,
    )

    private val targets = listOf(
        Target(
            ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE,
            "Gaz pedalı (sıkışma %)",
            "Sanal motor sesinde birincil gaz / ses şiddeti sinyali.",
            "0 = pedal bırakıldı, 100 = tam gaz. Car Scanner throttle ile aynı rol.",
        ),
        Target(
            ENGINE_RPM,
            "Motor devri (RPM)",
            "Sanal motor sesinde devir / pitch sinyali.",
            "EX30 elektrikli; bu alan motor devri veya sentetik devir olabilir. İzin reddedilirse OEM kısıtı olabilir.",
        ),
        Target(
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
            "Anlık batarya gücü",
            "Gaz yedeği: pedal yoksa güç/regen ile ses yükü tahmin edilebilir.",
            "PDF: ~30 Hz, gaz tepkisine yakın. İşaret yönü araçta doğrulanmalı.",
        ),
        Target(
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
            "Gösterge hızı",
            "Devir hissini hızla eşlemek için (sentetik RPM = f(hız, pedal)).",
            "Zaten AAOS Verileri ekranında var; burada keşif karşılaştırması için.",
        ),
    )

    fun run(manager: CarPropertyManager, powerMultiplier: () -> Int): List<VhalProbeResult> {
        val configs = try {
            manager.propertyList.associateBy { it.propertyId }
        } catch (_: Exception) {
            emptyMap()
        }
        var capacityWh: Float? = null
        try {
            capacityWh = (manager.getFloatProperty(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0))
        } catch (_: Exception) {
        }

        return targets.map { target ->
            probeOne(manager, configs, target, capacityWh, powerMultiplier())
        }
    }

    fun summary(results: List<VhalProbeResult>): String {
        val pedal = results.firstOrNull { it.label.startsWith("Gaz pedalı") }
        val rpm = results.firstOrNull { it.label.startsWith("Motor devri") }
        val power = results.firstOrNull { it.label.startsWith("Anlık batarya") }
        return buildString {
            appendLine("Sanal motor sesi için özet")
            appendLine()
            when {
                pedal?.status == "CANLI" -> {
                    appendLine("• Gaz pedalı AAOS üzerinden okunuyor (${pedal.displayValue}). OBD pedal aramadan önce bunu kullanmayı dene.")
                }
                pedal?.status == "DESTEKLENMİYOR" -> {
                    appendLine("• Gaz pedalı VHAL'de yok. OBD keşfi veya HCI profili (Car Scanner) gerekli.")
                }
                pedal?.status == "İZİN YOK" -> {
                    appendLine("• Gaz pedalı için izin gerekli. Uygulama izin isteyene kadar tekrar dene veya sistem ayarlarından izin ver.")
                }
                else -> appendLine("• Gaz pedalı henüz okunamadı: ${pedal?.status ?: "bilinmiyor"}.")
            }
            when {
                rpm?.status == "CANLI" -> appendLine("• Motor devri okunuyor (${rpm.displayValue}) — sanal ses pitch kaynağı olarak kullanılabilir.")
                rpm?.status == "İZİN YOK" -> appendLine("• Motor devri (ENGINE_RPM) izin gerektiriyor; üçüncü parti uygulamalarda kapalı olabilir.")
                rpm?.status == "DESTEKLENMİYOR" -> appendLine("• Motor devri VHAL'de bildirilmemiş — OBD (010C / ECU-E DID) veya sentetik RPM (hız + pedal) kullan.")
                else -> appendLine("• Motor devri: ${rpm?.status ?: "—"}.")
            }
            when (power?.status) {
                "CANLI" -> appendLine("• Anlık güç canlı — pedal bulunamazsa yedek yük sinyali olarak kullanılabilir.")
                else -> appendLine("• Anlık güç: ${power?.status ?: "—"}.")
            }
            appendLine()
            appendLine("Sonraki adım: OBD bölümünde «Otomatik OBD keşfini başlat» veya HCI profili ile Car Scanner'ın sorduğu DID'leri kopyala.")
        }
    }

    private fun probeOne(
        manager: CarPropertyManager,
        configs: Map<Int, *>,
        target: Target,
        capacityWh: Float?,
        powerMultiplier: Int,
    ): VhalProbeResult {
        val hex = "0x%08X".format(Locale.US, target.id)
        if (configs.isNotEmpty() && configs[target.id] == null) {
            return result(target, hex, "DESTEKLENMİYOR", "—", "—", "Araç bu property'yi Car API listesinde bildirmiyor.")
        }
        return try {
            @Suppress("DEPRECATION")
            val value = manager.getProperty<Any>(target.id, 0)?.value
            if (value == null) {
                result(target, hex, "DEĞER YOK", "—", "—", "Property var ama anlık değer dönmedi; kontak READY/ON mu kontrol et.")
            } else {
                val display = formatDisplay(target.id, value, capacityWh, powerMultiplier)
                result(
                    target,
                    hex,
                    "CANLI",
                    VhalFormatter.raw(value),
                    display,
                    "${target.note} Şu an: $display",
                )
            }
        } catch (_: SecurityException) {
            result(target, hex, "İZİN YOK", "—", "—", "Okuma izni reddedildi. ${target.soundRole}")
        } catch (e: Exception) {
            result(target, hex, "HATA", "—", "—", e.message ?: "Okuma hatası")
        }
    }

    private fun result(
        target: Target,
        hex: String,
        status: String,
        raw: String,
        display: String,
        note: String,
    ) = VhalProbeResult(
        label = target.label,
        propertyHex = hex,
        status = status,
        rawValue = raw,
        displayValue = display,
        note = note,
    )

    private fun formatDisplay(propertyId: Int, value: Any?, capacityWh: Float?, powerMultiplier: Int): String =
        when (propertyId) {
            ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE -> {
                val pct = (value as? Number)?.toFloat()
                pct?.let { String.format(Locale.US, "%.0f %%", it) } ?: VhalFormatter.raw(value)
            }
            ENGINE_RPM -> {
                val rpm = (value as? Number)?.toFloat()
                rpm?.let { String.format(Locale.US, "%.0f rpm", it) } ?: VhalFormatter.raw(value)
            }
            else -> VhalFormatter.display(propertyId, value, capacityWh, powerMultiplier)
        }
}
