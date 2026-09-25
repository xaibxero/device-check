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
        // 1. Android ID (SSAID)
        val ssaid = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "RESTRICTED_OR_NULL"

        // 2. Multi-Path Google Services Framework (GSF) ID
        val (gsfId, gsfStatus) = extractGsfId(context)

        return IdentityAuditReport(
            ssaid = ssaid,
            gsfId = gsfId,
            gsfStatus = gsfStatus
        )
    }

    private fun extractGsfId(context: Context): Pair<String, String> {
        val uri = Uri.parse("content://com.google.android.gsf.gservices")
        
        // Strategy A: Standard query with selection
        try {
            val cursor = context.contentResolver.query(
                uri,
                null,
                "name=?",
                arrayOf("android_id"),
                null
            )
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    val rawVal = cursor.getString(1)
                    cursor.close()
                    if (!rawVal.isNullOrBlank()) {
                        val hex = rawVal.toLongOrNull()?.let { java.lang.Long.toHexString(it).uppercase() } ?: rawVal
                        return Pair(hex, "REGISTERED_PLAY_TOKEN")
                    }
                }
                cursor.close()
            }
        } catch (_: Throwable) {}

        // Strategy B: Positional argument query
        try {
            val cursor = context.contentResolver.query(
                uri,
                null,
                null,
                arrayOf("android_id"),
                null
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val value = cursor.getString(1)
                    if (name == "android_id" && !value.isNullOrBlank()) {
                        cursor.close()
                        val hex = value.toLongOrNull()?.let { java.lang.Long.toHexString(it).uppercase() } ?: value
                        return Pair(hex, "REGISTERED_PLAY_TOKEN")
                    }
                }
                cursor.close()
            }
        } catch (_: Throwable) {}

        // Strategy C: Direct URI path
        try {
            val directUri = Uri.parse("content://com.google.android.gsf.gservices/android_id")
            val cursor = context.contentResolver.query(directUri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val value = cursor.getString(1) ?: cursor.getString(0)
                cursor.close()
                if (!value.isNullOrBlank()) {
                    val hex = value.toLongOrNull()?.let { java.lang.Long.toHexString(it).uppercase() } ?: value
                    return Pair(hex, "REGISTERED_PLAY_TOKEN")
                }
            }
            cursor?.close()
        } catch (_: Throwable) {}

        return Pair("NOT_REGISTERED", "EMPTY_OR_UNCHECKED_GMS")
    }
}
