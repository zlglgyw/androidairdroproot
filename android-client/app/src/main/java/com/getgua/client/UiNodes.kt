package com.getgua.client

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Model satu elemen UI di layar. Bentuk JSON-nya disamakan persis
 * dengan phone_server.py supaya perkakas agen (ps.sh) tidak perlu
 * berubah: {text, desc, resource, class, clickable, x, y}.
 */
data class UiNode(
    val text: String,
    val desc: String,
    val resource: String,
    val cls: String,
    val clickable: Boolean,
    val x: Int?,
    val y: Int?,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("desc", desc)
        put("resource", resource)
        put("class", cls)
        put("clickable", clickable)
        if (x != null) put("x", x) else put("x", JSONObject.NULL)
        if (y != null) put("y", y) else put("y", JSONObject.NULL)
    }
}

object UiNodes {

    // Disalin dari phone_server.py — ketukan ke tombol dengan kata
    // ini DITOLAK server, kecuali permintaan membawa allow_risky.
    private val RISKY_WORDS = listOf(
        "beli", "buy", "deposit", "bayar", "pay", "payment",
        "kirim uang", "send money", "transfer", "withdraw",
        "connect wallet", "hubungkan dompet", "approve", "setujui",
        "sign", "tanda tangan", "confirm payment", "konfirmasi pembayaran",
    )

    private val CAPTCHA_WORDS = listOf(
        "captcha", "i'm not a robot", "i am not a robot",
        "verifikasi manusia", "human verification", "prove you are human",
        "pilih gambar", "select images",
    )

    fun isRisky(text: String): Boolean {
        val t = text.lowercase()
        return RISKY_WORDS.any { it in t }
    }

    fun isCaptcha(nodes: List<UiNode>): Boolean {
        val all = nodes.joinToString(" ") { (it.text + " " + it.desc).lowercase() }
        return CAPTCHA_WORDS.any { it in all }
    }

    /** Cari node berdasarkan teks (substring, case-insensitive) atau resource-id. */
    fun find(nodes: List<UiNode>, text: String?, resource: String?): UiNode? {
        val t = (text ?: "").lowercase()
        for (n in nodes) {
            if (!resource.isNullOrEmpty() && resource in n.resource) return n
            if (t.isNotEmpty() && (t in n.text.lowercase() || t in n.desc.lowercase())) return n
        }
        return null
    }

    /**
     * Sidik jari pohon UI — dipakai verifikasi sesudah tap: kalau
     * sidik jari berubah, tap dianggap berefek. Mencakup teks, deskripsi,
     * kelas, status klik, koordinat, serta state selected/checked/focused
     * (supaya toggle yang berubah state ikut terdeteksi).
     */
    fun signature(nodes: List<UiNode>): String {
        val sb = StringBuilder(nodes.size * 32)
        for (n in nodes) {
            sb.append(n.text).append('|').append(n.desc).append('|')
                .append(n.cls).append('|').append(n.clickable).append('|')
                .append(n.x).append(',').append(n.y).append('|')
                .append(n.selected).append(n.checked).append(n.focused)
                .append(';')
        }
        return sb.toString().hashCode().toString(16)
    }

    /**
     * Dump UI lewat `uiautomator dump` (root) — jalur FALLBACK bila
     * AccessibilityService tidak aktif. Sama seperti phone_server.py.
     */
    fun dumpViaUiautomator(cacheDirPath: String): List<UiNode> {
        val xmlPath = "$cacheDirPath/window_dump.xml"
        val r = try {
            RootShell.runRaw("uiautomator dump $xmlPath && cat $xmlPath", null, 30)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!r.ok || r.stdout.isBlank()) return emptyList()
        // Hapus file dump supaya tidak menumpuk.
        try {
            RootShell.run(listOf("rm", "-f", xmlPath), 10)
        } catch (_: Exception) {
        }
        return try {
            parseXml(r.stdout)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parse XML hasil uiautomator dump menjadi daftar node. */
    fun parseXml(xml: String): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        val boundsRe = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                var cx: Int? = null
                var cy: Int? = null
                val bounds = parser.getAttributeValue(null, "bounds") ?: ""
                val m = boundsRe.find(bounds)
                if (m != null) {
                    val (x1, y1, x2, y2) = m.destructured
                    cx = (x1.toInt() + x2.toInt()) / 2
                    cy = (y1.toInt() + y2.toInt()) / 2
                }
                nodes.add(
                    UiNode(
                        text = parser.getAttributeValue(null, "text") ?: "",
                        desc = parser.getAttributeValue(null, "content-desc") ?: "",
                        resource = parser.getAttributeValue(null, "resource-id") ?: "",
                        cls = parser.getAttributeValue(null, "class") ?: "",
                        clickable = parser.getAttributeValue(null, "clickable") == "true",
                        x = cx,
                        y = cy,
                        selected = parser.getAttributeValue(null, "selected") == "true",
                        checked = parser.getAttributeValue(null, "checked") == "true",
                        focused = parser.getAttributeValue(null, "focused") == "true",
                        enabled = parser.getAttributeValue(null, "enabled") != "false",
                    ),
                )
            }
            event = parser.next()
        }
        return nodes
    }
}
