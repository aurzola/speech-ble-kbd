package com.example.speechblekbd.speech

import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector (P2.14).
 *
 * Fed 16-bit mono PCM frames, it reports whether a frame is likely speech and
 * exposes a normalized level. It is intentionally simple: Vosk already performs
 * its own endpointing/segmentation; this VAD drives the UI level meter and the
 * "silencio/escuchando" state, and can optionally gate very quiet frames.
 */
class EnergyVad(
    @Volatile private var threshold: Float = 550f,
) {
    /** Effective RMS of the last frame, 0..1. */
    @Volatile
    private var level: Float = 0f

    fun level(): Float = level

    /** True when the frame's RMS exceeds [threshold]. Updates [level]. */
    fun isSpeech(pcm: ByteArray, len: Int): Boolean {
        if (len < 2) {
            level = 0f
            return false
        }
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() // sign-extended
            val sample = (hi shl 8) or lo
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) {
            level = 0f
            return false
        }
        val rms = sqrt(sum / count).toFloat()
        level = (rms / 32768f).coerceIn(0f, 1f)
        return rms > threshold
    }
}
