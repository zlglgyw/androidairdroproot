package com.getgua.client

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Routing endpoint HTTP. Cerminan phone_server v3 (endpoint, bentuk
 * JSON, dan kode info yang sama) dengan tiga perbedaan:
 * - /screenshot dan /screen mengembalikan JPEG (default), bukan PNG.
 * - Respons tap membawa `method`, `attempts`, dan `ui_changed`.
 * - /health membawa status accessibility + root + versi aplikasi.
 *
 * Semua permintaan wajib header X-Token (nilai dari
 * phone_config.json Termux). Aksi tunggal adalah POST dengan
 * body JSON, seperti phone_server.
 */
class Router(private val context: Context) {

    companion object {
        const val APP_VERSION = "1.0.0"
        const val REC_FILE = "/sdcard/phone_rec.mp4"
    }

    private val startedAt = SystemClock.uptimeMillis()
    private var recProc: Process? = null

    fun handle(req: HttpServer.Request): HttpServer.Response {
        // --- Auth: semua endpoint wajib token. ---
        val token = TokenStore.getToken()
        if (token == null) {
            return jsonErr(403, "token_not_configured")
        }
        if (req.headers["x-token"] != token) {
            return jsonErr(403, "forbidden")
        }

        val body = try {
            if (req.body.isEmpty()) JSONObject()
            else JSONObject(req.body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }

        return when (req.method) {
            "GET" -> handleGet(req)
            "POST" -> handlePost(req, body)
            else -> jsonErr(404, "not found")
        }
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    private fun handleGet(req: HttpServer.Request): HttpServer.Response {
        return when (req.path) {
            "/ping" -> jsonOk(JSONObject().put("ok", true).put("version", APP_VERSION))
            "/health" -> health()
            "/screenshot" -> screenshot(req)
            "/screen" -> screen(req)
            "/ocr" -> ocr()
            "/recording" -> recording()
            "/trace" -> trace()
            "/logcat" -> logcat(req)
            "/ui" -> ui()
            else -> jsonErr(404, "not found")
        }
    }

    private fun health(): HttpServer.Response {
        val obj = JSONObject()
            .put("ok", true)
            .put("version", APP_VERSION)
            .put("battery_level", DeviceInfo.batteryLevel(context) ?: JSONObject.NULL)
            .put("uptime_seconds", (SystemClock.uptimeMillis() - startedAt) / 1000)
            .put("current_package", DeviceInfo.currentPackage() ?: JSONObject.NULL)
            .put("screen_on", DeviceInfo.screenOn(context) ?: JSONObject.NULL)
            .put("accessibility_active", GetguaAccessibilityService.isActive())
            .put("root_available", RootShell.isRootAvailable())
            .put("token_configured", TokenStore.isConfigured())
        return jsonOk(obj)
    }

    private fun screenshot(req: HttpServer.Request): HttpServer.Response {
        val quality = req.query["quality"]?.toIntOrNull() ?: 70
        val width = req.query["width"]?.toIntOrNull()
        val format = (req.query["format"] ?: "jpeg").lowercase()
        val png = Screenshot.capturePng()
        if (png == null) {
            AppLog.log("screenshot", "failed")
            return jsonErr(500, "screencap failed")
        }
        if (format == "png") {
            AppLog.log("screenshot", "ok png ${png.size} bytes")
            return HttpServer.Response(200, "image/png", png)
        }
        val jpeg = Screenshot.toJpeg(png, quality, width)
            ?: return jsonErr(500, "jpeg encode failed")
        AppLog.log("screenshot", "ok jpeg q$quality ${jpeg.size} bytes")
        return HttpServer.Response(200, "image/jpeg", jpeg)
    }

    private fun screen(req: HttpServer.Request): HttpServer.Response {
        val quality = req.query["quality"]?.toIntOrNull() ?: 70
        val width = req.query["width"]?.toIntOrNull()
        val png = Screenshot.capturePng()
            ?: return jsonErr(500, "screencap failed")
        val jpeg = Screenshot.toJpeg(png, quality, width)
            ?: return jsonErr(500, "jpeg encode failed")
        val (w, h) = DeviceInfo.screenSize(context)
        val (nodes, source) = Automation.nodes()
        val arr = JSONArray()
        for (n in nodes) arr.put(n.toJson())
        AppLog.log("screen", "${nodes.size} nodes via $source")
        return jsonOk(
            JSONObject()
                .put("ok", true)
                .put("screenshot", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .put("format", "jpeg")
                .put("nodes", arr)
                .put("ui_source", source)
                .put("captcha_detected", UiNodes.isCaptcha(nodes))
                .put("width", w)
                .put("height", h),
        )
    }

    private fun ui(): HttpServer.Response {
        val (nodes, source) = Automation.nodes()
        val arr = JSONArray()
        for (n in nodes) arr.put(n.toJson())
        AppLog.log("ui", "${nodes.size} nodes via $source")
        return jsonOk(
            JSONObject()
                .put("ok", true)
                .put("nodes", arr)
                .put("ui_source", source)
                .put("captcha_detected", UiNodes.isCaptcha(nodes)),
        )
    }

    private fun ocr(): HttpServer.Response {
        val png = Screenshot.capturePng()
            ?: return jsonErr(500, "screencap failed")
        val shot = File(context.cacheDir, "ocr_shot.png")
        return try {
            shot.writeBytes(png)
            // Cari tesseract: PATH root belum tentu mencakup bin Termux.
            var tess: String? = null
            if (RootShell.run(listOf("which", "tesseract"), 10).ok) {
                tess = "tesseract"
            } else if (RootShell.run(
                    listOf("test", "-x", "/data/data/com.termux/files/usr/bin/tesseract"),
                    10,
                ).ok
            ) {
                tess = "/data/data/com.termux/files/usr/bin/tesseract"
            }
            if (tess == null) {
                return jsonOk(
                    JSONObject()
                        .put("ok", false)
                        .put("error", "tesseract_not_installed")
                        .put("hint", "pkg install tesseract"),
                )
            }
            val r = RootShell.run(listOf(tess, shot.absolutePath, "stdout", "--psm", "6"), 60)
            if (!r.ok) {
                return jsonOk(
                    JSONObject()
                        .put("ok", false)
                        .put("error", "ocr_failed")
                        .put("detail", r.stderr.trim().take(200)),
                )
            }
            AppLog.log("ocr", "${r.stdout.length} chars")
            jsonOk(JSONObject().put("ok", true).put("text", r.stdout))
        } catch (e: Exception) {
            jsonOk(
                JSONObject()
                    .put("ok", false)
                    .put("error", "ocr_failed")
                    .put("detail", (e.message ?: "").take(200)),
            )
        } finally {
            try {
                shot.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun recording(): HttpServer.Response {
        // File video di /sdcard tidak bisa dibaca langsung aplikasi
        // (scoped storage) — baca lewat root cat.
        val r = RootShell.runBytes(listOf("cat", REC_FILE), 60)
        if (!r.ok || r.bytes.isEmpty()) {
            return jsonErr(404, "no_recording")
        }
        AppLog.log("recording", "${r.bytes.size} bytes")
        return HttpServer.Response(200, "video/mp4", r.bytes)
    }

    private fun trace(): HttpServer.Response {
        val arr = JSONArray()
        for (line in AppLog.tail(50)) arr.put(line)
        return jsonOk(JSONObject().put("ok", true).put("lines", arr))
    }

    private fun logcat(req: HttpServer.Request): HttpServer.Response {
        val n = (req.query["n"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val r = RootShell.run(listOf("logcat", "-d", "-t", n.toString()), 30)
        if (!r.ok) {
            return jsonOk(JSONObject().put("ok", false).put("error", "logcat_failed"))
        }
        val arr = JSONArray()
        for (line in r.stdout.split("\n")) arr.put(line)
        return jsonOk(JSONObject().put("ok", true).put("lines", arr))
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    private fun handlePost(req: HttpServer.Request, body: JSONObject): HttpServer.Response {
        return when (req.path) {
            "/rotate_token" -> rotateToken(body)
            "/record/start" -> recordStart(body)
            "/record/stop" -> recordStop()
            "/tap" -> action("tap") {
                Automation.tap(
                    body.optInt("x"),
                    body.optInt("y"),
                    verify = body.optBoolean("verify", true),
                    retries = body.optInt("retries", 2),
                )
            }
            "/tap_text" -> action("tap_text") {
                Automation.tapText(
                    body.optString("text", ""),
                    allowRisky = body.optBoolean("allow_risky", false),
                    verify = body.optBoolean("verify", true),
                )
            }
            "/tap_resource" -> action("tap_resource") {
                Automation.tapResource(
                    body.optString("resource", ""),
                    verify = body.optBoolean("verify", true),
                )
            }
            "/swipe" -> action("swipe") {
                Automation.swipe(
                    body.optInt("x1"),
                    body.optInt("y1"),
                    body.optInt("x2"),
                    body.optInt("y2"),
                    body.optInt("duration", 300),
                )
            }
            "/scroll" -> action("scroll") {
                Automation.scroll(body.optString("direction", ""))
            }
            "/long_press" -> action("long_press") {
                Automation.longPress(
                    body.optInt("x"),
                    body.optInt("y"),
                    body.optInt("duration", 1000),
                )
            }
            "/double_tap" -> action("double_tap") {
                Automation.doubleTap(body.optInt("x"), body.optInt("y"))
            }
            "/type" -> action("type") {
                Automation.type(
                    body.optString("text", ""),
                    body.optBoolean("clear", false),
                    body.optBoolean("enter", false),
                )
            }
            "/key" -> action("key") {
                Automation.key(body.optInt("key"))
            }
            "/launch" -> action("launch") {
                Automation.launch(body.optString("package", ""))
            }
            "/stop_app" -> action("stop_app") {
                Automation.stopApp(body.optString("package", ""))
            }
            "/wait" -> action("wait") {
                Automation.wait(body.optDouble("seconds", 1.0))
            }
            else -> jsonErr(404, "not found")
        }
    }

    private fun action(name: String, block: () -> Automation.ActionResult): HttpServer.Response {
        val r = try {
            block()
        } catch (e: Exception) {
            AppLog.log(name, "exception ${(e.message ?: "").take(100)}")
            return jsonOk(JSONObject().put("ok", false).put("info", "exception"))
        }
        AppLog.log(name, "${r.info} ok=${r.ok} method=${r.method} attempts=${r.attempts} changed=${r.uiChanged}")
        val obj = JSONObject()
            .put("ok", r.ok)
            .put("info", r.info)
        if (r.method != null) obj.put("method", r.method)
        obj.put("attempts", r.attempts)
        if (r.uiChanged != null) obj.put("ui_changed", r.uiChanged)
        return jsonOk(obj)
    }

    private fun rotateToken(body: JSONObject): HttpServer.Response {
        val newToken = body.optString("new_token", "")
        if (newToken.length < 20) {
            return jsonErr(400, "token too short")
        }
        val ok = TokenStore.rotate(newToken)
        AppLog.log("rotate_token", "ok=$ok")
        return jsonOk(JSONObject().put("ok", ok))
    }

    private fun recordStart(body: JSONObject): HttpServer.Response {
        val n = body.optInt("time_limit", 30).coerceIn(1, 180)
        val proc = recProc
        if (proc != null && proc.isAlive) {
            return jsonOk(JSONObject().put("ok", false).put("error", "already_recording"))
        }
        val p = RootShell.startBackground(
            listOf("screenrecord", "--time-limit", n.toString(), REC_FILE),
        ) ?: return jsonErr(500, "record_start_failed")
        recProc = p
        AppLog.log("record_start", "time_limit=$n")
        return jsonOk(
            JSONObject()
                .put("ok", true)
                .put("recording", true)
                .put("time_limit", n),
        )
    }

    private fun recordStop(): HttpServer.Response {
        RootShell.run(listOf("pkill", "-f", "screenrecord"), 10)
        val proc = recProc
        if (proc != null) {
            val deadline = SystemClock.uptimeMillis() + 3000
            while (proc.isAlive && SystemClock.uptimeMillis() < deadline) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
            recProc = null
        }
        val size = try {
            val r = RootShell.run(listOf("stat", "-c", "%s", REC_FILE), 10)
            if (r.ok) r.stdout.trim().toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
        AppLog.log("record_stop", "size=$size")
        val obj = JSONObject().put("ok", true).put("file", REC_FILE)
        if (size != null) obj.put("size_bytes", size) else obj.put("size_bytes", JSONObject.NULL)
        return jsonOk(obj)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jsonOk(obj: JSONObject): HttpServer.Response {
        return HttpServer.Response(200, "application/json", obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun jsonErr(code: Int, error: String): HttpServer.Response {
        val obj = JSONObject().put("ok", false).put("error", error)
        return HttpServer.Response(code, "application/json", obj.toString().toByteArray(Charsets.UTF_8))
    }
}
