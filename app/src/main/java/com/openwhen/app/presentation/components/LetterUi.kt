package com.openwhen.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun words(english: String, arabic: String): String =
    if (LocalConfiguration.current.locales[0].language == "ar") arabic else english

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun PaperSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.surface
    val grain = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(background)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 18.dp.toPx()
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else step / 2
                while (x < size.width) {
                    drawCircle(grain, .65.dp.toPx(), Offset(x, y))
                    x += step
                }
                row++
                y += step
            }
        }
        content()
    }
}

internal fun envelopeColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFFE8BDB5))

internal fun sealGlyph(icon: String): String = when (icon.lowercase()) {
    "star" -> "✦"
    "moon" -> "☾"
    "hug" -> "∞"
    "coffee" -> "☕"
    else -> "♥"
}

internal fun clockText(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun dateText(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
