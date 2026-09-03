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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import com.kadireren.ex30sensorlab.ui.SensorListAdapter
import com.kadireren.ex30sensorlab.vhal.AndroidVhalReader
import com.kadireren.ex30sensorlab.vhal.SafetyState
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class ObdConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

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
    private var obdStatusView: TextView? = null
    private var obdConnectionState = ObdConnectionState.DISCONNECTED
    private var obdDeviceName: String? = null
    private var obdDeviceAddress: String? = null
    private var obdAdapterId: String? = null
    private var obdErrorMessage: String? = null
    private var obdDeviceReturnAction: (() -> Unit)? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.lab_background)
        window.navigationBarColor = color(R.color.lab_background)
        requestRequiredPermissions()
        connectCar()
        showHome()
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
        val root = baseScreen("EX30 Sensor Lab", if (carPropertyManager != null) "● Araç bağlantısı hazır" else "○ Araç bağlantısı bekleniyor", false)
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(20))
        }
        cards.addView(menuCard("1", "AAOS Verileri", "14 doğrulanmış VHAL sensörü") { showAaos() }, weighted())
        cards.addView(menuCard("2", "OBD Verileri", "Bluetooth OBD adaptörü ile okuma") { showObd() }, weighted(dp(18)))
        cards.addView(menuCard("3", "OBD Scanner", "Salt-okunur aday ve DID taraması") { showScanner() }, weighted(dp(18)))
        root.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val obdRow = controlRow()
        obdRow.addView(actionButton("OBD cihazlarını tara") { showObdDevices { showHome() } })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            obdRow.addView(actionButton("OBD bağlantısını kes") { disconnectObdConnection(); showHome() })
        }
        root.addView(obdRow)
        root.addView(label("Tarama yalnız araç sabitken çalışır", 16f, color(R.color.lab_text_secondary), Gravity.CENTER).apply {
            setPadding(0, dp(16), 0, dp(20))
        })
        setContentView(root)
    }

    private fun showAaos() {
        stopActiveScreen()
        val manager = carPropertyManager
        val root = baseScreen("AAOS Verileri", if (manager != null) "● VHAL hazır" else "○ Car API bekleniyor", true)
        val calibration = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            val direction = when (powerMultiplier()) { 1 -> "hızlanmada +"; -1 -> "hızlanmada −"; else -> "doğrulanmadı" }
            addView(label("Güç yönü: $direction", 15f, color(R.color.lab_text_secondary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    }

    private fun showObd() {
        stopActiveScreen()
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
            startObdPolling(status, adapter)
        })
        controls.addView(actionButton("Durdur") { obdPolling?.stop(); status.text = "OBD okuma durdu" })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            controls.addView(actionButton("Bağlantıyı kes") { disconnectObdConnection(); status.text = obdScreenStatusText() })
        }
        controls.addView(actionButton("Kayıtları paylaş") { shareLogs() })
        root.addView(controls)
        root.addView(label("Bir sensöre dokun: odak modu · tekrar dokun: genel tarama", 13f, color(R.color.lab_text_secondary)).apply { setPadding(dp(18), 0, 0, dp(6)) })
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
    }

    private fun showScanner() {
        stopActiveScreen()
        val root = baseScreen("OBD Scanner", obdScreenStatusText(), true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        scannerStatus = status
        val eventRows = mutableListOf<String>()
        val eventAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, eventRows)

        val connectRow = controlRow()
        connectRow.addView(actionButton("OBD cihazlarını tara") { showObdDevices { showScanner() } })
        connectRow.addView(actionButton("Bağlan") {
            if (obdConnectionState != ObdConnectionState.CONNECTED) {
                toast("Önce bir OBD cihazı seçin")
                showObdDevices { showScanner() }
                return@actionButton
            }
            logger?.close()
            logger = SessionLogger(this).also { it.start("scanner") }
            attachScanner(status)
        })
        connectRow.addView(actionButton("Durdur") { scanner?.stop() })
        if (obdConnectionState == ObdConnectionState.CONNECTED) {
            connectRow.addView(actionButton("Bağlantıyı kes") { disconnectObdConnection(); status.text = obdScreenStatusText() })
        }
        connectRow.addView(actionButton("Kayıtları paylaş") { shareLogs() })
        root.addView(connectRow)

        val actionRow = controlRow()
        actionRow.addView(actionButton("Adayları izle") { scanner?.watchCandidates(eventSink(eventRows, eventAdapter), stateSink(status)) ?: toast("Önce bağlanın") })
        actionRow.addView(actionButton("Kısa ECU taraması") { scanner?.scanKnownEcuCandidates(eventSink(eventRows, eventAdapter), stateSink(status)) ?: toast("Önce bağlanın") })
        actionRow.addView(actionButton("HCI profili içe aktar") { openProfile() })
        actionRow.addView(actionButton("Profili oynat") {
            val profile = importedProfile
            if (profile == null) toast("Önce profil içe aktarın")
            else scanner?.replayProfile(profile, eventSink(eventRows, eventAdapter), stateSink(status)) ?: toast("Önce bağlanın")
        })
        root.addView(actionRow)

        val rangeRow = controlRow()
        val ecuSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, EcuContexts.known.map { it.name })
        }
        val start = hexInput("4800")
        val end = hexInput("48FF")
        rangeRow.addView(ecuSpinner, LinearLayout.LayoutParams(0, dp(54), 1f))
        rangeRow.addView(start, LinearLayout.LayoutParams(0, dp(54), 1f))
        rangeRow.addView(end, LinearLayout.LayoutParams(0, dp(54), 1f))
        rangeRow.addView(actionButton("DID aralığını tara") {
            try {
                val from = start.text.toString().toInt(16)
                val to = end.text.toString().toInt(16)
                val ecu = EcuContexts.known[ecuSpinner.selectedItemPosition]
                scanner?.scanDidPage(ecu, from, to, eventSink(eventRows, eventAdapter), stateSink(status)) ?: toast("Önce bağlanın")
            } catch (e: Exception) {
                toast(e.message ?: "Geçersiz DID aralığı")
            }
        })
        root.addView(rangeRow)
        root.addView(ListView(this).apply {
            adapter = eventAdapter
            dividerHeight = dp(2)
            setPadding(dp(18), 0, dp(18), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val manager = carPropertyManager
        if (manager != null) {
            vhalReader = AndroidVhalReader(manager) { powerMultiplier() }.also { reader ->
                reader.start(onSample = {}, onSafety = {
                    safetyState = it
                    runOnUiThread {
                        if (scanner == null) {
                            status.text = if (obdConnectionState == ObdConnectionState.CONNECTED) safetyLabel() else obdScreenStatusText()
                        }
                    }
                })
            }
        } else {
            status.text = "Tarama kilitli: Car API yok"
        }
    }

    private fun showObdDevices(returnTo: () -> Unit) {
        stopScreenResources()
        obdDeviceReturnAction = returnTo
        val scanner = BluetoothObdDeviceScanner(this).also { deviceScanner = it }
        val root = baseScreen("OBD Cihazları", "Bluetooth cihazlarını seçin", true)
        val status = root.getChildAt(0).findViewWithTag<TextView>("status")
        val devices = linkedMapOf<String, ObdDeviceEntry>()
        val rows = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)

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
        root.addView(label("Bağlanmak için bir cihaza dokunun", 13f, color(R.color.lab_text_secondary)).apply {
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
                val id = protocol.connect(entry.address)
                elmProtocol = protocol
                obdAdapterId = id.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "ELM327" }
                runOnUiThread {
                    setObdConnectionState(ObdConnectionState.CONNECTED)
                    status.text = "● Bağlı: ${entry.name} · $obdAdapterId"
                    onConnected()
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

    private fun startObdPolling(status: TextView, adapter: SensorListAdapter) {
        val protocol = elmProtocol
        if (protocol == null || obdConnectionState != ObdConnectionState.CONNECTED) {
            status.text = "OBD bağlı değil"
            return
        }
        obdPolling?.close()
        obdPolling = ObdPollingController(protocol).also { polling ->
            polling.start(onSample = { sample ->
                logger?.append(sample)
                runOnUiThread { adapter.update(sample) }
            }, onState = { text -> runOnUiThread { status.text = text } })
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
        }
    }

    private fun obdIndicatorText(): String = when (obdConnectionState) {
        ObdConnectionState.DISCONNECTED -> "○ OBD"
        ObdConnectionState.CONNECTING -> "◐ OBD"
        ObdConnectionState.CONNECTED -> "● OBD"
        ObdConnectionState.ERROR -> "✕ OBD"
    }

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
        obdPolling?.close()
        obdPolling = null
        scanner?.close()
        scanner = null
        logger?.close()
        logger = null
        scannerStatus = null
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
            setPadding(dp(20), dp(14), dp(20), dp(14))
            if (back) addView(actionButton("‹ Ana menü") { showHome() })
            addView(label(title, 28f, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(obdIndicatorText(), 14f, obdIndicatorColor()).apply {
                tag = "obd_indicator"
                setPadding(dp(12), 0, dp(12), 0)
                obdStatusView = this
            })
            addView(label(status, 15f, color(R.color.lab_success)).apply { tag = "status" })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
    }

    private fun menuCard(number: String, title: String, subtitle: String, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(26), dp(18), dp(26))
        background = rounded(color(R.color.lab_surface), color(R.color.lab_surface_alt), dp(16))
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(label(number, 52f, color(R.color.lab_accent), Gravity.CENTER).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(label(title, 24f, Color.WHITE, Gravity.CENTER).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(22), 0, dp(8)) })
        addView(label(subtitle, 15f, color(R.color.lab_text_secondary), Gravity.CENTER))
    }

    private fun controlRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
    }

    private fun actionButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        background = rounded(color(R.color.lab_surface_alt), color(R.color.lab_accent), dp(10))
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(10) }
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

    private fun messageSample(message: String, status: SampleStatus) = SensorSample(
        SensorDefinition("message", message, SensorSource.VHAL, "—", "", 0f), "—", "—",
        android.os.SystemClock.elapsedRealtime(), status = status,
    )

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
    }
}
