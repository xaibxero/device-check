package com.devicecheck.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.devicecheck.app.audit.CellularRadioAuditor
import com.devicecheck.app.audit.CellularTelemetry
import com.devicecheck.app.audit.GnssConstellationAuditor
import com.devicecheck.app.audit.GnssTelemetry
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
                DeviceCheckMasterUI()
            }
        }
    }
}

@Composable
fun DeviceCheckMasterUI() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var nativeSerials by remember { mutableStateOf("Auditing...") }
    var nativeNetwork by remember { mutableStateOf("Auditing...") }
    var nativeBattery by remember { mutableStateOf("Auditing...") }
    var nativeAntiTamper by remember { mutableStateOf("Auditing...") }
    var nativeClocks by remember { mutableStateOf("Auditing...") }
    var cellular by remember { mutableStateOf<CellularTelemetry?>(null) }
    var gnss by remember { mutableStateOf<GnssTelemetry?>(null) }
    var ssaid by remember { mutableStateOf("Reading...") }

    fun refreshAll() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                nativeSerials = NativeProbeCore.auditHardwareSerials()
                nativeNetwork = NativeProbeCore.auditKernelNetwork()
                nativeBattery = NativeProbeCore.auditBatteryRegisters()
                nativeAntiTamper = NativeProbeCore.auditAntiTamper()
                nativeClocks = NativeProbeCore.auditClocks()
                cellular = CellularRadioAuditor.audit(context)
                gnss = GnssConstellationAuditor.audit(context)
                ssaid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "N/A"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
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
                        text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} // Android ${Build.VERSION.RELEASE}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF88),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = { refreshAll() },
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

            // 1. Telephony & Cellular Radio Card
            cellular?.let { cell ->
                AuditCard(title = "CELLULAR RADIO & TELEPHONY IDENTIFIERS", badge = cell.dataNetworkType) {
                    MetricRow("IMEI 1", cell.imei1)
                    MetricRow("IMEI 2", cell.imei2)
                    MetricRow("MEID", cell.meid)
                    MetricRow("IMSI (Subscriber)", cell.imsi)
                    MetricRow("ICCID (SIM Serial)", cell.iccid)
                    MetricRow("SIM Carrier", "${cell.simOperatorName} [${cell.simCountryIso}] (MCC+MNC: ${cell.simOperator})")
                    MetricRow("Network Operator", "${cell.networkOperatorName} [${cell.networkCountryIso}] (${cell.networkOperator})")
                    MetricRow("Cell Tower ID (CID)", "${cell.cellTowerId} (TAC: ${cell.trackingAreaCode} | PCI: ${cell.physicalCellId})")
                    MetricRow("Radio Signal", cell.radioSignalDbm)
                    MetricRow("Baseband Radio", cell.basebandRadio)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Hardware Serials & Silicon Node Card
            AuditCard(title = "RAW SILICON, STORAGE & HARDWARE SERIALS", badge = "POSIX DIRECT") {
                MetricRow("OS Android ID (SSAID)", ssaid)
                nativeSerials.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Raw GNSS & Satellite Constellation Card
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
                    MetricRow("Active Constellations", if (g.constellationsActive.isEmpty()) "Searching for satellites..." else g.constellationsActive.joinToString(" • "))
                    MetricRow("Avg Carrier Noise (C/N0)", "${"%.1f".format(g.averageSnrNoiseDbHz)} dB-Hz")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Kernel Network & Sockets Card
            AuditCard(title = "KERNEL NETWORK ROUTES & ARP TABLE", badge = "SOCKET LAYER") {
                nativeNetwork.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Battery PMIC Micro-Registers Card
            AuditCard(title = "BATTERY FUEL-GAUGE MICRO-REGISTERS", badge = "SYSFS RAW") {
                MetricRow("PMIC Register Feed", nativeBattery)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Anti-Tamper & Memory Map Forensics Card
            AuditCard(title = "ANTI-TAMPER & MEMORY MAP SCAN", badge = "PROCFS") {
                nativeAntiTamper.lines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) MetricRow(parts[0], parts[1])
                    else Text(text = line, fontSize = 10.sp, color = Color(0xFFF43F5E), fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Temporal Clocks & Drift Card
            AuditCard(title = "TEMPORAL CLOCK SYNCHRONIZATION", badge = "POSIX CLOCKS") {
                MetricRow("Uptime Millis", "${SystemClock.elapsedRealtime()} ms")
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
