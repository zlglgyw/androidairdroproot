package com.getgua.client

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Eksekusi perintah shell, sebagian besar sebagai root via `su -c`.
 *
 * Semua perintah perangkat (screencap, input, uiautomator, dumpsys,
 * pidof, kill, tesseract, logcat, screenrecord) berjalan lewat sini,
 * sama seperti phone_server.py. Argumen dikutip gaya shell supaya
 * karakter khusus (tanda kutip, ;, $) tidak merusak perintah.
 */
object RootShell {

    data class Result(val ok: Boolean, val stdout: String, val stderr: String, val code: Int)
    data class BytesResult(val ok: Boolean, val bytes: ByteArray, val stderr: String, val code: Int)

    /** Kutip satu argumen gaya shell (mirip shlex.quote Python). */
    fun quote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_@%+=:,./-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }

    /** Jalankan perintah sebagai root via `su -c`. Stdout sebagai teks. */
    fun run(args: List<String>, timeoutSec: Int = 30): Result {
        val (code, out, err) = exec(listOf("su", "-c", join(args)), null, timeoutSec)
        return Result(code == 0, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), code)
    }

    /** Seperti [run], tapi stdout sebagai bytes mentah (untuk screenshot/video). */
    fun runBytes(args: List<String>, timeoutSec: Int = 30): BytesResult {
        val (code, out, err) = exec(listOf("su", "-c", join(args)), null, timeoutSec)
        return BytesResult(code == 0, out, err.toString(Charsets.UTF_8), code)
    }

    /** Jalankan perintah TANPA root (UID aplikasi sendiri). */
    fun runPlain(args: List<String>, timeoutSec: Int = 30): Result {
        val (code, out, err) = exec(args, null, timeoutSec)
        return Result(code == 0, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), code)
    }

    /** Seperti [runPlain], stdout sebagai bytes mentah. */
    fun runPlainBytes(args: List<String>, timeoutSec: Int = 30): BytesResult {
        val (code, out, err) = exec(args, null, timeoutSec)
        return BytesResult(code == 0, out, err.toString(Charsets.UTF_8), code)
    }

    /**
     * Jalankan string perintah shell mentah sebagai root (dipakai bila
     * perlu fitur shell seperti redirect `>`, yang tidak bisa lewat
     * [join] karena argumen dikutip). HANYA untuk string konstan
     * internal — jangan pernah memasukkan input HTTP ke sini.
     */
    fun runRaw(command: String, stdin: ByteArray? = null, timeoutSec: Int = 30): Result {
        val (code, out, err) = exec(listOf("su", "-c", command), stdin, timeoutSec)
        return Result(code == 0, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), code)
    }

    /**
     * Jalankan proses latar sebagai root dan kembalikan handle-nya
     * (untuk screenrecord). Stdout/stderr dikuras di thread latar
     * supaya pipe yang penuh tidak membuat proses menggantung.
     * (Sengaja tidak memakai ProcessBuilder.Redirect.DISCARD:
     *  kotlinc tidak bisa me-resolve-nya saat android.jar ada di
     *  classpath.)
     */
    fun startBackground(args: List<String>): Process? {
        return try {
            val proc = ProcessBuilder(listOf("su", "-c", join(args))).start()
            thread(isDaemon = true) {
                try {
                    proc.inputStream.copyTo(ByteArrayOutputStream())
                } catch (_: Exception) {
                }
            }
            thread(isDaemon = true) {
                try {
                    proc.errorStream.copyTo(ByteArrayOutputStream())
                } catch (_: Exception) {
                }
            }
            proc
        } catch (_: Exception) {
            null
        }
    }

    /** Apakah `su` tersedia dan memberi UID 0. */
    fun isRootAvailable(): Boolean {
        return try {
            val r = run(listOf("id"), 10)
            r.ok && r.stdout.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun exec(cmd: List<String>, stdin: ByteArray?, timeoutSec: Int): Triple<Int, ByteArray, ByteArray> {
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        // Baca stdout dan stderr di thread terpisah supaya pipe yang
        // penuh tidak membuat proses menggantung (deadlock).
        val tOut = thread(isDaemon = true) {
            try {
                proc.inputStream.copyTo(outBuf)
            } catch (_: Exception) {
            }
        }
        val tErr = thread(isDaemon = true) {
            try {
                proc.errorStream.copyTo(errBuf)
            } catch (_: Exception) {
            }
        }
        if (stdin != null) {
            try {
                proc.outputStream.write(stdin)
                proc.outputStream.flush()
            } catch (_: Exception) {
            }
        }
        try {
            proc.outputStream.close()
        } catch (_: Exception) {
        }
        val finished = try {
            proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
        if (!finished) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        try {
            tOut.join(2000)
        } catch (_: Exception) {
        }
        try {
            tErr.join(2000)
        } catch (_: Exception) {
        }
        val code = try {
            proc.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }
        return Triple(code, outBuf.toByteArray(), errBuf.toByteArray())
    }
}
