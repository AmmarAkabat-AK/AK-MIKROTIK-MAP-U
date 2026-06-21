package app.ak.mikrotik.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * تنفيذ أوامر MikroTik عبر RouterOS API.
 * يكافئ دالة executeCommand في ak-agent.mjs بنفس مفاتيح JSON المُرجعة.
 *
 * msg يجب أن يحوي: cmd, host, username, password, port, (interface, subnet, ips حسب الأمر)
 */
object CommandExecutor {

    private const val NET = "جهاز شبكة"
    private const val MIKROTIK = "راوتر MikroTik"

    fun execute(msg: JSONObject): JSONObject {
        val cmd = msg.optString("cmd")
        val host = msg.optString("host")
        val username = msg.optString("username")
        val password = msg.optString("password", "")
        val port = msg.optInt("port", 8728)

        val api = RouterOSApi(host, username, password, port)
        try {
            api.connect()
            return when (cmd) {
                "ping" -> ping(api)
                "um-users" -> umUsers(api)
                "discover" -> discover(api)
                "interfaces" -> interfaces(api)
                "ip-scan" -> ipScan(api, msg.optString("interface", ""), msg.optString("subnet", ""))
                "vlan-stats" -> vlanStats(api)
                "arp" -> arp(api)
                "backup-config" -> backupConfig(api, host)
                "backup-um" -> backupUm(api, host)
                "active-count" -> activeCount(api)
                "reboot" -> {
                    try { api.write("/system/reboot") } catch (_: Exception) {}
                    JSONObject().put("success", true)
                }
                else -> throw RuntimeException("أمر غير معروف: $cmd")
            }
        } finally {
            try { api.close() } catch (_: Exception) {}
        }
    }

    // ── ping ──────────────────────────────────────────────────────
    private fun ping(api: RouterOSApi): JSONObject {
        val resource = api.write("/system/resource/print").firstOrNull() ?: emptyMap()
        val identity = api.write("/system/identity/print").firstOrNull() ?: emptyMap()
        return JSONObject().apply {
            put("online", true)
            put("viaAgent", true)
            put("board", resource["board-name"] ?: "")
            put("version", resource["version"] ?: "")
            put("uptime", resource["uptime"] ?: "")
            put("identity", identity["name"] ?: "")
            put("cpuLoad", resource["cpu-load"]?.toIntOrNull() ?: 0)
            put("freeMemory", resource["free-memory"]?.toIntOrNull() ?: 0)
            put("totalMemory", resource["total-memory"]?.toIntOrNull() ?: 0)
        }
    }

    // ── um-users ──────────────────────────────────────────────────
    private fun umUsers(api: RouterOSApi): JSONObject {
        val users = api.writeCatch("/tool/user-manager/user/print")
        val profiles = api.writeCatch("/tool/user-manager/profile/print")
        val active = api.writeCatch("/tool/user-manager/user/active/print")
        val activeSet = active.mapNotNull { it["username"] ?: it["name"] }.toHashSet()

        val usersArr = JSONArray()
        var activeCount = 0
        var disabledCount = 0
        for (u in users) {
            val uname = u["username"] ?: u["name"] ?: ""
            val disabled = u["disabled"] == "true"
            val isActive = activeSet.contains(uname)
            if (isActive) activeCount++
            if (disabled) disabledCount++
            usersArr.put(JSONObject().apply {
                put("username", uname)
                put("profile", u["profile"] ?: u["profile-name"] ?: "")
                put("comment", u["comment"] ?: "")
                put("disabled", disabled)
                put("isActive", isActive)
                put("end-time", u["end-time"] ?: "")
            })
        }
        val sliced = JSONArray()
        val limit = minOf(500, usersArr.length())
        for (i in 0 until limit) sliced.put(usersArr.get(i))

        val profilesArr = JSONArray()
        for (p in profiles) p["name"]?.takeIf { it.isNotEmpty() }?.let { profilesArr.put(it) }

        return JSONObject().apply {
            put("users", sliced)
            put("total", users.size)
            put("profiles", profilesArr)
            put("activeCount", activeCount)
            put("disabledCount", disabledCount)
        }
    }

    // ── discover ──────────────────────────────────────────────────
    private fun discover(api: RouterOSApi): JSONObject {
        val arp = api.writeCatch("/ip/arp/print")
        val neighbors = api.writeCatch("/ip/neighbor/print")
        val dhcp = api.writeCatch("/ip/dhcp-server/lease/print")
        val devices = LinkedHashMap<String, JSONObject>()

        for (i in arp) {
            val a = i["address"] ?: continue
            if (a.isEmpty()) continue
            devices[a] = dev(a, i["mac-address"] ?: "", "", NET, i["interface"] ?: "", "ARP")
        }
        for (i in dhcp) {
            val a = i["address"] ?: continue
            if (a.isEmpty()) continue
            val existing = devices[a]
            if (existing != null) existing.put("hostname", i["hostname"] ?: "")
            else devices[a] = dev(a, i["mac-address"] ?: "", i["hostname"] ?: "", NET, "", "DHCP")
        }
        for (i in neighbors) {
            val a = i["address"] ?: continue
            if (a.isEmpty()) continue
            val mk = (i["system-description"] ?: "").lowercase().contains("mikrotik")
            val existing = devices[a]
            if (existing != null) existing.put("type", if (mk) MIKROTIK else NET)
            else devices[a] = dev(a, i["mac-address"] ?: "", i["identity"] ?: "", if (mk) MIKROTIK else NET, i["interface"] ?: "", "Neighbor")
        }
        return JSONObject().put("devices", JSONArray(devices.values.toList()))
    }

    // ── interfaces ────────────────────────────────────────────────
    private fun interfaces(api: RouterOSApi): JSONObject {
        val ifaces = api.writeCatch("/interface/print")
        val ipAddrs = api.writeCatch("/ip/address/print")
        val addrMap = HashMap<String, String>()
        for (a in ipAddrs) {
            val iface = a["interface"]; val addr = a["address"]
            if (!iface.isNullOrEmpty() && !addr.isNullOrEmpty()) addrMap[iface] = addr
        }
        val arr = JSONArray()
        for (i in ifaces) {
            val name = i["name"] ?: continue
            if (name.isEmpty()) continue
            if (i["type"] == "bridge") continue
            if (i["disabled"] == "true") continue
            arr.put(JSONObject().apply {
                put("name", name)
                put("type", i["type"] ?: "ether")
                put("running", i["running"] != "false")
                put("address", addrMap[name] ?: "")
            })
        }
        return JSONObject().put("interfaces", arr)
    }

    // ── ip-scan ───────────────────────────────────────────────────
    private fun ipScan(api: RouterOSApi, iface: String, subnet: String): JSONObject {
        val arpList = api.writeCatch("/ip/arp/print")
        val dhcpList = api.writeCatch("/ip/dhcp-server/lease/print")
        val neighborList = api.writeCatch("/ip/neighbor/print")
        val toolScanRaw = if (subnet.isNotEmpty()) {
            val params = arrayListOf("=address-range=$subnet")
            if (iface.isNotEmpty()) params.add("=interface=$iface")
            params.add("=duration=15s")
            api.writeCatch("/tool/ip-scan", params)
        } else emptyList()

        val devices = LinkedHashMap<String, JSONObject>()

        for (item in arpList) {
            val a = item["address"] ?: continue
            if (a.isEmpty() || a == "0.0.0.0") continue
            if (iface.isNotEmpty() && item["interface"] != iface) continue
            if (!matchSubnet(a, subnet)) continue
            devices[a] = devFull(a, item["mac-address"] ?: "", "", NET, item["interface"] ?: "", "ARP", "",
                if (item["complete"] == "true") "online" else "unknown")
        }
        for (item in dhcpList) {
            val a = item["address"] ?: continue
            if (a.isEmpty()) continue
            if (!matchSubnet(a, subnet)) continue
            val h = item["hostname"] ?: item["comment"] ?: ""
            val existing = devices[a]
            if (existing != null) {
                if (h.isNotEmpty()) existing.put("hostname", h)
                item["mac-address"]?.takeIf { it.isNotEmpty() }?.let { existing.put("mac", it) }
                existing.put("source", "DHCP")
                if (item["status"] == "bound") existing.put("status", "online")
            } else {
                devices[a] = devFull(a, item["mac-address"] ?: "", h, NET, iface, "DHCP", item["comment"] ?: "",
                    if (item["status"] == "bound") "online" else "unknown")
            }
        }
        for (item in neighborList) {
            val a = item["address"] ?: continue
            if (a.isEmpty()) continue
            if (iface.isNotEmpty() && item["interface"] != iface) continue
            if (!matchSubnet(a, subnet)) continue
            val mk = (item["system-description"] ?: "").lowercase().contains("mikrotik") || !item["identity"].isNullOrEmpty()
            val name = item["identity"] ?: item["system-name"] ?: ""
            val existing = devices[a]
            if (existing != null) {
                if (mk) existing.put("type", MIKROTIK)
                if (name.isNotEmpty()) existing.put("hostname", name)
            } else {
                devices[a] = devFull(a, item["mac-address"] ?: "", name, if (mk) MIKROTIK else NET,
                    item["interface"] ?: iface, "CDP/LLDP", "", "online")
            }
        }
        for (item in toolScanRaw) {
            val ip = item["address"] ?: continue
            if (ip.isEmpty() || ip == "0.0.0.0") continue
            val mac = item["mac-address"] ?: item["mac"] ?: ""
            val dns = item["dns-name"] ?: item["hostname"] ?: ""
            val scanIface = item["interface"] ?: iface
            val existing = devices[ip]
            if (existing != null) {
                if (mac.isNotEmpty()) existing.put("mac", mac)
                if (dns.isNotEmpty()) existing.put("hostname", dns)
                existing.put("status", "online")
                existing.put("source", "IP Scan")
            } else {
                devices[ip] = devFull(ip, mac, dns, NET, scanIface, "IP Scan", "", "online")
            }
        }
        // تصنيف الأجهزة حسب اسم المضيف
        for (d in devices.values) {
            if (d.optString("type") != NET) continue
            val h = d.optString("hostname").lowercase()
            when {
                h.contains("ubnt") || h.contains("ltu") || h.contains("airmax") || h.contains("antenna") -> d.put("type", "أنتينا")
                h.contains("modem") || h.contains("zte") || h.contains("huawei") || h.contains("dsl") -> d.put("type", "مودم")
                h.contains("ap") || h.contains("wifi") || h.contains("access") -> d.put("type", "أكسس بوينت")
                h.contains("cam") || h.contains("ipc") || h.contains("nvr") -> d.put("type", "كاميرا")
            }
        }
        val sorted = devices.values.sortedWith(Comparator { a, b -> compareIp(a.optString("ip"), b.optString("ip")) })
        return JSONObject().apply {
            put("devices", JSONArray(sorted))
            put("scanned", sorted.size)
        }
    }

    // ── vlan-stats ────────────────────────────────────────────────
    private fun vlanStats(api: RouterOSApi): JSONObject {
        val hsServers = api.writeCatch("/ip/hotspot/print", listOf("=.proplist=name,interface"))
        val hsActive = api.writeCatch("/ip/hotspot/active/print", listOf("=.proplist=user,server,radius,uptime,bytes-in,bytes-out"))
        val umTotal = api.writeCatch("/tool/user-manager/user/print", listOf("=count-only="))
        val cookies = api.writeCatch("/ip/hotspot/cookie/print", listOf("=.proplist=user,server"))

        val totalUmUsers = umTotal.firstOrNull()?.get("ret")?.toIntOrNull() ?: 0

        data class Srv(var total: Int = 0, var radius: Int = 0, var local: Int = 0, val users: JSONArray = JSONArray())
        val activeByServer = HashMap<String, Srv>()
        for (s in hsActive) {
            val srv = s["server"] ?: "unknown"
            val e = activeByServer.getOrPut(srv) { Srv() }
            e.total++
            if (s["radius"] == "true") e.radius++ else e.local++
            e.users.put(s["user"] ?: "")
        }
        val cookieByServer = HashMap<String, HashSet<String>>()
        for (c in cookies) {
            val srv = c["server"] ?: "unknown"
            val set = cookieByServer.getOrPut(srv) { HashSet() }
            c["user"]?.takeIf { it.isNotEmpty() }?.let { set.add(it) }
        }
        val vlans = ArrayList<JSONObject>()
        for (s in hsServers) {
            val name = s["name"] ?: ""
            val stats = activeByServer[name] ?: Srv()
            val cookieCount = cookieByServer[name]?.size ?: 0
            vlans.add(JSONObject().apply {
                put("name", name)
                put("interface", s["interface"] ?: "")
                put("active", stats.total)
                put("radius", stats.radius)
                put("local", stats.local)
                put("users", stats.users)
                put("totalUsed", cookieCount)
            })
        }
        vlans.sortWith(Comparator { a, b ->
            val d = b.optInt("active") - a.optInt("active")
            if (d != 0) d else b.optInt("totalUsed") - a.optInt("totalUsed")
        })
        return JSONObject().apply {
            put("vlans", JSONArray(vlans))
            put("totalActive", hsActive.size)
            put("totalRadius", hsActive.count { it["radius"] == "true" })
            put("totalUmUsers", totalUmUsers)
            put("activeVlans", vlans.count { it.optInt("active") > 0 })
        }
    }

    // ── arp ───────────────────────────────────────────────────────
    private fun arp(api: RouterOSApi): JSONObject {
        val arpList = api.writeCatch("/ip/arp/print")
        val neighborList = api.writeCatch("/ip/neighbor/print")
        val dhcpList = api.writeCatch("/ip/dhcp-server/lease/print")
        val wirelessList = api.writeCatch("/ip/hotspot/host/print")

        val arpIpSet = LinkedHashSet<String>()
        val neighborIpSet = LinkedHashSet<String>()
        val wirelessIpSet = LinkedHashSet<String>()

        for (e in arpList) e["address"]?.takeIf { it.isNotEmpty() && it != "0.0.0.0" }?.let { arpIpSet.add(it) }
        for (e in dhcpList) e["address"]?.takeIf { it.isNotEmpty() }?.let { arpIpSet.add(it) }
        for (e in neighborList) e["address"]?.takeIf { it.isNotEmpty() }?.let { neighborIpSet.add(it) }
        for (e in wirelessList) e["address"]?.takeIf { it.isNotEmpty() }?.let { wirelessIpSet.add(it) }

        val allIps = LinkedHashSet<String>()
        allIps.addAll(arpIpSet); allIps.addAll(neighborIpSet); allIps.addAll(wirelessIpSet)

        return JSONObject().apply {
            put("ips", JSONArray(allIps.toList()))
            put("arpIps", JSONArray(arpIpSet.toList()))
            put("neighborIps", JSONArray(neighborIpSet.toList()))
            put("wirelessIps", JSONArray(wirelessIpSet.toList()))
        }
    }

    // ── backup-config ─────────────────────────────────────────────
    private fun backupConfig(api: RouterOSApi, host: String): JSONObject {
        val identity = api.writeCatch("/system/identity/print").firstOrNull()
        val routerName = identity?.get("name") ?: host
        var configText = ""
        try {
            val raw = api.write("/export")
            configText = raw.joinToString("\n") { item ->
                item["ret"] ?: item["line"] ?: item["!re"] ?: item.values.joinToString(" ")
            }
        } catch (_: Exception) {
            configText = "# failed to export config"
        }
        if (configText.trim().isEmpty()) configText = "# no config data"
        return JSONObject().apply {
            put("success", true)
            put("config", configText)
            put("routerName", routerName)
            put("filename", "$routerName-config-${dateStamp()}.rsc")
        }
    }

    // ── backup-um ─────────────────────────────────────────────────
    private fun backupUm(api: RouterOSApi, host: String): JSONObject {
        val identity = api.writeCatch("/system/identity/print").firstOrNull()
        val routerName = identity?.get("name") ?: host
        val users = api.writeCatch("/tool/user-manager/user/print")
        val profiles = api.writeCatch("/tool/user-manager/profile/print")
        val limitations = api.writeCatch("/tool/user-manager/profile-limitation/print")
        val activeSessions = api.writeCatch("/tool/user-manager/user/active/print")

        val data = JSONObject().apply {
            put("router", routerName)
            put("exported_at", isoNow())
            put("stats", JSONObject().apply {
                put("total_users", users.size)
                put("total_profiles", profiles.size)
                put("active_sessions", activeSessions.size)
            })
            put("users", listToJson(users))
            put("profiles", listToJson(profiles))
            put("limitations", listToJson(limitations))
            put("active_sessions", listToJson(activeSessions))
        }
        return JSONObject().apply {
            put("success", true)
            put("routerName", routerName)
            put("filename", "$routerName-um-${dateStamp()}.json")
            put("data", data)
        }
    }

    // ── active-count ──────────────────────────────────────────────
    private fun activeCount(api: RouterOSApi): JSONObject {
        val sessions = api.writeCatch("/ip/hotspot/active/print", listOf("=.proplist=user,server,address,uptime"))
        val firstFive = JSONArray()
        for (i in 0 until minOf(5, sessions.size)) firstFive.put(JSONObject(sessions[i] as Map<*, *>))
        return JSONObject().apply {
            put("count", sessions.size)
            put("sessions", firstFive)
        }
    }

    // ── أدوات مساعدة ──────────────────────────────────────────────
    private fun dev(ip: String, mac: String, hostname: String, type: String, iface: String, source: String) =
        JSONObject().apply {
            put("ip", ip); put("mac", mac); put("hostname", hostname)
            put("type", type); put("interface", iface); put("source", source)
        }

    private fun devFull(ip: String, mac: String, hostname: String, type: String, iface: String, source: String, comment: String, status: String) =
        JSONObject().apply {
            put("ip", ip); put("mac", mac); put("hostname", hostname)
            put("type", type); put("interface", iface); put("source", source)
            put("comment", comment); put("status", status)
        }

    private fun listToJson(list: List<Map<String, String>>): JSONArray {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject(m as Map<*, *>))
        return arr
    }

    private fun matchSubnet(ip: String, subnet: String): Boolean {
        if (subnet.isEmpty()) return true
        return try {
            val parts = subnet.split("/")
            val net = parts.getOrNull(0) ?: return true
            if (net.isEmpty()) return true
            val bits = parts.getOrNull(1)
            if (bits.isNullOrEmpty()) {
                return ip.startsWith(net.split(".").take(3).joinToString("."))
            }
            val maskBits = bits.toInt()
            val ipParts = ip.split(".").map { it.toIntOrNull() ?: 0 }
            val netParts = net.split(".").map { it.toIntOrNull() ?: 0 }
            var ipNum = 0L; var netNum = 0L
            for (i in 0 until 4) {
                ipNum = (ipNum shl 8) or (ipParts.getOrElse(i) { 0 }.toLong() and 0xFF)
                netNum = (netNum shl 8) or (netParts.getOrElse(i) { 0 }.toLong() and 0xFF)
            }
            val mask = if (maskBits == 0) 0L else ((0xFFFFFFFFL shl (32 - maskBits)) and 0xFFFFFFFFL)
            (ipNum and mask) == (netNum and mask)
        } catch (_: Exception) {
            true
        }
    }

    private fun compareIp(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 4) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun dateStamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun isoNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}
