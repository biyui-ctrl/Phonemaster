package com.twophone.smsbridge

data class PairBundle(
    val pairId: String,
    val joinCode: String,
    val keyText: String
) {
    fun encode(): String = listOf("SMSBRIDGE1", pairId, joinCode, keyText).joinToString(":")

    companion object {
        fun decode(text: String): PairBundle {
            val parts = text.trim().split(":")
            require(parts.size == 4 && parts[0] == "SMSBRIDGE1") {
                "Invalid pairing bundle"
            }
            return PairBundle(parts[1], parts[2], parts[3])
        }
    }
}
