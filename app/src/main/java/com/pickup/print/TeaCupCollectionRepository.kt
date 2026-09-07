package com.pickup.print

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TeaCupRecord(
    val id: String,
    val createdAt: Long,
    val teaCode: String,
    val shopName: String,
    val drinkName: String?,
    val note: String?,
    val thumbPath: String?,
    val source: String,
)

/** 奶茶杯贴收集库（与上门取件历史分开）。 */
class TeaCupCollectionRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir: File by lazy {
        File(context.filesDir, "tea_cups").also { it.mkdirs() }
    }

    fun list(): List<TeaCupRecord> {
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

    fun add(
        teaCode: String,
        shopName: String,
        drinkName: String? = null,
        note: String? = null,
        thumbJpeg: File? = null,
        source: String = "manual",
    ): TeaCupRecord {
        val id = UUID.randomUUID().toString()
        var thumbPath: String? = null
        if (thumbJpeg != null && thumbJpeg.exists()) {
            val dest = File(dir, "$id.jpg")
            thumbJpeg.copyTo(dest, overwrite = true)
            if (thumbJpeg.parentFile == context.cacheDir || thumbJpeg.name.startsWith("tmp_")) {
                thumbJpeg.delete()
            }
            thumbPath = dest.absolutePath
        }
        val record = TeaCupRecord(
            id = id,
            createdAt = System.currentTimeMillis(),
            teaCode = teaCode.trim(),
            shopName = shopName.trim().ifBlank { "奶茶店" },
            drinkName = drinkName?.trim()?.takeIf { it.isNotBlank() },
            note = note?.trim()?.takeIf { it.isNotBlank() },
            thumbPath = thumbPath,
            source = source,
        )
        val current = list().toMutableList()
        current.add(0, record)
        while (current.size > 100) {
            val removed = current.removeAt(current.lastIndex)
            removed.thumbPath?.let { File(it).delete() }
        }
        saveAll(current)
        return record
    }

    fun delete(id: String) {
        val next = list().toMutableList()
        val idx = next.indexOfFirst { it.id == id }
        if (idx < 0) return
        next.removeAt(idx).thumbPath?.let { File(it).delete() }
        saveAll(next)
    }

    private fun saveAll(list: List<TeaCupRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun toJson(r: TeaCupRecord) = JSONObject().apply {
        put("id", r.id)
        put("createdAt", r.createdAt)
        put("teaCode", r.teaCode)
        put("shopName", r.shopName)
        put("drinkName", r.drinkName ?: "")
        put("note", r.note ?: "")
        put("thumbPath", r.thumbPath ?: "")
        put("source", r.source)
    }

    private fun parse(obj: JSONObject): TeaCupRecord? = try {
        TeaCupRecord(
            id = obj.getString("id"),
            createdAt = obj.getLong("createdAt"),
            teaCode = obj.getString("teaCode"),
            shopName = obj.optString("shopName").ifBlank { "奶茶店" },
            drinkName = obj.optString("drinkName").ifBlank { null },
            note = obj.optString("note").ifBlank { null },
            thumbPath = obj.optString("thumbPath").ifBlank { null },
            source = obj.optString("source", "manual"),
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PREFS = "tea_cup_collection"
        private const val KEY_LIST = "list"
    }
}
