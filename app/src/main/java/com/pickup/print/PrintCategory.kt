package com.pickup.print

/** 两类热敏产出：上门取件码 vs 奶茶杯贴复原。 */
enum class PrintCategory(val id: String, val label: String) {
    PICKUP("pickup", "取件码"),
    TEA_CUP("tea_cup", "奶茶杯贴");

    companion object {
        fun fromId(id: String?): PrintCategory =
            entries.find { it.id == id } ?: PICKUP
    }
}
