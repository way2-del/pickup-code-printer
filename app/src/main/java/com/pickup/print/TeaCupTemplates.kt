package com.pickup.print

/** 常见奶茶店杯贴标题预设（后续可做成按店精细模板）。 */
object TeaCupTemplates {
    data class ShopPreset(
        val shopName: String,
        val titleText: String,
        val hint: String,
    )

    val presets: List<ShopPreset> = listOf(
        ShopPreset("古茗", "取茶号", "杯贴复原 · 取茶号"),
        ShopPreset("蜜雪冰城", "取餐号", "杯贴复原 · 取餐号"),
        ShopPreset("喜茶", "取茶号", "杯贴复原"),
        ShopPreset("霸王茶姬", "取餐号", "杯贴复原"),
        ShopPreset("茶百道", "取餐号", "杯贴复原"),
        ShopPreset("瑞幸咖啡", "取餐号", "杯贴复原"),
        ShopPreset("自定义", "杯贴", "自行填写店名与标题"),
    )

    fun titleForShop(shopName: String): String =
        presets.find { it.shopName == shopName }?.titleText ?: "取茶号"
}
