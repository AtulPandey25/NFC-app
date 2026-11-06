package com.example.nfcdebitplayer

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nfcdebitplayer.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var nfcAdapter: NfcAdapter? = null
    private var mediaPlayer: MediaPlayer? = null

    // Holds the currently active or pending card/device for audio association
    private var currentCardInfo: CardInfo? = null

    // Debounce window to prevent repeated restarts while device is held near phone
    private var lastTagDetectedTime: Long = 0L
    private val debounceIntervalMs: Long = 2500L

    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logEntries = StringBuilder()

    // Launcher for picking custom audio files
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleCustomAudioSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow app to show & wake up if phone was locked or sleeping
        setupScreenWake()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupUI()
        updateAudioTrackDisplay()
        appendLog("App initialized.")

        // Show this device's own HCE phone ID
        val localPhoneId = PhoneHceService.getOrCreateDeviceId(this)
        appendLog("This device NFC ID: $localPhoneId")
        appendLog("Background NFC active: Can wake when closed.")

        // Handle cold-start from background NFC tap
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle warm-start / background NFC tap
        handleNfcIntent(intent)
    }

    private fun setupScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    /**
     * Handles background NFC launch intents when app was closed.
     */
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                appendLog("Background NFC trigger received ($action)")
                handleDiscoveredTag(it)
            }
        }
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
        // Do not immediately kill audio if triggered from background
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    private fun setupUI() {
        binding.btnEnableNfc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }

        binding.btnAssignAudioForCard.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnChangeCardAudio.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnSelectAudio.setOnClickListener {
            currentCardInfo = null
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnResetAudio.setOnClickListener {
            resetDefaultAudio()
        }

        binding.btnPlayAudio.setOnClickListener {
            appendLog("Manual 'Test Play' button pressed.")
            playCurrentConfiguredAudio(trigger = "Manual")
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

    private fun checkNfcAvailability() {
        when {
            nfcAdapter == null -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_unsupported)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.btnEnableNfc.visibility = View.GONE
                binding.tvDetectionStatus.text = getString(R.string.nfc_status_unsupported)
                appendLog("ERROR: Device does not have NFC hardware.")
            }
            !nfcAdapter!!.isEnabled -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_disabled)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.btnEnableNfc.visibility = View.VISIBLE
                binding.tvDetectionStatus.text = "NFC Disabled - Turn on NFC"
                appendLog("WARNING: NFC is turned OFF in device settings.")
            }
            else -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_ready)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.btnEnableNfc.visibility = View.GONE
                if (currentCardInfo == null) {
                    binding.tvDetectionStatus.text = getString(R.string.detection_waiting)
                }
                appendLog("NFC Hardware ready & enabled.")
            }
        }
    }

    /**
     * Enables Android Reader Mode detecting Cards, Phones, and Tags.
     */
    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        adapter.enableReaderMode(this, { tag ->
            handleDiscoveredTag(tag)
        }, flags, extras)

        appendLog("NFC Reader Mode active (Cards & Phones supported).")
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
        appendLog("NFC Reader Mode paused.")
    }

    /**
     * Callback invoked when an NFC card or phone is presented.
     */
    private fun handleDiscoveredTag(tag: Tag) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTagDetectedTime < debounceIntervalMs) {
            return
        }
        lastTagDetectedTime = now

        // Read card/phone identification
        val cardInfo = EmvCardReader.readCard(tag)
        currentCardInfo = cardInfo

        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        val savedAudioPath = prefs.getString("card_audio_path_${cardInfo.id}", null)
        val savedAudioName = prefs.getString("card_audio_name_${cardInfo.id}", null)

        val hasAssociatedAudio = savedAudioPath != null && File(savedAudioPath).exists()

        runOnUiThread {
            binding.tvDetectionStatus.text = "Detected: ${cardInfo.displayName}"
            binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))

            binding.layoutTagDetails.visibility = View.VISIBLE
            binding.tvTagTechList.text = "Type: ${cardInfo.scheme}\nIdentifier: ${cardInfo.displayName}"

            if (hasAssociatedAudio) {
                // Device/Card recognized with associated audio!
                binding.layoutNewCardPrompt.visibility = View.GONE
                binding.layoutCardLinked.visibility = View.VISIBLE
                binding.tvLinkedCardTitle.text = "Recognized: ${cardInfo.displayName}"
                binding.tvLinkedAudioName.text = "Associated Audio: $savedAudioName"

                appendLog("DEVICE RECOGNIZED: ${cardInfo.displayName}")
                appendLog("Playing mapped audio: $savedAudioName")

                playFile(File(savedAudioPath!!), savedAudioName ?: "Audio", cardInfo.displayName)
            } else {
                // First time this card or phone is detected
                binding.layoutCardLinked.visibility = View.GONE
                binding.layoutNewCardPrompt.visibility = View.VISIBLE
                binding.tvNewCardTitle.text = "New Device: ${cardInfo.displayName}"
                binding.tvNewCardDesc.text = "No audio is associated with this device yet. Tap below to select an audio file."

                appendLog("NEW DEVICE DETECTED: ${cardInfo.displayName}")
                appendLog("Prompting user to choose audio for this device...")
            }
        }
    }

    private fun handleCustomAudioSelected(uri: Uri) {
        try {
            var fileName = "Custom Audio"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: "Custom Audio"
                }
            }

            val card = currentCardInfo
            val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

            if (card != null) {
                val targetFile = File(filesDir, "card_audio_${card.id}")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                prefs.edit()
                    .putString("card_audio_path_${card.id}", targetFile.absolutePath)
                    .putString("card_audio_name_${card.id}", fileName)
                    .putString("card_display_name_${card.id}", card.displayName)
                    .apply()

                binding.layoutNewCardPrompt.visibility = View.GONE
                binding.layoutCardLinked.visibility = View.VISIBLE
                binding.tvLinkedCardTitle.text = "Linked: ${card.displayName}"
                binding.tvLinkedAudioName.text = "Associated Audio: $fileName"

                appendLog("SUCCESS: Linked '$fileName' to ${card.displayName}!")
                appendLog("Next time this device/card is tapped, it will play '$fileName' automatically.")

                playFile(targetFile, fileName, card.displayName)
            } else {
                val targetFile = File(filesDir, "custom_card_sound")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                prefs.edit()
                    .putBoolean("is_custom_audio", true)
                    .putString("custom_audio_name", fileName)
                    .apply()

                updateAudioTrackDisplay()
                appendLog("Default audio updated to: $fileName")
                playFile(targetFile, fileName, "Default Trigger")
            }
        } catch (e: Exception) {
            appendLog("Error setting audio: ${e.message}")
        }
    }

    private fun resetDefaultAudio() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("is_custom_audio", false).apply()
        val customFile = File(filesDir, "custom_card_sound")
        if (customFile.exists()) {
            customFile.delete()
        }
        updateAudioTrackDisplay()
        appendLog("Reset default audio to chime.")
    }

    private fun updateAudioTrackDisplay() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        val isCustom = prefs.getBoolean("is_custom_audio", false)
        val name = prefs.getString("custom_audio_name", null)

        val customFile = File(filesDir, "custom_card_sound")
        if (isCustom && customFile.exists() && name != null) {
            binding.tvCurrentTrack.text = "Default Audio: $name"
            binding.btnResetAudio.visibility = View.VISIBLE
        } else {
            binding.tvCurrentTrack.text = getString(R.string.audio_current_track_default)
            binding.btnResetAudio.visibility = View.GONE
        }
    }

    private fun playFile(file: File, trackName: String, triggerSource: String) {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    binding.tvAudioStatus.text = getString(R.string.audio_status_stopped)
                    appendLog("Playback finished ($trackName).")
                }
                start()
            }

            binding.tvAudioStatus.text = "Status: Playing ($trackName)"
            appendLog("Playing: $trackName ($triggerSource)...")
        } catch (e: Exception) {
            appendLog("Audio Playback Error: ${e.message}")
        }
    }

    private fun playCurrentConfiguredAudio(trigger: String) {
        val card = currentCardInfo
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

        if (card != null) {
            val savedPath = prefs.getString("card_audio_path_${card.id}", null)
            val savedName = prefs.getString("card_audio_name_${card.id}", null)
            if (savedPath != null && File(savedPath).exists()) {
                playFile(File(savedPath), savedName ?: "Card Audio", trigger)
                return
            }
        }

        val isCustom = prefs.getBoolean("is_custom_audio", false)
        val customFile = File(filesDir, "custom_card_sound")
        val customName = prefs.getString("custom_audio_name", "Custom Audio")

        if (isCustom && customFile.exists()) {
            playFile(customFile, customName ?: "Custom Audio", trigger)
        } else {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
                mediaPlayer = MediaPlayer.create(this, R.raw.card_chime).apply {
                    setOnCompletionListener {
                        binding.tvAudioStatus.text = getString(R.string.audio_status_stopped)
                        appendLog("Chime completed.")
                    }
                    start()
                }
                binding.tvAudioStatus.text = "Status: Playing (Default Chime)"
                appendLog("Playing Default Chime ($trigger)...")
            } catch (e: Exception) {
                appendLog("Audio Error: ${e.message}")
            }
        }
    }

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

    private fun appendLog(message: String) {
        val timestamp = logDateFormat.format(Date())
        val entry = "[$timestamp] $message\n"
        logEntries.append(entry)

        runOnUiThread {
            binding.tvDiagnosticLog.text = logEntries.toString()
        }
    }
}
