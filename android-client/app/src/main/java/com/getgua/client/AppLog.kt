package com.getgua.client

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log tindakan server (teks saja: waktu, aksi, hasil).
 * Tanpa token, tanpa screenshot. Dipakai endpoint /trace.
 * File diputar otomatis bila melewati ~200 KB.
 */
object AppLog {

    private const val MAX_BYTES = 200_000L
    private const val KEEP_LINES = 1000

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "getgua-client.log")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun log(action: String, result: String) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val lines = f.readLines()
                val kept = if (lines.size > KEEP_LINES) lines.takeLast(KEEP_LINES) else lines
                f.writeText(kept.joinToString("\n") + "\n")
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts $action -> $result\n")
        } catch (_: Exception) {
        }
    }

    fun tail(n: Int): List<String> {
        val f = logFile ?: return emptyList()
        return try {
            if (!f.exists()) emptyList() else f.readLines().takeLast(n)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
