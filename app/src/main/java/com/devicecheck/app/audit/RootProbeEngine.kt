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
    val selinuxMode: String
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
                selinuxMode = "Enforcing (Sandbox)"
            )
        }

        // Query raw IMEI via IPC binder service call to iphonesubinfo (TRANSACTION_getDeviceId / getImei)
        val (_, imeiRaw) = executeSuCommand("service call iphonesubinfo 1")
        val parsedImei = parseBinderImei(imeiRaw)

        // Read raw sysfs battery uevent bypass SELinux
        val (_, batteryUevent) = executeSuCommand("cat /sys/class/power_supply/battery/uevent")
        val parsedBattery = parseUeventBattery(batteryUevent)

        // Read raw block device serial
        val (_, storageSerial) = executeSuCommand("cat /sys/block/sda/device/serial 2>/dev/null || cat /sys/block/mmcblk0/device/cid 2>/dev/null")

        // Read SELinux enforcement state
        val (_, selinux) = executeSuCommand("getenforce")

        RootHardwareGroundTruth(
            isRootAvailable = true,
            rootImei = parsedImei.ifBlank { "Unreadable via IPC dump" },
            rawBatteryUevent = parsedBattery.ifBlank { "Node inaccessible" },
            rawStorageSerial = storageSerial.ifBlank { "N/A" },
            selinuxMode = selinux.ifBlank { "Enforcing" }
        )
    }

    private fun parseBinderImei(raw: String): String {
        // Output format: Result: Parcel(00000000 0000000f 00380036 00370033 ...)
        if (raw.isBlank() || !raw.contains("Result: Parcel(")) return ""
        val hexChars = StringBuilder()
        val regex = Regex("'([^']+)'")
        val matches = regex.findAll(raw)
        for (m in matches) {
            hexChars.append(m.groupValues[1])
        }
        val cleaned = hexChars.toString().replace(".", "").trim()
        return if (cleaned.length >= 14) cleaned else raw.take(40)
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
