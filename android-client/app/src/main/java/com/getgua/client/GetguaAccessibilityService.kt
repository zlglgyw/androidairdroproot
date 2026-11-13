package com.getgua.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jalur klik UTAMA getgua client.
 *
 * Masalah yang diselesaikan: koordinat dari UI dump kadang tidak
 * tepat (kasus nyata: tombol kirim Telegraph di dump bilang 617,1038
 * padahal tombol aslinya di 667,1036), tap kadang tidak bereaksi, dan
 * layout bergeser setelah keyboard muncul.
 *
 * Solusi:
 * 1. Klik node LANGSUNG via ACTION_CLICK — tanpa koordinat sama
 *    sekali, jadi tidak bisa meleset. Termasuk tap by text dan
 *    tap by resource-id.
 * 2. Tunggu UI idle sebelum aksi (waitForIdle): tap tidak dilakukan
 *    saat animasi/layout masih bergerak.
 * 3. Tap koordinat lewat dispatchGesture (API resmi, durasi tekan
 *    bisa diatur), bukan `input tap` instan.
 *
 * Service ini hanya aktif bila Pak mengaktifkannya di
 * Pengaturan > Aksesibilitas. Tanpa itu, Automation memakai jalur
 * fallback (uiautomator + koordinat via root) seperti phone_server.
 */
class GetguaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GetguaAccessibilityService? = null
            private set

        @Volatile private var lastEventUptime: Long = 0L

        fun isActive(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        lastEventUptime = SystemClock.uptimeMillis()
        AppLog.log("a11y", "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AppLog.log("a11y", "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Setiap event berarti UI masih beraktivitas.
        lastEventUptime = SystemClock.uptimeMillis()
    }

    /**
     * Tunggu sampai UI tenang: tidak ada event accessibility selama
     * [quietMs]. Mengembalikan true bila idle tercapai sebelum
     * [timeoutMs], false bila waktu habis (UI masih sibuk).
     */
    fun waitForIdle(timeoutMs: Long = 2000, quietMs: Long = 300): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (SystemClock.uptimeMillis() - lastEventUptime >= quietMs) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** Seluruh pohon UI jendela aktif sebagai daftar node. */
    fun getNodes(): List<UiNode> {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<UiNode>()
        try {
            traverse(root, out)
        } catch (_: Exception) {
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun traverse(node: AccessibilityNodeInfo, out: MutableList<UiNode>) {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
        }
        val cx = if (rect.isEmpty) null else (rect.left + rect.right) / 2
        val cy = if (rect.isEmpty) null else (rect.top + rect.bottom) / 2
        out.add(
            UiNode(
                text = node.text?.toString() ?: "",
                desc = node.contentDescription?.toString() ?: "",
                resource = node.viewIdResourceName ?: "",
                cls = node.className?.toString() ?: "",
                clickable = node.isClickable,
                x = cx,
                y = cy,
                selected = node.isSelected,
                checked = node.isChecked,
                focused = node.isFocused,
                enabled = node.isEnabled,
            ),
        )
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                traverse(child, out)
            } catch (_: Exception) {
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Cari node berdasarkan teks (substring, case-insensitive, cocok
     * ke text ATAU content-desc) atau resource-id (substring).
     * Node yang dikembalikan menjadi milik pemanggil (wajib recycle).
     */
    fun findNode(text: String?, resource: String?): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val found = try {
            search(root, text, resource)
        } catch (_: Exception) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        return found
    }

    private fun search(
        node: AccessibilityNodeInfo,
        text: String?,
        resource: String?,
    ): AccessibilityNodeInfo? {
        if (matches(node, text, resource)) return node
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = try {
                search(child, text, resource)
            } catch (_: Exception) {
                null
            }
            if (found != null) return found
            try {
                child.recycle()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, text: String?, resource: String?): Boolean {
        if (!resource.isNullOrEmpty()) {
            val id = node.viewIdResourceName ?: ""
            if (resource in id) return true
        }
        if (!text.isNullOrEmpty()) {
            val t = text.lowercase()
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (t in nodeText || t in nodeDesc) return true
        }
        return false
    }

    /**
     * Klik node langsung (tanpa koordinat). Bila node sendiri tidak
     * bisa diklik, naik ke leluhur terdekat yang bisa diklik.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard < 10) {
            guard++
            try {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
                return false
            }
            val parent = try {
                cur.parent
            } catch (_: Exception) {
                null
            }
            if (cur !== node) {
                try {
                    cur.recycle()
                } catch (_: Exception) {
                }
            }
            cur = parent
        }
        return false
    }

    /** Long-click node langsung (dipakai sebagai percobaan retry). */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard < 10) {
            guard++
            try {
                if (cur.isLongClickable) {
                    return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                if (cur.isClickable) {
                    // Tidak ada long-click di node ini; tahan lewat
                    // gesture di tengah bounds-nya sebagai gantinya.
                    val rect = Rect()
                    cur.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        return gestureTap(
                            ((rect.left + rect.right) / 2).toFloat(),
                            ((rect.top + rect.bottom) / 2).toFloat(),
                            500,
                        )
                    }
                }
            } catch (_: Exception) {
                return false
            }
            val parent = try {
                cur.parent
            } catch (_: Exception) {
                null
            }
            if (cur !== node) {
                try {
                    cur.recycle()
                } catch (_: Exception) {
                }
            }
            cur = parent
        }
        return false
    }

    /** Ketuk koordinat lewat gesture (durasi tekan bisa diatur). */
    fun gestureTap(x: Float, y: Float, durationMs: Long = 100): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchStroke(path, durationMs)
    }

    /** Geser lewat gesture (dipakai /swipe dan /scroll). */
    fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchStroke(path, durationMs)
    }

    private fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val ok = try {
            dispatchGesture(
                desc,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        result.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                },
                null,
            )
        } catch (_: Exception) {
            false
        }
        if (!ok) return false
        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return result.get()
    }

    /**
     * Ketik ke field yang sedang fokus via ACTION_SET_TEXT.
     * Jauh lebih andal daripada `input text` untuk karakter khusus,
     * spasi, dan unicode. Mengembalikan false bila tidak ada field
     * teks yang fokus (pemanggil lalu memakai fallback root).
     */
    fun setFocusedText(newText: String, append: Boolean): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        } ?: return false
        try {
            if (!focused.isEditable) return false
            val final = if (append) (focused.text?.toString() ?: "") + newText else newText
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    final,
                )
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                focused.recycle()
            } catch (_: Exception) {
            }
        }
    }
}
