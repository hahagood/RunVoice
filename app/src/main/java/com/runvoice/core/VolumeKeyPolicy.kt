package com.runvoice.core

/**
 * Turns Bluetooth headset volume keys into an announcement trigger.
 *
 * Under AVRCP absolute volume a headset key press never reaches the app as a KeyEvent: the
 * Bluetooth stack calls straight into AudioService and the media stream volume simply moves.
 * The only usable signal is that volume change, so this policy watches it and asks the caller to
 * write the locked level back, which makes the keys behave like function keys instead of volume
 * keys while a run is in progress.
 *
 * Deliberate volume changes still work. The screen is lit whenever the user adjusts volume from
 * the phone, and dark whenever the press came from the headset in a pocket, so [screenInteractive]
 * separates the two cases without any extra permission.
 */
class VolumeKeyPolicy(
    private val announceIntervalMillis: Long = DEFAULT_ANNOUNCE_INTERVAL_MILLIS,
    private val selfWriteWindowMillis: Long = DEFAULT_SELF_WRITE_WINDOW_MILLIS,
    private val minChangeToTrigger: Int = DEFAULT_MIN_CHANGE_TO_TRIGGER
) {
    init {
        require(announceIntervalMillis > 0L)
        require(selfWriteWindowMillis > 0L)
        require(minChangeToTrigger > 0)
    }

    sealed interface Decision {
        /** A headset key moved the volume: restore [restoreTo] and announce the run stats. */
        data class AnnounceAndRestore(val restoreTo: Int) : Decision

        /** Same, but too soon after the previous announcement to speak again. */
        data class RestoreOnly(val restoreTo: Int) : Decision

        /** The user changed volume on purpose; [volume] becomes the level to protect. */
        data class AdoptAsLock(val volume: Int) : Decision

        /** Echo of our own write, or no change worth acting on. */
        object Ignore : Decision
    }

    private var lockedVolume = UNSET
    private var lastAnnouncedAtMillis: Long? = null
    private var pendingSelfWrite = UNSET
    private var pendingSelfWriteAtMillis: Long? = null

    val isArmed: Boolean get() = lockedVolume != UNSET

    /** Starts protecting [currentVolume]. Call when a run begins. */
    fun arm(currentVolume: Int) {
        lockedVolume = currentVolume
        lastAnnouncedAtMillis = null
        clearPendingSelfWrite()
    }

    /** Stops protecting any level. Call when the run ends so idle volume is never touched. */
    fun disarm() {
        lockedVolume = UNSET
        clearPendingSelfWrite()
    }

    /**
     * Records a restore this policy just asked for, so the resulting change notification is not
     * mistaken for another key press.
     */
    fun noteSelfWrite(volume: Int, nowMillis: Long) {
        pendingSelfWrite = volume
        pendingSelfWriteAtMillis = nowMillis
    }

    fun onVolumeChanged(
        volume: Int,
        screenInteractive: Boolean,
        runActive: Boolean,
        nowMillis: Long
    ): Decision {
        if (!isArmed) return Decision.Ignore

        val selfWriteAt = pendingSelfWriteAtMillis
        if (pendingSelfWrite == volume &&
            selfWriteAt != null &&
            nowMillis - selfWriteAt <= selfWriteWindowMillis
        ) {
            clearPendingSelfWrite()
            return Decision.Ignore
        }
        clearPendingSelfWrite()

        // Volume belongs to the user whenever they can see what they are doing, and whenever no
        // run is collecting stats worth announcing.
        if (screenInteractive || !runActive) {
            lockedVolume = volume
            return Decision.AdoptAsLock(volume)
        }

        // Absolute volume rounds between the headset's 0-127 scale and the phone's own range, so a
        // restore can come back off by a step. A real key press moves several units at once.
        if (kotlin.math.abs(volume - lockedVolume) < minChangeToTrigger) return Decision.Ignore

        val restoreTo = lockedVolume
        val announcedAt = lastAnnouncedAtMillis
        if (announcedAt != null && nowMillis - announcedAt < announceIntervalMillis) {
            return Decision.RestoreOnly(restoreTo)
        }
        lastAnnouncedAtMillis = nowMillis
        return Decision.AnnounceAndRestore(restoreTo)
    }

    private fun clearPendingSelfWrite() {
        pendingSelfWrite = UNSET
        pendingSelfWriteAtMillis = null
    }

    companion object {
        const val UNSET = -1

        /** Repeated presses inside this window restore volume without speaking again. */
        const val DEFAULT_ANNOUNCE_INTERVAL_MILLIS = 1_500L

        /** How long a restore may take to come back as a change notification. */
        const val DEFAULT_SELF_WRITE_WINDOW_MILLIS = 1_000L

        /** Smaller moves are absolute-volume rounding, not a key press. */
        const val DEFAULT_MIN_CHANGE_TO_TRIGGER = 3
    }
}
