package com.example.nfcdebitplayer

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.security.MessageDigest

data class CardInfo(
    val id: String,           // Unique key for mapping (SHA-256 hash, Phone ID, or UID)
    val displayName: String,  // User-facing name (e.g. "Visa •••• 1234" or "NFC Phone A1B2")
    val scheme: String,       // "Visa", "Mastercard", "RuPay", "Android Phone", "NFC Device", etc.
    val isEmv: Boolean
)

object EmvCardReader {

    // Custom AID for phone-to-phone pairing with this app
    private val PHONE_APP_AID = byteArrayOf(0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

    private val PPSE = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    private val KNOWN_AIDS = listOf(
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x10, 0x10), // Visa Credit/Debit
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x20, 0x10), // Visa Electron
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x04, 0x10, 0x10), // Mastercard
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x04, 0x30, 0x60), // Maestro
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x05, 0x24, 0x10, 0x10), // RuPay
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x25, 0x01, 0x04)  // Amex
    )

    /**
     * Reads card, phone, or NFC tag interaction.
     * Detects:
     * 1. Other NFC phones running this app (via custom HCE AID).
     * 2. Contactless EMV debit/credit cards (Visa, Mastercard, RuPay, etc.).
     * 3. Generic NFC phones / devices / tags.
     */
    fun readCard(tag: Tag): CardInfo {
        val isoDep = IsoDep.get(tag)
        val uidHex = tag.id.joinToString("") { "%02X".format(it) }

        if (isoDep == null) {
            return CardInfo(
                id = uidHex,
                displayName = "NFC Tag ($uidHex)",
                scheme = "Generic NFC",
                isEmv = false
            )
        }

        try {
            isoDep.timeout = 2000
            isoDep.connect()

            // 1. Check if the touching device is another phone running our app
            val phoneApdu = createSelectApdu(PHONE_APP_AID)
            val phoneResp = isoDep.transceive(phoneApdu)
            if (isSuccess(phoneResp) && phoneResp.size > 2) {
                val returnedId = String(phoneResp, 0, phoneResp.size - 2, Charsets.UTF_8).trim()
                if (returnedId.startsWith("PHONE_")) {
                    isoDep.close()
                    val shortId = returnedId.removePrefix("PHONE_")
                    return CardInfo(
                        id = returnedId,
                        displayName = "Phone ($shortId)",
                        scheme = "NFC Smartphone",
                        isEmv = false
                    )
                }
            }

            // 2. Not a companion phone; check if it's an EMV contactless payment card
            var pan: String? = null
            var scheme = "Card"

            val ppseApdu = createSelectApdu(PPSE)
            val ppseResp = isoDep.transceive(ppseApdu)

            val aids = mutableListOf<ByteArray>()
            if (isSuccess(ppseResp)) {
                aids.addAll(extractAids(ppseResp))
            }
            if (aids.isEmpty()) {
                aids.addAll(KNOWN_AIDS)
            }

            for (aid in aids) {
                scheme = detectScheme(aid)
                val selectAidApdu = createSelectApdu(aid)
                val aidResp = isoDep.transceive(selectAidApdu)

                if (!isSuccess(aidResp)) continue

                val pdol = extractPdol(aidResp)
                val gpoApdu = createGpoApdu(pdol)
                val gpoResp = isoDep.transceive(gpoApdu)

                if (isSuccess(gpoResp)) {
                    pan = extractPanFromTlv(gpoResp)
                    if (pan != null) break

                    val afl = extractAfl(gpoResp)
                    for ((sfi, record) in afl) {
                        val readApdu = byteArrayOf(
                            0x00.toByte(),
                            0xB2.toByte(),
                            record.toByte(),
                            ((sfi shl 3) or 4).toByte(),
                            0x00.toByte()
                        )
                        val recordResp = isoDep.transceive(readApdu)
                        if (isSuccess(recordResp)) {
                            pan = extractPanFromTlv(recordResp)
                            if (pan != null) break
                        }
                    }
                    if (pan != null) break
                }
            }

            isoDep.close()

            if (pan != null) {
                val cleanPan = pan.replace("[^0-9]".toRegex(), "")
                val last4 = if (cleanPan.length >= 4) cleanPan.takeLast(4) else cleanPan
                val masked = if (cleanPan.length >= 8) {
                    "${cleanPan.take(4)} •••• •••• $last4"
                } else {
                    "•••• $last4"
                }
                val hashId = sha256("CARD_$cleanPan")

                return CardInfo(
                    id = hashId,
                    displayName = "$scheme ($masked)",
                    scheme = scheme,
                    isEmv = true
                )
            }
        } catch (_: Exception) {
            try { isoDep.close() } catch (_: Exception) {}
        }

        // 3. Fallback: If it's a phone without our app or another ISO-DEP device
        val isLikelyPhone = uidHex.startsWith("08") || uidHex.length == 8
        val deviceLabel = if (isLikelyPhone) "NFC Device / Phone ($uidHex)" else "Contactless Card ($uidHex)"
        val schemeLabel = if (isLikelyPhone) "NFC Phone/Device" else "ISO-DEP"

        return CardInfo(
            id = uidHex,
            displayName = deviceLabel,
            scheme = schemeLabel,
            isEmv = false
        )
    }

    private fun isSuccess(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 2) return false
        val sw1 = resp[resp.size - 2].toInt() and 0xFF
        val sw2 = resp[resp.size - 1].toInt() and 0xFF
        return (sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61
    }

    private fun createSelectApdu(data: ByteArray): ByteArray {
        val apdu = ByteArray(5 + data.size + 1)
        apdu[0] = 0x00.toByte()
        apdu[1] = 0xA4.toByte()
        apdu[2] = 0x04.toByte()
        apdu[3] = 0x00.toByte()
        apdu[4] = data.size.toByte()
        System.arraycopy(data, 0, apdu, 5, data.size)
        apdu[apdu.size - 1] = 0x00.toByte()
        return apdu
    }

    private fun createGpoApdu(pdolLen: Int): ByteArray {
        val data = ByteArray(2 + pdolLen)
        data[0] = 0x83.toByte()
        data[1] = pdolLen.toByte()

        val apdu = ByteArray(5 + data.size + 1)
        apdu[0] = 0x80.toByte()
        apdu[1] = 0xA8.toByte()
        apdu[2] = 0x00.toByte()
        apdu[3] = 0x00.toByte()
        apdu[4] = data.size.toByte()
        System.arraycopy(data, 0, apdu, 5, data.size)
        apdu[apdu.size - 1] = 0x00.toByte()
        return apdu
    }

    private fun extractAids(bytes: ByteArray): List<ByteArray> {
        val aids = mutableListOf<ByteArray>()
        var i = 0
        while (i < bytes.size - 2) {
            if (bytes[i] == 0x4F.toByte()) {
                val len = bytes[i + 1].toInt() and 0xFF
                if (i + 2 + len <= bytes.size && len in 5..16) {
                    val aid = bytes.copyOfRange(i + 2, i + 2 + len)
                    aids.add(aid)
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return aids
    }

    private fun extractPdol(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == 0x9F.toByte() && bytes[i + 1] == 0x38.toByte()) {
                val len = bytes[i + 2].toInt() and 0xFF
                var total = 0
                var p = i + 3
                while (p < i + 3 + len) {
                    val tag = bytes[p].toInt() and 0xFF
                    val isTwoByte = (tag and 0x1F) == 0x1F
                    p += if (isTwoByte) 2 else 1
                    if (p < bytes.size) {
                        total += (bytes[p].toInt() and 0xFF)
                        p++
                    }
                }
                return total
            }
            i++
        }
        return 0
    }

    private fun extractAfl(bytes: ByteArray): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < bytes.size - 2) {
            if (bytes[i] == 0x94.toByte()) {
                val len = bytes[i + 1].toInt() and 0xFF
                var p = i + 2
                while (p + 3 < i + 2 + len && p + 3 < bytes.size) {
                    val sfi = (bytes[p].toInt() and 0xFF) shr 3
                    val start = bytes[p + 1].toInt() and 0xFF
                    val end = bytes[p + 2].toInt() and 0xFF
                    for (rec in start..end) {
                        list.add(Pair(sfi, rec))
                    }
                    p += 4
                }
                break
            }
            i++
        }
        return list
    }

    private fun extractPanFromTlv(bytes: ByteArray): String? {
        val hex = bytes.joinToString("") { "%02X".format(it) }

        val tag5aIndex = hex.indexOf("5A")
        if (tag5aIndex != -1 && tag5aIndex + 4 < hex.length) {
            try {
                val len = hex.substring(tag5aIndex + 2, tag5aIndex + 4).toInt(16) * 2
                if (tag5aIndex + 4 + len <= hex.length && len in 16..38) {
                    val rawPan = hex.substring(tag5aIndex + 4, tag5aIndex + 4 + len)
                    val pan = rawPan.replace("F", "")
                    if (pan.length in 13..19) return pan
                }
            } catch (_: Exception) {}
        }

        val tag57Index = hex.indexOf("57")
        if (tag57Index != -1 && tag57Index + 4 < hex.length) {
            try {
                val len = hex.substring(tag57Index + 2, tag57Index + 4).toInt(16) * 2
                if (tag57Index + 4 + len <= hex.length) {
                    val track2 = hex.substring(tag57Index + 4, tag57Index + 4 + len)
                    val dIndex = track2.indexOf('D')
                    if (dIndex != -1) {
                        val pan = track2.substring(0, dIndex)
                        if (pan.length in 13..19) return pan
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun detectScheme(aid: ByteArray): String {
        val hex = aid.joinToString("") { "%02X".format(it) }
        return when {
            hex.startsWith("A000000003") -> "Visa"
            hex.startsWith("A000000004") -> "Mastercard"
            hex.startsWith("A000000524") -> "RuPay"
            hex.startsWith("A000000025") -> "American Express"
            else -> "Payment Card"
        }
    }

    fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
