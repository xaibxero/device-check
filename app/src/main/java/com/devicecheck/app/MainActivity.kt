package com.devicecheck.app

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.devicecheck.app.audit.*
import com.devicecheck.app.nativebridge.NativeProbeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GnssConstellationAuditor.startHardwareGps(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DeviceCheckAppRoot()
            }
        }
    }
}

private val BgDark = Color(0xFF090D16)
private val CardSurface = Color(0xFF111726)
private val BorderSubtle = Color(0xFF1E293B)
private val AccentBlue = Color(0xFF38BDF8)
private val AccentGreen = Color(0xFF10B981)
private val AccentPurple = Color(0xFF818CF8)
private val TextMuted = Color(0xFF94A3B8)
private val TextPrimary = Color(0xFFF8FAFC)

enum class AuditTab(val title: String) {
    DASHBOARD("Live"),
    SILICON("Silicon & DRM"),
    OPTICS_DISPLAY("Optics & Panel"),
    NETWORK_RADIO("Network & Radio"),
    SYSTEM_IDENTITY("System & Profile")
}

@Composable
fun DeviceCheckAppRoot() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(AuditTab.DASHBOARD) }
    var permissionsGranted by remember { mutableStateOf(false) }

    // Static Audit States
    var nonRootReport by remember { mutableStateOf<NonRootTrackerReport?>(null) }
    var identityReport by remember { mutableStateOf<IdentityAuditReport?>(null) }
    var networkReport by remember { mutableStateOf<NonRootNetworkReport?>(null) }
    var cellular by remember { mutableStateOf<CellularTelemetry?>(null) }
    var gnss by remember { mutableStateOf<GnssTelemetry?>(null) }
    var hardwareExt by remember { mutableStateOf<HardwareExtensionsReport?>(null) }
    var nativeAntiTamper by remember { mutableStateOf("Auditing...") }

    // Live Dynamic Telemetry States
    var liveUptimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var ramUsedMb by remember { mutableLongStateOf(0L) }
    var ramTotalMb by remember { mutableLongStateOf(1L) }
    var liveBatteryMv by remember { mutableIntStateOf(0) }
    var liveBatteryTemp by remember { mutableFloatStateOf(0f) }
    var liveBatteryCurrentUa by remember { mutableIntStateOf(0) }
    var accelX by remember { mutableFloatStateOf(0f) }
    var accelY by remember { mutableFloatStateOf(0f) }
    var accelZ by remember { mutableFloatStateOf(9.8f) }

    // 1. Event-Driven Battery Receiver
    DisposableEffect(context) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent != null) {
                    liveBatteryMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    liveBatteryTemp = rawTemp / 10.0f
                    liveBatteryCurrentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    // 2. Live Accelerometer IMU Listener
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.size >= 3) {
                    accelX = event.values[0]
                    accelY = event.values[1]
                    accelZ = event.values[2]
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }

    // 3. Live 1000ms Polling Loop (RAM & Hardware Clock)
    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        while (isActive) {
            liveUptimeMs = SystemClock.elapsedRealtime()
            am?.getMemoryInfo(memInfo)
            ramTotalMb = memInfo.totalMem / (1024 * 1024)
            ramUsedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
            delay(1000)
        }
    }

    fun refreshTelemetry() {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    nonRootReport = NonRootTrackerAuditor.audit(context)
                    identityReport = IdentityAuditor.audit(context)
                    networkReport = NonRootNetworkAuditor.audit(context)
                    cellular = CellularRadioAuditor.audit(context)
                    gnss = GnssConstellationAuditor.audit(context)
                    hardwareExt = HardwareExtensionsAuditor.audit(context)
                    nativeAntiTamper = NativeProbeCore.auditAntiTamper()
                }
            } catch (_: Throwable) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        refreshTelemetry()
    }

    LaunchedEffect(Unit) {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA
        )
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) permissionLauncher.launch(requiredPermissions)
        else permissionsGranted = true
        refreshTelemetry()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // TOP WORKSTATION HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DeviceCheck",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} // API ${Build.VERSION.SDK_INT}",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val jsonString = generateSnapshotJson(
                                nonRootReport = nonRootReport,
                                identityReport = identityReport,
                                networkReport = networkReport,
                                cellular = cellular,
                                gnss = gnss,
                                hardwareExt = hardwareExt,
                                nativeAntiTamper = nativeAntiTamper
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, jsonString)
                                putExtra(Intent.EXTRA_SUBJECT, "DeviceCheck Hardware Snapshot")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export Hardware Snapshot JSON"))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardSurface,
                            contentColor = AccentGreen
                        ),
                        border = BorderStroke(1.dp, BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Export", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { refreshTelemetry() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardSurface,
                            contentColor = AccentBlue
                        ),
                        border = BorderStroke(1.dp, BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Re-Audit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // REFINED TAB SELECTOR
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color.Transparent,
                contentColor = AccentBlue,
                edgePadding = 0.dp,
                divider = {},
                indicator = {}
            ) {
                AuditTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AccentBlue.copy(alpha = 0.15f) else CardSurface,
                        border = BorderStroke(1.dp, if (isSelected) AccentBlue.copy(alpha = 0.6f) else BorderSubtle),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { selectedTab = tab }
                    ) {
                        Text(
                            text = tab.title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AccentBlue else TextMuted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // TAB CONTENT CONTAINER
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    AuditTab.DASHBOARD -> {
                        val ramFraction = if (ramTotalMb > 0) ramUsedMb.toFloat() / ramTotalMb.toFloat() else 0f
                        val animatedRam by animateFloatAsState(targetValue = ramFraction, animationSpec = tween(500), label = "ram")

                        CleanCard(title = "LIVE SYSTEM MEMORY", badge = "DYNAMIC") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Physical RAM Load", fontSize = 11.sp, color = TextMuted)
                                Text(
                                    text = "$ramUsedMb MB / $ramTotalMb MB (${(ramFraction * 100).toInt()}%)",
                                    fontSize = 11.sp,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { animatedRam },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (ramFraction > 0.85f) AccentPurple else AccentBlue,
                                trackColor = CardSurface
                            )
                        }

                        CleanCard(title = "LIVE BATTERY FUEL-GAUGE", badge = "EVENT-DRIVEN") {
                            MetricRow("Terminal Voltage", "$liveBatteryMv mV")
                            MetricRow("Cell Temperature", "$liveBatteryTemp °C")
                            MetricRow("Instantaneous Draw", if (liveBatteryCurrentUa != 0) "${liveBatteryCurrentUa / 1000} mA (${liveBatteryCurrentUa} µA)" else "Standard Idle Draw")
                            nonRootReport?.let { nr ->
                                MetricRow("Battery Health", nr.batteryHealth)
                                MetricRow("Chemistry", nr.batteryTechnology)
                            }
                        }

                        CleanCard(title = "LIVE 3-AXIS MEMS MOTION VECTOR", badge = "HARDWARE STREAM") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AxisMeter(label = "X-AXIS", value = accelX)
                                AxisMeter(label = "Y-AXIS", value = accelY)
                                AxisMeter(label = "Z-AXIS", value = accelZ)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Values stream live from the physical accelerometer. Tap any row to copy.",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }

                        CleanCard(title = "LIVE HARDWARE MONOTONIC CLOCK", badge = "BOOTTIME") {
                            MetricRow("System Uptime", formatUptime(liveUptimeMs))
                            MetricRow("Kernel Boottime Ticks", "${liveUptimeMs / 1000} seconds since cold boot")
                        }
                    }

                    AuditTab.SILICON -> {
                        hardwareExt?.let { ext ->
                            CleanCard(title = "SYSTEM ON CHIP (SOC) HARDWARE", badge = "API 31+") {
                                MetricRow("Declared SoC Manufacturer", ext.socManufacturer)
                                MetricRow("Declared SoC Model / Chip", ext.socModel)
                                MetricRow("Physical CPU Cores", "${Runtime.getRuntime().availableProcessors()} Cores Online")
                            }
                        }

                        nonRootReport?.let { nr ->
                            CleanCard(title = "GPU ARCHITECTURE & DRIVERS", badge = "OPENGL ES") {
                                MetricRow("GPU Renderer", nr.gpu.renderer)
                                MetricRow("GPU Vendor", nr.gpu.vendor)
                                MetricRow("OpenGL Driver Version", nr.gpu.openGlVersion)
                                MetricRow("GL Extensions SHA-256", "${nr.gpu.extensionsHash} (${nr.gpu.extensionCount} extensions)")
                            }

                            CleanCard(title = "WIDEVINE HARDWARE DRM", badge = "TRUSTZONE") {
                                MetricRow("Security Level", nr.widevine.securityLevel)
                                MetricRow("Motherboard System ID", nr.widevine.systemId)
                                MetricRow("DRM Provider", nr.widevine.vendor)
                                MetricRow("Max Hardware HDCP", nr.widevine.maxHdcpLevel)
                            }

                            CleanCard(title = "MEDIACODEC HARDWARE PIPELINE", badge = "MEDIA DSP") {
                                MetricRow("Total Codecs Registered", "${nr.codecCount} codecs")
                                MetricRow("Hardware Decoders", nr.hardwareDecoders.joinToString(", "))
                            }
                        }
                    }

                    AuditTab.OPTICS_DISPLAY -> {
                        nonRootReport?.let { nr ->
                            CleanCard(title = "CAMERA SILICON & OPTICAL MATRIX", badge = "OPTICS") {
                                MetricRow("Rear Sensor Geometry", nr.optics.rearOptics)
                                MetricRow("Front Sensor Geometry", nr.optics.frontOptics)
                                MetricRow("Physical Lenses Present", "${nr.optics.totalPhysicalSensors} physical modules")
                            }

                            CleanCard(title = "DISPLAY PANEL & TIMING", badge = "PANEL") {
                                MetricRow("Physical Resolution", nr.displayMetrics)
                                MetricRow("Supported Refresh Rates", nr.supportedRefreshRates)
                                MetricRow("Color & Dynamic Range", "HDR: ${nr.isHdrSupported} • WideColor: ${nr.isWideColorGamut}")
                            }

                            CleanCard(title = "AUDIO DAC & TOPOLOGY", badge = "AUDIO") {
                                MetricRow("Native Output Sample Rate", nr.audioOutputSampleRate)
                                MetricRow("Hardware Buffer Sizing", nr.audioBufferSize)
                                hardwareExt?.let { ext ->
                                    MetricRow("Audio Sinks (Outputs)", ext.audioOutputTopology.joinToString(" • ").ifBlank { "Default Speaker" })
                                    MetricRow("Audio Sources (Inputs)", ext.audioInputTopology.joinToString(" • ").ifBlank { "Built-in Mic" })
                                }
                            }
                        }

                        hardwareExt?.let { ext ->
                            CleanCard(title = "INPUT CONTROLLER HARDWARE ROSTER", badge = "PHYSICAL HID") {
                                MetricRow("Input Controllers Detected", ext.inputDevices.joinToString("\n").ifBlank { "Standard Touchscreen" })
                            }
                        }
                    }

                    AuditTab.NETWORK_RADIO -> {
                        hardwareExt?.let { ext ->
                            CleanCard(title = "WI-FI PHYSICAL RADIO & GENERATION", badge = "802.11 PHY") {
                                MetricRow("Wi-Fi Standard", ext.wifiStandard)
                                MetricRow("Carrier Frequency Band", ext.wifiFrequencyMhz)
                                MetricRow("Physical Link Speed", ext.wifiLinkSpeed)
                            }
                        }

                        networkReport?.let { net ->
                            CleanCard(title = "NETWORK INTERFACE & ROUTING", badge = "NET STACK") {
                                MetricRow("Primary Interface", net.activeInterface)
                                MetricRow("Bound IPv4 Address", net.localIpAddress)
                                MetricRow("Default Gateway", net.defaultGateway)
                                MetricRow("Interface MTU", net.interfaceMtu)
                                MetricRow("Virtual Tunnel (VPN)", if (net.isVpnDetected) "Virtual adapter active" else "Direct physical route")
                            }
                        }

                        cellular?.let { cell ->
                            CleanCard(title = "CELLULAR BASEBAND & TELEPHONY", badge = cell.dataNetworkType) {
                                MetricRow("Radio Firmware", cell.basebandRadio)
                                MetricRow("SIM Operator", "${cell.simOperatorName} [${cell.simCountryIso}] (${cell.simOperator})")
                                MetricRow("Registered Network", "${cell.networkOperatorName} [${cell.networkCountryIso}]")
                                MetricRow("Cell Tower ID (CID)", "${cell.cellTowerId} (TAC: ${cell.trackingAreaCode} | PCI: ${cell.physicalCellId})")
                                MetricRow("Signal Strength", cell.radioSignalDbm)
                            }
                        }

                        gnss?.let { g ->
                            CleanCard(title = "GNSS SATELLITES & POSITIONING", badge = if (g.isSystemLocationEnabled) "ACTIVE" else "OFF") {
                                MetricRow("System Location Status", if (g.isSystemLocationEnabled) "Enabled in settings" else "Disabled in settings")
                                MetricRow("Provider Mode", g.provider)
                                MetricRow("Coordinates", "Lat: ${"%.5f".format(g.latitude)}, Lng: ${"%.5f".format(g.longitude)} (±${g.accuracyMeters}m)")
                                MetricRow("Satellites (Fix / View)", "${g.satellitesUsedInFix} used / ${g.satellitesInView} visible")
                                MetricRow("Constellations", if (g.constellationsActive.isEmpty()) "Searching for satellite locks..." else g.constellationsActive.joinToString(" • "))
                                MetricRow("Carrier Noise (C/N0)", "${"%.1f".format(g.averageSnrNoiseDbHz)} dB-Hz")
                            }
                        }
                    }

                    AuditTab.SYSTEM_IDENTITY -> {
                        CleanCard(title = "FRAMEWORK VS HARDWARE CROSS-EXAMINATION", badge = "COMPARISON") {
                            MetricRow("Declared User-Agent Model", nonRootReport?.defaultUserAgent?.take(75) ?: "Reading...")
                            MetricRow("Physical GPU Renderer", nonRootReport?.gpu?.renderer ?: "Reading...")
                            MetricRow("Declared SoC Model", hardwareExt?.socModel ?: "Reading...")
                            MetricRow("Widevine Motherboard ID", nonRootReport?.widevine?.systemId ?: "Reading...")
                            MetricRow("Physical Display Steps", nonRootReport?.supportedRefreshRates ?: "Reading...")
                            MetricRow("Baseband Transceiver", cellular?.basebandRadio ?: "Reading...")
                        }

                        identityReport?.let { id ->
                            CleanCard(title = "PERSISTENT IDENTIFIERS", badge = "IDENTITY") {
                                MetricRow("OS Android ID (SSAID)", id.ssaid)
                                MetricRow("Google Services (GSF) ID", "${id.gsfId} [${id.gsfStatus}]")
                            }
                        }

                        nonRootReport?.let { nr ->
                            CleanCard(title = "SYSTEM EXTENSIONS & MANIFEST", badge = "ROSTER") {
                                MetricRow("PackageManager Features", "${nr.systemFeaturesCount} features (SHA: ${nr.systemFeaturesHash})")
                                MetricRow("Total Hardware Sensors", "${nr.sensorCount} sensors (SHA: ${nr.sensorFingerprintHash})")
                                MetricRow("Installed Keyboards", nr.installedKeyboards.joinToString("\n"))
                                MetricRow("System Fonts Fingerprint", "${nr.systemFontCount} fonts (SHA: ${nr.fontRosterHash})")
                            }
                        }

                        CleanCard(title = "PROCESS MEMORY MAP & INTEGRITY", badge = "PROCFS") {
                            nativeAntiTamper.lines().forEach { line ->
                                val parts = line.split("=", limit = 2)
                                if (parts.size == 2) MetricRow(parts[0], parts[1])
                                else Text(text = line, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun CleanCard(
    title: String,
    badge: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    letterSpacing = 0.5.sp
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Text(
                        text = badge,
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label, value)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun AxisMeter(label: String, value: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(BgDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = label, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "%.2f".format(value),
            fontSize = 12.sp,
            color = AccentGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatUptime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    val hrs = (ms / (1000 * 60 * 60))
    return "%02d:%02d:%02d".format(hrs, min, sec)
}

private fun generateSnapshotJson(
    nonRootReport: NonRootTrackerReport?,
    identityReport: IdentityAuditReport?,
    networkReport: NonRootNetworkReport?,
    cellular: CellularTelemetry?,
    gnss: GnssTelemetry?,
    hardwareExt: HardwareExtensionsReport?,
    nativeAntiTamper: String
): String {
    val root = JSONObject()

    val meta = JSONObject().apply {
        put("tool", "DeviceCheck Forensic Audit")
        put("version", "1.0.0")
        put("timestamp_epoch", System.currentTimeMillis())
        put("timestamp_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US).format(Date()))
        put("uptime_ms", SystemClock.elapsedRealtime())
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_api", Build.VERSION.SDK_INT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("claimed_fingerprint", Build.FINGERPRINT)
    }
    root.put("metadata", meta)

    val silicon = JSONObject().apply {
        put("declared_soc_manufacturer", hardwareExt?.socManufacturer ?: "N/A")
        put("declared_soc_model", hardwareExt?.socModel ?: "N/A")
        put("egl_renderer", nonRootReport?.gpu?.renderer ?: "N/A")
        put("egl_vendor", nonRootReport?.gpu?.vendor ?: "N/A")
        put("opengl_driver", nonRootReport?.gpu?.openGlVersion ?: "N/A")
        put("gl_extensions_hash", nonRootReport?.gpu?.extensionsHash ?: "N/A")
        put("total_hardware_sensors", nonRootReport?.sensorCount ?: 0)
        put("sensor_roster_hash", nonRootReport?.sensorFingerprintHash ?: "N/A")
        put("registered_codecs_count", nonRootReport?.codecCount ?: 0)
        put("hardware_decoders", JSONArray(nonRootReport?.hardwareDecoders ?: emptyList<String>()))
    }
    root.put("silicon", silicon)

    val crypto = JSONObject().apply {
        put("widevine_security_level", nonRootReport?.widevine?.securityLevel ?: "N/A")
        put("widevine_system_id", nonRootReport?.widevine?.systemId ?: "N/A")
        put("widevine_vendor", nonRootReport?.widevine?.vendor ?: "N/A")
        put("widevine_hdcp_level", nonRootReport?.widevine?.maxHdcpLevel ?: "N/A")
        put("ssaid", identityReport?.ssaid ?: "N/A")
        put("gsf_id", identityReport?.gsfId ?: "N/A")
        put("gsf_status", identityReport?.gsfStatus ?: "N/A")
    }
    root.put("cryptography_and_identifiers", crypto)

    val opticsDisplay = JSONObject().apply {
        put("rear_camera_optics", nonRootReport?.optics?.rearOptics ?: "N/A")
        put("front_camera_optics", nonRootReport?.optics?.frontOptics ?: "N/A")
        put("display_resolution", nonRootReport?.displayMetrics ?: "N/A")
        put("supported_refresh_rate_steps", nonRootReport?.supportedRefreshRates ?: "N/A")
        put("is_hdr_supported", nonRootReport?.isHdrSupported ?: false)
        put("is_wide_color_gamut", nonRootReport?.isWideColorGamut ?: false)
        put("audio_dac_sample_rate", nonRootReport?.audioOutputSampleRate ?: "N/A")
    }
    root.put("optics_and_display", opticsDisplay)

    val network = JSONObject().apply {
        put("primary_interface", networkReport?.activeInterface ?: "N/A")
        put("local_ipv4", networkReport?.localIpAddress ?: "N/A")
        put("default_gateway", networkReport?.defaultGateway ?: "N/A")
        put("interface_mtu", networkReport?.interfaceMtu ?: "N/A")
        put("is_virtual_tunnel_vpn", networkReport?.isVpnDetected ?: false)
        put("wifi_standard", hardwareExt?.wifiStandard ?: "N/A")
        put("wifi_frequency", hardwareExt?.wifiFrequencyMhz ?: "N/A")
        put("wifi_link_speed", hardwareExt?.wifiLinkSpeed ?: "N/A")
        put("dns_servers", nonRootReport?.dhcpDnsServers ?: "N/A")
    }
    root.put("network", network)

    val radio = JSONObject().apply {
        put("baseband_firmware", cellular?.basebandRadio ?: "N/A")
        put("sim_operator", cellular?.simOperator ?: "N/A")
        put("sim_operator_name", cellular?.simOperatorName ?: "N/A")
        put("sim_country_iso", cellular?.simCountryIso ?: "N/A")
        put("network_operator_name", cellular?.networkOperatorName ?: "N/A")
        put("cell_tower_id", cellular?.cellTowerId ?: "N/A")
        put("signal_dbm", cellular?.radioSignalDbm ?: "N/A")
        put("network_type", cellular?.dataNetworkType ?: "N/A")
    }
    root.put("telephony_radio", radio)

    val gnssObj = JSONObject().apply {
        put("system_location_enabled", gnss?.isSystemLocationEnabled ?: false)
        put("provider", gnss?.provider ?: "N/A")
        put("is_mock_flagged", gnss?.isMockFlagged ?: false)
        put("satellites_used_in_fix", gnss?.satellitesUsedInFix ?: 0)
        put("satellites_in_view", gnss?.satellitesInView ?: 0)
        put("active_constellations", JSONArray(gnss?.constellationsActive ?: emptyList<String>()))
        put("avg_carrier_noise_dbhz", gnss?.averageSnrNoiseDbHz ?: 0f)
    }
    root.put("gnss_satellites", gnssObj)

    val peripherals = JSONObject().apply {
        put("input_controllers", JSONArray(hardwareExt?.inputDevices ?: emptyList<String>()))
        put("audio_outputs", JSONArray(hardwareExt?.audioOutputTopology ?: emptyList<String>()))
        put("audio_inputs", JSONArray(hardwareExt?.audioInputTopology ?: emptyList<String>()))
    }
    root.put("hardware_peripherals", peripherals)

    root.put("anti_tamper_procfs", nativeAntiTamper)

    return root.toString(2)
}
