package com.example.smsguardian

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import android.net.Uri

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val destination = intent?.data?.schemeSpecificPart.orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (destination.isNotBlank() && text.isNotBlank()) runCatching {
            SmsManager.getDefault().sendTextMessage(destination, null, text, null, null)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
