package com.devicecheck.app.audit

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

data class GnssTelemetry(
    val isSystemLocationEnabled: Boolean,
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
    private var latestLocation: Location? = null
    private var isGpsActive = false

    private val locationListener = LocationListener { location ->
        latestLocation = location
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            latestGnssStatus = status
        }
    }

    @SuppressLint("MissingPermission")
    fun startHardwareGps(context: Context) {
        if (isGpsActive) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        try {
            val mainHandler = Handler(Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCallback, mainHandler)

            // Register GPS provider
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // Register Network provider (Cell/Wi-Fi positioning for indoor lock)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            isGpsActive = true
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    fun audit(context: Context): GnssTelemetry {
        startHardwareGps(context)

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocOn = lm?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLocationEnabled
            else it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false

        val loc = latestLocation ?: try {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) { null }

        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc?.isMock == true
        } else {
            @Suppress("DEPRECATION")
            loc?.isFromMockProvider == true
        }

        val status = latestGnssStatus
        val inView = status?.satelliteCount ?: 0
        var usedCount = 0
        var totalSnr = 0f
        val constellations = mutableSetOf<String>()

        if (status != null) {
            for (i in 0 until inView) {
                try {
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
                } catch (_: Throwable) {}
            }
        }

        val avgSnr = if (inView > 0) totalSnr / inView else 0f

        return GnssTelemetry(
            isSystemLocationEnabled = isLocOn,
            provider = loc?.provider ?: if (!isLocOn) "LOCATION_OFF_IN_SETTINGS" else "ACQUIRING_LOCK",
            isMockFlagged = isMock,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            altitudeMeters = loc?.altitude ?: 0.0,
            accuracyMeters = loc?.accuracy ?: 0f,
            satellitesInView = inView,
            satellitesUsedInFix = usedCount,
            constellationsActive = constellations.toList(),
            averageSnrNoiseDbHz = avgSnr
        )
    }
}
