package com.getgua.client

import android.os.SystemClock
import org.json.JSONObject

/**
 * Token auth HTTP (header X-Token).
 *
 * Token TIDAK PERNAH hardcode di aplikasi dan TIDAK PERNAH ditulis
 * ke file aplikasi sendiri — nilainya dibaca dari file config yang
 * sama dengan phone_server (`~/phone_config.json` di Termux) lewat
 * root. Dengan begitu satu token berlaku untuk kedua server, dan
 * repo publik ini tetap bersih dari secret.
 *
 * Nilai token di-cache 60 detik supaya tiap permintaan HTTP tidak
 * perlu memanggil `su` lagi.
 */
object TokenStore {

    private const val CONFIG_PATH = "/data/data/com.termux/files/home/phone_config.json"
    private const val TTL_MS = 60_000L

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedPackages: Set<String> = emptySet()
    @Volatile private var loadedAt: Long = 0L

    /** Token saat ini (null bila config tidak terbaca / token kosong). */
    fun getToken(): String? {
        if (cachedToken != null && SystemClock.uptimeMillis() - loadedAt < TTL_MS) {
            return cachedToken
        }
        return reload()
    }

    /** Daftar allowed_packages dari config (kosong = semua paket boleh). */
    fun allowedPackages(): Set<String> {
        getToken() // pastikan cache segar
        return cachedPackages
    }

    fun isConfigured(): Boolean = getToken() != null

    @Synchronized
    fun reload(): String? {
        val r = try {
            RootShell.run(listOf("cat", CONFIG_PATH), 10)
        } catch (_: Exception) {
            return null
        }
        if (!r.ok || r.stdout.isBlank()) {
            cachedToken = null
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
            cachedPackages = pkgs
            loadedAt = SystemClock.uptimeMillis()
            cachedToken
        } catch (_: Exception) {
            cachedToken = null
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
        cachedToken = newToken
        loadedAt = SystemClock.uptimeMillis()
        return true
    }
}
