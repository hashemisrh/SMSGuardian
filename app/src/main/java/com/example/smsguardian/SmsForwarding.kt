package com.example.smsguardian

import android.app.Activity
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local, user-configured SMS forwarding. */
object SmsForwarding {
    const val PREFS = "sms_guardian_forwarding"
    const val CHANNEL = "sms_forwarding_status"
    const val NOTIFICATION_ID = 7341
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

    /** Always resolve a concrete SIM on multi-SIM phones. */
    fun resolveSubscriptionId(context: Context): Int {
        val p = prefs(context)
        val configured = p.getInt("subscription_id", -1)
        val active = if (Build.VERSION.SDK_INT >= 22 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        ) runCatching { SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId } }.getOrDefault(emptyList()) else emptyList()

        if (configured >= 0) {
            if (active.isEmpty() || configured in active) return configured
            Log.e("SMSForwarding", "Configured SIM subscription $configured is no longer active")
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val systemDefault = if (Build.VERSION.SDK_INT >= 24) SubscriptionManager.getDefaultSmsSubscriptionId() else SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (systemDefault >= 0 && (active.isEmpty() || systemDefault in active)) return systemDefault
        // If Android has no SMS default selected, use the first active SIM deterministically.
        return active.firstOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    @Suppress("DEPRECATION")
    fun smsManager(context: Context, subscriptionId: Int): SmsManager {
        require(subscriptionId >= 0) { "هیچ سیم‌کارت فعالی برای ارسال پیامک انتخاب نشده است." }
        return if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
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

    fun hideStatus(c: Context) { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }

    fun recordResult(context: Context, sender: String, destination: String, success: Boolean, detail: String, body: String?) {
        val p = prefs(context)
        val nowDate = Date()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(nowDate)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(nowDate)
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(nowDate)
        val line = "$now | $sender → $destination | ${if (success) "ارسال موفق" else "ارسال ناموفق"}${if (detail.isBlank()) "" else " ($detail)"}\n"
        val history = (line + p.getString("history", "").orEmpty()).take(8000)
        val ok = if (success) 1 else 0
        val fail = if (success) 0 else 1
        p.edit().putString("last", "$now → $ok موفق / $fail ناموفق")
            .putString("history", history)
            .putInt("total_ok", p.getInt("total_ok", 0) + ok)
            .putInt("total_failed", p.getInt("total_failed", 0) + fail)
            .putInt("day_${day}_ok", p.getInt("day_${day}_ok", 0) + ok)
            .putInt("day_${day}_fail", p.getInt("day_${day}_fail", 0) + fail)
            .putInt("month_${month}_ok", p.getInt("month_${month}_ok", 0) + ok)
            .putInt("month_${month}_fail", p.getInt("month_${month}_fail", 0) + fail).apply()
        showStatus(context, "آخرین فوروارد: $now • موفق: $ok • ناموفق: $fail")
        Log.i("SMSForwarding", line.trim())
    }

    fun saveToSent(context: Context, destination: String, body: String) {
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

        val subId = SmsForwarding.resolveSubscriptionId(context)
        if (subId < 0) {
            destinations.forEach { SmsForwarding.recordResult(context, sender, it, false, "سیم‌کارت ارسال مشخص نیست؛ در تنظیمات فوروارد سیم‌کارت را انتخاب کنید", null) }
            return
        }

        for (destination in destinations) {
            val token = UUID.randomUUID().toString()
            try {
                val manager = SmsForwarding.smsManager(context, subId)
                val parts = manager.divideMessage(body)
                val state = JSONObject()
                    .put("sender", sender).put("destination", destination).put("body", body)
                    .put("part_count", parts.size).put("subscription_id", subId).put("success_count", 0).put("failed_count", 0)
                    .put("received", JSONArray())
                p.edit().putString("forward_pending_$token", state.toString()).apply()

                val sentIntents = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { index ->
                    val callback = Intent(context, SmsForwardingResultReceiver::class.java).apply {
                        action = "com.example.smsguardian.FORWARD_SENT.$token.$index"
                        putExtra("token", token)
                        putExtra("part_index", index)
                    }
                    sentIntents.add(PendingIntent.getBroadcast(
                        context, (token.hashCode() * 31) + index, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                }
                if (parts.size > 1) manager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
                else manager.sendTextMessage(destination, null, body, sentIntents.first(), null)
                Log.i("SMSForwarding", "Forward queued: sender=$sender destination=$destination subscriptionId=$subId parts=${parts.size}")
            } catch (e: Exception) {
                p.edit().remove("forward_pending_$token").apply()
                Log.e("SMSForwarding", "Could not start forward to $destination using subscription $subId", e)
                SmsForwarding.recordResult(context, sender, destination, false, e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    private fun normalize(s: String): String = s.filter { it.isDigit() || it == '+' }.let {
        when { it.startsWith("+98") -> "0" + it.drop(3); it.startsWith("0098") -> "0" + it.drop(4); else -> it }
    }
}

/** Receives the real modem/network result for every SMS segment. Only confirmed sends enter Sent. */
class SmsForwardingResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val index = intent.getIntExtra("part_index", -1)
        val p = context.getSharedPreferences(SmsForwarding.PREFS, Context.MODE_PRIVATE)
        synchronized(SmsForwardingResultReceiver::class.java) {
            val key = "forward_pending_$token"
            val state = runCatching { JSONObject(p.getString(key, null) ?: return) }.getOrNull() ?: return
            val received = state.optJSONArray("received") ?: JSONArray()
            if ((0 until received.length()).any { received.optInt(it) == index }) return
            received.put(index)
            val success = resultCode == Activity.RESULT_OK
            var ok = state.optInt("success_count")
            var failed = state.optInt("failed_count")
            if (success) ok++ else failed++
            state.put("received", received).put("success_count", ok).put("failed_count", failed)
            val expected = state.optInt("part_count", 1)
            if (received.length() < expected) {
                p.edit().putString(key, state.toString()).apply()
                return
            }
            p.edit().remove(key).apply()
            val sender = state.optString("sender")
            val destination = state.optString("destination")
            val body = state.optString("body")
            val allSent = failed == 0 && ok == expected
            if (allSent) {
                runCatching { SmsForwarding.saveToSent(context, destination, body) }
                    .onFailure { Log.e("SMSForwarding", "SMS sent but could not be saved to Sent provider", it) }
            }
            val error = intent.getIntExtra("errorCode", 0)
            val noDefault = intent.getBooleanExtra("noDefault", false)
            val subId = state.optInt("subscription_id", -1)
            val detail = if (allSent) "سیم‌کارت $subId" else "کد خطا=$resultCode، جزئیات=$error، بدون سیم‌کارت پیش‌فرض=$noDefault، قطعات موفق=$ok/$expected"
            SmsForwarding.recordResult(context, sender, destination, allSent, detail, body)
        }
    }
}

class SmsForwardingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) && SmsForwarding.isEnabled(context)) SmsForwarding.showStatus(context)
    }
}
