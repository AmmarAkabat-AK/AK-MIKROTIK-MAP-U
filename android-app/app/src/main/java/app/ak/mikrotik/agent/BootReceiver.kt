package app.ak.mikrotik.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** يشغّل الوكيل تلقائياً عند إقلاع الجهاز إذا فعّل المستخدم الخيار. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            if (Prefs.startOnBoot(context)) {
                val svc = Intent(context, AgentService::class.java).apply {
                    this.action = AgentService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }
        }
    }
}
