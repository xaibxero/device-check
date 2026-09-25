package com.devicecheck.app.audit

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NonRootNetworkReport(
    val activeInterface: String,
    val localIpAddress: String,
    val defaultGateway: String,
    val interfaceMtu: String,
    val allNetworkInterfaces: List<String>,
    val isVpnDetected: Boolean
)

object NonRootNetworkAuditor {

    fun audit(context: Context): NonRootNetworkReport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val linkProps = cm?.getLinkProperties(activeNet)

        val ifaceName = linkProps?.interfaceName ?: "Unknown"
        val mtu = linkProps?.mtu ?: 1500

        var gatewayStr = "Direct"
        linkProps?.routes?.forEach { route ->
            if (route.isDefaultRoute && route.gateway != null) {
                gatewayStr = route.gateway?.hostAddress ?: "Unknown"
            }
        }

        val ifaceList = mutableListOf<String>()
        var localIp = "127.0.0.1"
        var vpnFound = false

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp) continue
                val name = nif.name.lowercase()
                if (name.contains("tun") || name.contains("wg") || name.contains("p2p")) {
                    vpnFound = true
                }

                val addrs = Collections.list(nif.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        if (nif.name == ifaceName) {
                            localIp = addr.hostAddress ?: localIp
                        }
                        ifaceList.add("${nif.name}: ${addr.hostAddress}")
                    }
                }
            }
        } catch (_: Throwable) {}

        return NonRootNetworkReport(
            activeInterface = ifaceName,
            localIpAddress = localIp,
            defaultGateway = gatewayStr,
            interfaceMtu = "$mtu bytes",
            allNetworkInterfaces = ifaceList,
            isVpnDetected = vpnFound
        )
    }
}
