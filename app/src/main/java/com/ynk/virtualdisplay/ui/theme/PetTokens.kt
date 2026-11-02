package com.ynk.virtualdisplay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the QQ 宠物托管 UI, following the warm crab-themed mock-ups in 图/android_ui/新.
 * The Blue* tokens are the primary accent and hold the mock-ups' green; the names stay so every screen
 * picks up the theme from here.
 */
object PetTokens {
    // :root palette
    val Bg = Color(0xFFF9F4EC)
    val Surface = Color(0xFFFFFDF9)
    val Ink = Color(0xFF3A2A1F)
    val Ink2 = Color(0xFF75655A)
    val Ink3 = Color(0xFF9A8B7D)
    val Line = Color(0xFFEFE7DC)

    val Blue = Color(0xFF4E7A55)
    val BlueSoft = Color(0xFFE8F0E6)
    val BlueInk = Color(0xFF3C6244)

    val Green = Color(0xFF12A150)
    val GreenSoft = Color(0xFFE6F6EC)
    val GreenInk = Color(0xFF0B7A3D)

    val Amber = Color(0xFFC07A3D) // --amber used for banners; text tone below
    val AmberStrong = Color(0xFFC07600)
    val AmberSoft = Color(0xFFFDF2E0)
    val AmberInk = Color(0xFF9A5F00)
    val AmberHead = Color(0xFF7A4B00)

    val Red = Color(0xFFD93B3B)
    val RedSoft = Color(0xFFFDECEC)
    val RedInk = Color(0xFFB62E2E)

    val GraySoft = Color(0xFFF3EDE4)
    val Violet = Color(0xFF7C5CD6)
    val VioletSoft = Color(0xFFF1EEFB)
    val VioletInk = Color(0xFF5B3FB0)

    // Mood dot colour from the reference S_NOW stats (not part of :root, taken from the sample row)
    val MoodDot = Color(0xFFE0567B)

    // Extras transcribed from the fixed colours the mock-ups hard-code inline
    val Chevron = Color(0xFFCFC5B8)          // .chev stroke
    val RedBorder = Color(0xFFF3D6D6)        // .btn.danger inset border
    val BarTrack = Color(0xFFEFE8DD)         // .bar background
    val FilmBorder = Color(0xFFE9E0D4)       // .shot .frame border
    val FilmTop = Color(0xFFFBF7F0)          // .frame gradient start
    val FilmBottom = Color(0xFFF3ECE1)       // .frame gradient end
    val TabInactive = Color(0xFFA39689)      // .tabbar .tb default
    val HandleGray = Color(0xFFE6DDD0)       // .handle
    val BannerTile = Color(0xFFF6E3C4)       // paused banner tile bg

    // Radii (px == dp on the 400px reference width; kept as dp for a faithful 1:1 shape)
    val CardRadius = 20.dp
    val TileRadius = 10.dp
    val PillRadius = 999.dp
    val BtnRadius = 24.dp
    val ChipRadius = 999.dp
    val NextRadius = 11.dp

    val BtnHeight = 52.dp
    val CardPad = 14.dp
}
