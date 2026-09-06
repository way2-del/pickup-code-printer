package com.pickup.print

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RecognitionRecord(
    val id: String,
    val createdAt: Long,
    val pickupCode: String?,
    val candidates: List<String>,
    val ocrText: String,
    val thumbPath: String,
    val source: String
)

class RecognitionHistoryRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir: File by lazy {
        File(context.filesDir, "history").also { it.mkdirs() }
    }

    fun list(): List<RecognitionRecord> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    parse(arr.getJSONObject(i))?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun latest(): RecognitionRecord? = list().firstOrNull()

    fun get(id: String): RecognitionRecord? = list().find { it.id == id }

    fun add(
        pickupCode: String?,
        candidates: List<String>,
        ocrText: String,
        thumbJpeg: File,
        source: String
    ): RecognitionRecord {
        val id = UUID.randomUUID().toString()
        val dest = File(dir, "$id.jpg")
        if (thumbJpeg.absolutePath != dest.absolutePath) {
            thumbJpeg.copyTo(dest, overwrite = true)
            if (thumbJpeg.parentFile == context.cacheDir || thumbJpeg.name.startsWith("tmp_")) {
                thumbJpeg.delete()
            }
        }
        val record = RecognitionRecord(
            id = id,
            createdAt = System.currentTimeMillis(),
            pickupCode = pickupCode,
            candidates = candidates,
            ocrText = ocrText,
            thumbPath = dest.absolutePath,
            source = source
        )
        val current = list().toMutableList()
        current.add(0, record)
        // 最多保留 50 条
        while (current.size > 50) {
            val removed = current.removeAt(current.lastIndex)
            File(removed.thumbPath).delete()
        }
        saveAll(current)
        return record
    }

    fun clear() {
        list().forEach { File(it.thumbPath).delete() }
        prefs.edit().putString(KEY_LIST, "[]").apply()
    }

    private fun saveAll(items: List<RecognitionRecord>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun toJson(r: RecognitionRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("createdAt", r.createdAt)
        put("pickupCode", r.pickupCode ?: "")
        put("candidates", JSONArray(r.candidates))
        put("ocrText", r.ocrText)
        put("thumbPath", r.thumbPath)
        put("source", r.source)
    }

    private fun parse(obj: JSONObject): RecognitionRecord? {
        return try {
            val candidates = mutableListOf<String>()
            val cArr = obj.optJSONArray("candidates")
            if (cArr != null) {
                for (i in 0 until cArr.length()) candidates += cArr.getString(i)
            }
            RecognitionRecord(
                id = obj.getString("id"),
                createdAt = obj.getLong("createdAt"),
                pickupCode = obj.optString("pickupCode").ifBlank { null },
                candidates = candidates,
                ocrText = obj.optString("ocrText"),
                thumbPath = obj.getString("thumbPath"),
                source = obj.optString("source", "unknown")
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS = "recognition_history"
        private const val KEY_LIST = "list"
    }
}
