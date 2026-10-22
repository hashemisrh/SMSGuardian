package com.example.smsguardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local, user-configured SMS forwarding. It is always visible through an ongoing notification while enabled. */
object SmsForwarding {
    const val PREFS = "sms_guardian_forwarding"
    const val CHANNEL = "sms_forwarding_status"
    const val NOTIFICATION_ID = 7341
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun showStatus(c: Context, message: String = "SMS forwarding is enabled") {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "وضعیت فوروارد پیامک", NotificationManager.IMPORTANCE_LOW))
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
        val pi = launch?.let { PendingIntent.getActivity(c, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_minimal)
            .setContentTitle("SMS Guardian · فوروارد پیامک")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) nm.notify(NOTIFICATION_ID, notification)
    }
    fun hideStatus(c: Context) { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
}

class SmsForwardingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !SmsForwarding.isEnabled(context)) return
        val p = context.getSharedPreferences(SmsForwarding.PREFS, Context.MODE_PRIVATE)
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrDefault(emptyArray())
        if (messages.isEmpty()) return
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val source = p.getString("source", "").orEmpty().trim()
        val keyword = p.getString("keyword", "").orEmpty().trim()
        if (source.isNotEmpty() && normalize(sender) != normalize(source)) return
        if (keyword.isNotEmpty() && !body.contains(keyword, ignoreCase = true)) return
        val destinations = (1..3).map { p.getString("destination$it", "").orEmpty().trim() }.filter { it.isNotEmpty() }.distinct()
        if (destinations.isEmpty()) return
        var ok = 0; var failed = 0
        val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else defaultSmsManager()
        for (destination in destinations) {
            try {
                val parts = manager.divideMessage(body)
                if (parts.size > 1) manager.sendMultipartTextMessage(destination, null, parts, null, null) else manager.sendTextMessage(destination, null, body, null, null)
                // SmsManager transmits messages but does not reliably add them to the system
                // Sent provider. Persist the forwarded copy so it appears in that recipient's chat.
                runCatching {
                    val sentAt = System.currentTimeMillis()
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, destination)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, sentAt)
                        put(Telephony.Sms.DATE_SENT, sentAt)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    }
                    context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                }.onFailure { android.util.Log.e("SMSGuardian", "Forwarded SMS sent but could not be saved in Sent history", it) }
                ok++
            } catch (_: Exception) { failed++ }
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val historyLine = "$now | $sender | $ok sent / $failed failed\n"
        var history = historyLine + p.getString("history", "").orEmpty()
        if (history.length > 8000) history = history.take(8000)
        p.edit().putString("last", "$now → $ok sent / $failed failed")
            .putString("history", history)
            .putInt("total_ok", p.getInt("total_ok", 0) + ok)
            .putInt("total_failed", p.getInt("total_failed", 0) + failed)
            .putInt("day_${day}_ok", p.getInt("day_${day}_ok", 0) + ok)
            .putInt("day_${day}_fail", p.getInt("day_${day}_fail", 0) + failed)
            .putInt("month_${month}_ok", p.getInt("month_${month}_ok", 0) + ok)
            .putInt("month_${month}_fail", p.getInt("month_${month}_fail", 0) + failed).apply()
        SmsForwarding.showStatus(context, "فعال • آخرین فوروارد: $now • موفق: $ok، ناموفق: $failed")
    }
    @Suppress("DEPRECATION")
    private fun defaultSmsManager(): SmsManager = SmsManager.getDefault()

    private fun normalize(s: String): String = s.filter { it.isDigit() || it == '+' }.let { when { it.startsWith("+98") -> "0" + it.drop(3); it.startsWith("0098") -> "0" + it.drop(4); else -> it } }
}

class SmsForwardingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) && SmsForwarding.isEnabled(context)) SmsForwarding.showStatus(context)
    }
}
