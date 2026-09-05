package com.kadireren.ex30sensorlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.kadireren.ex30sensorlab.logging.SessionLogger
import com.kadireren.ex30sensorlab.model.SampleStatus
import com.kadireren.ex30sensorlab.model.ScanProfile
import com.kadireren.ex30sensorlab.model.SensorDefinition
import com.kadireren.ex30sensorlab.model.SensorSample
import com.kadireren.ex30sensorlab.model.SensorSource
import com.kadireren.ex30sensorlab.obd.BluetoothElmTransport
import com.kadireren.ex30sensorlab.obd.BluetoothObdDeviceScanner
import com.kadireren.ex30sensorlab.obd.EcuContexts
import com.kadireren.ex30sensorlab.obd.ElmProtocol
import com.kadireren.ex30sensorlab.obd.ObdDeviceEntry
import com.kadireren.ex30sensorlab.obd.ObdPollingController
import com.kadireren.ex30sensorlab.scanner.ScanProfileParser
import com.kadireren.ex30sensorlab.scanner.ScannerController
import com.kadireren.ex30sensorlab.ui.DriveSensorAdapter
import com.kadireren.ex30sensorlab.ui.SensorListAdapter
import com.kadireren.ex30sensorlab.vhal.AndroidVhalReader
import com.kadireren.ex30sensorlab.vhal.SafetyState
import com.kadireren.ex30sensorlab.vhal.VhalCatalog
import com.kadireren.ex30sensorlab.vhal.VhalProbe
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private enum class ObdConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    private enum class Page { AAOS, OBD, SCANNER, DRIVE }

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var vhalReader: AndroidVhalReader? = null
    private var elmProtocol: ElmProtocol? = null
    private var obdPolling: ObdPollingController? = null
    private var scanner: ScannerController? = null
    private var logger: SessionLogger? = null
    private var deviceScanner: BluetoothObdDeviceScanner? = null
    private var safetyState = SafetyState()
    private var importedProfile: ScanProfile? = null
    private var scannerStatus: TextView? = null
    private var scannerWorkflowView: TextView? = null
    private var obdStatusView: TextView? = null
    private var obdConnectionState = ObdConnectionState.DISCONNECTED
    private var obdDeviceName: String? = null
    private var obdDeviceAddress: String? = null
    private var obdAdapterId: String? = null
    private var obdErrorMessage: String? = null
    private var obdDeviceReturnAction: (() -> Unit)? = null
    private var obdSampleSink: ((SensorSample) -> Unit)? = null
    private var obdStateSink: ((String) -> Unit)? = null
    private var obdPollingPausedForScanner = false
    private var driveAdapter: DriveSensorAdapter? = null
    private var drivePageLabel: TextView? = null
    private var driveSourceVhalButton: Button? = null
    private var driveSourceObdButton: Button? = null
    private var driveSource = SensorSource.VHAL
    private val drivePages = mutableMapOf(SensorSource.VHAL to 0, SensorSource.OBD to 0)
    private var driveStatusView: TextView? = null
    private var currentPage: Page? = null
    private lateinit var swipeDetector: GestureDetector
    private val swipeMinDistancePx by lazy { dp(64) }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.lab_background)
        window.navigationBarColor = color(R.color.lab_background)
        swipeDetector = GestureDetector(this, SwipeListener())
        requestRequiredPermissions()
        connectCar()
        showHome()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun navigateToPage(page: Page) {
        when (page) {
            Page.AAOS -> showAaos()
            Page.OBD -> showObd()
            Page.SCANNER -> showScanner()
            Page.DRIVE -> showDriveView()
        }
    }

    private inner class SwipeListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) < swipeMinDistancePx || abs(dx) <= abs(dy) || abs(velocityX) < SWIPE_MIN_VELOCITY) return false
            when (currentPage) {
                Page.DRIVE -> {
                    val adapter = driveAdapter ?: return false
                    val delta = if (dx < 0f) 1 else -1
                    window.decorView.post { changeDrivePage(delta, adapter, driveStatusView) }
                    return true
                }
                else -> {
                    val page = currentPage ?: return false
                    val index = SWIPE_PAGES.indexOf(page)
                    if (index < 0) return false
                    val target = if (dx < 0f) index + 1 else index - 1
                    if (target !in SWIPE_PAGES.indices) return false
                    window.decorView.post { navigateToPage(SWIPE_PAGES[target]) }
                    return true
                }
            }
        }
    }

    override fun onDestroy() {
        stopActiveScreen(disconnectObd = true)
        try { car?.disconnect() } catch (_: Exception) {}
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        showHome()
    }

    private fun connectCar() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { readyCar, ready ->
                if (ready) {
                    car = readyCar
                    carPropertyManager = readyCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                } else {
                    carPropertyManager = null
                }
            }
        } catch (_: Exception) {
            carPropertyManager = null
        }
    }

    private fun showHome() {
        stopActiveScreen()
        currentPage = null
        driveStatusView = null
        val root = baseScreen("EX30 Sensor Lab", if (carPropertyManager != null) "● Araç bağlantısı hazır" else "○ Araç bağlantısı bekleniyor", false)
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(16), dp(28), dp(8))
        }
        cards.addView(menuCard("1", "AAOS Verileri", "${VhalCatalog.entries.size} VHAL sensörü") { showAaos() }, menuCardLayoutParams())
        cards.addView(menuCard("2", "OBD Verileri", "Bluetooth OBD adaptörü ile okuma") { showObd() }, menuCardLayoutParams(dp(16)))
        root.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        row2.addView(menuCard("3", "Sensör Keşfi", "AAOS + OBD adım adım rehber") { showScanner() }, menuCardLayoutParams())
        row2.addView(menuCard("4", "Sürüş Görünümü", "Büyük yazı · sayfalı okuma") { showDriveView() }, menuCardLayoutParams(dp(16)))
        root.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val obdRow = controlRow()
        obdRow.addView(actionButton("OBD cihazlarını tara") { showObdDevices { showHome() } })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            obdRow.addView(actionButton("OBD bağlantısını kes") { disconnectObdConnection(); showHome() })
        }
        root.addView(obdRow)
        root.addView(label("Tarama yalnız araç sabitken çalışır", 18f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(0, dp(16), 0, dp(20))
        })
        setContentView(root)
        if (obdConnectionState == ObdConnectionState.CONNECTED) ensureObdPolling()
    }

    private fun showAaos() {
        stopActiveScreen()
        currentPage = Page.AAOS
        driveStatusView = null
        val manager = carPropertyManager
        val root = baseScreen("AAOS Verileri", if (manager != null) "● VHAL hazır" else "○ Car API bekleniyor", true)
        val calibration = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            val direction = when (powerMultiplier()) { 1 -> "hızlanmada +"; -1 -> "hızlanmada −"; else -> "doğrulanmadı" }
            addView(label("Güç yönü: $direction", 17f, color(R.color.lab_text_secondary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("Hızlanmada +") { savePowerMultiplier(1); showAaos() })
            addView(actionButton("Hızlanmada −") { savePowerMultiplier(-1); showAaos() })
            addView(actionButton("Sıfırla") { savePowerMultiplier(0); showAaos() })
        }
        root.addView(calibration)
        val adapter = SensorListAdapter(this)
        root.addView(ListView(this).apply {
            dividerHeight = dp(8)
            setPadding(dp(18), 0, dp(18), dp(12))
            clipToPadding = false
            this.adapter = adapter
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        logger = SessionLogger(this).also { it.start("aaos") }
        if (manager == null) {
            adapter.update(messageSample("Car API hazır değil", SampleStatus.ERROR))
            return
        }
        vhalReader = AndroidVhalReader(manager) { powerMultiplier() }.also { reader ->
            reader.start(onSample = { sample ->
                logger?.append(sample)
                runOnUiThread { adapter.update(sample) }
            }, onSafety = { safetyState = it })
        }
        if (obdConnectionState == ObdConnectionState.CONNECTED) ensureObdPolling()
    }

    private fun showDriveView() {
        stopScreenResources()
        currentPage = Page.DRIVE
        driveSource = SensorSource.VHAL
        drivePages[SensorSource.VHAL] = 0
        drivePages[SensorSource.OBD] = 0
        val adapter = DriveSensorAdapter(this, SensorSource.VHAL).also { driveAdapter = it }
        val root = baseScreen("Sürüş Görünümü", "Canlı sensör değerleri", true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status").also { driveStatusView = it }
        val sourceRow = controlRow().apply { setPadding(dp(18), dp(8), dp(18), dp(6)) }
        driveSourceVhalButton = largeTabButton("VHAL") { selectDriveSource(SensorSource.VHAL, adapter, status) }
        driveSourceObdButton = largeTabButton("OBD") { selectDriveSource(SensorSource.OBD, adapter, status) }
        sourceRow.addView(driveSourceVhalButton)
        sourceRow.addView(driveSourceObdButton)
        root.addView(sourceRow)
        drivePageLabel = label("VHAL · Sayfa 1 / 1", 17f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(drivePageLabel)
        root.addView(label("Sağa/sola kaydır: sayfa değiştir", 15f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(dp(18), 0, dp(18), dp(4))
        })
        root.addView(ListView(this).apply {
            dividerHeight = dp(12)
            setPadding(dp(24), dp(8), dp(24), dp(8))
            clipToPadding = false
            this.adapter = adapter
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshDrivePage(adapter, status)

        logger = SessionLogger(this).also { it.start("drive") }
        val manager = carPropertyManager
        if (manager != null) {
            vhalReader = AndroidVhalReader(manager) { powerMultiplier() }.also { reader ->
                reader.start(onSample = { sample ->
                    logger?.append(sample)
                    runOnUiThread {
                        adapter.update(sample)
                        if (driveSource == SensorSource.VHAL) refreshDrivePage(adapter, status)
                    }
                }, onSafety = { safetyState = it })
            }
        }
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            obdSampleSink = { sample ->
                adapter.update(sample)
                if (driveSource == SensorSource.OBD) refreshDrivePage(adapter, status)
            }
            obdStateSink = { text ->
                if (driveSource == SensorSource.OBD) status.text = text else status.text = driveStatusText()
            }
            ensureObdPolling()
        } else if (manager == null) {
            status.text = "VHAL ve OBD verisi yok"
        }
    }

    private fun selectDriveSource(source: SensorSource, adapter: DriveSensorAdapter, status: TextView?) {
        if (driveSource == source) return
        drivePages[driveSource] = adapter.page
        driveSource = source
        adapter.sourceFilter = source
        adapter.page = drivePages[source] ?: 0
        refreshDrivePage(adapter, status)
        adapter.notifyDataSetChanged()
    }

    private fun changeDrivePage(delta: Int, adapter: DriveSensorAdapter, status: TextView?) {
        val currentPage = drivePages[driveSource] ?: 0
        val next = (currentPage + delta).coerceIn(0, adapter.pageCount() - 1)
        if (next == currentPage) return
        drivePages[driveSource] = next
        adapter.page = next
        adapter.notifyDataSetChanged()
        refreshDrivePage(adapter, status)
    }

    private fun refreshDrivePage(adapter: DriveSensorAdapter, status: TextView?) {
        adapter.page = (drivePages[driveSource] ?: 0).coerceIn(0, adapter.pageCount() - 1)
        drivePages[driveSource] = adapter.page
        val sourceLabel = if (driveSource == SensorSource.VHAL) "VHAL" else "OBD"
        drivePageLabel?.text = "$sourceLabel · Sayfa ${adapter.page + 1} / ${adapter.pageCount()}"
        updateDriveSourceButtons()
        status?.text = driveStatusText()
    }

    private fun driveStatusText(): String {
        val vhalReady = carPropertyManager != null
        val obdReady = obdConnectionState == ObdConnectionState.CONNECTED
        return when {
            vhalReady && obdReady -> "VHAL + OBD aktif"
            vhalReady -> "VHAL aktif · OBD bağlı değil"
            obdReady -> "OBD aktif · VHAL yok"
            else -> "Veri kaynağı bekleniyor"
        }
    }

    private fun updateDriveSourceButtons() {
        val active = color(R.color.lab_accent)
        val inactive = color(R.color.lab_surface_alt)
        driveSourceVhalButton?.background = rounded(
            if (driveSource == SensorSource.VHAL) active else inactive,
            color(R.color.lab_accent),
            dp(10),
        )
        driveSourceObdButton?.background = rounded(
            if (driveSource == SensorSource.OBD) active else inactive,
            color(R.color.lab_accent),
            dp(10),
        )
    }

    private fun showObd() {
        stopActiveScreen()
        currentPage = Page.OBD
        driveStatusView = null
        val root = baseScreen("OBD Verileri", obdScreenStatusText(), true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        val adapter = SensorListAdapter(this)
        val controls = controlRow()
        controls.addView(actionButton("OBD cihazlarını tara") { showObdDevices { showObd() } })
        controls.addView(actionButton("Bağlan ve oku") {
            if (obdConnectionState != ObdConnectionState.CONNECTED) {
                toast("Önce bir OBD cihazı seçin")
                showObdDevices { showObd() }
                return@actionButton
            }
            status.text = "OBD okuma başlatılıyor…"
            logger?.close()
            logger = SessionLogger(this).also { it.start("obd") }
            obdSampleSink = { adapter.update(it) }
            obdStateSink = { status.text = it }
            ensureObdPolling(forceRestart = true)
        })
        controls.addView(actionButton("Durdur") {
            obdPolling?.stop()
            status.text = "OBD okuma durdu · bağlantı açık"
        })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            controls.addView(actionButton("Bağlantıyı kes") { disconnectObdConnection(); status.text = obdScreenStatusText() })
        }
        controls.addView(actionButton("Kayıtları paylaş") { shareLogs() })
        root.addView(controls)
        root.addView(label("Bir sensöre dokun: odak modu · tekrar dokun: genel tarama · ekranlar arası kaydır", 16f, color(R.color.lab_text_secondary)).apply { setPadding(dp(18), 0, 0, dp(6)) })
        root.addView(ListView(this).apply {
            dividerHeight = dp(8)
            setPadding(dp(18), 0, dp(18), dp(12))
            clipToPadding = false
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val key = adapter.getItem(position).definition.key
                val next = if (adapter.focusedKey == key) null else key
                adapter.focusedKey = next
                obdPolling?.focus(next)
                status.text = next?.let { "Odak modu: $it" } ?: "Genel OBD taraması"
                adapter.notifyDataSetChanged()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            obdSampleSink = { adapter.update(it) }
            obdStateSink = { status.text = it }
            ensureObdPolling()
        }
    }

    private fun showScanner() {
        stopActiveScreen()
        currentPage = Page.SCANNER
        driveStatusView = null
        val root = baseScreen("Sensör Keşfi", "AAOS + OBD adım adım rehber", true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        scannerStatus = status
        val eventRows = mutableListOf<String>()
        val eventAdapter = themedStringAdapter(eventRows)

        fun refreshWorkflow() {
            scannerWorkflowView?.text = buildScannerWorkflowText()
            status.text = when {
                obdConnectionState == ObdConnectionState.CONNECTED && safetyState.scannerAllowed -> "● Keşif için hazır"
                obdConnectionState == ObdConnectionState.CONNECTED -> safetyLabel()
                else -> obdScreenStatusText()
            }
        }

        fun ensureScannerReady(): Boolean {
            if (obdConnectionState != ObdConnectionState.CONNECTED) {
                toast("Önce OBD adaptörüne bağlanın")
                showObdDevices {
                    showScanner()
                }
                return false
            }
            if (scanner == null) {
                logger?.close()
                logger = SessionLogger(this).also { it.start("scanner") }
                attachScanner(status)
            }
            if (!safetyState.scannerAllowed) {
                showScrollableHelpDialog(
                    "Tarama şu an güvenli değil",
                    "${safetyState.denialReason()}\n\nOBD taraması yalnızca kontak READY/ON, park freni aktif ve hız ~0 iken yapılır.",
                )
                return false
            }
            return true
        }

        fun runObdTask(name: String, block: () -> Unit) {
            if (!ensureScannerReady()) return
            pauseObdPollingForScanner()
            eventRows.add(0, "▶ $name başlatıldı")
            eventAdapter.notifyDataSetChanged()
            block()
            refreshWorkflow()
        }

        val guidePanel = ScrollView(this).apply {
            isFillViewport = false
            setPadding(0, 0, 0, dp(4))
        }
        val guideContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }

        guideContent.addView(scannerSectionCard(
            "1 · AAOS sensör denemesi (OBD gerekmez)",
            "Sanal motor sesi için önce araç ekranından gaz pedalı %, motor devri (RPM) ve anlık güç okunabilir mi bakılır. Uygulama tek dokunuşla dener ve sonucu açıklar.",
            "AAOS sensörlerini dene",
            "Gaz pedalı, ENGINE_RPM, anlık güç ve gösterge hızını bir kez okur; sanal ses için hangi kaynağı kullanacağını özetler.",
        ) { runVhalProbeDialog { refreshWorkflow() } })

        scannerWorkflowView = label(buildScannerWorkflowText(), 15f, color(R.color.lab_text_secondary)).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(color(R.color.lab_surface), color(R.color.lab_accent), dp(12))
        }
        guideContent.addView(scannerWorkflowView)

        guideContent.addView(scannerSectionCard(
            "2 · OBD ile yeni veri keşfi",
            "Adaptör bağlandıktan sonra aşağıdaki adımları sırayla izleyin. «Otomatik keşif» bilinen aday DID'leri izler; motor sinyalleri için ayrı tarama ECU-E aralığını tarar.",
            "OBD adaptörüne bağlan",
            "Bluetooth cihaz listesini açar. Bağlantı kurulunca scanner otomatik hazırlanır.",
        ) {
            showObdDevices {
                if (obdConnectionState == ObdConnectionState.CONNECTED) {
                    logger?.close()
                    logger = SessionLogger(this).also { it.start("scanner") }
                    attachScanner(status)
                }
                refreshWorkflow()
                showScanner()
            }
        })

        guideContent.addView(scannerActionRow(
            "Otomatik OBD keşfini başlat",
            "Güvenlik koşulları uygunsa bilinen aday DID listesini izlemeye başlar. Sonuçlar alttaki olay günlüğünde görünür.",
        ) {
            runObdTask("Otomatik OBD keşfi") {
                scanner?.watchCandidates(eventSink(eventRows, eventAdapter), scannerStateSink(status))
                    ?: toast("Scanner hazır değil")
            }
        })

        guideContent.addView(scannerActionRow(
            "Motor / gaz sinyali taraması (ECU-E 2B00–2B20)",
            "Sanal motor sesi adayları: 2B04, 2B05, 2B11, FEE7 civarı. Park halinde ECU-E üzerinde kısa DID aralığı tarar.",
        ) {
            runObdTask("Motor DID taraması") {
                scanner?.scanDidPage(EcuContexts.ECU_E, 0x2B00, 0x2B20, eventSink(eventRows, eventAdapter), scannerStateSink(status))
                    ?: toast("Scanner hazır değil")
            }
        })

        guideContent.addView(scannerActionRow(
            "Kısa ECU adres yoklaması",
            "Bilinmeyen ECU header adaylarını (0x1640…0x17A0) yoklar; yeni modül bulmak için ikinci aşama.",
        ) {
            runObdTask("ECU yoklaması") {
                scanner?.scanKnownEcuCandidates(eventSink(eventRows, eventAdapter), scannerStateSink(status))
                    ?: toast("Scanner hazır değil")
            }
        })

        guideContent.addView(scannerSectionCard(
            "3 · Car Scanner HCI profili (telefon / tablet)",
            "Car Scanner'ın adaptöre gönderdiği komutları kopyalamak için telefonda Bluetooth HCI kaydı alınır; profil JSON olarak içe aktarılır.",
            "HCI kayıt rehberini göster",
            "Telefon/tablet adımları, bugreport alma ve profil çıkarma komutları.",
        ) { showScrollableHelpDialog("Bluetooth HCI kayıt rehberi", hciCaptureGuideText()) })

        val hciRow = controlRow()
        hciRow.addView(scannerActionRow(
            "HCI profili içe aktar",
            "Mac/PC'de üretilen ex30-profile.json dosyasını seçin.",
            compact = true,
        ) { openProfile() })
        hciRow.addView(scannerActionRow(
            "Profili oynat",
            "İçe aktarılan salt-okunur sorguları adaptörde tekrarlar; Car Scanner'ın hangi DID'leri sorduğunu görürsünüz.",
            compact = true,
        ) {
            val profile = importedProfile
            if (profile == null) {
                toast("Önce HCI profili içe aktarın")
                return@scannerActionRow
            }
            runObdTask("HCI profil oynatma") {
                scanner?.replayProfile(profile, eventSink(eventRows, eventAdapter), scannerStateSink(status))
                    ?: toast("Scanner hazır değil")
            }
        })
        guideContent.addView(hciRow)

        val controlRowFooter = controlRow()
        controlRowFooter.addView(scannerActionRow(
            "Taramayı durdur",
            "Devam eden OBD taramasını durdurur; normal OBD okumaya döner.",
            compact = true,
        ) {
            scanner?.stop()
            resumeObdPollingAfterScanner()
            eventRows.add(0, "■ Tarama durduruldu")
            eventAdapter.notifyDataSetChanged()
            refreshWorkflow()
        })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            controlRowFooter.addView(scannerActionRow(
                "OBD bağlantısını kes",
                "Adaptör bağlantısını kapatır.",
                compact = true,
            ) {
                disconnectObdConnection()
                refreshWorkflow()
            })
        }
        controlRowFooter.addView(scannerActionRow(
            "Kayıtları paylaş",
            "Keşif oturumu CSV/JSONL loglarını dışa aktarır.",
            compact = true,
        ) { shareLogs() })
        guideContent.addView(controlRowFooter)

        guidePanel.addView(guideContent)
        root.addView(guidePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        root.addView(label("Olay günlüğü · pozitif yanıt / aday / NRC", 14f, color(R.color.lab_text_secondary)).apply {
            setPadding(dp(18), dp(4), dp(18), dp(2))
        })
        root.addView(ListView(this).apply {
            adapter = eventAdapter
            dividerHeight = dp(2)
            setPadding(dp(18), 0, dp(18), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val manager = carPropertyManager
        if (manager != null) {
            vhalReader = AndroidVhalReader(manager) { powerMultiplier() }.also { reader ->
                reader.start(onSample = {}, onSafety = {
                    safetyState = it
                    runOnUiThread { refreshWorkflow() }
                })
            }
        } else {
            status.text = "Car API yok — yalnızca OBD keşfi kullanılabilir"
        }

        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            logger?.close()
            logger = SessionLogger(this).also { it.start("scanner") }
            attachScanner(status)
        }
        refreshWorkflow()
        if (obdConnectionState == ObdConnectionState.CONNECTED) ensureObdPolling()
    }

    private fun showObdDevices(returnTo: () -> Unit) {
        stopScreenResources()
        currentPage = null
        driveStatusView = null
        obdDeviceReturnAction = returnTo
        val scanner = BluetoothObdDeviceScanner(this).also { deviceScanner = it }
        val root = baseScreen("OBD Cihazları", "Bluetooth cihazlarını seçin", true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        val devices = linkedMapOf<String, ObdDeviceEntry>()
        val rows = mutableListOf<String>()
        val adapter = themedStringAdapter(rows)

        fun refreshList() {
            rows.clear()
            devices.values.sortedWith(compareByDescending<ObdDeviceEntry> { it.bonded }.thenBy { it.name.lowercase() })
                .forEach { entry ->
                    val prefix = if (entry.bonded) "● Eşleşmiş" else "○ Keşfedildi"
                    rows += "$prefix · ${entry.name} · ${entry.address}"
                }
            if (rows.isEmpty()) rows += "Henüz cihaz bulunamadı"
            adapter.notifyDataSetChanged()
        }

        fun loadBonded() {
            if (!ensureBluetoothPermissions(status)) return
            try {
                scanner.bondedDevices().forEach { devices[it.address] = it }
                refreshList()
                status.text = "${devices.size} cihaz listelendi"
            } catch (e: Exception) {
                status.text = "Cihaz listesi alınamadı: ${e.message}"
            }
        }

        val controls = controlRow()
        var scanButton: Button? = null
        scanButton = actionButton("Taramayı başlat") {
            if (!ensureBluetoothPermissions(status)) return@actionButton
            if (scanner.isScanning) {
                scanner.stopDiscovery()
                scanButton?.text = "Taramayı başlat"
                status.text = "${devices.size} cihaz listelendi"
                return@actionButton
            }
            scanButton?.text = "Taramayı durdur"
            status.text = "Yakındaki Bluetooth cihazları taranıyor…"
            try {
                scanner.startDiscovery(
                    onFound = { entry ->
                        runOnUiThread {
                            devices[entry.address] = entry
                            refreshList()
                            status.text = "Tarama sürüyor · ${devices.size} cihaz"
                        }
                    },
                    onFinished = {
                        runOnUiThread {
                            scanButton?.text = "Taramayı başlat"
                            status.text = "Tarama tamamlandı · ${devices.size} cihaz"
                        }
                    },
                )
            } catch (e: Exception) {
                scanButton?.text = "Taramayı başlat"
                status.text = "Tarama başlatılamadı: ${e.message}"
            }
        }
        controls.addView(scanButton)
        controls.addView(actionButton("Eşleşmiş cihazları yenile") { loadBonded() })
        controls.addView(actionButton("Geri") {
            scanner.stopDiscovery()
            deviceScanner = null
            returnTo()
        })
        root.addView(controls)
        root.addView(label("Bağlanmak için bir cihaza dokunun", 16f, color(R.color.lab_text_secondary)).apply {
            setPadding(dp(18), 0, 0, dp(6))
        })
        root.addView(ListView(this).apply {
            this.adapter = adapter
            dividerHeight = dp(8)
            setPadding(dp(18), 0, dp(18), dp(12))
            setOnItemClickListener { _, _, position, _ ->
                val sorted = devices.values.sortedWith(
                    compareByDescending<ObdDeviceEntry> { it.bonded }.thenBy { it.name.lowercase() },
                )
                if (position >= sorted.size) return@setOnItemClickListener
                val entry = sorted[position]
                connectObdToDevice(entry, status) {
                    scanner.stopDiscovery()
                    deviceScanner = null
                    toast("OBD bağlantısı kuruldu")
                    returnTo()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadBonded()
    }

    private fun connectObdToDevice(entry: ObdDeviceEntry, status: TextView, onConnected: () -> Unit = {}) {
        if (!ensureBluetoothPermissions(status)) return
        deviceScanner?.stopDiscovery()
        obdDeviceName = entry.name
        obdDeviceAddress = entry.address
        obdErrorMessage = null
        setObdConnectionState(ObdConnectionState.CONNECTING)
        status.text = "${entry.name} bağlanıyor…"
        ioExecutor.execute {
            try {
                elmProtocol?.disconnect()
                val protocol = ElmProtocol(BluetoothElmTransport(this@MainActivity)) { command, response ->
                    logger?.appendProtocol(command, response)
                }
                runOnUiThread { status.text = "${entry.name} · ELM327 başlatılıyor…" }
                val id = protocol.connect(entry.address, verifyLink = false)
                elmProtocol = protocol
                obdAdapterId = id.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "ELM327" }
                runOnUiThread {
                    setObdConnectionState(ObdConnectionState.CONNECTED)
                    status.text = "● Bağlı: ${entry.name} · $obdAdapterId"
                    ensureObdPolling(forceRestart = true)
                    onConnected()
                }
                try {
                    protocol.verifyLink()
                    runOnUiThread { refreshObdIndicator() }
                } catch (verifyError: Exception) {
                    runOnUiThread {
                        obdErrorMessage = verifyError.message
                        toast("ECU doğrulama uyarısı: ${verifyError.message ?: "bilinmiyor"}")
                        refreshObdIndicator()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    obdErrorMessage = e.message ?: "Bağlantı hatası"
                    setObdConnectionState(ObdConnectionState.ERROR)
                    status.text = "Bağlantı hatası: $obdErrorMessage"
                    toast("OBD bağlantısı kurulamadı: $obdErrorMessage")
                }
            }
        }
    }

    private fun ensureObdPolling(forceRestart: Boolean = false) {
        val protocol = elmProtocol ?: return
        if (obdConnectionState != ObdConnectionState.CONNECTED || obdPollingPausedForScanner) return
        if (forceRestart) {
            obdPolling?.stop()
        }
        val existing = obdPolling
        if (existing != null) {
            existing.start(
                onSample = { sample ->
                    logger?.append(sample)
                    runOnUiThread { obdSampleSink?.invoke(sample) }
                },
                onState = { text ->
                    runOnUiThread {
                        obdStateSink?.invoke(text)
                        refreshObdIndicator()
                    }
                },
            )
            return
        }
        obdPolling = ObdPollingController(protocol).also { polling ->
            polling.start(
                onSample = { sample ->
                    logger?.append(sample)
                    runOnUiThread { obdSampleSink?.invoke(sample) }
                },
                onState = { text ->
                    runOnUiThread {
                        obdStateSink?.invoke(text)
                        refreshObdIndicator()
                    }
                },
            )
        }
    }

    private fun pauseObdPollingForScanner() {
        obdPollingPausedForScanner = true
        obdPolling?.stop()
    }

    private fun resumeObdPollingAfterScanner() {
        if (!obdPollingPausedForScanner) return
        obdPollingPausedForScanner = false
        if (obdConnectionState == ObdConnectionState.CONNECTED) ensureObdPolling(forceRestart = true)
    }

    private fun scannerStateSink(status: TextView): (String) -> Unit = { text ->
        runOnUiThread {
            status.text = text
            if (text.contains("durdu", ignoreCase = true) || text.contains("tamamland", ignoreCase = true)) {
                resumeObdPollingAfterScanner()
            }
        }
    }

    private fun attachScanner(status: TextView) {
        val protocol = elmProtocol
        if (protocol == null || obdConnectionState != ObdConnectionState.CONNECTED) {
            status.text = "OBD bağlı değil"
            return
        }
        scanner?.close()
        scanner = ScannerController(protocol) { safetyState }
        status.text = safetyLabel()
    }

    private fun disconnectObdConnection() {
        obdPolling?.close()
        obdPolling = null
        obdPollingPausedForScanner = false
        obdSampleSink = null
        obdStateSink = null
        scanner?.close()
        scanner = null
        try { elmProtocol?.disconnect() } catch (_: Exception) {}
        elmProtocol = null
        obdDeviceName = null
        obdDeviceAddress = null
        obdAdapterId = null
        obdErrorMessage = null
        setObdConnectionState(ObdConnectionState.DISCONNECTED)
    }

    private fun setObdConnectionState(state: ObdConnectionState) {
        obdConnectionState = state
        refreshObdIndicator()
    }

    private fun refreshObdIndicator() {
        obdStatusView?.let { view ->
            view.text = obdIndicatorText()
            view.setTextColor(obdIndicatorColor())
            view.background = obdIndicatorBackground()
        }
    }

    private fun obdIndicatorText(): String = when (obdConnectionState) {
        ObdConnectionState.DISCONNECTED -> "○ OBD\nBağlı değil"
        ObdConnectionState.CONNECTING -> "◐ OBD\nBağlanıyor…"
        ObdConnectionState.CONNECTED -> buildString {
            append("● OBD\n")
            append(obdDeviceName ?: "Bağlı")
            obdAdapterId?.let { append("\n").append(it) }
        }
        ObdConnectionState.ERROR -> "✕ OBD\nHata"
    }

    private fun obdIndicatorBackground(): GradientDrawable = rounded(
        color(R.color.lab_surface),
        obdIndicatorColor(),
        dp(12),
    )

    private fun obdIndicatorColor(): Int = when (obdConnectionState) {
        ObdConnectionState.CONNECTED -> color(R.color.lab_success)
        ObdConnectionState.CONNECTING -> color(R.color.lab_warning)
        ObdConnectionState.ERROR -> color(R.color.lab_error)
        ObdConnectionState.DISCONNECTED -> color(R.color.lab_text_secondary)
    }

    private fun obdScreenStatusText(): String = when (obdConnectionState) {
        ObdConnectionState.CONNECTED -> "● Bağlı: ${obdDeviceName ?: "OBD"} · ${obdAdapterId ?: "ELM327"}"
        ObdConnectionState.CONNECTING -> "◐ ${obdDeviceName ?: "OBD"} bağlanıyor…"
        ObdConnectionState.ERROR -> "Bağlantı hatası: ${obdErrorMessage ?: "bilinmiyor"}"
        ObdConnectionState.DISCONNECTED -> "○ OBD bağlı değil"
    }

    private fun ensureBluetoothPermissions(status: TextView? = null): Boolean {
        val missing = requiredBluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        status?.text = "Bluetooth izni gerekli"
        toast("Bluetooth izni gerekli")
        return false
    }

    private fun requiredBluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        return permissions
    }

    private fun stopScreenResources() {
        deviceScanner?.stopDiscovery()
        deviceScanner = null
        vhalReader?.stop()
        vhalReader = null
        obdSampleSink = null
        obdStateSink = null
        if (currentPage == Page.SCANNER) {
            obdPollingPausedForScanner = false
        }
        if (obdConnectionState != ObdConnectionState.CONNECTED) {
            obdPolling?.close()
            obdPolling = null
        }
        scanner?.close()
        scanner = null
        logger?.close()
        logger = null
        scannerStatus = null
        scannerWorkflowView = null
    }

    private fun stopActiveScreen(disconnectObd: Boolean = false) {
        stopScreenResources()
        if (disconnectObd) disconnectObdConnection()
    }

    private fun openProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, REQUEST_PROFILE)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Profil okunamadı")
            importedProfile = ScanProfileParser.parse(text)
            scannerStatus?.text = "Profil hazır: ${importedProfile?.queries?.size ?: 0} salt-okunur sorgu"
            importedProfile?.let(::showProfileReview)
        } catch (e: Exception) {
            scannerStatus?.text = "Profil reddedildi: ${e.message}"
        }
    }

    private fun showProfileReview(profile: ScanProfile) {
        val rows = profile.queries.joinToString("\n") { query ->
            "${query.ecu?.name ?: "Standart OBD"} · ${query.service}${query.did} · ${query.observationCount} gözlem"
        }
        AlertDialog.Builder(this)
            .setTitle("HCI profili inceleme")
            .setMessage("Kaynak: ${profile.sourceSession}\n\n$rows")
            .setPositiveButton("Onayla ve kapat", null)
            .show()
    }

    private fun eventSink(rows: MutableList<String>, adapter: ArrayAdapter<String>): (com.kadireren.ex30sensorlab.model.ScanEvent) -> Unit = { event ->
        logger?.append(event)
        runOnUiThread {
            rows.add(0, "${event.ecu} · ${event.command} · ${event.classification} · ${event.rawResponse.take(120)}")
            if (rows.size > 500) rows.removeAt(rows.lastIndex)
            adapter.notifyDataSetChanged()
        }
    }

    private fun stateSink(status: TextView): (String) -> Unit = { text -> runOnUiThread { status.text = text } }

    private fun shareLogs() {
        val files = listOfNotNull(logger?.csvFile, logger?.jsonlFile).filter(File::isFile)
        if (files.isEmpty()) {
            toast("Paylaşılacak kayıt yok")
            return
        }
        val uris = ArrayList(files.map { Uri.parse("content://${packageName}.files/${it.name}") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        startActivity(Intent.createChooser(intent, "Sensör kayıtlarını paylaş"))
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Car.PERMISSION_SPEED, Car.PERMISSION_ENERGY, Car.PERMISSION_POWERTRAIN,
            Car.PERMISSION_CAR_INFO, Car.PERMISSION_ENERGY_PORTS, Car.PERMISSION_EXTERIOR_ENVIRONMENT,
            "android.car.permission.READ_CAR_PEDALS",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_SCAN
        val missing = permissions.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun safetyLabel(): String = if (safetyState.scannerAllowed) "● Tarama güvenlik koşulları hazır" else "Tarama kilitli: ${safetyState.denialReason()}"

    private fun savePowerMultiplier(value: Int) {
        getSharedPreferences("lab", MODE_PRIVATE).edit().putInt("vhal_power_multiplier", value).apply()
    }

    private fun powerMultiplier(): Int = getSharedPreferences("lab", MODE_PRIVATE).getInt("vhal_power_multiplier", 0)

    private fun baseScreen(title: String, status: String, back: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.lab_background))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(10))
            if (back) addView(actionButton("‹ Ana menü") { showHome() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 28f, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(label(status, 17f, color(R.color.lab_text_secondary)).apply { tag = "status" })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                tag = "obd_indicator"
                text = obdIndicatorText()
                setTextColor(obdIndicatorColor())
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minWidth = dp(132)
                background = obdIndicatorBackground()
                obdStatusView = this
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun themedStringAdapter(items: MutableList<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_list_item_1, items,
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            view.setTextColor(color(R.color.lab_text))
            view.setBackgroundColor(color(R.color.lab_surface))
            view.setPadding(dp(16), dp(14), dp(16), dp(14))
            view.textSize = 17f
            return view
        }
    }

    private fun menuCard(number: String, title: String, subtitle: String, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(color(R.color.lab_surface), color(R.color.lab_accent), dp(18))
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(label(title, 22f, Color.WHITE, Gravity.CENTER).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(label(number, 30f, color(R.color.lab_accent), Gravity.CENTER).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(label(subtitle, 14f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun controlRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
    }

    private fun actionButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        isAllCaps = false
        background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_accent), dp(10))
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(10) }
    }

    private fun largeTabButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 22f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_accent), dp(14))
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(12) }
    }

    private fun hexInput(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        setSingleLine(true)
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(color(R.color.lab_surface), color(R.color.lab_accent), dp(8))
    }

    private fun label(text: String, size: Float, textColor: Int, gravityValue: Int = Gravity.START) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(textColor)
        gravity = gravityValue
    }

    private fun buildScannerWorkflowText(): String {
        val obd = when (obdConnectionState) {
            ObdConnectionState.CONNECTED -> "✓ ${obdDeviceName ?: "OBD"} bağlı"
            ObdConnectionState.CONNECTING -> "◐ bağlanıyor…"
            ObdConnectionState.ERROR -> "✕ bağlantı hatası"
            ObdConnectionState.DISCONNECTED -> "○ henüz bağlı değil → «OBD adaptörüne bağlan»"
        }
        val safety = if (safetyState.scannerAllowed) "✓ park freni + sabit + READY/ON"
        else "✕ ${safetyState.denialReason()}"
        val scannerReady = if (scanner != null && obdConnectionState == ObdConnectionState.CONNECTED) "✓ scanner hazır"
        else if (obdConnectionState == ObdConnectionState.CONNECTED) "◐ bağlanınca otomatik hazırlanır"
        else "○ OBD bağlantısı gerekli"
        val profile = importedProfile?.let { "✓ ${it.queries.size} salt-okunur sorgu içe aktarıldı" }
            ?: "○ HCI profili yok (isteğe bağlı)"
        return buildString {
            appendLine("OBD keşif kontrol listesi")
            appendLine("Adım 1 · Adaptör: $obd")
            appendLine("Adım 2 · Güvenlik: $safety")
            appendLine("Adım 3 · Scanner: $scannerReady")
            appendLine("Adım 4 · HCI profili: $profile")
            appendLine()
            append("Sıra: AAOS dene → OBD bağlan → Otomatik keşif → (isteğe bağlı) HCI profili")
        }
    }

    private fun runVhalProbeDialog(onFinished: () -> Unit = {}) {
        val manager = carPropertyManager
        if (manager == null) {
            showScrollableHelpDialog(
                "AAOS sensör denemesi",
                "Car API bu ortamda yok (emülatör veya izin eksik).\n\nGerçek EX30 araç ekranında bu test gaz pedalı ve motor devri VHAL property'lerini okumayı dener.",
            )
            onFinished()
            return
        }
        val results = VhalProbe.run(manager) { powerMultiplier() }
        val detail = buildString {
            results.forEach { row ->
                appendLine("• ${row.label} (${row.propertyHex})")
                appendLine("  Durum: ${row.status}")
                if (row.displayValue != "—") appendLine("  Değer: ${row.displayValue} · ham: ${row.rawValue}")
                appendLine("  ${row.note}")
                appendLine()
            }
            appendLine(VhalProbe.summary(results))
        }
        showScrollableHelpDialog("AAOS sensör denemesi sonucu", detail)
        onFinished()
    }

    private fun showScrollableHelpDialog(title: String, body: String) {
        val scroll = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = body
                setTextColor(color(R.color.lab_text))
                textSize = 15f
                setPadding(dp(20), dp(12), dp(20), dp(8))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun hciCaptureGuideText(): String = """
Bu kayıt araç ekranında değil, Car Scanner kurulu ayrı bir Android telefon veya tablette yapılır.

1) Telefonda Geliştirici seçeneklerini açın → «Bluetooth HCI snoop log» etkin.
2) Bluetooth'u kapatıp açın. Android-Vlink adaptörünü yalnız Car Scanner ile eşleştirin/bağlayın.
3) Car Scanner'da sanal motor sesi için ilgili göstergeleri açın (Engine RPM, Throttle, Accelerator pedal vb.).
4) 60–120 saniye kayıt alın: dur → hafif gaz → orta gaz → gaz bırak (regen).
5) Car Scanner bağlantısını kesin. Aynı adaptöre iki uygulama aynı anda bağlanmasın.
6) Telefonu USB ile bilgisayara bağlayın:
   adb bugreport bugreport-ex30.zip
7) Profil üretin (Mac/PC, proje klasöründe):
   python3 tools/extract_hci_profile.py bugreport-ex30.zip ex30-profile.json
8) JSON dosyasını USB veya bulut ile EX30'e aktarın → bu ekranda «HCI profili içe aktar» → «Profili oynat».

Not: Kayıt kişisel veri içerebilir; ham bugreport'u herkese açık paylaşmayın. Uygulama yalnız salt-okunur 01xx ve 22xxxx sorgularını kabul eder.
""".trimIndent()

    private fun scannerSectionCard(
        title: String,
        body: String,
        buttonLabel: String,
        buttonHelp: String,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(color(R.color.lab_surface), color(R.color.lab_accent), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
        addView(label(title, 18f, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(label(body, 14f, color(R.color.lab_text_secondary)).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        addView(actionButton(buttonLabel, onClick))
        addView(label(buttonHelp, 13f, color(R.color.lab_text_secondary)).apply {
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun scannerActionRow(
        buttonLabel: String,
        help: String,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (!compact) {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_accent), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        } else {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        addView(actionButton(buttonLabel, onClick))
        addView(label(help, 12f, color(R.color.lab_text_secondary)).apply {
            setPadding(0, dp(4), 0, 0)
            if (compact) maxLines = 3
        })
    }

    private fun messageSample(message: String, status: SampleStatus) = SensorSample(
        SensorDefinition("message", message, SensorSource.VHAL, "—", "", 0f), "—", "—",
        android.os.SystemClock.elapsedRealtime(), status = status,
    )

    private fun menuCardLayoutParams(marginStart: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        this.marginStart = marginStart
    }

    private fun weighted(marginStart: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        this.marginStart = marginStart
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun color(id: Int): Int = getColor(id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_PROFILE = 1002
        private const val SWIPE_MIN_VELOCITY = 250f
        private val SWIPE_PAGES = listOf(Page.AAOS, Page.OBD, Page.SCANNER, Page.DRIVE)
    }
}
