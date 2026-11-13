package com.getgua.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Screenshot sebagai JPEG, bukan PNG.
 *
 * Alasan: screenshot PNG 720x1640 sekitar 324 KB — lambat dan sering
 * terpotong di jaringan HP yang lambat (3 dari 4 screenshot terbaru
 * worker MyAge korup). JPEG kualitas 70 hanya ~95 KB (3,4x lebih
 * kecil), kualitas 60 ~82 KB (3,9x), dan masih jelas dibaca.
 * Pengukuran nyata dari screenshot HP, 13 Nov 2026.
 *
 * Rantai fallback penangkapan:
 * 1. `screencap -p` tanpa root (murah dicoba; biasanya gagal karena
 *    UID aplikasi tidak punya izin framebuffer).
 * 2. `su -c screencap -p` (root).
 *
 * CATATAN FLAG_SECURE: aplikasi yang memasang FLAG_SECURE (MetaMask,
 * Bitget) tetap memblokir screenshot — root pun tidak menembusnya di
 * ROM ini (sudah terbukti di phone_server yang juga memakai su).
 * Untuk aplikasi itu, pakai /ui (UI dump tetap bekerja).
 */
object Screenshot {

    /** Tangkap layar sebagai bytes PNG, atau null bila gagal. */
    fun capturePng(): ByteArray? {
        // 1. Tanpa root.
        try {
            val plain = RootShell.runPlainBytes(listOf("screencap", "-p"), 15)
            if (plain.ok && isPng(plain.bytes)) return plain.bytes
        } catch (_: Exception) {
        }
        // 2. Via root.
        return try {
            val root = RootShell.runBytes(listOf("screencap", "-p"), 15)
            if (root.ok && isPng(root.bytes)) root.bytes else null
        } catch (_: Exception) {
            null
        }
    }

    /** Encode PNG menjadi JPEG. [maxWidth] opsional untuk memperkecil lebar. */
    fun toJpeg(png: ByteArray, quality: Int, maxWidth: Int?): ByteArray? {
        val bmp = try {
            BitmapFactory.decodeByteArray(png, 0, png.size)
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            val scaled = if (maxWidth != null && maxWidth > 0 && bmp.width > maxWidth) {
                val h = (bmp.height * maxWidth) / bmp.width
                Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
            } else {
                bmp
            }
            try {
                val out = ByteArrayOutputStream()
                val ok = scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    quality.coerceIn(1, 100),
                    out,
                )
                return if (ok) out.toByteArray() else null
            } finally {
                if (scaled !== bmp) {
                    try {
                        scaled.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            try {
                bmp.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun isPng(b: ByteArray): Boolean {
        return b.size > 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
    }
}
