package com.getgua.client

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Info perangkat untuk /health. Baterai dan status layar dibaca
 * lewat API Android (tanpa root, cepat); paket aplikasi depan dan
 * ukuran layar memakai root dumpsys/wm seperti phone_server.py.
 */
object DeviceInfo {

    fun batteryLevel(context: Context): Int? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            if (level >= 0) level else null
        } catch (_: Exception) {
            null
        }
    }

    fun screenOn(context: Context): Boolean? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Exception) {
            null
        }
    }

    /** Paket aplikasi yang sedang di depan (best-effort). */
    fun currentPackage(): String? {
        return try {
            val out = RootShell.run(listOf("dumpsys", "window", "windows"), 15)
            if (out.ok) {
                val m = Regex("""mCurrentFocus=Window\{[^}]*?u\d+\s+([A-Za-z0-9_.]+)/""").find(out.stdout)
                if (m != null) return m.groupValues[1]
            }
            val out2 = RootShell.run(listOf("dumpsys", "activity", "activities"), 15)
            if (out2.ok) {
                val m = Regex("""(?:topResumedActivity|ResumedActivity)[=:]\s*ActivityRecord\{[^}]*?u\d+\s+([A-Za-z0-9_.]+)/""")
                    .find(out2.stdout)
                if (m != null) return m.groupValues[1]
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Ukuran layar (lebar, tinggi); fallback 720x1640. */
    fun screenSize(context: Context): Pair<Int, Int> {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        } catch (_: Exception) {
        }
        return try {
            val out = RootShell.run(listOf("wm", "size"), 10)
            val m = Regex("""(\d+)\s*x\s*(\d+)""").find(out.stdout)
            if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt() else 720 to 1640
        } catch (_: Exception) {
            720 to 1640
        }
    }
}
