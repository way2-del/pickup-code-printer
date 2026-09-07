package com.pickup.print

/** 按记录分类解析打印排版配置与备注文案（列表预览 / 实际打印共用）。 */
object PrintLayoutResolver {

    data class Resolved(
        val config: PrintLayoutConfig,
        val code: String,
        val remark: String?,
    )

    fun resolve(
        prefs: PrintPrefs,
        category: CollectionCategory?,
        item: CollectionItem,
    ): Resolved {
        val code = item.title.trim().ifBlank { "——" }
        val kind = category?.kind ?: CollectionKind.GENERIC
        return when (kind) {
            CollectionKind.PICKUP -> Resolved(
                config = prefs.loadLayout(PrintCategory.PICKUP),
                code = code,
                remark = HistoryLabels.cleanRemark(item.note ?: item.subtitle),
            )
            CollectionKind.TEA_CUP -> {
                val shop = item.subtitle ?: "奶茶店"
                val title = TeaCupTemplates.titleForShop(shop).let { "$shop · $it" }
                Resolved(
                    config = prefs.loadLayout(PrintCategory.TEA_CUP).copy(titleText = title),
                    code = code,
                    remark = HistoryLabels.cleanRemark(
                        listOfNotNull(item.drinkName, item.note).joinToString(" · ").ifBlank { null }
                    ),
                )
            }
            CollectionKind.TICKET -> Resolved(
                config = prefs.loadLayout(PrintCategory.TEA_CUP).copy(
                    titleText = category?.name?.ifBlank { "车票" } ?: "车票",
                    showBarcode = true,
                    showRemark = true,
                ),
                code = code,
                remark = HistoryLabels.cleanRemark(
                    listOfNotNull(item.subtitle, item.note).joinToString(" · ").ifBlank { null }
                ),
            )
            CollectionKind.GENERIC -> Resolved(
                config = prefs.loadLayout(PrintCategory.PICKUP).copy(
                    titleText = category?.name?.ifBlank { "记录" } ?: "记录",
                    showBarcode = false,
                ),
                code = code,
                remark = HistoryLabels.cleanRemark(
                    listOfNotNull(item.subtitle, item.note).joinToString(" · ").ifBlank { null }
                ),
            )
        }
    }
}
