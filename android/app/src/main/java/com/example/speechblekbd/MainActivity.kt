package com.example.speechblekbd

import android.Manifest
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

class MainActivity : AppCompatActivity(), NusClient.Listener {

    companion object {
        private const val PARTIAL_THROTTLE_MS = 200L
        private const val MAX_DOC_LEN = 200

        /** Keyboard layout selected by default (Spanish). */
        private const val DEFAULT_LAYOUT = "ES"

        /** Enter keywords, matched against folded (lowercase, no accents) text. */
        private val ENTER_PHRASE = Regex(
            "\\b(?:enter|intro|nueva\\s+linea|salto\\s+de\\s+linea|linea\\s+nueva)\\b"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var nus: NusClient
    private lateinit var speech: ContinuousSpeech

    private var listening = false
    private var pendingConnect = false
    private var pendingLayout = DEFAULT_LAYOUT

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

        nus = NusClient(this, this)
        speech = ContinuousSpeech(
            context = this,
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
        )

        setupLayoutSpinner()

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

    /** Send the whole document (committed + live partial) so the ESP32 can diff. */
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
        // The daemon resets its mirror on Enter, so drop ours to stay in sync.
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
        val langs = resources.getStringArray(R.array.layout_langs)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.layoutSpinner.adapter = adapter
        binding.layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = codes[position]
                speech.language = langs[position]
                pendingLayout = code
                if (nus.isConnected()) {
                    nus.send("L:$code")
                    log("layout → $code")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // Start on the host layout by default instead of the first array entry.
        val defaultPosition = codes.indexOfFirst { it == DEFAULT_LAYOUT }.coerceAtLeast(0)
        binding.layoutSpinner.setSelection(defaultPosition)
        speech.language = langs[defaultPosition]
        pendingLayout = codes[defaultPosition]
    }

    private fun toggleListening() {
        if (!speech.isAvailable()) {
            toast("Reconocimiento de voz no disponible en este teléfono")
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        listening = !listening
        if (listening) {
            speech.start()
            binding.listenButton.text = getString(R.string.stop_dictation)
        } else {
            speech.stop()
            binding.listenButton.text = getString(R.string.start_dictation)
            binding.partialText.text = ""
        }
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
        speech.release()
        nus.disconnect()
        super.onDestroy()
    }
}
