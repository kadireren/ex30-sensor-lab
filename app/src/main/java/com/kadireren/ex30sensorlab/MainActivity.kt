package com.kadireren.ex30sensorlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.kadireren.ex30sensorlab.discovery.CalibrationCandidate
import com.kadireren.ex30sensorlab.discovery.CalibrationPhase
import com.kadireren.ex30sensorlab.discovery.CalibrationScorer
import com.kadireren.ex30sensorlab.discovery.DiscoveredQueryRecord
import com.kadireren.ex30sensorlab.discovery.DiscoveredSensorCatalog
import com.kadireren.ex30sensorlab.discovery.DiscoveredSensorConfig
import com.kadireren.ex30sensorlab.discovery.DiscoveredSensorStore
import com.kadireren.ex30sensorlab.discovery.DiscoveryDecodeType
import com.kadireren.ex30sensorlab.discovery.DiscoveryTarget
import com.kadireren.ex30sensorlab.discovery.DiscoveryStoreState
import com.kadireren.ex30sensorlab.logging.DownloadStorage
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
import com.kadireren.ex30sensorlab.obd.ObdCatalog
import com.kadireren.ex30sensorlab.obd.ObdPollingController
import com.kadireren.ex30sensorlab.obd.ObdPreferredDevice
import com.kadireren.ex30sensorlab.scanner.ScanProfileParser
import com.kadireren.ex30sensorlab.scanner.ScannerController
import com.kadireren.ex30sensorlab.ui.DriveSensorAdapter
import com.kadireren.ex30sensorlab.ui.DriveLayout
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
    private enum class Page { AAOS, OBD, SCANNER, DRIVE, MOTOR }

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var vhalReader: AndroidVhalReader? = null
    private var elmProtocol: ElmProtocol? = null
    private var obdPolling: ObdPollingController? = null
    private var scanner: ScannerController? = null
    private var logger: SessionLogger? = null
    private var deviceScanner: BluetoothObdDeviceScanner? = null
    private var safetyState = SafetyState()
    private var discoveryReplayPositive = 0
    private var importedProfile: ScanProfile? = null
    private var motorSensorAdapter: SensorListAdapter? = null
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
    private var pendingLaunchAutoConnect = true
    private var preferredDiscoveryActive = false
    private val autoConnectHandler = Handler(Looper.getMainLooper())
    private var sensorListAdapter: SensorListAdapter? = null
    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            sensorListAdapter?.notifyDataSetChanged()
            motorSensorAdapter?.notifyDataSetChanged()
            if (sensorListAdapter != null || motorSensorAdapter != null) {
                statusRefreshHandler.postDelayed(this, STATUS_REFRESH_MS)
            }
        }
    }
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
        loadDiscoveryStore()
        showHome()
        window.decorView.post { attemptPreferredObdAutoConnect() }
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
            Page.MOTOR -> showMotorSensors()
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

    override fun onStop() {
        stopStatusRefresh()
        scanner?.stop()
        obdPolling?.stop()
        obdPollingPausedForScanner = false
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        startStatusRefresh()
        if (pendingLaunchAutoConnect) attemptPreferredObdAutoConnect()
        if (currentPage != Page.SCANNER && obdConnectionState == ObdConnectionState.CONNECTED) {
            ensureObdPolling()
        }
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
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        cards.addView(menuCard("1", "AAOS Verileri", "${VhalCatalog.entries.size} VHAL sensörü") { showAaos() }, menuCardLayoutParams())
        cards.addView(menuCard("2", "OBD Verileri", "Bluetooth OBD adaptörü ile okuma") { showObd() }, menuCardLayoutParams(dp(16)))
        root.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        row2.addView(menuCard("3", "Sensör Keşfi", "Faz 1–2 · HCI + kalibrasyon") { showScanner() }, menuCardLayoutParams())
        row2.addView(menuCard("4", "Sürüş Görünümü", "Tam ekran · yoğun sensör ızgarası") { showDriveView() }, menuCardLayoutParams(dp(16)))
        root.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val homeActions = controlRow().apply { setPadding(dp(20), dp(5), dp(20), dp(5)) }
        homeActions.addView(homeActionButton("OBD'ye bağlan", color(R.color.lab_accent), color(R.color.lab_accent_surface)) {
            tryConnectPreferredObd(returnTo = { showHome() }, showListOnFailure = true)
        })
        homeActions.addView(homeActionButton("Diğer cihazlar", color(R.color.lab_violet)) { showObdDevices { showHome() } })
        homeActions.addView(homeActionButton("Bağlantıyı kes", color(R.color.lab_warning)) {
            if (obdConnectionState == ObdConnectionState.CONNECTED) {
                disconnectObdConnection()
                showHome()
            } else {
                toast("OBD bağlı değil")
            }
        })
        homeActions.addView(homeActionButton("Uygulamadan çık", color(R.color.lab_error), color(R.color.lab_error_surface), endMargin = 0) { exitApp() })
        root.addView(homeActions)
        root.addView(label("● Scanner güvenlik kapıları etkin   ·   v${appVersionName()}", 13f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(0, dp(5), 0, dp(8))
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
        sensorListAdapter = adapter
        startStatusRefresh()
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
        val adapter = DriveSensorAdapter(this, SensorSource.VHAL, driveLayout()).also { driveAdapter = it }
        val root = baseScreen("Sürüş Görünümü", "Canlı sensör değerleri", true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status").also { driveStatusView = it }
        val sourceRow = controlRow().apply { setPadding(dp(12), dp(4), dp(12), dp(2)) }
        driveSourceVhalButton = driveTabButton("VHAL") { selectDriveSource(SensorSource.VHAL, adapter, status) }
        driveSourceObdButton = driveTabButton("OBD") { selectDriveSource(SensorSource.OBD, adapter, status) }
        sourceRow.addView(driveSourceVhalButton)
        sourceRow.addView(driveSourceObdButton)
        var layoutButton: Button? = null
        layoutButton = driveTabButton(adapter.layout.label) { showDriveLayoutPicker(adapter, layoutButton) }
        sourceRow.addView(layoutButton)
        root.addView(sourceRow)
        drivePageLabel = label("VHAL · Sayfa 1 / 1", 15f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(drivePageLabel)
        val listView = ListView(this).apply {
            dividerHeight = dp(4)
            setPadding(dp(10), dp(2), dp(10), dp(4))
            clipToPadding = false
            this.adapter = adapter
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        listView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left - listView.paddingLeft - listView.paddingRight
            val height = bottom - top - listView.paddingTop - listView.paddingBottom
            if (width > 0 && height > 0 && adapter.fitToViewport(width, height, listView.dividerHeight)) {
                refreshDrivePage(adapter, status)
            }
        }
        root.post {
            val density = resources.displayMetrics.density
            Log.i(
                "EX30_LAYOUT",
                "widthPx=${root.width} heightPx=${root.height} listH=${listView.height} pageSize=${adapter.pageSize} density=$density widthDp=${root.width / density} heightDp=${root.height / density} orientation=${resources.configuration.orientation}",
            )
        }
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

    private fun showDriveLayoutPicker(adapter: DriveSensorAdapter, button: Button?) {
        val layouts = DriveLayout.entries
        AlertDialog.Builder(this)
            .setTitle("Sürüş görünümü")
            .setSingleChoiceItems(layouts.map { it.label }.toTypedArray(), layouts.indexOf(adapter.layout)) { dialog, selected ->
                adapter.layout = layouts[selected]
                getSharedPreferences("lab", MODE_PRIVATE).edit().putString("drive_layout", adapter.layout.name).apply()
                button?.text = adapter.layout.label
                val list = findDriveListView()
                if (list != null && list.width > 0 && list.height > 0) {
                    adapter.fitToViewport(
                        list.width - list.paddingLeft - list.paddingRight,
                        list.height - list.paddingTop - list.paddingBottom,
                        list.dividerHeight,
                    )
                } else {
                    adapter.notifyDataSetChanged()
                }
                refreshDrivePage(adapter, driveStatusView)
                dialog.dismiss()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun driveLayout(): DriveLayout = runCatching {
        DriveLayout.valueOf(getSharedPreferences("lab", MODE_PRIVATE).getString("drive_layout", DriveLayout.GRID.name)!!)
    }.getOrDefault(DriveLayout.GRID)

    private fun findDriveListView(): ListView? {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return null
        return content.findListView()
    }

    private fun ViewGroup.findListView(): ListView? {
        for (index in 0 until childCount) {
            when (val child = getChildAt(index)) {
                is ListView -> return child
                is ViewGroup -> child.findListView()?.let { return it }
            }
        }
        return null
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
        drivePageLabel?.text =
            "$sourceLabel · Sayfa ${adapter.page + 1} / ${adapter.pageCount()} · ${adapter.pageSize} sensör/sayfa · kaydır"
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
        sensorListAdapter = adapter
        startStatusRefresh()
        val controls = controlRow()
        controls.addView(actionButton("Download'a aktar") { exportLogsToDownload() })
        root.addView(controls)
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
            logger?.close()
            logger = SessionLogger(this).also { it.start("obd") }
            obdSampleSink = { adapter.update(it) }
            obdStateSink = { status.text = it }
            ensureObdPolling(forceRestart = true)
        }
    }

    private fun exitApp() {
        stopActiveScreen(disconnectObd = true)
        finishAffinity()
    }

    private fun showMotorSensors() {
        stopActiveScreen()
        currentPage = Page.MOTOR
        driveStatusView = null
        val state = DiscoveredSensorStore.snapshot()
        val root = baseScreen("Motor sensörleri", motorScreenStatusText(state), true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        val adapter = SensorListAdapter(this)
        motorSensorAdapter = adapter
        seedMotorSensorPlaceholders(adapter)
        val visibleKeys = ObdCatalog.motorSignals.map { it.key }.toSet()
        startStatusRefresh()

        val controls = controlRow()
        controls.addView(actionButton("Sensör Keşfi (Faz 1–2)") { showScanner() })
        controls.addView(actionButton("Bağlan ve oku") {
            if (obdConnectionState != ObdConnectionState.CONNECTED) {
                toast("Önce OBD adaptörüne bağlanın")
                tryConnectPreferredObd(returnTo = { showMotorSensors() }, showListOnFailure = true)
                return@actionButton
            }
            status.text = "Motor sensörleri okunuyor…"
            logger?.close()
            logger = SessionLogger(this).also { it.start("motor") }
            obdSampleSink = { sample ->
                if (sample.definition.key in visibleKeys) adapter.update(sample)
            }
            obdStateSink = { status.text = it }
            ensureObdPolling(forceRestart = true)
        })
        controls.addView(actionButton("Durdur") {
            obdPolling?.stop()
            status.text = motorScreenStatusText(DiscoveredSensorStore.snapshot()) + " · okuma durdu"
        })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            controls.addView(actionButton("Bağlantıyı kes") {
                disconnectObdConnection()
                status.text = motorScreenStatusText(DiscoveredSensorStore.snapshot())
            })
        }
        controls.addView(actionButton("Keşif raporu") { exportDiscoveryArtifacts() })
        root.addView(controls)
        root.addView(label("Hedefler: gaz pedalı PWM · ERAD motor devri · ERAD tork", 16f, color(R.color.lab_text_secondary)).apply {
            setPadding(dp(18), 0, 0, dp(6))
        })
        root.addView(ListView(this).apply {
            dividerHeight = dp(8)
            setPadding(dp(18), 0, dp(18), dp(12))
            clipToPadding = false
            this.adapter = adapter
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            obdSampleSink = { sample ->
                if (sample.definition.key in visibleKeys) adapter.update(sample)
            }
            obdStateSink = { status.text = it }
            ensureObdPolling()
        }
    }

    private fun showScanner() {
        stopActiveScreen()
        currentPage = Page.SCANNER
        driveStatusView = null
        val root = baseScreen("Sensör Keşfi", "Faz 1 HCI replay · Faz 2 kalibrasyon · Faz 3 LIVE", true)
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
                tryConnectPreferredObd(returnTo = { showScanner() }, showListOnFailure = true, statusOverride = status)
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
            "Faz 1 · HCI profili ve keşif replay",
            "Tablet + IOS-Vlink + Car Scanner ile HCI kaydı alın. Profili içe aktarın; araçta replay pozitif UDS yanıtlarını discovered_sensors.json dosyasına yazar.",
            "HCI kayıt rehberini göster",
            "Telefon/tablet adımları, bugreport ve extract_hci_profile.py komutları.",
        ) { showScrollableHelpDialog("Bluetooth HCI kayıt rehberi", hciCaptureGuideText()) })

        scannerWorkflowView = label(buildScannerWorkflowText(), 15f, color(R.color.lab_text_secondary)).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(color(R.color.lab_surface), color(R.color.lab_accent), dp(12))
        }
        guideContent.addView(scannerWorkflowView)

        val faz1Row = controlRow()
        faz1Row.addView(scannerActionRow(
            "OBD adaptörüne bağlan",
            "EX30 adaptörü (Android-Vlink). Bağlantı kurulunca scanner hazırlanır.",
            compact = true,
        ) {
            tryConnectPreferredObd(returnTo = {
                if (obdConnectionState == ObdConnectionState.CONNECTED) {
                    logger?.close()
                    logger = SessionLogger(this).also { it.start("scanner") }
                    attachScanner(status)
                }
                refreshWorkflow()
                showScanner()
            }, showListOnFailure = true, statusOverride = status)
        })
        faz1Row.addView(scannerActionRow(
            "HCI profili içe aktar",
            "Download/EX30SensorLab/*.json veya dosya seçici.",
            compact = true,
        ) { openProfile() })
        guideContent.addView(faz1Row)

        guideContent.addView(scannerActionRow(
            "Keşif replay (Faz 1 · kaydet)",
            "Profildeki salt-okunur sorguları tekrarlar; pozitif yanıtları pending listesine yazar ve JSON'a kaydeder.",
        ) {
            val profile = importedProfile
            if (profile == null) {
                toast("Önce HCI profili içe aktarın")
                return@scannerActionRow
            }
            runObdTask("Keşif replay") {
                discoveryReplayPositive = 0
                scanner?.replayProfileForDiscovery(
                    profile,
                    eventSink(eventRows, eventAdapter),
                    scannerStateSink(status),
                ) { record ->
                    runOnUiThread {
                        DiscoveredSensorStore.mergeReplayDiscoveries(
                            listOf(record),
                            profile.sourceSession,
                            SystemClock.elapsedRealtime(),
                        )
                        persistDiscoveryStore()
                        discoveryReplayPositive++
                        refreshWorkflow()
                    }
                } ?: toast("Scanner hazır değil")
            }
        })

        guideContent.addView(scannerSectionCard(
            "Faz 2 · Pedal kalibrasyonu ve DID eşleştirme",
            "Park halinde: pedal bırak → ~%30 gaz → tekrar bırak. Skor en yüksek aday gaz pedalı içindir. Motor RPM ve Actual torque için bekleyen listeden manuel seçim yapın.",
            "Kalibrasyon sihirbazını başlat",
            "Her faz ~8 sn · en az birkaç pending DID gerekir (Faz 1 sonrası).",
        ) {
            if (!ensureScannerReady()) return@scannerSectionCard
            startCalibrationWizard(status, eventRows, eventAdapter) { refreshWorkflow() }
        })

        guideContent.addView(scannerActionRow(
            "Sensörü manuel eşleştir",
            "Kalibrasyonun otomatik bulamadığı gaz, RPM veya tork sinyalini bekleyen DID listesinden seçin.",
        ) { showDiscoveryTargetPicker { refreshWorkflow() } })

        guideContent.addView(scannerSectionCard(
            "Faz 3 · Onaylanan sensörleri canlı izle",
            "HCI/Car Scanner kaydıyla eşlenen ve EX30'da Sensor Lab ile canlı doğrulanan gaz PWM, ERAD motor devri ve tork sorgularını OBD üzerinden okur.",
            "Canlı sensör ekranını aç",
            "Yerleşik üç motor sorgusunu OBD polling ile LIVE dener.",
        ) { showMotorSensors() })

        val resultRow = controlRow()
        resultRow.addView(scannerActionRow(
            "Keşif raporunu Download'a yaz",
            "discovery_report.txt + discovered_sensors.json",
            compact = true,
        ) { exportDiscoveryArtifacts() })
        resultRow.addView(scannerActionRow(
            "Olay günlüğünü aç",
            "Sorgu, pozitif yanıt, aday ve NRC kayıtlarını ayrı pencerede gösterir.",
            compact = true,
        ) { showEventLog(eventRows) })
        guideContent.addView(resultRow)

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
        guideContent.addView(controlRowFooter)

        guidePanel.addView(guideContent)
        root.addView(guidePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun obdConnectionStatusView(fallback: TextView? = null): TextView =
        fallback ?: obdStatusView ?: TextView(this)

    private fun attemptPreferredObdAutoConnect() {
        if (!pendingLaunchAutoConnect) return
        if (obdConnectionState != ObdConnectionState.DISCONNECTED) {
            pendingLaunchAutoConnect = false
            return
        }
        if (!hasBluetoothPermissions()) return
        pendingLaunchAutoConnect = false
        tryConnectPreferredObd(returnTo = { refreshObdIndicator() }, showListOnFailure = false, quiet = true)
    }

    private fun tryConnectPreferredObd(
        returnTo: () -> Unit,
        showListOnFailure: Boolean,
        statusOverride: TextView? = null,
        quiet: Boolean = false,
    ) {
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            returnTo()
            return
        }
        if (obdConnectionState == ObdConnectionState.CONNECTING) return
        if (!ensureBluetoothPermissions(statusOverride)) {
            if (showListOnFailure) showObdDevices(returnTo)
            return
        }
        cancelPreferredDiscovery()
        val scanner = BluetoothObdDeviceScanner(this)
        val preferred = ObdPreferredDevice.resolve(this, scanner)
        val status = obdConnectionStatusView(statusOverride)
        if (preferred != null) {
            if (!quiet) toast("${ObdPreferredDevice.DISPLAY_NAME} bağlanıyor…")
            connectObdToDevice(preferred, status) {
                cancelPreferredDiscovery()
                if (!quiet) toast("OBD bağlantısı kuruldu · ${preferred.name}")
                returnTo()
            }
            return
        }
        if (!quiet) toast("${ObdPreferredDevice.DISPLAY_NAME} aranıyor…")
        discoverPreferredObd(scanner, status, returnTo, showListOnFailure, quiet)
    }

    private fun discoverPreferredObd(
        scanner: BluetoothObdDeviceScanner,
        status: TextView?,
        returnTo: () -> Unit,
        showListOnFailure: Boolean,
        quiet: Boolean,
    ) {
        var connecting = false
        preferredDiscoveryActive = true
        deviceScanner = scanner
        status?.text = "${ObdPreferredDevice.DISPLAY_NAME} aranıyor…"
        val timeout = Runnable {
            if (!preferredDiscoveryActive || connecting || obdConnectionState == ObdConnectionState.CONNECTED) return@Runnable
            cancelPreferredDiscovery()
            if (showListOnFailure) {
                if (!quiet) toast("${ObdPreferredDevice.DISPLAY_NAME} bulunamadı · cihaz listesi açılıyor")
                showObdDevices(returnTo)
            } else if (!quiet) {
                toast("${ObdPreferredDevice.DISPLAY_NAME} bulunamadı")
            }
        }
        autoConnectHandler.postDelayed(timeout, PREFERRED_DISCOVERY_TIMEOUT_MS)
        try {
            scanner.startDiscovery(
                onFound = { entry ->
                    if (!preferredDiscoveryActive || !ObdPreferredDevice.isPreferred(entry)) return@startDiscovery
                    if (connecting || obdConnectionState != ObdConnectionState.DISCONNECTED) return@startDiscovery
                    connecting = true
                    autoConnectHandler.removeCallbacks(timeout)
                    runOnUiThread {
                        connectObdToDevice(entry, obdConnectionStatusView(status)) {
                            cancelPreferredDiscovery()
                            if (!quiet) toast("OBD bağlantısı kuruldu · ${entry.name}")
                            returnTo()
                        }
                    }
                },
                onFinished = {
                    if (!connecting && preferredDiscoveryActive && obdConnectionState == ObdConnectionState.DISCONNECTED) {
                        autoConnectHandler.postDelayed(timeout, 500L)
                    }
                },
            )
        } catch (e: Exception) {
            cancelPreferredDiscovery()
            if (showListOnFailure) showObdDevices(returnTo)
            else if (!quiet) toast("Bluetooth taraması başlatılamadı: ${e.message}")
        }
    }

    private fun cancelPreferredDiscovery() {
        if (!preferredDiscoveryActive) return
        preferredDiscoveryActive = false
        autoConnectHandler.removeCallbacksAndMessages(null)
        deviceScanner?.stopDiscovery()
        deviceScanner = null
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
            devices.values.sortedWith(
                compareByDescending<ObdDeviceEntry> { ObdPreferredDevice.isPreferred(it) }
                    .thenByDescending { it.bonded }
                    .thenBy { it.name.lowercase() },
            ).forEach { entry ->
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
                    compareByDescending<ObdDeviceEntry> { ObdPreferredDevice.isPreferred(it) }
                        .thenByDescending { it.bonded }
                        .thenBy { it.name.lowercase() },
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
        cancelPreferredDiscovery()
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
                    ObdPreferredDevice.remember(this@MainActivity, entry)
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
        cancelPreferredDiscovery()
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
        sensorListAdapter = null
        stopStatusRefresh()
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
        motorSensorAdapter = null
    }

    private fun stopActiveScreen(disconnectObd: Boolean = false) {
        stopScreenResources()
        if (disconnectObd) disconnectObdConnection()
    }

    private fun startStatusRefresh() {
        if (sensorListAdapter == null && motorSensorAdapter == null) return
        statusRefreshHandler.removeCallbacks(statusRefresh)
        statusRefreshHandler.postDelayed(statusRefresh, STATUS_REFRESH_MS)
    }

    private fun stopStatusRefresh() {
        statusRefreshHandler.removeCallbacks(statusRefresh)
    }

    private fun openProfile() {
        val files = DownloadStorage.listJsonFiles(this)
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("HCI profili bulunamadı")
                .setMessage(
                    buildString {
                        appendLine("Download klasöründe .json bulunamadı.")
                        appendLine()
                        appendLine("Dosyayı şuraya kopyalayın:")
                        appendLine("Download/EX30SensorLab/ex30-profile.json")
                        appendLine("veya doğrudan Download/ex30-profile.json")
                        appendLine()
                        append(DownloadStorage.scanDiagnostics(this@MainActivity))
                    },
                )
                .setPositiveButton("Manuel seç") { _, _ -> openProfilePicker() }
                .setNegativeButton("Kapat", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Download klasöründen profil seç")
            .setItems(files.map { entry ->
                when (entry) {
                    is DownloadStorage.Entry.Legacy -> entry.name
                    is DownloadStorage.Entry.Media -> entry.name
                }
            }.toTypedArray()) { _, index ->
                loadProfile(files[index])
            }
            .setNeutralButton("Manuel seç") { _, _ -> openProfilePicker() }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun openProfilePicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            REQUEST_PROFILE,
        )
    }

    private fun loadProfile(entry: DownloadStorage.Entry) {
        try {
            val text = DownloadStorage.readText(this, entry)
            importedProfile = ScanProfileParser.parse(text)
            scannerStatus?.text = "Profil hazır: ${importedProfile?.queries?.size ?: 0} salt-okunur sorgu"
            importedProfile?.let(::showProfileReview)
            scannerWorkflowView?.text = buildScannerWorkflowText()
        } catch (e: Exception) {
            scannerStatus?.text = "Profil reddedildi: ${e.message}"
            toast("Profil okunamadı: ${e.message}")
        }
    }

    private fun loadProfileText(text: String) {
        importedProfile = ScanProfileParser.parse(text)
        scannerStatus?.text = "Profil hazır: ${importedProfile?.queries?.size ?: 0} salt-okunur sorgu"
        importedProfile?.let(::showProfileReview)
        scannerWorkflowView?.text = buildScannerWorkflowText()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Profil okunamadı")
            loadProfileText(text)
        } catch (e: Exception) {
            scannerStatus?.text = "Profil reddedildi: ${e.message}"
            toast("Profil okunamadı: ${e.message}")
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

    private fun exportLogsToDownload() {
        val files = listOfNotNull(logger?.csvFile, logger?.jsonlFile).filter { it.isFile }
        if (files.isEmpty()) {
            toast("Aktarılacak kayıt yok")
            return
        }
        ioExecutor.execute {
            try {
                val exported = files.map { DownloadStorage.exportFile(this, it) }
                runOnUiThread {
                    val paths = exported.joinToString("\n") { file ->
                        if (file.absolutePath.isNotBlank()) file.absolutePath else "${DownloadStorage.displayPath()}/${file.name}"
                    }
                    toast("${exported.size} dosya Download'a yazıldı:\n$paths")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download'a aktarılamadı: ${e.message}") }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Car.PERMISSION_SPEED, Car.PERMISSION_ENERGY, Car.PERMISSION_POWERTRAIN,
            Car.PERMISSION_CAR_INFO, Car.PERMISSION_ENERGY_PORTS, Car.PERMISSION_EXTERIOR_ENVIRONMENT,
            "android.car.permission.READ_CAR_PEDALS",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = permissions.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun safetyLabel(): String = if (safetyState.scannerAllowed) "● Tarama güvenlik koşulları hazır" else "Tarama kilitli: ${safetyState.denialReason()}"

    private fun savePowerMultiplier(value: Int) {
        getSharedPreferences("lab", MODE_PRIVATE).edit().putInt("vhal_power_multiplier", value).apply()
    }

    private fun powerMultiplier(): Int = getSharedPreferences("lab", MODE_PRIVATE).getInt("vhal_power_multiplier", 0)

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun baseScreen(title: String, status: String, back: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.lab_background))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(15), dp(24), dp(14))
            setBackgroundColor(color(R.color.lab_surface))
            if (back) addView(actionButton("‹ Ana menü") { showHome() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("VEHICLE DIAGNOSTICS", 11f, color(R.color.lab_accent)).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = 0.12f
                })
                addView(label(title, 29f, color(R.color.lab_text)).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, dp(2))
                })
                addView(label(status, 16f, color(R.color.lab_text_secondary)).apply { tag = "status" })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                tag = "obd_indicator"
                text = obdIndicatorText()
                setTextColor(obdIndicatorColor())
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minWidth = dp(146)
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
        val tileColor = when (number) {
            "2" -> color(R.color.lab_success)
            "3" -> color(R.color.lab_warning)
            "4" -> color(R.color.lab_violet)
            else -> color(R.color.lab_accent)
        }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(color(R.color.lab_surface), color(R.color.lab_border), dp(18))
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(label(number, 21f, color(R.color.lab_background), Gravity.CENTER).apply {
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(tileColor, tileColor, dp(11))
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 19f, color(R.color.lab_text)).apply {
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            })
            addView(label(subtitle, 12f, color(R.color.lab_text_secondary)).apply {
                setPadding(0, dp(2), 0, dp(3))
                maxLines = 1
            })
            addView(label(when (number) {
                "1" -> "VHAL SENSÖRLERİ  →"
                "2" -> "CANLI OKUMA  →"
                "3" -> "YALNIZ ARAÇ SABİTKEN  →"
                else -> "BÜYÜK VE SADE  →"
            }, 10f, tileColor).apply {
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_border), dp(11))
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(10) }
    }

    private fun homeActionButton(
        text: String,
        accent: Int,
        fill: Int = color(R.color.lab_surface_alt),
        endMargin: Int = dp(8),
        click: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        setTextColor(color(R.color.lab_text))
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(fill, accent, dp(12))
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = endMargin }
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

    private fun driveTabButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_accent), dp(10))
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
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
        val store = DiscoveredSensorStore.snapshot()
        val obd = when (obdConnectionState) {
            ObdConnectionState.CONNECTED -> "✓ ${obdDeviceName ?: "OBD"} bağlı"
            ObdConnectionState.CONNECTING -> "◐ bağlanıyor…"
            ObdConnectionState.ERROR -> "✕ bağlantı hatası"
            ObdConnectionState.DISCONNECTED -> "○ henüz bağlı değil"
        }
        val safety = if (safetyState.scannerAllowed) "✓ park freni + sabit + READY/ON"
        else "✕ ${safetyState.denialReason()}"
        val profile = importedProfile?.let { "✓ ${it.queries.size} HCI sorgusu" } ?: "○ HCI profili yok"
        val pending = store.pendingQueries.size
        val confirmed = store.confirmedSensors.joinToString { it.target.labelTr }.ifBlank { "—" }
        return buildString {
            appendLine("Motor keşif kontrol listesi")
            appendLine("Faz 1 · OBD: $obd · Güvenlik: $safety")
            appendLine("Faz 1 · Profil: $profile · Replay pozitif: $discoveryReplayPositive · Bekleyen: $pending")
            appendLine("Faz 2 · Onaylı: $confirmed")
            appendLine("Faz 3 · Motor sensörleri ekranında LIVE")
            appendLine()
            append("Kalıcı dosya: Download/EX30SensorLab/${DiscoveredSensorStore.FILE_NAME}")
        }
    }

    private fun loadDiscoveryStore() {
        try {
            DownloadStorage.readNamedText(this, DiscoveredSensorStore.FILE_NAME)?.let { DiscoveredSensorStore.load(it) }
        } catch (_: Exception) {
        }
    }

    private fun persistDiscoveryStore() {
        val json = DiscoveredSensorStore.toJson()
        ioExecutor.execute {
            try {
                DownloadStorage.writeText(this, DiscoveredSensorStore.FILE_NAME, json)
            } catch (e: Exception) {
                runOnUiThread { toast("Keşif dosyası yazılamadı: ${e.message}") }
            }
        }
    }

    private fun exportDiscoveryArtifacts() {
        val report = DiscoveredSensorStore.reportText()
        val json = DiscoveredSensorStore.toJson()
        ioExecutor.execute {
            try {
                val reportFile = DownloadStorage.writeText(this, "discovery_report.txt", report)
                val jsonFile = DownloadStorage.writeText(this, DiscoveredSensorStore.FILE_NAME, json)
                runOnUiThread {
                    toast(
                        "Keşif dosyaları yazıldı:\n${reportFile.absolutePath}\n${jsonFile.absolutePath}",
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Export başarısız: ${e.message}") }
            }
        }
    }

    private fun motorScreenStatusText(state: DiscoveryStoreState = DiscoveredSensorStore.snapshot()): String {
        return "Gaz PWM · ERAD devri · tork: EX30'da canlı doğrulandı · Manuel keşif: ${state.confirmedSensors.size}"
    }

    private fun builtInMotorSignal(target: DiscoveryTarget) = ObdCatalog.motorSignals.first { definition ->
        definition.key == when (target) {
            DiscoveryTarget.THROTTLE -> "pedal_pwm"
            DiscoveryTarget.MOTOR_RPM -> "erad_motor_speed"
            DiscoveryTarget.ACTUAL_TORQUE -> "erad_actual_torque"
        }
    }

    private fun seedMotorSensorPlaceholders(adapter: SensorListAdapter) {
        DiscoveryTarget.entries.forEach { target ->
            val builtIn = builtInMotorSignal(target)
            adapter.update(
                SensorSample(
                    SensorDefinition(builtIn.key, builtIn.name, SensorSource.OBD, "22${builtIn.did} @ ${builtIn.ecu?.name ?: "ELM"}", builtIn.unit, builtIn.targetHz),
                    rawValue = "—",
                    displayValue = "Bekleniyor…",
                    monotonicTimestampMs = SystemClock.elapsedRealtime(),
                    status = SampleStatus.WAITING,
                    detail = "OBD okuma başlatın",
                ),
            )
        }
    }

    private fun buildConfirmedConfig(
        target: DiscoveryTarget,
        record: DiscoveredQueryRecord,
        decodeType: DiscoveryDecodeType,
    ): DiscoveredSensorConfig = DiscoveredSensorConfig(
        target = target,
        key = "discovered_${target.id}",
        name = target.labelTr,
        service = record.service,
        did = record.did,
        ecu = record.ecu,
        decodeType = decodeType,
        unit = target.unitHint,
        targetHz = when (target) {
            DiscoveryTarget.THROTTLE -> 5f
            DiscoveryTarget.MOTOR_RPM -> 10f
            DiscoveryTarget.ACTUAL_TORQUE -> 2f
        },
        confirmedAtMs = SystemClock.elapsedRealtime(),
    )

    private fun confirmDiscoveredSensor(
        target: DiscoveryTarget,
        record: DiscoveredQueryRecord,
        decodeType: DiscoveryDecodeType,
        onUpdated: () -> Unit = {},
    ) {
        DiscoveredSensorStore.confirmSensor(buildConfirmedConfig(target, record, decodeType), SystemClock.elapsedRealtime())
        persistDiscoveryStore()
        toast("${target.labelTr} onaylandı · ${record.service}${record.did}")
        onUpdated()
    }

    private fun showPendingTargetDialog(target: DiscoveryTarget, onUpdated: () -> Unit = {}) {
        val pending = DiscoveredSensorStore.snapshot().pendingQueries
        if (pending.isEmpty()) {
            toast("Bekleyen DID yok — önce Faz 1 keşif replay çalıştırın")
            return
        }
        val labels = pending.map { record ->
            "${record.service}${record.did} · ${record.ecu?.name ?: "MODE01"} · ${record.lastRawResponse.take(24)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${target.labelTr} DID seç")
            .setItems(labels) { _, index ->
                val record = pending[index]
                val decode = when (target) {
                    DiscoveryTarget.THROTTLE -> DiscoveryDecodeType.U16_DIV100
                    DiscoveryTarget.MOTOR_RPM -> DiscoveryDecodeType.U16
                    DiscoveryTarget.ACTUAL_TORQUE -> DiscoveryDecodeType.S16
                }
                confirmDiscoveredSensor(target, record, decode, onUpdated)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showDiscoveryTargetPicker(onUpdated: () -> Unit = {}) {
        val targets = DiscoveryTarget.entries
        AlertDialog.Builder(this)
            .setTitle("Eşleştirilecek sensör")
            .setItems(targets.map { it.labelTr }.toTypedArray()) { _, index ->
                showPendingTargetDialog(targets[index], onUpdated)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showEventLog(rows: List<String>) {
        showScrollableHelpDialog(
            "Olay günlüğü",
            if (rows.isEmpty()) "Henüz olay kaydı yok." else rows.joinToString("\n\n"),
        )
    }

    private fun startCalibrationWizard(
        status: TextView,
        eventRows: MutableList<String>,
        eventAdapter: ArrayAdapter<String>,
        onUpdated: () -> Unit,
    ) {
        val pending = DiscoveredSensorStore.snapshot().pendingQueries
        if (pending.isEmpty()) {
            toast("Önce Faz 1 keşif replay çalıştırın")
            return
        }
        pauseObdPollingForScanner()
        val phaseLabel = TextView(this).apply {
            setTextColor(color(R.color.lab_text))
            textSize = 16f
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Faz 2 · Pedal kalibrasyonu")
            .setView(phaseLabel)
            .setCancelable(false)
            .create()
        progressDialog.show()
        eventRows.add(0, "▶ Kalibrasyon başlatıldı")
        eventAdapter.notifyDataSetChanged()
        scanner?.runCalibration(
            pending = pending,
            phaseSeconds = 8,
            onPhase = { phase, hint ->
                runOnUiThread {
                    phaseLabel.text = when (phase) {
                        CalibrationPhase.REST -> "1/3 · Pedalı bırakın\n$hint"
                        CalibrationPhase.PEDAL -> "2/3 · ~%30 gaz verin\n$hint"
                        CalibrationPhase.REST_AGAIN -> "3/3 · Pedalı bırakın\n$hint"
                    }
                }
            },
            onSample = {},
            onState = { runOnUiThread { status.text = it } },
            onFinished = { samples ->
                runOnUiThread {
                    progressDialog.dismiss()
                    resumeObdPollingAfterScanner()
                    val ranked = CalibrationScorer.rankCandidates(pending, samples)
                    showCalibrationResults(ranked, onUpdated)
                    eventRows.add(0, "■ Kalibrasyon bitti · ${ranked.size} aday")
                    eventAdapter.notifyDataSetChanged()
                }
            },
        ) ?: run {
            progressDialog.dismiss()
            toast("Scanner hazır değil")
        }
    }

    private fun showCalibrationResults(candidates: List<CalibrationCandidate>, onUpdated: () -> Unit) {
        if (candidates.isEmpty()) {
            showScrollableHelpDialog(
                "Kalibrasyon sonucu",
                "Pedal yanıtı veren aday bulunamadı.\n\nBekleyen listeden manuel seçim yapın veya HCI profilini yenileyip Faz 1'i tekrarlayın.",
            )
            return
        }
        val lines = candidates.take(12).mapIndexed { index, candidate ->
            "${index + 1}. ${candidate.record.service}${candidate.record.did} @ ${candidate.record.ecu?.name ?: "ELM"}\n   ${candidate.summary}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Gaz pedalı adayları (en yüksek skor üstte)")
            .setItems(lines) { _, index ->
                val candidate = candidates[index]
                confirmDiscoveredSensor(
                    DiscoveryTarget.THROTTLE,
                    candidate.record,
                    candidate.suggestedDecode,
                    onUpdated,
                )
            }
            .setNeutralButton("Manuel seç") { _, _ -> showPendingTargetDialog(DiscoveryTarget.THROTTLE, onUpdated) }
            .setNegativeButton("Kapat", null)
            .show()
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
2) HCI kaydını bağlantıdan ÖNCE açtıysanız Bluetooth'u bir kez kapatıp açın; sonra adaptörü yalnız Car Scanner'a bağlayın. Car Scanner zaten bağlıysa Bluetooth'a dokunmayın.
3) Car Scanner'da [VCU] Accelerator pedal PWM signal, [IEM] ERAD Motor Speed ve [IEM] ERAD Actual Torque göstergelerini açın.
4) 60–120 saniye kayıt alın: pedal bırak → hafif gaz → orta gaz → pedal bırak. Bu üç sinyalin canlı değiştiğini kontrol edin.
5) Honor tablette tam dosya bugreport ZIP'e girmese bile /data/log/bt/ altında kalabilir. Oturumdan sonra tableti USB ile Mac'e bağlayıp kontrol edin:
   adb shell ls -lah /data/log/bt
6) btsnoop_hci_*.log dosyasının tam adını kullanıp Mac'e alın; ardından profil üretin:
   adb pull /data/log/bt/btsnoop_hci_YYYYMMDD_HHMMSS.log ex30-full-hci.log
   python3 tools/extract_hci_profile.py ex30-full-hci.log ex30-profile.json
7) Ayrı tam dosya yoksa Car Scanner/Bluetooth açıkken adb bugreport alın; ZIP içinde tam btsnoop_hci.log yoksa kırpılmış Bluetooth özetinden replay yapmayın.
8) Geçerli ex30-profile.json dosyasını USB bellek ile EX30 Download/EX30SensorLab klasörüne kopyalayın → Sensör Keşfi → HCI profili içe aktar → Keşif replay (Faz 1 · kaydet). Aynı adaptöre iki uygulama aynı anda bağlanmasın.

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
        private const val STATUS_REFRESH_MS = 1_000L
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_PROFILE = 1002
        private const val SWIPE_MIN_VELOCITY = 250f
        private const val PREFERRED_DISCOVERY_TIMEOUT_MS = 10_000L
        private val SWIPE_PAGES = listOf(Page.AAOS, Page.OBD, Page.SCANNER, Page.DRIVE)
    }
}
