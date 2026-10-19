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
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF081326)
private val Panel = Color(0xFF111F35)
private val Cyan = Color(0xFF12C9C3)
private val TextWhite = Color(0xFFF1F6FF)
private val Muted = Color(0xFF9AAAC3)

data class SmsRow(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : ComponentActivity() {
    private var smsRoleRefreshSignal by mutableIntStateOf(0)

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
            parts.indices.forEach { index ->
                val sentIntent = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "com.example.smsguardian.SMS_SENT"
                    putExtra("part_index", index)
                    putExtra("part_count", parts.size)
                }
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this, (System.currentTimeMillis().toInt() + index), sentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            if (parts.size > 1) manager.sendMultipartTextMessage(destination.trim(), null, parts, sentIntents, null)
            else manager.sendTextMessage(destination.trim(), null, message, sentIntents.first(), null)

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

    fun getSmsRows(): List<SmsRow> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = mutableListOf<SmsRow>()
        val cursor: Cursor? = contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE), null, null, "date DESC")
        cursor?.use { while (it.moveToNext()) rows.add(SmsRow(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getLong(2), it.getInt(3))) }
        return rows
    }

    @Composable private fun GuardianApp() {
        var language by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getString("language", "fa") ?: "fa") }
        var page by remember { mutableStateOf("chats") }
        var bgChoice by remember { mutableIntStateOf(getPreferences(MODE_PRIVATE).getInt("bg_choice", 0)) }
        val appBg = when (bgChoice) { 1 -> Color(0xFF24152F); 2 -> Color(0xFF102A24); else -> Navy }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(smsRoleRefreshSignal) { refresh++ }
        val isFa = language == "fa"
        val direction = if (isFa) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = appBg, surface = Panel, onBackground = TextWhite, onSurface = TextWhite)) {
                Surface(Modifier.fillMaxSize(), color = appBg) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().background(Brush.verticalGradient(listOf(appBg.copy(red = (appBg.red + 0.06f).coerceAtMost(1f)), appBg)))) {
                        Header(isFa, page, { language = if (language == "fa") "en" else "fa"; getPreferences(MODE_PRIVATE).edit().putString("language", language).apply() }, { page = it })
                        when (page) {
                            "rules" -> RulesPage(isFa, refresh) { refresh++ }
                            "settings" -> SettingsPage(isFa, language, { language = it; getPreferences(MODE_PRIVATE).edit().putString("language", it).apply() }, { requestDefaultSmsRole() }, bgChoice) { choice -> bgChoice = choice; getPreferences(MODE_PRIVATE).edit().putInt("bg_choice", choice).apply() }
                            "about" -> AboutPage(isFa)
                            "deleted" -> DeletedMessagesPage(isFa, refresh) { refresh++ }
                            else -> ChatsPage(isFa, refresh, ::getSmsRows, ::sendSms)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.Menu, contentDescription = if (isFa) "منو" else "Menu", tint = Cyan) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    listOf("chats" to (if (isFa) "گفتگوها" else "Chats"), "rules" to (if (isFa) "قوانین بلاک" else "Blocking rules"), "settings" to (if (isFa) "تنظیمات" else "Settings"), "about" to (if (isFa) "درباره ما" else "About")).forEach { (target, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuExpanded = false; navigate(target) })
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SMS Guardian", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextWhite)
                Text(if (page == "chats") { if (isFa) "پیام‌رسان امن شما" else "Your secure inbox" } else if (page == "rules") { if (isFa) "مدیریت قوانین هوشمند" else "Smart filtering rules" } else { if (isFa) "شخصی‌سازی برنامه" else "Personalize your app" }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Language, null, tint = Cyan, modifier = Modifier.clickable(onClick = toggleLanguage).padding(8.dp))
        }
    }

    @Composable private fun ChatsPage(isFa: Boolean, refresh: Int, loadRows: () -> List<SmsRow>, send: (String, String, Int?) -> Unit) {
        var rows by remember { mutableStateOf(emptyList<SmsRow>()) }
        var destination by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var threadMessage by remember { mutableStateOf("") }
        var selectedNumber by remember { mutableStateOf<String?>(null) }
        var subId by remember { mutableStateOf<Int?>(null) }
        var sims by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
        var localRefresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh, localRefresh) {
            rows = loadRows()
            sims = if (Build.VERSION.SDK_INT >= 22 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) runCatching {
                SubscriptionManager.from(this@MainActivity).activeSubscriptionInfoList.orEmpty().mapIndexed { i, sub ->
                    sub.subscriptionId to (sub.displayName?.toString()?.ifBlank { null } ?: if (isFa) "سیم‌کارت ${i + 1}" else "SIM ${i + 1}")
                }
            }.getOrDefault(emptyList()) else emptyList()
            if (subId == null) subId = sims.firstOrNull()?.first
        }
        val currentNumber = selectedNumber
        if (currentNumber != null) {
            val thread = rows.filter { it.address.equals(currentNumber, ignoreCase = true) }.sortedBy { it.date }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedNumber = null; threadMessage = "" }) { Icon(Icons.Default.ArrowBack, contentDescription = if (isFa) "بازگشت" else "Back", tint = Cyan) }
                    Column(Modifier.weight(1f)) {
                        Text(lookupContactName(currentNumber) ?: currentNumber, color = TextWhite, fontWeight = FontWeight.Bold)
                        Text(if (isFa) "گفتگو" else "Conversation", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(currentNumber)}"))) } }) {
                        Icon(Icons.Default.Call, contentDescription = if (isFa) "تماس" else "Call", tint = Cyan)
                    }
                }
                val threadListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(currentNumber, thread.size) {
                    if (thread.isNotEmpty()) threadListState.scrollToItem(thread.lastIndex)
                }
                LazyColumn(state = threadListState, modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(thread, key = { "${it.address}-${it.date}-${it.type}" }) { row ->
                        val outgoing = row.type == Telephony.Sms.MESSAGE_TYPE_SENT || row.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                        var messageMenu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                            Column(Modifier.widthIn(max = 300.dp).background(if (outgoing) Color(0xFF124B59) else Panel, RoundedCornerShape(16.dp)).combinedClickable(onClick = {}, onLongClick = { messageMenu = true }).padding(12.dp)) {
                                Text(row.body, color = TextWhite)
                                Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date)), color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (messageMenu) AlertDialog(onDismissRequest = { messageMenu = false }, title = { Text(if (isFa) "عملیات پیامک" else "Message actions") }, text = { Text(if (isFa) "متن پیام را کپی کنید یا برای ویرایش در کادر نوشتن قرار دهید." else "Copy the message or load its text into the composer to edit.") }, confirmButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SMS", row.body)); messageMenu = false; android.widget.Toast.makeText(this@MainActivity, if (isFa) "متن کپی شد" else "Copied", android.widget.Toast.LENGTH_SHORT).show() }) { Text(if (isFa) "کپی" else "Copy") } }, dismissButton = { Row { TextButton(onClick = { threadMessage = row.body; messageMenu = false }) { Text(if (isFa) "ویرایش متن" else "Edit text") }; TextButton(onClick = { messageMenu = false }) { Text(if (isFa) "بستن" else "Close") } } })
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = threadMessage, onValueChange = { threadMessage = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (isFa) "نوشتن پیامک..." else "Type a message...") }, maxLines = 4)
                    IconButton(enabled = threadMessage.isNotBlank(), onClick = {
                        send(currentNumber, threadMessage, subId)
                        threadMessage = ""
                        localRefresh++
                    }) { Icon(Icons.Default.Send, contentDescription = if (isFa) "ارسال" else "Send", tint = Cyan) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (isFa) "پیامک جدید" else "New message", color = TextWhite, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = destination, onValueChange = { destination = it }, modifier = Modifier.weight(1f), label = { Text(if (isFa) "شماره گیرنده" else "Recipient number") }, singleLine = true)
                            IconButton(onClick = {
                                contactCallback = { picked -> destination = picked }
                                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                            }) { Icon(Icons.Default.Contacts, contentDescription = if (isFa) "انتخاب از دفترچه تلفن" else "Choose contact", tint = Cyan) }
                        }
                        OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (isFa) "متن پیام" else "Message text") }, minLines = 2)
                        if (sims.isNotEmpty()) {
                            Text(if (isFa) "سیم‌کارت ارسال" else "Send using SIM", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { sims.forEach { (id, label) -> FilterChip(selected = subId == id, onClick = { subId = id }, label = { Text(label) }) } }
                        }
                        Button(onClick = { send(destination, message, subId); destination = ""; message = ""; localRefresh++ }, modifier = Modifier.fillMaxWidth(), enabled = destination.isNotBlank() && message.isNotBlank()) {
                            Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(if (isFa) "ارسال پیامک" else "Send SMS")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isFa) "گفتگوها" else "Conversations", color = TextWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); Text("${rows.map { it.address }.distinct().size}", color = Cyan)
                }
                Spacer(Modifier.height(8.dp))
                val conversations = rows.groupBy { it.address }.mapNotNull { (_, messages) -> messages.maxByOrNull { it.date } }.sortedByDescending { it.date }
                if (conversations.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (isFa) "پیامکی برای نمایش پیدا نشد. برای دریافت پیامک جدید، مجوزها و پیام‌رسان پیش‌فرض را بررسی کنید." else "No messages found. Check permissions and the default SMS role to receive new messages.", color = Muted)
                } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { "${it.address}-${it.date}" }) { row ->
                        val displayName = lookupContactName(row.address) ?: row.address
                        val initials = initialsFor(displayName)
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { selectedNumber = row.address }, onLongClick = { showConversationActions(row.address, isFa) { localRefresh++ } })) {
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
            if (isFa) "لغو" else "Cancel"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isFa) "مدیریت گفتگو: $number" else "Conversation: $number")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> android.app.AlertDialog.Builder(this).setTitle(if (isFa) "حذف گفتگو؟" else "Delete conversation?")
                        .setMessage(if (isFa) "تمام پیامک‌های این شماره از صندوق پیامک حذف می‌شوند." else "All SMS messages for this number will be deleted from the SMS provider.")
                        .setNegativeButton(if (isFa) "انصراف" else "Cancel", null)
                        .setPositiveButton(if (isFa) "حذف" else "Delete") { _, _ ->
                            runCatching {
                                val toArchive = getSmsRows().filter { it.address == number }
                                archiveDeletedMessages(toArchive)
                                contentResolver.delete(Telephony.Sms.CONTENT_URI, "address = ?", arrayOf(number))
                            }
                                .onSuccess { changed(); android.widget.Toast.makeText(this, if (isFa) "گفتگو حذف شد" else "Conversation deleted", android.widget.Toast.LENGTH_SHORT).show() }
                                .onFailure { android.widget.Toast.makeText(this, if (isFa) "حذف گفتگو ناموفق بود" else "Could not delete conversation", android.widget.Toast.LENGTH_LONG).show() }
                        }.show()
                    1 -> { RuleStore.addBlocked(this, number); changed(); android.widget.Toast.makeText(this, if (isFa) "شماره بلاک شد" else "Number blocked", android.widget.Toast.LENGTH_SHORT).show() }
                    2 -> {
                        val prefix = number.filter { it.isDigit() || it == '+' }.take(5)
                        if (prefix.isNotBlank()) RuleStore.addPrefix(this, prefix)
                        changed()
                        android.widget.Toast.makeText(this, if (isFa) "سرشماره $prefix به فهرست بلاک اضافه شد" else "Prefix $prefix blocked", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                dialog.dismiss()
            }.show()
    }

    private fun lookupContactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val candidates = linkedSetOf<String>()
        candidates += number.trim()
        val digits = number.filter { it.isDigit() }
        if (digits.isNotBlank()) {
            candidates += digits
            // Try common international/local formatting variants without assuming a country code.
            if (digits.startsWith("00") && digits.length > 2) candidates += digits.drop(2)
            if (digits.startsWith("0") && digits.length > 1) candidates += digits.drop(1)
            candidates += "+$digits"
        }
        return runCatching {
            for (candidate in candidates) {
                val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(candidate).build()
                val found = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
                }
                if (found != null) return@runCatching found
            }
            null
        }.getOrNull()
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
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF173C59), Panel))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Brush.linearGradient(listOf(Cyan, Color(0xFF356BCE))), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Chat, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
                    Text("SMS Guardian", color = TextWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (isFa) "مدیریت هوشمند پیامک‌ها، فیلتر و کنترل گفتگوها" else "Smart SMS management, filtering and conversation control", color = Muted)
                    AssistChip(onClick = {}, label = { Text(if (isFa) "حریم خصوصی؛ اطلاعات روی دستگاه شما" else "Privacy-minded · on-device rules") }, leadingIcon = { Icon(Icons.Default.Settings, null) })
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
                    ).forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.FilterAlt, null, tint = Cyan, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(item, color = TextWhite) } }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isFa) "توسعه‌دهنده" else "Developer", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("سیدرضا هاشمی · S. Reza Hashemi", color = TextWhite)
                    Text("hashemi.srh@gmail.com", color = Cyan, modifier = Modifier.clickable { runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hashemi.srh@gmail.com"))) } })
                    Text(if (isFa) "برای دریافت و فیلتر پیامک‌های ورودی، برنامه باید به‌عنوان پیام‌رسان پیش‌فرض انتخاب شده باشد و مجوز اعلان‌ها فعال باشد." else "To receive and filter incoming SMS, set this app as the default SMS app and allow notifications.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable private fun SettingsPage(isFa: Boolean, language: String, changeLanguage: (String) -> Unit, requestRole: () -> Unit, bgChoice: Int, changeBackground: (Int) -> Unit) {
        val isDefault = Telephony.Sms.getDefaultSmsPackage(this) == packageName
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isFa) "رنگ زمینه برنامه" else "App background color", color = TextWhite, fontWeight = FontWeight.Bold)
                    listOf((0 to (if (isFa) "آبی تیره" else "Midnight blue")), (1 to (if (isFa) "بنفش" else "Purple")), (2 to (if (isFa) "سبز جنگلی" else "Forest green"))).forEach { (choice, label) ->
                        Row(Modifier.fillMaxWidth().clickable { changeBackground(choice) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = bgChoice == choice, onClick = { changeBackground(choice) })
                            Text(label, color = TextWhite)
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
