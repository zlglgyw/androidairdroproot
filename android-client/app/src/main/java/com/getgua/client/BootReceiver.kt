package com.getgua.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nyalakan service otomatis sesudah HP reboot.
 *
 * Catatan: receiver ini baru bekerja sesudah Pak membuka aplikasi
 * sekali (Android tidak mengirim BOOT_COMPLETED ke aplikasi yang
 * belum pernah dibuka).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                context.startForegroundService(Intent(context, ClientService::class.java))
            } catch (_: Exception) {
                // Diabaikan: service bisa dinyalakan manual dari aplikasi.
            }
        }
    }
}
