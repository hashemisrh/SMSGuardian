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
    fun addPrefix(context: Context, value: String) = add(context, KEY_PREFIXES, value.trim().removeSuffix("*"))
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
    fun normalizeNumber(value: String): String = value.filter { it.isDigit() || it == '+' }.let {
        when { it.startsWith("+98") -> "0" + it.drop(3); it.startsWith("0098") -> "0" + it.drop(4); else -> it }
    }
    fun isBlocked(context: Context, sender: String, body: String): Boolean {
        val normalizedSender = normalizeNumber(sender)
        if (blocked(context).any { normalizeNumber(it) == normalizedSender }) return true
        if (numbers(context).any { normalizeNumber(it) == normalizedSender }) return true
        if (prefixes(context).any { normalizedSender.startsWith(it) }) return true
        val lowerBody = body.lowercase()
        val matchedKeyword = words(context).any { it.isNotBlank() && lowerBody.contains(it.lowercase()) }
        if (matchedKeyword) addBlocked(context, sender)
        return matchedKeyword
    }
}
