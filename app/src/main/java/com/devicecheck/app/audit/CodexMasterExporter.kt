package com.devicecheck.app.audit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CodexMasterExporter {

    fun exportSnapshot(
        context: Context,
        nonRootReport: NonRootTrackerReport?,
        identityReport: IdentityAuditReport?,
        networkReport: NonRootNetworkReport?,
        cellular: CellularTelemetry?,
        gnss: GnssTelemetry?,
        hardwareExt: HardwareExtensionsReport?,
        nativeAntiTamper: String
    ): File? {
        val root = JSONObject()

        // 1. Audit Metadata
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

        // 2. Chipset & Silicon
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

        // 3. Cryptographic DRM & Identifiers
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

        // 4. Optical Matrix & Physical Display
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

        // 5. Network, Wi-Fi & Sockets
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

        // 6. Cellular Radio & Baseband
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

        // 7. GNSS Satellites
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

        // 8. Hardware Input & Audio Devices
        val peripherals = JSONObject().apply {
            put("input_controllers", JSONArray(hardwareExt?.inputDevices ?: emptyList<String>()))
            put("audio_outputs", JSONArray(hardwareExt?.audioOutputTopology ?: emptyList<String>()))
            put("audio_inputs", JSONArray(hardwareExt?.audioInputTopology ?: emptyList<String>()))
        }
        root.put("hardware_peripherals", peripherals)

        // 9. Anti-Tamper & Memory Map
        root.put("anti_tamper_procfs", nativeAntiTamper)

        // Write to File
        return try {
            val reportsDir = File(context.cacheDir, "reports")
            if (!reportsDir.exists()) reportsDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(reportsDir, "DeviceCheck_Snapshot_${timestamp}.json")
            file.writeText(root.toString(2))
            file
        } catch (_: Throwable) {
            null
        }
    }

    fun shareSnapshotFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "com.devicecheck.app.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DeviceCheck Hardware Snapshot")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Export Hardware Snapshot JSON"))
    }
}
