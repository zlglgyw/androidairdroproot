package com.getgua.client

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * UI setup minimal: status + tombol sekali jalan.
 * Pemakaian sehari-hari lewat HTTP, bukan lewat UI ini.
 */
class MainActivity : Activity() {

    private lateinit var tvService: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvRoot: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvBattery: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLog.init(this)
        Automation.init(this)

        tvService = findViewById(R.id.tvService)
        tvToken = findViewById(R.id.tvToken)
        tvRoot = findViewById(R.id.tvRoot)
        tvA11y = findViewById(R.id.tvA11y)
        tvBattery = findViewById(R.id.tvBattery)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startForegroundService(Intent(this, ClientService::class.java))
            refreshStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, ClientService::class.java).setAction(ClientService.ACTION_STOP))
            refreshStatus()
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        tvService.text = "Service: " + if (ClientService.running) "JALAN (port 8766)" else "berhenti"
        tvA11y.text = "Aksesibilitas: " +
            if (GetguaAccessibilityService.isActive()) "AKTIF" else "tidak aktif — aktifkan di Pengaturan > Aksesibilitas"
        tvBattery.text = "Baterai: " + (DeviceInfo.batteryLevel(this)?.let { "$it%" } ?: "…")
        tvToken.text = "Token: memeriksa…"
        tvRoot.text = "Root: memeriksa…"
        // Cek token + root di thread latar (keduanya memanggil `su`).
        thread(isDaemon = true) {
            val tokenOk = TokenStore.isConfigured()
            val rootOk = RootShell.isRootAvailable()
            runOnUiThread {
                tvToken.text = "Token: " + if (tokenOk) "terbaca dari phone_config.json" else "TIDAK TERBACA — cek Termux/phone_config.json"
                tvRoot.text = "Root: " + if (rootOk) "tersedia (uid=0)" else "TIDAK TERSEDIA — izinkan root saat prompt muncul"
            }
        }
    }
}
