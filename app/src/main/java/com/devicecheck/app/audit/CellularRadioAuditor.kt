package com.devicecheck.app.audit

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager

data class CellularTelemetry(
    val imei1: String,
    val imei2: String,
    val meid: String,
    val imsi: String,
    val iccid: String,
    val simOperator: String,
    val simOperatorName: String,
    val simCountryIso: String,
    val networkOperator: String,
    val networkOperatorName: String,
    val networkCountryIso: String,
    val dataNetworkType: String,
    val cellTowerId: String,
    val trackingAreaCode: String,
    val physicalCellId: String,
    val radioSignalDbm: String,
    val basebandRadio: String
)

object CellularRadioAuditor {

    @SuppressLint("HardwareIds", "MissingPermission")
    fun audit(context: Context): CellularTelemetry {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val isAirplaneMode = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) != 0

        var imei1 = "Restricted (READ_PRIVILEGED_PHONE_STATE)"
        var imei2 = "Restricted (READ_PRIVILEGED_PHONE_STATE)"
        var meid = "Restricted"
        var imsi = "Restricted (Carrier Privileges Required)"
        var iccid = "Restricted (Carrier Privileges Required)"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imei1 = tm.getImei(0) ?: "N/A"
                imei2 = tm.getImei(1) ?: "N/A"
                meid = tm.meid ?: "N/A"
            }
        } catch (_: SecurityException) {}

        try {
            imsi = tm.subscriberId ?: "Restricted"
        } catch (_: SecurityException) {}

        try {
            iccid = tm.simSerialNumber ?: "Restricted"
        } catch (_: SecurityException) {}

        // Cell Tower Diagnostics
        var cellId = if (isAirplaneMode) "RADIO_OFFLINE (AIRPLANE MODE)" else "N/A"
        var tac = if (isAirplaneMode) "RADIO_OFFLINE" else "N/A"
        var pci = if (isAirplaneMode) "RADIO_OFFLINE" else "N/A"
        var signal = if (isAirplaneMode) "AIRPLANE_MODE_ACTIVE" else "N/A"

        if (!isAirplaneMode) {
            try {
                val cellInfos = tm.allCellInfo
                if (!cellInfos.isNullOrEmpty()) {
                    for (info in cellInfos) {
                        if (info is CellInfoLte) {
                            val identity: CellIdentityLte = info.cellIdentity
                            cellId = identity.ci.toString()
                            tac = identity.tac.toString()
                            pci = identity.pci.toString()
                            signal = "${info.cellSignalStrength.dbm} dBm"
                            break
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                            val identity = info.cellIdentity as? CellIdentityNr
                            if (identity != null) {
                                cellId = identity.nci.toString()
                                tac = identity.tac.toString()
                                pci = identity.pci.toString()
                                signal = "${info.cellSignalStrength.dbm} dBm"
                                break
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }

        val networkTypeStr = if (isAirplaneMode) {
            "RADIO_POWERED_DOWN"
        } else {
            try {
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G (NR)"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
                    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA -> "3G (HSPA)"
                    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN / NO_SERVICE"
                    else -> "Type_${tm.dataNetworkType}"
                }
            } catch (_: SecurityException) { "Permission Restricted" }
        }

        val simOpName = tm.simOperatorName.ifBlank { if (isAirplaneMode) "AIRPLANE MODE" else "NO_SIM_OR_CARRIER" }
        val netOpName = tm.networkOperatorName.ifBlank { if (isAirplaneMode) "AIRPLANE MODE" else "NO_SERVICE" }

        return CellularTelemetry(
            imei1 = imei1,
            imei2 = imei2,
            meid = meid,
            imsi = imsi,
            iccid = iccid,
            simOperator = tm.simOperator.ifBlank { "N/A" },
            simOperatorName = simOpName,
            simCountryIso = tm.simCountryIso.ifBlank { "N/A" }.uppercase(),
            networkOperator = tm.networkOperator.ifBlank { "N/A" },
            networkOperatorName = netOpName,
            networkCountryIso = tm.networkCountryIso.ifBlank { "N/A" }.uppercase(),
            dataNetworkType = networkTypeStr,
            cellTowerId = cellId,
            trackingAreaCode = tac,
            physicalCellId = pci,
            radioSignalDbm = signal,
            basebandRadio = Build.getRadioVersion() ?: "N/A"
        )
    }
}
