package com.runvoice.history.ui

import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale

internal fun formatHistoryMonth(month: YearMonth): String =
    "${month.year}年${month.monthValue}月"

internal fun formatHistoryDate(epochMillis: Long): String =
    SimpleDateFormat("M月d日  HH:mm", Locale.getDefault()).format(Date(epochMillis))

internal fun formatHistoryFullDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

internal fun formatHistoryDistance(distanceMeters: Float): String =
    String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1_000f)

internal fun formatHistoryDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}

internal fun formatHistoryPace(secondsPerKm: Int?): String {
    if (secondsPerKm == null || secondsPerKm <= 0) return "--'--\"/km"
    return "%d'%02d\"/km".format(secondsPerKm / 60, secondsPerKm % 60)
}
