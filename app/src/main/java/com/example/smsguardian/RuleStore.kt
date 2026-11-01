package com.example.smsguardian

import android.content.Context
import org.json.JSONArray

/** Small local rule store. Rules stay on-device. */
object RuleStore {
    private const val PREFS = "sms_guardian_rules"
    private const val KEY_WORDS = "words"
    private const val KEY_NUMBERS = "numbers"
    private const val KEY_PREFIXES = "prefixes"
    private const val KEY_BLOCKED = "blocked_senders"
    private const val KEY_BLOCKED_ARCHIVE = "blocked_sms_archive"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun read(context: Context, key: String): MutableList<String> {
        val array = runCatching { JSONArray(prefs(context).getString(key, "[]")) }.getOrNull() ?: JSONArray()
        return MutableList(array.length()) { array.optString(it) }.filter { it.isNotBlank() }.toMutableList()
    }
    private fun write(context: Context, key: String, values: List<String>) {
        val array = JSONArray()
        values.distinct().forEach { array.put(it) }
        prefs(context).edit().putString(key, array.toString()).apply()
    }
    fun words(context: Context) = read(context, KEY_WORDS)
    fun numbers(context: Context) = read(context, KEY_NUMBERS)
    fun prefixes(context: Context) = read(context, KEY_PREFIXES)
    fun blocked(context: Context) = read(context, KEY_BLOCKED)
    fun addWord(context: Context, value: String) = add(context, KEY_WORDS, value.trim())
    fun addNumber(context: Context, value: String) = add(context, KEY_NUMBERS, normalizeNumber(value))
    fun addPrefix(context: Context, value: String) = add(context, KEY_PREFIXES, normalizeDigits(value).filter { it.isDigit() })
    fun archiveBlocked(context: Context, sender: String, body: String, date: Long) {
        val p = prefs(context)
        val all = runCatching { JSONArray(p.getString(KEY_BLOCKED_ARCHIVE, "[]")) }.getOrDefault(JSONArray())
        val key = "$sender|$date|$body"
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if ("${item.optString("address")}|${item.optLong("date")}|${item.optString("body")}" == key) return
        }
        all.put(org.json.JSONObject().put("address", sender).put("body", body).put("date", date).put("type", 1))
        p.edit().putString(KEY_BLOCKED_ARCHIVE, all.toString()).apply()
    }
    fun archivedBlocked(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_BLOCKED_ARCHIVE, "[]")) }.getOrDefault(JSONArray())
    fun removeArchivedBlocked(context: Context, sender: String, body: String, date: Long) {
        val old = archivedBlocked(context); val fresh = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("address") == sender && item.optString("body") == body && item.optLong("date") == date) continue
            fresh.put(item)
        }
        prefs(context).edit().putString(KEY_BLOCKED_ARCHIVE, fresh.toString()).apply()
    }
    fun addBlocked(context: Context, value: String) = add(context, KEY_BLOCKED, normalizeNumber(value))
    private fun add(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        val items = read(context, key)
        if (value !in items) items.add(value)
        write(context, key, items)
    }
    fun remove(context: Context, kind: String, value: String) {
        val key = when (kind) { "word" -> KEY_WORDS; "number" -> KEY_NUMBERS; "prefix" -> KEY_PREFIXES; else -> KEY_BLOCKED }
        write(context, key, read(context, key).filterNot { it == value })
    }
    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { ch ->
            val digit = ch.digitToIntOrNull()
            if (digit != null) append(digit) else if (ch == '+') append(ch)
        }
    }
    fun normalizeNumber(value: String): String {
        val digits = normalizeDigits(value)
        return when {
            digits.startsWith("+98") -> "0" + digits.drop(3)
            digits.startsWith("0098") -> "0" + digits.drop(4)
            digits.startsWith("98") && digits.length >= 11 -> "0" + digits.drop(2)
            else -> digits
        }
    }
    fun isBlocked(context: Context, sender: String, body: String): Boolean {
        val normalizedSender = normalizeNumber(sender)
        if (blocked(context).any { normalizeNumber(it) == normalizedSender }) return true
        if (numbers(context).any { normalizeNumber(it) == normalizedSender }) return true
        if (prefixes(context).any { it.isNotBlank() && normalizedSender.contains(normalizeDigits(it).filter { ch -> ch.isDigit() }) }) return true
        val lowerBody = body.lowercase()
        val matchedKeyword = words(context).any { it.isNotBlank() && lowerBody.contains(it.lowercase()) }
        if (matchedKeyword) addBlocked(context, sender)
        return matchedKeyword
    }
}
