package com.example.smsguardian

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
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
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askRuntimePermissions()
        setContent { GuardianApp() }
    }

    private fun askRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(permissions.toTypedArray())
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
        try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && subId != null && subId >= 0) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            manager.sendTextMessage(destination.trim(), null, message, null, null)
            // For a default SMS app, sent messages should be stored in the SMS provider.
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, destination.trim()); put(Telephony.Sms.BODY, message)
                    put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
                contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }
        } catch (e: Exception) { android.util.Log.e("SMSGuardian", "SMS send failed", e) }
    }

    fun getSmsRows(): List<SmsRow> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = mutableListOf<SmsRow>()
        val cursor: Cursor? = contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE), null, null, "date DESC")
        cursor?.use { while (it.moveToNext() && rows.size < 100) rows.add(SmsRow(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getLong(2), it.getInt(3))) }
        return rows
    }

    @Composable private fun GuardianApp() {
        var language by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getString("language", "fa") ?: "fa") }
        var page by remember { mutableStateOf("chats") }
        var refresh by remember { mutableIntStateOf(0) }
        val isFa = language == "fa"
        val direction = if (isFa) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel, onBackground = TextWhite, onSurface = TextWhite)) {
                Surface(Modifier.fillMaxSize(), color = Navy) {
                    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D2140), Navy)))) {
                        Header(isFa, page) { page = it }
                        when (page) {
                            "rules" -> RulesPage(isFa, refresh) { refresh++ }
                            "settings" -> SettingsPage(isFa, language, { language = it; getPreferences(MODE_PRIVATE).edit().putString("language", it).apply() }, { requestDefaultSmsRole() })
                            else -> ChatsPage(isFa, refresh, ::getSmsRows, ::sendSms)
                        }
                        NavigationBar(containerColor = Color(0xFF0A172A), contentColor = TextWhite) {
                            NavigationBarItem(selected = page == "chats", onClick = { page = "chats" }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text(if (isFa) "گفتگوها" else "Chats") })
                            NavigationBarItem(selected = page == "rules", onClick = { page = "rules" }, icon = { Icon(Icons.Default.FilterAlt, null) }, label = { Text(if (isFa) "فیلترها" else "Filters") })
                            NavigationBarItem(selected = page == "settings", onClick = { page = "settings" }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(if (isFa) "تنظیمات" else "Settings") })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Header(isFa: Boolean, page: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Cyan.copy(alpha = .15f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Chat, null, tint = Cyan) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SMS Guardian", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextWhite)
                Text(if (page == "chats") { if (isFa) "پیام‌رسان امن شما" else "Your secure inbox" } else if (page == "rules") { if (isFa) "مدیریت قوانین هوشمند" else "Smart filtering rules" } else { if (isFa) "شخصی‌سازی برنامه" else "Personalize your app" }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Language, null, tint = Cyan)
        }
    }

    @Composable private fun ChatsPage(isFa: Boolean, refresh: Int, loadRows: () -> List<SmsRow>, send: (String, String, Int?) -> Unit) {
        var rows by remember { mutableStateOf(emptyList<SmsRow>()) }
        var destination by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var subId by remember { mutableStateOf<Int?>(null) }
        var sims by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
        LaunchedEffect(refresh) { rows = loadRows(); sims = if (Build.VERSION.SDK_INT >= 22 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) runCatching { SubscriptionManager.from(this@MainActivity).activeSubscriptionInfoList.orEmpty().mapIndexed { i, s -> s.subscriptionId to (s.displayName?.toString()?.ifBlank { null } ?: if (isFa) "سیم‌کارت ${i + 1}" else "SIM ${i + 1}") } }.getOrDefault(emptyList()) else emptyList(); if (subId == null) subId = sims.firstOrNull()?.first }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFa) "پیامک جدید" else "New message", color = TextWhite, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(destination, { destination = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (isFa) "شماره گیرنده" else "Recipient number") }, singleLine = true)
                    OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (isFa) "متن پیام" else "Message text") }, minLines = 2)
                    if (sims.isNotEmpty()) {
                        Text(if (isFa) "سیم‌کارت ارسال" else "Send using SIM", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { sims.forEach { (id, label) -> FilterChip(selected = subId == id, onClick = { subId = id }, label = { Text(label) }) } }
                    }
                    Button(onClick = { send(destination, message, subId); destination = ""; message = "" }, modifier = Modifier.fillMaxWidth(), enabled = destination.isNotBlank() && message.isNotBlank()) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(if (isFa) "ارسال پیامک" else "Send SMS") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (isFa) "گفتگوهای اخیر" else "Recent messages", color = TextWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("${rows.size}", color = Cyan) }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(if (isFa) "هنوز پیامکی برای نمایش نیست؛ برای مدیریت کامل، برنامه را پیام‌رسان پیش‌فرض کنید." else "No messages to show yet. Set this app as the default SMS app for full handling.", color = Muted) }
            else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { "${it.address}-${it.date}-${it.type}" }) { row ->
                    val displayName = lookupContactName(row.address) ?: row.address
                    val initials = initialsFor(displayName)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Cyan, Color(0xFF356BCE))), CircleShape), contentAlignment = Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(displayName, color = TextWhite, fontWeight = FontWeight.SemiBold); Text(row.body, color = Muted, maxLines = 2); Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(row.date)), color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }

    private fun lookupContactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(Uri.encode(number)).build()
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts.first().first()}${parts.last().first()}".uppercase() else parts.firstOrNull()?.take(2)?.uppercase() ?: "?"
    }

    @Composable private fun RulesPage(isFa: Boolean, refresh: Int, changed: () -> Unit) {
        var kind by remember { mutableStateOf("word") }
        var value by remember { mutableStateOf("") }
        var autoBlock by remember { mutableStateOf(true) }
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

    @Composable private fun SettingsPage(isFa: Boolean, language: String, changeLanguage: (String) -> Unit, requestRole: () -> Unit) {
        val isDefault = Telephony.Sms.getDefaultSmsPackage(this) == packageName
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            Text(if (isFa) "نکته: عملکرد پس‌زمینه به سیاست‌های اندروید و سازنده گوشی بستگی دارد. این نسخه پیامک را مدیریت می‌کند؛ پردازش کامل MMS و نمایش نام مخاطب از دفترچه تلفن در نسخه بعدی قابل افزودن است." else "Note: background behavior depends on Android and device-vendor policies. This starter handles SMS; full MMS handling and contact-name lookup can be added in a later version.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
