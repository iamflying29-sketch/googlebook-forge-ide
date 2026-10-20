package local.forge

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The Forge server's API from the native app: JSON over HTTP, server-sent events for a running reply, and a small
 * WebSocket client for the terminal. Every request carries the window's folder in the X-Forge-Folder header, the
 * way the old web page did, and every call has a timeout (nothing here may ever hang).
 *
 * Audit of 2026-10-19. The defects that were fixed are marked "Fix:" in the comments below. In short: error bodies
 * that are not JSON now reach the caller, connection failures become readable ApiError messages, the folder header
 * encodes spaces the way the server decodes them, the event stream reports a lost connection exactly once and
 * resumes a dropped connection, and the WebSocket client follows RFC 6455 for fragments, close codes, pings,
 * masking and lengths and sends in order from one thread.
 */
object Api {
    const val SERVER = "http://localhost:8765"
    private const val TAG = "ForgeApi"
    val main = Handler(Looper.getMainLooper())

    /** The folder this window works in; empty means the server's default folder. */
    @Volatile var folder: String = ""

    class ApiError(message: String, val code: Int = 0) : Exception(message)

    /**
     * Fix: URLEncoder turns a space into "+", but the server decodes the header with urllib.parse.unquote, which
     * leaves "+" alone, so a folder with a space in its name was looked up under the wrong path. The web page
     * uses encodeURIComponent, which writes "%20"; this does the same.
     */
    private fun encodeComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json")
        if (folder.isNotEmpty()) c.setRequestProperty("X-Forge-Folder", encodeComponent(folder))
        return c
    }

    /** Reads at most [cap] bytes of the response or error body and closes the stream in every case. */
    private fun readBody(c: HttpURLConnection, cap: Int = 32 * 1024 * 1024): String {
        val stream = (if (c.responseCode >= 400) c.errorStream else c.inputStream) ?: return ""
        stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() + n > cap) throw ApiError("The server's reply is larger than $cap bytes", c.responseCode)
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /**
     * Fix: an error body that is not JSON (a plain-text traceback, an HTML page from a proxy, an empty body) used
     * to be reported as a bare "HTTP 500"; now its first line is part of the message so the user sees the cause.
     */
    private fun parse(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = readBody(c)
        val obj = try { JSONObject(body) } catch (e: Exception) { JSONObject().put("raw", body) }
        if (code >= 400) {
            val fromJson = obj.optString("error", "")
            val excerpt = body.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
            val message = when {
                fromJson.isNotEmpty() -> fromJson
                excerpt.isNotEmpty() -> "HTTP $code: $excerpt"
                else -> "HTTP $code"
            }
            throw ApiError(message, code)
        }
        return obj
    }

    /**
     * Fix: a refused connection or a timeout surfaced as "java.net.ConnectException: Failed to connect to
     * localhost/127.0.0.1:8765", which the panels show to the user. Network failures now become an ApiError with
     * a sentence that says what happened; an ApiError from the server passes through unchanged.
     */
    private inline fun <T> withConnection(c: HttpURLConnection, what: String, block: () -> T): T {
        try {
            return block()
        } catch (e: ApiError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiError("The Forge server did not answer $what in time")
        } catch (e: IOException) {
            throw ApiError("Cannot reach the Forge server for $what: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            // Fix: disconnect() also runs when open() succeeded but the request threw before the body was read.
            try { c.disconnect() } catch (e: Exception) { }
        }
    }

    /** GET with query parameters; runs on the calling (background) thread. */
    fun get(path: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): JSONObject {
        val q = params.entries.joinToString("&") { encodeComponent(it.key) + "=" + encodeComponent(it.value) }
        val full = if (q.isEmpty()) path else path + (if (path.contains("?")) "&" else "?") + q
        val c = open(full, "GET", timeoutMs)
        return withConnection(c, path) { parse(c) }
    }

    /** GET raw bytes (an image for the image viewer) with at most [cap] bytes; runs on the calling (background) thread. */
    fun bytes(path: String, params: Map<String, String> = emptyMap(), cap: Int = 40 * 1024 * 1024, timeoutMs: Int = 30000): ByteArray {
        val q = params.entries.joinToString("&") { encodeComponent(it.key) + "=" + encodeComponent(it.value) }
        val full = if (q.isEmpty()) path else path + (if (path.contains("?")) "&" else "?") + q
        val c = open(full, "GET", timeoutMs)
        try {
            if (c.responseCode >= 400) throw ApiError(readBody(c, 4096).let { b -> try { JSONObject(b).optString("error", b) } catch (e: Exception) { b } }.ifEmpty { "HTTP " + c.responseCode }, c.responseCode)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (out.size() + n > cap) throw ApiError("larger than " + (cap / (1024 * 1024)) + " MB")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally { try { c.disconnect() } catch (e: Exception) { } }
    }

    /** POST a JSON body; runs on the calling (background) thread. */
    fun post(path: String, body: JSONObject, timeoutMs: Int = 60000): JSONObject {
        val c = open(path, "POST", timeoutMs)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        return withConnection(c, path) {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            parse(c)
        }
    }

    /**
     * Runs work on a background thread and hands the result (or the error) to the main thread.
     * Fix: an Error thrown by the work (for example an OutOfMemoryError while decoding an image) used to kill the
     * thread without ever calling done, so a panel could wait forever; it is now reported like an exception.
     */
    fun <T> async(name: String, work: () -> T, done: (T?, Exception?) -> Unit) {
        val t = Thread({
            var result: T? = null
            var error: Exception? = null
            try { result = work() }
            catch (e: Exception) { error = e; Log.w(TAG, "$name failed: $e") }
            catch (e: Error) { error = ApiError("$name failed: $e"); Log.w(TAG, "$name failed: $e") }
            main.post { done(result, error) }
        }, "forge-$name")
        t.isDaemon = true
        t.start()
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
     * the run ends, when the connection is lost for good (one error event is delivered then) or when stop() is
     * called (nothing is delivered after that).
     *
     * Fixes of the audit:
     *  - When the server closed the stream without an "end" event (a server restart, a dropped connection), the
     *    old reader returned quietly and the chat waited forever. The stream now resumes from the last event id
     *    with "since" (the server numbers its events), and only when the resume attempts are used up does it
     *    deliver one error event. The error event is guaranteed to be delivered at most once, and never after
     *    an "end" event or after stop().
     *  - The server writes ": ping" every 10 seconds, so a read that waits more than 45 seconds means the
     *    connection is dead. The old 20-minute read timeout would have kept a dead stream open for 20 minutes.
     *  - Several "data:" lines of one event are joined with a newline, as the event stream format requires.
     */
    class EventStream(private val runId: String, private val onEvent: (JSONObject) -> Unit) {
        private val stopped = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        @Volatile private var conn: HttpURLConnection? = null
        /** The id of the last event delivered, so a resumed connection starts right after it. */
        @Volatile private var lastId = -1L
        private val resumeDelaysMs = longArrayOf(500, 1500, 3000, 5000)

        fun start() {
            val t = Thread({ runReader() }, "forge-events")
            t.isDaemon = true
            t.start()
        }

        private fun runReader() {
            var attempt = 0
            while (!stopped.get() && !finished.get()) {
                val outcome = readOnce()
                if (stopped.get() || finished.get()) break
                when (outcome) {
                    Outcome.ENDED -> break
                    Outcome.DROPPED, Outcome.NOT_FOUND -> {
                        if (outcome == Outcome.NOT_FOUND || attempt >= resumeDelaysMs.size) {
                            deliverOnce(JSONObject().put("type", "error")
                                .put("message", if (outcome == Outcome.NOT_FOUND) "The Forge server no longer knows this run (it was probably restarted)."
                                                else "Connection to the Forge server was lost and could not be resumed."))
                            break
                        }
                        try { Thread.sleep(resumeDelaysMs[attempt]) } catch (e: InterruptedException) { break }
                        attempt++
                    }
                }
            }
        }

        private enum class Outcome { ENDED, DROPPED, NOT_FOUND }

        /** One connection to the event stream; returns how it ended. */
        private fun readOnce(): Outcome {
            var c: HttpURLConnection? = null
            try {
                val since = if (lastId >= 0) "&since=" + (lastId + 1) else ""
                c = open("/api/events?run_id=" + encodeComponent(runId) + since, "GET", 45000)
                conn = c
                val code = c.responseCode
                if (code == 404) return Outcome.NOT_FOUND
                if (code >= 400) throw ApiError("HTTP $code", code)
                val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                val data = StringBuilder()
                var id = -1L
                while (!stopped.get()) {
                    val line = r.readLine() ?: return Outcome.DROPPED
                    when {
                        line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trim()) }
                        line.startsWith("id:") -> id = line.substring(3).trim().toLongOrNull() ?: -1L
                        line.startsWith(":") -> { }   // a comment, the server's keep-alive ping
                        line.isEmpty() && data.isNotEmpty() -> {
                            val text = data.toString(); data.setLength(0)
                            val ev = try { JSONObject(text) } catch (e: Exception) { null }
                            if (id >= 0) lastId = id
                            id = -1L
                            if (ev != null) {
                                if (ev.optString("type") == "end") { deliverOnce(ev); return Outcome.ENDED }
                                // Only stop() silences an event. The end event is posted after this one, and the main
                                // thread runs posts in order, so checking "finished" here dropped the last events of a
                                // reply (the final code of Ctrl K, the closing words and the statistics) whenever they
                                // arrived just before the end.
                                main.post { if (!stopped.get()) onEvent(ev) }
                            }
                        }
                    }
                }
                return Outcome.ENDED   // stopped by the user; nothing more is delivered
            } catch (e: Exception) {
                if (!stopped.get()) Log.w(TAG, "event stream for $runId dropped: $e")
                return Outcome.DROPPED
            } finally {
                try { c?.disconnect() } catch (e: Exception) { }
                if (conn === c) conn = null
            }
        }

        /** Delivers the final event (an "end" or the one error) at most once, and never after stop(). */
        private fun deliverOnce(ev: JSONObject) {
            if (finished.getAndSet(true)) return
            main.post { if (!stopped.get()) onEvent(ev) }
        }

        fun stop() {
            stopped.set(true)
            // disconnect() interrupts the blocking read; it does I/O, so it runs off the main thread.
            val c = conn
            val t = Thread({ try { c?.disconnect() } catch (e: Exception) { } }, "forge-events-stop")
            t.isDaemon = true
            t.start()
        }
    }

    // ------------------------------------------------------------------ a small WebSocket client (RFC 6455)
    /**
     * Text frames in, text frames out; enough for the terminal. All callbacks arrive on the main thread.
     *
     * Fixes of the audit:
     *  - Fragmented messages: the opcode of the first fragment is kept, continuation frames are appended and the
     *    bytes are decoded as UTF-8 only when the final fragment arrives, so a character split across frames
     *    survives. Control frames between fragments are handled without disturbing the message.
     *  - Close frames: the status code and reason are read, a close frame with the same code is sent back as the
     *    protocol requires, and onClose reports "closed by the server (code 1000)". onClose is delivered at most
     *    once, and not at all after close() was called locally (the terminal panel reconnects then and must not
     *    see a stale "closed" message).
     *  - Ping and pong: a ping is answered with a pong carrying the same payload; pongs are ignored; the client
     *    sends its own ping every 25 seconds and the socket has a 90-second read timeout, so a dead connection
     *    is noticed instead of hanging forever (the old socket had no timeout).
     *  - Masking: every client frame (text, ping, pong, close) is masked with a fresh random key.
     *  - Lengths: the 64-bit length is read as a long and rejected when negative or above the 8 MB cap, so a bad
     *    length can no longer produce a negative array size. A 16-bit length is read unsigned.
     *  - Ordering and the main thread: sendText() used to start a new thread per message, so two keystrokes could
     *    be written in the wrong order; all writes now go through one queue and one sender thread. connect(),
     *    sendText() and close() never block the caller; connecting has a 3-second timeout.
     *  - The handshake checks the Sec-WebSocket-Accept header value itself, not just that the key appears
     *    somewhere in the reply, and the Host header no longer prints a port of -1.
     *  - reconnectAfter(delayMs) is a small helper for owners that want to try again after a drop.
     */
    class WebSocketClient(private val path: String, private val onText: (String) -> Unit, private val onClose: (String) -> Unit) {
        @Volatile private var socket: Socket? = null
        private val closed = AtomicBoolean(false)
        private val closeDelivered = AtomicBoolean(false)
        /** True after close() was called by the owner; a dropped connection does not set it. */
        private val userClosed = AtomicBoolean(false)
        private val outQueue = LinkedBlockingQueue<Frame>()
        private class Frame(val op: Int, val payload: ByteArray)
        private val MAX_FRAME = 8L * 1024 * 1024
        private val PING_EVERY_MS = 25000L

        fun connect() {
            // A client may be connected again after a drop or a close(); the flags start fresh each time.
            closed.set(false); closeDelivered.set(false); userClosed.set(false); outQueue.clear()
            val t = Thread({ runConnection() }, "forge-ws")
            t.isDaemon = true
            t.start()
        }

        private fun runConnection() {
            var s: Socket? = null
            var sender: Thread? = null
            try {
                val url = URL(SERVER)
                val port = if (url.port > 0) url.port else 80
                s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(url.host, port), 3000)
                s.soTimeout = 90000
                socket = s
                val key = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
                val req = "GET $path HTTP/1.1\r\nHost: ${url.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                val o = s.getOutputStream()
                o.write(req.toByteArray(Charsets.ISO_8859_1)); o.flush()
                val inp = DataInputStream(s.getInputStream())
                handshake(inp, key)
                sender = startSender(o)
                readFrames(inp)
            } catch (e: Exception) {
                if (!closed.get()) {
                    val why = when (e) {
                        is SocketTimeoutException -> "no answer from the server for 90 seconds"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    deliverClose(why)
                }
            } finally {
                closed.set(true)
                sender?.interrupt()
                try { s?.close() } catch (e: Exception) { }
                if (socket === s) socket = null
            }
        }

        /** Reads the handshake reply line by line until the empty line and checks the accept key. */
        private fun handshake(inp: DataInputStream, key: String) {
            val head = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) throw IOException("the server closed the connection during the handshake")
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) break
                if (head.length > 8192) throw IOException("handshake reply too long")
            }
            val lines = head.toString().split("\r\n")
            val status = lines.firstOrNull() ?: ""
            if (!status.startsWith("HTTP/1.1 101")) throw IOException("handshake refused: $status")
            val expect = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)), Base64.NO_WRAP)
            val accept = lines.firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
            if (accept != expect) throw IOException("handshake key mismatch")
        }

        /** One thread writes every frame, in queue order, and pings the server while the queue is idle. */
        private fun startSender(o: OutputStream): Thread {
            val t = Thread({
                try {
                    // The loop runs on after close() so that the queued close frame still goes out; it ends with
                    // that frame, with an interrupt from the reader thread, or when the queue stays empty after a close.
                    while (true) {
                        val f = outQueue.poll(PING_EVERY_MS, TimeUnit.MILLISECONDS)
                        if (f == null) { if (closed.get()) break; writeFrame(o, 0x9, ByteArray(0)); continue }
                        writeFrame(o, f.op, f.payload)
                        if (f.op == 0x8) break
                    }
                } catch (e: InterruptedException) {
                } catch (e: Exception) {
                    if (!closed.get()) { Log.w(TAG, "ws send failed: $e"); try { socket?.close() } catch (x: Exception) { } }
                }
            }, "forge-ws-send")
            t.isDaemon = true
            t.start()
            return t
        }

        private fun readFrames(inp: DataInputStream) {
            val message = ByteArrayOutputStream()
            while (!closed.get()) {
                val b0 = inp.readUnsignedByte(); val b1 = inp.readUnsignedByte()
                val fin = b0 and 0x80 != 0; val op = b0 and 0x0f
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                if (len < 0 || len > MAX_FRAME) { enqueue(0x8, closePayload(1009, "frame too large")); throw IOException("frame of $len bytes refused") }
                val masked = b1 and 0x80 != 0
                val mask = ByteArray(4); if (masked) inp.readFully(mask)
                val payload = ByteArray(len.toInt()); inp.readFully(payload)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (op) {
                    0x8 -> {
                        var code = 1005; var reason = ""
                        if (payload.size >= 2) {
                            code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                            reason = String(payload, 2, payload.size - 2, Charsets.UTF_8)
                        }
                        enqueue(0x8, if (payload.size >= 2) closePayload(code, "") else ByteArray(0))
                        deliverClose("closed by the server (code $code" + (if (reason.isNotEmpty()) ", $reason" else "") + ")")
                        return
                    }
                    0x9 -> enqueue(0xA, payload)
                    0xA -> { }
                    0x1, 0x2 -> {
                        message.reset(); message.write(payload)
                        if (fin) finishMessage(message)
                    }
                    0x0 -> {
                        if (message.size() + payload.size > MAX_FRAME) throw IOException("message too large")
                        message.write(payload)
                        if (fin) finishMessage(message)
                    }
                    else -> throw IOException("unknown frame opcode $op")
                }
            }
        }

        private fun finishMessage(message: ByteArrayOutputStream) {
            val text = String(message.toByteArray(), Charsets.UTF_8)   // binary frames from the terminal are text too
            message.reset()
            main.post { if (!closed.get()) onText(text) }
        }

        private fun closePayload(code: Int, reason: String): ByteArray {
            val r = reason.toByteArray(Charsets.UTF_8)
            val p = ByteArray(2 + r.size)
            p[0] = (code shr 8).toByte(); p[1] = (code and 0xff).toByte()
            System.arraycopy(r, 0, p, 2, r.size)
            return p
        }

        private fun deliverClose(why: String) {
            if (closeDelivered.getAndSet(true)) return
            main.post { onClose(why) }
        }

        private fun enqueue(op: Int, payload: ByteArray) { if (!closed.get() || op == 0x8) outQueue.offer(Frame(op, payload)) }

        /** Writes one masked frame; only the sender thread calls this. */
        private fun writeFrame(o: OutputStream, op: Int, payload: ByteArray) {
            val mask = Random.nextBytes(4)
            val head = ByteArrayOutputStream()
            head.write(0x80 or op)
            val n = payload.size
            when {
                n < 126 -> head.write(0x80 or n)
                n < 65536 -> { head.write(0x80 or 126); head.write(n shr 8); head.write(n and 0xff) }
                else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            head.write(mask)
            val data = ByteArray(n) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            o.write(head.toByteArray()); o.write(data); o.flush()
        }

        fun sendText(text: String) { enqueue(0x1, text.toByteArray(Charsets.UTF_8)) }

        /** Closes politely: a close frame goes out first, then the socket is shut after a short grace period. */
        fun close() {
            userClosed.set(true)
            if (closed.getAndSet(true)) return
            closeDelivered.set(true)   // a local close is never reported through onClose
            outQueue.offer(Frame(0x8, closePayload(1000, "")))
            val s = socket
            val t = Thread({
                try { Thread.sleep(300) } catch (e: InterruptedException) { }
                try { s?.close() } catch (e: Exception) { }
            }, "forge-ws-close")
            t.isDaemon = true
            t.start()
        }

        /** Connects again after [delayMs] on the main thread, unless close() was called in the meantime. */
        fun reconnectAfter(delayMs: Long) {
            main.postDelayed({ if (!userClosed.get() && socket == null) connect() }, delayMs)
        }
    }

    fun jsonArrayOf(items: List<JSONObject>): JSONArray { val a = JSONArray(); for (i in items) a.put(i); return a }
}
