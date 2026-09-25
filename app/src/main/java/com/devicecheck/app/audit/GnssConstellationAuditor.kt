package com.devicecheck.app.audit

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build

data class GnssTelemetry(
    val provider: String,
    val isMockFlagged: Boolean,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val satellitesInView: Int,
    val satellitesUsedInFix: Int,
    val constellationsActive: List<String>,
    val averageSnrNoiseDbHz: Float
)

object GnssConstellationAuditor {

    private var latestGnssStatus: GnssStatus? = null

    @SuppressLint("MissingPermission")
    fun registerGnssListener(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.registerGnssStatusCallback(object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    latestGnssStatus = status
                }
            }, null)
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    fun audit(context: Context): GnssTelemetry {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var lastLoc: Location? = null

        try {
            lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {}

        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lastLoc?.isMock == true
        } else {
            @Suppress("DEPRECATION")
            lastLoc?.isFromMockProvider == true
        }

        val status = latestGnssStatus
        val inView = status?.satelliteCount ?: 0
        var usedCount = 0
        var totalSnr = 0f
        val constellations = mutableSetOf<String>()

        if (status != null) {
            for (i in 0 until inView) {
                if (status.usedInFix(i)) usedCount++
                totalSnr += status.getCn0DbHz(i)
                constellations.add(
                    when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS (USA)"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS (RU)"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo (EU)"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou (CN)"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS (JP)"
                        else -> "Other"
                    }
                )
            }
        }

        val avgSnr = if (inView > 0) totalSnr / inView else 0f

        return GnssTelemetry(
            provider = lastLoc?.provider ?: "NONE",
            isMockFlagged = isMock,
            latitude = lastLoc?.latitude ?: 0.0,
            longitude = lastLoc?.longitude ?: 0.0,
            altitudeMeters = lastLoc?.altitude ?: 0.0,
            accuracyMeters = lastLoc?.accuracy ?: 0f,
            satellitesInView = inView,
            satellitesUsedInFix = usedCount,
            constellationsActive = constellations.toList(),
            averageSnrNoiseDbHz = avgSnr
        )
    }
}
