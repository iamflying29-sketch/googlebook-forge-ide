package local.forge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject

/**
 * Voice input for the chat with Android's own speech recognizer (Google's speech service on the Googlebook).
 * Dictation keeps listening across short pauses until you press the microphone button again, for at most
 * three minutes, and reports partial and final text through a callback. It runs outside the Linux VM and needs
 * no model in the VM's memory.
 */
class Voice(private val ctx: Context, private val sink: (JSONObject) -> Unit) {
    companion object {
        private const val TAG = "ForgeVoice"
        private const val MAX_SESSION_MS = 180_000L
        private const val MAX_RESTARTS = 40
        fun available(ctx: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

        private fun best(b: Bundle?): String? {
            val r = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return if (r == null || r.isEmpty()) null else r[0]
        }

        private fun message(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Forge does not have permission to use the microphone."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a network connection for this language."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service stopped unexpectedly."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This language is not available in the speech service."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech service is getting too many requests."
            else -> "Speech recognition failed (code $code)."
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var wanted = false
    private var startedAt = 0L
    private var restarts = 0
    private var language = "en-US"

    /** Must be called on the main thread. */
    fun start(lang: String?) {
        if (wanted) return
        if (!lang.isNullOrEmpty()) language = lang
        if (!available(ctx)) { emit("error", "No speech recognition service is installed on this device."); return }
        wanted = true
        startedAt = System.currentTimeMillis()
        restarts = 0
        emit("state", "listening")
        listen()
        // Watchdog: dictation never runs longer than the cap, even if the recognizer misbehaves.
        main.postDelayed({ if (wanted && System.currentTimeMillis() - startedAt >= MAX_SESSION_MS) stop() }, MAX_SESSION_MS + 500)
    }

    /** Stops listening; text recognized so far is still delivered. */
    fun stop() {
        if (!wanted) return
        wanted = false
        try { rec?.stopListening() } catch (e: Exception) { }
        // If the recognizer does not answer within two seconds, release it anyway.
        main.postDelayed({ release() }, 2000)
        emit("state", "stopped")
    }

    fun release() {
        if (wanted) return
        val r = rec ?: return
        try { r.cancel() } catch (e: Exception) { }
        try { r.destroy() } catch (e: Exception) { }
        rec = null
    }

    private fun listen() {
        if (!wanted) return
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener); rec = it }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try { r.startListening(i) }
        catch (e: Exception) { Log.w(TAG, "startListening failed", e); emit("error", "Could not start the speech recognizer."); wanted = false; release() }
    }

    /** After each sentence the recognizer stops; listen again while dictation is on. */
    private fun again() {
        if (!wanted) { release(); return }
        if (System.currentTimeMillis() - startedAt >= MAX_SESSION_MS || ++restarts > MAX_RESTARTS) { stop(); return }
        main.postDelayed({ listen() }, 120)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(b: Bundle?) { emit("state", "ready") }
        override fun onBeginningOfSpeech() { emit("state", "speaking") }
        override fun onRmsChanged(db: Float) {}
        override fun onBufferReceived(buf: ByteArray?) {}
        override fun onEndOfSpeech() { emit("state", "processing") }
        override fun onPartialResults(b: Bundle?) { val t = best(b); if (t != null) emit("partial", t) }
        override fun onResults(b: Bundle?) { val t = best(b); if (!t.isNullOrEmpty()) emit("final", t); again() }
        override fun onEvent(type: Int, b: Bundle?) {}
        override fun onError(code: Int) {
            // No speech or no match just means a pause: keep listening. Anything else ends dictation.
            if (wanted && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { again(); return }
            if (code == SpeechRecognizer.ERROR_CLIENT && !wanted) { release(); return }
            wanted = false
            emit("error", message(code))
            emit("state", "stopped")
            main.post { release() }
        }
    }

    private fun emit(type: String, text: String) {
        try { sink(JSONObject().put("type", type).put("text", text)) } catch (e: Exception) { }
    }
}
