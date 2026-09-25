package com.devicecheck.app.audit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class RootHardwareGroundTruth(
    val isRootAvailable: Boolean,
    val rootImei: String,
    val rawBatteryUevent: String,
    val rawStorageSerial: String,
    val selinuxMode: String,
    val rootGsfId: String = "N/A",
    val rootSsaid: String = "N/A",
    val rootSerialNo: String = "N/A"
)

object RootProbeEngine {

    suspend fun executeSuCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            val exitCode = process.waitFor()
            Pair(exitCode, output)
        } catch (_: Exception) {
            Pair(-1, "")
        }
    }

    suspend fun probeGroundTruth(): RootHardwareGroundTruth = withContext(Dispatchers.IO) {
        val (rootCheckCode, _) = executeSuCommand("id")
        val isRoot = rootCheckCode == 0

        if (!isRoot) {
            return@withContext RootHardwareGroundTruth(
                isRootAvailable = false,
                rootImei = "Root not granted (Sandbox restricted)",
                rawBatteryUevent = "SELinux blocked",
                rawStorageSerial = "SELinux blocked",
                selinuxMode = "Enforcing (Sandbox)",
                rootGsfId = "N/A (Root required)",
                rootSsaid = "N/A (Root required)",
                rootSerialNo = "RESTRICTED"
            )
        }

        // 1. Query Hardware IMEI via multiple root methods
        var imeiOut = ""
        val (_, cmdPhone) = executeSuCommand("cmd phone get-device-id 2>/dev/null")
        if (cmdPhone.isNotBlank() && !cmdPhone.contains("Exception") && !cmdPhone.contains("not found")) {
            imeiOut = cmdPhone.lineSequence().firstOrNull { it.trim().all { c -> c.isDigit() } } ?: cmdPhone.trim()
        }
        if (imeiOut.isBlank()) {
            val (_, dumpsysImei) = executeSuCommand("dumpsys iphonesubinfo 2>/dev/null | grep -i 'Device ID' | head -n 1")
            if (dumpsysImei.contains("=")) imeiOut = dumpsysImei.substringAfter("=").trim()
        }
        if (imeiOut.isBlank()) {
            val (_, propImei) = executeSuCommand("getprop persist.radio.imei 2>/dev/null || getprop ro.ril.oem.imei1 2>/dev/null")
            imeiOut = propImei.trim()
        }

        // 2. Read battery PMIC uevent (already verified working)
        val (_, batteryUevent) = executeSuCommand("cat /sys/class/power_supply/battery/uevent 2>/dev/null")
        val parsedBattery = parseUeventBattery(batteryUevent)

        // 3. Read Hardware UFS / eMMC serial and system serial number
        val (_, storageSerial) = executeSuCommand(
            "cat /sys/block/sda/device/serial 2>/dev/null || cat /sys/block/bootdevice/device/serial 2>/dev/null || cat /sys/block/mmcblk0/device/cid 2>/dev/null"
        )
        val (_, rootSerial) = executeSuCommand("getprop ro.serialno 2>/dev/null || getprop ro.boot.serialno 2>/dev/null")

        // 4. Read SELinux enforcement mode
        val (_, selinux) = executeSuCommand("getenforce 2>/dev/null")

        // 5. Query GSF ID via native Android content command (no sqlite3 needed)
        val (_, contentGsf) = executeSuCommand(
            "content query --uri content://com.google.android.gsf.gservices --projection value --where \"name='android_id'\" 2>/dev/null"
        )
        val parsedGsf = parseContentGsf(contentGsf)

        // 6. Query raw SSAID entry from settings XML
        val (_, xmlSsaid) = executeSuCommand(
            "grep 'name=\"android_id\"' /data/system/users/0/settings_ssaid.xml 2>/dev/null | grep 'com.devicecheck.app' | sed -n 's/.*value=\"\\([^\"]*\\)\".*/\\1/p'"
        )

        RootHardwareGroundTruth(
            isRootAvailable = true,
            rootImei = imeiOut.ifBlank { "Restricted by modern RIL" },
            rawBatteryUevent = parsedBattery.ifBlank { "Node inaccessible" },
            rawStorageSerial = storageSerial.trim().ifBlank { "N/A" },
            selinuxMode = selinux.ifBlank { "Enforcing" },
            rootGsfId = parsedGsf,
            rootSsaid = xmlSsaid.trim().ifBlank { "N/A" },
            rootSerialNo = rootSerial.trim().ifBlank { "RESTRICTED" }
        )
    }

    private fun parseContentGsf(raw: String): String {
        // Output format: Row: 0 value=1234567890123456
        if (raw.isBlank() || !raw.contains("value=")) return "N/A"
        val decStr = raw.substringAfter("value=").trim().lines().firstOrNull()?.trim() ?: return "N/A"
        return decStr.toLongOrNull()?.let { java.lang.Long.toHexString(it).uppercase() } ?: decStr
    }

    private fun parseUeventBattery(uevent: String): String {
        if (uevent.isBlank()) return ""
        var current = "N/A"
        var voltage = "N/A"
        var temp = "N/A"
        uevent.lines().forEach { line ->
            if (line.startsWith("POWER_SUPPLY_CURRENT_NOW=")) current = "${line.substringAfter("=")} uA"
            if (line.startsWith("POWER_SUPPLY_VOLTAGE_NOW=")) voltage = "${line.substringAfter("=")} uV"
            if (line.startsWith("POWER_SUPPLY_TEMP=")) temp = "${line.substringAfter("=").toDoubleOrNull()?.div(10) ?: "N/A"} °C"
        }
        return "Current: $current | Voltage: $voltage | Temp: $temp"
    }
}
