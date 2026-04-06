package com.runvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

class MediaButtonIntentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RunVoiceMediaButton"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        if (event == null) {
            Log.w(TAG, "Received ACTION_MEDIA_BUTTON without KeyEvent")
            return
        }

        val serviceIntent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_HANDLE_MEDIA_BUTTON
            putExtra(Intent.EXTRA_KEY_EVENT, event)
        }

        try {
            context.startService(serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to forward media button to service", e)
        }
    }
}
