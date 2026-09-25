package com.devicecheck.app.audit

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.view.InputDevice

data class HardwareExtensionsReport(
    val socManufacturer: String,
    val socModel: String,
    val wifiStandard: String,
    val wifiFrequencyMhz: String,
    val wifiLinkSpeed: String,
    val inputDevices: List<String>,
    val audioOutputTopology: List<String>,
    val audioInputTopology: List<String>
)

object HardwareExtensionsAuditor {

    fun audit(context: Context): HardwareExtensionsReport {
        // 1. Official Chipset SoC Properties (API 31+)
        val socVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else "Qualcomm / Legacy"

        val socChip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else "Snapdragon / Legacy"

        // 2. Wi-Fi Physical Radio Metrics (Using integer constants to ensure SDK compatibility)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo: WifiInfo? = try { wm?.connectionInfo } catch (_: Throwable) { null }

        val standardStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiInfo != null) {
            when (wifiInfo.wifiStandard) {
                6 -> "Wi-Fi 6 / 6E (802.11ax)"
                5 -> "Wi-Fi 5 (802.11ac)"
                4 -> "Wi-Fi 4 (802.11n)"
                7, 8 -> "Wi-Fi 7 (802.11be)"
                1 -> "Legacy (802.11a/b/g)"
                else -> "Standard #${wifiInfo.wifiStandard}"
            }
        } else "802.11 Multi-Band"

        val freqMhz = wifiInfo?.frequency ?: 0
        val bandStr = when {
            freqMhz in 2400..2499 -> "2.4 GHz ($freqMhz MHz)"
            freqMhz in 4900..5900 -> "5.0 GHz ($freqMhz MHz)"
            freqMhz > 5925 -> "6.0 GHz ($freqMhz MHz - Wi-Fi 6E/7)"
            else -> if (freqMhz > 0) "$freqMhz MHz" else "Radio Standby"
        }

        val linkSpeedStr = if (wifiInfo != null && wifiInfo.linkSpeed > 0) {
            "${wifiInfo.linkSpeed} ${WifiInfo.LINK_SPEED_UNITS}"
        } else "Standby"

        // 3. Input Hardware Controller Roster
        val inputDeviceNames = mutableListOf<String>()
        try {
            val deviceIds = InputDevice.getDeviceIds()
            for (id in deviceIds) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (!dev.isVirtual) {
                    inputDeviceNames.add("${dev.name} [Vendor: 0x${"%04x".format(dev.vendorId)} Product: 0x${"%04x".format(dev.productId)}]")
                }
            }
        } catch (_: Throwable) {}

        // 4. Audio Topology
        val audioOutputs = mutableListOf<String>()
        val audioInputs = mutableListOf<String>()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_ALL)
                for (dev in devices) {
                    val name = dev.productName.toString()
                    val typeStr = when (dev.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Stereo Speaker"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece Receiver"
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone Array"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Sink"
                        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Interface"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                        else -> "Type 0x${dev.type}"
                    }
                    if (dev.isSink) audioOutputs.add("$typeStr ($name)")
                    if (dev.isSource) audioInputs.add("$typeStr ($name)")
                }
            }
        } catch (_: Throwable) {}

        return HardwareExtensionsReport(
            socManufacturer = socVendor,
            socModel = socChip,
            wifiStandard = standardStr,
            wifiFrequencyMhz = bandStr,
            wifiLinkSpeed = linkSpeedStr,
            inputDevices = inputDeviceNames.distinct(),
            audioOutputTopology = audioOutputs.distinct(),
            audioInputTopology = audioInputs.distinct()
        )
    }
}
