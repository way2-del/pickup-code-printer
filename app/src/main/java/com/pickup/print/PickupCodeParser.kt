package com.pickup.print

import java.util.regex.Pattern

/**
 * 从 OCR 全文中提取上门取件码。
 * 覆盖顺丰 / 菜鸟 / 京东 / 拼多多等常见文案。
 */
object PickupCodeParser {

    private val labeledPatterns = listOf(
        Pattern.compile("(?:上门)?取件码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("取件验证码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("验证码[:：\\s]*([0-9]{4,8})"),
        Pattern.compile("取件密码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("收件码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("Pickup\\s*Code[:：\\s]*([A-Za-z0-9]{3,12})", Pattern.CASE_INSENSITIVE),
    )

    private val standaloneDigits = Pattern.compile("(?<!\\d)(\\d{4,6})(?!\\d)")

    data class Result(
        val code: String?,
        val candidates: List<String>
    )

    fun parse(ocrText: String): Result {
        val text = ocrText.replace('\n', ' ').replace('\r', ' ').trim()
        if (text.isEmpty()) return Result(null, emptyList())

        val found = LinkedHashSet<String>()

        for (pattern in labeledPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val code = matcher.group(1)?.trim().orEmpty()
                if (isPlausible(code)) found.add(code)
            }
        }

        // 若带标签未命中，再收集独立 4–6 位数字（常见上门码）
        if (found.isEmpty()) {
            val matcher = standaloneDigits.matcher(text)
            while (matcher.find()) {
                val code = matcher.group(1) ?: continue
                if (isPlausible(code)) found.add(code)
            }
        }

        val list = found.toList()
        return Result(list.firstOrNull(), list)
    }

    private fun isPlausible(code: String): Boolean {
        if (code.length !in 3..12) return false
        // 过滤明显日期 / 手机号片段
        if (code.length == 8 && code.startsWith("20")) return false
        if (code.length == 11) return false
        return code.any { it.isDigit() }
    }
}
