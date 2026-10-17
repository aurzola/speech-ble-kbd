package com.example.speechblekbd.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline streaming dictation engine backed by Vosk (P2.13).
 *
 * Unlike [ContinuousSpeech], this captures audio itself with [AudioRecord] and
 * feeds a streaming [Recognizer], so there is no re-open gap between utterances
 * and partial results arrive continuously. It also provides:
 *
 *  - P2.14: an [EnergyVad] drives the level meter / silence state, and Vosk's
 *    built-in endpointing segments utterances into finals.
 *  - P2.15: selectable [audioSource] and an option to prefer the built-in
 *    microphone (avoids picking a paired Bluetooth headset mic by default).
 *
 * Heavy work (model load, capture loop) runs off the main thread; all callbacks
 * are posted back to the main thread.
 */
class VoskSpeechEngine(
    context: Context,
    private val store: VoskModelStore,
    private val callbacks: DictationCallbacks,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val preferBuiltInMic: Boolean = true,
) : DictationEngine {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "VoskSpeechEngine"
        private const val READ_SAMPLES = SAMPLE_RATE / 10 // 100 ms
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val id = "vosk"
    override val label = "Vosk (offline)"

    @Volatile
    override var language: String = "es-ES"

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    private val running = AtomicBoolean(false)
    private val vad = EnergyVad()

    override fun isAvailable(): Boolean = store.installed(language) != null

    override fun unavailableReason(): String =
        if (isAvailable()) "" else "Falta el modelo Vosk de ${store.languageName(language)}"

    override fun start() {
        if (running.get()) return
        val dir = store.installed(language)
        if (dir == null) {
            emitError(unavailableReason(), true)
            return
        }
        running.set(true)
        emitState("Cargando modelo…")
        worker = Thread({ runLoop(dir) }, "vosk-asr").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        emitState("Dictado detenido")
    }

    override fun release() {
        stop()
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(dir: File) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = Model(dir.absolutePath)
            model = m
            if (!running.get()) return
            val rec = Recognizer(m, SAMPLE_RATE.toFloat())
            recognizer = rec

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) throw IllegalStateException("AudioRecord no soporta 16 kHz mono")
            val bufSize = maxOf(minBuf, READ_SAMPLES * 2 * 4)

            val ar = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("Micrófono no disponible (¿en uso por otra app?)")
            }
            recorder = ar
            if (preferBuiltInMic) selectBuiltInMic(ar)
            ar.startRecording()

            emitState("Escuchando…")
            val buf = ByteArray(READ_SAMPLES * 2)
            while (running.get()) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read returned $n")
                    continue
                }
                val speaking = vad.isSpeech(buf, n)
                emitLevel(vad.level())
                if (!speaking && !running.get()) break

                if (rec.acceptWaveForm(buf, n)) {
                    val text = field(rec.result, "text")
                    if (text.isNotBlank()) emitFinal(text)
                } else {
                    val partial = field(rec.partialResult, "partial")
                    if (partial.isNotBlank()) emitPartial(partial)
                }
            }
            // Flush whatever is buffered when the user stops.
            val tail = field(rec.finalResult, "text")
            if (tail.isNotBlank()) emitFinal(tail)
        } catch (t: Throwable) {
            Log.e(TAG, "Vosk engine failed", t)
            emitError(t.message ?: "Fallo del motor Vosk", true)
        } finally {
            cleanup()
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectBuiltInMic(ar: AudioRecord) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val builtIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: return
        if (ar.setPreferredDevice(builtIn)) Log.i(TAG, "capturing from built-in mic")
    }

    private fun cleanup() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
        model?.let { runCatching { it.close() } }
        model = null
    }

    private fun field(json: String?, key: String): String = runCatching {
        if (json.isNullOrBlank()) "" else JSONObject(json).optString(key)
    }.getOrDefault("").trim()

    private fun emitPartial(text: String) = main.post { callbacks.onPartial(text) }
    private fun emitFinal(text: String) = main.post { callbacks.onFinal(text) }
    private fun emitState(text: String) = main.post { callbacks.onState(text) }
    private fun emitLevel(level: Float) = main.post { callbacks.onLevel(level) }
    private fun emitError(message: String, fatal: Boolean) = main.post { callbacks.onError(message, fatal) }
}
