package com.runvoice.voice

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.runvoice.core.VolumeKeyPolicy

/**
 * Watches the media stream volume so Bluetooth headset volume keys can trigger a stats
 * announcement.
 *
 * A headset key press arrives as an AVRCP volume command handled inside the Bluetooth stack, so no
 * KeyEvent is ever delivered to the app and neither a media button receiver nor an accessibility
 * service can see it. Observing the resulting volume change is the only channel available.
 *
 * While a run is in progress and the screen is off, any change is treated as a key press: the
 * previous level is written back and [onAnnounce] fires. With the screen on the user is adjusting
 * volume on purpose, so the new level is adopted instead.
 */
class VolumeKeyWatcher(
    context: Context,
    private val runActiveProvider: () -> Boolean,
    private val onAnnounce: () -> Unit,
    private val policy: VolumeKeyPolicy = VolumeKeyPolicy()
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handleVolumeChanged()
        }
    }

    private var registered = false

    fun start() {
        val volume = currentVolume() ?: run {
            Log.w(TAG, "No AudioManager; volume key announcements are unavailable")
            return
        }
        policy.arm(volume)
        if (registered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer
            )
        }.onSuccess {
            registered = true
            Log.i(TAG, "Volume key watcher armed at volume=$volume")
        }.onFailure {
            policy.disarm()
            Log.w(TAG, "Unable to observe volume changes", it)
        }
    }

    fun stop() {
        policy.disarm()
        if (!registered) return
        registered = false
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
            .onFailure { Log.w(TAG, "Unable to stop observing volume changes", it) }
        Log.i(TAG, "Volume key watcher disarmed")
    }

    private fun handleVolumeChanged() {
        val volume = currentVolume() ?: return
        val decision = policy.onVolumeChanged(
            volume = volume,
            screenInteractive = powerManager?.isInteractive ?: true,
            runActive = runActiveProvider(),
            nowMillis = SystemClock.elapsedRealtime()
        )
        when (decision) {
            is VolumeKeyPolicy.Decision.AnnounceAndRestore -> {
                restoreVolume(decision.restoreTo)
                Log.i(TAG, "Volume key press at $volume; announcing and restoring ${decision.restoreTo}")
                onAnnounce()
            }
            is VolumeKeyPolicy.Decision.RestoreOnly -> restoreVolume(decision.restoreTo)
            is VolumeKeyPolicy.Decision.AdoptAsLock ->
                Log.i(TAG, "Volume changed on purpose; protecting ${decision.volume}")
            VolumeKeyPolicy.Decision.Ignore -> Unit
        }
    }

    private fun restoreVolume(target: Int) {
        val manager = audioManager ?: return
        policy.noteSelfWrite(target, SystemClock.elapsedRealtime())
        // Writing without FLAG_SHOW_UI keeps the volume panel from waking the screen mid-run.
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { Log.w(TAG, "Unable to restore volume to $target", it) }
    }

    private fun currentVolume(): Int? =
        audioManager?.let { runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() }

    private companion object {
        const val TAG = "RunVoiceVolumeKey"
    }
}
