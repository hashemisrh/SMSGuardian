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
import android.telephony.PhoneNumberUtils
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ForwardToInbox
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private fun isValidClockTime(value: String): Boolean =
    value.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))

private val Navy = Color(0xFF081326)
private var Panel = Color(0xFF111F35)
private var Cyan = Color(0xFF12C9C3)
private var TextWhite = Color(0xFFF1F6FF)
private var Muted = Color(0xFF9AAAC3)
private data class ContactSuggestion(val name: String, val number: String)
private data class InternalSmsGroup(val id: String, val name: String, val members: List<String>, val createdAt: Long)
private data class ConversationPreview(val address: String, val latestMessage: SmsRow, val contactName: String?)

data class SmsRow(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : ComponentActivity() {
    private var smsRoleRefreshSignal by mutableIntStateOf(0)
    private var launchRecipient by mutableStateOf<String?>(null)
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
                "دسترسی پیامک داده نشده است. ابتدا مدیریت پیامک را از تنظیمات برنامه‌های پیش‌فرض به‌عنوان برنامه پیامک انتخاب کنید. اگر انتخاب شده ولی دسترسی‌ها رد می‌شوند، این APK هنوز مجوزهای لازم نقش پیامک را نگرفته است.",
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
            android.widget.Toast.makeText(this, "برای دریافت و مدیریت پیامک‌ها، مدیریت پیامک را در تنظیمات گوشی به‌عنوان برنامه پیامک پیش‌فرض انتخاب کنید.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { GuardianApp() }
        // Do not trigger Android's SMS permission denial on first launch: role must come first.
        window.decorView.post {
            if (isDefaultSmsApp()) requestNonSmsPermissions() else requestDefaultSmsRole()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val fromNotification = intent.getIntExtra("sms_notification_id", -1)
        if (fromNotification >= 0) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(fromNotification)
        }
        val explicitNumber = intent.getStringExtra("open_chat_number")
        val dataNumber = intent.data?.takeIf {
            intent.action == Intent.ACTION_SENDTO || (it.scheme?.let { scheme -> scheme in listOf("sms", "smsto", "mms", "mmsto", "smsguardian") } == true)
        }?.let { uri ->
            if (uri.scheme == "smsguardian") uri.getQueryParameter("number")
                ?: uri.lastPathSegment
            else uri.schemeSpecificPart.substringBefore('?').trim()
        }
        val number = (explicitNumber ?: dataNumber)?.trim()?.takeIf { it.isNotBlank() }
        if (number != null) launchRecipient = Uri.decode(number).trim().takeIf { it.isNotBlank() }
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
        val selected = choice.coerceIn(0, aliases.lastIndex)
        // Enable the new launcher entry first: disabling the active alias first can
        // make some launchers close/restart the app while no launcher entry is active.
        pm.setComponentEnabledSetting(android.content.ComponentName(this, packageName + aliases[selected]), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        aliases.forEachIndexed { index, alias ->
            if (index != selected) pm.setComponentEnabledSetting(android.content.ComponentName(this, packageName + alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        getPreferences(MODE_PRIVATE).edit().putInt("launcher_icon_choice", selected).apply()
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
        var chatFontScale by remember { mutableFloatStateOf(getPreferences(MODE_PRIVATE).getFloat("chat_font_scale", 1.0f).coerceIn(0.8f, 1.5f)) }
        var smsFontScale by remember { mutableFloatStateOf(getPreferences(MODE_PRIVATE).getFloat("sms_font_scale", 1.0f).coerceIn(0.8f, 1.5f)) }
        var launcherIconChoice by remember { mutableIntStateOf(getPreferences(MODE_PRIVATE).getInt("launcher_icon_choice", 3)) }
        val appBg = when (bgChoice) {
            1 -> Color(0xFF24152F) // Purple
            2 -> Color(0xFF102A24) // Forest
            3 -> Color(0xFFF7F9FC) // White
            4 -> Color(0xFF050608) // Midnight Black
            5 -> Color(0xFF35172C) // Pink
            6 -> Color(0xFF3B0718) // Cherry
            7 -> Color(0xFF09213B) // Deep Ocean
            8 -> Color(0xFF33240D) // Amber
            9 -> Color(0xFF172D2A) // Jade
            else -> Navy
        }
        val lightTheme = bgChoice == 3
        Panel = if (lightTheme) Color(0xFFFFFFFF) else when (bgChoice) {
            1 -> Color(0xFF33213F); 2 -> Color(0xFF18382F); 4 -> Color(0xFF111214)
            5 -> Color(0xFF4A203B); 6 -> Color(0xFF521025); 7 -> Color(0xFF123250)
            8 -> Color(0xFF493414); 9 -> Color(0xFF23413C); else -> Color(0xFF111F35)
        }
        TextWhite = if (lightTheme) Color(0xFF172033) else Color(0xFFF1F6FF)
        Muted = if (lightTheme) Color(0xFF647084) else Color(0xFFB4C0D2)
        Cyan = if (lightTheme) Color(0xFF087F8C) else if (bgChoice == 5 || bgChoice == 6) Color(0xFFFF9CC8) else Color(0xFF12C9C3)
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(smsRoleRefreshSignal) { refresh++ }
        LaunchedEffect(Unit) {
            val migration = getPreferences(MODE_PRIVATE)
            // Minimal is the default only on a truly fresh install. Never overwrite
            // a previously saved user's launcher icon when the app starts/upgrades.
            if (!migration.getBoolean("minimal_icon_default_applied", false)) {
                if (!migration.contains("launcher_icon_choice")) {
                    setLauncherIcon(3)
                    launcherIconChoice = 3
                } else {
                    launcherIconChoice = migration.getInt("launcher_icon_choice", 3).coerceIn(0, 3)
                    setLauncherIcon(launcherIconChoice)
                }
                migration.edit().putBoolean("minimal_icon_default_applied", true).apply()
            } else {
                launcherIconChoice = migration.getInt("launcher_icon_choice", 3).coerceIn(0, 3)
            }
        }
        val isFa = language == "fa"
        BackHandler(enabled = page != "chats") { page = "chats" }
        val direction = if (isFa) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MaterialTheme(colorScheme = if (lightTheme) lightColorScheme(primary = Cyan, secondary = Color(0xFF356BCE), background = appBg, surface = Panel, onBackground = TextWhite, onSurface = TextWhite) else darkColorScheme(primary = Cyan, secondary = Color(0xFF356BCE), background = appBg, surface = Panel, onBackground = TextWhite, onSurface = TextWhite)) {
                Surface(Modifier.fillMaxSize(), color = appBg) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().background(Brush.verticalGradient(listOf(appBg.copy(red = (appBg.red + 0.06f).coerceAtMost(1f)), appBg)))) {
                        Header(
                            isFa, page,
                            { language = if (language == "fa") "en" else "fa"; getPreferences(MODE_PRIVATE).edit().putString("language", language).apply() },
                            { page = it },
                            chatFontScale, smsFontScale,
                            { chatValue, smsValue ->
                                chatFontScale = chatValue.coerceIn(0.8f, 1.5f)
                                smsFontScale = smsValue.coerceIn(0.8f, 1.5f)
                                getPreferences(MODE_PRIVATE).edit().putFloat("chat_font_scale", chatFontScale).putFloat("sms_font_scale", smsFontScale).apply()
                            }
                        )
                        when (page) {
                            "rules" -> RulesPage(isFa, refresh) { refresh++ }
                            "forwarding" -> SmsForwardingPage(isFa)
                            "settings" -> SettingsPage(isFa, language, { language = it; getPreferences(MODE_PRIVATE).edit().putString("language", it).apply() }, { requestDefaultSmsRole() }, bgChoice, { choice -> bgChoice = choice; getPreferences(MODE_PRIVATE).edit().putInt("bg_choice", choice).apply() }, launcherIconChoice) { choice -> launcherIconChoice = choice; setLauncherIcon(choice) }
                            "about" -> AboutPage(isFa)
                            "deleted" -> DeletedMessagesPage(isFa, refresh) { refresh++ }
                            else -> ChatsPage(isFa, refresh, bgChoice, chatFontScale, smsFontScale, ::getSmsRows, ::sendSms)
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

    @Composable private fun Header(
        isFa: Boolean, page: String, toggleLanguage: () -> Unit, navigate: (String) -> Unit,
        chatFontScale: Float, smsFontScale: Float, onFontScalesChanged: (Float, Float) -> Unit
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        var showDonate by remember { mutableStateOf(false) }
        var showFontSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = menuExpanded) { menuExpanded = false }
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.Menu, contentDescription = if (isFa) "منو" else "Menu", tint = Cyan) }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(292.dp),
                    shape = RoundedCornerShape(24.dp),
                    containerColor = Panel,
                    tonalElevation = 10.dp
                ) {
                    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF10243D), Color(0xFF087F8C)))).padding(horizontal = 18.dp, vertical = 17.dp)) {
                        Text(if (isFa) "مدیریت پیامک" else "SMS Guardian", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(if (isFa) "دسترسی سریع به بخش‌های برنامه" else "Quick access to app sections", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Home, null, tint = Cyan) }, text = { Text(if (isFa) "گفتگوها" else "Chats", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("chats") }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.FilterAlt, null, tint = Cyan) }, text = { Text(if (isFa) "قوانین و فیلترها" else "Rules & filters", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("rules") }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = Cyan) }, text = { Text(if (isFa) "حذف‌شده‌ها و بلاک‌شده‌ها" else "Deleted & blocked", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("deleted") }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.ForwardToInbox, null, tint = Cyan) }, text = { Text(if (isFa) "فوروارد خودکار پیامک" else "SMS forwarding", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("forwarding") }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Settings, null, tint = Cyan) }, text = { Text(if (isFa) "تنظیمات برنامه" else "App settings", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("settings") }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Info, null, tint = Cyan) }, text = { Text(if (isFa) "درباره برنامه" else "About", color = TextWhite, fontWeight = FontWeight.Medium) }, onClick = { menuExpanded = false; navigate("about") }, modifier = Modifier.padding(horizontal = 6.dp))
                    HorizontalDivider(color = Muted.copy(alpha = .2f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.TextFields, null, tint = Cyan) }, text = { Text(if (isFa) "اندازه قلم چت و پیامک" else "Chat and SMS font size", color = TextWhite) }, onClick = { menuExpanded = false; showFontSettings = true }, modifier = Modifier.padding(horizontal = 6.dp))
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Favorite, null, tint = Color(0xFFE879A8)) }, text = { Text(if (isFa) "حمایت از توسعه برنامه" else "Support development", color = TextWhite) }, onClick = { menuExpanded = false; showDonate = true }, modifier = Modifier.padding(horizontal = 6.dp))
                    Spacer(Modifier.height(5.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("مدیریت پیامک", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextWhite)
                Text(if (page == "chats") "Messenger; But Lovely" else if (page == "rules") { if (isFa) "مدیریت قوانین هوشمند" else "Smart filtering rules" } else if (page == "forwarding") { if (isFa) "ارسال خودکار پیامک‌های مطابق شرط" else "Automatically forward matching SMS" } else { if (isFa) "شخصی‌سازی برنامه" else "Personalize your app" }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.clickable(onClick = toggleLanguage).padding(8.dp))
        }
        if (showFontSettings) AlertDialog(
            onDismissRequest = { showFontSettings = false },
            title = { Text(if (isFa) "تنظیم اندازه قلم" else "Font size settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "اندازه متن گفتگوها: ${(chatFontScale * 100).toInt()}٪" else "Chat list text: ${(chatFontScale * 100).toInt()}%")
                    Slider(value = chatFontScale, onValueChange = { onFontScalesChanged(it, smsFontScale) }, valueRange = 0.8f..1.5f, steps = 6)
                    Text(if (isFa) "اندازه متن پیامک‌ها: ${(smsFontScale * 100).toInt()}٪" else "SMS text: ${(smsFontScale * 100).toInt()}%")
                    Slider(value = smsFontScale, onValueChange = { onFontScalesChanged(chatFontScale, it) }, valueRange = 0.8f..1.5f, steps = 6)
                }
            },
            confirmButton = { TextButton(onClick = { showFontSettings = false }) { Text(if (isFa) "تمام" else "Done") } },
            dismissButton = { TextButton(onClick = { onFontScalesChanged(1f, 1f); showFontSettings = false }) { Text(if (isFa) "بازنشانی" else "Reset") } }
        )
        if (showDonate) AlertDialog(
            onDismissRequest = { showDonate = false },
            title = { Text(if (isFa) "حمایت از توسعه برنامه" else "Support development") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isFa) "از حمایت شما سپاسگزارم. شماره کارت:" else "Thank you for supporting the app. Bank card number:")
                Text("5054161017807474", color = Cyan, fontWeight = FontWeight.Bold)
            } },
            confirmButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Donation card", "5054161017807474")); android.widget.Toast.makeText(this@MainActivity, if (isFa) "شماره کارت کپی شد" else "Card number copied", android.widget.Toast.LENGTH_SHORT).show(); showDonate = false }) { Text(if (isFa) "کپی شماره کارت" else "Copy card number") } },
            dismissButton = { TextButton(onClick = { showDonate = false }) { Text(if (isFa) "بستن" else "Close") } }
        )
    }

    @Composable private fun ChatsPage(isFa: Boolean, refresh: Int, bgChoice: Int, chatFontScale: Float, smsFontScale: Float, loadRows: () -> List<SmsRow>, send: (String, String, Int?) -> Unit) {
        var rows by remember { mutableStateOf(emptyList<SmsRow>()) }
        var initialLoadComplete by remember { mutableStateOf(false) }
        var destination by remember { mutableStateOf("") }
        var recipientNumber by remember { mutableStateOf("") }
        var chatSearch by remember { mutableStateOf("") }
        var searchExpanded by remember { mutableStateOf(false) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedConversations by remember { mutableStateOf(setOf<String>()) }
        val chatPrefs = getSharedPreferences("sms_guardian_chat_organization", MODE_PRIVATE)
        var pinnedChats by remember { mutableStateOf(chatPrefs.getStringSet("pinned_chats", emptySet())?.toSet() ?: emptySet()) }
        var chatFolders by remember { mutableStateOf(chatPrefs.getString("chat_folders", "") ?: "") }
        var activeFolder by remember { mutableStateOf("") }
        var folderDialog by remember { mutableStateOf(false) }
        var folderNameInput by remember { mutableStateOf("") }
        var editingFolder by remember { mutableStateOf<String?>(null) }
        var folderManageDialog by remember { mutableStateOf(false) }
        var pageMenuFolder by remember { mutableStateOf<String?>(null) }
        var deletingFolder by remember { mutableStateOf<String?>(null) }
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
        val conversationListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(launchRecipient) {
            launchRecipient?.let { number ->
                markConversationRead(number)
                selectedNumber = number
                showComposer = false
                launchRecipient = null
            }
        }
        var subId by remember { mutableStateOf<Int?>(null) }
        var sims by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
        var localRefresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(localRefresh, refresh) { pinnedChats = chatPrefs.getStringSet("pinned_chats", emptySet())?.toSet() ?: emptySet() }
        LaunchedEffect(pinnedChats) { if (pinnedChats.isNotEmpty()) conversationListState.animateScrollToItem(0) }
        val unreadCounts = remember(refresh, localRefresh, rows.size, selectedNumber) { unreadSmsCounts() }
        val badgeColorChoice = getSharedPreferences("sms_guardian_settings", MODE_PRIVATE).getInt("unread_badge_color", 0)
        val smsTextDirectionChoice = getSharedPreferences("sms_guardian_settings", MODE_PRIVATE).getInt("sms_text_direction", 0)
        val smsTextDirection = when (smsTextDirectionChoice) {
            1 -> TextDirection.Ltr
            2 -> TextDirection.Rtl
            else -> TextDirection.Content
        }
        val badgeColor = listOf(Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF1565C0), Color(0xFF00897B))[badgeColorChoice.coerceIn(0, 3)]
        LaunchedEffect(refresh, localRefresh) {
            rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadRows() + internalGroupPreviewRows() }
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
            currentNumber?.let { number ->
                getPreferences(MODE_PRIVATE).edit().putString(draftKey(number), threadMessage).apply()
                markConversationRead(number)
            }
            selectedNumber = null
            targetMessage = null
            localRefresh++
        }
        if (currentNumber != null) {
            var loadedThreadRows by remember(currentNumber) { mutableStateOf<List<SmsRow>?>(null) }
            var threadSearchExpanded by remember(currentNumber) { mutableStateOf(false) }
            var threadSearchQuery by remember(currentNumber) { mutableStateOf("") }
            var groupEditDialog by remember(currentNumber) { mutableStateOf(false) }
            var groupNameDraft by remember(currentNumber) { mutableStateOf("") }
            var groupMembersDraft by remember(currentNumber) { mutableStateOf("") }
            LaunchedEffect(currentNumber) {
                threadMessage = getPreferences(MODE_PRIVATE).getString(draftKey(currentNumber), "") ?: ""
            }
            LaunchedEffect(currentNumber, refresh, localRefresh) {
                loadedThreadRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (currentNumber.startsWith("group:")) groupThreadRows(currentNumber.removePrefix("group:")) else querySmsRows(address = currentNumber).sortedBy { it.date }
                }
            }
            val thread = loadedThreadRows ?: emptyList()
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { getPreferences(MODE_PRIVATE).edit().putString(draftKey(currentNumber), threadMessage).apply(); markConversationRead(currentNumber); selectedNumber = null; targetMessage = null; localRefresh++ }) { Icon(Icons.Default.ArrowBack, contentDescription = if (isFa) "بازگشت" else "Back", tint = Cyan) }
                    Column(Modifier.weight(1f).then(if (currentNumber.startsWith("group:")) Modifier.clickable {
                        val g = readInternalGroups().firstOrNull { it.id == currentNumber.removePrefix("group:") }
                        if (g != null) { groupNameDraft = g.name; groupMembersDraft = g.members.joinToString(", "); groupEditDialog = true }
                    } else Modifier)) {
                        val group = if (currentNumber.startsWith("group:")) readInternalGroups().firstOrNull { it.id == currentNumber.removePrefix("group:") } else null
                        Text(group?.name ?: lookupContactName(currentNumber) ?: currentNumber, color = TextWhite, fontWeight = FontWeight.Bold)
                        Text(if (group != null) (if (isFa) "${group.members.size} عضو • برای مدیریت لمس کنید" else "${group.members.size} members • tap to manage") else currentNumber, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (!currentNumber.startsWith("group:") && lookupContactName(currentNumber) == null) {
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
                    IconButton(onClick = { threadSearchExpanded = !threadSearchExpanded; if (!threadSearchExpanded) threadSearchQuery = "" }) {
                        Icon(if (threadSearchExpanded) Icons.Default.Close else Icons.Default.Search, contentDescription = if (isFa) "جست‌وجو در این گفتگو" else "Search this conversation", tint = Cyan)
                    }
                    if (!currentNumber.startsWith("group:")) IconButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(currentNumber)}"))) } }) {
                        Icon(Icons.Default.Call, contentDescription = if (isFa) "تماس" else "Call", tint = Cyan)
                    }
                }
                if (groupEditDialog && currentNumber.startsWith("group:")) AlertDialog(
                    onDismissRequest = { groupEditDialog = false },
                    title = { Text(if (isFa) "مدیریت گروه" else "Manage group") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = groupNameDraft, onValueChange = { groupNameDraft = it }, singleLine = true, label = { Text(if (isFa) "نام گروه" else "Group name") })
                        OutlinedTextField(value = groupMembersDraft, onValueChange = { groupMembersDraft = it }, minLines = 2, label = { Text(if (isFa) "شماره اعضا (با ویرگول جدا کنید)" else "Member numbers (comma separated)") })
                        Text(if (isFa) "اعضا را با افزودن یا حذف شماره‌ها مدیریت کنید. پاسخ‌ها فقط در همین برنامه تجمیع می‌شوند." else "Add or remove numbers. Replies are aggregated locally in this app.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = { TextButton(onClick = {
                        val members = groupMembersDraft.split(',', ';', '\n').map { it.trim() }.filter { it.any(Char::isDigit) }.distinct()
                        if (members.size >= 2) { updateInternalGroup(currentNumber.removePrefix("group:"), groupNameDraft.trim().ifBlank { if (isFa) "گروه پیامکی" else "SMS group" }, members); localRefresh++; groupEditDialog = false }
                        else android.widget.Toast.makeText(this@MainActivity, if (isFa) "گروه باید حداقل دو عضو داشته باشد." else "A group needs at least two members.", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(if (isFa) "ذخیره" else "Save") } },
                    dismissButton = { TextButton(onClick = { groupEditDialog = false }) { Text(if (isFa) "انصراف" else "Cancel") } }
                )
                val searchMatches = remember(thread, threadSearchQuery) {
                    if (threadSearchQuery.isBlank()) emptyList() else thread.withIndex().filter { it.value.body.contains(threadSearchQuery, ignoreCase = true) }.take(100)
                }
                AnimatedVisibility(visible = threadSearchExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = threadSearchQuery, onValueChange = { threadSearchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (isFa) "جست‌وجو در پیام‌های این گفتگو" else "Search messages in this chat") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (threadSearchQuery.isNotEmpty()) IconButton(onClick = { threadSearchQuery = "" }) { Icon(Icons.Default.Close, null) } })
                        if (threadSearchQuery.isNotBlank()) {
                            Text(if (isFa) "${searchMatches.size} نتیجه (حداکثر ۱۰۰ مورد)" else "${searchMatches.size} results (up to 100)", color = Muted, style = MaterialTheme.typography.labelSmall)
                            if (searchMatches.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                                LazyColumn {
                                    items(searchMatches, key = { "thread-search-${it.index}-${it.value.date}" }) { match ->
                                        Column(Modifier.fillMaxWidth().clickable {
                                            targetMessage = match.value
                                            threadSearchExpanded = false
                                        }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Text(match.value.body, color = TextWhite, maxLines = 2)
                                            Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(match.value.date)), color = Muted, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
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
                            Column(Modifier.widthIn(max = 300.dp).background(if (isTarget) Color(0xFFFFE082) else if (outgoing) { if (bgChoice == 3) Color(0xFFD6F5F2) else Color(0xFF124B59) } else Panel, RoundedCornerShape(16.dp)).combinedClickable(onClick = { messageMenu = true }, onLongClick = { messageMenu = true }).padding(12.dp)) {
                                if (currentNumber.startsWith("group:") && !outgoing) Text(lookupContactName(row.address) ?: row.address, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                val linkText = remember(row.body, isTarget, TextWhite, Cyan) { linkedMessageText(row.body, Cyan) }
                                ClickableText(
                                    text = linkText,
                                    style = TextStyle(color = if (isTarget) Color(0xFF172033) else TextWhite, fontSize = 16.sp * smsFontScale, textDirection = smsTextDirection),
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
                    OutlinedTextField(value = threadMessage, onValueChange = { text -> threadMessage = text; getPreferences(MODE_PRIVATE).edit().putString(draftKey(currentNumber), text).apply() }, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 16.sp * smsFontScale), placeholder = { Text(if (isFa) "نوشتن پیامک..." else "Type a message...") }, maxLines = 4)
                    IconButton(enabled = threadMessage.isNotBlank(), onClick = {
                        if (currentNumber.startsWith("group:")) {
                            val group = readInternalGroups().firstOrNull { it.id == currentNumber.removePrefix("group:") }
                            if (group != null) { group.members.forEach { send(it, threadMessage, subId) }; appendInternalGroupMessage(group.id, threadMessage, System.currentTimeMillis()) }
                        } else send(currentNumber, threadMessage, subId)
                        threadMessage = ""
                        getPreferences(MODE_PRIVATE).edit().remove(draftKey(currentNumber)).apply()
                        localRefresh++
                    }) { Icon(Icons.Default.Send, contentDescription = if (isFa) "ارسال" else "Send", tint = Cyan) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selectionMode) (if (isFa) "انتخاب‌شده: ${selectedConversations.size}" else "Selected: ${selectedConversations.size}") else (if (isFa) "گفتگوها" else "Conversations"),
                        color = TextWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    if (selectionMode) {
                        IconButton(enabled = selectedConversations.isNotEmpty(), onClick = {
                            confirmDeleteConversations(selectedConversations.toList(), isFa) {
                                selectedConversations = emptySet()
                                selectionMode = false
                                localRefresh++
                            }
                        }) { Icon(Icons.Default.DeleteOutline, contentDescription = if (isFa) "حذف انتخاب‌شده‌ها" else "Delete selected", tint = if (selectedConversations.isNotEmpty()) Color(0xFFFF6B6B) else Muted) }
                    }
                    IconButton(onClick = { selectionMode = !selectionMode; selectedConversations = emptySet() }) {
                        Icon(if (selectionMode) Icons.Default.Close else Icons.Default.Check, contentDescription = if (isFa) "انتخاب چند گفتگو" else "Select multiple chats", tint = Cyan)
                    }
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
                        var newGroupName by remember { mutableStateOf("") }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = destination,
                                    onValueChange = { typed ->
                                        destination = typed
                                        recipientNumber = typed.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() && it.any(Char::isDigit) }.distinct().joinToString(";")
                                        suggestions = findContactSuggestions(typed.substringAfterLast(',').substringAfterLast(';').trim())
                                        suggestionsExpanded = typed.isNotBlank() && suggestions.isNotEmpty()
                                    },
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
                                                    Modifier.fillMaxWidth().clickable {
                                                        val current = recipientNumber.split(';').filter { it.isNotBlank() }
                                                        recipientNumber = (current + item.number).distinct().joinToString(";")
                                                        destination = (current + item.number).distinct().joinToString(", ") { lookupContactName(it) ?: it }
                                                        suggestionsExpanded = false
                                                    }
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
                                contactCallback = { picked ->
                                    val current = recipientNumber.split(';').filter { it.isNotBlank() }
                                    recipientNumber = (current + picked).distinct().joinToString(";")
                                    destination = (current + picked).distinct().joinToString(", ") { lookupContactName(it) ?: it }
                                    suggestionsExpanded = false
                                }
                                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                            }) { Icon(Icons.Default.Contacts, contentDescription = if (isFa) "انتخاب از دفترچه تلفن" else "Choose contact", tint = Cyan) }
                        }
                        if (recipientNumber.split(';', ',', '\n').map { it.trim() }.filter { it.any(Char::isDigit) }.distinct().size >= 2) {
                            OutlinedTextField(value = newGroupName, onValueChange = { newGroupName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (isFa) "نام گروه (اختیاری)" else "Group name (optional)") })
                            Text(if (isFa) "این گیرنده‌ها به‌صورت یک گفتگوی گروهی داخلی ذخیره می‌شوند؛ پیامک برای هر عضو جداگانه ارسال می‌شود." else "These recipients will share one in-app group conversation; SMS is sent separately to each member.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 16.sp * smsFontScale), label = { Text(if (isFa) "متن پیام" else "Message text") }, minLines = 2)
                        if (sims.isNotEmpty()) {
                            Text(if (isFa) "سیم‌کارت ارسال" else "Send using SIM", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { sims.forEach { (id, label) -> FilterChip(selected = subId == id, onClick = { subId = id }, label = { Text(label) }) } }
                        }
                        Button(onClick = {
                            val targets = recipientNumber.split(';', ',', '\n').map { it.trim() }.filter { it.isNotBlank() && it.any(Char::isDigit) }.distinct()
                            if (targets.isEmpty()) android.widget.Toast.makeText(this@MainActivity, if (isFa) "شماره معتبر وارد کنید." else "Enter a valid recipient number.", android.widget.Toast.LENGTH_SHORT).show()
                            else {
                                targets.forEach { send(it, message, subId) }
                                if (targets.size >= 2) {
                                    val group = createInternalGroup(newGroupName.trim().ifBlank { targets.mapNotNull { lookupContactName(it) }.take(2).joinToString(" + ").ifBlank { if (isFa) "گروه پیامکی" else "SMS group" } }, targets)
                                    appendInternalGroupMessage(group.id, message, System.currentTimeMillis())
                                    selectedNumber = "group:${group.id}"
                                } else selectedNumber = targets.first()
                                threadMessage = ""; destination = ""; recipientNumber = ""; message = ""; newGroupName = ""; suggestionsExpanded = false; showComposer = false; localRefresh++
                                android.widget.Toast.makeText(this@MainActivity, if (isFa) "پیام برای ${targets.size} مخاطب ارسال شد." else "SMS sent to ${targets.size} recipients.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }, modifier = Modifier.fillMaxWidth(), enabled = (recipientNumber.isNotBlank() || destination.isNotBlank()) && message.isNotBlank()) {
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
                val folderNames = chatFolders.split("|").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if (selectedConversations.isNotEmpty() && folderNames.isNotEmpty()) {
                    var moveDialog by remember { mutableStateOf(false) }
                    TextButton(onClick = { moveDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Folder, null); Text(if (isFa) "انتقال ${selectedConversations.size} گفتگو به صفحه" else "Move ${selectedConversations.size} selected chats")
                    }
                    if (moveDialog) AlertDialog(onDismissRequest = { moveDialog = false }, title = { Text(if (isFa) "انتقال گفتگوهای انتخاب‌شده" else "Move selected conversations") },
                        text = { Text(if (isFa) "صفحه مقصد را انتخاب کنید." else "Choose the destination page.") },
                        confirmButton = { TextButton(onClick = { moveDialog = false }) { Text(if (isFa) "بستن" else "Close") } },
                        dismissButton = { Row { folderNames.forEach { folder -> TextButton(onClick = { selectedConversations.forEach { chatPrefs.edit().putString("folder_$it", folder).apply() }; activeFolder = folder; selectedConversations = emptySet(); selectionMode = false; moveDialog = false }) { Text(folder) } } } })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { FilterChip(selected = activeFolder.isBlank(), onClick = { activeFolder = "" }, label = { Text(if (isFa) "همه" else "All") }) }
                        items(folderNames) { folder ->
                            Box {
                                Surface(
                                    onClick = { activeFolder = folder },
                                    shape = RoundedCornerShape(50),
                                    color = if (activeFolder == folder) Cyan.copy(alpha = .18f) else Panel,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (activeFolder == folder) Cyan else Muted.copy(alpha = .25f))
                                ) {
                                    Row(Modifier.padding(start = 13.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(folder, color = if (activeFolder == folder) Cyan else TextWhite, style = MaterialTheme.typography.labelLarge)
                                        IconButton(onClick = { pageMenuFolder = folder }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = if (isFa) "مدیریت صفحه $folder" else "Manage $folder", tint = if (activeFolder == folder) Cyan else Muted, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                }
                                DropdownMenu(expanded = pageMenuFolder == folder, onDismissRequest = { pageMenuFolder = null }, shape = RoundedCornerShape(16.dp), containerColor = Panel) {
                                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Edit, null, tint = Cyan) }, text = { Text(if (isFa) "تغییر نام صفحه" else "Rename page") }, onClick = { pageMenuFolder = null; editingFolder = folder; folderNameInput = folder; folderManageDialog = true })
                                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFE05A5A)) }, text = { Text(if (isFa) "حذف صفحه" else "Delete page") }, onClick = { pageMenuFolder = null; deletingFolder = folder })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { folderNameInput = ""; folderDialog = true }) { Icon(Icons.Default.AddCircle, contentDescription = if (isFa) "صفحه جدید" else "New page", tint = Cyan) }
                }
                if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false }, title = { Text(if (isFa) "صفحه جدید" else "New page") },
                    text = { OutlinedTextField(value = folderNameInput, onValueChange = { folderNameInput = it }, singleLine = true, label = { Text(if (isFa) "نام صفحه" else "Page name") }) },
                    confirmButton = { TextButton(onClick = { val n = folderNameInput.trim(); if (n.isNotBlank()) { chatFolders = (folderNames + n).distinct().joinToString("|"); chatPrefs.edit().putString("chat_folders", chatFolders).apply(); activeFolder = n }; folderDialog = false }) { Text(if (isFa) "ایجاد" else "Create") } },
                    dismissButton = { TextButton(onClick = { folderDialog = false }) { Text(if (isFa) "انصراف" else "Cancel") } })
                if (folderManageDialog && editingFolder != null) AlertDialog(
                    onDismissRequest = { folderManageDialog = false; editingFolder = null },
                    title = { Text(if (isFa) "مدیریت صفحه" else "Manage page") },
                    text = { OutlinedTextField(value = folderNameInput, onValueChange = { folderNameInput = it }, singleLine = true, label = { Text(if (isFa) "نام جدید صفحه" else "New page name") }) },
                    confirmButton = { TextButton(onClick = {
                        val oldName = editingFolder!!; val newName = folderNameInput.trim()
                        if (newName.isNotBlank() && newName != oldName && newName !in folderNames) {
                            val renamed = folderNames.map { if (it == oldName) newName else it }
                            chatFolders = renamed.joinToString("|")
                            val editor = chatPrefs.edit().putString("chat_folders", chatFolders)
                            chatPrefs.all.keys.filter { it.startsWith("folder_") && chatPrefs.getString(it, "") == oldName }.forEach { editor.putString(it, newName) }
                            editor.apply()
                            if (activeFolder == oldName) activeFolder = newName
                        }
                        folderManageDialog = false; editingFolder = null
                    }) { Text(if (isFa) "ذخیره نام" else "Save name") } },
                    dismissButton = { TextButton(onClick = { folderManageDialog = false; editingFolder = null }) { Text(if (isFa) "انصراف" else "Cancel") } })
                if (deletingFolder != null) AlertDialog(
                    onDismissRequest = { deletingFolder = null },
                    title = { Text(if (isFa) "حذف صفحه؟" else "Delete page?") },
                    text = { Text(if (isFa) "صفحه «${deletingFolder}» حذف می‌شود. گفتگوها حذف نمی‌شوند و به بخش همه منتقل خواهند شد." else "The page '${deletingFolder}' will be deleted. Its conversations will remain under All.") },
                    confirmButton = { TextButton(onClick = {
                        val oldName = deletingFolder!!
                        chatFolders = folderNames.filterNot { it == oldName }.joinToString("|")
                        val editor = chatPrefs.edit().putString("chat_folders", chatFolders)
                        chatPrefs.all.keys.filter { it.startsWith("folder_") && chatPrefs.getString(it, "") == oldName }.forEach { editor.remove(it) }
                        editor.apply()
                        if (activeFolder == oldName) activeFolder = ""
                        deletingFolder = null
                    }) { Text(if (isFa) "حذف صفحه" else "Delete page", color = Color(0xFFE05A5A)) } },
                    dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text(if (isFa) "انصراف" else "Cancel") } }
                )
                // Pinned conversations are always sorted before regular conversations.
                val visibleConversations = conversations.filter { activeFolder.isBlank() || chatPrefs.getString("folder_${it.address}", "") == activeFolder }
                    .sortedWith(compareByDescending<SmsRow> { it.address in pinnedChats }.thenByDescending { it.date })
                // Keep the user's current list position when returning from a conversation.
                // rememberLazyListState is hoisted above the chat/list branch and restored here.
                if (conversations.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (!initialLoadComplete && query.isBlank()) CircularProgressIndicator(color = Cyan)
                    else Text(if (query.isNotBlank()) { if (isFa) "نتیجه‌ای برای این جست‌وجو پیدا نشد." else "No matching messages found." } else { if (isFa) "پیامکی برای نمایش پیدا نشد. برای دریافت پیامک جدید، مجوزها و پیام‌رسان پیش‌فرض را بررسی کنید." else "No messages found. Check permissions and the default SMS role to receive new messages." }, color = Muted)
                } else if (visibleConversations.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (isFa) "گفتگویی در این صفحه نیست." else "No conversations in this page.", color = Muted) }
                } else LazyColumn(state = conversationListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleConversations, key = { "${it.address}-${it.date}" }) { row ->
                        val groupPreview = if (row.address.startsWith("group:")) readInternalGroups().firstOrNull { it.id == row.address.removePrefix("group:") } else null
                        val displayName = groupPreview?.name ?: groupedConversations.firstOrNull { it.address == row.address }?.contactName ?: row.address
                        val initials = initialsFor(displayName)
                        val isSelected = row.address in selectedConversations
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Cyan.copy(alpha = .18f) else Panel),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    if (selectionMode) selectedConversations = if (isSelected) selectedConversations - row.address else selectedConversations + row.address
                                    else { markConversationRead(row.address); selectedNumber = row.address; targetMessage = if (appliedChatSearch.isNotBlank()) row else null; showComposer = false; localRefresh++ }
                                },
                                onLongClick = {
                                    if (selectionMode) selectedConversations = if (isSelected) selectedConversations - row.address else selectedConversations + row.address
                                    else showConversationActions(row.address, isFa) { pinnedChats = chatPrefs.getStringSet("pinned_chats", emptySet())?.toSet() ?: emptySet(); localRefresh++ }
                                }
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
                                    Checkbox(checked = isSelected, onCheckedChange = { checked ->
                                        selectedConversations = if (checked) selectedConversations + row.address else selectedConversations - row.address
                                    })
                                    Spacer(Modifier.width(4.dp))
                                }
                                Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Cyan, Color(0xFF356BCE))), CircleShape), contentAlignment = Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(displayName, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 16.sp * chatFontScale)
                                    Text(row.body, color = Muted, maxLines = 2, fontSize = 14.sp * chatFontScale)
                                    Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date)), color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                                }
                                val unread = unreadCountForAddress(unreadCounts, row.address)
                                if (unread > 0) {
                                    Box(Modifier.size(27.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
                                        Text(unread.coerceAtMost(99).toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (row.address in pinnedChats) Icon(Icons.Default.PushPin, contentDescription = if (isFa) "سنجاق‌شده" else "Pinned", tint = Cyan, modifier = Modifier.size(18.dp))
                                if (!row.address.startsWith("group:")) IconButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(row.address)}"))) } }) { Icon(Icons.Default.Call, contentDescription = if (isFa) "تماس" else "Call", tint = Cyan) }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun internalGroupPrefs() = getSharedPreferences("sms_guardian_internal_groups", MODE_PRIVATE)
    private fun readInternalGroups(): List<InternalSmsGroup> = runCatching {
        val array = JSONArray(internalGroupPrefs().getString("groups", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { obj ->
            val members = obj.optJSONArray("members") ?: JSONArray()
            InternalSmsGroup(obj.optString("id"), obj.optString("name"), (0 until members.length()).map { members.optString(it) }.filter { it.isNotBlank() }, obj.optLong("createdAt"))
        } }
    }.getOrDefault(emptyList())
    private fun saveInternalGroups(groups: List<InternalSmsGroup>) {
        val array = JSONArray(); groups.forEach { g -> val members = JSONArray(); g.members.forEach { members.put(it) }; array.put(JSONObject().put("id", g.id).put("name", g.name).put("members", members).put("createdAt", g.createdAt)) }
        internalGroupPrefs().edit().putString("groups", array.toString()).apply()
    }
    private fun createInternalGroup(name: String, members: List<String>): InternalSmsGroup {
        val group = InternalSmsGroup(java.util.UUID.randomUUID().toString(), name, members.distinct(), System.currentTimeMillis())
        saveInternalGroups(readInternalGroups() + group); return group
    }
    private fun updateInternalGroup(id: String, name: String, members: List<String>) { saveInternalGroups(readInternalGroups().map { if (it.id == id) it.copy(name = name, members = members.distinct()) else it }) }
    private fun deleteInternalGroup(id: String) {
        if (id.isBlank()) return
        saveInternalGroups(readInternalGroups().filterNot { it.id == id })
        internalGroupPrefs().edit().remove("messages_$id").apply()
        val organization = getSharedPreferences("sms_guardian_chat_organization", MODE_PRIVATE)
        val key = "group:$id"
        val editor = organization.edit().remove("folder_$key")
        val pinned = organization.getStringSet("pinned_chats", emptySet())?.toMutableSet() ?: mutableSetOf()
        pinned.remove(key)
        editor.putStringSet("pinned_chats", pinned).apply()
    }
    private fun appendInternalGroupMessage(id: String, body: String, date: Long) {
        val prefs = internalGroupPrefs(); val array = runCatching { JSONArray(prefs.getString("messages_$id", "[]") ?: "[]") }.getOrDefault(JSONArray())
        array.put(JSONObject().put("body", body).put("date", date)); prefs.edit().putString("messages_$id", array.toString()).apply()
    }
    private fun groupThreadRows(id: String): List<SmsRow> {
        val group = readInternalGroups().firstOrNull { it.id == id } ?: return emptyList()
        val local = runCatching { JSONArray(internalGroupPrefs().getString("messages_$id", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val rows = (0 until local.length()).mapNotNull { i -> local.optJSONObject(i)?.let { SmsRow("group:$id", it.optString("body"), it.optLong("date"), Telephony.Sms.MESSAGE_TYPE_SENT) } }.toMutableList()
        val incoming = querySmsRows().filter { it.address in group.members && it.date >= group.createdAt && it.type != Telephony.Sms.MESSAGE_TYPE_SENT && it.type != Telephony.Sms.MESSAGE_TYPE_OUTBOX }
        rows.addAll(incoming); return rows.sortedBy { it.date }
    }
    private fun internalGroupPreviewRows(): List<SmsRow> = readInternalGroups().mapNotNull { group ->
        val last = groupThreadRows(group.id).maxByOrNull { it.date } ?: return@mapNotNull null
        last.copy(address = "group:${group.id}")
    }

    private fun showConversationActions(number: String, isFa: Boolean, changed: () -> Unit) {
        val prefs = getSharedPreferences("sms_guardian_chat_organization", MODE_PRIVATE)
        val pinned = prefs.getStringSet("pinned_chats", emptySet())?.contains(number) == true
        val folders = prefs.getString("chat_folders", "").orEmpty().split("|").filter { it.isNotBlank() }
        val options = arrayOf(
            if (pinned) (if (isFa) "برداشتن سنجاق" else "Unpin conversation") else (if (isFa) "سنجاق‌کردن بالای فهرست" else "Pin conversation"),
            if (isFa) "ساخت میانبر روی صفحه اصلی" else "Add home-screen shortcut",
            if (isFa) "انتقال به صفحه..." else "Move to page...",
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
                    0 -> {
                        val set = prefs.getStringSet("pinned_chats", emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (pinned) set.remove(number) else set.add(number)
                        prefs.edit().putStringSet("pinned_chats", set).apply()
                        changed()
                    }
                    1 -> createConversationShortcut(number, isFa)
                    2 -> if (folders.isEmpty()) android.widget.Toast.makeText(this, if (isFa) "ابتدا با دکمه + یک صفحه بسازید." else "Create a page with + first.", android.widget.Toast.LENGTH_SHORT).show()
                        else android.app.AlertDialog.Builder(this).setTitle(if (isFa) "انتقال گفتگو به صفحه" else "Move conversation to page")
                            .setItems(folders.toTypedArray()) { _, index -> prefs.edit().putString("folder_$number", folders[index]).apply(); changed() }.show()
                    3 -> confirmDeleteConversation(number, isFa, changed)
                    4 -> { RuleStore.addBlocked(this, number); changed() }
                    5 -> {
                        val prefix = number.filter { it.isDigit() || it == '+' }.take(5)
                        if (prefix.isNotBlank()) RuleStore.addPrefix(this, prefix)
                        changed()
                    }
                    6 -> { RuleStore.addBlocked(this, number); confirmDeleteConversation(number, isFa, changed) }
                }
                dialog.dismiss()
            }.show()
    }

    private fun createConversationShortcut(number: String, isFa: Boolean) {
        val id = "sms_chat_" + number.filter { it.isLetterOrDigit() || it == '+' }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("smsguardian://conversation?number=${Uri.encode(number)}")
            putExtra("open_chat_number", number)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (manager?.isRequestPinShortcutSupported == true) {
                val display = lookupContactName(number)?.takeIf { it.isNotBlank() } ?: number
                val mark = if (lookupContactName(number).isNullOrBlank()) number.filter { it.isDigit() }.takeLast(3).ifBlank { number.takeLast(2) } else initialsFor(display).take(3)
                val bitmap = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.shader = android.graphics.LinearGradient(0f, 0f, 144f, 144f, intArrayOf(0xFF12C9C3.toInt(), 0xFF356BCE.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(72f, 72f, 72f, paint)
                paint.shader = null; paint.color = android.graphics.Color.WHITE; paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); paint.textSize = if (mark.length > 2) 48f else 58f
                val baseline = 72f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(mark, 72f, baseline, paint)
                val shortcut = android.content.pm.ShortcutInfo.Builder(this, id)
                    .setShortLabel(display.take(25))
                    .setLongLabel(if (isFa) "گفتگو با $display" else "Chat with $display")
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                    .setIntent(intent).build()
                manager.requestPinShortcut(shortcut, null)
                return
            }
        }
        android.widget.Toast.makeText(this, if (isFa) "این نسخه اندروید از میانبر سنجاق‌شده پشتیبانی نمی‌کند." else "Pinned shortcuts require Android 8 or newer.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun confirmDeleteConversations(numbers: List<String>, isFa: Boolean, changed: () -> Unit) {
        val uniqueNumbers = numbers.distinct().filter { it.isNotBlank() }
        if (uniqueNumbers.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isFa) "حذف ${uniqueNumbers.size} گفتگو؟" else "Delete ${uniqueNumbers.size} conversations?")
            .setMessage(if (uniqueNumbers.all { it.startsWith("group:") }) {
                if (isFa) "گروه‌های انتخاب‌شده و پیام‌های داخلی آن‌ها حذف می‌شوند؛ پیامک‌های اصلی اعضا باقی می‌مانند." else "The selected group conversations and their internal messages will be removed. Original member SMS messages will remain."
            } else if (uniqueNumbers.any { it.startsWith("group:") }) {
                if (isFa) "گفتگوهای عادی و پیامک‌های آن‌ها حذف و بایگانی می‌شوند؛ گروه‌های انتخاب‌شده حذف می‌شوند، اما پیامک‌های اصلی اعضای گروه باقی می‌مانند." else "Regular conversations will be deleted and archived. Selected groups will be removed, while original member SMS messages remain."
            } else {
                if (isFa) "تمام پیامک‌های گفتگوهای انتخاب‌شده از صندوق پیامک حذف می‌شوند و نسخه‌ای در بخش حذف‌شده‌ها نگهداری می‌شود." else "All SMS messages in the selected conversations will be deleted from the SMS provider and archived in Deleted messages."
            })
            .setNegativeButton(if (isFa) "انصراف" else "Cancel", null)
            .setPositiveButton(if (isFa) "حذف" else "Delete") { _, _ ->
                val groupIds = uniqueNumbers.filter { it.startsWith("group:") }.map { it.removePrefix("group:") }
                val smsNumbers = uniqueNumbers.filterNot { it.startsWith("group:") }
                val currentRows = cachedSmsRows ?: emptyList()
                val allRows = cachedAllSmsRows ?: currentRows
                val archiveRows = allRows.filter { it.address in smsNumbers }
                cachedSmsRows = currentRows.filterNot { it.address in uniqueNumbers }
                cachedAllSmsRows = cachedAllSmsRows?.filterNot { it.address in uniqueNumbers }
                cachedConversationPreviews = null
                changed()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val fullHistory = smsNumbers.flatMap { number -> querySmsRows(address = number) }
                            archiveDeletedMessages(if (fullHistory.isNotEmpty()) fullHistory else archiveRows)
                            smsNumbers.forEach { number ->
                                contentResolver.delete(Telephony.Sms.CONTENT_URI, "address = ?", arrayOf(number))
                            }
                            groupIds.forEach { deleteInternalGroup(it) }
                        }
                    }
                    result.onSuccess {
                        cachedSmsRows = (cachedSmsRows ?: emptyList()).filterNot { it.address in uniqueNumbers }
                        cachedAllSmsRows = cachedAllSmsRows?.filterNot { it.address in uniqueNumbers }
                        cachedConversationPreviews = null
                        changed()
                        android.widget.Toast.makeText(this@MainActivity, if (isFa) "${uniqueNumbers.size} گفتگو حذف شد" else "${uniqueNumbers.size} conversations deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        cachedSmsRows = null
                        cachedAllSmsRows = null
                        cachedConversationPreviews = null
                        changed()
                        android.widget.Toast.makeText(this@MainActivity, if (isFa) "حذف برخی گفتگوها ناموفق بود" else "Could not delete some conversations", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun confirmDeleteConversation(number: String, isFa: Boolean, changed: () -> Unit) {
        if (number.startsWith("group:")) {
            val groupId = number.removePrefix("group:")
            android.app.AlertDialog.Builder(this)
                .setTitle(if (isFa) "حذف گفتگوی گروهی؟" else "Delete group conversation?")
                .setMessage(if (isFa) "این گروه و پیام‌های داخلی آن از فهرست گفتگوها حذف می‌شود. پیامک‌های اصلی اعضا در صندوق پیامک باقی می‌مانند." else "This group and its internal messages will be removed. Original SMS messages belonging to its members will remain in the inbox.")
                .setNegativeButton(if (isFa) "انصراف" else "Cancel", null)
                .setPositiveButton(if (isFa) "حذف گروه" else "Delete group") { _, _ ->
                    deleteInternalGroup(groupId)
                    cachedSmsRows = cachedSmsRows?.filterNot { it.address == number }
                    cachedAllSmsRows = cachedAllSmsRows?.filterNot { it.address == number }
                    cachedConversationPreviews = null
                    changed()
                    android.widget.Toast.makeText(this, if (isFa) "گفتگوی گروهی حذف شد" else "Group conversation deleted", android.widget.Toast.LENGTH_SHORT).show()
                }.show()
            return
        }
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
        val messages = remember(refresh) {
            val deleted = readDeletedMessages().map { it to false }
            val blocked = RuleStore.archivedBlocked(this@MainActivity).let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(SmsRow(item.optString("address"), item.optString("body"), item.optLong("date"), item.optInt("type", Telephony.Sms.MESSAGE_TYPE_INBOX)) to true)
                    }
                }
            }
            (deleted + blocked).sortedByDescending { it.first.date }
        }
        var selected by remember(refresh) { mutableStateOf(setOf<String>()) }
        fun keyOf(entry: Pair<SmsRow, Boolean>) = "${entry.second}|${entry.first.address}|${entry.first.date}|${entry.first.body.hashCode()}"
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isFa) "پیامک‌های حذف‌شده و بلاک‌شده" else "Deleted & blocked messages", color = TextWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (selected.isNotEmpty()) TextButton(onClick = {
                    messages.filter { keyOf(it) in selected }.forEach { entry ->
                        val row = entry.first
                        if (entry.second) RuleStore.removeArchivedBlocked(this@MainActivity, row.address, row.body, row.date)
                        else removeArchivedMessage(row)
                    }
                    selected = emptySet()
                    changed()
                }) { Icon(Icons.Default.Delete, null); Text(if (isFa) "حذف (${selected.size})" else "Delete (${selected.size})") }
                TextButton(onClick = { selected = if (selected.size == messages.size) emptySet() else messages.map(::keyOf).toSet() }, enabled = messages.isNotEmpty()) {
                    Text(if (selected.size == messages.size) (if (isFa) "لغو انتخاب" else "Clear") else (if (isFa) "انتخاب همه" else "Select all"))
                }
            }
            Text(if (isFa) "پیامک‌های حذف‌شده و مسدودشده را می‌توانی یکجا انتخاب و حذف کنی." else "Select and remove deleted or blocked messages in bulk.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (isFa) "آرشیو پیامک‌های حذف‌شده خالی است." else "The deleted-message archive is empty.", color = Muted) }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { keyOf(it) }) { entry ->
                    val row = entry.first
                    val isBlocked = entry.second
                    val key = keyOf(entry)
                    Card(colors = CardDefaults.cardColors(containerColor = if (key in selected) Cyan.copy(alpha = .16f) else Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { selected = if (key in selected) selected - key else selected + key }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = key in selected, onCheckedChange = { checked -> selected = if (checked) selected + key else selected - key })
                                Text("${if (isBlocked) { if (isFa) "مسدودشده" else "Blocked" } else { if (isFa) "حذف‌شده" else "Deleted" }} · ${lookupContactName(row.address) ?: row.address} · ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date))}", color = Cyan, fontWeight = FontWeight.SemiBold)
                            }
                            Text(row.body, color = TextWhite)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!isBlocked) TextButton(onClick = {
                                    val ok = restoreDeletedMessage(row)
                                    android.widget.Toast.makeText(this@MainActivity, if (ok) { if (isFa) "پیامک بازیابی شد" else "Message restored" } else { if (isFa) "بازیابی ناموفق بود؛ برنامه باید پیام‌رسان پیش‌فرض باشد." else "Restore failed; the app must be the default SMS app." }, android.widget.Toast.LENGTH_LONG).show()
                                    changed()
                                }) { Text(if (isFa) "بازیابی" else "Restore") }
                                TextButton(onClick = {
                                    if (isBlocked) RuleStore.removeArchivedBlocked(this@MainActivity, row.address, row.body, row.date) else removeArchivedMessage(row)
                                    selected = selected - key
                                    changed()
                                }) { Text(if (isFa) "حذف از آرشیو" else "Remove from archive") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun conversationLastVisitKey(address: String): String =
        "conversation_last_visit:$address"

    private fun unreadSmsCounts(): Map<String, Int> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val counts = mutableMapOf<String, Int>()
        val preferences = getPreferences(MODE_PRIVATE)
        runCatching {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.READ),
                "${Telephony.Sms.TYPE}=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                null
            )?.use { cursor ->
                val addressColumn = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateColumn = cursor.getColumnIndex(Telephony.Sms.DATE)
                val readColumn = cursor.getColumnIndex(Telephony.Sms.READ)
                while (cursor.moveToNext()) {
                    val address = if (addressColumn >= 0) cursor.getString(addressColumn).orEmpty() else ""
                    val date = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                    val lastVisit = preferences.getLong(conversationLastVisitKey(address), -1L)
                    val isUnreadInProvider = readColumn >= 0 && cursor.getInt(readColumn) == 0
                    val isNewSinceVisit = lastVisit >= 0L && date > lastVisit
                    if (address.isBlank() || !(if (lastVisit >= 0L) isNewSinceVisit else isUnreadInProvider)) continue
                    counts[address] = (counts[address] ?: 0) + 1
                }
            }
        }.onFailure { android.util.Log.e("SMSGuardian", "Could not calculate per-conversation unread counts", it) }
        return counts
    }

    private fun unreadCountForAddress(counts: Map<String, Int>, address: String): Int =
        counts[address] ?: 0

    private fun markConversationRead(address: String) {
        if (address.isBlank()) return
        // This is an app-local read marker. It deliberately does not modify the Android
        // SMS Provider, so it works even when SMSGuardian is not the default SMS app.
        // Exact address matching keeps distinct provider addresses in separate chats.
        getPreferences(MODE_PRIVATE).edit()
            .putLong(conversationLastVisitKey(address), System.currentTimeMillis())
            .apply()
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
                    Text(if (isFa) "قانون Prefix وقتی هر بخشی از شماره فرستنده شامل الگوی واردشده باشد، پیامک را بلاک می‌کند؛ پیش‌شماره‌های +98 و 0098 با 0 یکسان‌سازی می‌شوند. با تطبیق کلمه، شماره فرستنده نیز خودکار مسدود می‌شود." else "A prefix rule blocks when the sender number contains the entered digits anywhere. +98 and 0098 are normalized to the local 0 format. Keyword matches also block the sender.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                    Text("مدیریت پیامک", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "مدیریت هوشمند پیامک‌ها، فیلتر و کنترل گفتگوها" else "Smart SMS management, filtering and conversation control", color = Color.White.copy(alpha = 0.88f))
                    Text("Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.1.1"}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    AssistChip(onClick = {}, colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(alpha = 0.14f), labelColor = Color.White, leadingIconContentColor = Color.White), label = { Text(if (isFa) "حریم خصوصی؛ اطلاعات روی دستگاه شما" else "Privacy-minded · on-device rules") }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "قابلیت‌ها" else "Features", color = Cyan, fontWeight = FontWeight.Bold)
                    listOf(
                        if (isFa) "مدیریت گفتگوها و ارسال پیامک؛ پشتیبانی از پیامک‌های طولانی (با توجه به محدودیت اپراتور)" else "Conversation management and SMS sending, including long SMS (subject to carrier limits)",
                        if (isFa) "بارگذاری سریع فهرست گفتگوها و به‌روزرسانی پیامک‌های ورودی و خروجی" else "Fast conversation loading and incoming/outgoing message refresh",
                        if (isFa) "جست‌وجو در گفتگوها و پیام‌های داخل هر گفتگو" else "Search conversations and messages within a chat",
                        if (isFa) "فوروارد دستی پیامک و فوروارد خودکار بر اساس شماره و عبارت" else "Manual SMS forwarding and automatic rule-based forwarding",
                        if (isFa) "فیلتر بر اساس کلمات، شماره کامل و سرشماره؛ مسدودسازی و حذف گفتگو" else "Keyword, full-number and prefix filtering; blocking and conversation deletion",
                        if (isFa) "نمایش پیامک‌های حذف‌شده و بازیابی آرشیو" else "Deleted-message archive and restoration",
                        if (isFa) "لینک‌های قابل کلیک، پیش‌نویس خودکار و وضعیت ارسال/تحویل با تیک" else "Clickable links, saved drafts, and sent/delivered checkmarks",
                        if (isFa) "انتخاب مخاطب از دفترچه تلفن، افزودن مخاطب و انتخاب سیم‌کارت" else "Contact picker, add-contact shortcut, and SIM selection",
                        if (isFa) "انتخاب رنگ، آیکون و زبان برنامه؛ آیکون مینیمال به‌صورت پیش‌فرض" else "Custom themes, launcher icons, and language; Minimal launcher icon by default",
                        if (isFa) "سنجاق‌کردن گفتگوها و ساخت میانبر برای دسترسی سریع از صفحه اصلی" else "Pin conversations and create home-screen shortcuts",
                        if (isFa) "ساخت صفحه‌های شخصی مانند خانواده، دوستان و همکاران برای دسته‌بندی گفتگوها" else "Organize chats into custom pages such as Family, Friends, and Work",
                        if (isFa) "ارسال یک پیامک به چند شماره و مدیریت مقصدهای فوروارد به‌صورت مستقل" else "Send an SMS to multiple recipients and independently enable forwarding destinations",
                        if (isFa) "انتخاب و حذف گروهی پیامک‌های آرشیوشده" else "Bulk selection and deletion of archived messages",
                        if (isFa) "با لمس هر قسمت حباب پیامک، کپی و فوروارد در دسترس است" else "Tap anywhere on a message bubble to access copy and forwarding actions",
                        if (isFa) "اعلان پیامک جدید و تنظیمات فوروارد" else "New-message notifications and forwarding settings"
                    ).forEach { item -> Row(verticalAlignment = Alignment.Top) { Icon(Icons.Default.Check, null, tint = Cyan, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(item, color = aboutCardText) } }
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
        var startTime by remember { mutableStateOf(prefs.getString("start_time", "00:00") ?: "00:00") }
        var endTime by remember { mutableStateOf(prefs.getString("end_time", "23:59") ?: "23:59") }
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
                    if (on && !isValidClockTime(startTime)) { android.widget.Toast.makeText(this@MainActivity, if (isFa) "ساعت شروع باید به شکل HH:mm باشد." else "Start time must use HH:mm format.", android.widget.Toast.LENGTH_LONG).show() }
                    else if (on && !isValidClockTime(endTime)) { android.widget.Toast.makeText(this@MainActivity, if (isFa) "ساعت پایان باید به شکل HH:mm باشد." else "End time must use HH:mm format.", android.widget.Toast.LENGTH_LONG).show() }
                    else if (on && !dests) { android.widget.Toast.makeText(this@MainActivity, if (isFa) "حداقل یک شماره مقصد وارد کنید و ذخیره کنید." else "Enter and save at least one destination first.", android.widget.Toast.LENGTH_LONG).show() }
                    else {
                        enabled = on
                        prefs.edit().putString("source", source.trim()).putString("keyword", keyword.trim())
                            .putString("destination1", d1.trim()).putString("destination2", d2.trim()).putString("destination3", d3.trim())
                            .putString("start_time", startTime.trim()).putString("end_time", endTime.trim())
                            .putBoolean("enabled", on).apply()
                        if (on) SmsForwarding.showStatus(this@MainActivity) else SmsForwarding.hideStatus(this@MainActivity)
                    }
                })
            }
            Text(if (isFa) "بازه فعال بودن فوروارد (ساعت ۲۴ ساعته)" else "Forwarding active window (24-hour clock)", color = TextWhite, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startTime, onValueChange = { startTime = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(if (isFa) "از ساعت" else "From") }, placeholder = { Text("08:00") })
                OutlinedTextField(value = endTime, onValueChange = { endTime = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(if (isFa) "تا ساعت" else "Until") }, placeholder = { Text("22:00") })
            }
            Text(if (isFa) "خارج از این بازه پیامک فوروارد نمی‌شود. برای بازه عبوری از نیمه‌شب، مثلاً 22:00 تا 06:00 وارد کنید." else "SMS will not be forwarded outside this window. Overnight windows such as 22:00–06:00 are supported.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
            var destination1Enabled by remember { mutableStateOf(prefs.getBoolean("destination1_enabled", true)) }
            var destination2Enabled by remember { mutableStateOf(prefs.getBoolean("destination2_enabled", true)) }
            var destination3Enabled by remember { mutableStateOf(prefs.getBoolean("destination3_enabled", true)) }
            val destinationEnabled = listOf(destination1Enabled, destination2Enabled, destination3Enabled)
            listOf(d1, d2, d3).forEachIndexed { index, number ->
                if (number.isNotBlank()) Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isFa) "فعال‌بودن مقصد ${index + 1} · $number" else "Destination ${index + 1} · $number", color = TextWhite, modifier = Modifier.weight(1f))
                    Switch(checked = destinationEnabled[index], onCheckedChange = { value ->
                        when (index) { 0 -> destination1Enabled = value; 1 -> destination2Enabled = value; else -> destination3Enabled = value }
                        prefs.edit().putBoolean("destination${index + 1}_enabled", value).apply()
                    })
                }
            }
            Button(onClick = {
                if (listOf(d1,d2,d3).none { it.isNotBlank() }) {
                    android.widget.Toast.makeText(this@MainActivity, if (isFa) "حداقل یک شماره مقصد لازم است." else "At least one destination is required.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    if (!isValidClockTime(startTime) || !isValidClockTime(endTime)) {
                        android.widget.Toast.makeText(this@MainActivity, if (isFa) "ساعت شروع و پایان باید به شکل HH:mm و در قالب ۲۴ ساعته باشند." else "Enter valid 24-hour times in HH:mm format.", android.widget.Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    prefs.edit().putString("source", source.trim()).putString("keyword", keyword.trim()).putString("destination1", d1.trim()).putString("destination2", d2.trim()).putString("destination3", d3.trim()).putString("start_time", startTime.trim()).putString("end_time", endTime.trim()).putBoolean("enabled", enabled).apply()
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
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "رنگ زمینه برنامه" else "App background color", color = TextWhite, fontWeight = FontWeight.Bold)
                    listOf(
                        0 to (if (isFa) "آبی نیمه‌شب" else "Midnight blue"),
                        1 to (if (isFa) "بنفش" else "Purple"),
                        2 to (if (isFa) "سبز جنگلی" else "Forest green"),
                        3 to (if (isFa) "سفید" else "White"),
                        4 to (if (isFa) "مشکی نیمه‌شب" else "Midnight Black"),
                        5 to (if (isFa) "صورتی" else "Pink"),
                        6 to (if (isFa) "گیلاسی" else "Cherry"),
                        7 to (if (isFa) "اقیانوسی عمیق" else "Deep Ocean"),
                        8 to (if (isFa) "کهربایی" else "Amber"),
                        9 to (if (isFa) "یشمی" else "Jade")
                    ).forEach { (choice, label) ->
                        Row(Modifier.fillMaxWidth().clickable { changeBackground(choice) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = bgChoice == choice, onClick = { changeBackground(choice) })
                            Text(label, color = TextWhite)
                        }
                    }
                }
            }
            val smsPrefs = getSharedPreferences("sms_guardian_settings", MODE_PRIVATE)
            var autoCopyNumbers by remember { mutableStateOf(smsPrefs.getBoolean("auto_copy_sms_number", false)) }
            var badgeColorSetting by remember { mutableIntStateOf(smsPrefs.getInt("unread_badge_color", 0).coerceIn(0, 3)) }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "کپی خودکار عددهای داخل پیامک" else "Automatic number copying", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "با فعال‌سازی، اولین رشته عددی موجود در هر پیامک دریافتیِ غیرمسدودشده به حافظه موقت (Clipboard) کپی می‌شود." else "When enabled, the first number-like sequence in each accepted incoming SMS is copied to the clipboard.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isFa) "فعال‌سازی کپی خودکار" else "Enable automatic copying", color = TextWhite, modifier = Modifier.weight(1f))
                        Switch(checked = autoCopyNumbers, onCheckedChange = {
                            autoCopyNumbers = it
                            smsPrefs.edit().putBoolean("auto_copy_sms_number", it).apply()
                        })
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "رنگ نشان پیامک‌های خوانده‌نشده" else "Unread-message badge color", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "رنگ دایره و عدد سفید برای خوانایی بهتر انتخاب شده است." else "The badge uses a contrasting white number for readability.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    listOf(Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF1565C0), Color(0xFF00897B)).forEachIndexed { index, color ->
                        Row(Modifier.fillMaxWidth().clickable {
                            badgeColorSetting = index
                            smsPrefs.edit().putInt("unread_badge_color", index).apply()
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = badgeColorSetting == index, onClick = {
                                badgeColorSetting = index
                                smsPrefs.edit().putInt("unread_badge_color", index).apply()
                            })
                            Box(Modifier.size(28.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                                Text("3", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(listOf(if (isFa) "نارنجی" else "Orange", if (isFa) "قرمز" else "Red", if (isFa) "آبی" else "Blue", if (isFa) "سبزآبی" else "Teal")[index], color = TextWhite)
                        }
                    }
                }
            }
            var smsDirectionSetting by remember { mutableIntStateOf(smsPrefs.getInt("sms_text_direction", 0).coerceIn(0, 2)) }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "جهت نمایش متن پیامک‌ها" else "SMS message text direction", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "جهت متن داخل حباب پیامک را مستقل از زبان منو انتخاب کن." else "Choose the direction of message text independently from the app language.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    listOf(
                        0 to (if (isFa) "خودکار (بر اساس متن پیام)" else "Automatic (based on message text)"),
                        1 to (if (isFa) "چپ به راست (LTR)" else "Left to right (LTR)"),
                        2 to (if (isFa) "راست به چپ (RTL)" else "Right to left (RTL)")
                    ).forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            smsDirectionSetting = value
                            smsPrefs.edit().putInt("sms_text_direction", value).apply()
                        }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = smsDirectionSetting == value, onClick = {
                                smsDirectionSetting = value
                                smsPrefs.edit().putInt("sms_text_direction", value).apply()
                            })
                            Text(label, color = TextWhite)
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "آیکون برنامه" else "App icon", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "آیکون انتخاب‌شده ذخیره می‌شود و پس از باز کردن دوبارهٔ برنامه نباید به مینیمال برگردد. ممکن است لانچر برای به‌روزرسانی کمی زمان نیاز داشته باشد." else "Your selected launcher icon is saved and should persist after reopening the app. The launcher may take a moment to refresh.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
