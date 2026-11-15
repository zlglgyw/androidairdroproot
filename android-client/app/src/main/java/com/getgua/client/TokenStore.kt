package com.getgua.client

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * Token auth HTTP (header X-Token).
 *
 * Dua sumber token, sesuai urutan prioritas:
 * 1. Token MANUAL — diketik di UI aplikasi, disimpan di SharedPreferences
 *    privat aplikasi (MODE_PRIVATE, hanya aplikasi ini yang bisa baca).
 *    Ini jalan utama sejak v1.0.1: pembacaan file Termux dari UID
 *    aplikasi ternyata gagal di HP ini (15 Nov 2026 — baca file yang
 *    sama lewat shell Termux justru berhasil penuh, jadi masalahnya
 *    ada di sisi aplikasi, bukan di file/SELinux).
 * 2. File config Termux (`~/phone_config.json`) lewat root — fallback,
 *    dipertahankan supaya token tetap satu dengan phone_server bila
 *    pembacaan file bekerja.
 *
 * Token TIDAK PERNAH hardcode di aplikasi dan TIDAK PERNAH ditulis ke
 * repo (repo ini publik). Nilai token tidak pernah ditulis ke log —
 * log hanya mencatat status baca (ok/kode/error).
 *
 * Token file di-cache 60 detik supaya tiap permintaan HTTP tidak perlu
 * memanggil `su` lagi; token manual dibaca dari SharedPreferences
 * (sudah di-cache di memori oleh framework, jadi murah).
 */
object TokenStore {

    private const val CONFIG_PATH = "/data/data/com.termux/files/home/phone_config.json"
    private const val PREFS_NAME = "getgua_prefs"
    private const val KEY_MANUAL_TOKEN = "manual_token"
    private const val TTL_MS = 60_000L

    /** Sumber token terakhir yang berhasil dipakai. */
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_FILE = "file"

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedSource: String? = null
    @Volatile private var cachedPackages: Set<String> = emptySet()
    @Volatile private var loadedAt: Long = 0L

    /** Wajib dipanggil sekali (MainActivity / ClientService) sebelum getToken. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** Token manual tersimpan (null bila belum pernah diisi). */
    fun manualToken(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MANUAL_TOKEN, null)?.trim()?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun manualTokenSet(): Boolean = manualToken() != null

    /** Simpan token manual (dipakai UI). Menggantikan token file. */
    @Synchronized
    fun saveManualToken(token: String): Boolean {
        val clean = token.trim()
        if (clean.length < 20) return false
        val ctx = appContext ?: return false
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MANUAL_TOKEN, clean).apply()
            cachedToken = clean
            cachedSource = SOURCE_MANUAL
            loadedAt = SystemClock.uptimeMillis()
            AppLog.log("token", "manual token saved")
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Hapus token manual — kembali ke pembacaan file Termux. */
    @Synchronized
    fun clearManualToken(): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_MANUAL_TOKEN).apply()
            cachedToken = null
            cachedSource = null
            loadedAt = 0L
            AppLog.log("token", "manual token cleared")
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Token saat ini (null bila tidak ada sumber yang berhasil). */
    fun getToken(): String? {
        // Token manual selalu menang — tanpa TTL (SharedPreferences murah).
        manualToken()?.let {
            cachedToken = it
            cachedSource = SOURCE_MANUAL
            return it
        }
        if (cachedToken != null && cachedSource == SOURCE_FILE &&
            SystemClock.uptimeMillis() - loadedAt < TTL_MS
        ) {
            return cachedToken
        }
        return reload()
    }

    /** Sumber token saat ini: "manual" / "file" / null. */
    fun tokenSource(): String? {
        if (manualToken() != null) return SOURCE_MANUAL
        return if (getToken() != null) SOURCE_FILE else null
    }

    /** Daftar allowed_packages dari config (kosong = semua paket boleh). */
    fun allowedPackages(): Set<String> {
        getToken() // pastikan cache segar
        return cachedPackages
    }

    fun isConfigured(): Boolean = getToken() != null

    @Synchronized
    fun reload(): String? {
        manualToken()?.let {
            cachedToken = it
            cachedSource = SOURCE_MANUAL
            loadedAt = SystemClock.uptimeMillis()
            return it
        }
        val r = try {
            RootShell.run(listOf("cat", CONFIG_PATH), 10)
        } catch (e: Exception) {
            AppLog.log("token", "file read exception: ${(e.message ?: "").take(100)}")
            return null
        }
        if (!r.ok || r.stdout.isBlank()) {
            AppLog.log(
                "token",
                "file read failed: ok=${r.ok} code=${r.code} err=${r.stderr.trim().take(100)}",
            )
            cachedToken = null
            cachedSource = null
            cachedPackages = emptySet()
            return null
        }
        return try {
            val obj = JSONObject(r.stdout)
            val token = obj.optString("token", "").trim()
            val pkgs = mutableSetOf<String>()
            val arr = obj.optJSONArray("allowed_packages")
            if (arr != null) {
                for (i in 0 until arr.length()) pkgs.add(arr.optString(i))
            }
            cachedToken = token.ifEmpty { null }
            cachedSource = if (cachedToken != null) SOURCE_FILE else null
            cachedPackages = pkgs
            loadedAt = SystemClock.uptimeMillis()
            cachedToken
        } catch (e: Exception) {
            AppLog.log(
                "token",
                "file parse failed: ${(e.message ?: "").take(100)} (stdout ${r.stdout.length} chars)",
            )
            cachedToken = null
            cachedSource = null
            cachedPackages = emptySet()
            null
        }
    }

    /**
     * Ganti token di phone_config.json (dipakai endpoint /rotate_token).
     * Kunci config lain dipertahankan. CATATAN: phone_server.py hanya
     * membaca config saat start, jadi sesudah rotate, phone_server
     * (port 8765) tetap memakai token lama sampai di-restart — hanya
     * getgua client (port 8766) yang langsung memakai token baru.
     * Bila token manual sedang dipakai, token manual ikut diperbarui
     * supaya keduanya tetap sinkron.
     */
    @Synchronized
    fun rotate(newToken: String): Boolean {
        val current = try {
            val r = RootShell.run(listOf("cat", CONFIG_PATH), 10)
            if (r.ok && r.stdout.isNotBlank()) JSONObject(r.stdout) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        current.put("token", newToken)
        val bytes = current.toString(2).toByteArray(Charsets.UTF_8)
        val w = try {
            // Redirect `>` butuh string shell mentah; path adalah
            // konstanta internal, bukan input dari HTTP.
            RootShell.runRaw("cat > $CONFIG_PATH", bytes, 10)
        } catch (_: Exception) {
            return false
        }
        if (!w.ok) return false
        if (manualToken() != null) {
            saveManualToken(newToken)
        } else {
            cachedToken = newToken
            cachedSource = SOURCE_FILE
            loadedAt = SystemClock.uptimeMillis()
        }
        return true
    }
}
