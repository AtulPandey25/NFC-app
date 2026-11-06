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

    // Debounce window to prevent rapid restarts
    private var lastTagDetectedTime: Long = 0L
    private val debounceIntervalMs: Long = 2000L

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
            playCurrentConfiguredAudio(trigger = "Manual")
        }

        binding.btnStopAudio.setOnClickListener {
            stopAudio()
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
                binding.tvDetectionStatus.text = "NFC Turned Off"
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
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150)
        }

        adapter.enableReaderMode(this, { tag ->
            handleDiscoveredTag(tag)
        }, flags, extras)
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Fast card discovery callback.
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

        val savedAudioPath = prefs.getString("card_audio_path_${cardInfo.id}", null)
        val savedAudioName = prefs.getString("card_audio_name_${cardInfo.id}", null)
        val hasAssociatedAudio = savedAudioPath != null && File(savedAudioPath).exists()

        runOnUiThread {
            binding.tvDetectionStatus.text = cardInfo.displayName
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
                binding.tvNewCardDesc.text = "No audio is linked to this card yet. Choose a song to link with it."
                binding.btnAssignAudioForCard.text = getString(R.string.btn_assign_audio)
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
                    }
                    start()
                }
                binding.tvAudioStatus.text = "Status: Playing (Default Sound)"
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
