package com.atul.nfcaudioplayer

data class PreloadedTagAudio(
    val rawResId: Int,
    val title: String
)

object PreloadedTags {

    val MAPPING: Map<String, PreloadedTagAudio> = mapOf(
        "5315EFD7950001" to PreloadedTagAudio(R.raw.ananya_cutie, "ananya cutie"),
        "5317EFD7950001" to PreloadedTagAudio(R.raw.ananya_boat, "ananya boat"),
        "5316EFD7950001" to PreloadedTagAudio(R.raw.ananya_zoo, "ananya zoo"),
        "5310EFD7950001" to PreloadedTagAudio(R.raw.ananya_dhun, "ananya dhun"),
        "5318EFD7950001" to PreloadedTagAudio(R.raw.ananya_hareya, "ananya hareya"),
        "5320EFD7950001" to PreloadedTagAudio(R.raw.ananya_nahi_nahi, "ananya Nahi nahi"),
        "530DEFD7950001" to PreloadedTagAudio(R.raw.ananya_bematalab, "ananya bematalab"),
        "531FEFD7950001" to PreloadedTagAudio(R.raw.ananya_naina, "ananya naina"),
        "530EEFD7950001" to PreloadedTagAudio(R.raw.ananya_hasi, "ananya Hasi"),
        "530FEFD7950001" to PreloadedTagAudio(R.raw.ananya_samma, "ananya samma")
    )

    fun get(tagIdOrUid: String): PreloadedTagAudio? {
        val clean = tagIdOrUid.removePrefix("TAG_").trim().uppercase()
        return MAPPING[clean]
    }
}
