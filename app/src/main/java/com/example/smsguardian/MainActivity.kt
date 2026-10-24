package com.example.smsguardian

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.combinedClickable
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private val Navy = Color(0xFF081326)
private var Panel = Color(0xFF111F35)
private var Cyan = Color(0xFF12C9C3)
private var TextWhite = Color(0xFFF1F6FF)
private var Muted = Color(0xFF9AAAC3)
private data class ContactSuggestion(val name: String, val number: String)
private data class ConversationPreview(val address: String, val latestMessage: SmsRow, val contactName: String?)

data class SmsRow(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : ComponentActivity() {
    private var smsRoleRefreshSignal by mutableIntStateOf(0)
    private var smsObserver: ContentObserver? = null
    private var deliveryStatusReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var cachedSmsRows: List<SmsRow>? = null
    @Volatile private var cachedAllSmsRows: List<SmsRow>? = null
    @Volatile private var cachedConversationPreviews: List<ConversationPreview>? = null
    private val smsRefreshInProgress = AtomicBoolean(false)
    private val smsRefreshQueued = AtomicBoolean(false)
    @Volatile private var lastSmsRefreshAt = 0L
    private val smsCacheFileName = "sms_rows_cache.json"
    private val smsRecentCacheFileName = "sms_conversation_summary_cache.json"
    private val contactNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var contactNamesIndex: Map<String, String>? = null
    private val smsObserverHandler = Handler(Looper.getMainLooper())
    private val debouncedSmsRefresh = Runnable {
        // Keep the current list visible while the provider refresh runs. Replacing it
        // only after the conversation summary query avoids blanking the screen.
        requestSmsRowsRefresh(force = true)
    }

    override fun onResume() {
        super.onResume()
        if (smsObserver == null) {
            smsObserver = object : ContentObserver(smsObserverHandler) {
                override fun onChange(selfChange: Boolean) {
                    smsObserverHandler.removeCallbacks(debouncedSmsRefresh)
                    smsObserverHandler.postDelayed(debouncedSmsRefresh, 120)
                }
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    smsObserverHandler.removeCallbacks(debouncedSmsRefresh)
                    smsObserverHandler.postDelayed(debouncedSmsRefresh, 120)
                }
            }
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver!!)
            contentResolver.registerContentObserver(Telephony.Sms.Conversations.CONTENT_URI, true, smsObserver!!)
            contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, smsObserver!!)
            contentResolver.registerContentObserver(Telephony.Threads.CONTENT_URI, true, smsObserver!!)
        }
        if (deliveryStatusReceiver == null) {
            deliveryStatusReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) { smsRoleRefreshSignal++ }
            }
            val filter = android.content.IntentFilter("com.example.smsguardian.DELIVERY_STATUS_CHANGED")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(deliveryStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(deliveryStatusReceiver, filter)
            }
        }
        smsRoleRefreshSignal++
        // On a warm resume, reconcile the in-memory list in the background. On a cold
        // start, getSmsRows() owns the initial query to avoid two simultaneous scans.
        if (cachedSmsRows != null) requestSmsRowsRefresh(force = true)
    }

    override fun onPause() {
        smsObserverHandler.removeCallbacks(debouncedSmsRefresh)
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        deliveryStatusReceiver?.let { runCatching { unregisterReceiver(it) } }
        deliveryStatusReceiver = null
        super.onPause()
    }

    // SMS permissions are restricted on modern Android. Always request the SMS role first;
    // asking for READ/RECEIVE/SEND_SMS before the role is granted causes Android to deny access.
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == false) {
            android.widget.Toast.makeText(this, "برای انتخاب مخاطب، مجوز مخاطبین را فعال کنید.", android.widget.Toast.LENGTH_LONG).show()
        }
        val smsPermissions = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH
        )
        val deniedSms = smsPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedSms.isNotEmpty()) {
            android.util.Log.e("SMSGuardian", "SMS permissions still denied: ${deniedSms.joinToString()}; default=${isDefaultSmsApp()}")
            android.widget.Toast.makeText(
                this,
                "دسترسی پیامک داده نشده است. ابتدا SMS Guardian را از تنظیمات برنامه‌های پیش‌فرض به‌عنوان برنامه پیامک انتخاب کنید. اگر انتخاب شده ولی دسترسی‌ها رد می‌شوند، این APK هنوز مجوزهای لازم نقش پیامک را نگرفته است.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    private var contactCallback: ((String) -> Unit)? = null
    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            runCatching {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                    if (c.moveToFirst()) contactCallback?.invoke(c.getString(0).orEmpty())
                }
            }.onFailure { android.util.Log.e("SMSGuardian", "Could not read selected contact", it) }
        }
        contactCallback = null
    }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isDefaultSmsApp()) {
            requestNonSmsPermissions()
            smsRoleRefreshSignal++
        } else {
            android.widget.Toast.makeText(this, "برای دریافت و مدیریت پیامک‌ها، SMS Guardian را در تنظیمات گوشی به‌عنوان برنامه پیامک پیش‌فرض انتخاب کنید.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { GuardianApp() }
        // Do not trigger Android's SMS permission denial on first launch: role must come first.
        window.decorView.post {
            if (isDefaultSmsApp()) requestNonSmsPermissions() else requestDefaultSmsRole()
        }
    }

    private fun isDefaultSmsApp(): Boolean = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    private fun requestNonSmsPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        // Crucial: only ask for SMS permissions AFTER the OS confirms this package is the
        // default SMS handler. The SMS role dialog alone is not a substitute for checking grants.
        if (isDefaultSmsApp()) {
            permissions.addAll(listOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.RECEIVE_WAP_PUSH
            ))
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_SMS) && !manager.isRoleHeld(RoleManager.ROLE_SMS)) {
                roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            roleLauncher.launch(intent)
        }
    }

    fun sendSms(destination: String, message: String, subId: Int?) {
        if (destination.isBlank() || message.isBlank()) return
        // Do not block sending solely because the default-SMS-role check disagrees with
        // the system Settings UI. Try the actual send when SEND_SMS is granted and report
        // the real Android/modem result below.
        val defaultRoleHeld = isDefaultSmsApp()
        android.util.Log.i(
            "SMSGuardian",
            "Send requested; defaultSmsRole=$defaultRoleHeld; defaultPackage=${Telephony.Sms.getDefaultSmsPackage(this)}; package=$packageName"
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("SMSGuardian", "SEND_SMS permission is not granted; defaultSmsRole=$defaultRoleHeld")
            android.widget.Toast.makeText(
                this,
                "اندروید مجوز SEND_SMS را به این برنامه نداده است. از اطلاعات برنامه، بخش Permissions، وضعیت SMS را بررسی کنید. وضعیت پیام‌رسان پیش‌فرض: ${if (defaultRoleHeld) "فعال" else "غیرفعال"}.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && subId != null && subId >= 0) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent>(parts.size)
            val sendKey = SmsDeliveryStatus.key(destination.trim(), message)
            parts.indices.forEach { index ->
                val sentIntent = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "com.example.smsguardian.SMS_SENT"
                    putExtra("part_index", index)
                    putExtra("part_count", parts.size)
                }
                val deliveryIntent = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "com.example.smsguardian.SMS_DELIVERED"
                    putExtra("delivery_key", sendKey)
                    putExtra("part_index", index)
                    putExtra("part_count", parts.size)
                }
                val requestBase = (System.currentTimeMillis().toInt() * 31) + index
                sentIntents.add(PendingIntent.getBroadcast(this, requestBase, sentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                deliveryIntents.add(PendingIntent.getBroadcast(this, requestBase xor 0x5A5A5A5A, deliveryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (parts.size > 1) manager.sendMultipartTextMessage(destination.trim(), null, parts, sentIntents, deliveryIntents)
            else manager.sendTextMessage(destination.trim(), null, message, sentIntents.first(), deliveryIntents.first())

            // SmsManager transmits the SMS but does not automatically add it to the
            // system SMS database. As the default SMS app, persist one full outgoing
            // message so it appears in the conversation and chat list immediately.
            runCatching {
                val sentAt = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, destination.trim())
                    put(Telephony.Sms.BODY, message)
                    put(Telephony.Sms.DATE, sentAt)
                    put(Telephony.Sms.DATE_SENT, sentAt)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                }
                contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }.onFailure { android.util.Log.e("SMSGuardian", "SMS sent but could not be saved to Sent folder", it) }
            // The sent callback reports the actual modem/network submission result.
        } catch (e: Exception) {
            android.util.Log.e("SMSGuardian", "SMS send failed; default=${isDefaultSmsApp()}; SEND_SMS granted=${ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED}; subscriptionId=$subId", e)
            val detail = when (e) {
                is SecurityException -> "اندروید ارسال را از نظر امنیتی رد کرد: ${e.message ?: e.javaClass.simpleName}. بررسی نقش پیش‌فرض=${isDefaultSmsApp()}، بسته پیش‌فرض=${Telephony.Sms.getDefaultSmsPackage(this)}, مجوز SEND_SMS=${ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED}، سیم‌کارت=$subId."
                else -> "ارسال پیامک آغاز نشد: ${e.javaClass.simpleName}: ${e.message ?: "خطای نامشخص؛ سیم‌کارت و آنتن را بررسی کنید."}"
            }
            android.widget.Toast.makeText(this, detail, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun findContactSuggestions(query: String): List<ContactSuggestion> {
        if (query.isBlank() || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val found = linkedMapOf<String, ContactSuggestion>()
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$query%", "%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && found.size < 8) {
                    val name = if (ni >= 0) c.getString(ni).orEmpty() else ""
                    val number = if (pi >= 0) c.getString(pi).orEmpty() else ""
                    if (number.isNotBlank()) found[number] = ContactSuggestion(name, number)
                }
            }
        }.onFailure { android.util.Log.w("SMSGuardian", "Contact autocomplete query failed", it) }
        return found.values.toList()
    }

    private fun setLauncherIcon(choice: Int) {
        val aliases = listOf(".IconClassic", ".IconOcean", ".IconSunset", ".IconMinimal")
        val pm = packageManager
        aliases.forEachIndexed { index, alias ->
            val component = android.content.ComponentName(this, packageName + alias)
            pm.setComponentEnabledSetting(component, if (index == choice.coerceIn(0, aliases.lastIndex)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        getPreferences(MODE_PRIVATE).edit().putInt("launcher_icon_choice", choice.coerceIn(0, aliases.lastIndex)).apply()
    }

    private fun draftKey(address: String): String = "sms_draft_" + address.filter { it.isLetterOrDigit() || it == '+' }

    private fun linkedMessageText(body: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
        val matcher = android.util.Patterns.WEB_URL.matcher(body)
        var cursor = 0
        while (matcher.find()) {
            append(body.substring(cursor, matcher.start()))
            val raw = matcher.group().orEmpty()
            val link = raw.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
            if (link.isEmpty()) {
                append(raw)
            } else {
                val url = if (link.startsWith("http://", true) || link.startsWith("https://", true)) link else "https://$link"
                val start = length
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(link)
                pop()
                addStringAnnotation("URL", url, start, length)
                append(raw.substring(link.length))
            }
            cursor = matcher.end()
        }
        if (cursor < body.length) append(body.substring(cursor))
    }

    private fun latestRowsByConversation(rows: List<SmsRow>): List<SmsRow> {
        // Keep only one row per address for the main list. Do not build a List<SmsRow>
        // for every thread: with hundreds of conversations and a large history this
        // was the main source of avoidable allocation and repeated work.
        val latestByAddress = HashMap<String, SmsRow>(rows.size.coerceAtMost(4096))
        for (row in rows) {
            if (row.address.isBlank()) continue
            val previous = latestByAddress[row.address]
            if (previous == null || row.date > previous.date) latestByAddress[row.address] = row
        }
        return latestByAddress.values.sortedByDescending { it.date }
    }

    private fun getConversationPreviews(rows: List<SmsRow>): List<ConversationPreview> {
        cachedConversationPreviews?.let { cached ->
            if (cached.size == rows.size && cached.firstOrNull()?.latestMessage == rows.firstOrNull()) return cached
        }
        val previews = rows.map { row ->
            ConversationPreview(row.address, row, lookupContactName(row.address))
        }
        cachedConversationPreviews = previews
        return previews
    }

    private fun readSmsRowsCache(): List<SmsRow> {
        val current = loadSmsRowsFromFile(smsRecentCacheFileName)
        // One-time migration path: older builds stored a capped list of individual SMS
        // rows here. Use it only for instant first paint; provider refresh replaces it
        // with a complete one-row-per-conversation cache.
        return if (current.isNotEmpty()) current else loadSmsRowsFromFile("sms_recent_cache.json")
    }

    private fun writeSmsRowsCache(rows: List<SmsRow>) {
        runCatching {
            val array = JSONArray()
            rows.forEach { row ->
                array.put(JSONObject().put("a", row.address).put("b", row.body).put("d", row.date).put("t", row.type))
            }
            val file = java.io.File(filesDir, smsCacheFileName)
            val temp = java.io.File(filesDir, "$smsCacheFileName.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) { file.writeText(array.toString()); temp.delete() }
        }
    }

    private fun writeRecentSmsRowsCache(rows: List<SmsRow>) {
        runCatching {
            val array = JSONArray()
            rows.forEach { row -> array.put(JSONObject().put("a", row.address).put("b", row.body).put("d", row.date).put("t", row.type)) }
            val file = java.io.File(filesDir, smsRecentCacheFileName)
            val temp = java.io.File(filesDir, "$smsRecentCacheFileName.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) { file.writeText(array.toString()); temp.delete() }
        }
    }

    private fun loadSmsRowsFromFile(name: String): List<SmsRow> = runCatching {
        val file = java.io.File(filesDir, name)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(SmsRow(item.optString("a"), item.optString("b"), item.optLong("d"), item.optInt("t")))
            }
        }
    }.getOrElse { emptyList() }

    fun loadAllSmsRows(): List<SmsRow> {
        cachedAllSmsRows?.let { return it }
        val saved = loadSmsRowsFromFile(smsCacheFileName)
        if (saved.isNotEmpty()) {
            cachedAllSmsRows = saved
            return saved
        }
        return querySmsRows().also { all ->
            cachedAllSmsRows = all
            if (all.isNotEmpty()) writeSmsRowsCache(all)
        }
    }

    /** Full history is used only for explicit full-text search/archive tasks. */
    private fun querySmsRows(limit: Int? = null, address: String? = null): List<SmsRow> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = ArrayList<SmsRow>(limit ?: 256)
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        val selection = if (address != null) "address = ?" else null
        val selectionArgs = if (address != null) arrayOf(address) else null
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs, "date DESC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                rows.add(SmsRow(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getLong(2), it.getInt(3)))
                if (limit != null && rows.size >= limit) break
            }
        }
        return rows
    }

    /**
     * Android's SMS conversations view returns one latest-message row per thread.
     * This is the key performance fix: the main inbox reads ~1500 summary rows rather
     * than scanning 100k-450k individual messages and grouping them in app code.
     */
    private fun queryConversationRows(): List<SmsRow> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val result = ArrayList<SmsRow>(2048)
        val uri = Telephony.Sms.Conversations.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID
        )
        try {
            contentResolver.query(uri, projection, null, null, "date DESC")?.use { c ->
                val ai = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bi = c.getColumnIndex(Telephony.Sms.BODY)
                val di = c.getColumnIndex(Telephony.Sms.DATE)
                val ti = c.getColumnIndex(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    val address = if (ai >= 0) c.getString(ai).orEmpty() else ""
                    if (address.isBlank()) continue
                    result.add(SmsRow(address, if (bi >= 0) c.getString(bi).orEmpty() else "", if (di >= 0) c.getLong(di) else 0L, if (ti >= 0) c.getInt(ti) else Telephony.Sms.MESSAGE_TYPE_INBOX))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SMSGuardian", "SMS conversation summary query failed; falling back to distinct latest messages", e)
        }
        // Some OEM providers expose the URI but do not implement the conversations view.
        // Fallback is only used when that optimized query fails/returns nothing; scan the full provider history so older conversations are not silently omitted.
        if (result.isEmpty()) return latestRowsByConversation(querySmsRows())
        return latestRowsByConversation(result)
    }

    private fun requestSmsRowsRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSmsRefreshAt < 2_000L) return
        if (!smsRefreshInProgress.compareAndSet(false, true)) {
            if (force) smsRefreshQueued.set(true)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fresh = queryConversationRows()
                if (fresh.isNotEmpty() || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                    cachedSmsRows = fresh
                    cachedConversationPreviews = null
                    lastSmsRefreshAt = System.currentTimeMillis()
                    writeRecentSmsRowsCache(fresh)
                    launch(Dispatchers.Main) { smsRoleRefreshSignal++ }
                }
            } catch (e: Exception) {
                android.util.Log.e("SMSGuardian", "Background conversation refresh failed", e)
            } finally {
                smsRefreshInProgress.set(false)
                if (smsRefreshQueued.getAndSet(false)) {
                    smsObserverHandler.post { requestSmsRowsRefresh(force = true) }
                }
            }
        }
    }

    fun getSmsRows(): List<SmsRow> {
        cachedSmsRows?.let { return it }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        // Show the cached full conversation list immediately, then reconcile with the
        // system provider in the background. The cache contains one row per conversation.
        val diskRows = readSmsRowsCache()
        if (diskRows.isNotEmpty()) {
            val compactRows = latestRowsByConversation(diskRows)
            cachedSmsRows = compactRows
            requestSmsRowsRefresh(force = true)
            return compactRows
        }
        // First install: query the provider's conversation summary view, not a capped
        // window of individual messages (which silently omitted older conversations).
        val summaries = queryConversationRows()
        cachedSmsRows = summaries
        lastSmsRefreshAt = System.currentTimeMillis()
        if (summaries.isNotEmpty()) writeRecentSmsRowsCache(summaries)
        return summaries
    }

    @Composable private fun GuardianApp() {
        var language by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getString("language", "en") ?: "en") }
        var page by remember { mutableStateOf("chats") }
        var bgChoice by remember { mutableIntStateOf(getPreferences(MODE_PRIVATE).getInt("bg_choice", 3)) }
        var launcherIconChoice by remember { mutableIntStateOf(getPreferences(MODE_PRIVATE).getInt("launcher_icon_choice", 3)) }
        val appBg = when (bgChoice) { 1 -> Color(0xFF24152F); 2 -> Color(0xFF102A24); 3 -> Color(0xFFF7F9FC); else -> Navy }
        val lightTheme = bgChoice == 3
        Panel = if (lightTheme) Color(0xFFFFFFFF) else when (bgChoice) { 1 -> Color(0xFF33213F); 2 -> Color(0xFF18382F); else -> Color(0xFF111F35) }
        TextWhite = if (lightTheme) Color(0xFF172033) else Color(0xFFF1F6FF)
        Muted = if (lightTheme) Color(0xFF647084) else Color(0xFF9AAAC3)
        Cyan = if (lightTheme) Color(0xFF087F8C) else Color(0xFF12C9C3)
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(smsRoleRefreshSignal) { refresh++ }
        LaunchedEffect(Unit) {
            val migration = getPreferences(MODE_PRIVATE)
            if (!migration.getBoolean("minimal_icon_default_applied", false)) {
                setLauncherIcon(3)
                launcherIconChoice = 3
                migration.edit().putBoolean("minimal_icon_default_applied", true).apply()
            }
        }
        val isFa = language == "fa"
        val direction = if (isFa) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MaterialTheme(colorScheme = if (lightTheme) lightColorScheme(primary = Cyan, secondary = Color(0xFF356BCE), background = appBg, surface = Panel, onBackground = TextWhite, onSurface = TextWhite) else darkColorScheme(primary = Cyan, secondary = Color(0xFF356BCE), background = appBg, surface = Panel, onBackground = TextWhite, onSurface = TextWhite)) {
                Surface(Modifier.fillMaxSize(), color = appBg) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().background(Brush.verticalGradient(listOf(appBg.copy(red = (appBg.red + 0.06f).coerceAtMost(1f)), appBg)))) {
                        Header(isFa, page, { language = if (language == "fa") "en" else "fa"; getPreferences(MODE_PRIVATE).edit().putString("language", language).apply() }, { page = it })
                        when (page) {
                            "rules" -> RulesPage(isFa, refresh) { refresh++ }
                            "forwarding" -> SmsForwardingPage(isFa)
                            "settings" -> SettingsPage(isFa, language, { language = it; getPreferences(MODE_PRIVATE).edit().putString("language", it).apply() }, { requestDefaultSmsRole() }, bgChoice, { choice -> bgChoice = choice; getPreferences(MODE_PRIVATE).edit().putInt("bg_choice", choice).apply() }, launcherIconChoice) { choice -> launcherIconChoice = choice; setLauncherIcon(choice) }
                            "about" -> AboutPage(isFa)
                            "deleted" -> DeletedMessagesPage(isFa, refresh) { refresh++ }
                            else -> ChatsPage(isFa, refresh, bgChoice, ::getSmsRows, ::sendSms)
                        }
                        NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = Color(0xFF0A172A), contentColor = TextWhite) {
                            NavigationBarItem(selected = page == "chats", onClick = { page = "chats" }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text(if (isFa) "گفتگوها" else "Chats") })
                            NavigationBarItem(selected = page == "rules", onClick = { page = "rules" }, icon = { Icon(Icons.Default.FilterAlt, null) }, label = { Text(if (isFa) "فیلترها" else "Filters") })
                            NavigationBarItem(selected = page == "settings", onClick = { page = "settings" }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(if (isFa) "تنظیمات" else "Settings") })
                            NavigationBarItem(selected = page == "about", onClick = { page = "about" }, icon = { Icon(Icons.Default.Menu, null) }, label = { Text(if (isFa) "درباره ما" else "About") })
                            NavigationBarItem(selected = page == "deleted", onClick = { page = "deleted" }, icon = { Icon(Icons.Default.DeleteOutline, null) }, label = { Text(if (isFa) "حذف‌شده‌ها" else "Deleted") })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Header(isFa: Boolean, page: String, toggleLanguage: () -> Unit, navigate: (String) -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.Menu, contentDescription = if (isFa) "منو" else "Menu", tint = Cyan) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    listOf("chats" to (if (isFa) "گفتگوها" else "Chats"), "rules" to (if (isFa) "قوانین بلاک" else "Blocking rules"), "forwarding" to (if (isFa) "فوروارد خودکار پیامک" else "SMS forwarding"), "settings" to (if (isFa) "تنظیمات" else "Settings"), "about" to (if (isFa) "درباره ما" else "About")).forEach { (target, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuExpanded = false; navigate(target) })
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SMS Guardian", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextWhite)
                Text(if (page == "chats") { if (isFa) "پیام‌رسان امن شما" else "Your secure inbox" } else if (page == "rules") { if (isFa) "مدیریت قوانین هوشمند" else "Smart filtering rules" } else if (page == "forwarding") { if (isFa) "ارسال خودکار پیامک‌های مطابق شرط" else "Automatically forward matching SMS" } else { if (isFa) "شخصی‌سازی برنامه" else "Personalize your app" }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.clickable(onClick = toggleLanguage).padding(8.dp))
        }
    }

    @Composable private fun ChatsPage(isFa: Boolean, refresh: Int, bgChoice: Int, loadRows: () -> List<SmsRow>, send: (String, String, Int?) -> Unit) {
        var rows by remember { mutableStateOf(emptyList<SmsRow>()) }
        var initialLoadComplete by remember { mutableStateOf(false) }
        var destination by remember { mutableStateOf("") }
        var recipientNumber by remember { mutableStateOf("") }
        var chatSearch by remember { mutableStateOf("") }
        var searchExpanded by remember { mutableStateOf(false) }
        var showComposer by remember { mutableStateOf(false) }
        var targetMessage by remember { mutableStateOf<SmsRow?>(null) }
        var appliedChatSearch by remember { mutableStateOf("") }
        LaunchedEffect(chatSearch) {
            kotlinx.coroutines.delay(300)
            appliedChatSearch = chatSearch.trim()
        }
        var groupedConversations by remember { mutableStateOf(emptyList<ConversationPreview>()) }
        LaunchedEffect(rows) {
            // SMS grouping and contact-provider lookups must never run on the UI thread.
            groupedConversations = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getConversationPreviews(rows)
            }
        }
        var message by remember { mutableStateOf("") }
        var threadMessage by remember { mutableStateOf("") }
        var selectedNumber by remember { mutableStateOf<String?>(null) }
        var subId by remember { mutableStateOf<Int?>(null) }
        var sims by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
        var localRefresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh, localRefresh) {
            rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadRows() }
            initialLoadComplete = true
            sims = if (Build.VERSION.SDK_INT >= 22 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) runCatching {
                SubscriptionManager.from(this@MainActivity).activeSubscriptionInfoList.orEmpty().mapIndexed { i, sub ->
                    sub.subscriptionId to (sub.displayName?.toString()?.ifBlank { null } ?: if (isFa) "سیم‌کارت ${i + 1}" else "SIM ${i + 1}")
                }
            }.getOrDefault(emptyList()) else emptyList()
            if (subId == null) subId = sims.firstOrNull()?.first
        }
        val currentNumber = selectedNumber
        BackHandler(enabled = currentNumber != null) {
            currentNumber?.let { number -> getPreferences(MODE_PRIVATE).edit().putString(draftKey(number), threadMessage).apply() }
            selectedNumber = null
            targetMessage = null
        }
        if (currentNumber != null) {
            var loadedThreadRows by remember(currentNumber) { mutableStateOf<List<SmsRow>?>(null) }
            LaunchedEffect(currentNumber) {
                threadMessage = getPreferences(MODE_PRIVATE).getString(draftKey(currentNumber), "") ?: ""
            }
            LaunchedEffect(currentNumber, refresh, localRefresh) {
                loadedThreadRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    querySmsRows(address = currentNumber).sortedBy { it.date }
                }
            }
            val thread = loadedThreadRows ?: emptyList()
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { getPreferences(MODE_PRIVATE).edit().putString(draftKey(currentNumber), threadMessage).apply(); selectedNumber = null; targetMessage = null }) { Icon(Icons.Default.ArrowBack, contentDescription = if (isFa) "بازگشت" else "Back", tint = Cyan) }
                    Column(Modifier.weight(1f)) {
                        Text(lookupContactName(currentNumber) ?: currentNumber, color = TextWhite, fontWeight = FontWeight.Bold)
                        Text(currentNumber, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (lookupContactName(currentNumber) == null) {
                        IconButton(onClick = {
                            runCatching {
                                val add = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                    type = ContactsContract.RawContacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, currentNumber)
                                }
                                startActivity(add)
                            }.onFailure { android.widget.Toast.makeText(this@MainActivity, if (isFa) "باز کردن افزودن مخاطب ممکن نشد" else "Could not open contact creation", android.widget.Toast.LENGTH_SHORT).show() }
                        }) { Icon(Icons.Default.PersonAdd, contentDescription = if (isFa) "افزودن به مخاطبین" else "Add to contacts", tint = Cyan) }
                    }
                    IconButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(currentNumber)}"))) } }) {
                        Icon(Icons.Default.Call, contentDescription = if (isFa) "تماس" else "Call", tint = Cyan)
                    }
                }
                val threadListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(currentNumber, thread.size, targetMessage) {
                    if (thread.isNotEmpty()) {
                        val targetIndex = targetMessage?.let { target -> thread.indexOfFirst { it.date == target.date && it.address == target.address && it.body == target.body && it.type == target.type } } ?: -1
                        if (targetIndex >= 0) threadListState.scrollToItem(targetIndex)
                        else threadListState.scrollToItem(thread.lastIndex)
                    }
                }
                LazyColumn(state = threadListState, modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(thread, key = { "${it.address}-${it.date}-${it.type}" }) { row ->
                        val outgoing = row.type == Telephony.Sms.MESSAGE_TYPE_SENT || row.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                        var messageMenu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                            val isTarget = targetMessage?.let { it.date == row.date && it.address == row.address && it.body == row.body && it.type == row.type } == true
                            Column(Modifier.widthIn(max = 300.dp).background(if (isTarget) Color(0xFFFFE082) else if (outgoing) { if (bgChoice == 3) Color(0xFFD6F5F2) else Color(0xFF124B59) } else Panel, RoundedCornerShape(16.dp)).combinedClickable(onClick = {}, onLongClick = { messageMenu = true }).padding(12.dp)) {
                                val linkText = remember(row.body, isTarget, TextWhite, Cyan) { linkedMessageText(row.body, Cyan) }
                                ClickableText(
                                    text = linkText,
                                    style = TextStyle(color = if (isTarget) Color(0xFF172033) else TextWhite),
                                    onClick = { offset ->
                                        linkText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))) }
                                                .onFailure { android.widget.Toast.makeText(this@MainActivity, if (isFa) "باز کردن لینک ممکن نشد" else "Could not open link", android.widget.Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date)), color = if (isTarget) Color(0xFF4B5563) else Muted, style = MaterialTheme.typography.labelSmall)
                                    if (outgoing) {
                                        val delivered = SmsDeliveryStatus.isDelivered(this@MainActivity, SmsDeliveryStatus.key(row.address, row.body))
                                        Text(if (delivered) "✓✓" else "✓", color = if (delivered) Color(0xFF1687D9) else Muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        if (messageMenu) AlertDialog(onDismissRequest = { messageMenu = false }, title = { Text(if (isFa) "عملیات پیامک" else "Message actions") }, text = { Text(if (isFa) "پیام را کپی کنید، ویرایش کنید یا با انتخاب مخاطب از دفترچه تلفن فوروارد کنید." else "Copy, edit, or forward this message by choosing a contact from your phonebook.") }, confirmButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SMS", row.body)); messageMenu = false; android.widget.Toast.makeText(this@MainActivity, if (isFa) "متن کپی شد" else "Copied", android.widget.Toast.LENGTH_SHORT).show() }) { Text(if (isFa) "کپی" else "Copy") } }, dismissButton = { Row { TextButton(onClick = { contactCallback = { picked -> sendSms(picked, row.body, null); localRefresh++; android.widget.Toast.makeText(this@MainActivity, if (isFa) "پیامک برای ارسال به مخاطب انتخاب‌شده ثبت شد" else "Message queued for the selected contact", android.widget.Toast.LENGTH_SHORT).show() }; messageMenu = false; contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) { Icon(Icons.Default.Forward, null); Text(if (isFa) "فوروارد" else "Forward") }; TextButton(onClick = { threadMessage = row.body; messageMenu = false }) { Text(if (isFa) "ویرایش متن" else "Edit text") }; TextButton(onClick = { messageMenu = false }) { Text(if (isFa) "بستن" else "Close") } } })
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { getPreferences(MODE_PRIVATE).edit().putString(draftKey(currentNumber), threadMessage).apply(); selectedNumber = null; targetMessage = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = if (isFa) "بازگشت و نگهداری پیش‌نویس" else "Back and keep draft", tint = Cyan)
                    }
                    OutlinedTextField(value = threadMessage, onValueChange = { text -> threadMessage = text; getPreferences(MODE_PRIVATE).edit().putString(draftKey(currentNumber), text).apply() }, modifier = Modifier.weight(1f), placeholder = { Text(if (isFa) "نوشتن پیامک..." else "Type a message...") }, maxLines = 4)
                    IconButton(enabled = threadMessage.isNotBlank(), onClick = {
                        send(currentNumber, threadMessage, subId)
                        threadMessage = ""
                        getPreferences(MODE_PRIVATE).edit().remove(draftKey(currentNumber)).apply()
                        localRefresh++
                    }) { Icon(Icons.Default.Send, contentDescription = if (isFa) "ارسال" else "Send", tint = Cyan) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isFa) "گفتگوها" else "Conversations", color = TextWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${rows.map { it.address }.distinct().size}", color = Cyan)
                    IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) chatSearch = "" }) {
                        Icon(if (searchExpanded) Icons.Default.ArrowBack else Icons.Default.Search, contentDescription = if (isFa) "جست‌وجو در پیامک‌ها" else "Search messages", tint = Cyan)
                    }
                    IconButton(onClick = { showComposer = !showComposer }) {
                        Icon(if (showComposer) Icons.Default.ArrowBack else Icons.Default.Add, contentDescription = if (isFa) "پیام جدید" else "New message", tint = Cyan)
                    }
                }
                AnimatedVisibility(visible = showComposer, enter = expandVertically(), exit = shrinkVertically()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (isFa) "پیامک جدید" else "New message", color = TextWhite, fontWeight = FontWeight.SemiBold)
                        var suggestions by remember { mutableStateOf(emptyList<ContactSuggestion>()) }
                        var suggestionsExpanded by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = destination,
                                    onValueChange = { typed -> destination = typed; recipientNumber = ""; suggestions = findContactSuggestions(typed); suggestionsExpanded = typed.isNotBlank() && suggestions.isNotEmpty() },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(if (isFa) "نام یا شماره گیرنده" else "Recipient name or number") },
                                    singleLine = true,
                                    trailingIcon = { Icon(Icons.Default.Search, null) }
                                )
                                // Keep suggestions in the normal layout below the field, not in a popup
                                // that can cover the text being typed or steal the field's focus.
                                if (suggestionsExpanded && suggestions.isNotEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = Panel),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                            items(suggestions.take(8), key = { "${it.number}-${it.name}" }) { item ->
                                                Column(
                                                    Modifier.fillMaxWidth().clickable { destination = item.name.ifBlank { item.number }; recipientNumber = item.number; suggestionsExpanded = false }
                                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                                ) {
                                                    Text(item.name.ifBlank { item.number }, color = TextWhite, fontWeight = FontWeight.SemiBold)
                                                    if (item.name.isNotBlank()) Text(item.number, color = Muted, style = MaterialTheme.typography.labelSmall)
                                                }
                                                HorizontalDivider(color = Muted.copy(alpha = .18f))
                                            }
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = {
                                contactCallback = { picked -> recipientNumber = picked; destination = lookupContactName(picked) ?: picked; suggestionsExpanded = false }
                                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                            }) { Icon(Icons.Default.Contacts, contentDescription = if (isFa) "انتخاب از دفترچه تلفن" else "Choose contact", tint = Cyan) }
                        }
                        OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (isFa) "متن پیام" else "Message text") }, minLines = 2)
                        if (sims.isNotEmpty()) {
                            Text(if (isFa) "سیم‌کارت ارسال" else "Send using SIM", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { sims.forEach { (id, label) -> FilterChip(selected = subId == id, onClick = { subId = id }, label = { Text(label) }) } }
                        }
                        Button(onClick = { val target = recipientNumber.ifBlank { destination.trim() }; val textToSend = message; send(target, textToSend, subId); selectedNumber = target; threadMessage = ""; destination = ""; recipientNumber = ""; message = ""; suggestionsExpanded = false; localRefresh++ }, modifier = Modifier.fillMaxWidth(), enabled = (recipientNumber.isNotBlank() || destination.isNotBlank()) && message.isNotBlank()) {
                            Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(if (isFa) "ارسال پیامک" else "Send SMS")
                        }
                    }
                }
                }
                AnimatedVisibility(visible = searchExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    OutlinedTextField(
                        value = chatSearch,
                        onValueChange = { chatSearch = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        label = { Text(if (isFa) "جست‌وجو در گفتگوها و متن پیام‌ها" else "Search chats and message text") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = { if (chatSearch.isNotEmpty()) TextButton(onClick = { chatSearch = "" }) { Text(if (isFa) "پاک" else "Clear") } }
                    )
                }
                // Group and resolve contact names only when the SMS dataset changes, not on every keystroke.
                // Search is debounced so typing cannot trigger a full scan for every character.
                val query = appliedChatSearch
                var conversations by remember { mutableStateOf(emptyList<SmsRow>()) }
                LaunchedEffect(groupedConversations, query) {
                    conversations = if (query.isBlank()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            groupedConversations.map { it.latestMessage }
                        }
                    } else {
                        val allRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadAllSmsRows() }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            allRows.groupBy { it.address }.mapNotNull { (address, messages) ->
                                val name = lookupContactName(address)
                                val matched = if (address.contains(query, ignoreCase = true) || name?.contains(query, ignoreCase = true) == true)
                                    messages.maxByOrNull { it.date }
                                else messages.asSequence().filter { it.body.contains(query, ignoreCase = true) }.maxByOrNull { it.date }
                                matched
                            }.sortedByDescending { it.date }
                        }
                    }
                }
                val conversationListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(conversations.firstOrNull()?.date, conversations.firstOrNull()?.address, query) {
                    if (conversations.isNotEmpty() && query.isBlank()) conversationListState.scrollToItem(0)
                }
                if (conversations.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (!initialLoadComplete && query.isBlank()) CircularProgressIndicator(color = Cyan)
                    else Text(if (query.isNotBlank()) { if (isFa) "نتیجه‌ای برای این جست‌وجو پیدا نشد." else "No matching messages found." } else { if (isFa) "پیامکی برای نمایش پیدا نشد. برای دریافت پیامک جدید، مجوزها و پیام‌رسان پیش‌فرض را بررسی کنید." else "No messages found. Check permissions and the default SMS role to receive new messages." }, color = Muted)
                } else LazyColumn(state = conversationListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { "${it.address}-${it.date}" }) { row ->
                        val displayName = groupedConversations.firstOrNull { it.address == row.address }?.contactName ?: row.address
                        val initials = initialsFor(displayName)
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { selectedNumber = row.address; targetMessage = if (appliedChatSearch.isNotBlank()) row else null; showComposer = false }, onLongClick = { showConversationActions(row.address, isFa) { localRefresh++ } })) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Cyan, Color(0xFF356BCE))), CircleShape), contentAlignment = Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(displayName, color = TextWhite, fontWeight = FontWeight.SemiBold)
                                    Text(row.body, color = Muted, maxLines = 2)
                                    Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date)), color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(row.address)}"))) } }) { Icon(Icons.Default.Call, contentDescription = if (isFa) "تماس" else "Call", tint = Cyan) }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun showConversationActions(number: String, isFa: Boolean, changed: () -> Unit) {
        val options = arrayOf(
            if (isFa) "حذف این گفتگو" else "Delete conversation",
            if (isFa) "مسدود کردن این شماره" else "Block this number",
            if (isFa) "مسدود کردن سرشماره" else "Block sender prefix",
            if (isFa) "بلاک شماره و حذف گفتگو" else "Block Number & Delete Conversation",
            if (isFa) "لغو" else "Cancel"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isFa) "مدیریت گفتگو: $number" else "Conversation: $number")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> confirmDeleteConversation(number, isFa, changed)
                    1 -> { RuleStore.addBlocked(this, number); changed(); android.widget.Toast.makeText(this, if (isFa) "شماره بلاک شد" else "Number blocked", android.widget.Toast.LENGTH_SHORT).show() }
                    2 -> {
                        val prefix = number.filter { it.isDigit() || it == '+' }.take(5)
                        if (prefix.isNotBlank()) RuleStore.addPrefix(this, prefix)
                        changed()
                        android.widget.Toast.makeText(this, if (isFa) "سرشماره $prefix به فهرست بلاک اضافه شد" else "Prefix $prefix blocked", android.widget.Toast.LENGTH_LONG).show()
                    }
                    3 -> {
                        RuleStore.addBlocked(this, number)
                        changed()
                        confirmDeleteConversation(number, isFa, changed)
                    }
                }
                dialog.dismiss()
            }.show()
    }

    private fun confirmDeleteConversation(number: String, isFa: Boolean, changed: () -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(if (isFa) "حذف گفتگو؟" else "Delete conversation?")
            .setMessage(if (isFa) "تمام پیامک‌های این شماره از صندوق پیامک حذف می‌شوند." else "All SMS messages for this number will be deleted from the SMS provider.")
            .setNegativeButton(if (isFa) "انصراف" else "Cancel", null)
            .setPositiveButton(if (isFa) "حذف" else "Delete") { _, _ ->
                val currentRows = cachedSmsRows ?: emptyList()
                val hadFullHistory = cachedAllSmsRows != null
                val allCurrentRows = cachedAllSmsRows ?: currentRows
                val toArchive = allCurrentRows.filter { it.address == number }
                cachedSmsRows = currentRows.filterNot { it.address == number }
                cachedAllSmsRows = if (hadFullHistory) allCurrentRows.filterNot { it.address == number } else null
                cachedConversationPreviews = null
                changed()
                android.widget.Toast.makeText(this, if (isFa) "در حال حذف گفتگو…" else "Deleting conversation…", android.widget.Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            archiveDeletedMessages(toArchive)
                            contentResolver.delete(Telephony.Sms.CONTENT_URI, "address = ?", arrayOf(number))
                        }
                    }
                    result.onSuccess {
                        cachedSmsRows = (cachedSmsRows ?: emptyList()).filterNot { it.address == number }
                        cachedAllSmsRows = cachedAllSmsRows?.filterNot { it.address == number }
                        cachedConversationPreviews = null
                        changed()
                        android.widget.Toast.makeText(this@MainActivity, if (isFa) "گفتگو حذف شد" else "Conversation deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        cachedSmsRows = null
                        cachedAllSmsRows = null
                        cachedConversationPreviews = null
                        changed()
                        android.widget.Toast.makeText(this@MainActivity, if (isFa) "حذف گفتگو ناموفق بود" else "Could not delete conversation", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun phoneLookupKeys(number: String): List<String> {
        val key = number.trim()
        if (key.isBlank()) return emptyList()
        val digits = key.filter { it.isDigit() }
        return linkedSetOf<String>().apply {
            add(key)
            if (digits.isNotBlank()) {
                add(digits)
                add("+$digits")
                if (digits.startsWith("00") && digits.length > 2) add(digits.drop(2))
                if (digits.startsWith("0") && digits.length > 1) add(digits.drop(1))
            }
        }.toList()
    }

    /** Build one phonebook index instead of issuing 3-6 Contacts-provider queries per chat. */
    private fun getContactNamesIndex(): Map<String, String> {
        contactNamesIndex?.let { return it }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val built = HashMap<String, String>(4096)
        runCatching {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = if (ni >= 0) c.getString(ni).orEmpty() else ""
                    val number = if (pi >= 0) c.getString(pi).orEmpty() else ""
                    if (name.isBlank() || number.isBlank()) continue
                    phoneLookupKeys(number).forEach { key -> built.putIfAbsent(key, name) }
                }
            }
        }.onFailure { android.util.Log.w("SMSGuardian", "Bulk contact index failed", it) }
        contactNamesIndex = built
        return built
    }

    private fun lookupContactName(number: String): String? {
        val key = number.trim()
        if (key.isBlank()) return null
        contactNameCache[key]?.let { return it.takeUnless { cached -> cached == "\u0000" } }
        val index = getContactNamesIndex()
        val result = phoneLookupKeys(key).firstNotNullOfOrNull { index[it] }
        contactNameCache[key] = result ?: "\u0000"
        return result
    }

    private fun archiveDeletedMessages(messages: List<SmsRow>) {
        if (messages.isEmpty()) return
        val prefs = getSharedPreferences("deleted_sms_archive", MODE_PRIVATE)
        val all = runCatching { org.json.JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val known = mutableSetOf<String>()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            known += "${item.optString("address")}|${item.optLong("date")}|${item.optString("body")}"
        }
        messages.forEach { row ->
            val key = "${row.address}|${row.date}|${row.body}"
            if (known.add(key)) all.put(org.json.JSONObject().put("address", row.address).put("body", row.body).put("date", row.date).put("type", row.type))
        }
        prefs.edit().putString("items", all.toString()).apply()
    }

    private fun readDeletedMessages(): List<SmsRow> {
        val prefs = getSharedPreferences("deleted_sms_archive", MODE_PRIVATE)
        val all = runCatching { org.json.JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        return buildList {
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                add(SmsRow(item.optString("address"), item.optString("body"), item.optLong("date"), item.optInt("type", Telephony.Sms.MESSAGE_TYPE_INBOX)))
            }
        }.sortedByDescending { it.date }
    }

    private fun restoreDeletedMessage(row: SmsRow): Boolean {
        if (!isDefaultSmsApp()) return false
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, row.address)
                put(Telephony.Sms.BODY, row.body)
                put(Telephony.Sms.DATE, row.date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, if (row.type == Telephony.Sms.MESSAGE_TYPE_SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            contentResolver.insert(Telephony.Sms.CONTENT_URI, values) ?: return false
            removeArchivedMessage(row)
            true
        }.getOrDefault(false)
    }

    private fun removeArchivedMessage(row: SmsRow) {
        val prefs = getSharedPreferences("deleted_sms_archive", MODE_PRIVATE)
        val old = runCatching { org.json.JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val fresh = org.json.JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("address") == row.address && item.optLong("date") == row.date && item.optString("body") == row.body) continue
            fresh.put(item)
        }
        prefs.edit().putString("items", fresh.toString()).apply()
    }

    @Composable private fun DeletedMessagesPage(isFa: Boolean, refresh: Int, changed: () -> Unit) {
        val messages = remember(refresh) { readDeletedMessages() }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(if (isFa) "پیامک‌های حذف‌شده" else "Deleted messages", color = TextWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
            Text(if (isFa) "پیامک‌هایی که از این پس از داخل برنامه حذف شوند، اینجا نگهداری می‌شوند." else "Messages deleted from within the app from now on will be kept here.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (isFa) "آرشیو پیامک‌های حذف‌شده خالی است." else "The deleted-message archive is empty.", color = Muted) }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { "deleted-${it.address}-${it.date}-${it.body.hashCode()}" }) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${lookupContactName(row.address) ?: row.address} · ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date))}", color = Cyan, fontWeight = FontWeight.SemiBold)
                            Text(row.body, color = TextWhite)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val ok = restoreDeletedMessage(row)
                                    android.widget.Toast.makeText(this@MainActivity, if (ok) { if (isFa) "پیامک بازیابی شد" else "Message restored" } else { if (isFa) "بازیابی ناموفق بود؛ برنامه باید پیام‌رسان پیش‌فرض باشد." else "Restore failed; the app must be the default SMS app." }, android.widget.Toast.LENGTH_LONG).show()
                                    changed()
                                }) { Text(if (isFa) "بازیابی" else "Restore") }
                                TextButton(onClick = { removeArchivedMessage(row); changed() }) { Text(if (isFa) "حذف از آرشیو" else "Remove from archive") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts.first().first()}${parts.last().first()}".uppercase() else parts.firstOrNull()?.take(2)?.uppercase() ?: "?"
    }

    @Composable private fun RulesPage(isFa: Boolean, refresh: Int, changed: () -> Unit) {
        var kind by remember { mutableStateOf("word") }
        var value by remember { mutableStateOf("") }
        val words = remember(refresh) { RuleStore.words(this@MainActivity) }
        val numbers = remember(refresh) { RuleStore.numbers(this@MainActivity) }
        val prefixes = remember(refresh) { RuleStore.prefixes(this@MainActivity) }
        val blocked = remember(refresh) { RuleStore.blocked(this@MainActivity) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "افزودن قانون فیلتر" else "Add a filtering rule", color = TextWhite, fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("word" to (if (isFa) "کلمه" else "Keyword"), "number" to (if (isFa) "شماره" else "Number"), "prefix" to (if (isFa) "سرشماره" else "Prefix")).forEachIndexed { index, item ->
                            SegmentedButton(selected = kind == item.first, onClick = { kind = item.first }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(item.second) }
                        }
                    }
                    OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(when (kind) { "word" -> if (isFa) "عبارت؛ مثلاً وام فوری" else "Phrase, e.g. instant loan"; "number" -> if (isFa) "شماره کامل" else "Full phone number"; else -> if (isFa) "سرشماره؛ مثلاً 3000*" else "Prefix, e.g. 3000*" }) })
                    Button(onClick = { when (kind) { "word" -> RuleStore.addWord(this@MainActivity, value); "number" -> RuleStore.addNumber(this@MainActivity, value); else -> RuleStore.addPrefix(this@MainActivity, value) }; value = ""; changed() }, modifier = Modifier.fillMaxWidth(), enabled = value.isNotBlank()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(if (isFa) "افزودن قانون" else "Add rule") }
                    Text(if (isFa) "با تطبیق کلمه، شماره فرستنده نیز خودکار مسدود می‌شود." else "When a keyword matches, the sender is automatically added to blocked numbers.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                item { Text(if (isFa) "کلمات و عبارت‌ها" else "Keywords", color = Cyan, fontWeight = FontWeight.Bold) }
                items(words, key = { "w$it" }) { RuleRow(it, "word", isFa, changed) }
                item { Spacer(Modifier.height(4.dp)); Text(if (isFa) "شماره‌های کامل" else "Blocked numbers", color = Cyan, fontWeight = FontWeight.Bold) }
                items(numbers, key = { "n$it" }) { RuleRow(it, "number", isFa, changed) }
                item { Spacer(Modifier.height(4.dp)); Text(if (isFa) "الگوهای سرشماره" else "Sender prefixes", color = Cyan, fontWeight = FontWeight.Bold) }
                items(prefixes, key = { "p$it" }) { RuleRow("$it*", "prefix", isFa, changed) }
                item { Spacer(Modifier.height(4.dp)); Text(if (isFa) "شماره‌های مسدودشده خودکار و دستی" else "Blocked senders", color = Cyan, fontWeight = FontWeight.Bold) }
                items(blocked, key = { "b$it" }) { RuleRow(it, "blocked", isFa, changed) }
            }
        }
    }

    @Composable private fun RuleRow(value: String, kind: String, isFa: Boolean, changed: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = TextWhite, modifier = Modifier.weight(1f))
            IconButton(onClick = { RuleStore.remove(this@MainActivity, kind, value.removeSuffix("*")); changed() }) { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF7D89)) }
        }
    }

    @Composable private fun AboutPage(isFa: Boolean) {
        val lightAbout = getPreferences(MODE_PRIVATE).getInt("bg_choice", 3) == 3
        val aboutCardText = if (lightAbout) Color(0xFF172033) else TextWhite
        val aboutMuted = if (lightAbout) Color(0xFF596579) else Muted
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF173C59), Color(0xFF10283F)))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Brush.linearGradient(listOf(Cyan, Color(0xFF356BCE))), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Chat, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
                    Text("SMS Guardian", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "مدیریت هوشمند پیامک‌ها، فیلتر و کنترل گفتگوها" else "Smart SMS management, filtering and conversation control", color = Color.White.copy(alpha = 0.88f))
                    Text("Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0.2"}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    AssistChip(onClick = {}, colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(alpha = 0.14f), labelColor = Color.White, leadingIconContentColor = Color.White), label = { Text(if (isFa) "حریم خصوصی؛ اطلاعات روی دستگاه شما" else "Privacy-minded · on-device rules") }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "قابلیت‌ها" else "Features", color = Cyan, fontWeight = FontWeight.Bold)
                    listOf(
                        if (isFa) "مدیریت گفتگوها و ارسال پیامک" else "Conversation management and SMS sending",
                        if (isFa) "فیلتر بر اساس کلمات، شماره کامل و سرشماره" else "Filtering by keywords, full numbers and prefixes",
                        if (isFa) "مدیریت فهرست مسدودها" else "Manage blocked senders",
                        if (isFa) "تنظیم زبان و ظاهر برنامه" else "Customize language and appearance"
                    ).forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.FilterAlt, null, tint = Cyan, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(item, color = aboutCardText) } }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isFa) "توسعه‌دهنده" else "Developer", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("سیدرضا هاشمی · S. Reza Hashemi", color = aboutCardText)
                    Text("hashemi.srh@gmail.com", color = Cyan, modifier = Modifier.clickable { runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hashemi.srh@gmail.com"))) } })
                    Text(if (isFa) "برای دریافت و فیلتر پیامک‌های ورودی، برنامه باید به‌عنوان پیام‌رسان پیش‌فرض انتخاب شده باشد و مجوز اعلان‌ها فعال باشد." else "To receive and filter incoming SMS, set this app as the default SMS app and allow notifications.", color = aboutMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable private fun SmsForwardingPage(isFa: Boolean) {
        val prefs = getSharedPreferences(SmsForwarding.PREFS, MODE_PRIVATE)
        var source by remember { mutableStateOf(prefs.getString("source", "") ?: "") }
        var keyword by remember { mutableStateOf(prefs.getString("keyword", "") ?: "") }
        var d1 by remember { mutableStateOf(prefs.getString("destination1", "") ?: "") }
        var d2 by remember { mutableStateOf(prefs.getString("destination2", "") ?: "") }
        var d3 by remember { mutableStateOf(prefs.getString("destination3", "") ?: "") }
        var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
        var last by remember { mutableStateOf(prefs.getString("last", "") ?: "") }
        var reportTitle by remember { mutableStateOf("") }
        var reportBody by remember { mutableStateOf("") }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isFa) "فوروارد خودکار پیامک" else "Automatic SMS forwarding", style = MaterialTheme.typography.titleLarge, color = TextWhite, fontWeight = FontWeight.Bold)
            Text(if (isFa) "قانون‌ها فقط روی همین گوشی ذخیره می‌شوند. هنگام فعال بودن، اعلان دائمی وضعیت نمایش داده می‌شود." else "Rules are stored locally on this phone. An ongoing status notification is shown while forwarding is enabled.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (enabled) (if (isFa) "فوروارد فعال است" else "Forwarding is active") else (if (isFa) "فوروارد غیرفعال است" else "Forwarding is inactive"), color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Text(if (isFa) "فعال‌سازی بعد از ذخیره تنظیمات" else "Settings must be saved before activation", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    val dests = listOf(d1,d2,d3).any { it.isNotBlank() }
                    if (on && !dests) { android.widget.Toast.makeText(this@MainActivity, if (isFa) "حداقل یک شماره مقصد وارد کنید و ذخیره کنید." else "Enter and save at least one destination first.", android.widget.Toast.LENGTH_LONG).show() }
                    else {
                        enabled = on
                        prefs.edit().putString("source", source.trim()).putString("keyword", keyword.trim())
                            .putString("destination1", d1.trim()).putString("destination2", d2.trim()).putString("destination3", d3.trim())
                            .putBoolean("enabled", on).apply()
                        if (on) SmsForwarding.showStatus(this@MainActivity) else SmsForwarding.hideStatus(this@MainActivity)
                    }
                })
            }
            Text(if (isFa) "شماره فرستنده (اختیاری)" else "Sender number (optional)", color = TextWhite, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = source, onValueChange = { source = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (isFa) "خالی = هر شماره‌ای" else "Blank = any sender") })
            Text(if (isFa) "عبارت موردنظر (اختیاری)" else "Keyword (optional)", color = TextWhite, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = keyword, onValueChange = { keyword = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(if (isFa) "خالی = هر پیامکی" else "Blank = every SMS") })
            Text(if (isFa) "شماره مقصد ۱ (اجباری)" else "Destination 1 (required)", color = TextWhite, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = d1, onValueChange = { d1 = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("+98912…") })
            Text(if (isFa) "شماره مقصد ۲ (اختیاری)" else "Destination 2 (optional)", color = TextWhite, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = d2, onValueChange = { d2 = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text(if (isFa) "شماره مقصد ۳ (اختیاری)" else "Destination 3 (optional)", color = TextWhite, fontWeight = FontWeight.Medium)
            OutlinedTextField(value = d3, onValueChange = { d3 = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                if (listOf(d1,d2,d3).none { it.isNotBlank() }) {
                    android.widget.Toast.makeText(this@MainActivity, if (isFa) "حداقل یک شماره مقصد لازم است." else "At least one destination is required.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("source", source.trim()).putString("keyword", keyword.trim()).putString("destination1", d1.trim()).putString("destination2", d2.trim()).putString("destination3", d3.trim()).putBoolean("enabled", enabled).apply()
                    if (enabled) SmsForwarding.showStatus(this@MainActivity) else SmsForwarding.hideStatus(this@MainActivity)
                    android.widget.Toast.makeText(this@MainActivity, if (isFa) "تنظیمات فوروارد ذخیره شد." else "Forwarding settings saved.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (isFa) "ذخیره تنظیمات" else "Save settings") }
            Text(if (isFa) "آخرین وضعیت: ${last.ifBlank { "هنوز فورواردی ثبت نشده" }}\nموفق: ${prefs.getInt("total_ok",0)}  •  ناموفق: ${prefs.getInt("total_failed",0)}" else "Last status: ${last.ifBlank { "No forwarding yet" }}\nSuccessful: ${prefs.getInt("total_ok",0)}  •  Failed: ${prefs.getInt("total_failed",0)}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    reportTitle = if (isFa) "گزارش امروز" else "Daily report"
                    reportBody = "${if (isFa) "موفق" else "Successful"}: ${prefs.getInt("day_${day}_ok",0)}\n${if (isFa) "ناموفق" else "Failed"}: ${prefs.getInt("day_${day}_fail",0)}"
                }, modifier = Modifier.weight(1f)) { Text(if (isFa) "گزارش روزانه" else "Daily report") }
                OutlinedButton(onClick = {
                    val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
                    reportTitle = if (isFa) "گزارش ماهانه" else "Monthly report"
                    reportBody = "${if (isFa) "موفق" else "Successful"}: ${prefs.getInt("month_${month}_ok",0)}\n${if (isFa) "ناموفق" else "Failed"}: ${prefs.getInt("month_${month}_fail",0)}"
                }, modifier = Modifier.weight(1f)) { Text(if (isFa) "گزارش ماهانه" else "Monthly report") }
            }
            OutlinedButton(onClick = { reportTitle = if (isFa) "تاریخچه فوروارد" else "Forwarding history"; reportBody = prefs.getString("history", "").orEmpty().ifBlank { if (isFa) "هنوز تاریخچه‌ای وجود ندارد." else "No forwarding history yet." } }, modifier = Modifier.fillMaxWidth()) { Text(if (isFa) "نمایش تاریخچه" else "View history") }
        }
        if (reportTitle.isNotBlank()) AlertDialog(onDismissRequest = { reportTitle = "" }, title = { Text(reportTitle) }, text = { Text(reportBody) }, confirmButton = { TextButton(onClick = { reportTitle = "" }) { Text(if (isFa) "بستن" else "Close") } })
    }

    @Composable private fun SettingsPage(isFa: Boolean, language: String, changeLanguage: (String) -> Unit, requestRole: () -> Unit, bgChoice: Int, changeBackground: (Int) -> Unit, iconChoice: Int, changeIcon: (Int) -> Unit) {
        val isDefault = Telephony.Sms.getDefaultSmsPackage(this) == packageName
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "رنگ زمینه برنامه" else "App background color", color = TextWhite, fontWeight = FontWeight.Bold)
                    listOf((0 to (if (isFa) "آبی تیره" else "Midnight blue")), (1 to (if (isFa) "بنفش" else "Purple")), (2 to (if (isFa) "سبز جنگلی" else "Forest green")), (3 to (if (isFa) "سفید" else "White"))).forEach { (choice, label) ->
                        Row(Modifier.fillMaxWidth().clickable { changeBackground(choice) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = bgChoice == choice, onClick = { changeBackground(choice) })
                            Text(label, color = TextWhite)
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "آیکون برنامه" else "App icon", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "یکی از چهار آیکون را انتخاب کنید. ممکن است لانچر گوشی کمی زمان برای به‌روزرسانی نیاز داشته باشد." else "Choose one of four launcher icons. Your launcher may take a moment to refresh.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    listOf(if (isFa) "اصلی" else "Classic", if (isFa) "اقیانوسی" else "Ocean", if (isFa) "غروب" else "Sunset", if (isFa) "مینیمال" else "Minimal").forEachIndexed { index, label ->
                        Row(Modifier.fillMaxWidth().clickable { changeIcon(index) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = iconChoice == index, onClick = { changeIcon(index) })
                            Box(Modifier.size(34.dp).background(listOf(Color(0xFF12C9C3), Color(0xFF356BCE), Color(0xFFFF8A5B), Color(0xFF6B7280))[index], CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Chat, null, tint = Color.White) }
                            Spacer(Modifier.width(10.dp)); Text(label, color = TextWhite)
                            if (iconChoice == index) { Spacer(Modifier.weight(1f)); Icon(Icons.Default.Check, null, tint = Cyan) }
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "زبان برنامه" else "App language", color = TextWhite, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = language == "fa", onClick = { changeLanguage("fa") }, label = { Text("فارسی") })
                        FilterChip(selected = language == "en", onClick = { changeLanguage("en") }, label = { Text("English") })
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "پیام‌رسان پیش‌فرض" else "Default SMS app", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isDefault) { if (isFa) "این برنامه پیام‌رسان پیش‌فرض است." else "This app is the default SMS handler." } else { if (isFa) "برای فیلتر و مدیریت کامل پیامک‌ها، این برنامه را به‌عنوان پیام‌رسان پیش‌فرض انتخاب کنید." else "Set this app as the default SMS handler to filter and manage messages." }, color = Muted)
                    Button(onClick = requestRole, modifier = Modifier.fillMaxWidth()) { Text(if (isFa) "انتخاب به‌عنوان پیام‌رسان پیش‌فرض" else "Set as default SMS app") }
                }
            }
            Text(if (isFa) "برای فیلتر پیامک‌های ورودی، برنامه باید پیام‌رسان پیش‌فرض گوشی باشد." else "To filter incoming messages, this app must be the phone's default SMS app.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
