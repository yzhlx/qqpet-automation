package com.ynk.virtualdisplay.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = PetTokens.Blue,
    onPrimary = Color.White,
    secondary = PetTokens.Blue,
    onSecondary = Color.White,
    tertiary = PetTokens.Green,
    onTertiary = Color.White,
    background = PetTokens.Bg,
    onBackground = PetTokens.Ink,
    surface = PetTokens.Surface,
    onSurface = PetTokens.Ink,
    surfaceVariant = PetTokens.GraySoft,
    onSurfaceVariant = PetTokens.Ink2,
    outline = PetTokens.Line,
    error = PetTokens.Red,
    onError = Color.White,
    errorContainer = PetTokens.RedSoft,
    onErrorContainer = PetTokens.RedInk,
)

@Composable
fun MyApplicationTheme(
    // v0.1 ships the light reference design only; the mock-ups have no dark variant.
    darkTheme: Boolean = false,
    // The v0.1 UI is pinned to the approved reference palette (PetTokens), not Material dynamic
    // colour, so every screen matches 参考ui/redesign-v0.1 exactly on all devices.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}