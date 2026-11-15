package com.getgua.client

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var etToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLog.init(this)
        Automation.init(this)
        TokenStore.init(this)

        tvService = findViewById(R.id.tvService)
        tvToken = findViewById(R.id.tvToken)
        tvRoot = findViewById(R.id.tvRoot)
        tvA11y = findViewById(R.id.tvA11y)
        tvBattery = findViewById(R.id.tvBattery)
        etToken = findViewById(R.id.etToken)

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
        findViewById<Button>(R.id.btnSaveToken).setOnClickListener {
            val t = etToken.text.toString().trim()
            if (t.length < 20) {
                Toast.makeText(this, "Token terlalu pendek (min 20 karakter)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread(isDaemon = true) {
                val ok = TokenStore.saveManualToken(t)
                runOnUiThread {
                    if (ok) {
                        etToken.setText("")
                        Toast.makeText(this, "Token manual tersimpan", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Gagal menyimpan token", Toast.LENGTH_SHORT).show()
                    }
                    refreshStatus()
                }
            }
        }
        findViewById<Button>(R.id.btnClearToken).setOnClickListener {
            thread(isDaemon = true) {
                val ok = TokenStore.clearManualToken()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "Token manual dihapus — kembali ke file Termux" else "Gagal menghapus token",
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshStatus()
                }
            }
        }
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
        // Cek token + root di thread latar (keduanya bisa memanggil `su`).
        thread(isDaemon = true) {
            val source = TokenStore.tokenSource()
            val rootOk = RootShell.isRootAvailable()
            runOnUiThread {
                tvToken.text = "Token: " + when (source) {
                    TokenStore.SOURCE_MANUAL -> "TERBACA (manual — input aplikasi)"
                    TokenStore.SOURCE_FILE -> "TERBACA (file Termux)"
                    else -> "TIDAK TERBACA — masukkan token manual di bawah"
                }
                tvRoot.text = "Root: " + if (rootOk) "tersedia (uid=0)" else "TIDAK TERSEDIA — izinkan root saat prompt muncul"
            }
        }
    }
}
