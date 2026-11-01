package com.openwhen.app.presentation.screens

import android.app.DatePickerDialog
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.User
import com.openwhen.app.presentation.components.Eyebrow
import com.openwhen.app.presentation.components.words
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun PairingScreen(
    user: User?,
    space: CoupleSpace?,
    isDemo: Boolean,
    busy: Boolean,
    error: String?,
    onAnonymous: (String) -> Unit,
    onEmail: (String, String, String, Boolean) -> Unit,
    onGoogle: () -> Unit,
    onCreateSpace: (Long) -> Unit,
    onJoinSpace: (String) -> Unit,
    onSignOut: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailMode by rememberSaveable { mutableStateOf(false) }
    var createAccount by rememberSaveable { mutableStateOf(true) }
    var invite by rememberSaveable { mutableStateOf("") }
    var anniversary by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Text(words("Back to our letters", "العودة إلى رسائلنا"))
        }
        Eyebrow(words("A LITTLE CLOSER, ALWAYS", "أقرب إليك، دائماً"))
        Spacer(Modifier.height(18.dp))
        Text("Open when…", fontFamily = FontFamily.Serif, fontSize = 43.sp,
            color = MaterialTheme.colorScheme.primary, lineHeight = 49.sp)
        Spacer(Modifier.height(12.dp))
        Text(words("For every feeling,\na little piece of us.", "لكل شعور،\nجزء صغير منّا."),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.height(178.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            MiniEnvelope(Modifier.size(160.dp, 102.dp).offset(x = (-26).dp).rotate(-13f), Color(0xFFBFCABD))
            MiniEnvelope(Modifier.size(160.dp, 102.dp).offset(x = 20.dp, y = 12.dp).rotate(9f), Color(0xFFE8BDB5))
        }
        if (isDemo) {
            SuggestionChip(onClick = {}, label = { Text(words("A cozy demo · your own little preview", "تجربة دافئة · لمحتك الخاصة")) })
        }
        if (user == null) {
            Text(words("Begin your story", "ابدآ حكايتكما"), fontFamily = FontFamily.Serif,
                fontSize = 28.sp, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text(words("Your name", "اسمك")) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            if (emailMode) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(email, { email = it }, label = { Text(words("Email", "البريد الإلكتروني")) },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(password, { password = it }, label = { Text(words("Password", "كلمة المرور")) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                TextButton(onClick = { createAccount = !createAccount }, enabled = !busy) {
                    Text(if (createAccount) words("Already have an account? Sign in", "لديك حساب؟ سجّل الدخول") else words("New here? Create an account", "أول مرة هنا؟ أنشئ حساباً"))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (emailMode) onEmail(name.trim(), email.trim(), password, createAccount) else onAnonymous(name.trim())
            }, enabled = !busy && ((emailMode && !createAccount) || name.isNotBlank()) && (!emailMode || (email.isNotBlank() && password.length >= 6)),
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else { Text(words("Make room for love", "افسح مكاناً للحب")); Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
            }
            TextButton(onClick = { emailMode = !emailMode }, enabled = !busy) {
                Text(if (emailMode) words("Continue as a guest", "المتابعة كضيف") else words("Continue with email", "المتابعة بالبريد الإلكتروني"))
            }
            OutlinedButton(onClick = onGoogle, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(words("Continue with Google", "المتابعة باستخدام Google"))
            }
            Spacer(Modifier.height(14.dp))
            Text(words("Guest accounts stay on this device. Use email or Google to keep access when changing phones.",
                "يبقى حساب الضيف على هذا الجهاز. استخدم البريد أو Google للاحتفاظ بحسابك عند تغيير هاتفك."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        } else {
            Text(words("A space for ${user.name}", "مساحة لـ ${user.name}"), fontFamily = FontFamily.Serif, fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text(words("Two people. One small world.", "شخصان. وعالم صغير يجمعهما."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (space == null) {
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = anniversary }
                    DatePickerDialog(context, { _, year, month, day ->
                        anniversary = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text(words("Our story began · ", "بدأت حكايتنا · ") + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(anniversary)))
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onCreateSpace(anniversary) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(words("Create our space", "أنشئ مساحتنا"))
                }
                Spacer(Modifier.height(22.dp))
                HorizontalDivider()
                Spacer(Modifier.height(18.dp))
                Text(words("Have your partner’s invitation?", "لديك دعوة من شريكك؟"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(invite, { value -> invite = value.mapNotNull(Char::digitToIntOrNull).joinToString("").take(6) },
                    label = { Text(words("6-digit invitation code", "رمز الدعوة من ٦ أرقام")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), enabled = !busy)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onJoinSpace(invite) }, enabled = !busy && invite.length == 6,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(words("Join their space", "انضم إلى مساحتهما"))
                }
            } else {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(words("Your invitation to us", "دعوتك إلى عالمنا"), fontFamily = FontFamily.Serif, fontSize = 22.sp)
                        Spacer(Modifier.height(15.dp))
                        Text(space.inviteCode, fontSize = 36.sp, letterSpacing = 7.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        Text(words("Send this code to your person. Their letters will find a home here.", "أرسل هذا الرمز إلى شريكك. هنا ستجد رسائلكما بيتاً."),
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        val shareText = words("I made a little space for us on Open When… Join me with the code ${space.inviteCode} 💌", "صنعت لنا مساحة صغيرة في Open When… انضم إليّ بالرمز ${space.inviteCode} 💌")
                        Button(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText)
                            }, null))
                        }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(words("Share our code", "شارك رمزنا")) }
                    }
                }
            }
            if (busy) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            TextButton(onClick = onSignOut, enabled = !busy) { Text(words("Sign out", "تسجيل الخروج")) }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MiniEnvelope(modifier: Modifier, color: Color) {
    Surface(modifier, color = color, shadowElevation = 8.dp, shape = RoundedCornerShape(8.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val flap = Path().apply { moveTo(0f, 0f); lineTo(size.width / 2, size.height * .65f); lineTo(size.width, 0f) }
                drawPath(flap, Color.Black.copy(alpha = .09f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            Surface(color = Color(0xFF90475B), shape = androidx.compose.foundation.shape.CircleShape) {
                Text("♥", Modifier.padding(9.dp), color = Color(0xFFFFE8D7), fontSize = 19.sp)
            }
        }
    }
}
