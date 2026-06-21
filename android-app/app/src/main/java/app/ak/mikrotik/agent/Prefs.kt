package app.ak.mikrotik.agent

import android.content.Context

/** إعدادات الوكيل المحفوظة محلياً. */
object Prefs {
    private const val FILE = "ak_agent_prefs"
    private const val KEY_URL = "backend_ws"
    private const val KEY_BOOT = "start_on_boot"
    private const val KEY_RUNNING = "should_run"

    const val DEFAULT_URL = "wss://ak-mikrotik-map-u.replit.app/api/agent/ws"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getUrl(ctx: Context): String = sp(ctx).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    fun setUrl(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_URL, url).apply()

    fun startOnBoot(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_BOOT, false)
    fun setStartOnBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_BOOT, v).apply()

    fun shouldRun(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)
    fun setShouldRun(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_RUNNING, v).apply()
}
