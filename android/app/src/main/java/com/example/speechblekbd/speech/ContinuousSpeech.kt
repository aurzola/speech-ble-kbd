package com.example.speechblekbd.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dictation engine backed by Android's [SpeechRecognizer].
 *
 * Provides quasi-continuous dictation: the recognizer stops after one utterance,
 * so we restart it on every result/error. Partial results are surfaced for live
 * UI feedback; only final results are forwarded (see [DictationCallbacks.onFinal]).
 *
 * All methods must be called from the main thread.
 */
class ContinuousSpeech(
    private val context: Context,
    private val callbacks: DictationCallbacks,
) : DictationEngine {

    companion object {
        private const val TAG = "ContinuousSpeech"
        private const val RESTART_DELAY_MS = 250L
    }

    override val id = "system"
    override val label = "Sistema (online)"

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    override var language: String = "es-ES"

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun unavailableReason(): String = "Reconocimiento de voz no disponible en este teléfono"

    private val intent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Prefer the networked recognizer: it is markedly more accurate than offline.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Endpointing: allow pauses without cutting the sentence.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Bias toward the words we actually use (control keywords + common terms).
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    arrayListOf("enter", "intro", "nueva línea", "salto de línea", "línea nueva"),
                )
                // Automatic punctuation/capitalization, optimized for accuracy.
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }

    override fun start() {
        if (running) return
        if (!isAvailable()) {
            callbacks.onError(unavailableReason(), true)
            return
        }
        running = true
        main.post {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
            }
            callbacks.onState("Escuchando…")
            recognizer?.startListening(intent)
        }
    }

    override fun stop() {
        running = false
        main.post {
            recognizer?.cancel()
            callbacks.onState("Dictado detenido")
        }
    }

    override fun release() {
        running = false
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun scheduleRestart() {
        if (!running) return
        main.postDelayed({
            if (running) recognizer?.startListening(intent)
        }, RESTART_DELAY_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(callbacks.onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) callbacks.onFinal(text)
            scheduleRestart()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error: $error")
            // Includes ERROR_RECOGNIZER_BUSY / NO_MATCH / SPEECH_TIMEOUT: retry.
            scheduleRestart()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB is roughly -2 (loud) .. -120 (silence). Map to a 0..1 meter.
            callbacks.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
