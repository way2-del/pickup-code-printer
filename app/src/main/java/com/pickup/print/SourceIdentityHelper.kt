/** 把「微信」细化到小程序/窗口标题粒度（多任务卡片标题）。 */
package com.pickup.print

object SourceIdentityHelper {

    private val HOST_APPS = setOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.tencent.mobileqq",
    )

    /** 宿主 App 自身名字，不能当小程序名。 */
    private val HOST_GENERIC_TITLES = setOf(
        "微信", "WeChat", "Weixin",
        "支付宝", "Alipay",
        "QQ", "TIM",
        "小程序", "MiniProgram", "mini program",
    )

    /** 控制中心 / 通知栏 / 多任务等系统浮层标题。 */
    private val SYSTEM_UI_TITLES = setOf(
        "控制中心", "Control center", "Control Center", "control center",
        "通知中心", "Notification shade", "Notifications",
        "快捷设置", "Quick settings", "Quick Settings",
        "设置", "Settings",
        "最近任务", "Overview", "Recents",
        "系统界面", "System UI", "Android System",
        "超级小爱", "小爱同学", "小爱",
    )

    /** 明显是按钮/菜单文案，不是小程序名。 */
    private val UI_CHROME_EXACT = setOf(
        "返回", "关闭", "更多", "分享", "首页", "我的", "搜索", "取消", "确定",
        "完成", "发送", "复制", "粘贴", "删除", "编辑", "刷新", "投诉",
        "切换搜索引擎", "搜索引擎", "网页由", "浮窗", "最小化", "重启",
        "用默认浏览器打开", "刷新", "转发", "收藏", "在浏览器打开",
    )

    private val UI_CHROME_CONTAINS = listOf(
        "切换", "搜索引擎", "点击", "请输入", "长按", "下拉", "上拉",
        "打开浮窗", "权限", "允许", "拒绝", "知道了", "我知道了",
        "更多功能", "菜单", "设置中心", "控制中心",
    )

    fun isHostApp(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName in HOST_APPS

    fun isSystemUiTitle(title: String?): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (SYSTEM_UI_TITLES.any { it.equals(t, ignoreCase = true) }) return true
        if (t.contains("控制中心") || t.contains("通知中心") || t.contains("快捷设置")) return true
        if (t.contains("Control center", ignoreCase = true)) return true
        return false
    }

    /** 按钮/菜单/浏览器 chrome，不能当地小程序名。 */
    fun isUiChromeTitle(title: String?): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (UI_CHROME_EXACT.any { it.equals(t, ignoreCase = true) }) return true
        if (UI_CHROME_CONTAINS.any { t.contains(it) }) return true
        // 动词开头的操作文案
        if (t.startsWith("切换") || t.startsWith("打开") || t.startsWith("关闭") ||
            t.startsWith("搜索") || t.startsWith("选择") || t.startsWith("点击")
        ) {
            return true
        }
        return false
    }

    fun isNoiseTitle(title: String?): Boolean =
        isSystemUiTitle(title) || isUiChromeTitle(title)

    fun looksLikeMiniProgramClass(className: String?): Boolean {
        val c = className.orEmpty()
        if (c.isBlank()) return false
        val lower = c.lowercase()
        return lower.contains("appbrand") ||
            lower.contains("miniprogram") ||
            lower.contains("tinyapp") ||
            lower.contains("nebula") ||
            lower.contains("aerial") ||
            lower.contains("plugin.appbrand")
    }

    /**
     * 是否像「蜜雪冰城」这种专名，而不是句子/操作文案。
     * 小程序多任务标题通常较短、几乎不含动词。
     */
    fun looksLikeAppOrMiniName(title: String?): Boolean {
        val t = sanitizeTitle(title) ?: return false
        if (isNoiseTitle(t)) return false
        if (t.length !in 2..16) return false
        // 含空格的长句更像描述
        if (t.count { it == ' ' || it == '　' } >= 2) return false
        // 纯数字/取件码不要当地来源名
        if (t.all { it.isDigit() }) return false
        return true
    }

    /**
     * 从多个候选窗口标题里挑最像「小程序名 / 多任务卡片名」的一个。
     * [windowOnly] 为 true 时只接受专名，拒绝 UI chrome。
     */
    fun pickBestTitle(
        candidates: List<String?>,
        appLabel: String?,
        requireMiniLikeName: Boolean = false,
    ): String? {
        val cleaned = candidates
            .mapNotNull { sanitizeTitle(it) }
            .filterNot { isNoiseTitle(it) }
            .distinct()
        if (cleaned.isEmpty()) return null
        val base = appLabel?.trim().orEmpty()
        val nonGeneric = cleaned.filterNot { isGenericHostTitle(it, base) }
        val pool = nonGeneric.ifEmpty { cleaned }
            .let { list ->
                if (requireMiniLikeName) list.filter { looksLikeAppOrMiniName(it) } else list
            }
        if (pool.isEmpty()) return null
        return pool.sortedWith(
            compareBy<String> { it.length !in 2..12 }
                .thenBy { it.length }
        ).firstOrNull()
    }

    /**
     * @param appLabel 包名对应的应用名，如「微信」
     * @param windowTitle 多任务/窗口标题，小程序通常是小程序名
     * @param className Activity/窗口类名，用于判断是否小程序容器
     */
    fun refineDisplayLabel(
        packageName: String?,
        appLabel: String?,
        windowTitle: String?,
        className: String? = null,
    ): String {
        val pkg = packageName.orEmpty()
        val base = appLabel?.trim()?.takeIf { it.isNotBlank() }
            ?: pkg.substringAfterLast('.').ifBlank { pkg.ifBlank { "未知来源" } }
        val title = sanitizeTitle(windowTitle) ?: return base
        if (isNoiseTitle(title) || isGenericHostTitle(title, base)) return base
        // 宿主 App：只接受像小程序名的标题，避免「切换搜索引擎」之类
        if (isHostApp(pkg) && !looksLikeAppOrMiniName(title)) return base

        return if (isHostApp(pkg)) {
            "$base·$title"
        } else if (title != base && looksLikeAppOrMiniName(title)) {
            "$base·$title"
        } else {
            base
        }
    }

    /**
     * 已有精细标签（含 ·）时，不要被噪声盖掉；噪声后缀直接丢掉。
     */
    fun preferStableLabel(existing: String?, fresh: String?): String? {
        val e = existing?.trim()?.takeIf { it.isNotBlank() }
        val f = fresh?.trim()?.takeIf { it.isNotBlank() }

        fun suffixOk(label: String): Boolean {
            if (!label.contains('·')) return false
            val suffix = label.substringAfter('·')
            return looksLikeAppOrMiniName(suffix) && !isNoiseTitle(suffix)
        }

        val eOk = e != null && suffixOk(e)
        val fOk = f != null && suffixOk(f)

        if (eOk && !fOk) return e
        if (fOk) return f
        // 都没有 · 专名时，优先无噪声的简单名
        if (f != null && !f.contains('·')) return f
        if (e != null && !e.contains('·')) return e
        return f ?: e
    }

    private fun sanitizeTitle(raw: String?): String? {
        val t = raw?.trim()?.trim('\u0000')?.takeIf { it.isNotBlank() } ?: return null
        if (isNoiseTitle(t)) return null
        return t.take(24)
    }

    private fun isGenericHostTitle(title: String, appLabel: String): Boolean {
        if (title.equals(appLabel, ignoreCase = true)) return true
        if (HOST_GENERIC_TITLES.any { it.equals(title, ignoreCase = true) }) return true
        if (title.contains('.') && title.any { it.isLowerCase() } && !title.contains(' ')) {
            if (title.startsWith("com.") || title.contains("tencent") || title.contains("mm.")) {
                return true
            }
        }
        return false
    }
}
