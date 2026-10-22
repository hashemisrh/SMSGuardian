package com.example.smsguardian

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.net.Uri
import android.provider.Telephony
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.util.Log

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrDefault(emptyArray())
        if (messages.isEmpty()) return
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (RuleStore.isBlocked(context, sender, body)) {
            // As the default SMS handler, we intentionally do not insert blocked messages into the SMS provider.
            Log.i("SMSGuardian", "Filtered incoming SMS from ${sender.take(4)}…")
            return
        }
        runCatching {
            val date = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            showSmsNotification(context, sender, body)
        }.onFailure { Log.e("SMSGuardian", "Could not store SMS in provider", it) }
    }
}


private fun lookupSenderDisplayName(context: Context, sender: String): String {
    if (sender.isBlank()) return "پیامک جدید"
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return sender
    return runCatching {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(sender).build()
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        } ?: sender
    }.getOrDefault(sender)
}

private fun showSmsNotification(context: Context, sender: String, body: String) {
    val channelId = "incoming_sms"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "پیامک‌های جدید", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "اعلان پیامک‌های دریافتی"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pending = launchIntent?.let {
        PendingIntent.getActivity(context, 7001, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Log.w("SMSGuardian", "Notification permission not granted; incoming SMS notification skipped")
        return
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(com.example.smsguardian.R.drawable.ic_notification_minimal)
        .setContentTitle(lookupSenderDisplayName(context, sender))
        .setContentText(body.ifBlank { "پیامک جدید دریافت شد" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .apply { if (pending != null) setContentIntent(pending) }
        .build()
    NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
}

/** MMS is declared so Android can offer the default SMS role; this starter app does not yet parse/store MMS. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SMSGuardian", "MMS delivery received; MMS processing is not implemented in this starter build")
    }
}

object SmsDeliveryStatus {
    private const val PREFS = "sms_delivery_status"
    fun key(address: String, body: String): String = (address.filter { it.isDigit() || it == '+' } + "|" + body).hashCode().toString()
    fun isDelivered(context: android.content.Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(key, false)
    fun markDelivered(context: android.content.Context, key: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        if (intent.action == "com.example.smsguardian.SMS_DELIVERED") {
            if (resultCode == Activity.RESULT_OK) {
                intent.getStringExtra("delivery_key")?.let { SmsDeliveryStatus.markDelivered(context, it) }
                // Notify an open activity so its checkmark changes from one to two.
                context.sendBroadcast(Intent("com.example.smsguardian.DELIVERY_STATUS_CHANGED").setPackage(context.packageName))
            }
            return
        }
        Log.i("SMSGuardian", "SMS send result: $resultCode")
        if (resultCode != Activity.RESULT_OK) {
            val message = when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "ارسال ناموفق: شبکه تلفن همراه در دسترس نیست."
                SmsManager.RESULT_ERROR_RADIO_OFF -> "ارسال ناموفق: رادیوی تلفن یا سیم‌کارت خاموش است."
                SmsManager.RESULT_ERROR_NULL_PDU -> "ارسال ناموفق: اطلاعات پیامک نامعتبر است."
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "ارسال ناموفق: خطای عمومی شبکه/اپراتور یا اعتبار سیم‌کارت."
                else -> "ارسال پیامک ناموفق بود. کد خطا: $resultCode"
            }
            android.os.Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // Successful sends are indicated by one checkmark in the chat instead of a noisy toast.
    }
}
