package com.runvoice.core

sealed interface AnnouncementEvent {
    data class Kilometer(val kilometer: Int) : AnnouncementEvent
    data class CurrentPace(val paceSecondsPerKm: Int) : AnnouncementEvent
}

/** Decides distance-triggered announcements without knowing about TTS or Android. */
class AnnouncementPolicy(private val quarterKilometerMeters: Int = 250) {
    private var lastKilometer = 0
    private var lastQuarter = 0

    fun reset() {
        lastKilometer = 0
        lastQuarter = 0
    }

    fun eventsFor(distanceMeters: Float, paceSecondsPerKm: Int): List<AnnouncementEvent> {
        val currentKilometer = (distanceMeters / 1_000).toInt()
        if (currentKilometer > lastKilometer && currentKilometer > 0) {
            lastKilometer = currentKilometer
            lastQuarter = maxOf(lastQuarter, (distanceMeters / quarterKilometerMeters).toInt())
            return listOf(AnnouncementEvent.Kilometer(currentKilometer))
        }

        val currentQuarter = (distanceMeters / quarterKilometerMeters).toInt()
        if (currentQuarter <= lastQuarter) return emptyList()
        if (currentQuarter <= 0 || currentQuarter % 4 == 0) {
            lastQuarter = currentQuarter
            return emptyList()
        }
        if (paceSecondsPerKm <= 0) return emptyList()
        lastQuarter = currentQuarter
        return listOf(AnnouncementEvent.CurrentPace(paceSecondsPerKm))
    }
}
