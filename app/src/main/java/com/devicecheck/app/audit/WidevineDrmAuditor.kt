package com.devicecheck.app.audit

import android.media.MediaDrm
import java.util.UUID

data class WidevineTelemetry(
    val securityLevel: String,
    val systemId: String,
    val vendor: String,
    val description: String,
    val maxHdcpLevel: String
)

object WidevineDrmAuditor {

    fun audit(): WidevineTelemetry {
        val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        return try {
            val drm = MediaDrm(widevineUuid)
            val level = drm.getPropertyString("securityLevel") ?: "L3"
            val systemId = drm.getPropertyString("systemId") ?: "N/A"
            val vendor = drm.getPropertyString("vendor") ?: "Google"
            val description = drm.getPropertyString("description") ?: "N/A"
            val hdcp = try { drm.getPropertyString("maxHdcpLevel") ?: "N/A" } catch (_: Exception) { "N/A" }
            drm.close()

            WidevineTelemetry(
                securityLevel = level,
                systemId = systemId,
                vendor = vendor,
                description = description,
                maxHdcpLevel = hdcp
            )
        } catch (_: Exception) {
            WidevineTelemetry("L3 (Software/Restricted)", "N/A", "Google", "Fallback", "N/A")
        }
    }
}
