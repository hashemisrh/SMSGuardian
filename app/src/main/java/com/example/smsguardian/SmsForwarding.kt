package com.example.smsguardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local, user-configured SMS forwarding. A forward is counted only after Android reports send success. */
object SmsForwarding {
    const val PREFS = "sms_guardian_forwarding"
    const val CHANNEL = "sms_forwarding_status"
    const val NOTIFICATION_ID = 7341
    private const val TAG = "SMSForwarding"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun isWithinSchedule(c: Context, now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val p = prefs(c)
        val start = parseTime(p.getString("start_time", "00:00").orEmpty()) ?: return false
        val end = parseTime(p.getString("end_time", "23:59").orEmpty()) ?: return false
        val current = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return when {
            start == end -> true
            start < end -> current in start..end
            else -> current >= start || current <= end
        }
    }

    private fun parseTime(value: String): Int? {
        if (!value.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) return null
        val parts = value.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    fun showStatus(c: Context, message: String = "فوروارد پیامک فعال است") {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "وضعیت فوروارد پیامک", NotificationManager.IMPORTANCE_LOW))
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
        val pi = launch?.let { PendingIntent.getActivity(c, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_minimal)
            .setContentTitle("مدیریت پیامک · فوروارد پیامک")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) nm.notify(NOTIFICATION_ID, notification)
    }

    fun hideStatus(c: Context) {
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    /** Resolve the configured SMS subscription explicitly; this avoids relying on a vendor-specific default manager. */
    fun resolveSmsManager(context: Context): Pair<SmsManager, Int?> {
        val defaultSubId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val activeIds = if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching { context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }.orEmpty() }.getOrDefault(emptyList())
        } else emptyList()
        val selected = when {
            defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && (activeIds.isEmpty() || defaultSubId in activeIds) -> defaultSubId
            activeIds.size == 1 -> activeIds.first()
            else -> null
        }
        val manager = if (selected != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(selected)
        } else if (selected != null) {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(selected)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        Log.i(TAG, "Using SMS subscriptionId=${selected ?: "system-default"}; activeSubscriptions=$activeIds")
        return manager to selected
    }

    /** Prepare durable tracking before dispatching any SMS so fast callbacks cannot race initialization. */
    fun beginBatch(context: Context, batchId: String, sender: String, body: String, destinations: List<String>, partCounts: List<Int>) {
        val p = prefs(context)
        val e = p.edit()
            .putInt("batch_${batchId}_expected_callbacks", partCounts.sum())
            .putInt("batch_${batchId}_received_callbacks", 0)
            .putInt("batch_${batchId}_completed_destinations", 0)
            .putInt("batch_${batchId}_success", 0)
            .putInt("batch_${batchId}_failed", 0)
            .putString("batch_${batchId}_sender", sender)
        destinations.forEachIndexed { index, number ->
            e.putString("batch_${batchId}_d${index}_number", number)
                .putString("batch_${batchId}_d${index}_body", body)
                .putInt("batch_${batchId}_d${index}_parts", partCounts[index])
                .putInt("batch_${batchId}_d${index}_received", 0)
                .putInt("batch_${batchId}_d${index}_success", 0)
                .putBoolean("batch_${batchId}_d${index}_completed", false)
        }
        e.commit()
    }

    fun recordSendResult(context: Context, batchId: String, destinationIndex: Int, resultCode: Int) {
        val p = prefs(context)
        val prefix = "batch_${batchId}_d${destinationIndex}_"
        if (!p.contains(prefix + "parts") || p.getBoolean(prefix + "completed", false)) return
        val received = p.getInt(prefix + "received", 0) + 1
        val success = p.getInt(prefix + "success", 0) + if (resultCode == android.app.Activity.RESULT_OK) 1 else 0
        val batchReceived = p.getInt("batch_${batchId}_received_callbacks", 0) + 1
        val parts = p.getInt(prefix + "parts", 1)
        val e = p.edit().putInt(prefix + "received", received).putInt(prefix + "success", success)
            .putInt("batch_${batchId}_received_callbacks", batchReceived)
        if (resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "SMS send failed for destination ${p.getString(prefix + "number", "")}; resultCode=$resultCode")
        }
        if (received >= parts) completeDestination(context, p, e, batchId, destinationIndex, success == parts)
        else e.commit()
        finalizeIfComplete(context, batchId)
    }

    fun recordImmediateFailure(context: Context, batchId: String, destinationIndex: Int, reason: Throwable) {
        Log.e(TAG, "Could not submit forwarding SMS to destination index=$destinationIndex", reason)
        val p = prefs(context)
        val prefix = "batch_${batchId}_d${destinationIndex}_"
        if (!p.contains(prefix + "parts") || p.getBoolean(prefix + "completed", false)) return
        val parts = p.getInt(prefix + "parts", 1)
        val received = p.getInt("batch_${batchId}_received_callbacks", 0) + parts
        val e = p.edit().putInt(prefix + "received", parts).putInt(prefix + "success", 0)
            .putInt("batch_${batchId}_received_callbacks", received)
        completeDestination(context, p, e, batchId, destinationIndex, false)
        finalizeIfComplete(context, batchId)
    }

    private fun completeDestination(context: Context, p: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor, batchId: String, index: Int, success: Boolean) {
        val prefix = "batch_${batchId}_d${index}_"
        if (p.getBoolean(prefix + "completed", false)) return
        editor.putBoolean(prefix + "completed", true)
            .putInt("batch_${batchId}_completed_destinations", p.getInt("batch_${batchId}_completed_destinations", 0) + 1)
        if (success) {
            editor.putInt("batch_${batchId}_success", p.getInt("batch_${batchId}_success", 0) + 1)
            editor.commit()
            runCatching {
                val sentAt = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, p.getString(prefix + "number", "").orEmpty())
                    put(Telephony.Sms.BODY, p.getString(prefix + "body", "").orEmpty())
                    put(Telephony.Sms.DATE, sentAt)
                    put(Telephony.Sms.DATE_SENT, sentAt)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                }
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }.onFailure { Log.e(TAG, "SMS accepted for sending but could not be saved in Sent history", it) }
        } else {
            editor.putInt("batch_${batchId}_failed", p.getInt("batch_${batchId}_failed", 0) + 1).commit()
        }
    }

    private fun finalizeIfComplete(context: Context, batchId: String) {
        val p = prefs(context)
        val expected = p.getInt("batch_${batchId}_expected_callbacks", 0)
        val received = p.getInt("batch_${batchId}_received_callbacks", 0)
        val expectedDestinations = (0..2).count { p.contains("batch_${batchId}_d${it}_parts") }
        val completed = p.getInt("batch_${batchId}_completed_destinations", 0)
        if (expected <= 0 || received < expected || completed < expectedDestinations) return
        val ok = p.getInt("batch_${batchId}_success", 0)
        val failed = p.getInt("batch_${batchId}_failed", 0)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val sender = p.getString("batch_${batchId}_sender", "").orEmpty()
        val line = "$now | $sender | $ok sent / $failed failed\n"
        var history = line + p.getString("history", "").orEmpty()
        if (history.length > 8000) history = history.take(8000)
        val cleanup = p.edit()
            .putString("last", "$now → $ok sent / $failed failed")
            .putString("history", history)
            .putInt("total_ok", p.getInt("total_ok", 0) + ok)
            .putInt("total_failed", p.getInt("total_failed", 0) + failed)
            .putInt("day_${day}_ok", p.getInt("day_${day}_ok", 0) + ok)
            .putInt("day_${day}_fail", p.getInt("day_${day}_fail", 0) + failed)
            .putInt("month_${month}_ok", p.getInt("month_${month}_ok", 0) + ok)
            .putInt("month_${month}_fail", p.getInt("month_${month}_fail", 0) + failed)
            .remove("batch_${batchId}_expected_callbacks")
            .remove("batch_${batchId}_received_callbacks")
            .remove("batch_${batchId}_completed_destinations")
            .remove("batch_${batchId}_success")
            .remove("batch_${batchId}_failed")
            .remove("batch_${batchId}_sender")
        (0..2).forEach { index ->
            val prefix = "batch_${batchId}_d${index}_"
            listOf("number", "body", "parts", "received", "success", "completed").forEach { cleanup.remove(prefix + it) }
        }
        cleanup.commit()
        showStatus(context, "آخرین فوروارد: $now • موفق: $ok • ناموفق: $failed")
        Log.i(TAG, "Forwarding batch completed: success=$ok failed=$failed")
    }
}

class SmsForwardingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
            !SmsForwarding.isEnabled(context) || !SmsForwarding.isWithinSchedule(context)) return
        val p = context.getSharedPreferences(SmsForwarding.PREFS, Context.MODE_PRIVATE)
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrDefault(emptyArray())
        if (messages.isEmpty()) return
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val source = p.getString("source", "").orEmpty().trim()
        val keyword = p.getString("keyword", "").orEmpty().trim()
        if (source.isNotEmpty() && normalize(sender) != normalize(source)) return
        if (keyword.isNotEmpty() && !body.contains(keyword, ignoreCase = true)) return
        val destinations = (1..3).mapNotNull { index ->
            val number = p.getString("destination$index", "").orEmpty().trim()
            val active = p.getBoolean("destination${index}_enabled", true)
            number.takeIf { it.isNotEmpty() && active }
        }.distinct()
        if (destinations.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e("SMSForwarding", "Forwarding skipped: SEND_SMS permission is not granted")
            return
        }

        val batchId = UUID.randomUUID().toString()
        try {
            val (manager, subscriptionId) = SmsForwarding.resolveSmsManager(context)
            val partsByDestination = destinations.map { manager.divideMessage(body) }
            SmsForwarding.beginBatch(context, batchId, sender, body, destinations, partsByDestination.map { it.size })
            destinations.forEachIndexed { destinationIndex, destination ->
                try {
                    val parts = partsByDestination[destinationIndex]
                    val sentIntents = ArrayList<PendingIntent>(parts.size)
                    parts.indices.forEach { partIndex ->
                        val callback = Intent(context, SmsForwardingSentReceiver::class.java).apply {
                            action = "com.example.smsguardian.FORWARD_SMS_SENT"
                            putExtra("batch_id", batchId)
                            putExtra("destination_index", destinationIndex)
                            data = android.net.Uri.parse("smsforward://$batchId/$destinationIndex/$partIndex")
                        }
                        val requestCode = 31 * batchId.hashCode() + destinationIndex * 101 + partIndex
                        sentIntents.add(PendingIntent.getBroadcast(context, requestCode, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    if (parts.size > 1) manager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
                    else manager.sendTextMessage(destination, null, body, sentIntents.first(), null)
                    Log.i("SMSForwarding", "Forward queued: destination=$destination; subscriptionId=${subscriptionId ?: "system-default"}; parts=${parts.size}")
                } catch (e: Exception) {
                    SmsForwarding.recordImmediateFailure(context, batchId, destinationIndex, e)
                }
            }
        } catch (e: Exception) {
            Log.e("SMSForwarding", "Could not initialize forwarding batch", e)
            SmsForwarding.showStatus(context, "خطا در آماده‌سازی ارسال پیامک")
        }
    }

    private fun normalize(s: String): String = s.filter { it.isDigit() || it == '+' }.let {
        when {
            it.startsWith("+98") -> "0" + it.drop(3)
            it.startsWith("0098") -> "0" + it.drop(4)
            else -> it
        }
    }
}

class SmsForwardingSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.smsguardian.FORWARD_SMS_SENT") return
        val batchId = intent.getStringExtra("batch_id") ?: return
        val destinationIndex = intent.getIntExtra("destination_index", -1)
        if (destinationIndex !in 0..2) return
        SmsForwarding.recordSendResult(context, batchId, destinationIndex, resultCode)
    }
}

class SmsForwardingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) && SmsForwarding.isEnabled(context)) SmsForwarding.showStatus(context)
    }
}
