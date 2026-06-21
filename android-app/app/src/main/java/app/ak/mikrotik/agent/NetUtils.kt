package app.ak.mikrotik.agent

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** يكافئ getLocalSubnets في ak-agent.mjs: يرجع بادئات مثل "192.168.1." */
    fun getLocalSubnets(): List<String> {
        val subnets = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val prefix = ip.split(".").take(3).joinToString(".") + "."
                        subnets.add(prefix)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return subnets.toList()
    }

    /** فحص ICMP عبر أمر النظام (يكافئ check-ip في الوكيل الأصلي). */
    fun icmpPing(ip: String, timeoutSec: Int): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), ip))
            p.waitFor() == 0
        } catch (_: Exception) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", timeoutSec.toString(), ip))
                p.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    /** فحص TCP احتياطي. */
    fun tcpCheck(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
