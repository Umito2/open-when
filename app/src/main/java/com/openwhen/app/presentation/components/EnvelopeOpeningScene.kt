package com.openwhen.app.presentation.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A decorative, progress-driven scene. Private letter content never enters this illustration. */
@Composable
fun EnvelopeOpeningScene(
    envelopeColorHex: String,
    sealIcon: String,
    progress: Float,
    waiting: Boolean,
    modifier: Modifier = Modifier,
) {
    val envelope = envelopeColor(envelopeColorHex)
    val colors = MaterialTheme.colorScheme
    val paper = if (colors.surface.luminance() < .3f) Color(0xFF44343D) else Color(0xFFFFFAEF)
    val paperInk = colors.onSurface
    val accent = colors.primary
    val wax = Color(0xFF91465E)
    val glyph = sealGlyph(sealIcon)
    val glyphPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 25f
            color = Color(0xFFFFE5D7).toArgb()
            typeface = android.graphics.Typeface.SERIF
        }
    }
    Box(modifier.widthIn(max = 360.dp).aspectRatio(1f).clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width, size.height) / 360f
            withTransform({
                translate((size.width - 360f * scale) / 2f, (size.height - 360f * scale) / 2f)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                val p = progress.coerceIn(0f, 1f)
                val split = openingStage(p, .02f, .31f)
                val unfold = openingStage(p, .13f, .59f)
                val rise = openingStage(p, .37f, .9f)
                val settle = openingStage(p, .8f, 1f)
                val left = 39f
                val top = 141f + settle * 23f
                val width = 282f
                val height = 173f
                val middle = left + width / 2f
                val flapTip = top + cos(unfold * PI).toFloat() * 116f
                val edge = lerp(envelope, Color.Black, .13f)
                val glowAlpha = if (waiting) .13f else .08f

                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = glowAlpha), accent.copy(alpha = 0f)),
                        center = Offset(180f, 183f), radius = 168f,
                    ),
                    radius = 168f, center = Offset(180f, 183f),
                )
                drawOval(Color.Black.copy(alpha = .065f * (1f - settle * .3f)),
                    topLeft = Offset(54f, 315f + settle * 18f), size = Size(252f, 18f))

                // The open flap sits behind the rising sheet, with its hinge at the envelope mouth.
                drawRoundRect(lerp(envelope, Color.Black, .07f), Offset(left, top), Size(width, height), CornerRadius(10f))
                if (unfold >= .5f) {
                    drawOpeningFlap(left, top, width, flapTip, lerp(envelope, Color.White, .13f), edge)
                }

                val paperLeft = left + 21f
                val paperTop = top + 17f - rise * 156f
                val paperWidth = width - 42f
                val paperHeight = 191f
                clipRect(left = 0f, top = 0f, right = 360f, bottom = top + height - 2f) {
                    rotate(-2f * rise, Offset(middle, paperTop + paperHeight / 2f)) {
                        drawRoundRect(Color.Black.copy(alpha = .08f), Offset(paperLeft + 3f, paperTop + 6f),
                            Size(paperWidth, paperHeight), CornerRadius(7f))
                        drawRoundRect(paper, Offset(paperLeft, paperTop), Size(paperWidth, paperHeight), CornerRadius(7f))
                        drawRoundRect(paperInk.copy(alpha = .065f), Offset(paperLeft, paperTop),
                            Size(paperWidth, paperHeight), CornerRadius(7f), style = Stroke(.8f))
                        for (row in 0..7) {
                            for (column in 0..9) {
                                drawCircle(paperInk.copy(alpha = .035f), .45f,
                                    Offset(paperLeft + 13f + column * 23f, paperTop + 13f + row * 24f))
                            }
                        }
                        drawOpeningHeart(Offset(middle, paperTop + 39f), 10f, accent.copy(alpha = .7f))
                        for (line in 0..3) {
                            val y = paperTop + 72f + line * 19f
                            val lineWidth = if (line == 3) 111f else 172f - (line % 2) * 15f
                            drawLine(paperInk.copy(alpha = .15f), Offset(paperLeft + 31f, y),
                                Offset(paperLeft + 31f + lineWidth, y), 2.1f)
                        }
                        drawLine(accent.copy(alpha = .22f), Offset(paperLeft + 134f, paperTop + 165f),
                            Offset(paperLeft + 203f, paperTop + 165f), 2f)
                    }
                }

                // The pocket masks the sheet until it has actually risen above the opening.
                drawRoundRect(envelope, Offset(left, top), Size(width, height), CornerRadius(10f))
                val sideShade = lerp(envelope, Color.Black, .055f)
                val leftFold = Path().apply {
                    moveTo(left, top + 3f); lineTo(middle + 9f, top + height * .57f)
                    lineTo(left, top + height - 6f); close()
                }
                val rightFold = Path().apply {
                    moveTo(left + width, top + 3f); lineTo(middle - 9f, top + height * .57f)
                    lineTo(left + width, top + height - 6f); close()
                }
                drawPath(leftFold, sideShade)
                drawPath(rightFold, sideShade.copy(alpha = .7f))
                val lowerFold = Path().apply {
                    moveTo(left + 3f, top + height - 4f); lineTo(middle, top + height * .43f)
                    lineTo(left + width - 3f, top + height - 4f); close()
                }
                drawPath(lowerFold, lerp(envelope, Color.White, .1f))
                drawLine(edge.copy(alpha = .25f), Offset(left + 4f, top + height - 4f),
                    Offset(middle, top + height * .43f), 1f)
                drawLine(edge.copy(alpha = .25f), Offset(left + width - 4f, top + height - 4f),
                    Offset(middle, top + height * .43f), 1f)
                drawLine(edge.copy(alpha = .2f), Offset(left + 6f, top), Offset(left + width - 6f, top), .9f)
                if (unfold < .5f) drawOpeningFlap(left, top, width, flapTip, envelope, edge)

                val waxAlpha = 1f - openingStage(p, .18f, .4f)
                val sealCenter = Offset(middle, top + 88f)
                if (waxAlpha > 0f) {
                    for (half in listOf(-1f, 1f)) {
                        translate(half * split * 24f, split * split * 21f) {
                            rotate(half * split * 19f, sealCenter) {
                                val cut = waxHalf(sealCenter, half < 0f)
                                clipPath(cut) {
                                    drawCircle(Color.Black.copy(alpha = waxAlpha * .12f), 28f, sealCenter + Offset(1f, 3f))
                                    drawPath(waxOutline(sealCenter, 27f), wax.copy(alpha = waxAlpha))
                                    drawCircle(Color.White.copy(alpha = waxAlpha * .23f), 20f, sealCenter, style = Stroke(1.1f))
                                    glyphPaint.alpha = (255f * waxAlpha).toInt()
                                    drawIntoCanvas {
                                        val metrics = glyphPaint.fontMetrics
                                        it.nativeCanvas.drawText(glyph, sealCenter.x,
                                            sealCenter.y - (metrics.ascent + metrics.descent) / 2f, glyphPaint)
                                    }
                                }
                            }
                        }
                    }
                }

                val flutter = openingStage(p, .2f, .96f)
                val particleAlpha = sin(flutter * PI).toFloat().coerceIn(0f, 1f)
                if (particleAlpha > 0f) repeat(8) { index ->
                    val side = if (index % 2 == 0) -1f else 1f
                    val x = 180f + side * (78f + index / 2 * 15f + flutter * 24f)
                    val y = 178f - index / 2 * 17f - flutter * (50f + index * 3f)
                    val radius = 3.5f + index % 3
                    val tint = accent.copy(alpha = particleAlpha * (.45f + index % 2 * .2f))
                    if (index % 3 == 0) drawOpeningHeart(Offset(x, y), radius, tint)
                    else {
                        drawLine(tint, Offset(x - radius, y), Offset(x + radius, y), 1.25f)
                        drawLine(tint, Offset(x, y - radius), Offset(x, y + radius), 1.25f)
                    }
                }
            }
        }
    }
}

private fun openingStage(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun DrawScope.drawOpeningFlap(left: Float, top: Float, width: Float, tip: Float, color: Color, edge: Color) {
    val flap = Path().apply {
        moveTo(left + 1f, top); lineTo(left + width - 1f, top)
        lineTo(left + width / 2f, tip); close()
    }
    drawPath(flap, color)
    drawLine(edge.copy(alpha = .33f), Offset(left + 1f, top), Offset(left + width / 2f, tip), 1f)
    drawLine(edge.copy(alpha = .33f), Offset(left + width - 1f, top), Offset(left + width / 2f, tip), 1f)
}

private fun DrawScope.drawOpeningHeart(center: Offset, radius: Float, color: Color) {
    val heart = Path().apply {
        moveTo(center.x, center.y + radius)
        cubicTo(center.x - radius * 2f, center.y - radius * .15f, center.x - radius * .9f,
            center.y - radius * 1.35f, center.x, center.y - radius * .5f)
        cubicTo(center.x + radius * .9f, center.y - radius * 1.35f, center.x + radius * 2f,
            center.y - radius * .15f, center.x, center.y + radius)
        close()
    }
    drawPath(heart, color)
}

private fun waxOutline(center: Offset, radius: Float): Path = Path().apply {
    repeat(72) { index ->
        val angle = index * 2 * PI / 72
        val ripple = radius * (1f + .045f * sin(angle * 9).toFloat())
        val x = center.x + cos(angle).toFloat() * ripple
        val y = center.y + sin(angle).toFloat() * ripple
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun waxHalf(center: Offset, left: Boolean): Path = Path().apply {
    val outside = if (left) center.x - 40f else center.x + 40f
    moveTo(outside, center.y - 40f)
    lineTo(center.x - 2f, center.y - 40f)
    lineTo(center.x + 3f, center.y - 18f)
    lineTo(center.x - 3f, center.y - 7f)
    lineTo(center.x + 3f, center.y + 4f)
    lineTo(center.x - 3f, center.y + 16f)
    lineTo(center.x + 2f, center.y + 40f)
    lineTo(outside, center.y + 40f)
    close()
}
