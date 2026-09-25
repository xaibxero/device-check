package com.devicecheck.app.audit

import android.content.Context
import android.net.Uri
import android.provider.Settings

data class IdentityAuditReport(
    val ssaid: String,
    val gsfId: String,
    val gsfStatus: String
)

object IdentityAuditor {

    fun audit(context: Context): IdentityAuditReport {
        // 1. Android ID (SSAID) via Settings.Secure
        val ssaid = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "RESTRICTED_OR_NULL"

        // 2. Google Services Framework (GSF) ID via ContentProvider
        val (gsfId, gsfStatus) = getGsfId(context)

        return IdentityAuditReport(
            ssaid = ssaid,
            gsfId = gsfId,
            gsfStatus = gsfStatus
        )
    }

    private fun getGsfId(context: Context): Pair<String, String> {
        val uri = Uri.parse("content://com.google.android.gsf.gservices")
        return try {
            val cursor = context.contentResolver.query(
                uri,
                null,
                null,
                arrayOf("android_id"),
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                val rawValue = cursor.getString(1)
                cursor.close()

                if (!rawValue.isNullOrBlank()) {
                    val hexGsf = java.lang.Long.toHexString(rawValue.toLong())
                    Pair(hexGsf.uppercase(), "VALID_REGISTERED_TOKEN")
                } else {
                    Pair("N/A", "EMPTY_GSF_RECORD")
                }
            } else {
                cursor?.close()
                Pair("N/A", "NOT_REGISTERED / GMS_ABSENT")
            }
        } catch (e: SecurityException) {
            Pair("PERMISSION_DENIED", "ACCESS_BLOCKED_BY_FRAMEWORK")
        } catch (e: Exception) {
            Pair("N/A", "QUERY_FAILED: ${e.message?.take(25)}")
        }
    }
}
