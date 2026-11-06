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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var nfcAdapter: NfcAdapter? = null
    private var mediaPlayer: MediaPlayer? = null

    // Holds the currently active card for audio association
    private var currentCardInfo: CardInfo? = null

    // Flag indicating if file picker was launched specifically for mobile phone pairing
    private var isPickingForPhone: Boolean = false

    // Debounce window to prevent rapid restarts
    private var lastTagDetectedTime: Long = 0L
    private val debounceIntervalMs: Long = 2500L

    // Launcher for picking custom audio files
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleCustomAudioSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreenWake()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupUI()
        updateAudioTrackDisplay()
        updatePhoneNfcDisplay()

        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

            tag?.let { handleDiscoveredTag(it) }
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

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    private fun setupUI() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

        binding.btnEnableNfc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }

        // Mobile Phone NFC Toggle Switch
        val isPhoneEnabled = prefs.getBoolean("phone_nfc_enabled", true)
        binding.switchPhoneNfc.isChecked = isPhoneEnabled
        binding.switchPhoneNfc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("phone_nfc_enabled", isChecked).apply()
            updatePhoneNfcDisplay()
        }

        // Mobile Phone Audio selector button
        binding.btnSetPhoneAudio.setOnClickListener {
            isPickingForPhone = true
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnAssignAudioForCard.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnChangeCardAudio.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnSelectAudio.setOnClickListener {
            isPickingForPhone = false
            currentCardInfo = null
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnResetAudio.setOnClickListener {
            resetDefaultAudio()
        }

        binding.btnPlayAudio.setOnClickListener {
            playCurrentConfiguredAudio(trigger = "Manual")
        }

        binding.btnStopAudio.setOnClickListener {
            stopAudio()
        }
    }

    private fun updatePhoneNfcDisplay() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("phone_nfc_enabled", true)
        val phoneSongName = prefs.getString("phone_paired_audio_name", null)

        if (!isEnabled) {
            binding.layoutPhoneAudioInfo.alpha = 0.5f
            binding.tvPhoneAudioTrack.text = "Phone NFC is Disabled"
            binding.btnSetPhoneAudio.isEnabled = false
        } else {
            binding.layoutPhoneAudioInfo.alpha = 1.0f
            binding.btnSetPhoneAudio.isEnabled = true
            if (phoneSongName != null) {
                binding.tvPhoneAudioTrack.text = "Phone Song: $phoneSongName"
                binding.btnSetPhoneAudio.text = getString(R.string.btn_change_phone_audio)
            } else {
                binding.tvPhoneAudioTrack.text = getString(R.string.phone_nfc_track_none)
                binding.btnSetPhoneAudio.text = getString(R.string.btn_set_phone_audio)
            }
        }
    }

    private fun checkNfcAvailability() {
        when {
            nfcAdapter == null -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_unsupported)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.btnEnableNfc.visibility = View.GONE
                binding.tvDetectionStatus.text = getString(R.string.nfc_status_unsupported)
            }
            !nfcAdapter!!.isEnabled -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_disabled)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.btnEnableNfc.visibility = View.VISIBLE
                binding.tvDetectionStatus.text = "NFC Disabled - Turn on NFC"
            }
            else -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_ready)
                binding.tvNfcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.btnEnableNfc.visibility = View.GONE
                if (currentCardInfo == null) {
                    binding.tvDetectionStatus.text = getString(R.string.detection_waiting)
                }
            }
        }
    }

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
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Handles detected tag, differentiating between Debit Cards and Mobile Phones.
     */
    private fun handleDiscoveredTag(tag: Tag) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTagDetectedTime < debounceIntervalMs) {
            return
        }
        lastTagDetectedTime = now

        val cardInfo = EmvCardReader.readCard(tag)
        currentCardInfo = cardInfo
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

        // MECHANISM 1: DEBIT / CREDIT CARDS (100% separate from phones)
        if (cardInfo.isEmv) {
            val savedAudioPath = prefs.getString("card_audio_path_${cardInfo.id}", null)
            val savedAudioName = prefs.getString("card_audio_name_${cardInfo.id}", null)
            val hasAssociatedAudio = savedAudioPath != null && File(savedAudioPath).exists()

            isPickingForPhone = false
            runOnUiThread {
                binding.tvDetectionStatus.text = "Card Detected: ${cardInfo.displayName}"
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))

                if (hasAssociatedAudio) {
                    binding.layoutNewCardPrompt.visibility = View.GONE
                    binding.layoutCardLinked.visibility = View.VISIBLE
                    binding.tvLinkedCardTitle.text = "Card Recognized"
                    binding.tvLinkedAudioName.text = "Playing: $savedAudioName"

                    playFile(File(savedAudioPath!!), savedAudioName ?: "Card Audio", cardInfo.displayName)
                } else {
                    binding.layoutCardLinked.visibility = View.GONE
                    binding.layoutNewCardPrompt.visibility = View.VISIBLE
                    binding.tvNewCardTitle.text = "New Card: ${cardInfo.displayName}"
                    binding.tvNewCardDesc.text = "No audio is associated with this card yet. Choose a song to link with it."
                    binding.btnAssignAudioForCard.text = getString(R.string.btn_assign_audio)
                }
            }
            return
        }

        // MECHANISM 2: MOBILE PHONES
        val isPhoneEnabled = prefs.getBoolean("phone_nfc_enabled", true)
        if (!isPhoneEnabled) {
            runOnUiThread {
                binding.tvDetectionStatus.text = "Phone Detected (Disabled in Settings)"
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            }
            return
        }

        // Mobile Phone NFC is enabled: check paired phone song
        val phoneAudioPath = prefs.getString("phone_paired_audio_path", null)
        val phoneAudioName = prefs.getString("phone_paired_audio_name", null)

        if (phoneAudioPath != null && File(phoneAudioPath).exists()) {
            // Already paired: Play immediately! Never asks again.
            runOnUiThread {
                binding.tvDetectionStatus.text = "Mobile Phone Detected!"
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.layoutNewCardPrompt.visibility = View.GONE
                binding.layoutCardLinked.visibility = View.VISIBLE
                binding.tvLinkedCardTitle.text = "Phone Recognized"
                binding.tvLinkedAudioName.text = "Playing: $phoneAudioName"
            }
            playFile(File(phoneAudioPath), phoneAudioName ?: "Phone Song", "Mobile Phone")
        } else {
            // First time: prompt user to pick the phone's song
            isPickingForPhone = true
            runOnUiThread {
                binding.tvDetectionStatus.text = "Mobile Phone Detected!"
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.layoutCardLinked.visibility = View.GONE
                binding.layoutNewCardPrompt.visibility = View.VISIBLE
                binding.tvNewCardTitle.text = "New Phone Detected!"
                binding.tvNewCardDesc.text = "Select the song you want mobile phones to play."
                binding.btnAssignAudioForCard.text = "Choose Song for Phone"
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

            val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

            if (isPickingForPhone) {
                // Save as persistent Phone audio
                val targetFile = File(filesDir, "phone_paired_song")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                prefs.edit()
                    .putString("phone_paired_audio_path", targetFile.absolutePath)
                    .putString("phone_paired_audio_name", fileName)
                    .apply()

                updatePhoneNfcDisplay()

                binding.layoutNewCardPrompt.visibility = View.GONE
                binding.layoutCardLinked.visibility = View.VISIBLE
                binding.tvLinkedCardTitle.text = "Paired: Mobile Phone"
                binding.tvLinkedAudioName.text = "Associated Audio: $fileName"

                playFile(targetFile, fileName, "Mobile Phone")
                isPickingForPhone = false
                return
            }

            val card = currentCardInfo
            if (card != null && card.isEmv) {
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
                playFile(targetFile, fileName, "Default Trigger")
            }
        } catch (_: Exception) {}
    }

    private fun resetDefaultAudio() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("is_custom_audio", false).apply()
        val customFile = File(filesDir, "custom_card_sound")
        if (customFile.exists()) {
            customFile.delete()
        }
        updateAudioTrackDisplay()
    }

    private fun updateAudioTrackDisplay() {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)
        val isCustom = prefs.getBoolean("is_custom_audio", false)
        val name = prefs.getString("custom_audio_name", null)

        val customFile = File(filesDir, "custom_card_sound")
        if (isCustom && customFile.exists() && name != null) {
            binding.tvCurrentTrack.text = "Sound: $name"
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
                }
                start()
            }

            binding.tvAudioStatus.text = "Status: Playing ($trackName)"
        } catch (_: Exception) {}
    }

    private fun playCurrentConfiguredAudio(trigger: String) {
        val prefs = getSharedPreferences("nfc_prefs", MODE_PRIVATE)

        val phoneAudioPath = prefs.getString("phone_paired_audio_path", null)
        val phoneAudioName = prefs.getString("phone_paired_audio_name", null)
        if (phoneAudioPath != null && File(phoneAudioPath).exists()) {
            playFile(File(phoneAudioPath), phoneAudioName ?: "Phone Audio", trigger)
            return
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
                    }
                    start()
                }
                binding.tvAudioStatus.text = "Status: Playing (Default Chime)"
            } catch (_: Exception) {}
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
        }
        binding.tvAudioStatus.text = getString(R.string.audio_status_stopped)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
