package app.ak.mikrotik.agent

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** حالة مشتركة بين الخدمة والواجهة (السجل، الاتصال، عدد الطلبات). */
object AgentState {
    @Volatile var running = false
        private set
    @Volatile var connected = false
        private set
    @Volatile var requestCount = 0
        private set

    private val logs = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** يُستدعى عند أي تغيير لتحديث الواجهة. */
    @Volatile var listener: (() -> Unit)? = null

    fun setRunning(v: Boolean) { running = v; notifyChange() }

    fun setConnected(v: Boolean) { connected = v; notifyChange() }

    fun incRequest() { requestCount++; notifyChange() }

    fun resetCount() { requestCount = 0; notifyChange() }

    fun log(msg: String) {
        synchronized(logs) {
            logs.addLast("[${fmt.format(Date())}] $msg")
            while (logs.size > 200) logs.pollFirst()
        }
        notifyChange()
    }

    fun snapshot(): String = synchronized(logs) { logs.joinToString("\n") }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
        notifyChange()
    }

    private fun notifyChange() {
        handler.post { listener?.invoke() }
    }
}
