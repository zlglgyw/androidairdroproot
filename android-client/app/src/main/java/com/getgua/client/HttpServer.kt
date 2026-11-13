package com.getgua.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Server HTTP minimal hand-rolled (tanpa dependensi): cukup untuk
 * GET/POST + header X-Token + body JSON + respons biner (JPEG/MP4).
 * Satu koneksi = satu permintaan, lalu ditutup (Connection: close)
 * — cukup untuk pola pakai curl/ps.sh.
 */
class HttpServer(
    private val port: Int,
    private val router: Router,
) {

    data class Response(val code: Int, val contentType: String, val body: ByteArray)

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
        running = true
        acceptThread = thread(name = "http-accept", isDaemon = true) {
            while (running) {
                try {
                    val socket = serverSocket!!.accept()
                    pool.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!running) return@thread
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        AppLog.log("http", "listening on 0.0.0.0:$port")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
        AppLog.log("http", "stopped")
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 120_000
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                // Baca header sampai \r\n\r\n (maks 16 KB).
                val headerBuf = ByteArrayOutputStream()
                val tail = ByteArray(4)
                var tailLen = 0
                var total = 0
                while (total < 16 * 1024) {
                    val b = input.read()
                    if (b < 0) return
                    headerBuf.write(b)
                    total++
                    tail[0] = tail[1]
                    tail[1] = tail[2]
                    tail[2] = tail[3]
                    tail[3] = b.toByte()
                    tailLen++
                    if (tailLen >= 4 && tail[0] == 13.toByte() && tail[1] == 10.toByte() &&
                        tail[2] == 13.toByte() && tail[3] == 10.toByte()
                    ) {
                        break
                    }
                }
                val headerText = headerBuf.toString(Charsets.UTF_8.name())
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) return
                val requestLine = lines[0].split(" ")
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase()
                val rawTarget = requestLine[1]

                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                val qIdx = rawTarget.indexOf('?')
                val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
                val query = HashMap<String, String>()
                if (qIdx >= 0) {
                    val qs = rawTarget.substring(qIdx + 1)
                    for (pair in qs.split('&')) {
                        if (pair.isEmpty()) continue
                        val eq = pair.indexOf('=')
                        if (eq >= 0) {
                            query[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
                        } else {
                            query[decode(pair)] = ""
                        }
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(contentLength.coerceIn(0, 10 * 1024 * 1024))
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val bodyBytes = if (read < body.size) body.copyOf(read) else body

                val response = try {
                    router.handle(Request(method, path, query, headers, bodyBytes))
                } catch (e: Exception) {
                    Response(
                        500,
                        "application/json",
                        ("{\"ok\":false,\"error\":\"internal: " +
                            (e.message ?: "error").take(200).replace("\"", "'") + "\"}").toByteArray(Charsets.UTF_8),
                    )
                }

                val reason = when (response.code) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    else -> "Internal Server Error"
                }
                val head = "HTTP/1.1 ${response.code} $reason\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${response.body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(head.toByteArray(Charsets.UTF_8))
                output.write(response.body)
                output.flush()
            } catch (_: Exception) {
                // Koneksi terputus / timeout — tidak ada yang bisa dilakukan.
            }
        }
    }

    private fun decode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
