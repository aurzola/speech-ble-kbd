package com.example.speechblekbd.speech

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Locates and downloads Vosk models (P2.13).
 *
 * Models live under `filesDir/models/<modelName>` (the directory Vosk expects:
 * it contains `am/`, `conf/`, `graph/`…). A model can be installed either by
 * pressing "Descargar modelo" in the app (network) or by pushing it manually:
 *
 *     adb push vosk-model-small-es-0.42 /data/data/com.example.speechblekbd/files/models/
 *
 * Small models are used on purpose (~40 MB, real-time on a phone).
 */
class VoskModelStore(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelStore"

        const val MODEL_ES = "vosk-model-small-es-0.42"
        const val MODEL_EN = "vosk-model-small-en-us-0.15"

        private const val URL_ES = "https://alphacephei.com/vosk/models/$MODEL_ES.zip"
        private const val URL_EN = "https://alphacephei.com/vosk/models/$MODEL_EN.zip"
    }

    data class Spec(val modelName: String, val url: String, val humanName: String)

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    fun specFor(language: String): Spec? {
        val lang = language.lowercase()
        return when {
            lang.startsWith("es") -> Spec(MODEL_ES, URL_ES, "español")
            lang.startsWith("en") -> Spec(MODEL_EN, URL_EN, "inglés")
            else -> null
        }
    }

    fun languageName(language: String): String = specFor(language)?.humanName ?: language

    /** Returns the model directory if it is installed for [language], else null. */
    fun installed(language: String): File? {
        val spec = specFor(language) ?: return null
        val dir = File(modelsDir, spec.modelName)
        return if (isModelDir(dir)) dir else null
    }

    private fun isModelDir(dir: File): Boolean =
        dir.isDirectory && (File(dir, "conf").isDirectory || File(dir, "am").isDirectory)

    /**
     * Downloads and unzips the model for [language]. Blocking: call from a
     * background thread. [onProgress] receives (downloaded, total) bytes; total
     * may be -1 when the server does not report a length.
     */
    fun download(language: String, onProgress: (downloaded: Long, total: Long) -> Unit): File {
        val spec = specFor(language) ?: throw IOException("No hay modelo para el idioma $language")
        installed(language)?.let { return it }

        modelsDir.mkdirs()
        val tmp = File(context.cacheDir, "${spec.modelName}.zip")
        try {
            downloadTo(spec.url, tmp, onProgress)
            unzip(tmp, modelsDir)
        } finally {
            tmp.delete()
        }

        val dir = File(modelsDir, spec.modelName)
        if (!isModelDir(dir)) throw IOException("El modelo descargado no es válido")
        Log.i(TAG, "installed model ${spec.modelName}")
        return dir
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.connect()
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code al descargar el modelo")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, destDir: File) {
        val destPath = destDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                // Guard against zip-slip.
                if (!out.canonicalPath.startsWith(destPath)) {
                    throw IOException("Entrada de ZIP insegura: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
