package com.jarvis.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var hologram: ImageView
    private var tts: TextToSpeech? = null

    companion object {
        private const val SPEECH_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        message = findViewById(R.id.assistantMessage)
        hologram = findViewById(R.id.hologramImage)

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.voiceButton).setOnClickListener {
            listenToUser()
        }

        findViewById<Button>(R.id.cameraButton).setOnClickListener {
            message.text = "Camera vision will be added next."
            status.text = "CAMERA MODULE NOT CONNECTED"
        }

        findViewById<Button>(R.id.commandButton).setOnClickListener {
            message.text = "Voice commands will be connected next."
            status.text = "COMMAND CENTER READY"
        }

        hologram.alpha = 0.85f
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.US)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                status.text = "TTS LANGUAGE NOT AVAILABLE"
            } else {
                status.text = "SYSTEM ONLINE • VOICE READY"
                speak("Hello Sahil. JARVIS is online.")
            }
        } else {
            status.text = "VOICE OUTPUT UNAVAILABLE"
        }
    }

    private fun listenToUser() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
        }

        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: Exception) {
            message.text = "Speech recognition is not available."
            Toast.makeText(this, "Check Google speech services.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated Android callback retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )
            val spokenText = results?.firstOrNull() ?: return

            message.text = "You: $spokenText"
            status.text = "VOICE INPUT RECEIVED"
            handleCommand(spokenText)
        }
    }

    private fun handleCommand(command: String) {
        val text = command.lowercase(Locale.ROOT)

        when {
            text.contains("open youtube") || text.contains("youtube kholo") -> {
                openApp("com.google.android.youtube", "Opening YouTube.")
            }

            text.contains("open whatsapp") || text.contains("whatsapp kholo") -> {
                openApp("com.whatsapp", "Opening WhatsApp.")
            }

            text.contains("hello") || text.contains("hey jarvis") -> {
                speak("Hello Sahil. How can I help you?")
            }

            else -> {
                speak("I heard you say: $command. More AI features will be connected next.")
            }
        }
    }

    private fun openApp(packageName: String, reply: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            speak(reply)
            startActivity(launchIntent)
        } else {
            speak("That app is not installed.")
        }
    }

    private fun speak(text: String) {
        message.text = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
