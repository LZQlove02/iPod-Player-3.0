package com.ipodplayer3.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object IpodColors {
    // Silver (default Classic 6/7)
    val SilverBody = Color(0xFFE8E8ED)
    val SilverWheel = Color(0xFFD0D0D6)
    val SilverWheelEdge = Color(0xFFB0B0B8)

    // Black
    val BlackBody = Color(0xFF2C2C2E)
    val BlackWheel = Color(0xFF3A3A3C)
    val BlackWheelEdge = Color(0xFF2A2A2C)

    // U2 black / red
    val U2Body = Color(0xFF1A1A1A)
    val U2Wheel = Color(0xFF2A2A2A)
    val U2WheelEdge = Color(0xFFC41E3A)
    val U2Accent = Color(0xFFD62839)

    // 选中底色：浅底用深一档的蓝，深底用亮蓝
    val SelectionOnLight = Color(0xFF3B82F6)
    val SelectionOnDark = Color(0xFF0A84FF)

    // 屏幕调色板（浅）
    val ScreenLightBg = Color(0xFFF5F5F7)
    val ScreenLightPanel = Color(0xFFE8E8ED)
    val ScreenLightFg = Color(0xFF1D1D1F)
    val ScreenLightMuted = Color(0xFF6E6E73)
    val ScreenLightDivider = Color(0xFFD2D2D7)

    // 屏幕调色板（深）
    val ScreenDarkBg = Color(0xFF1C1C1E)
    val ScreenDarkPanel = Color(0xFF111113)
    val ScreenDarkFg = Color(0xFFF2F2F7)
    val ScreenDarkMuted = Color(0xFF98989D)
    val ScreenDarkDivider = Color(0xFF2C2C2E)

    // Cover Flow 恒为黑底（无论机身主题）
    val CoverFlowBg = Color(0xFF050506)
    val CoverBacksideBg = Color(0xFF1A1A1C)

    /** 机身之外的桌面底色（与主题无关，三种主题都用它）。 */
    val AppBackdrop = Color(0xFF1C1C1E)
}

/** 主题身份。[key] 是落盘的字符串（DataStore 里的 theme 值）。 */
enum class IpodThemeId(val key: String) {
    SILVER("silver"),
    BLACK("black"),
    U2("u2"),
    ;

    fun next(): IpodThemeId = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(key: String): IpodThemeId =
            entries.firstOrNull { it.key == key } ?: SILVER
    }
}

/**
 * 机身 + 屏幕的一套配色。
 *
 * 以前屏幕底色/文字色散落在 4 个文件里各自写 hex（同一个语义还出现过两个值），
 * 现在统一收到这里：改配色只改这一个文件。
 */
data class IpodTheme(
    val id: IpodThemeId,
    // 机身
    val body: Color,
    val wheel: Color,
    val wheelEdge: Color,
    val accent: Color,
    val isDarkScreen: Boolean = false,
    // 屏幕
    val screenBg: Color,
    val screenPanel: Color,
    val screenFg: Color,
    val screenMuted: Color,
    val screenDivider: Color,
    /** 选中行上的文字颜色（选中底是 accent）。 */
    val onAccent: Color = Color.White,
    val coverFlowBg: Color = IpodColors.CoverFlowBg,
    val coverBacksideBg: Color = IpodColors.CoverBacksideBg,
) {
    companion object {
        fun of(key: String): IpodTheme = when (IpodThemeId.from(key)) {
            IpodThemeId.SILVER -> SilverTheme
            IpodThemeId.BLACK -> BlackTheme
            IpodThemeId.U2 -> U2Theme
        }
    }
}

val SilverTheme = IpodTheme(
    id = IpodThemeId.SILVER,
    body = IpodColors.SilverBody,
    wheel = IpodColors.SilverWheel,
    wheelEdge = IpodColors.SilverWheelEdge,
    accent = IpodColors.SelectionOnLight,
    isDarkScreen = false,
    screenBg = IpodColors.ScreenLightBg,
    screenPanel = IpodColors.ScreenLightPanel,
    screenFg = IpodColors.ScreenLightFg,
    screenMuted = IpodColors.ScreenLightMuted,
    screenDivider = IpodColors.ScreenLightDivider,
)

val BlackTheme = IpodTheme(
    id = IpodThemeId.BLACK,
    body = IpodColors.BlackBody,
    wheel = IpodColors.BlackWheel,
    wheelEdge = IpodColors.BlackWheelEdge,
    accent = IpodColors.SelectionOnDark,
    isDarkScreen = true,
    screenBg = IpodColors.ScreenDarkBg,
    screenPanel = IpodColors.ScreenDarkPanel,
    screenFg = IpodColors.ScreenDarkFg,
    screenMuted = IpodColors.ScreenDarkMuted,
    screenDivider = IpodColors.ScreenDarkDivider,
)

val U2Theme = IpodTheme(
    id = IpodThemeId.U2,
    body = IpodColors.U2Body,
    wheel = IpodColors.U2Wheel,
    wheelEdge = IpodColors.U2WheelEdge,
    accent = IpodColors.U2Accent,
    isDarkScreen = true,
    screenBg = IpodColors.ScreenDarkBg,
    screenPanel = IpodColors.ScreenDarkPanel,
    screenFg = IpodColors.ScreenDarkFg,
    screenMuted = IpodColors.ScreenDarkMuted,
    screenDivider = IpodColors.ScreenDarkDivider,
)

/** 当前主题。组件可以 `theme: IpodTheme = LocalIpodTheme.current` 省掉一路传参。 */
val LocalIpodTheme = staticCompositionLocalOf { SilverTheme }

@Composable
fun IpodThemeProvider(theme: IpodTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIpodTheme provides theme, content = content)
}
