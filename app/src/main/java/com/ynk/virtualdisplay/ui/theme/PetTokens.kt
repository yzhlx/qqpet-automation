package com.ynk.virtualdisplay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the QQ 宠物托管 v0.1 UI.
 *
 * Every value here is transcribed directly from the CSS custom properties and fixed sizes in
 * 参考ui/redesign-v0.1/ui-v2.html (:root block, .card, .btn, .pill, .chip). Nothing is invented:
 * the palette is the reference blue/green/amber/red system, not Material dynamic colour, so the
 * app renders identically on every device and matches the approved mock-ups.
 */
object PetTokens {
    // :root palette
    val Bg = Color(0xFFF2F4F9)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111827)
    val Ink2 = Color(0xFF6B7280)
    val Ink3 = Color(0xFF9CA3AF)
    val Line = Color(0xFFEAECF2)

    val Blue = Color(0xFF2C63D4)
    val BlueSoft = Color(0xFFE9F0FD)
    val BlueInk = Color(0xFF1E4FB0)

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

    val GraySoft = Color(0xFFF1F3F7)
    val Violet = Color(0xFF7C5CD6)
    val VioletSoft = Color(0xFFF1EEFB)
    val VioletInk = Color(0xFF5B3FB0)

    // Mood dot colour from the reference S_NOW stats (not part of :root, taken from the sample row)
    val MoodDot = Color(0xFFE0567B)

    // Extras transcribed from the fixed colours the mock-ups hard-code inline
    val Chevron = Color(0xFFC3C8D2)          // .chev stroke
    val RedBorder = Color(0xFFF3D6D6)        // .btn.danger inset border
    val BarTrack = Color(0xFFEDEFF4)         // .bar background
    val FilmBorder = Color(0xFFE4E7EE)       // .shot .frame border
    val FilmTop = Color(0xFFF7F9FC)          // .frame gradient start
    val FilmBottom = Color(0xFFEDF1F8)       // .frame gradient end
    val TabInactive = Color(0xFF9AA0AC)      // .tabbar .tb default
    val HandleGray = Color(0xFFDFE3EA)       // .handle
    val BannerTile = Color(0xFFF6E3C4)       // paused banner tile bg

    // Radii (px == dp on the 400px reference width; kept as dp for a faithful 1:1 shape)
    val CardRadius = 17.dp
    val TileRadius = 10.dp
    val PillRadius = 999.dp
    val BtnRadius = 15.dp
    val ChipRadius = 999.dp
    val NextRadius = 11.dp

    val BtnHeight = 50.dp
    val CardPad = 14.dp
}
