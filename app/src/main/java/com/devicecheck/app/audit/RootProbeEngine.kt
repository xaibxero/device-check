// Add these queries inside probeGroundTruth() in RootProbeEngine.kt:

// Query GSF ID directly from Google Play Services database
val (_, rootGsfRaw) = executeSuCommand(
    "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \"SELECT value FROM main WHERE name='android_id';\" 2>/dev/null"
)
val rootGsfHex = rootGsfRaw.trim().toLongOrNull()?.let { java.lang.Long.toHexString(it).uppercase() } ?: "N/A"

// Query raw SSAID entry for our package from system settings XML
val (_, xmlSsaid) = executeSuCommand(
    "grep 'name=\"android_id\"' /data/system/users/0/settings_ssaid.xml 2>/dev/null | grep 'com.devicecheck.app' | sed -n 's/.*value=\"\\([^\"]*\\)\".*/\\1/p'"
)
