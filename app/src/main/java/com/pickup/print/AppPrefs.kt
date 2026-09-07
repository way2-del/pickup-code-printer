package com.pickup.print

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit

/** 应用级偏好（对齐 Nexio `app_preferences` / `app_theme_prefs`）。 */
object AppPrefs {
    private const val PREFS_APP = "app_preferences"
    private const val PREFS_THEME = "app_theme_prefs"
    private const val KEY_HIDE_BACKGROUND = "hide_background"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_APP_STYLE = "app_style"
    private const val KEY_ISLAND_ENABLED = "island_notification"
    private const val KEY_ISLAND_EXPAND_GLOW = "island_expand_glow_enabled"

    const val STYLE_HYPEROS3 = "hyperos3"
    const val STYLE_LIQUID_GLASS = "liquidglass"

    fun themePrefs(context: Context) =
        context.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)

    fun appPrefs(context: Context) =
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)

    fun getThemeMode(context: Context): String =
        themePrefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        themePrefs(context).edit { putString(KEY_THEME_MODE, mode) }
    }

    fun getAppStyle(context: Context): String =
        themePrefs(context).getString(KEY_APP_STYLE, STYLE_LIQUID_GLASS) ?: STYLE_LIQUID_GLASS

    fun setAppStyle(context: Context, style: String) {
        themePrefs(context).edit { putString(KEY_APP_STYLE, style) }
    }

    fun isIslandEnabled(context: Context): Boolean =
        appPrefs(context).getBoolean(KEY_ISLAND_ENABLED, true)

    fun setIslandEnabled(context: Context, enabled: Boolean) {
        appPrefs(context).edit { putBoolean(KEY_ISLAND_ENABLED, enabled) }
    }

    fun isIslandExpandGlowEnabled(context: Context): Boolean =
        appPrefs(context).getBoolean(KEY_ISLAND_EXPAND_GLOW, true)

    fun setIslandExpandGlowEnabled(context: Context, enabled: Boolean) {
        appPrefs(context).edit { putBoolean(KEY_ISLAND_EXPAND_GLOW, enabled) }
    }

    fun isHideBackground(context: Context): Boolean =
        appPrefs(context).getBoolean(KEY_HIDE_BACKGROUND, false)

    fun setHideBackground(context: Context, hidden: Boolean) {
        appPrefs(context).edit { putBoolean(KEY_HIDE_BACKGROUND, hidden) }
        setTaskExcludedFromRecents(context, hidden)
    }

    fun setTaskExcludedFromRecents(context: Context, hidden: Boolean) {
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val appTask = manager.appTasks.firstOrNull { task ->
                task.taskInfo?.baseIntent?.component?.packageName == context.packageName
            } ?: manager.appTasks.firstOrNull()
            appTask?.setExcludeFromRecents(hidden)
        }
    }
}
