package com.openwhen.app.presentation.screens

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.openwhen.app.BuildConfig
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.media.RecordingState
import com.openwhen.app.presentation.components.*
import com.openwhen.app.presentation.state.CreateEnvelopeState
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateEnvelopeScreen(
    state: CreateEnvelopeState,
    recording: RecordingState,
    onUpdate: ((CreateEnvelopeState) -> CreateEnvelopeState) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (Boolean) -> Unit,
    onRemoveVoice: () -> Unit,
    onSeal: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val editingEnabled = !state.isSaving && !state.isAwaitingConfirmation
    val haptic = LocalHapticFeedback.current
    var permissionError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dirty = state.letterText.isNotBlank() || state.photoUri != null || state.voiceNoteUri != null
    val permissionGrantedMessage = words("Microphone ready. Hold again to record your message.", "الميكروفون جاهز. اضغط مطولاً مجدداً لتسجيل رسالتك.")
    val permissionDeniedMessage = words("Allow microphone access in your phone settings to record a voice note.", "اسمح بالوصول إلى الميكروفون في إعدادات هاتفك لتسجيل رسالة صوتية.")
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionError = if (granted) permissionGrantedMessage else permissionDeniedMessage
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onUpdate { it.copy(photoUri = uri.toString()) }
        }
    }
    val requestRecord: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionError = null
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onStartRecording()
        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val back: () -> Unit = { if (!state.isSaving) { if (dirty && !state.isAwaitingConfirmation) confirmDiscard = true else onBack() } }
    androidx.activity.compose.BackHandler(enabled = !state.isSaving) { back() }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(words("Leave this letter for now?", "تترك هذه الرسالة الآن؟"), fontFamily = FontFamily.Serif) },
        text = { Text(words("Your unsealed letter and attachments will be discarded.", "ستُحذف رسالتك غير المختومة ومرفقاتها.")) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(words("Discard letter", "حذف الرسالة")) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(words("Keep writing", "أكمل الكتابة")) } })
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(words("The letter studio", "استوديو الرسائل"), fontFamily = FontFamily.Serif, fontSize = 22.sp) },
                navigationIcon = { IconButton(onClick = back, enabled = !state.isSaving) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, words("Back", "رجوع"))
                } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    Button(onClick = onSeal, enabled = !state.isSaving && !recording.isRecording && state.letterText.isNotBlank() && state.titlePrompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) {
                        if (state.isSaving) { CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                        Text(when {
                            state.isSaving -> words("Keeping your love safe…", "نحفظ حبك بأمان…")
                            state.isAwaitingConfirmation -> words("Retry sealing this letter", "أعد محاولة ختم هذه الرسالة")
                            else -> words("Seal with love  ♥", "اختمها بالحب  ♥")
                        })
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Eyebrow(words("SOMETHING ONLY YOU COULD WRITE", "شيء لا يكتبه سواك"))
            Spacer(Modifier.height(9.dp))
            Text(words("For the moment\nthey need you.", "للحظة التي\nيحتاجونك فيها."), fontFamily = FontFamily.Serif, fontSize = 35.sp,
                lineHeight = 41.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            StudioLabel(words("01  ·  Choose their moment", "٠١  ·  اختر لحظتهم"))
            Spacer(Modifier.height(8.dp))
            val prompts = listOf(
                "Open when you miss me" to "افتحها عندما تشتاق إليّ",
                "Open when you can't sleep" to "افتحها عندما لا تستطيع النوم",
                "Open when you're stressed" to "افتحها عندما تشعر بالضغط",
                "Open when we had a fight" to "افتحها عندما نتخاصم")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                prompts.forEach { (english, arabic) ->
                    val label = words(english, arabic)
                    FilterChip(selected = state.titlePrompt == english || state.titlePrompt == arabic,
                        onClick = { onUpdate { it.copy(titlePrompt = label) } }, enabled = editingEnabled,
                        label = { Text(label.removePrefix("Open "), style = MaterialTheme.typography.labelMedium) })
                }
            }
            OutlinedTextField(state.titlePrompt, { value -> onUpdate { it.copy(titlePrompt = value.take(120)) } },
                label = { Text(words("Or write your own moment", "أو اكتب لحظتك الخاصة")) }, singleLine = false, maxLines = 3,
                enabled = editingEnabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(28.dp))
            StudioLabel(words("02  ·  Make it feel like you", "٠٢  ·  أضف لمستك"))
            Spacer(Modifier.height(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("#EBC4C4" to words("Rose", "وردي"), "#BFCABD" to words("Sage", "أخضر هادئ"),
                    "#D8CEE5" to words("Lavender", "لافندر"), "#EBCF9B" to words("Honey", "عسلي"),
                    "#BBD2D8" to words("Sky", "سماوي"), "#DDAB98" to words("Terracotta", "طوبي")).forEach { (hex, label) ->
                    Box(Modifier.size(48.dp).clip(CircleShape).background(envelopeColor(hex))
                        .border(if (state.envelopeColorHex == hex) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = label }
                        .clickable(enabled = editingEnabled) { onUpdate { it.copy(envelopeColorHex = hex) } }, contentAlignment = Alignment.Center) {
                        if (state.envelopeColorHex == hex) Icon(Icons.Rounded.Check, words("Selected envelope color", "لون الظرف المحدد"), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Heart" to words("Heart", "قلب"), "Star" to words("Star", "نجمة"), "Moon" to words("Moon", "قمر"),
                    "Hug" to words("Hug", "عناق"), "Coffee" to words("Coffee", "قهوة")).forEach { (icon, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(onClick = { onUpdate { it.copy(sealIcon = icon) } }, enabled = editingEnabled,
                            shape = CircleShape, color = if (state.sealIcon == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(sealGlyph(icon), fontSize = 25.sp,
                                color = if (state.sealIcon == icon) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary) }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            StudioLabel(words("03  ·  The words they'll keep", "٠٣  ·  الكلمات التي ستبقى"))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(state.letterText, { value -> onUpdate { it.copy(letterText = value.take(20_000)) } },
                placeholder = { Text(words("My love,\n\nIf you’re reading this, I wish I could be right beside you…", "حبيبي،\n\nإذا كنت تقرأ هذا، أتمنى لو كنت بجانبك الآن…"), fontFamily = FontFamily.Cursive) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Cursive, fontSize = 21.sp, lineHeight = 31.sp),
                minLines = 8, enabled = editingEnabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                supportingText = { Text("${state.letterText.length} / 20,000") })
            Spacer(Modifier.height(24.dp))
            StudioLabel(if (BuildConfig.SPARK_MODE) words("04  ·  A song to go with your words", "٠٤  ·  أغنية ترافق كلماتك")
                else words("04  ·  A memory, a voice, a song", "٠٤  ·  ذكرى وصوت وأغنية"))
            Spacer(Modifier.height(12.dp))
            if (BuildConfig.SPARK_MODE) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(words("Text letters edition · Send your words and an optional song link. Photo and voice uploads are unavailable in this edition.",
                        "نسخة الرسائل النصية · أرسل كلماتك ورابط أغنية اختياريًا. رفع الصور والرسائل الصوتية غير متاح في هذه النسخة."),
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
            if (state.photoUri != null) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Box {
                            AsyncImage(state.photoUri, words("Selected memory", "الذكرى المختارة"), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)))
                            FilledTonalIconButton(onClick = { onUpdate { it.copy(photoUri = null, photoCaption = "") } },
                                enabled = editingEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                                Icon(Icons.Rounded.Close, words("Remove photo", "حذف الصورة"))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(state.photoCaption, { value -> onUpdate { it.copy(photoCaption = value.take(240)) } },
                            placeholder = { Text(words("The story behind this memory…", "الحكاية وراء هذه الذكرى…")) },
                            enabled = editingEnabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    }
                }
            } else OutlinedButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = editingEnabled, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(10.dp)); Text(words("Tuck in a photo memory", "أرفق صورة لذكرى"))
            }
            Spacer(Modifier.height(15.dp))
            if (state.voiceNoteUri != null && !recording.isRecording) {
                VoiceNotePlayerCard(state.voiceNoteUri, state.voiceDurationSec)
                TextButton(onClick = onRemoveVoice, enabled = editingEnabled, modifier = Modifier.align(Alignment.End)) {
                    Text(words("Remove voice note", "حذف الرسالة الصوتية"))
                }
            } else {
                val recordColor by animateColorAsState(if (recording.isRecording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    label = "recording color")
                val accessibilityRecord = if (recording.isRecording) words("Finish recording", "إنهاء التسجيل") else words("Start recording", "بدء التسجيل")
                val currentStart by rememberUpdatedState(requestRecord)
                val currentStop by rememberUpdatedState(onStopRecording)
                Surface(color = recordColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        onClick(accessibilityRecord) {
                            if (editingEnabled) {
                                if (recording.isRecording) currentStop(false) else currentStart()
                            }
                            editingEnabled
                        }
                    }.pointerInput(editingEnabled) {
                        if (editingEnabled) detectTapGestures(onPress = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            currentStart()
                            if (granted) {
                                val released = tryAwaitRelease()
                                currentStop(!released)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        })
                    }) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Mic, null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (recording.isRecording) words("Your voice, their comfort", "صوتك، وطمأنينتهم") else words("Hold to record a little love", "اضغط مطولاً لتسجيل قليل من الحب"),
                                style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(3.dp))
                            Text(if (recording.isRecording) words("Release to keep · ", "ارفع إصبعك للحفظ · ") + clockText(recording.durationMs)
                                else words("Up to 5 minutes · release to save", "حتى ٥ دقائق · ارفع إصبعك للحفظ"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (recording.isRecording) AudioWaveform(true, Modifier.fillMaxWidth().height(42.dp).padding(top = 8.dp), recording.amplitude)
            }
            (recording.error ?: permissionError)?.let {
                Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(state.songUrl, { value -> onUpdate { it.copy(songUrl = value.take(2_000)) } },
                label = { Text(words("A song for them · optional", "أغنية لهم · اختياري")) },
                placeholder = { Text("https://open.spotify.com/…") },
                leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = editingEnabled, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(28.dp))
            StudioLabel(words("05  ·  Let the moment find them", "٠٥  ·  دع اللحظة تجدهم"))
            Spacer(Modifier.height(10.dp))
            val rules = listOf(UnlockRule.ANYTIME to words("Whenever they need it", "حين يحتاجونها"),
                UnlockRule.DATE_LOCKED to words("On a special date", "في موعد خاص"),
                UnlockRule.ONE_TIME_ONLY to words("A once-only moment", "لحظة لمرة واحدة"))
            rules.filter { !BuildConfig.SPARK_MODE || it.first != UnlockRule.ONE_TIME_ONLY }.forEach { (rule, label) ->
                Surface(onClick = { onUpdate { it.copy(unlockRule = rule) } }, enabled = editingEnabled,
                    color = if (state.unlockRule == rule) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.unlockRule == rule, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (state.unlockRule == UnlockRule.DATE_LOCKED) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = state.unlockTimestamp ?: (System.currentTimeMillis() + 86_400_000) }
                    DatePickerDialog(context, { _, year, month, day ->
                        calendar.set(year, month, day)
                        TimePickerDialog(context, { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute)
                            calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                            onUpdate { it.copy(unlockTimestamp = calendar.timeInMillis) }
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.minDate = System.currentTimeMillis()
                    }.show()
                }, enabled = editingEnabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
                    Text(state.unlockTimestamp?.let(::dateText) ?: words("Choose a date & time", "اختر التاريخ والوقت"))
                }
                Text(words("The letter stays sealed until this moment, in your local time.", "تبقى الرسالة مختومة حتى هذا الموعد، بتوقيتك المحلي."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.unlockRule == UnlockRule.ONE_TIME_ONLY) Text(words("They can open it once. A small moment to be fully present.", "يمكنهم فتحها مرة واحدة. لحظة صغيرة يعيشونها بكامل إحساسهم."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(30.dp))
            Text(words("Some words become a place to come home to.", "بعض الكلمات تصبح بيتاً نعود إليه."),
                fontFamily = FontFamily.Serif, fontSize = 18.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun StudioLabel(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}
