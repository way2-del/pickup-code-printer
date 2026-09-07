/** 从无障碍控件树 / OCR 线索识别小程序品牌名（TaskDescription 常为空时的主路径）。 */
package com.pickup.print

import android.view.accessibility.AccessibilityNodeInfo

object BrandHintHelper {

    private val HINTS = listOf(
        listOf("雪王币", "蜜雪冰城", "雪王", "蜜雪") to "蜜雪冰城",
        listOf("古茗", "古茗茶饮", "goodme", "葫芦", "取茶号") to "古茗",
        listOf("茶百道") to "茶百道",
        listOf("霸王茶姬", "伯牙绝弦") to "霸王茶姬",
        listOf("瑞幸", "瑞幸咖啡") to "瑞幸咖啡",
        listOf("星巴克") to "星巴克",
        listOf("喜茶") to "喜茶",
        listOf("奈雪") to "奈雪的茶",
        listOf("库迪咖啡", "库迪") to "库迪咖啡",
        listOf("一点点") to "一点点",
        listOf("CoCo", "都可") to "CoCo都可",
        listOf("书亦烧仙草", "书亦") to "书亦烧仙草",
        listOf("甜啦啦") to "甜啦啦",
        listOf("沪上阿姨") to "沪上阿姨",
        listOf("菜鸟", "裹裹", "菜鸟驿站") to "菜鸟裹裹",
        listOf("丰巢") to "丰巢",
        listOf("朴朴") to "朴朴",
        listOf("美团外卖") to "美团外卖",
        listOf("饿了么") to "饿了么",
        listOf("京东到家", "京东") to "京东",
        listOf("盒马") to "盒马",
    )

    fun guessMiniNameFromOcr(ocrText: String?): String? {
        val text = ocrText?.takeIf { it.isNotBlank() } ?: return null
        for ((keys, name) in HINTS) {
            if (keys.any { text.contains(it) }) return name
        }
        return null
    }

    /** 在无障碍树里找品牌关键字（截屏瞬间、OCR 之前就能定名）。 */
    fun guessMiniNameFromNode(root: AccessibilityNodeInfo?, maxNodes: Int = 120): String? {
        if (root == null) return null
        val texts = ArrayList<String>(maxNodes)
        collectTexts(root, texts, maxNodes, depth = 0)
        if (texts.isEmpty()) return null
        val joined = texts.joinToString("\n")
        guessMiniNameFromOcr(joined)?.let { return it }
        // 再单独看短文本：有的品牌只出现在标题栏
        for (t in texts) {
            val s = t.trim()
            if (s.length in 2..12) {
                for ((_, name) in HINTS) {
                    if (s == name) return name
                }
            }
        }
        return null
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        maxNodes: Int,
        depth: Int,
    ) {
        if (out.size >= maxNodes || depth > 8) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            node.paneTitle?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) return
            val child = node.getChild(i) ?: continue
            try {
                collectTexts(child, out, maxNodes, depth + 1)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 把「微信」细化成「微信·蜜雪冰城」。 */
    fun refineLabelWithOcr(existingLabel: String?, packageName: String?, ocrText: String?): String? {
        val brand = guessMiniNameFromOcr(ocrText) ?: return existingLabel
        return mergeBrand(existingLabel, packageName, brand)
    }

    fun refineLabelWithBrand(existingLabel: String?, packageName: String?, brand: String?): String? {
        val b = brand?.takeIf { it.isNotBlank() } ?: return existingLabel
        return mergeBrand(existingLabel, packageName, b)
    }

    private fun mergeBrand(existingLabel: String?, packageName: String?, brand: String): String {
        val label = existingLabel?.trim().orEmpty()
        if (label.contains('·')) {
            val suffix = label.substringAfter('·')
            if (SourceIdentityHelper.looksLikeAppOrMiniName(suffix) &&
                !SourceIdentityHelper.isNoiseTitle(suffix)
            ) {
                // 已有专名且与品牌冲突时，以更具体的品牌关键字为准（OCR/树更准）
                if (suffix == brand) return label
            }
        }
        val base = when {
            label.isNotBlank() && !label.contains('·') -> label
            packageName == "com.tencent.mm" -> "微信"
            packageName == "com.eg.android.AlipayGphone" -> "支付宝"
            else -> label.ifBlank { "微信" }
        }
        return SourceIdentityHelper.refineDisplayLabel(
            packageName = packageName ?: "com.tencent.mm",
            appLabel = base,
            windowTitle = brand,
            className = "appbrand",
        )
    }

    /** 可选：内置品牌图标资源名（没有则返回 null，岛上继续用微信图标）。 */
    fun brandIconResName(brandOrLabel: String?): String? {
        val key = brandOrLabel?.substringAfter('·')?.trim().orEmpty()
        return when (key) {
            "蜜雪冰城" -> "brand_mixue"
            "古茗" -> "brand_guming"
            "瑞幸咖啡" -> "brand_luckin"
            else -> null
        }
    }
}
