package com.example.speechblekbd.speech

/**
 * Callbacks shared by every dictation engine.
 *
 * All callbacks are delivered on the main thread. [onLevel] receives a
 * normalized 0..1 microphone level (for a VU meter); [onError] receives a
 * human-readable message and whether it is fatal (the engine stopped).
 */
class DictationCallbacks(
    val onPartial: (String) -> Unit,
    val onFinal: (String) -> Unit,
    val onState: (String) -> Unit,
    val onLevel: (Float) -> Unit = {},
    val onError: (String, Boolean) -> Unit = { _, _ -> },
)

/**
 * A pluggable dictation backend.
 *
 * Implementations: [ContinuousSpeech] (Android [android.speech.SpeechRecognizer])
 * and [VoskSpeechEngine] (offline streaming ASR).
 */
interface DictationEngine {
    /** Stable id used for persistence. */
    val id: String

    /** Human-readable label for the UI. */
    val label: String

    /** BCP-47 tag, e.g. "es-ES". */
    var language: String

    /** Whether the engine can run right now (model installed, service present…). */
    fun isAvailable(): Boolean

    /** One-line explanation shown when [isAvailable] is false. */
    fun unavailableReason(): String

    fun start()

    fun stop()

    fun release()
}
