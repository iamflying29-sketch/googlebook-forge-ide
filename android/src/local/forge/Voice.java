package local.forge;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Voice input for the chat with Android's own speech recognizer (Google's speech service on the Googlebook).
 * Dictation keeps listening across short pauses until you press the microphone button again, for at most
 * three minutes, and reports partial and final text to the page through a callback.
 * It runs outside the Linux VM and needs no model in the VM's memory.
 */
final class Voice {
    interface Sink { void send(JSONObject event); }

    private static final String TAG = "ForgeVoice";
    private static final long MAX_SESSION_MS = 180_000;
    private static final int MAX_RESTARTS = 40;

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private boolean wanted = false;
    private long startedAt = 0;
    private int restarts = 0;
    private String language = "en-US";

    Voice(Context ctx, Sink sink) { this.ctx = ctx; this.sink = sink; }

    static boolean available(Context ctx) { return SpeechRecognizer.isRecognitionAvailable(ctx); }

    /** Must be called on the main thread. */
    void start(String lang) {
        if (wanted) return;
        if (lang != null && !lang.isEmpty()) language = lang;
        if (!available(ctx)) { emit("error", "No speech recognition service is installed on this device."); return; }
        wanted = true;
        startedAt = System.currentTimeMillis();
        restarts = 0;
        emit("state", "listening");
        listen();
        // Watchdog: dictation never runs longer than the cap, even if the recognizer misbehaves.
        main.postDelayed(() -> { if (wanted && System.currentTimeMillis() - startedAt >= MAX_SESSION_MS) stop(); }, MAX_SESSION_MS + 500);
    }

    /** Stops listening; text recognized so far is still delivered. */
    void stop() {
        if (!wanted) return;
        wanted = false;
        if (rec != null) { try { rec.stopListening(); } catch (Exception ignored) { } }
        // If the recognizer does not answer within two seconds, release it anyway.
        main.postDelayed(this::release, 2000);
        emit("state", "stopped");
    }

    void release() {
        if (wanted) return;
        if (rec != null) {
            try { rec.cancel(); } catch (Exception ignored) { }
            try { rec.destroy(); } catch (Exception ignored) { }
            rec = null;
        }
    }

    private void listen() {
        if (!wanted) return;
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(ctx);
            rec.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        try { rec.startListening(i); }
        catch (Exception e) { Log.w(TAG, "startListening failed", e); emit("error", "Could not start the speech recognizer."); wanted = false; release(); }
    }

    /** After each sentence the recognizer stops; listen again while dictation is on. */
    private void again() {
        if (!wanted) { release(); return; }
        if (System.currentTimeMillis() - startedAt >= MAX_SESSION_MS || ++restarts > MAX_RESTARTS) { stop(); return; }
        main.postDelayed(this::listen, 120);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { emit("state", "ready"); }
        @Override public void onBeginningOfSpeech() { emit("state", "speaking"); }
        @Override public void onRmsChanged(float db) { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { emit("state", "processing"); }
        @Override public void onPartialResults(Bundle b) { String t = best(b); if (t != null) emit("partial", t); }
        @Override public void onResults(Bundle b) { String t = best(b); if (t != null && !t.isEmpty()) emit("final", t); again(); }
        @Override public void onEvent(int type, Bundle b) { }
        @Override public void onError(int code) {
            // No speech or no match just means a pause: keep listening. Anything else ends dictation.
            if (wanted && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { again(); return; }
            if (code == SpeechRecognizer.ERROR_CLIENT && !wanted) { release(); return; }
            wanted = false;
            emit("error", message(code));
            emit("state", "stopped");
            main.post(Voice.this::release);
        }
    };

    private static String best(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private static String message(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "The microphone could not be read.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Forge does not have permission to use the microphone.";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "The speech service needs a network connection for this language.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "The speech recognizer is busy. Try again in a moment.";
            case SpeechRecognizer.ERROR_SERVER: case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "The speech service stopped unexpectedly.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "This language is not available in the speech service.";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "The speech service is getting too many requests.";
            default: return "Speech recognition failed (code " + code + ").";
        }
    }

    private void emit(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            sink.send(o);
        } catch (Exception ignored) { }
    }
}
