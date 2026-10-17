package dev.imaizentarou.autotube.phone.ui

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

/** 端末の壁紙色（Android 12 以降）に合わせる。無ければ YouTube 寄りの赤を軸にした配色。 */
@Composable
fun AutoTubeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFFFB4A9), secondary = Color(0xFFE7BDB6))
        else -> lightColorScheme(primary = Color(0xFFB3261E), secondary = Color(0xFF775651))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
