package com.openwhen.app.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.UnlockRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WaxSealEnvelopeCard(
    envelope: Envelope,
    isSender: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(envelope.unlockTimestamp, envelope.unlockRule) {
        now = System.currentTimeMillis()
        while (envelope.unlockTimestamp != null && envelope.isLocked(now)) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val locked = !isSender && envelope.isLocked(now)
    val consumed = !isSender && envelope.unlockRule == UnlockRule.ONE_TIME_ONLY && envelope.isOpened
    val enabled = !locked && !consumed
    val hold = remember(envelope.envelopeId) { Animatable(0f) }
    val sealBreak = remember(envelope.envelopeId) { Animatable(0f) }
    var opening by remember(envelope.envelopeId) { mutableStateOf(false) }
    var holdConfirmed by remember(envelope.envelopeId) { mutableStateOf(false) }
    var openingJob by remember(envelope.envelopeId) { mutableStateOf<Job?>(null) }
    val currentOpen by rememberUpdatedState(onOpen)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLifecycle by rememberUpdatedState(lifecycleOwner.lifecycle)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) openingJob?.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); openingJob?.cancel() }
    }
    val color = envelopeColor(envelope.envelopeColorHex)
    val sealColor = Color(0xFF90475B)
    val actionLabel = if (!isSender && envelope.unlockRule == UnlockRule.ONE_TIME_ONLY)
        words("Open this one-time letter", "افتح هذه الرسالة لمرة واحدة")
    else words("Hold to open letter", "اضغط مطولاً لفتح الرسالة")
    val openLetter: suspend () -> Unit = {
        if (!opening && enabled) {
            opening = true
            try {
                if (!isSender) kotlinx.coroutines.coroutineScope {
                    val progress = launch { hold.animateTo(1f, tween(1_500)) }
                    // A real delay preserves the deliberate hold when system animations are disabled.
                    delay(1_500)
                    progress.cancelAndJoin()
                    hold.snapTo(1f)
                }
                holdConfirmed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // The larger reveal owns the flap and paper animation after this wax handoff.
                sealBreak.animateTo(1f, tween(180))
                currentCoroutineContext().ensureActive()
                if (currentLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentOpen()
                delay(250)
            } finally {
                withContext(NonCancellable) {
                    sealBreak.snapTo(0f)
                    hold.snapTo(0f)
                    holdConfirmed = false
                    opening = false
                }
            }
        }
    }
    val stateLabel = when {
        locked -> words("Locked", "مغلقة")
        consumed -> words("Opened once", "فُتحت مرة واحدة")
        !isSender && envelope.unlockRule == UnlockRule.ONE_TIME_ONLY -> words("Sealed · opens only once", "مختومة · تُفتح مرة واحدة فقط")
        envelope.isOpened -> words("Opened", "مفتوحة")
        else -> words("Sealed", "مختومة")
    }
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = envelope.titlePrompt
        stateDescription = stateLabel
        if (enabled) customActions = listOf(CustomAccessibilityAction(actionLabel) {
            if (opening || openingJob?.isActive == true) false else {
                openingJob = scope.launch { openLetter() }
                true
            }
        })
    }) {
        Box(Modifier.fillMaxWidth().height(148.dp)
            .shadow(5.dp, RoundedCornerShape(10.dp), spotColor = color.copy(alpha = .4f))
            .clip(RoundedCornerShape(10.dp)).background(color)) {
            Canvas(Modifier.fillMaxSize()) {
                val fold = color.copy(red = (color.red * .87f).coerceIn(0f, 1f),
                    green = (color.green * .87f).coerceIn(0f, 1f), blue = (color.blue * .87f).coerceIn(0f, 1f))
                val lower = Path().apply {
                    moveTo(0f, size.height); lineTo(size.width / 2, size.height * .42f)
                    lineTo(size.width, size.height); close()
                }
                drawPath(lower, Color.White.copy(alpha = .11f))
                drawLine(fold.copy(alpha = .35f), Offset(0f, size.height), Offset(size.width / 2, size.height * .42f), 1.dp.toPx())
                drawLine(fold.copy(alpha = .35f), Offset(size.width, size.height), Offset(size.width / 2, size.height * .42f), 1.dp.toPx())
            }
            Canvas(Modifier.fillMaxWidth().height(92.dp)) {
                val triangle = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }
                drawPath(triangle, color)
                drawLine(Color.Black.copy(alpha = .075f), Offset(0f, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                drawLine(Color.Black.copy(alpha = .075f), Offset(size.width, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
            }
            Text("✧", Modifier.align(Alignment.TopEnd).padding(12.dp), color = Color.Black.copy(alpha = .3f), fontSize = 23.sp)
            Box(Modifier.align(Alignment.Center).offset(y = 12.dp).size(62.dp).testTag("wax-seal-${envelope.envelopeId}")
                .graphicsLayer {
                    alpha = 1f - sealBreak.value
                    scaleX = (1f - hold.value * .055f) * (1f - sealBreak.value * .18f)
                    scaleY = scaleX
                    rotationZ = sin(hold.value * PI * 4).toFloat() * hold.value * 1.4f - sealBreak.value * 9f
                }
                .pointerInput(envelope.envelopeId, enabled, isSender) {
                    if (enabled) {
                        if (isSender) detectTapGestures(onTap = {
                            if (!opening && openingJob?.isActive != true) openingJob = scope.launch { openLetter() }
                        }) else detectTapGestures(onPress = {
                            if (opening || openingJob?.isActive == true) return@detectTapGestures
                            kotlinx.coroutines.coroutineScope {
                                val job = launch { openLetter() }
                                openingJob = job
                                tryAwaitRelease()
                                if (!holdConfirmed) job.cancelAndJoin()
                            }
                        })
                    }
                }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                    val seal = Path()
                    val radius = size.minDimension / 2
                    repeat(80) { index ->
                        val angle = index * 2 * PI / 80
                        val ripple = radius * (1 + .055f * sin(angle * 10)).toFloat()
                        val x = center.x + cos(angle).toFloat() * ripple
                        val y = center.y + sin(angle).toFloat() * ripple
                        if (index == 0) seal.moveTo(x, y) else seal.lineTo(x, y)
                    }
                    seal.close()
                    drawPath(seal, sealColor)
                    drawCircle(Color.White.copy(alpha = .2f), radius * .72f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    if (sealBreak.value > 0f) {
                        val crack = Path().apply {
                            moveTo(center.x - radius * .1f, center.y - radius)
                            lineTo(center.x + radius * .12f, center.y - radius * .46f)
                            lineTo(center.x - radius * .1f, center.y - radius * .1f)
                            lineTo(center.x + radius * .14f, center.y + radius * .3f)
                            lineTo(center.x - radius * .05f, center.y + radius)
                        }
                        drawPath(crack, color.copy(alpha = sealBreak.value),
                            style = androidx.compose.ui.graphics.drawscope.Stroke((1f + sealBreak.value).dp.toPx()))
                    }
                }
                if (locked) Icon(Icons.Rounded.Lock, null, tint = Color(0xFFFFE8D7), modifier = Modifier.size(21.dp))
                else Text(if (consumed) "✓" else sealGlyph(envelope.sealIcon), color = Color(0xFFFFE8D7), fontSize = 24.sp)
                if (hold.value > 0f && hold.value < 1f) CircularProgressIndicator(
                    progress = { hold.value }, modifier = Modifier.fillMaxSize(), color = sealColor,
                    strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = .6f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(envelope.titlePrompt, fontFamily = FontFamily.Serif, fontSize = 17.sp,
            lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (envelope.isOpened && !locked) Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            val remaining = ((envelope.unlockTimestamp ?: now) - now).coerceAtLeast(0) / 1000
            val days = remaining / 86_400
            val status = when {
                locked && envelope.unlockTimestamp == null -> words("Waiting for its moment", "تنتظر لحظتها")
                locked && days > 0 -> words("Opens in $days days", "تُفتح بعد $days أيام")
                locked -> words("Opens in %02d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60), "تُفتح بعد %02d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60))
                consumed -> words("A memory, opened once", "ذكرى فُتحت مرة واحدة")
                !isSender && envelope.unlockRule == UnlockRule.ONE_TIME_ONLY -> words("Hold the seal · opens once", "اضغط على الختم · تُفتح مرة واحدة")
                isSender -> if (envelope.isOpened) words("Opened with love", "فُتحت بكل حب") else words("Waiting for their moment", "تنتظر لحظتها")
                envelope.isOpened -> words("Hold to read again", "اضغط لتقرأها مجدداً")
                else -> words("Hold the seal · 1.5s", "اضغط على الختم · ١٫٥ ثانية")
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
