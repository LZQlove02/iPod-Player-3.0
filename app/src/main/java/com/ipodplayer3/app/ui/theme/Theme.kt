package com.ipodplayer3.app.ui.theme

import androidx.compose.ui.graphics.Color

object IpodColors {
    // Silver (default Classic 6/7)
    val SilverBody = Color(0xFFE8E8ED)
    val SilverBodyDark = Color(0xFFC8C8CE)
    val SilverWheel = Color(0xFFD0D0D6)
    val SilverWheelEdge = Color(0xFFB0B0B8)

    // Black
    val BlackBody = Color(0xFF2C2C2E)
    val BlackBodyDark = Color(0xFF1C1C1E)
    val BlackWheel = Color(0xFF3A3A3C)
    val BlackWheelEdge = Color(0xFF2A2A2C)

    // U2 black / red
    val U2Body = Color(0xFF1A1A1A)
    val U2BodyDark = Color(0xFF0D0D0D)
    val U2Wheel = Color(0xFF2A2A2A)
    val U2WheelEdge = Color(0xFFC41E3A)
    val U2Accent = Color(0xFFD62839)

    // LCD
    val ScreenBg = Color(0xFFF5F5F7)
    val ScreenBgDark = Color(0xFF1C1C1E)
    val ScreenText = Color(0xFF1D1D1F)
    val ScreenTextDark = Color(0xFFF5F5F7)
    val SelectionBlue = Color(0xFF3B82F6)
    val SelectionBlueDark = Color(0xFF0A84FF)
    val MenuLine = Color(0xFFD2D2D7)
    val MenuLineDark = Color(0xFF38383A)
    val StatusGray = Color(0xFF6E6E73)
    val ProgressTrack = Color(0xFFD2D2D7)
    val ProgressFill = Color(0xFF3B82F6)

    val WheelText = Color(0xFF8E8E93)
    val WheelTextDark = Color(0xFFAEAEB2)
}

data class IpodTheme(
    val name: String,
    val body: Color,
    val bodyDark: Color,
    val wheel: Color,
    val wheelEdge: Color,
    val accent: Color,
    val isDarkScreen: Boolean = false,
)

val SilverTheme = IpodTheme(
    name = "silver",
    body = IpodColors.SilverBody,
    bodyDark = IpodColors.SilverBodyDark,
    wheel = IpodColors.SilverWheel,
    wheelEdge = IpodColors.SilverWheelEdge,
    accent = IpodColors.SelectionBlue,
)

val BlackTheme = IpodTheme(
    name = "black",
    body = IpodColors.BlackBody,
    bodyDark = IpodColors.BlackBodyDark,
    wheel = IpodColors.BlackWheel,
    wheelEdge = IpodColors.BlackWheelEdge,
    accent = IpodColors.SelectionBlueDark,
    isDarkScreen = true,
)

val U2Theme = IpodTheme(
    name = "u2",
    body = IpodColors.U2Body,
    bodyDark = IpodColors.U2BodyDark,
    wheel = IpodColors.U2Wheel,
    wheelEdge = IpodColors.U2WheelEdge,
    accent = IpodColors.U2Accent,
    isDarkScreen = true,
)

val themes = listOf(SilverTheme, BlackTheme, U2Theme)
