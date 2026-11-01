package com.crsmthw.hingegate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material 3 Expressive with Material You colours, following the system's light/dark mode. No
 * version check: dynamic colour exists from Android 12, which is the minimum. Not remembered: building the
 * scheme only reads the system's colour resources, so there is nothing worth caching.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HingegateTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
