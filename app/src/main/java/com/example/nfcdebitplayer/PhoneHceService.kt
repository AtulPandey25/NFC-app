package com.example.nfcdebitplayer

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.util.UUID

class PhoneHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return STATUS_FAILED
        }

        // Check if command is SELECT AID F0010203040506
        val hex = commandApdu.joinToString("") { "%02X".format(it) }
        return if (hex.contains("F0010203040506")) {
            val deviceId = getOrCreateDeviceId(this)
            val idBytes = deviceId.toByteArray(Charsets.UTF_8)
            val response = ByteArray(idBytes.size + 2)
            System.arraycopy(idBytes, 0, response, 0, idBytes.size)
            response[response.size - 2] = 0x90.toByte()
            response[response.size - 1] = 0x00.toByte()
            response
        } else {
            STATUS_FAILED
        }
    }

    override fun onDeactivated(reason: Int) {}

    companion object {
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
            var id = prefs.getString("local_device_nfc_id", null)
            if (id == null) {
                id = "PHONE_" + UUID.randomUUID().toString().substring(0, 8).uppercase()
                prefs.edit().putString("local_device_nfc_id", id).apply()
            }
            return id
        }
    }
}
