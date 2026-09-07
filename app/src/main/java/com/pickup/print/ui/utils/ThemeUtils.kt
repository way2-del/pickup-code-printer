/** 主题工具类 - 管理深色模式切换和状态栏样式 */
package com.pickup.print.ui.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 壁纸强制深色模式覆盖：非 null 时，isAppDarkTheme() 直接返回该值。
 */
val LocalForcedDarkTheme = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun isAppDarkTheme(): Boolean {
    LocalForcedDarkTheme.current?.let { return it }
    return rememberAppSettingDark()
}

@Composable
fun rememberAppSettingDark(): Boolean {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE) }
    val themeMode = remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
            if (key == "theme_mode") {
                themeMode.value = prefs.getString("theme_mode", "system") ?: "system"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return when (themeMode.value) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
}

/** 应用风格：`hyperos3` / `liquidglass`（对齐旧版课表偏好）。 */
@Composable
fun rememberAppStyle(): String {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE) }
    val appStyle = remember {
        mutableStateOf(prefs.getString("app_style", "liquidglass") ?: "liquidglass")
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences, key: String? ->
            if (key == "app_style") {
                appStyle.value = prefs.getString("app_style", "liquidglass") ?: "liquidglass"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return appStyle.value
}

fun Activity.applyThemeAwareSystemBars() {
    val prefs = getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
    val themeMode = prefs.getString("theme_mode", "system") ?: "system"

    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
    applyThemeAwareSystemBars(isDark)
    applyNavigationBarIsDark(isDark)
}

fun Activity.applyThemeAwareSystemBars(isDark: Boolean) {
    window.decorView.post {
        window.insetsController?.setSystemBarsAppearance(
            if (isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    }
}

fun Activity.applyNavigationBarIsDark(isDark: Boolean) {
    window.decorView.post {
        window.insetsController?.setSystemBarsAppearance(
            if (isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    }
}
