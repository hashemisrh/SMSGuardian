package com.example.smsguardian

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.telephony.SmsManager
import android.net.Uri
import android.provider.Telephony
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
        }.onFailure { Log.e("SMSGuardian", "Could not store SMS in provider", it) }
    }
}

/** MMS is declared so Android can offer the default SMS role; this starter app does not yet parse/store MMS. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SMSGuardian", "MMS delivery received; MMS processing is not implemented in this starter build")
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SMSGuardian", "SMS send result: $resultCode")
        val message = when (resultCode) {
            Activity.RESULT_OK -> "پیامک به شبکه ارسال شد. این وضعیت به معنی تحویل قطعی به گیرنده نیست."
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
}
