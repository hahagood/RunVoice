package com.runvoice.voice

/** Compact numeric forms used by spoken running-stat announcements. */
internal object VoiceStatsText {
    fun pace(secondsPerKm: Int): String {
        require(secondsPerKm >= 0)
        val minutes = secondsPerKm / 60
        val seconds = secondsPerKm % 60
        val compactDigits = "$minutes${seconds.toString().padStart(2, '0')}"
        // Use the ideographic zero here. Unlike “零” in a continuous Chinese
        // numeral, “〇” explicitly represents a digit and keeps a trailing zero audible.
        return digits(compactDigits, one = '幺', zero = '〇')
    }

    fun heartRate(beatsPerMinute: Int): String {
        require(beatsPerMinute >= 0)
        return digits(beatsPerMinute.toString(), one = '幺', zero = '零')
    }

    private fun digits(value: String, one: Char, zero: Char): String =
        value.map { digitForSpeech(it, one, zero) }.joinToString("")

    private fun digitForSpeech(digit: Char, one: Char, zero: Char): Char = when (digit) {
        '0' -> zero
        '1' -> one
        '2' -> '二'
        '3' -> '三'
        '4' -> '四'
        '5' -> '五'
        '6' -> '六'
        '7' -> '七'
        '8' -> '八'
        '9' -> '九'
        else -> error("Unsupported speech digit: $digit")
    }
}
