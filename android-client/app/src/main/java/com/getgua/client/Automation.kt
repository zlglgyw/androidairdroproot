package com.getgua.client

import android.content.Context

/**
 * Orkestrator semua aksi kendali HP.
 *
 * Prinsip klik anti-meleset (permintaan utama Pak):
 * 1. Tunggu UI idle dulu (settle) — tap tidak dilakukan saat
 *    animasi/layout masih bergerak (mis. sesudah keyboard muncul).
 * 2. Jalur utama: AccessibilityService (klik node langsung tanpa
 *    koordinat, atau dispatchGesture dengan durasi tekan).
 * 3. Verifikasi sesudah tap: bandingkan sidik jari UI sebelum vs
 *    sesudah. Kalau tidak berubah, ulangi dengan metode lain:
 *    coba lagi -> long-press -> fallback tap koordinat via root
 *    (tahan ~100ms, karena `input tap` instan kadang tak bereaksi).
 * 4. Respons selalu melaporkan metode yang dipakai, jumlah percobaan,
 *    dan apakah UI berubah — supaya agen tahu tap-nya berefek atau tidak.
 *
 * Parameter `verify` (default true) bisa dimatikan pemanggil bila
 * tap memang tidak diharapkan mengubah UI (mis. tap ke field yang
 * sudah fokus) — dengan begitu tidak ada tap ganda yang tidak perlu.
 */
object Automation {

    data class ActionResult(
        val ok: Boolean,
        val info: String,
        val method: String? = null,
        val attempts: Int = 0,
        val uiChanged: Boolean? = null,
    )

    /** Paket yang TIDAK BOLEH dimatikan lewat /stop_app. */
    private val PROTECTED_PACKAGES = setOf(
        "com.termux", // Termux: rumah phone_server + Hermes
        "com.tailscale.ipn", // Tailscale: satu-satunya jalan masuk jaringan
        "com.getgua.client", // diri sendiri
    )

    private var cacheDirPath: String = "/data/data/com.getgua.client/cache"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            cacheDirPath = context.cacheDir.absolutePath
        } catch (_: Exception) {
        }
    }

    private fun a11y(): GetguaAccessibilityService? = GetguaAccessibilityService.instance

    /** Tunggu UI tenang sebelum aksi (idle 300ms, maks 2 detik). */
    private fun settle() {
        val s = a11y()
        if (s != null) {
            s.waitForIdle(2000, 300)
        } else {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Daftar node layar + sumbernya ("accessibility" atau "uiautomator"). */
    fun nodes(): Pair<List<UiNode>, String> {
        val s = a11y()
        if (s != null) {
            try {
                val n = s.getNodes()
                if (n.isNotEmpty()) return n to "accessibility"
            } catch (_: Exception) {
            }
        }
        return UiNodes.dumpViaUiautomator(cacheDirPath) to "uiautomator"
    }

    private fun changedSince(before: String?): Boolean {
        if (before == null) return false
        return try {
            UiNodes.signature(nodes().first) != before
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Tap
    // ------------------------------------------------------------------

    fun tap(x: Int, y: Int, verify: Boolean = true, retries: Int = 2): ActionResult {
        settle()
        val before = if (verify) {
            try {
                UiNodes.signature(nodes().first)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        var attempts = 0
        val s = a11y()

        // Percobaan 1: gesture (100ms).
        if (s != null) {
            attempts++
            try {
                if (s.gestureTap(x.toFloat(), y.toFloat(), 100)) {
                    settle()
                    if (!verify) return ActionResult(true, "tap", "gesture", attempts, null)
                    if (changedSince(before)) {
                        return ActionResult(true, "tap", "gesture", attempts, true)
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Percobaan 2: gesture tahan lama (500ms).
        if (s != null && retries >= 1) {
            attempts++
            try {
                if (s.gestureTap(x.toFloat(), y.toFloat(), 500)) {
                    settle()
                    if (changedSince(before)) {
                        return ActionResult(true, "tap", "gesture_long", attempts, true)
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Percobaan 3: root, tap ditahan ~100ms (lebih andal daripada
        // `input tap` instan yang kadang tidak bereaksi).
        attempts++
        RootShell.run(
            listOf("input", "swipe", x.toString(), y.toString(), x.toString(), y.toString(), "100"),
            15,
        )
        settle()
        if (!verify) return ActionResult(true, "tap", "root_hold", attempts, null)
        if (changedSince(before)) return ActionResult(true, "tap", "root_hold", attempts, true)
        // Percobaan 4: root, `input tap` instan.
        if (retries >= 2) {
            attempts++
            RootShell.run(listOf("input", "tap", x.toString(), y.toString()), 15)
            settle()
            if (changedSince(before)) return ActionResult(true, "tap", "root_tap", attempts, true)
        }
        // Tap sudah dieksekusi, tapi UI tidak berubah — laporkan jujur.
        return ActionResult(true, "tap", if (s != null) "gesture" else "root_hold", attempts, false)
    }

    fun tapText(text: String, allowRisky: Boolean, verify: Boolean = true): ActionResult {
        if (UiNodes.isRisky(text) && !allowRisky) {
            return ActionResult(false, "blocked_risky")
        }
        settle()
        val before = if (verify) {
            try {
                UiNodes.signature(nodes().first)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val s = a11y()
        if (s != null) {
            val node = try {
                s.findNode(text = text, resource = null)
            } catch (_: Exception) {
                null
            }
            if (node != null) {
                try {
                    // Percobaan 1-2: ACTION_CLICK (langsung, tanpa koordinat).
                    repeat(2) { i ->
                        try {
                            if (s.clickNode(node)) {
                                settle()
                                if (!verify) {
                                    return ActionResult(true, "tap_text", "a11y_click", i + 1, null)
                                }
                                if (changedSince(before)) {
                                    return ActionResult(true, "tap_text", "a11y_click", i + 1, true)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    // Percobaan 3: long-click.
                    try {
                        if (s.longClickNode(node)) {
                            settle()
                            if (changedSince(before)) {
                                return ActionResult(true, "tap_text", "a11y_long_click", 3, true)
                            }
                        }
                    } catch (_: Exception) {
                    }
                } finally {
                    try {
                        node.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // Fallback: tap koordinat tengah node (dari UI dump).
        val (list, _) = nodes()
        val n = UiNodes.find(list, text = text, resource = null)
        if (n == null || n.x == null || n.y == null) {
            return ActionResult(false, "not_found")
        }
        val r = tap(n.x, n.y, verify = verify, retries = 1)
        return r.copy(info = "tap_text")
    }

    fun tapResource(resource: String, verify: Boolean = true): ActionResult {
        settle()
        val before = if (verify) {
            try {
                UiNodes.signature(nodes().first)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val s = a11y()
        if (s != null) {
            val node = try {
                s.findNode(text = null, resource = resource)
            } catch (_: Exception) {
                null
            }
            if (node != null) {
                try {
                    repeat(2) { i ->
                        try {
                            if (s.clickNode(node)) {
                                settle()
                                if (!verify) {
                                    return ActionResult(true, "tap_resource", "a11y_click", i + 1, null)
                                }
                                if (changedSince(before)) {
                                    return ActionResult(true, "tap_resource", "a11y_click", i + 1, true)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        if (s.longClickNode(node)) {
                            settle()
                            if (changedSince(before)) {
                                return ActionResult(true, "tap_resource", "a11y_long_click", 3, true)
                            }
                        }
                    } catch (_: Exception) {
                    }
                } finally {
                    try {
                        node.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val (list, _) = nodes()
        val n = UiNodes.find(list, text = null, resource = resource)
        if (n == null || n.x == null || n.y == null) {
            return ActionResult(false, "not_found")
        }
        val r = tap(n.x, n.y, verify = verify, retries = 1)
        return r.copy(info = "tap_resource")
    }

    // ------------------------------------------------------------------
    // Swipe / scroll / long press / double tap
    // ------------------------------------------------------------------

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): ActionResult {
        settle()
        val s = a11y()
        if (s != null) {
            try {
                if (s.gestureSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong())) {
                    settle()
                    return ActionResult(true, "swipe", "gesture", 1)
                }
            } catch (_: Exception) {
            }
        }
        val ok = RootShell.run(
            listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString()),
            15,
        ).ok
        settle()
        return ActionResult(ok, "swipe", "root", 1)
    }

    /**
     * Scroll pola Artemis (sama seperti phone_server): titik tengah
     * 60% lebar layar, jarak 70% -> 30% dalam ~800ms.
     */
    fun scroll(direction: String): ActionResult {
        val ctx = appContext ?: return ActionResult(false, "no_context")
        val (w, h) = DeviceInfo.screenSize(ctx)
        val x = (w * 0.6).toInt()
        val y = (h * 0.5).toInt()
        val coords = when (direction.lowercase()) {
            "down" -> listOf(x, (h * 0.7).toInt(), x, (h * 0.3).toInt())
            "up" -> listOf(x, (h * 0.3).toInt(), x, (h * 0.7).toInt())
            "left" -> listOf((w * 0.7).toInt(), y, (w * 0.3).toInt(), y)
            "right" -> listOf((w * 0.3).toInt(), y, (w * 0.7).toInt(), y)
            else -> return ActionResult(false, "bad_direction")
        }
        val r = swipe(coords[0], coords[1], coords[2], coords[3], 800)
        return r.copy(info = "scroll")
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 1000): ActionResult {
        settle()
        val s = a11y()
        if (s != null) {
            try {
                if (s.gestureTap(x.toFloat(), y.toFloat(), durationMs.toLong())) {
                    settle()
                    return ActionResult(true, "long_press", "gesture", 1)
                }
            } catch (_: Exception) {
            }
        }
        val ok = RootShell.run(
            listOf("input", "swipe", x.toString(), y.toString(), x.toString(), y.toString(), durationMs.toString()),
            15,
        ).ok
        settle()
        return ActionResult(ok, "long_press", "root", 1)
    }

    fun doubleTap(x: Int, y: Int): ActionResult {
        settle()
        val s = a11y()
        if (s != null) {
            try {
                val ok1 = s.gestureTap(x.toFloat(), y.toFloat(), 80)
                Thread.sleep(200)
                val ok2 = s.gestureTap(x.toFloat(), y.toFloat(), 80)
                settle()
                return ActionResult(ok1 && ok2, "double_tap", "gesture", 1)
            } catch (_: Exception) {
            }
        }
        val ok1 = RootShell.run(listOf("input", "tap", x.toString(), y.toString()), 15).ok
        Thread.sleep(200)
        val ok2 = RootShell.run(listOf("input", "tap", x.toString(), y.toString()), 15).ok
        settle()
        return ActionResult(ok1 && ok2, "double_tap", "root", 1)
    }

    // ------------------------------------------------------------------
    // Type / key / launch / stop_app / wait
    // ------------------------------------------------------------------

    fun type(text: String, clear: Boolean, enter: Boolean): ActionResult {
        var typed = false
        val s = a11y()
        if (s != null) {
            // ACTION_SET_TEXT menangani karakter khusus/spasi/unicode
            // yang sering rusak lewat `input text`.
            typed = try {
                s.setFocusedText(text, append = !clear)
            } catch (_: Exception) {
                false
            }
        }
        if (!typed) {
            if (clear) {
                RootShell.run(listOf("input", "keyevent", "123"), 15) // MOVE_END
                val dels = listOf("input", "keyevent") + List(50) { "67" } // DEL x50
                RootShell.run(dels, 15)
            }
            val escaped = text.replace(" ", "%s")
            typed = RootShell.run(listOf("input", "text", escaped), 30).ok
        }
        if (enter) {
            RootShell.run(listOf("input", "keyevent", "66"), 15) // ENTER
        }
        settle()
        return ActionResult(typed, "type")
    }

    fun key(keyCode: Int): ActionResult {
        val ok = RootShell.run(listOf("input", "keyevent", keyCode.toString()), 15).ok
        settle()
        return ActionResult(ok, "key")
    }

    fun launch(pkg: String): ActionResult {
        val allowed = TokenStore.allowedPackages()
        if (allowed.isNotEmpty() && pkg !in allowed) {
            return ActionResult(false, "blocked_not_allowed_package")
        }
        val ok = RootShell.run(
            listOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"),
            15,
        ).ok
        settle()
        return ActionResult(ok, "launch")
    }

    /**
     * Matikan aplikasi. `am force-stop` TIDAK PERNAH bekerja di ROM
     * XOS ini (Binder "Failed transaction"), jadi: coba force-stop,
     * verifikasi via root pidof hingga 10 detik, lalu SIGKILL semua
     * PID dan verifikasi lagi. Verifikasi HARUS lewat root pidof:
     * pidof tanpa root buta terhadap proses aplikasi lain (hidepid).
     */
    fun stopApp(pkg: String): ActionResult {
        if (pkg in PROTECTED_PACKAGES) {
            return ActionResult(false, "blocked_protected_package")
        }
        val allowed = TokenStore.allowedPackages()
        if (allowed.isNotEmpty() && pkg !in allowed) {
            return ActionResult(false, "blocked_not_allowed_package")
        }
        RootShell.run(listOf("am", "force-stop", pkg), 15)
        repeat(20) {
            Thread.sleep(500)
            val out = RootShell.run(listOf("pidof", pkg), 10).stdout
            if (out.isBlank()) return ActionResult(true, "stop_app")
        }
        val pids = RootShell.run(listOf("pidof", pkg), 10).stdout
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        for (pid in pids) {
            RootShell.run(listOf("kill", "-9", pid), 10)
        }
        Thread.sleep(500)
        val still = RootShell.run(listOf("pidof", pkg), 10).stdout.isNotBlank()
        return ActionResult(!still, "stop_app")
    }

    fun wait(seconds: Double): ActionResult {
        try {
            Thread.sleep((seconds * 1000).toLong().coerceIn(0, 60_000))
        } catch (_: InterruptedException) {
        }
        return ActionResult(true, "wait")
    }
}
