package com.rockmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// RockCast desktop palette. Keeping these values in one place makes the two
// clients feel like the same product even though their layouts are different.
private val RockBackground = Color(0xFF1A1410)
private val RockPanel = Color(0xFF241C16)
private val RockPanelElevated = Color(0xFF2E241C)
private val RockText = Color(0xFFE8DCC8)
private val RockMuted = Color(0xFF9A8B78)
private val RockAccent = Color(0xFFC45C26)
private val RockAccentBright = Color(0xFFE58E3B)
private val RockLine = Color(0xFF3A2E24)

private val RockDarkColors = darkColorScheme(
    primary = RockAccent,
    onPrimary = RockBackground,
    primaryContainer = Color(0xFF733314),
    onPrimaryContainer = Color(0xFFFFDCC5),
    secondary = RockAccentBright,
    onSecondary = RockBackground,
    secondaryContainer = RockPanelElevated,
    onSecondaryContainer = RockText,
    background = RockBackground,
    onBackground = RockText,
    surface = RockPanel,
    onSurface = RockText,
    surfaceVariant = RockPanelElevated,
    onSurfaceVariant = RockMuted,
    outline = RockLine,
    outlineVariant = Color(0xFF4B3930),
    error = Color(0xFFE57373),
    onError = RockBackground,
)

@Composable
fun RockmobileTheme(content: @Composable () -> Unit) {
    // RockCast is intentionally dark. The light branch only keeps previews and
    // unusual system configurations readable without changing the product UI.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) RockDarkColors else lightColorScheme(
            primary = RockAccent,
            onPrimary = Color.White,
            background = RockBackground,
            onBackground = RockText,
            surface = RockPanel,
            onSurface = RockText,
            surfaceVariant = RockPanelElevated,
            onSurfaceVariant = RockMuted,
            outline = RockLine,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
