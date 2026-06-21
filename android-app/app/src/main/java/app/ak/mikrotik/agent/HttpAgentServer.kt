package app.ak.mikrotik.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * خادم HTTP محلي على 127.0.0.1:7779 (يكافئ MODE 1 في ak-agent.mjs)
 * للمتصفح أو التطبيقات على نفس الجهاز.
 *
 * المسارات:
 *   GET  /health, /
 *   POST /check-ip, /ping-devices
 *   POST /ping, /um-users, /discover, /interfaces, /ip-scan, /arp,
 *        /backup-config, /backup-um, /active-count, /reboot
 */
class HttpAgentServer(private val port: Int = 7779) {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    private val cmdMap = mapOf(
        "/ping" to "ping", "/um-users" to "um-users", "/discover" to "discover",
        "/interfaces" to "interfaces", "/ip-scan" to "ip-scan", "/arp" to "arp",
        "/backup-config" to "backup-config", "/backup-um" to "backup-um",
        "/active-count" to "active-count", "/reboot" to "reboot"
    )

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                AgentState.log("HTTP → http://localhost:$port (نفس الجهاز)")
                while (running) {
                    val client = try { server?.accept() ?: break } catch (_: Exception) { break }
                    Thread { handle(client) }.start()
                }
            } catch (e: Exception) {
                AgentState.log("HTTP خطأ: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                body = String(buf, 0, read)
            }

            val out = client.getOutputStream()

            if (method == "OPTIONS") { writeResponse(out, 204, "{}"); return }

            if (path == "/health" || path == "/") {
                writeResponse(out, 200, JSONObject().apply {
                    put("ok", true); put("agent", "ak-mikrotik"); put("port", port)
                }.toString())
                return
            }

            if (method != "POST") { writeResponse(out, 405, "{}"); return }

            val json = try { if (body.isEmpty()) JSONObject() else JSONObject(body) }
            catch (_: Exception) { writeResponse(out, 400, "{\"error\":\"Bad JSON\"}"); return }

            when (path) {
                "/check-ip" -> handleCheckIp(out, json)
                "/ping-devices" -> handlePingDevices(out, json)
                else -> {
                    val cmd = cmdMap[path]
                    if (cmd == null) { writeResponse(out, 404, "{\"error\":\"Not found\"}"); return }
                    AgentState.incRequest()
                    AgentState.log("HTTP ${cmd.uppercase()} ← ${json.optString("host")}")
                    try {
                        json.put("cmd", cmd)
                        val result = CommandExecutor.execute(json)
                        writeResponse(out, 200, result.toString())
                        AgentState.log("HTTP ${cmd.uppercase()} ✓ ${json.optString("host")}")
                    } catch (e: Exception) {
                        writeResponse(out, 200, JSONObject().apply {
                            put("online", false); put("error", e.message ?: "error")
                        }.toString())
                        AgentState.log("HTTP ${cmd.uppercase()} ✗ ${json.optString("host")} — ${e.message}")
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleCheckIp(out: OutputStream, json: JSONObject) {
        val ip = json.optString("ip")
        if (ip.isEmpty()) { writeResponse(out, 400, "{\"error\":\"ip required\"}"); return }
        val checkPort = json.optInt("port", 80)
        val checkTimeout = json.optInt("timeout", 2000)
        val timeoutSec = maxOf(1, Math.ceil(checkTimeout / 1000.0).toInt())

        if (NetUtils.icmpPing(ip, timeoutSec)) {
            writeResponse(out, 200, JSONObject().apply {
                put("online", true); put("ip", ip); put("method", "icmp")
            }.toString())
            return
        }
        val tcpOnline = NetUtils.tcpCheck(ip, checkPort, checkTimeout)
        writeResponse(out, 200, JSONObject().apply {
            put("online", tcpOnline); put("ip", ip)
        }.toString())
    }

    private fun handlePingDevices(out: OutputStream, json: JSONObject) {
        val host = json.optString("host")
        val ipsArr = json.optJSONArray("ips")
        if (host.isEmpty() || ipsArr == null || ipsArr.length() == 0) {
            writeResponse(out, 400, "{\"error\":\"host, ips[] required\"}"); return
        }
        val username = json.optString("username")
        val password = json.optString("password", "")
        val rPort = json.optInt("port", 8728)
        val ips = ArrayList<String>()
        for (i in 0 until ipsArr.length()) ips.add(ipsArr.optString(i))

        AgentState.log("HTTP PING-DEVICES ← $host (${ips.size} IPs)")
        val onlineIps = JSONArray()
        val details = JSONArray()
        for (ip in ips) {
            val online = pingOneIp(host, username, password, rPort, ip)
            details.put(JSONObject().apply { put("ip", ip); put("online", online) })
            if (online) onlineIps.put(ip)
        }
        AgentState.log("HTTP PING-DEVICES ✓ $host — ${onlineIps.length()}/${ips.size}")
        writeResponse(out, 200, JSONObject().apply {
            put("ips", onlineIps); put("details", details)
        }.toString())
    }

    private fun pingOneIp(host: String, user: String, pass: String, port: Int, ip: String): Boolean {
        val api = RouterOSApi(host, user, pass, port)
        return try {
            api.connect()
            val replies = api.write("/tool/ping", listOf("=address=$ip", "=count=2", "=interval=500ms"))
            replies.any { r ->
                (!r["time"].isNullOrEmpty() && r["time"] != "0ms") ||
                    (r["received"]?.toIntOrNull() ?: 0) > 0 ||
                    ((r["sent"]?.toIntOrNull() ?: 0) > 0 && (r["loss"]?.toIntOrNull() ?: 100) < 100)
            }
        } catch (_: Exception) {
            false
        } finally {
            try { api.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "OK"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $statusText\r\n")
        sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
