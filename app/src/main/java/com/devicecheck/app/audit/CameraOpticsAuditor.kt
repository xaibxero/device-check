package com.devicecheck.app.audit

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

data class CameraOpticsTelemetry(
    val rearOptics: String,
    val frontOptics: String,
    val totalPhysicalSensors: Int
)

object CameraOpticsAuditor {

    fun audit(context: Context): CameraOpticsTelemetry {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        var rear = "N/A"
        var front = "N/A"
        var sensorCount = 0

        try {
            val ids = cm?.cameraIdList ?: emptyArray()
            sensorCount = ids.size
            for (id in ids) {
                val chars = cm?.getCameraCharacteristics(id) ?: continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

                val focalStr = focalLengths?.joinToString("/") { "${it}mm" } ?: "Fixed"
                val apertureStr = apertures?.joinToString("/") { "f/$it" } ?: "Fixed"

                if (facing == CameraCharacteristics.LENS_FACING_BACK && rear == "N/A") {
                    rear = "${size?.width}x${size?.height}mm @ $focalStr ($apertureStr)"
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && front == "N/A") {
                    front = "${size?.width}x${size?.height}mm @ $focalStr ($apertureStr)"
                }
            }
        } catch (_: Exception) {}

        return CameraOpticsTelemetry(
            rearOptics = rear,
            frontOptics = front,
            totalPhysicalSensors = sensorCount
        )
    }
}
