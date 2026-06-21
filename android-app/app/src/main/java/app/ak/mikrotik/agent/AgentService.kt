package app.ak.mikrotik.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * خدمة أمامية تبقى تعمل في الخلفية:
 *   - تتصل بالسيرفر عبر WebSocket (MODE 2 في ak-agent.mjs) مع إعادة اتصال
 *   - تشغّل خادم HTTP محلي (MODE 1)
 *   - تنفّذ أوامر MikroTik القادمة من السيرفر
 */
class AgentService : Service() {

    companion object {
        const val ACTION_START = "app.ak.mikrotik.agent.START"
        const val ACTION_STOP = "app.ak.mikrotik.agent.STOP"
        private const val CHANNEL_ID = "ak_agent_status"
        private const val NOTIF_ID = 1001
        private const val RECONNECT_MS = 5000L
    }

    private lateinit var client: OkHttpClient
    private var ws: WebSocket? = null
    @Volatile private var shouldRun = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val httpServer = HttpAgentServer(7779)

    // مجمع خيوط لتنفيذ الأوامر دون حجب خيط WebSocket
    private val executor = Executors.newFixedThreadPool(4)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AKAgent::wakelock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (!shouldRun) {
            shouldRun = true
            Prefs.setShouldRun(this, true)
            AgentState.setRunning(true)
            try { wakeLock?.acquire() } catch (_: Exception) {}
            httpServer.start()
            logHeader()
            connect()
        }
        return START_STICKY
    }

    private fun logHeader() {
        AgentState.log("━━━━━━━━━━━━━━━━━━━━━━━")
        AgentState.log("AK-MIKROTIK Local Agent")
        AgentState.log("━━━━━━━━━━━━━━━━━━━━━━━")
    }

    // ── الاتصال بـ WebSocket مع إعادة المحاولة ─────────────────────
    private fun connect() {
        if (!shouldRun) return
        val url = Prefs.getUrl(this)
        val request = Request.Builder().url(url).build()
        AgentState.log("WS → $url")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subnets = NetUtils.getLocalSubnets()
                val msg = JSONObject().apply {
                    put("type", "register")
                    put("subnets", JSONArray(subnets))
                }
                webSocket.send(msg.toString())
                AgentState.setConnected(true)
                AgentState.log("WS ✓ تم الإرسال (register)")
                updateNotification("متصل بالسيرفر")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AgentState.setConnected(false)
                updateNotification("غير متصل — إعادة المحاولة")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AgentState.setConnected(false)
                AgentState.log("WS ✗ ${t.message?.take(80) ?: "فشل الاتصال"}")
                updateNotification("غير متصل — إعادة المحاولة")
                scheduleReconnect()
            }
        })
    }

    @Volatile private var reconnectScheduled = false
    private fun scheduleReconnect() {
        if (!shouldRun || reconnectScheduled) return
        reconnectScheduled = true
        executor.execute {
            try { Thread.sleep(RECONNECT_MS) } catch (_: Exception) {}
            reconnectScheduled = false
            if (shouldRun) {
                AgentState.log("↺ إعادة اتصال WS...")
                connect()
            }
        }
    }

    // ── معالجة رسالة السيرفر ──────────────────────────────────────
    private fun handleMessage(webSocket: WebSocket, raw: String) {
        val msg = try { JSONObject(raw) } catch (_: Exception) { return }

        if (msg.optString("type") == "registered") {
            AgentState.log("WS ✓ متصل (agent: ${msg.optString("agentId")})")
            return
        }
        val reqId = msg.opt("reqId") ?: return

        val cmd = msg.optString("cmd")
        val host = msg.optString("host")
        AgentState.incRequest()
        AgentState.log("WS ${cmd.uppercase()} ← $host")

        // التنفيذ في خيط منفصل حتى لا نحجب استقبال الرسائل
        executor.execute {
            try {
                val data = CommandExecutor.execute(msg)
                val response = JSONObject().apply {
                    put("type", "response")
                    put("reqId", reqId)
                    put("data", data)
                }
                webSocket.send(response.toString())
                AgentState.log("WS ${cmd.uppercase()} ✓ $host")
            } catch (e: Exception) {
                val response = JSONObject().apply {
                    put("type", "response")
                    put("reqId", reqId)
                    put("error", e.message ?: "error")
                }
                webSocket.send(response.toString())
                AgentState.log("WS ${cmd.uppercase()} ✗ $host — ${e.message}")
            }
        }
    }

    // ── إيقاف الوكيل ──────────────────────────────────────────────
    private fun stopAgent() {
        shouldRun = false
        Prefs.setShouldRun(this, false)
        try { ws?.close(1000, "stopped") } catch (_: Exception) {}
        ws = null
        httpServer.stop()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        AgentState.setConnected(false)
        AgentState.setRunning(false)
        AgentState.log("⏹ تم إيقاف الوكيل")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ws?.cancel() } catch (_: Exception) {}
        httpServer.stop()
        executor.shutdownNow()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        AgentState.setRunning(false)
    }

    // ── الإشعار / الخدمة الأمامية ─────────────────────────────────
    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification("جاري بدء الوكيل…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(status))
        } catch (_: Exception) {}
    }
}
