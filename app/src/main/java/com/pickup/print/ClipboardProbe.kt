package com.pickup.print

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences

/** 读取系统剪切板并提取可能的取件码。 */
object ClipboardProbe {

    data class Snapshot(
        val rawText: String,
        val code: String?,
        val candidates: List<String>,
        val fingerprint: String
    )

    fun read(context: Context): Snapshot? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = PickupCodeParser.parse(text)
        // 剪切板多为短文本：再补抽一串纯数字候选
        val digits = DIGITS.findAll(text).map { it.value }.filter { it.length in 4..8 }.toList()
        val candidates = (parsed.candidates + digits).distinct()
        val code = parsed.code ?: candidates.firstOrNull()
        if (code.isNullOrBlank() && text.length > 80) {
            // 太长且抽不出码，不打扰用户
            return null
        }
        if (code.isNullOrBlank() && !text.any { it.isDigit() }) return null
        val fp = "${text.hashCode()}|$code"
        return Snapshot(rawText = text, code = code, candidates = candidates, fingerprint = fp)
    }

    fun wasHandled(prefs: SharedPreferences, fingerprint: String): Boolean =
        prefs.getString(KEY_LAST_FP, null) == fingerprint

    fun markHandled(prefs: SharedPreferences, fingerprint: String) {
        prefs.edit().putString(KEY_LAST_FP, fingerprint).apply()
    }

    private val DIGITS = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private const val KEY_LAST_FP = "clipboard_fp"
}
