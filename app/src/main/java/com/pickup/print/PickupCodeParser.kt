/**
 * 从 OCR 全文中提取取件/取餐码。
 * 覆盖：快递上门码 + 茶饮小程序「取茶号」（古茗等）。
 */
package com.pickup.print

import java.util.regex.Pattern

object PickupCodeParser {

    /** 快递类：标签后紧跟码 */
    private val expressLabeled = listOf(
        Pattern.compile("(?:上门)?取件码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("取件验证码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("取件密码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("收件码[:：\\s]*([A-Za-z0-9]{3,12})"),
        Pattern.compile("Pickup\\s*Code[:：\\s]*([A-Za-z0-9]{3,12})", Pattern.CASE_INSENSITIVE),
    )

    /**
     * 茶饮小程序（古茗等）：
     * - 「取茶号」下一行大号数字 985
     * - 「取茶号644」同行
     * - 「取餐号 / 取餐码 / 叫号」
     */
    private val teaLabeled = listOf(
        // 同行：取茶号644 / 取茶号：644 / 取茶号 644
        Pattern.compile("取茶号[:：\\s#＃]*([0-9]{2,5})"),
        Pattern.compile("取餐号[:：\\s#＃]*([0-9]{2,5})"),
        Pattern.compile("取餐码[:：\\s#＃]*([0-9A-Za-z]{2,8})"),
        Pattern.compile("叫号[:：\\s#＃]*([0-9]{2,5})"),
        Pattern.compile("排队号[:：\\s#＃]*([0-9]{2,5})"),
        // 跨行：取茶号\\n985（OCR 常把换行吃成空格，也兼容）
        Pattern.compile("取茶号[:：\\s#＃]*\\s+([0-9]{2,5})(?!\\d)"),
    )

    private val orderIdNoise = Pattern.compile(
        "订单编号[:：\\s]*([0-9]{10,})"
    )
    private val queueNoise = Pattern.compile(
        "前方\\s*([0-9]{1,3})\\s*杯|/\\s*([0-9]{1,3})\\s*单"
    )

    /** 独立短数字：茶饮常见 3–4 位；快递常见 4–6 位 */
    private val standaloneShort = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)")
    private val standaloneMid = Pattern.compile("(?<!\\d)(\\d{5,6})(?!\\d)")

    data class Result(
        val code: String?,
        val candidates: List<String>
    )

    fun parse(ocrText: String): Result {
        val raw = ocrText.replace('\r', '\n').trim()
        if (raw.isEmpty()) return Result(null, emptyList())

        // 保留换行，便于「取茶号\\n985」；同时准备单行版
        val spaced = raw.replace('\n', ' ').replace(Regex("\\s+"), " ")
        val teaPage = looksLikeTeaPickupPage(spaced)

        val noise = collectNoiseNumbers(spaced)
        val scored = LinkedHashMap<String, Int>()

        fun add(code: String?, score: Int) {
            val c = code?.trim().orEmpty()
            if (!isPlausible(c, teaPage)) return
            if (c in noise) return
            scored[c] = maxOf(scored[c] ?: 0, score)
        }

        // 1) 茶饮标签：最高优先
        for (pattern in teaLabeled) {
            matchAll(pattern, raw) { add(it, 100) }
            matchAll(pattern, spaced) { add(it, 100) }
        }
        // 「取茶号」后 40 字符内的短数字（OCR 把布局打散时）
        extractNearLabel(raw, listOf("取茶号", "取餐号", "取餐码", "叫号")) { add(it, 95) }

        // 2) 快递标签
        for (pattern in expressLabeled) {
            matchAll(pattern, spaced) { add(it, 80) }
        }

        // 3) 茶饮页：优先 3–4 位独立数字；非茶饮页才捞 4–6 位
        if (teaPage || scored.isEmpty()) {
            matchAll(standaloneShort, spaced) { code ->
                val bonus = when {
                    teaPage && code.length in 3..4 -> 50
                    else -> 20
                }
                add(code, bonus)
            }
        }
        if (!teaPage && scored.isEmpty()) {
            matchAll(standaloneMid, spaced) { add(it, 30) }
        }

        val ranked = scored.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key.length } // 同分偏短（取茶号 644 优于长串）
            )
            .map { it.key }

        return Result(ranked.firstOrNull(), ranked)
    }

    private fun looksLikeTeaPickupPage(text: String): Boolean {
        val keys = listOf(
            "取茶号", "取餐号", "取餐码", "古茗", "goodme", "葫芦",
            "制作中", "再来一单", "话梅", "实付", "联系门店", "前往门店",
            "雪王币", "蜜雪冰城", "茶百道", "霸王茶姬", "瑞幸",
        )
        return keys.any { text.contains(it, ignoreCase = true) }
    }

    /** 订单号、排队杯数等噪声，不能当地取餐码 */
    private fun collectNoiseNumbers(text: String): Set<String> {
        val out = HashSet<String>()
        matchAll(orderIdNoise, text) { out += it }
        // 前方16杯/13单
        val q = queueNoise.matcher(text)
        while (q.find()) {
            q.group(1)?.let { out += it }
            q.group(2)?.let { out += it }
        }
        // 明显订单号：很长的纯数字
        val longDigits = Pattern.compile("(?<!\\d)(\\d{10,})(?!\\d)").matcher(text)
        while (longDigits.find()) {
            longDigits.group(1)?.let { out += it }
        }
        // 价格附近的数字（¥18、实付：¥0）不当码
        val price = Pattern.compile("[¥￥]\\s*(\\d{1,4})").matcher(text)
        while (price.find()) {
            price.group(1)?.let { out += it }
        }
        return out
    }

    /**
     * 标签后一段窗口内找最像取餐码的短数字。
     * 古茗制作中页：取茶号 与 985 常被 OCR 拆到不同行。
     */
    private fun extractNearLabel(text: String, labels: List<String>, onHit: (String) -> Unit) {
        for (label in labels) {
            var from = 0
            while (true) {
                val idx = text.indexOf(label, from)
                if (idx < 0) break
                val window = text.substring(idx, minOf(text.length, idx + label.length + 48))
                val m = Pattern.compile("(?<!\\d)(\\d{2,5})(?!\\d)").matcher(window)
                var best: String? = null
                while (m.find()) {
                    val n = m.group(1) ?: continue
                    // 跳过标签里不可能出现的杯数语境已在 noise；窗口内优先 3–4 位
                    if (n.length in 3..4) {
                        best = n
                        break
                    }
                    if (best == null && n.length in 2..5) best = n
                }
                best?.let(onHit)
                from = idx + label.length
            }
        }
    }

    private fun matchAll(pattern: Pattern, text: String, onHit: (String) -> Unit) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val code = matcher.group(1)?.trim().orEmpty()
            if (code.isNotEmpty()) onHit(code)
        }
    }

    private fun isPlausible(code: String, teaPage: Boolean): Boolean {
        if (code.length !in 2..12) return false
        if (!code.any { it.isDigit() }) return false
        // 日期
        if (code.length == 8 && code.startsWith("20")) return false
        if (code.length == 11) return false
        // 超长订单号
        if (code.length >= 10 && code.all { it.isDigit() }) return false
        // 茶饮页允许 2–5 位纯数字；快递页至少 3 位
        if (code.all { it.isDigit() }) {
            if (teaPage) return code.length in 2..5
            return code.length in 3..8
        }
        return code.length in 3..12
    }
}
