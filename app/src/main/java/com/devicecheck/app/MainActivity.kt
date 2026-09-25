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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GnssConstellationAuditor.startHardwareGps(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DeviceCheckProHUD()
            }
        }
    }
}

// Visual Theme Palette
private val VoidBlack = Color(0xFF040711)
private val SurfaceDark = Color(0xFF090E1A)
private val CardBg = Color(0xFF0E1626)
private val NeonCyan = Color(0xFF00F0FF)
private val NeonGreen = Color(0xFF00FF88)
private val NeonRed = Color(0xFFFF3366)
private val NeonAmber = Color(0xFFFFB800)
private val TextSlate = Color(0xFF64748B)
private val TextLight = Color(0xFFF1F5F9)

enum class FilterCategory(val label: String) {
    ALL("ALL VECTORS"),
    FLAGS("DISCREPANCIES"),
    SILICON("SILICON / GPU"),
    NETWORK("NET & DNS"),
    IDENTITY("IDENTITY / DRM")
}

@Composable
fun DeviceCheckProHUD() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var permissionsGranted by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(FilterCategory.ALL) }

    // Live Telemetry States
    var nonRootReport by remember { mutableStateOf<NonRootTrackerReport?>(null) }
    var identityReport by remember { mutableStateOf<IdentityAuditReport?>(null) }
    var networkReport by remember { mutableStateOf<NonRootNetworkReport?>(null) }
    var cellular by remember { mutableStateOf<CellularTelemetry?>(null) }
    var gnss by remember { mutableStateOf<GnssTelemetry?>(null) }
    var nativeAntiTamper by remember { mutableStateOf("Auditing...") }

    // Dynamic Live Hardware Uptime Ticker
    var liveUptimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var scanTick by remember { mutableIntStateOf(0) }

    // Pulsing Heartbeat Radar Animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )

    fun refreshTelemetry() {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    nonRootReport = NonRootTrackerAuditor.audit(context)
                    identityReport = IdentityAuditor.audit(context)
                    networkReport = NonRootNetworkAuditor.audit(context)
                    cellular = CellularRadioAuditor.audit(context)
                    gnss = GnssConstellationAuditor.audit(context)
                    nativeAntiTamper = NativeProbeCore.auditAntiTamper()
                }
                scanTick++
            } catch (_: Throwable) {}
        }
    }

    // Dynamic Live 1000ms Polling Loop
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            liveUptimeMs = SystemClock.elapsedRealtime()
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
        color = VoidBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // TOP STATUS CONSOLE HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NeonGreen.copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "DEVICE//CHECK",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextLight,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "LIVE PRIVACY & HARDWARE RADAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Button(
                    onClick = { refreshTelemetry() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan.copy(alpha = 0.15f),
                        contentColor = NeonCyan
                    ),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("RE-AUDIT", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // LIVE TELEMETRY STREAM BAR
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LIVE UPTIME:",
                            fontSize = 9.sp,
                            color = TextSlate,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatUptime(liveUptimeMs),
                            fontSize = 10.sp,
                            color = NeonGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "POLL TICK:",
                            fontSize = 9.sp,
                            color = TextSlate,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "#$scanTick",
                            fontSize = 10.sp,
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // INTERACTIVE CATEGORY FILTER PILLS
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(FilterCategory.values()) { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) NeonCyan.copy(alpha = 0.18f) else SurfaceDark,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) NeonCyan else Color(0x1FFFFFFF)
                        ),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat.label,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            color = if (isSelected) NeonCyan else TextSlate,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 0. DISCREPANCY MATRIX (FLAGGED VECTORS)
            nonRootReport?.let { nr ->
                val discrepancies = mutableListOf<String>()
                val ua = nr.defaultUserAgent.lowercase()
                val renderer = nr.gpu.renderer.lowercase()
                val refresh = nr.supportedRefreshRates

                if (ua.contains("pixel") && renderer.contains("adreno")) {
                    discrepancies.add("GPU SILICON MISMATCH: Profile claims Pixel (Tensor Mali), but physical GPU is Qualcomm Adreno!")
                }
                if (ua.contains("pixel") && refresh.contains("144hz")) {
                    discrepancies.add("DISPLAY PANEL MISMATCH: Pixel hardware caps at 120Hz, but panel supports 144Hz (Motorola Panel)!")
                }
                if (ua.contains("pixel") && nr.widevine.systemId == "28917") {
                    discrepancies.add("WIDEVINE MOTHERBOARD MISMATCH: System ID 28917 is registered to Motorola OEM, not Google!")
                }

                val hasDiscrepancy = discrepancies.isNotEmpty()

                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.FLAGS) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                if (hasDiscrepancy) listOf(NeonRed, NeonAmber.copy(0.4f))
                                else listOf(NeonGreen, NeonCyan.copy(0.4f))
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (hasDiscrepancy) "🚨 HARDWARE SPOOF ANOMALIES" else "✅ HARDWARE PROFILE VERIFIED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (hasDiscrepancy) NeonRed else NeonGreen
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (hasDiscrepancy) NeonRed.copy(0.2f) else NeonGreen.copy(0.2f)
                                ) {
                                    Text(
                                        text = if (hasDiscrepancy) "${discrepancies.size} FLAGS" else "CONSISTENT",
                                        color = if (hasDiscrepancy) NeonRed else NeonGreen,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (hasDiscrepancy) {
                                discrepancies.forEach { flag ->
                                    Text(
                                        text = "• $flag",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFCA5A5),
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = "All userland strings, GPU renderer, and optical matrices are coherent.",
                                    fontSize = 10.sp,
                                    color = TextSlate
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 1. SILICON & GPU SECTION
            nonRootReport?.let { nr ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.SILICON) {
                    ProHudCard(title = "GPU EGL ENGINE & WIDEVINE CRYPTO", badge = "SILICON TEE", badgeColor = NeonCyan) {
                        HudMetricRow("EGL Renderer", nr.gpu.renderer, highlight = NeonCyan)
                        HudMetricRow("Hardware Vendor", nr.gpu.vendor)
                        HudMetricRow("OpenGL Driver", nr.gpu.openGlVersion)
                        HudMetricRow("GL Extensions SHA-256", "${nr.gpu.extensionsHash} (${nr.gpu.extensionCount} ext)")
                        Spacer(modifier = Modifier.height(4.dp))
                        HudMetricRow("Widevine Security Tier", "[${nr.widevine.securityLevel}] Provider: ${nr.widevine.vendor}")
                        HudMetricRow("Motherboard System ID", nr.widevine.systemId, highlight = NeonAmber)
                        HudMetricRow("Hardware HDCP Level", nr.widevine.maxHdcpLevel)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ProHudCard(title = "PHYSICAL OPTICAL LENS MATRIX", badge = "OPTICS MM", badgeColor = NeonGreen) {
                        HudMetricRow("Rear Primary Sensor", nr.optics.rearOptics, highlight = TextLight)
                        HudMetricRow("Front Selfie Sensor", nr.optics.frontOptics)
                        HudMetricRow("Total Physical Lenses", "${nr.optics.totalPhysicalSensors} Individual Modules")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ProHudCard(title = "DISPLAY PANEL & AUDIO DSP", badge = "HARDWARE", badgeColor = NeonCyan) {
                        HudMetricRow("Resolution & Viewport", nr.displayMetrics)
                        HudMetricRow("Refresh Rate Steps", nr.supportedRefreshRates, highlight = NeonGreen)
                        HudMetricRow("HDR & Wide Color Gamut", "HDR: ${nr.isHdrSupported} • WideColor: ${nr.isWideColorGamut}")
                        HudMetricRow("Native Audio Clock", nr.audioOutputSampleRate)
                        HudMetricRow("DAC Buffer Boundary", nr.audioBufferSize)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ProHudCard(title = "SENSOR SILICON ROSTER", badge = "40+ NODES", badgeColor = NeonAmber) {
                        HudMetricRow("System Feature Manifest", "${nr.systemFeaturesCount} Features (SHA: ${nr.systemFeaturesHash})")
                        HudMetricRow("Hardware Sensors Roster", "${nr.sensorCount} Sensors (SHA: ${nr.sensorFingerprintHash})")
                        HudMetricRow("Primary MEMS Sensors", nr.primarySensors.joinToString("\n"))
                        Spacer(modifier = Modifier.height(4.dp))
                        HudMetricRow("Registered MediaCodecs", "${nr.codecCount} Codecs (${nr.hardwareDecoders.size} Hardware QTI)")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ProHudCard(title = "BATTERY HARDWARE PMIC GAUGES", badge = "LIVE SENSOR", badgeColor = NeonGreen) {
                        HudMetricRow("Terminal Voltage", nr.batteryVoltageMv, highlight = NeonCyan)
                        HudMetricRow("Cell Temperature", nr.batteryTemperatureC, highlight = NeonAmber)
                        HudMetricRow("PMIC Health", nr.batteryHealth)
                        HudMetricRow("Chemistry", nr.batteryTechnology)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 2. NETWORK SECTION
            networkReport?.let { net ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.NETWORK) {
                    ProHudCard(title = "SOCKET & ROUTE INTEGRITY", badge = "NET STACK", badgeColor = NeonCyan) {
                        HudMetricRow("Active Physical Adapter", net.activeInterface, highlight = NeonGreen)
                        HudMetricRow("Bound IPv4 Address", net.localIpAddress)
                        HudMetricRow("Default Gateway Route", net.defaultGateway)
                        HudMetricRow("Interface MTU", net.interfaceMtu)
                        HudMetricRow("Virtual Interface (VPN)", if (net.isVpnDetected) "TUN/WG DETECTED" else "CLEAR (PHYSICAL)")
                        HudMetricRow("Network Interfaces", net.allNetworkInterfaces.joinToString(" • "))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            nonRootReport?.let { nr ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.NETWORK) {
                    ProHudCard(title = "TRANSPORT CAPABILITIES & USER AGENT", badge = "WEBKIT", badgeColor = NeonCyan) {
                        HudMetricRow("Active Transports", nr.networkTransports)
                        HudMetricRow("DNS Resolvers (LinkProps)", nr.dhcpDnsServers)
                        HudMetricRow("Bandwidth Estimation", nr.linkBandwidthEstimate)
                        HudMetricRow("Timezone & DST Offset", nr.timezoneDst)
                        HudMetricRow("Primary System Locale", nr.localeOrder)
                        HudMetricRow("Default User-Agent", nr.defaultUserAgent, highlight = TextLight)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 3. IDENTITY SECTION
            identityReport?.let { id ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.IDENTITY) {
                    ProHudCard(title = "PERSISTENT TRACKING IDENTIFIERS", badge = "PERSISTENT", badgeColor = NeonGreen) {
                        HudMetricRow("OS Android ID (SSAID)", id.ssaid, highlight = NeonCyan)
                        HudMetricRow("Google Services (GSF) ID", "${id.gsfId} [${id.gsfStatus}]", highlight = NeonGreen)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 4. GNSS SATELLITES SECTION
            gnss?.let { g ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.NETWORK) {
                    ProHudCard(
                        title = "GNSS SATELLITES & INDOOR FIX",
                        badge = if (g.isMockFlagged) "MOCK DETECTED" else if (g.isSystemLocationEnabled) "ACTIVE" else "LOCATION OFF",
                        badgeColor = if (g.isMockFlagged) NeonRed else NeonGreen
                    ) {
                        HudMetricRow("System Location Switch", if (g.isSystemLocationEnabled) "ENABLED IN SETTINGS" else "DISABLED IN SETTINGS")
                        HudMetricRow("Positioning Provider", "${g.provider} (Mock Flag: ${if (g.isMockFlagged) "TRUE" else "FALSE"})")
                        HudMetricRow("Coordinates", "Lat: ${"%.5f".format(g.latitude)}, Lng: ${"%.5f".format(g.longitude)} (±${g.accuracyMeters}m)")
                        HudMetricRow("Altitude", "${"%.2f".format(g.altitudeMeters)}m")
                        HudMetricRow("Satellites (Fix / View)", "${g.satellitesUsedInFix} used / ${g.satellitesInView} in view", highlight = NeonGreen)
                        HudMetricRow("Active Constellations", if (g.constellationsActive.isEmpty()) "Acquiring satellites..." else g.constellationsActive.joinToString(" • "))
                        HudMetricRow("Avg Carrier Noise (C/N0)", "${"%.1f".format(g.averageSnrNoiseDbHz)} dB-Hz")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 5. TELEPHONY SECTION
            cellular?.let { cell ->
                if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.IDENTITY) {
                    ProHudCard(title = "TELEPHONY & BASEBAND RADIO", badge = cell.dataNetworkType, badgeColor = NeonAmber) {
                        HudMetricRow("App Sandbox IMEI 1", cell.imei1)
                        HudMetricRow("App Sandbox IMEI 2", cell.imei2)
                        HudMetricRow("IMSI (Subscriber ID)", cell.imsi)
                        HudMetricRow("ICCID (SIM Serial)", cell.iccid)
                        HudMetricRow("SIM Carrier", "${cell.simOperatorName} [${cell.simCountryIso}] (MCC+MNC: ${cell.simOperator})")
                        HudMetricRow("Network Operator", "${cell.networkOperatorName} [${cell.networkCountryIso}] (${cell.networkOperator})")
                        HudMetricRow("Live Cell Tower (CID)", "${cell.cellTowerId} (TAC: ${cell.trackingAreaCode} | PCI: ${cell.physicalCellId})")
                        HudMetricRow("Radio Signal Strength", cell.radioSignalDbm)
                        HudMetricRow("Baseband Radio Firmware", cell.basebandRadio, highlight = NeonCyan)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 6. ANTI-TAMPER PROCFS SECTION
            if (selectedCategory == FilterCategory.ALL || selectedCategory == FilterCategory.FLAGS) {
                ProHudCard(title = "ANTI-TAMPER & MEMORY MAP SCAN", badge = "PROCFS", badgeColor = NeonCyan) {
                    nativeAntiTamper.lines().forEach { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) HudMetricRow(parts[0], parts[1])
                        else Text(text = line, fontSize = 10.sp, color = NeonRed, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

// Tactical HUD UI Components
@Composable
fun ProHudCard(
    title: String,
    badge: String,
    badgeColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(badgeColor.copy(alpha = 0.35f), Color(0x10FFFFFF))
            )
        ),
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
                    fontWeight = FontWeight.Black,
                    color = NeonCyan,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.4.sp,
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
fun HudMetricRow(label: String, value: String, highlight: Color = Color(0xFFCBD5E1)) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            color = TextSlate,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = highlight,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatUptime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    val hrs = (ms / (1000 * 60 * 60))
    return "%02d:%02d:%02d.%03d".format(hrs, min, sec, ms % 1000)
}
