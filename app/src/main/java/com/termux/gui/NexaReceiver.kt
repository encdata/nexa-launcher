package com.termux.gui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NexaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GUIService.requests.add(
            GUIService.ConnectionRequest(
                intent.getStringExtra("mainSocket"),
                intent.getStringExtra("eventSocket"),
                true,
            ),
        )
        context.startService(Intent(context, GUIService::class.java))
    }
}
