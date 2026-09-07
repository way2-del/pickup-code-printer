package com.pickup.print

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 通用收集库：自定义分类 + 条目（兼容迁移旧奶茶杯贴数据）。 */
class CollectionRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir: File by lazy {
        File(context.filesDir, "collection").also { it.mkdirs() }
    }

    init {
        migrateFromTeaCupsIfNeeded()
        ensureSeedCategories()
        ensurePickupCategory()
        migratePickupHistoryIfNeeded()
        sanitizeNotesWithSourcePrefix()
    }

    fun listCategories(): List<CollectionCategory> {
        val raw = prefs.getString(KEY_CATEGORIES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    parseCategory(arr.getJSONObject(i))?.let { add(it) }
                }
            }.sortedBy { it.sortOrder }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun listItems(categoryId: String? = null): List<CollectionItem> {
        val all = loadAllItems()
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    fun addCategory(name: String, kind: CollectionKind): CollectionCategory {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分类名不能为空" }
        val cats = listCategories().toMutableList()
        if (cats.any { it.name == trimmed }) error("已存在同名分类")
        val cat = CollectionCategory(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            kind = kind,
            sortOrder = (cats.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        cats += cat
        saveCategories(cats)
        return cat
    }

    fun renameCategory(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val cats = listCategories().toMutableList()
        val idx = cats.indexOfFirst { it.id == id }
        if (idx < 0) return false
        if (cats.any { it.id != id && it.name == trimmed }) return false
        cats[idx] = cats[idx].copy(name = trimmed)
        saveCategories(cats)
        return true
    }

    fun deleteCategory(id: String): Boolean {
        val cats = listCategories().toMutableList()
        if (cats.size <= 1) return false
        if (cats.none { it.id == id }) return false
        cats.removeAll { it.id == id }
        saveCategories(cats)
        val kept = loadAllItems().filter { it.categoryId != id }
        val removed = loadAllItems().filter { it.categoryId == id }
        removed.forEach { it.thumbPath?.let { p -> File(p).delete() } }
        saveAllItems(kept)
        return true
    }

    fun moveCategory(id: String, towardStart: Boolean): Boolean {
        val cats = listCategories().toMutableList()
        val idx = cats.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val swapWith = if (towardStart) idx - 1 else idx + 1
        if (swapWith !in cats.indices) return false
        val a = cats[idx]
        val b = cats[swapWith]
        cats[idx] = a.copy(sortOrder = b.sortOrder)
        cats[swapWith] = b.copy(sortOrder = a.sortOrder)
        saveCategories(cats.sortedBy { it.sortOrder })
        return true
    }

    fun addItem(
        categoryId: String,
        title: String,
        subtitle: String? = null,
        note: String? = null,
        thumbJpeg: File? = null,
        source: String = "manual",
        extras: Map<String, String> = emptyMap(),
    ): CollectionItem {
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
        val item = CollectionItem(
            id = id,
            categoryId = categoryId,
            createdAt = System.currentTimeMillis(),
            title = title.trim(),
            subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() },
            note = note?.trim()?.takeIf { it.isNotBlank() },
            thumbPath = thumbPath,
            source = source,
            extras = extras.filterValues { it.isNotBlank() },
        )
        val current = loadAllItems().toMutableList()
        current.add(0, item)
        while (current.size > 200) {
            val removed = current.removeAt(current.lastIndex)
            removed.thumbPath?.let { File(it).delete() }
        }
        saveAllItems(current)
        return item
    }

    fun deleteItem(id: String) {
        val next = loadAllItems().toMutableList()
        val idx = next.indexOfFirst { it.id == id }
        if (idx < 0) return
        next.removeAt(idx).thumbPath?.let { File(it).delete() }
        saveAllItems(next)
    }

    fun firstCategoryOfKind(kind: CollectionKind): CollectionCategory? =
        listCategories().firstOrNull { it.kind == kind }

    fun categoryById(id: String): CollectionCategory? =
        listCategories().find { it.id == id }

    private fun ensureSeedCategories() {
        if (listCategories().isNotEmpty()) return
        saveCategories(
            listOf(
                CollectionCategory(
                    id = UUID.randomUUID().toString(),
                    name = "上门取件码",
                    kind = CollectionKind.PICKUP,
                    sortOrder = 0,
                ),
                CollectionCategory(
                    id = UUID.randomUUID().toString(),
                    name = "奶茶",
                    kind = CollectionKind.TEA_CUP,
                    sortOrder = 1,
                ),
                CollectionCategory(
                    id = UUID.randomUUID().toString(),
                    name = "车票",
                    kind = CollectionKind.TICKET,
                    sortOrder = 2,
                ),
            )
        )
    }

    /** 已有安装补种「上门取件码」分类。 */
    private fun ensurePickupCategory() {
        val cats = listCategories()
        if (cats.any { it.kind == CollectionKind.PICKUP }) return
        val pickup = CollectionCategory(
            id = UUID.randomUUID().toString(),
            name = "上门取件码",
            kind = CollectionKind.PICKUP,
            sortOrder = -1,
        )
        val reindexed = (listOf(pickup) + cats).mapIndexed { index, cat ->
            cat.copy(sortOrder = index)
        }
        saveCategories(reindexed)
    }

    private fun migratePickupHistoryIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_PICKUP_HISTORY, false)) return
        ensurePickupCategory()
        val pickupCat = firstCategoryOfKind(CollectionKind.PICKUP) ?: run {
            prefs.edit().putBoolean(KEY_MIGRATED_PICKUP_HISTORY, true).apply()
            return
        }
        val history = RecognitionHistoryRepository(context).list()
        if (history.isEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED_PICKUP_HISTORY, true).apply()
            return
        }
        val migrated = history.mapNotNull { record ->
            val code = record.pickupCode?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            var thumbPath: String? = null
            val src = File(record.thumbPath)
            if (src.exists()) {
                val id = UUID.randomUUID().toString()
                val dest = File(dir, "$id.jpg")
                runCatching {
                    src.copyTo(dest, overwrite = true)
                    thumbPath = dest.absolutePath
                }
            }
            CollectionItem(
                id = UUID.randomUUID().toString(),
                categoryId = pickupCat.id,
                createdAt = record.createdAt,
                title = code,
                subtitle = null,
                note = HistoryLabels.remarkFromSource(record.source),
                thumbPath = thumbPath,
                source = record.source,
            )
        }
        if (migrated.isNotEmpty()) {
            saveAllItems(migrated + loadAllItems())
        }
        prefs.edit().putBoolean(KEY_MIGRATED_PICKUP_HISTORY, true).apply()
    }

    /** 清理备注里误写入的 screenshot:/clipboard: 前缀。 */
    private fun sanitizeNotesWithSourcePrefix() {
        if (prefs.getBoolean(KEY_SANITIZED_NOTES_V2, false)) return
        val all = loadAllItems()
        var changed = false
        val next = all.map { item ->
            val cleaned = HistoryLabels.cleanRemark(item.note)
            if (cleaned != item.note) {
                changed = true
                item.copy(note = cleaned)
            } else {
                item
            }
        }
        if (changed) saveAllItems(next)
        prefs.edit()
            .putBoolean(KEY_SANITIZED_NOTES, true)
            .putBoolean(KEY_SANITIZED_NOTES_V2, true)
            .apply()
    }

    private fun migrateFromTeaCupsIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_TEA, false)) return
        val old = context.getSharedPreferences("tea_cup_collection", Context.MODE_PRIVATE)
        val raw = old.getString("list", null)
        if (raw.isNullOrBlank() || raw == "[]") {
            prefs.edit().putBoolean(KEY_MIGRATED_TEA, true).apply()
            return
        }
        ensureSeedCategories()
        var cats = listCategories()
        var teaCat = cats.firstOrNull { it.kind == CollectionKind.TEA_CUP }
        if (teaCat == null) {
            teaCat = addCategory("奶茶", CollectionKind.TEA_CUP)
            cats = listCategories()
        }
        try {
            val arr = JSONArray(raw)
            val migrated = mutableListOf<CollectionItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val oldThumb = obj.optString("thumbPath").ifBlank { null }
                var newThumb: String? = null
                if (!oldThumb.isNullOrBlank()) {
                    val src = File(oldThumb)
                    if (src.exists()) {
                        val dest = File(dir, "$id.jpg")
                        src.copyTo(dest, overwrite = true)
                        newThumb = dest.absolutePath
                    }
                }
                val drink = obj.optString("drinkName").ifBlank { null }
                migrated += CollectionItem(
                    id = id,
                    categoryId = teaCat.id,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    title = obj.optString("teaCode"),
                    subtitle = obj.optString("shopName").ifBlank { null },
                    note = obj.optString("note").ifBlank { null },
                    thumbPath = newThumb,
                    source = obj.optString("source", "manual"),
                    extras = if (drink != null) mapOf("drinkName" to drink) else emptyMap(),
                )
            }
            if (migrated.isNotEmpty()) {
                saveAllItems(migrated + loadAllItems())
            }
        } catch (_: Exception) {
        }
        prefs.edit().putBoolean(KEY_MIGRATED_TEA, true).apply()
    }

    private fun loadAllItems(): List<CollectionItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    parseItem(arr.getJSONObject(i))?.let { add(it) }
                }
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCategories(list: List<CollectionCategory>) {
        val arr = JSONArray()
        list.sortedBy { it.sortOrder }.forEachIndexed { index, cat ->
            arr.put(
                JSONObject()
                    .put("id", cat.id)
                    .put("name", cat.name)
                    .put("kind", cat.kind.id)
                    .put("sortOrder", index)
            )
        }
        prefs.edit().putString(KEY_CATEGORIES, arr.toString()).apply()
    }

    private fun saveAllItems(list: List<CollectionItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    private fun toJson(item: CollectionItem) = JSONObject().apply {
        put("id", item.id)
        put("categoryId", item.categoryId)
        put("createdAt", item.createdAt)
        put("title", item.title)
        put("subtitle", item.subtitle ?: "")
        put("note", item.note ?: "")
        put("thumbPath", item.thumbPath ?: "")
        put("source", item.source)
        val ex = JSONObject()
        item.extras.forEach { (k, v) -> ex.put(k, v) }
        put("extras", ex)
    }

    private fun parseCategory(obj: JSONObject): CollectionCategory? = try {
        CollectionCategory(
            id = obj.getString("id"),
            name = obj.getString("name"),
            kind = CollectionKind.fromId(obj.optString("kind")),
            sortOrder = obj.optInt("sortOrder", 0),
        )
    } catch (_: Exception) {
        null
    }

    private fun parseItem(obj: JSONObject): CollectionItem? = try {
        val extrasObj = obj.optJSONObject("extras")
        val extras = buildMap {
            extrasObj?.keys()?.forEach { key ->
                put(key, extrasObj.optString(key))
            }
            // 兼容直接字段
            obj.optString("drinkName").takeIf { it.isNotBlank() }?.let { put("drinkName", it) }
        }
        CollectionItem(
            id = obj.getString("id"),
            categoryId = obj.getString("categoryId"),
            createdAt = obj.getLong("createdAt"),
            title = obj.getString("title"),
            subtitle = obj.optString("subtitle").ifBlank { null },
            note = obj.optString("note").ifBlank { null },
            thumbPath = obj.optString("thumbPath").ifBlank { null },
            source = obj.optString("source", "manual"),
            extras = extras,
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PREFS = "collection_repo"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ITEMS = "items"
        private const val KEY_MIGRATED_TEA = "migrated_tea_cups"
        private const val KEY_MIGRATED_PICKUP_HISTORY = "migrated_pickup_history"
        private const val KEY_SANITIZED_NOTES = "sanitized_notes_prefix"
        private const val KEY_SANITIZED_NOTES_V2 = "sanitized_notes_prefix_v2"
    }
}
