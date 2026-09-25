package com.devicecheck.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@Composable
fun DeviceCheckAppRoot() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var permissionsGranted by remember { mutableStateOf(false) }

    // State Collectors
    var nonRootReport by remember { mutableStateOf<NonRootTrackerReport?>(null) }
    var identityReport by remember { mutableStateOf<IdentityAuditReport?>(null) }
    var cellular by remember { mutableStateOf<CellularTelemetry?>(null) }
    var gnss by remember { mutableStateOf<GnssTelemetry?>(null) }
    var nativeSerials by remember { mutableStateOf("Auditing...") }
    var nativeNetwork by remember { mutableStateOf("Auditing...") }
    var nativeBattery by remember { mutableStateOf("Auditing...") }
    var nativeAntiTamper by remember { mutableStateOf("Auditing...") }
    var nativeClocks by remember { mutableStateOf("Auditing...") }
    var rootGroundTruth by remember { mutableStateOf<RootHardwareGroundTruth?>(null) }

    fun refreshTelemetry() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                // Non-Root Zero-Permission Commercial Tracker Silhouette
                nonRootReport = NonRootTrackerAuditor.audit(context)
                identityReport = IdentityAuditor.audit(context)
                cellular = CellularRadioAuditor.audit(context)
                gnss = GnssConstellationAuditor.audit(context)

                // Native C++ POSIX Layer
                nativeSerials = NativeProbeCore.auditHardwareSerials()
                nativeNetwork = NativeProbeCore.auditKernelNetwork()
                nativeBattery = NativeProbeCore.auditBatteryRegisters()
                nativeAntiTamper = NativeProbeCore.auditAntiTamper()
                nativeClocks = NativeProbeCore.auditClocks()

                // Optional Root Ground Truth (if available)
                rootGroundTruth = RootProbeEngine.probeGroundTruth()
            }
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
        if (!allGranted) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            permissionsGranted = true
        }
        refreshTelemetry()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF030712)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // App Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DEVICE-CHECK",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "TRACKER PERSPECTIVE RADAR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF88),
                        letterSpacing = 0.8.sp
                    )
                }

                Button(
                    onClick = { refreshTelemetry() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Re-Audit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Non-Root Tracker Banner
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0x330B1120),
                border = BorderStroke(1.dp, Color(0x3300FF88)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "UNPRIVILEGED TRACKER PERSPECTIVE ACTIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF00FF88)
                    )
                    Text(
                        text = "Displaying exact hardware vectors accessible to standard social apps without root.",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. NON-ROOT: Silicon Sensors & Codec Silhouette
            nonRootReport?.let { nr ->
                AuditCard(title = "SENSOR SILICON & MEDIACODEC SILHOUETTE", badge = "ZERO PERM") {
                    MetricRow("Total Physical Sensors", "${nr.sensorCount} Hardware Sensors")
                    MetricRow("Sensor Roster SHA-256", nr.sensorFingerprintHash)
                    MetricRow("Primary Sensor Modules", nr.primarySensors.joinToString("\n"))
                    Spacer(modifier = Modifier.height(4.dp))
                    MetricRow("Registered MediaCodecs", "${nr.codecCount} Codecs Registered")
                    MetricRow("Hardware QTI/SoC Decoders", nr.hardwareDecoders.joinToString(", "))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. NON-ROOT: Display Modes & Native Audio DAC
                AuditCard(title = "DISPLAY REFRESH STEPS & AUDIO DAC", badge = "ZERO PERM") {
                    MetricRow("Physical Viewport", nr.displayMetrics)
                    MetricRow("Supported Refresh Rates", nr.supportedRefreshRates)
                    MetricRow("Native Audio DAC Clock", nr.audioOutputSampleRate)
                    MetricRow("Audio Buffer Sizing", nr.audioBufferSize)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. NON-ROOT: Battery Hardware Telemetry
                AuditCard(title = "BATTERY HARDWARE TELEMETRY", badge = "STICKY INTENT") {
                    MetricRow("Terminal Voltage", nr.batteryVoltageMv)
                    MetricRow("Battery Temperature", nr.batteryTemperatureC)
                    MetricRow("Battery Health Status", nr.batteryHealth)
                    MetricRow("Battery Chemistry", nr.batteryTechnology)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4. NON-ROOT: System Extensions (Keyboards, Fonts, TTS)
                AuditCard(title = "INSTALLED EXTENSIONS & SYSTEM FONTS", badge = "ZERO PERM") {
                    MetricRow("Installed Input Keyboards", nr.installedKeyboards.joinToString("\n"))
                    MetricRow("Text-to-Speech (TTS)", nr.ttsEngines.joinToString(", ").ifBlank { "None" })
                    MetricRow("System Fonts Directory", "${nr.systemFontCount} font files (Hash: ${nr.fontRosterHash})")
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. NON-ROOT: Storage & Memory Heap Geometry
                AuditCard(title = "EXACT MEMORY & NAND FLASH GEOMETRY", badge = "ZERO PERM") {
                    MetricRow("Internal Flash Geometry", nr.exactNandFlashBytes)
                    MetricRow("Physical RAM Size", nr.physicalRamBytes)
                    MetricRow("ART Dalvik Heap Allocation", nr.dalvikHeapLimitMb)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 6. NON-ROOT: Network, WebSettings & Locale
                AuditCard(title = "NETWORK CAPABILITIES & USER AGENT", badge = "ACCESS_NET") {
                    MetricRow("Active Transports", nr.networkTransports)
                    MetricRow("DNS Servers (LinkProps)", nr.dhcpDnsServers)
                    MetricRow("Bandwidth Estimation", nr.linkBandwidthEstimate)
                    MetricRow("Timezone & DST", nr.timezoneDst)
                    MetricRow("Primary System Locale", nr.localeOrder)
                    MetricRow("Default WebKit User-Agent", nr.defaultUserAgent)
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 7. Persistent Device Identifiers (GSF & SSAID)
            identityReport?.let { id ->
                AuditCard(title = "PERSISTENT DEVICE IDENTIFIERS", badge = "IDENTITY") {
                    MetricRow("OS Android ID (SSAID)", id.ssaid)
                    MetricRow("Google Services (GSF) ID", "${id.gsfId} [${id.gsfStatus}]")
                    rootGroundTruth?.let { root ->
                        if (root.isRootAvailable) {
                            MetricRow("Root GSF ID (Query)", root.rootGsfId)
                            MetricRow("Root Settings SSAID (XML)", root.rootSsaid)
                            MetricRow("Root Hardware Serial", root.rootSerialNo)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 8. GNSS Physical Constellations
            gnss?.let { g ->
                AuditCard(
                    title = "GNSS SATELLITE CONSTELLATIONS & NOISE",
                    badge = if (g.isMockFlagged) "MOCK DETECTED" else "PHYSICAL GNSS",
                    badgeColor = if (g.isMockFlagged) Color(0xFFF43F5E) else Color(0xFF00FF88)
                ) {
                    MetricRow("Location Provider", "${g.provider} (Mock Flag: ${if (g.isMockFlagged) "TRUE" else "FALSE"})")
                    MetricRow("Coordinates", "Lat: ${"%.5f".format(g.latitude)}, Lng: ${"%.5f".format(g.longitude)} (±${g.accuracyMeters}m)")
                    MetricRow("Altitude", "${"%.2f".format(g.altitudeMeters)}m")
                    MetricRow("Satellites (Fix / View)", "${g.satellitesUsedInFix} used / ${g.satellitesInView} in view")
                    MetricRow("Active Constellations", if (g.constellationsActive.isEmpty()) "Acquiring satellite constellation..." else g.constellationsActive.joinToString(" • "))
                    MetricRow("Avg Carrier Noise (C/N0)", "${"%.1f".format(g.averageSnrNoiseDbHz)} dB-Hz")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 9. Telephony & Baseband Status
            cellular?.let { cell ->
                AuditCard(title = "TELEPHONY & BASEBAND IDENTIFIERS", badge = cell.dataNetworkType) {
                    MetricRow("App Sandbox IMEI 1", cell.imei1)
                    MetricRow("App Sandbox IMEI 2", cell.imei2)
                    MetricRow("IMSI (Subscriber ID)", cell.imsi)
                    MetricRow("ICCID (SIM Serial)", cell.iccid)
                    MetricRow("SIM Carrier", "${cell.simOperatorName} [${cell.simCountryIso}] (MCC+MNC: ${cell.simOperator})")
                    MetricRow("Network Operator", "${cell.networkOperatorName} [${cell.networkCountryIso}] (${cell.networkOperator})")
                    MetricRow("Live Cell Tower (CID)", "${cell.cellTowerId} (TAC: ${cell.trackingAreaCode} | PCI: ${cell.physicalCellId})")
                    MetricRow("Radio Signal Strength", cell.radioSignalDbm)
                    MetricRow("Baseband Radio Firmware", cell.basebandRadio)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 10. Native C++ POSIX Kernel Route Audit
            AuditCard(title = "RAW POSIX KERNEL NETWORK ROUTES", badge = "C++20 NDK") {
                nativeNetwork.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 11. Anti-Tamper Memory Map Scan
            AuditCard(title = "ANTI-TAMPER & MEMORY MAP SCAN", badge = "PROCFS") {
                nativeAntiTamper.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                    else Text(text = line, fontSize = 10.sp, color = Color(0xFFF43F5E), fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AuditCard(
    title: String,
    badge: String,
    badgeColor: Color = Color(0xFF00FF88),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0x330B1120),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
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
                    color = Color(0xFF00E5FF),
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.5.dp)) {
        Text(text = label, fontSize = 10.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
        Text(text = value, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
    }
}
