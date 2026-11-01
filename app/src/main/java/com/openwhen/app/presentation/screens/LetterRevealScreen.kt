package com.openwhen.app.presentation.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.openwhen.app.media.SparkPhotoPolicy
import com.openwhen.app.media.VoiceNotePlayer
import com.openwhen.app.presentation.components.*
import com.openwhen.app.presentation.state.OpenEnvelopeState
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterRevealScreen(
    state: OpenEnvelopeState,
    currentUserId: String?,
    senderName: String,
    onBack: () -> Unit,
    onReact: (String) -> Unit,
) {
    val envelope = state.envelope
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(words("A little piece of my heart", "جزء صغير من قلبي"), fontFamily = FontFamily.Serif, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.isLoading) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, words("Back", "رجوع")) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(18.dp))
                    Text(words("Unfolding something just for you…", "نكشف شيئاً كُتب لك وحدك…"), fontFamily = FontFamily.Serif, fontSize = 20.sp)
                }
            }
        } else if (envelope == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error ?: words("This letter is resting for now.", "هذه الرسالة تستريح الآن."), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(words("Back to our letters", "العودة إلى رسائلنا")) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow(words("WRITTEN WITH YOU IN MIND", "كُتبت وأنت في البال"))
                Spacer(Modifier.height(12.dp))
                Text(envelope.titlePrompt, fontFamily = FontFamily.Serif, fontSize = 31.sp, lineHeight = 38.sp,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                PaperSurface(Modifier.fillMaxWidth().widthIn(max = 650.dp)) {
                    Column(Modifier.padding(horizontal = 25.dp, vertical = 32.dp)) {
                        Text(sealGlyph(envelope.sealIcon), color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
                        Spacer(Modifier.height(24.dp))
                        SelectionContainer {
                            Text(envelope.letterText, fontFamily = FontFamily.Cursive, fontSize = 23.sp, lineHeight = 36.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(30.dp))
                        Text(words("Always, yours.", "دائماً، لك."), fontFamily = FontFamily.Cursive, fontSize = 25.sp,
                            color = MaterialTheme.colorScheme.primary)
                        Text(senderName, fontFamily = FontFamily.Serif, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                envelope.photoUrl?.let { url ->
                    Spacer(Modifier.height(34.dp))
                    PolaroidPhoto(url, envelope.photoCaption.orEmpty(), Modifier.widthIn(max = 330.dp).fillMaxWidth(.9f))
                    Spacer(Modifier.height(12.dp))
                }
                envelope.voiceNoteUrl?.let { url ->
                    Spacer(Modifier.height(26.dp))
                    VoiceNotePlayerCard(url, envelope.voiceDurationSec)
                }
                envelope.songUrl?.takeIf { it.isNotBlank() }?.let { SongLink(it) }
                if (envelope.receiverId == currentUserId) {
                    Spacer(Modifier.height(32.dp))
                    Text(words("Send a little feeling back", "أرسل شعوراً صغيراً بالمقابل"), fontFamily = FontFamily.Serif, fontSize = 23.sp)
                    Spacer(Modifier.height(12.dp))
                    val reactions = listOf(
                        words("Sending a hug 🫂", "أرسل لك عناقاً 🫂"),
                        words("Made me smile ❤️", "رسمت ابتسامتي ❤️"),
                        words("I miss you too 🥺", "أشتاق إليك أيضاً 🥺"))
                    reactions.forEach { reaction ->
                        FilterChip(selected = envelope.partnerReaction == reaction, onClick = { onReact(reaction) },
                            enabled = !state.isReacting, label = { Text(reaction, Modifier.padding(vertical = 4.dp)) },
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (state.isReacting) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else envelope.partnerReaction?.let { reaction ->
                    Spacer(Modifier.height(24.dp))
                    Text(words("Their little reply", "ردّهما الصغير"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(reaction, fontFamily = FontFamily.Serif, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                }
                state.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(32.dp))
                Text("♥", color = MaterialTheme.colorScheme.primary.copy(alpha = .5f), fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PolaroidPhoto(url: String, caption: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inlinePhoto = url.startsWith("data:")
    val photoBytes = remember(url) {
        if (inlinePhoto) runCatching { SparkPhotoPolicy.decode(url) }.getOrNull() else null
    }
    val image = remember(context, url, photoBytes) {
        ImageRequest.Builder(context).data(if (inlinePhoto) photoBytes else url).apply {
            if (inlinePhoto) {
                // A sealed photo belongs to this reveal, not the shared image cache after sign-out.
                memoryCachePolicy(CachePolicy.DISABLED)
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }.build()
    }
    Surface(modifier.rotate(-3f), color = Color(0xFFFFFEFA), shadowElevation = 8.dp, shape = RoundedCornerShape(2.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            var failed by remember(url) { mutableStateOf(false) }
            var loading by remember(url) { mutableStateOf(true) }
            if (failed || (inlinePhoto && photoBytes == null)) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Text(words("A memory worth keeping ♡\nPhoto unavailable", "ذكرى تستحق أن تبقى ♡\nالصورة غير متاحة"), textAlign = TextAlign.Center,
                    color = Color(0xFF783D51), fontFamily = FontFamily.Serif)
            } else Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                AsyncImage(model = image, contentDescription = caption.ifBlank { words("A memory from your partner", "ذكرى من شريكك") },
                    contentScale = ContentScale.Crop, onError = { failed = true; loading = false }, onSuccess = { loading = false },
                    modifier = Modifier.fillMaxSize())
                if (loading) CircularProgressIndicator(Modifier.size(26.dp), color = Color(0xFF783D51), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(13.dp))
            Text(caption.ifBlank { "♡" }, fontFamily = FontFamily.Cursive, fontSize = 22.sp,
                textAlign = TextAlign.Center, color = Color(0xFF59494A), modifier = Modifier.padding(bottom = 9.dp))
        }
    }
}

@Composable
fun VoiceNotePlayerCard(url: String, durationSec: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val player = remember(context, url, owner) { VoiceNotePlayer(context) }
    val playback by player.state.collectAsStateWithLifecycle()
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(19.dp)) {
            Text(words("A little closer. Press play.", "أقرب قليلاً. اضغط واستمع."), fontFamily = FontFamily.Serif, fontSize = 21.sp)
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { player.toggle(url) }, enabled = !playback.isPreparing,
                    modifier = Modifier.size(50.dp), shape = CircleShape) {
                    if (playback.isPreparing) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.isPlaying) words("Pause voice note", "إيقاف مؤقت") else words("Play voice note", "تشغيل الرسالة الصوتية"))
                }
                Spacer(Modifier.width(16.dp))
                AudioWaveform(playback.isPlaying, Modifier.weight(1f).height(42.dp))
                Spacer(Modifier.width(12.dp))
                Text(clockText(if (playback.durationMs > 0) (playback.durationMs - playback.positionMs).toLong() else durationSec * 1_000L),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (playback.durationMs > 0) Slider(value = playback.positionMs.toFloat(), onValueChange = { player.seekTo(it.toInt()) },
                valueRange = 0f..playback.durationMs.toFloat(), modifier = Modifier.fillMaxWidth())
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun AudioWaveform(playing: Boolean, modifier: Modifier = Modifier, amplitude: Float = .5f) {
    val phase = if (playing) {
        val transition = rememberInfiniteTransition(label = "voice waveform")
        val value by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "wave phase")
        value
    } else 0f
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val count = 28
        val spacing = size.width / count
        repeat(count) { index ->
            val wave = if (playing) abs(sin(index * .8f + phase)) * (.45f + amplitude * .5f)
                else .12f + abs(sin(index * 1.7f)) * .5f
            val height = size.height * (.12f + wave * .8f)
            drawRoundRect(color.copy(alpha = if (playing) .85f else .45f),
                topLeft = Offset(index * spacing, (size.height - height) / 2),
                size = Size((spacing * .45f).coerceAtLeast(1f), height), cornerRadius = CornerRadius(6f))
        }
    }
}

@Composable
private fun SongLink(url: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val valid = uri?.scheme?.lowercase() in setOf("https", "http", "spotify")
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = {
        if (valid) try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { error = true }
    }, enabled = valid, shape = RoundedCornerShape(16.dp)) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(words("This song made me think of you", "هذه الأغنية ذكّرتني بك"))
    }
    if (error || !valid) Text(words("This song link could not be opened.", "تعذّر فتح رابط الأغنية."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
