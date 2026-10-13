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
 * Wraps [SpeechRecognizer] to provide quasi-continuous dictation.
 *
 * Android's recognizer stops after one utterance, so we restart it on every
 * result/error. Partial results are surfaced for live UI feedback; only final
 * results are forwarded (see [onFinal]).
 *
 * All methods must be called from the main thread.
 */
class ContinuousSpeech(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onState: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ContinuousSpeech"
        private const val RESTART_DELAY_MS = 250L
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    var language: String = "es-ES"

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

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

    fun start() {
        if (running) return
        running = true
        main.post {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
            }
            onState("Escuchando…")
            recognizer?.startListening(intent)
        }
    }

    fun stop() {
        running = false
        main.post {
            recognizer?.cancel()
            onState("Dictado detenido")
        }
    }

    fun release() {
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
                ?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onFinal(text)
            scheduleRestart()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error: $error")
            // Includes ERROR_RECOGNIZER_BUSY / NO_MATCH / SPEECH_TIMEOUT: retry.
            scheduleRestart()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
