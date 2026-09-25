package com.devicecheck.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import com.devicecheck.app.audit.CellularRadioAuditor
import com.devicecheck.app.audit.CellularTelemetry
import com.devicecheck.app.audit.GnssConstellationAuditor
import com.devicecheck.app.audit.GnssTelemetry
import com.devicecheck.app.audit.IdentityAuditReport
import com.devicecheck.app.audit.IdentityAuditor
import com.devicecheck.app.audit.RootHardwareGroundTruth
import com.devicecheck.app.audit.RootProbeEngine
import com.devicecheck.app.nativebridge.NativeProbeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GnssConstellationAuditor.registerGnssListener(this)

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

    // Telemetry states
    var nativeSerials by remember { mutableStateOf("Auditing...") }
    var nativeNetwork by remember { mutableStateOf("Auditing...") }
    var nativeBattery by remember { mutableStateOf("Auditing...") }
    var nativeAntiTamper by remember { mutableStateOf("Auditing...") }
    var nativeClocks by remember { mutableStateOf("Auditing...") }
    var cellular by remember { mutableStateOf<CellularTelemetry?>(null) }
    var gnss by remember { mutableStateOf<GnssTelemetry?>(null) }
    var identityReport by remember { mutableStateOf<IdentityAuditReport?>(null) }
    var rootGroundTruth by remember { mutableStateOf<RootHardwareGroundTruth?>(null) }

    fun refreshTelemetry() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                nativeSerials = NativeProbeCore.auditHardwareSerials()
                nativeNetwork = NativeProbeCore.auditKernelNetwork()
                nativeBattery = NativeProbeCore.auditBatteryRegisters()
                nativeAntiTamper = NativeProbeCore.auditAntiTamper()
                nativeClocks = NativeProbeCore.auditClocks()
                cellular = CellularRadioAuditor.audit(context)
                gnss = GnssConstellationAuditor.audit(context)
                identityReport = IdentityAuditor.audit(context)
                rootGroundTruth = RootProbeEngine.probeGroundTruth()
            }
        }
    }

    // Permission dispatcher
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

            // Header
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
                        text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} // API ${Build.VERSION.SDK_INT}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF88),
                        fontFamily = FontFamily.Monospace
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

            // Root & Privilege Status Banner
            rootGroundTruth?.let { root ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0x330B1120),
                    border = BorderStroke(1.dp, if (root.isRootAvailable) Color(0x4400FF88) else Color(0x44F43F5E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (root.isRootAvailable) "ROOT ENGINE: ACTIVE (GROUND TRUTH ON)" else "SANDBOX RESTRICTED MODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = if (root.isRootAvailable) Color(0xFF00FF88) else Color(0xFFF43F5E)
                            )
                            Text(
                                text = "SELinux: ${root.selinuxMode} • Permissions: ${if (permissionsGranted) "Granted" else "Partial"}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Dual-Layer Telephony Matrix
            cellular?.let { cell ->
                AuditCard(title = "TELEPHONY & BASEBAND IDENTIFIERS", badge = cell.dataNetworkType) {
                    MetricRow("App Sandbox IMEI 1", cell.imei1)
                    MetricRow("App Sandbox IMEI 2", cell.imei2)
                    MetricRow("Root Ground Truth IMEI", rootGroundTruth?.rootImei ?: "Awaiting root...")
                    MetricRow("IMSI (Subscriber ID)", cell.imsi)
                    MetricRow("ICCID (SIM Serial)", cell.iccid)
                    MetricRow("SIM Carrier", "${cell.simOperatorName} [${cell.simCountryIso}] (MCC+MNC: ${cell.simOperator})")
                    MetricRow("Network Operator", "${cell.networkOperatorName} [${cell.networkCountryIso}] (${cell.networkOperator})")
                    MetricRow("Live Cell Tower (CID)", "${cell.cellTowerId} (TAC: ${cell.trackingAreaCode} | PCI: ${cell.physicalCellId})")
                    MetricRow("Radio Signal Strength", cell.radioSignalDbm)
                    MetricRow("Baseband Radio Firmware", cell.basebandRadio)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Hardware Serials, GSF & Silicon Storage Card
            AuditCard(title = "RAW SILICON, STORAGE & HARDWARE SERIALS", badge = "CROSS-LAYER") {
                identityReport?.let { id ->
                    MetricRow("OS Android ID (SSAID)", id.ssaid)
                    MetricRow("Google Services (GSF) ID", "${id.gsfId} [${id.gsfStatus}]")
                } ?: run {
                    MetricRow("OS Android ID (SSAID)", "Auditing...")
                    MetricRow("Google Services (GSF) ID", "Auditing...")
                }
                MetricRow("Storage Hardware Serial", rootGroundTruth?.rawStorageSerial ?: "Querying...")
                nativeSerials.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Raw GNSS Satellites
            gnss?.let { g ->
                AuditCard(
                    title = "GNSS SATELLITE CONSTELLATIONS & NOISE",
                    badge = if (g.isMockFlagged) "MOCK FLAGGED" else "PHYSICAL GNSS",
                    badgeColor = if (g.isMockFlagged) Color(0xFFF43F5E) else Color(0xFF00FF88)
                ) {
                    MetricRow("Location Provider", "${g.provider} (Mock Flag: ${if (g.isMockFlagged) "TRUE" else "FALSE"})")
                    MetricRow("Coordinates", "Lat: ${"%.5f".format(g.latitude)}, Lng: ${"%.5f".format(g.longitude)} (±${g.accuracyMeters}m)")
                    MetricRow("Altitude", "${"%.2f".format(g.altitudeMeters)}m")
                    MetricRow("Satellites (Fix / View)", "${g.satellitesUsedInFix} used / ${g.satellitesInView} in view")
                    MetricRow("Active Constellations", if (g.constellationsActive.isEmpty()) "Acquiring satellites (ensure GPS is ON)..." else g.constellationsActive.joinToString(" • "))
                    MetricRow("Avg Carrier Noise (C/N0)", "${"%.1f".format(g.averageSnrNoiseDbHz)} dB-Hz")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Kernel Network & Socket Routing
            AuditCard(title = "KERNEL NETWORK ROUTES & ARP TABLE", badge = "SOCKET LAYER") {
                nativeNetwork.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Fuel-Gauge Micro-Registers
            AuditCard(title = "BATTERY FUEL-GAUGE MICRO-REGISTERS", badge = "PMIC SYSFS") {
                MetricRow("Sandbox sysfs Access", nativeBattery)
                MetricRow("Root PMIC Feed", rootGroundTruth?.rawBatteryUevent ?: "Querying...")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Anti-Tamper & In-Memory Hooks
            AuditCard(title = "ANTI-TAMPER & MEMORY MAP SCAN", badge = "PROCFS") {
                nativeAntiTamper.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                    else Text(text = line, fontSize = 10.sp, color = Color(0xFFF43F5E), fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Temporal Clocks & Drift
            AuditCard(title = "TEMPORAL CLOCK SYNCHRONIZATION", badge = "POSIX CLOCKS") {
                MetricRow("Monotonic Uptime", "${SystemClock.elapsedRealtime()} ms")
                nativeClocks.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
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
