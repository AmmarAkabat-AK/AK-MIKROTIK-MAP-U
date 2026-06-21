package app.ak.mikrotik.agent

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * عميل بروتوكول RouterOS API الثنائي (المنفذ الافتراضي 8728).
 * يكافئ مكتبة node-routeros المستخدمة في ak-agent.mjs.
 *
 * يدعم:
 *   - تسجيل الدخول الحديث (RouterOS 6.43+) عبر إرسال كلمة المرور مباشرة
 *   - تسجيل الدخول القديم عبر تحدٍ/استجابة MD5
 */
class RouterOSApi(
    private val host: String,
    private val user: String,
    private val password: String,
    private val port: Int = 8728,
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 25000,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    data class Reply(val type: String, val attrs: Map<String, String>)

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        login()
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    // ── تسجيل الدخول ──────────────────────────────────────────────
    private fun login() {
        // المحاولة الأولى: تسجيل دخول حديث (إرسال كلمة المرور مباشرة)
        val reply = write("/login", listOf("=name=$user", "=password=$password"))
        val ret = reply.firstOrNull { it.containsKey("ret") }?.get("ret")
        if (ret != null) {
            // راوتر قديم: تحدٍ MD5
            val challenge = hexToBytes(ret)
            val md = MessageDigest.getInstance("MD5")
            md.update(0.toByte())
            md.update(password.toByteArray(Charsets.UTF_8))
            md.update(challenge)
            val digest = bytesToHex(md.digest())
            write("/login", listOf("=name=$user", "=response=00$digest"))
        }
    }

    // ── تنفيذ أمر وإرجاع كل ردود !re (ويُلحق !done إن حمل سمات مثل ret) ──
    fun write(command: String, params: List<String> = emptyList()): List<Map<String, String>> {
        val words = ArrayList<String>(params.size + 1)
        words.add(command)
        words.addAll(params)
        sendSentence(words)

        val results = ArrayList<Map<String, String>>()
        while (true) {
            val reply = readSentence()
            when (reply.type) {
                "!re" -> results.add(reply.attrs)
                "!done" -> {
                    if (reply.attrs.isNotEmpty()) results.add(reply.attrs)
                    break
                }
                "!trap" -> throw RuntimeException(reply.attrs["message"] ?: "trap")
                "!fatal" -> throw RuntimeException(reply.attrs.values.joinToString(" ").ifEmpty { "fatal" })
                else -> { /* تجاهل */ }
            }
        }
        return results
    }

    /** مثل write لكنها ترجع قائمة فارغة بدل رمي الاستثناء (تكافئ .catch(() => []) في JS). */
    fun writeCatch(command: String, params: List<String> = emptyList()): List<Map<String, String>> =
        try { write(command, params) } catch (_: Exception) { emptyList() }

    // ── الطبقة الدنيا للبروتوكول ───────────────────────────────────
    private fun sendSentence(words: List<String>) {
        val out = output ?: throw RuntimeException("not connected")
        for (w in words) writeWord(out, w)
        out.write(0) // كلمة بطول صفر = نهاية الجملة
        out.flush()
    }

    private fun writeWord(out: OutputStream, word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        writeLen(out, bytes.size)
        out.write(bytes)
    }

    private fun writeLen(out: OutputStream, len: Int) {
        when {
            len < 0x80 -> out.write(len)
            len < 0x4000 -> {
                val l = len or 0x8000
                out.write((l ushr 8) and 0xFF); out.write(l and 0xFF)
            }
            len < 0x200000 -> {
                val l = len or 0xC00000
                out.write((l ushr 16) and 0xFF); out.write((l ushr 8) and 0xFF); out.write(l and 0xFF)
            }
            len < 0x10000000 -> {
                val l = len.toLong() or 0xE0000000L
                out.write(((l ushr 24) and 0xFF).toInt()); out.write(((l ushr 16) and 0xFF).toInt())
                out.write(((l ushr 8) and 0xFF).toInt()); out.write((l and 0xFF).toInt())
            }
            else -> {
                out.write(0xF0)
                out.write((len ushr 24) and 0xFF); out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
            }
        }
    }

    private fun readSentence(): Reply {
        var type = ""
        val attrs = LinkedHashMap<String, String>()
        var first = true
        while (true) {
            val word = readWord() ?: break // طول صفر => نهاية الجملة
            if (first) {
                type = word
                first = false
            } else if (word.startsWith("=")) {
                val idx = word.indexOf('=', 1)
                if (idx == -1) attrs[word.substring(1)] = ""
                else attrs[word.substring(1, idx)] = word.substring(idx + 1)
            }
            // تجاهل .tag وغيرها
        }
        return Reply(type, attrs)
    }

    /** يرجع null عند كلمة بطول صفر (نهاية الجملة). */
    private fun readWord(): String? {
        val len = readLen()
        if (len == 0) return null
        val buf = ByteArray(len)
        var read = 0
        val ins = input ?: throw RuntimeException("not connected")
        while (read < len) {
            val r = ins.read(buf, read, len - read)
            if (r == -1) throw RuntimeException("connection closed")
            read += r
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readLen(): Int {
        val c = readByte()
        return when {
            c and 0x80 == 0x00 -> c
            c and 0xC0 == 0x80 -> ((c and 0x3F) shl 8) or readByte()
            c and 0xE0 == 0xC0 -> ((c and 0x1F) shl 16) or (readByte() shl 8) or readByte()
            c and 0xF0 == 0xE0 -> ((c and 0x0F) shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
            else -> (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
        }
    }

    private fun readByte(): Int {
        val b = input?.read() ?: throw RuntimeException("not connected")
        if (b == -1) throw RuntimeException("connection closed")
        return b and 0xFF
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                    Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
