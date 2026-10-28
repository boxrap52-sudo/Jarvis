package com.jarvis.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    companion object {
        private const val REQ_MIC = 101
        private const val PREFS = "jarvis_prefs"
        private const val KEY_LANG = "lang"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val LANG_EN = "en-IN"
        private const val LANG_HI = "hi-IN"
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_MIC_ACTIVE = 0xFFFF5252.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val animators = mutableListOf<ObjectAnimator>()
    private var isRunning = false

    private lateinit var statusText: TextView
    private lateinit var userText: TextView
    private lateinit var replyText: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnLang: TextView

    private var state = State.IDLE
    private var langTag = LANG_EN

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLangOk = false

    private val resetStatus = Runnable { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        userText = findViewById(R.id.user_text)
        replyText = findViewById(R.id.reply_text)
        btnMic = findViewById(R.id.btn_mic)
        btnLang = findViewById(R.id.btn_lang)

        langTag = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, LANG_EN) ?: LANG_EN
        updateLangLabel()
        initTts()
        renderStatus()

        btnMic.setOnClickListener { onMicClicked() }
        btnLang.setOnClickListener { toggleLanguage() }
        findViewById<View>(R.id.btn_camera).setOnClickListener { flash(getString(R.string.status_camera_soon)) }
        findViewById<View>(R.id.btn_command).setOnClickListener { showGeminiSettings() }
    }

    override fun onStart() {
        super.onStart()
        startAnimations()
    }

    override fun onStop() {
        super.onStop()
        stopAnimations()
        // Never keep the microphone or voice running in the background
        recognizer?.cancel()
        tts?.stop()
        handler.removeCallbacks(resetStatus)
        setState(State.IDLE)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Mic

    private fun onMicClicked() {
        when (state) {
            State.IDLE -> startListening()
            State.LISTENING -> recognizer?.stopListening()
            State.SPEAKING -> {
                tts?.stop()
                setState(State.IDLE)
            }
            State.PROCESSING -> {
                recognizer?.cancel()
                setState(State.IDLE)
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            flash(getString(R.string.err_no_recognizer))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Ask only when the user actually taps the mic
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        try {
            tts?.stop()
            val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            userText.text = ""
            replyText.text = ""
            setState(State.LISTENING)
            sr.startListening(intent)
        } catch (e: Exception) {
            setState(State.IDLE)
            flash(getString(R.string.err_generic))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                flash(getString(R.string.err_mic_denied))
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            if (state == State.LISTENING) setState(State.PROCESSING)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                userText.text = getString(R.string.you_said, partial)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrEmpty()) {
                setState(State.IDLE)
                flash(getString(R.string.err_no_match))
                return
            }
            userText.text = getString(R.string.you_said, text)
            handleUserText(text)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                // Happens when we cancel on purpose
                setState(State.IDLE)
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.destroy()
                recognizer = null
            }
            setState(State.IDLE)
            flash(getString(errorMessage(error)))
        }
    }

    private fun errorMessage(code: Int): Int = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.err_no_match
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> R.string.err_network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.err_mic_denied
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.err_busy
        SpeechRecognizer.ERROR_AUDIO -> R.string.err_audio
        12, 13 -> R.string.err_lang // LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE (Android 12+)
        else -> R.string.err_generic
    }

    // ---------------------------------------------------- Conversation hook

    /**
     * Stage 3 placeholder: repeats what you said.
     * Stage 4 will replace this with the real AI brain.
     */
    private fun handleUserText(text: String) {
        setState(State.PROCESSING)
        val key = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_GEMINI, null)
            ?.trim()
            .orEmpty()

        if (key.isEmpty()) {
            setState(State.IDLE)
            showGeminiSettings()
            return
        }

        aiExecutor.execute {
            var reply: String? = null
            var error: String? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" +
                        java.net.URLEncoder.encode(key, "UTF-8")
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val languageInstruction = if (langTag == LANG_HI) {
                    "Reply in simple Hindi written in Devanagari."
                } else {
                    "Reply in simple conversational English."
                }
                val body = JSONObject()
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "You are JARVIS, a helpful phone voice assistant. " +
                                        languageInstruction +
                                        " Keep answers concise and useful. Never claim you performed phone actions unless actually done."
                                )
                            )
                        )
                    )
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", text)))
                        )
                    )
                    .put(
                        "generationConfig",
                        JSONObject().put("temperature", 0.7).put("maxOutputTokens", 500)
                    )

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val json = JSONObject(responseText)
                    reply = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.let { parts ->
                            (0 until parts.length()).joinToString(" ") {
                                parts.optJSONObject(it)?.optString("text").orEmpty()
                            }.trim()
                        }
                        ?.takeIf { it.isNotBlank() }
                    if (reply == null) error = "Gemini returned no text."
                } else {
                    error = "Gemini API error ($code): " +
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                            .orEmpty().ifBlank { "Check your API key and internet connection." }
                }
            } catch (e: Exception) {
                error = e.message ?: "Network error."
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (reply != null) {
                        speak(reply!!)
                    } else {
                        setState(State.IDLE)
                        flash("AI error: ${error ?: "Unknown error"}")
                    }
                }
            }
        }
    }

    private fun showGeminiSettings() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_GEMINI, "")
            )
        }

        AlertDialog.Builder(this)
            .setTitle("JARVIS AI Settings")
            .setMessage("Enter your Gemini API key. It will be stored on this device.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    flash("API key is empty.")
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString(KEY_GEMINI, key).apply()
                    flash("Gemini API key saved.")
                }
            }
            .setNeutralButton("Remove key") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().remove(KEY_GEMINI).apply()
                flash("API key removed.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- TTS

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            runOnUiThread {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            runOnUiThread { setState(State.SPEAKING) }
                        }

                        override fun onDone(utteranceId: String?) {
                            runOnUiThread { setState(State.IDLE) }
                        }

                        override fun onError(utteranceId: String?) {
                            runOnUiThread { setState(State.IDLE) }
                        }
                    })
                    applyTtsLanguage()
                } else {
                    ttsReady = false
                    flash(getString(R.string.err_tts_unavailable))
                }
            }
        }
    }

    private fun applyTtsLanguage() {
        val locale = if (langTag == LANG_HI) Locale("hi", "IN") else Locale("en", "IN")
        val result = tts?.setLanguage(locale)
        ttsLangOk = result != null &&
            result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun speak(text: String) {
        replyText.text = getString(R.string.jarvis_said, text)
        if (!ttsReady || !ttsLangOk) {
            setState(State.IDLE)
            flash(getString(if (ttsReady) R.string.err_tts_lang else R.string.err_tts_unavailable))
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    // ------------------------------------------------------------ Language

    private fun toggleLanguage() {
        recognizer?.cancel()
        tts?.stop()
        setState(State.IDLE)
        langTag = if (langTag == LANG_HI) LANG_EN else LANG_HI
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LANG, langTag).apply()
        updateLangLabel()
        applyTtsLanguage()
        flash(getString(if (langTag == LANG_HI) R.string.status_lang_hindi else R.string.status_lang_english))
    }

    private fun updateLangLabel() {
        btnLang.setText(if (langTag == LANG_HI) R.string.lang_hindi else R.string.lang_english)
    }

    // ------------------------------------------------------------- Status

    private fun setState(newState: State) {
        state = newState
        renderStatus()
    }

    private fun renderStatus() {
        statusText.setText(
            when (state) {
                State.IDLE -> R.string.status_standby
                State.LISTENING -> R.string.status_listening
                State.PROCESSING -> R.string.status_processing
                State.SPEAKING -> R.string.status_speaking
            }
        )
        // Red mic while the microphone is really active, so it is always obvious
        val color = if (state == State.LISTENING) COLOR_MIC_ACTIVE else COLOR_CYAN
        btnMic.imageTintList = ColorStateList.valueOf(color)
    }

    /** Shows a message for a few seconds, then goes back to the normal status. */
    private fun flash(message: String) {
        statusText.text = message
        handler.removeCallbacks(resetStatus)
        handler.postDelayed(resetStatus, 3500)
    }

    // ---------------------------------------------------------- Animations

    private fun startAnimations() {
        stopAnimations()
        isRunning = true

        val hologram = findViewById<View>(R.id.hologram)
        val dot = findViewById<View>(R.id.status_dot)
        val ring = findViewById<View>(R.id.mic_ring)
        val scan = findViewById<View>(R.id.scan_line)

        add(ObjectAnimator.ofPropertyValuesHolder(
            hologram,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.82f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.025f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.025f)
        ).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        })

        add(ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        })

        add(ObjectAnimator.ofPropertyValuesHolder(
            ring,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.2f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        })

        scan.post {
            if (!isRunning) return@post
            val height = (scan.parent as View).height.toFloat()
            add(ObjectAnimator.ofFloat(scan, View.TRANSLATION_Y, 0f, height).apply {
                duration = 4200
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            })
        }
    }

    private fun add(animator: ObjectAnimator) {
        animators.add(animator)
        animator.start()
    }

    private fun stopAnimations() {
        isRunning = false
        animators.forEach { it.cancel() }
        animators.clear()
    }
}
