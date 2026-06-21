package app.ak.mikrotik.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var requestCount: TextView
    private lateinit var serverUrl: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var bootSwitch: MaterialSwitch
    private lateinit var logView: TextView

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* تجاهل النتيجة */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        requestCount = findViewById(R.id.requestCount)
        serverUrl = findViewById(R.id.serverUrl)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        bootSwitch = findViewById(R.id.bootSwitch)
        logView = findViewById(R.id.logView)
        logView.movementMethod = ScrollingMovementMethod()

        serverUrl.setText(Prefs.getUrl(this))
        bootSwitch.isChecked = Prefs.startOnBoot(this)

        btnStart.setOnClickListener {
            ensureNotificationPermission()
            val url = serverUrl.text.toString().trim().ifEmpty { Prefs.DEFAULT_URL }
            Prefs.setUrl(this, url)
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_STOP
            }
            startService(intent)
        }

        bootSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStartOnBoot(this, isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        AgentState.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        AgentState.listener = null
    }

    private fun refresh() {
        requestCount.text = AgentState.requestCount.toString()
        logView.text = AgentState.snapshot()

        when {
            !AgentState.running -> {
                statusText.text = getString(R.string.status_stopped)
                statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.offline))
            }
            AgentState.connected -> {
                statusText.text = getString(R.string.status_connected)
                statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.online))
            }
            else -> {
                statusText.text = getString(R.string.status_disconnected)
                statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.amber))
            }
        }
        btnStart.isEnabled = !AgentState.running
        btnStop.isEnabled = AgentState.running
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
