package com.pickup.print

/** 记录分类的能力类型（决定表单/打印排版）。 */
enum class CollectionKind(val id: String, val label: String) {
    PICKUP("pickup", "上门取件码"),
    TEA_CUP("tea_cup", "奶茶杯贴"),
    TICKET("ticket", "车票"),
    GENERIC("generic", "通用");

    companion object {
        fun fromId(id: String?): CollectionKind =
            entries.find { it.id == id } ?: GENERIC
    }

    fun toPrintCategory(): PrintCategory = when (this) {
        PICKUP -> PrintCategory.PICKUP
        TEA_CUP, TICKET, GENERIC -> PrintCategory.TEA_CUP
    }
}

data class CollectionCategory(
    val id: String,
    val name: String,
    val kind: CollectionKind,
    val sortOrder: Int,
)

data class CollectionItem(
    val id: String,
    val categoryId: String,
    val createdAt: Long,
    /** 主字段：取件码 / 取茶号 / 车次 / 标题 */
    val title: String,
    /** 次字段：店名 / 线路等 */
    val subtitle: String?,
    val note: String?,
    val thumbPath: String?,
    val source: String,
    val extras: Map<String, String> = emptyMap(),
) {
    val drinkName: String? get() = extras["drinkName"]?.takeIf { it.isNotBlank() }
}
