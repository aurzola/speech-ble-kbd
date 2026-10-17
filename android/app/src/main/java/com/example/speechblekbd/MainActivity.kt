package com.example.speechblekbd

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.speechblekbd.ble.NusClient
import com.example.speechblekbd.databinding.ActivityMainBinding
import com.example.speechblekbd.speech.ContinuousSpeech
import com.example.speechblekbd.speech.DictationCallbacks
import com.example.speechblekbd.speech.DictationEngine
import com.example.speechblekbd.speech.VoskModelStore
import com.example.speechblekbd.speech.VoskSpeechEngine

class MainActivity : AppCompatActivity(), NusClient.Listener {

    companion object {
        private const val PARTIAL_THROTTLE_MS = 200L
        private const val MAX_DOC_LEN = 200

        /** Keyboard layout selected by default (Spanish). */
        private const val DEFAULT_LAYOUT = "ES"

        /** Dictation (recognition) language selected by default. */
        private const val DEFAULT_DICTATION_LANG = "es-ES"

        private const val PREFS = "speech_ble_kbd"
        private const val PREF_ENGINE = "engine"
        private const val PREF_DICTATION_LANG = "dictation_lang"
        private const val PREF_LAYOUT = "layout"

        /** Enter keywords, matched against folded (lowercase, no accents) text. */
        private val ENTER_PHRASE = Regex(
            "\\b(?:enter|intro|nueva\\s+linea|salto\\s+de\\s+linea|linea\\s+nueva)\\b"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var nus: NusClient
    private lateinit var prefs: SharedPreferences
    private lateinit var modelStore: VoskModelStore

    /** Dictation engines by id, in menu order. */
    private val engines = LinkedHashMap<String, DictationEngine>()
    private var activeEngine: DictationEngine? = null
    private var activeEngineId = "system"

    private var listening = false
    private var pendingConnect = false
    private var pendingLayout = DEFAULT_LAYOUT
    private var dictationLanguage = DEFAULT_DICTATION_LANG
    private var downloading = false

    /** Text already committed on the host. The live partial is streamed on top. */
    private val committed = StringBuilder()
    private var lastPartialSent = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            when {
                denied.isNotEmpty() -> {
                    pendingConnect = false
                    toast("Permisos denegados: $denied")
                }
                pendingConnect -> {
                    pendingConnect = false
                    nus.startScan()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        modelStore = VoskModelStore(this)
        nus = NusClient(this, this)

        val callbacks = DictationCallbacks(
            onPartial = { partial ->
                binding.partialText.text = "$committed$partial"
                streamPartial(partial)
            },
            onFinal = { text ->
                emitUserText(text, trailingSpace = true)
                binding.partialText.text = committed.toString()
                lastPartialSent = 0
            },
            onState = { log(it) },
            onLevel = { level -> binding.levelBar.progress = (level * 100).toInt() },
            onError = { message, fatal ->
                log("voz: $message")
                if (fatal && listening) stopListening()
            },
        )

        engines["system"] = ContinuousSpeech(this, callbacks)
        engines["vosk"] = VoskSpeechEngine(this, modelStore, callbacks)

        setupLayoutSpinner()
        setupDictationSpinner()
        setupEngineSpinner()

        binding.connectButton.setOnClickListener {
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                nus.startScan()
            } else {
                pendingConnect = true
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        binding.listenButton.setOnClickListener { toggleListening() }

        binding.downloadModelButton.setOnClickListener { downloadModel() }

        binding.sendTextButton.setOnClickListener {
            val text = binding.manualInput.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                emitUserText(text, trailingSpace = false)
                binding.manualInput.text?.clear()
            }
        }

        binding.backspaceButton.setOnClickListener {
            if (committed.isNotEmpty()) {
                committed.deleteCharAt(committed.length - 1)
                if (nus.isConnected()) nus.send("B:1")
                log("manual → B:1")
            }
        }

        binding.enterButton.setOnClickListener { pressEnter() }

        binding.clearButton.setOnClickListener {
            committed.setLength(0)
            if (nus.isConnected()) nus.send("C")
            log("manual → C")
        }

        log("Listo. Pulsa Conectar y luego Iniciar dictado.")
    }

    /** Send the whole document (committed + live partial) so the host can diff. */
    private fun streamPartial(partial: String) {
        if (!nus.isConnected()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPartialSent < PARTIAL_THROTTLE_MS) return
        lastPartialSent = now
        val doc = ("$committed$partial").take(480)
        nus.send("S:$doc")
    }

    /** Append text to the document and push it to the device. */
    private fun commit(text: String) {
        if (committed.length + text.length > MAX_DOC_LEN) {
            // Too long to keep a reliable mirror: clear the host text and restart.
            if (nus.isConnected()) nus.send("C")
            committed.setLength(0)
        }
        committed.append(text)
        if (nus.isConnected()) {
            nus.send("S:$committed")
            log("→ $text")
        } else {
            log("(sin conexión) $text")
        }
    }

    /**
     * Emit a user utterance. Keywords (enter, intro, nueva línea, salto de
     * línea, línea nueva) become an Enter on the host; the surrounding text is
     * typed normally. Dictation adds a trailing space between utterances.
     */
    private fun emitUserText(original: String, trailingSpace: Boolean) {
        val text = original.trim()
        if (text.isEmpty()) return
        val folded = buildString(text.length) {
            for (c in text) append(foldChar(c.lowercaseChar()))
        }
        val matches = ENTER_PHRASE.findAll(folded).toList()
        if (matches.isEmpty()) {
            commit(if (trailingSpace) "$text " else text)
            return
        }
        var pos = 0
        for (m in matches) {
            text.substring(pos, m.range.first).trim().takeIf { it.isNotEmpty() }?.let(::commit)
            pressEnter()
            pos = m.range.last + 1
        }
        text.substring(pos).trim().takeIf { it.isNotEmpty() }?.let(::commit)
    }

    private fun pressEnter() {
        // The host resets its mirror on Enter, so drop ours to stay in sync.
        committed.setLength(0)
        if (nus.isConnected()) {
            nus.send("E")
            log("→ Enter")
        } else {
            log("(sin conexión) Enter")
        }
    }

    /** Lowercase one char without changing length (é→e, ñ→n, Á→a). */
    private fun foldChar(c: Char): Char {
        val decomposed = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        return decomposed[0]
    }

    private fun setupLayoutSpinner() {
        val labels = resources.getStringArray(R.array.layout_labels)
        val codes = resources.getStringArray(R.array.layout_codes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.layoutSpinner.adapter = adapter
        binding.layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = codes[position]
                pendingLayout = code
                prefs.edit().putString(PREF_LAYOUT, code).apply()
                if (nus.isConnected()) {
                    nus.send("L:$code")
                    log("layout → $code")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val saved = prefs.getString(PREF_LAYOUT, DEFAULT_LAYOUT) ?: DEFAULT_LAYOUT
        val defaultPosition = codes.indexOfFirst { it == saved }.coerceAtLeast(0)
        binding.layoutSpinner.setSelection(defaultPosition)
        pendingLayout = codes[defaultPosition]
    }

    private fun setupDictationSpinner() {
        val labels = resources.getStringArray(R.array.dictation_labels)
        val langs = resources.getStringArray(R.array.dictation_langs)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.dictationSpinner.adapter = adapter
        binding.dictationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                dictationLanguage = langs[position]
                activeEngine?.language = dictationLanguage
                prefs.edit().putString(PREF_DICTATION_LANG, dictationLanguage).apply()
                updateModelStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val saved = prefs.getString(PREF_DICTATION_LANG, DEFAULT_DICTATION_LANG) ?: DEFAULT_DICTATION_LANG
        val position = langs.indexOfFirst { it == saved }.coerceAtLeast(0)
        binding.dictationSpinner.setSelection(position)
        dictationLanguage = langs[position]
    }

    private fun setupEngineSpinner() {
        val ids = engines.keys.toList()
        val labels = ids.map { engines.getValue(it).label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.engineSpinner.adapter = adapter
        binding.engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyEngine(ids[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val saved = prefs.getString(PREF_ENGINE, "system") ?: "system"
        activeEngineId = saved.takeIf { engines.containsKey(it) } ?: "system"
        binding.engineSpinner.setSelection(ids.indexOf(activeEngineId).coerceAtLeast(0))
        applyEngine(activeEngineId, persist = false)
    }

    /** Switch the active dictation engine, restarting it if we were listening. */
    private fun applyEngine(id: String, persist: Boolean = true) {
        val engine = engines[id] ?: return
        val wasListening = listening
        if (wasListening) stopListening()
        activeEngine = engine
        activeEngineId = id
        engine.language = dictationLanguage
        if (persist) prefs.edit().putString(PREF_ENGINE, id).apply()
        updateModelStatus()
        if (wasListening && engine.isAvailable()) startListening()
    }

    private fun updateModelStatus() {
        if (activeEngineId != "vosk") {
            binding.modelStatusText.text = getString(R.string.model_needed)
            binding.downloadModelButton.visibility = View.GONE
            return
        }
        val spec = modelStore.specFor(dictationLanguage)
        if (spec == null) {
            binding.modelStatusText.text = getString(R.string.model_unsupported)
            binding.downloadModelButton.visibility = View.GONE
            return
        }
        val installed = engines.getValue("vosk").isAvailable()
        binding.modelStatusText.text = if (installed) {
            getString(R.string.model_installed, spec.humanName)
        } else {
            getString(R.string.model_missing, spec.humanName)
        }
        binding.downloadModelButton.visibility = if (installed || downloading) View.GONE else View.VISIBLE
    }

    private fun downloadModel() {
        if (downloading) return
        val spec = modelStore.specFor(dictationLanguage) ?: return
        downloading = true
        binding.downloadModelButton.isEnabled = false
        binding.downloadModelButton.visibility = View.GONE
        log("Descargando modelo ${spec.humanName}…")
        var lastPct = -1
        Thread {
            try {
                modelStore.download(dictationLanguage) { done, total ->
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            runOnUiThread {
                                binding.modelStatusText.text =
                                    getString(R.string.model_downloading, spec.humanName, pct)
                            }
                        }
                    }
                }
                runOnUiThread {
                    downloading = false
                    binding.downloadModelButton.isEnabled = true
                    log("Modelo ${spec.humanName} listo")
                    updateModelStatus()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    downloading = false
                    binding.downloadModelButton.isEnabled = true
                    log("Error descargando modelo: ${t.message}")
                    updateModelStatus()
                }
            }
        }.start()
    }

    private fun toggleListening() {
        val engine = activeEngine ?: return
        if (!engine.isAvailable()) {
            toast(engine.unavailableReason())
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        val engine = activeEngine ?: return
        listening = true
        engine.language = dictationLanguage
        engine.start()
        binding.listenButton.text = getString(R.string.stop_dictation)
    }

    private fun stopListening() {
        listening = false
        activeEngine?.stop()
        binding.listenButton.text = getString(R.string.start_dictation)
        binding.levelBar.progress = 0
        binding.partialText.text = ""
    }

    private fun requiredPermissions(): List<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed
    }

    private fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun log(line: String) {
        val current = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (current.isEmpty()) line else "$current\n$line"
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /* ----- NusClient.Listener ----- */

    override fun onStatus(text: String) = log(text)

    override fun onConnected() {
        log("BLE listo: NUS conectado")
        nus.send("L:$pendingLayout")
    }

    override fun onDisconnected() {
        log("BLE desconectado")
        committed.setLength(0)
    }

    override fun onData(text: String) = log("ESP32 → $text")

    override fun onDestroy() {
        engines.values.forEach { it.release() }
        nus.disconnect()
        super.onDestroy()
    }
}
