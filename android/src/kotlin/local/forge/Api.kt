package local.forge

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The Forge server's API from the native app: JSON over HTTP, server-sent events for a running reply, and a small
 * WebSocket client for the terminal. Every request carries the window's folder in the X-Forge-Folder header, the
 * way the old web page did, and every call has a timeout (nothing here may ever hang).
 */
object Api {
    const val SERVER = "http://localhost:8765"
    private const val TAG = "ForgeApi"
    val main = Handler(Looper.getMainLooper())

    /** The folder this window works in; empty means the server's default folder. */
    @Volatile var folder: String = ""

    class ApiError(message: String, val code: Int = 0) : Exception(message)

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.setRequestProperty("Accept", "application/json")
        if (folder.isNotEmpty()) c.setRequestProperty("X-Forge-Folder", URLEncoder.encode(folder, "UTF-8"))
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        if (stream == null) return ""
        stream.use { s -> return String(s.readBytes(), Charsets.UTF_8) }
    }

    private fun parse(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = readBody(c)
        val obj = try { JSONObject(body) } catch (e: Exception) { JSONObject().put("raw", body) }
        if (code >= 400) throw ApiError(obj.optString("error", "HTTP $code"), code)
        return obj
    }

    /** GET with query parameters; runs on the calling (background) thread. */
    fun get(path: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): JSONObject {
        val q = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val full = if (q.isEmpty()) path else path + (if (path.contains("?")) "&" else "?") + q
        val c = open(full, "GET", timeoutMs)
        try { return parse(c) } finally { c.disconnect() }
    }

    /** POST a JSON body; runs on the calling (background) thread. */
    fun post(path: String, body: JSONObject, timeoutMs: Int = 60000): JSONObject {
        val c = open(path, "POST", timeoutMs)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return parse(c)
        } finally { c.disconnect() }
    }

    /** Runs work on a background thread and hands the result (or the error) to the main thread. */
    fun <T> async(name: String, work: () -> T, done: (T?, Exception?) -> Unit) {
        Thread({
            var result: T? = null
            var error: Exception? = null
            try { result = work() } catch (e: Exception) { error = e; Log.w(TAG, "$name failed: $e") }
            main.post { done(result, error) }
        }, "forge-$name").start()
    }

    fun serverUp(): Boolean {
        return try {
            val c = open("/api/status", "GET", 2500)
            c.connectTimeout = 1500
            try { c.responseCode == 200 } finally { c.disconnect() }
        } catch (e: Exception) { false }
    }

    /** Uploads an image or a file for the chat; returns the server's attachment record. */
    fun upload(name: String, bytes: ByteArray): JSONObject {
        val body = JSONObject().put("name", name).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return post("/api/attachment", body, 120000)
    }

    // ------------------------------------------------------------------ server-sent events of one reply
    /**
     * Reads the events of a run until its end. Each event is delivered on the main thread. The reader stops when
     * the run ends, when the connection drops (an error event is delivered then) or when stop() is called.
     */
    class EventStream(private val runId: String, private val onEvent: (JSONObject) -> Unit) {
        private val stopped = AtomicBoolean(false)
        @Volatile private var conn: HttpURLConnection? = null

        fun start() {
            Thread({
                var c: HttpURLConnection? = null
                try {
                    c = open("/api/events?run_id=" + URLEncoder.encode(runId, "UTF-8"), "GET", 1000 * 60 * 20)
                    conn = c
                    val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                    val data = StringBuilder()
                    while (!stopped.get()) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("data:")) { data.append(line.substring(5).trim()) }
                        else if (line.isEmpty() && data.isNotEmpty()) {
                            val text = data.toString(); data.setLength(0)
                            val ev = try { JSONObject(text) } catch (e: Exception) { null }
                            if (ev != null) {
                                main.post { if (!stopped.get()) onEvent(ev) }
                                if (ev.optString("type") == "end") break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!stopped.get()) main.post { onEvent(JSONObject().put("type", "error").put("message", "Connection to the Forge server was lost: $e")) }
                } finally {
                    try { c?.disconnect() } catch (e: Exception) { }
                }
            }, "forge-events").start()
        }

        fun stop() {
            stopped.set(true)
            Thread({ try { conn?.disconnect() } catch (e: Exception) { } }, "forge-events-stop").start()
        }
    }

    // ------------------------------------------------------------------ a small WebSocket client (RFC 6455)
    /** Text frames in, text frames out; enough for the terminal. All callbacks arrive on the main thread. */
    class WebSocketClient(private val path: String, private val onText: (String) -> Unit, private val onClose: (String) -> Unit) {
        private var socket: Socket? = null
        private var out: OutputStream? = null
        private val closed = AtomicBoolean(false)

        fun connect() {
            Thread({
                try {
                    val url = URL(SERVER)
                    val s = Socket(url.host, if (url.port > 0) url.port else 80)
                    s.soTimeout = 0
                    socket = s
                    val key = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
                    val req = "GET $path HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                    val o = s.getOutputStream(); out = o
                    o.write(req.toByteArray(Charsets.ISO_8859_1)); o.flush()
                    val inp = DataInputStream(s.getInputStream())
                    // The handshake reply, line by line until the empty line.
                    val head = StringBuilder()
                    while (true) {
                        val b = inp.read()
                        if (b < 0) throw Exception("closed during the handshake")
                        head.append(b.toChar())
                        if (head.endsWith("\r\n\r\n")) break
                        if (head.length > 8192) throw Exception("handshake too long")
                    }
                    if (!head.startsWith("HTTP/1.1 101")) throw Exception("handshake refused: " + head.lineSequence().first())
                    val expect = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP)
                    if (!head.contains(expect)) throw Exception("handshake key mismatch")
                    val buf = java.io.ByteArrayOutputStream()
                    while (!closed.get()) {
                        val b0 = inp.readUnsignedByte(); val b1 = inp.readUnsignedByte()
                        val fin = b0 and 0x80 != 0; val op = b0 and 0x0f
                        var len = (b1 and 0x7f).toLong()
                        if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                        val masked = b1 and 0x80 != 0
                        val mask = ByteArray(4); if (masked) inp.readFully(mask)
                        if (len > 8L * 1024 * 1024) throw Exception("frame too large")
                        val payload = ByteArray(len.toInt()); inp.readFully(payload)
                        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                        when (op) {
                            0x8 -> { break }
                            0x9 -> send(payload, 0xA)
                            0xA -> { }
                            0x0, 0x1, 0x2 -> {
                                buf.write(payload)
                                if (fin) { val text = String(buf.toByteArray(), Charsets.UTF_8); buf.reset(); main.post { if (!closed.get()) onText(text) } }
                            }
                        }
                    }
                    main.post { onClose("closed") }
                } catch (e: Exception) {
                    if (!closed.get()) main.post { onClose(e.toString()) }
                } finally {
                    try { socket?.close() } catch (e: Exception) { }
                }
            }, "forge-ws").start()
        }

        @Synchronized private fun send(payload: ByteArray, op: Int) {
            val o = out ?: return
            val mask = Random.nextBytes(4)
            val head = java.io.ByteArrayOutputStream()
            head.write(0x80 or op)
            val n = payload.size
            when {
                n < 126 -> head.write(0x80 or n)
                n < 65536 -> { head.write(0x80 or 126); head.write(n shr 8); head.write(n and 0xff) }
                else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            head.write(mask)
            val data = ByteArray(n) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            try { o.write(head.toByteArray()); o.write(data); o.flush() } catch (e: Exception) { Log.w(TAG, "ws send failed: $e") }
        }

        fun sendText(text: String) { Thread({ send(text.toByteArray(Charsets.UTF_8), 0x1) }, "forge-ws-send").start() }

        fun close() {
            if (closed.getAndSet(true)) return
            Thread({ try { send(ByteArray(0), 0x8) } catch (e: Exception) { }; try { socket?.close() } catch (e: Exception) { } }, "forge-ws-close").start()
        }
    }

    fun jsonArrayOf(items: List<JSONObject>): JSONArray { val a = JSONArray(); for (i in items) a.put(i); return a }
}
