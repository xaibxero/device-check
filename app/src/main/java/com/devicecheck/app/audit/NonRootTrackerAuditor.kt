package com.devicecheck.app.audit

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

data class NonRootTrackerReport(
    val gpu: EglGpuTelemetry,
    val widevine: WidevineTelemetry,
    val optics: CameraOpticsTelemetry,
    val systemFeaturesCount: Int,
    val systemFeaturesHash: String,
    val sensorCount: Int,
    val sensorFingerprintHash: String,
    val primarySensors: List<String>,
    val codecCount: Int,
    val hardwareDecoders: List<String>,
    val displayMetrics: String,
    val supportedRefreshRates: String,
    val isHdrSupported: Boolean,
    val isWideColorGamut: Boolean,
    val audioOutputSampleRate: String,
    val audioBufferSize: String,
    val batteryVoltageMv: String,
    val batteryTemperatureC: String,
    val batteryHealth: String,
    val batteryTechnology: String,
    val installedKeyboards: List<String>,
    val ttsEngines: List<String>,
    val systemFontCount: Int,
    val fontRosterHash: String,
    val exactNandFlashBytes: String,
    val physicalRamBytes: String,
    val dalvikHeapLimitMb: String,
    val defaultUserAgent: String,
    val networkTransports: String,
    val dhcpDnsServers: String,
    val linkBandwidthEstimate: String,
    val localeOrder: String,
    val timezoneDst: String
)

object NonRootTrackerAuditor {

    fun audit(context: Context): NonRootTrackerReport {
        // 1. Headless EGL GPU context (Zero Permissions)
        val gpuTelemetry = EglGpuAuditor.audit()

        // 2. Widevine DRM Hardware Anchor (Zero Permissions)
        val widevineTelemetry = WidevineDrmAuditor.audit()

        // 3. Camera2 Optical Physics (Zero Permissions)
        val cameraOptics = CameraOpticsAuditor.audit(context)

        // 4. PackageManager System Available Features Hash (Zero Permissions)
        val pmFeatures = context.packageManager.systemAvailableFeatures
            .mapNotNull { it.name }
            .sorted()
        val pmFeaturesHash = sha256(pmFeatures.joinToString(","))

        // 5. Sensor Silicon Roster (Zero Permissions)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
        val sensorNames = allSensors.map { "${it.name} [${it.vendor} v${it.version}]" }
        val sensorRosterHash = sha256(sensorNames.joinToString(";"))
        val primarySensorsList = allSensors.take(4).map { "${it.name} (${it.vendor})" }

        // 6. MediaCodec Hardware Silhouette (Zero Permissions)
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val allCodecs = codecList.codecInfos
        val hwDecoders = allCodecs.filter { !it.isEncoder && it.name.startsWith("c2.") }
            .map { it.name }

        // 7. Display Metrics, Refresh Rates, HDR (Zero Permissions)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay
        }
        val modes = display?.supportedModes ?: emptyArray()
        val refreshRates = modes.map { "${it.refreshRate.toInt()}Hz (${it.physicalWidth}x${it.physicalHeight})" }.distinct()
        val displayStr = "${display?.width ?: 0}x${display?.height ?: 0} @ ${display?.refreshRate?.toInt() ?: 0}Hz"
        val isHdr = display?.isHdr ?: false
        val isWide = display?.isWideColorGamut ?: false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "48000"
        val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "192"

        // 8. Battery Hardware Telemetry (Sticky Intent - Zero Permissions)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val healthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val tech = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            else -> "UNKNOWN"
        }

        // 9. System Extensions: Keyboards & TTS (Zero Permissions)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboards = imm.enabledInputMethodList.map { it.serviceInfo.packageName }

        val tts = TextToSpeech(context, null)
        val ttsList = tts.engines.map { it.name }
        tts.shutdown()

        // 10. System Fonts Directory Fingerprint (Zero Permissions)
        val fontsDir = File("/system/fonts")
        val fontFiles = fontsDir.list() ?: emptyArray()
        val fontHash = sha256(fontFiles.sorted().joinToString(","))

        // 11. Storage & Memory Geometry (Zero Permissions)
        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalBytes = statFs.totalBytes
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        // 12. Default User Agent & System Locales (Zero Permissions)
        val ua = try { WebSettings.getDefaultUserAgent(context) } catch (_: Exception) { "Standard Android Dalvik/2.1.0" }
        val tz = TimeZone.getDefault()
        val dst = if (tz.useDaylightTime()) "DST Supported (In Effect: ${tz.inDaylightTime(java.util.Date())})" else "No DST"

        // 13. Network Unprivileged Capabilities
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)
        val linkProps = cm.getLinkProperties(activeNet)

        val transports = mutableListOf<String>()
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) transports.add("WiFi")
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) transports.add("Cellular")
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) transports.add("VPN Active")

        val dns = linkProps?.dnsServers?.map { it.hostAddress ?: "" }?.filter { it.isNotBlank() } ?: emptyList()
        val downBandwidth = caps?.linkDownstreamBandwidthKbps ?: 0
        val upBandwidth = caps?.linkUpstreamBandwidthKbps ?: 0

        return NonRootTrackerReport(
            gpu = gpuTelemetry,
            widevine = widevineTelemetry,
            optics = cameraOptics,
            systemFeaturesCount = pmFeatures.size,
            systemFeaturesHash = pmFeaturesHash.take(16).uppercase(),
            sensorCount = allSensors.size,
            sensorFingerprintHash = sensorRosterHash.take(16).uppercase(),
            primarySensors = primarySensorsList,
            codecCount = allCodecs.size,
            hardwareDecoders = hwDecoders.take(4),
            displayMetrics = displayStr,
            supportedRefreshRates = refreshRates.joinToString(" • "),
            isHdrSupported = isHdr,
            isWideColorGamut = isWide,
            audioOutputSampleRate = "$sampleRate Hz",
            audioBufferSize = "$framesPerBuffer frames per buffer",
            batteryVoltageMv = "$voltageMv mV",
            batteryTemperatureC = "${tempRaw / 10.0} °C",
            batteryHealth = healthStr,
            batteryTechnology = tech,
            installedKeyboards = keyboards,
            ttsEngines = ttsList,
            systemFontCount = fontFiles.size,
            fontRosterHash = fontHash.take(16).uppercase(),
            exactNandFlashBytes = "$totalBytes bytes (${totalBytes / (1024 * 1024 * 1024)} GB)",
            physicalRamBytes = "${memInfo.totalMem} bytes (${"%.2f".format(memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))} GB)",
            dalvikHeapLimitMb = "${am.memoryClass} MB (Large: ${am.largeMemoryClass} MB)",
            defaultUserAgent = ua,
            networkTransports = transports.joinToString(" • ").ifBlank { "Disconnected" },
            dhcpDnsServers = dns.joinToString(" • ").ifBlank { "Default Gateway" },
            linkBandwidthEstimate = "Down: ${downBandwidth / 1000} Mbps | Up: ${upBandwidth / 1000} Mbps",
            localeOrder = Locale.getDefault().toLanguageTag(),
            timezoneDst = "${tz.id} ($dst)"
        )
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
