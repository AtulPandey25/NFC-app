package com.example.nfcdebitplayer

import android.content.Intent
import android.media.MediaPlayer
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.nfcdebitplayer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var nfcAdapter: NfcAdapter? = null
    private var mediaPlayer: MediaPlayer? = null

    // Debounce window to prevent repeated restarts while card is held near phone
    private var lastTagDetectedTime: Long = 0L
    private val debounceIntervalMs: Long = 2500L

    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logEntries = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize NFC Adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupUI()
        appendLog("App initialized.")
        appendLog("Privacy mode active: No APDU commands, zero financial data access.")
    }

    override fun onResume() {
        super.onResume()
        checkNfcAvailability()
        enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    override fun onStop() {
        super.onStop()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    private fun setupUI() {
        binding.btnEnableNfc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }

        binding.btnPlayAudio.setOnClickListener {
            appendLog("Manual 'Play Sound' button pressed.")
            playAudio(trigger = "Manual")
        }

        binding.btnStopAudio.setOnClickListener {
            stopAudio()
        }

        binding.btnClearLog.setOnClickListener {
            logEntries.clear()
            binding.tvDiagnosticLog.text = ""
            appendLog("Log cleared.")
        }
    }

    /**
     * Checks device NFC availability and displays the appropriate status.
     */
    private fun checkNfcAvailability() {
        when {
            nfcAdapter == null -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_unsupported)
                binding.tvNfcStatus.setTextColor(getColor(R.color.status_red))
                binding.btnEnableNfc.visibility = View.GONE
                binding.tvDetectionStatus.text = getString(R.string.nfc_status_unsupported)
                appendLog("ERROR: Device does not have NFC hardware.")
            }
            !nfcAdapter!!.isEnabled -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_disabled)
                binding.tvNfcStatus.setTextColor(getColor(R.color.status_amber))
                binding.btnEnableNfc.visibility = View.VISIBLE
                binding.tvDetectionStatus.text = "NFC Disabled - Turn on NFC"
                appendLog("WARNING: NFC is turned OFF in device settings.")
            }
            else -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_ready)
                binding.tvNfcStatus.setTextColor(getColor(R.color.status_green))
                binding.btnEnableNfc.visibility = View.GONE
                binding.tvDetectionStatus.text = getString(R.string.detection_waiting)
                appendLog("NFC Hardware ready & enabled.")
            }
        }
    }

    /**
     * Enables Android Reader Mode specifically tuned for ISO-DEP contactless cards.
     * FLAG_READER_SKIP_NDEF_CHECK avoids overhead trying to parse NDEF on payment cards.
     * FLAG_READER_NO_PLATFORM_SOUNDS allows our custom audio player to provide the feedback.
     */
    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        adapter.enableReaderMode(this, { tag ->
            handleDiscoveredTag(tag)
        }, flags, extras)

        appendLog("NFC Reader Mode activated. Waiting for tag...")
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        appendLog("NFC Reader Mode paused.")
    }

    /**
     * Callback invoked when an NFC tag/contactless card enters the RF field.
     * Note: This callback executes on an Android Binder background thread.
     */
    private fun handleDiscoveredTag(tag: Tag) {
        val now = SystemClock.elapsedRealtime()

        // Debounce: prevent rapid re-triggering while same card stays in field
        if (now - lastTagDetectedTime < debounceIntervalMs) {
            return
        }
        lastTagDetectedTime = now

        // Extract technical specifications for feasibility testing
        val techList = tag.techList.map { it.substringAfterLast('.') }
        val isoDep = IsoDep.get(tag)
        val supportsIsoDep = (isoDep != null) || tag.techList.contains("android.nfc.tech.IsoDep")

        runOnUiThread {
            if (supportsIsoDep) {
                // ISO-DEP (ISO 14443-4) is the international standard used by EMV contactless cards
                binding.tvDetectionStatus.text = getString(R.string.detection_card_detected)
                binding.tvDetectionStatus.setTextColor(getColor(R.color.status_green))
                binding.layoutTagDetails.visibility = View.VISIBLE
                binding.tvTagTechList.text = "Supported Techs: $techList\nStandard: ISO/IEC 14443-4 (EMV Compatible)"

                appendLog("TAG DETECTED: IsoDep=YES | Techs=$techList")
                appendLog("Feasibility check PASSED: Card supports IsoDep.")

                // Trigger audio playback automatically
                playAudio(trigger = "NFC Debit Card")
            } else {
                // Non-ISO-DEP tag (e.g. simple NFC sticker, MIFARE Classic, etc.)
                binding.tvDetectionStatus.text = "NFC Tag Detected (Non-IsoDep)"
                binding.tvDetectionStatus.setTextColor(getColor(R.color.status_amber))
                binding.layoutTagDetails.visibility = View.VISIBLE
                binding.tvTagTechList.text = "Supported Techs: $techList\nNote: Standard EMV debit cards use IsoDep."

                appendLog("TAG DETECTED: IsoDep=NO | Techs=$techList")
                appendLog("Tag detected, but not an IsoDep payment card.")
            }
        }
    }

    /**
     * Plays the audio file stored in res/raw/card_chime.wav.
     */
    private fun playAudio(trigger: String) {
        try {
            // If already playing, stop previous instance cleanly
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }

            mediaPlayer = MediaPlayer.create(this, R.raw.card_chime).apply {
                setOnCompletionListener {
                    binding.tvAudioStatus.text = getString(R.string.audio_status_stopped)
                    appendLog("Audio playback completed.")
                }
                start()
            }

            binding.tvAudioStatus.text = "Status: Playing ($trigger trigger)"
            appendLog("Audio playing ($trigger trigger)...")
        } catch (e: Exception) {
            appendLog("Audio Error: ${e.message}")
        }
    }

    /**
     * Stops current audio playback.
     */
    private fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
                appendLog("Audio playback stopped.")
            }
        }
        binding.tvAudioStatus.text = getString(R.string.audio_status_stopped)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Appends a timestamped log entry to the UI Diagnostic Console.
     */
    private fun appendLog(message: String) {
        val timestamp = logDateFormat.format(Date())
        val entry = "[$timestamp] $message\n"
        logEntries.append(entry)

        runOnUiThread {
            binding.tvDiagnosticLog.text = logEntries.toString()
        }
    }
}
