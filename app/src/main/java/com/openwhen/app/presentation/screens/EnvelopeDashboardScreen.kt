package com.openwhen.app.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.presentation.components.Eyebrow
import com.openwhen.app.presentation.components.WaxSealEnvelopeCard
import com.openwhen.app.presentation.components.words
import com.openwhen.app.presentation.state.DashboardUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvelopeDashboardScreen(
    state: DashboardUiState,
    onCreate: () -> Unit,
    onOpen: (Envelope) -> Unit,
    onTabSelected: (Boolean) -> Unit,
    onPairing: () -> Unit,
    onMood: (String) -> Unit,
    onSignOut: () -> Unit,
    alert: String? = null,
    onAlertDismiss: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val latest = state.envelopes.filter { it.senderId == state.user?.uid && it.isOpened }
        .maxByOrNull { it.lastOpenedAt ?: 0L }
    val partnerName = state.partner?.name?.takeIf { it.isNotBlank() } ?: words("Your person", "شريكك")
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate,
                expanded = true, icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(words("Leave a little love", "اترك قليلاً من الحب")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary)
        }) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(152.dp), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(words("YOUR LITTLE WORLD", "عالمكما الصغير"))
                        Spacer(Modifier.height(5.dp))
                        Text("Open when…", fontFamily = FontFamily.Serif, fontSize = 35.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Box {
                        FilledTonalIconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreHoriz, words("Our space options", "خيارات مساحتنا"))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(words("Our invitation", "دعوتنا")) }, onClick = { menu = false; onPairing() })
                            DropdownMenuItem(text = { Text(words("Sign out", "تسجيل الخروج")) }, onClick = { menu = false; onSignOut() })
                        }
                    }
                }
            }
            if (alert != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("💌", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(alert, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = onAlertDismiss) { Icon(Icons.Rounded.Close, words("Dismiss", "إغلاق"), Modifier.size(18.dp)) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Rounded.FavoriteBorder, null, Modifier.padding(14.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(15.dp))
                            Column {
                                Text(words("${state.daysTogether} days of us", "${state.daysTogether} يوماً معاً"),
                                    fontFamily = FontFamily.Serif, fontSize = 25.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(words("${state.user?.name.orEmpty()} & $partnerName", "${state.user?.name.orEmpty()} و $partnerName"),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        Spacer(Modifier.height(15.dp))
                        val activity = if (latest != null) words("$partnerName opened “${latest.titlePrompt}”", "$partnerName فتح «${latest.titlePrompt}»")
                            else words("A little love, waiting for the right moment.", "قليل من الحب، ينتظر اللحظة المناسبة.")
                        Text(activity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        latest?.partnerReaction?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                        state.partner?.currentMood?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(words("Their little feeling: $it", "شعورهما الآن: $it"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.isDemo) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(words("DEMO SPACE · letters to explore, feelings to keep", "مساحة تجريبية · رسائل لتكتشفها ومشاعر لتحتفظ بها"),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            if (state.space?.user2Id.isNullOrBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedCard(onClick = onPairing, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text(words("There’s room for your person", "هناك مكان لشريكك"), fontFamily = FontFamily.Serif, fontSize = 20.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(words("Share your invitation and start your collection together →", "شارك دعوتك وابدآ مجموعة رسائلكما معاً ←"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(words("How’s your heart today?", "كيف حال قلبك اليوم؟"), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(9.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("❤️", "🥺", "☁️", "🌙", "☀️").forEach { mood ->
                            FilterChip(selected = state.user?.currentMood == mood, onClick = { onMood(mood) }, label = { Text(mood, fontSize = 18.sp) })
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    TabRow(selectedTabIndex = if (state.showSent) 1 else 0,
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.primary) {
                        Tab(!state.showSent, { onTabSelected(false) }, text = {
                            Text(words("For me", "لي") + "  ${state.inbox.size}", fontWeight = FontWeight.Medium)
                        })
                        Tab(state.showSent, { onTabSelected(true) }, text = {
                            Text(words("From me", "منّي") + "  ${state.sent.size}", fontWeight = FontWeight.Medium)
                        })
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(if (state.showSent) words("Pieces of your heart", "أجزاء من قلبك") else words("A letter for every feeling", "رسالة لكل شعور"),
                        fontFamily = FontFamily.Serif, fontSize = 25.sp)
                }
            }
            if (state.isLoading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (!state.isLoading && state.visibleEnvelopes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MailOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(words("Beautiful things take a little time.", "الأشياء الجميلة تحتاج بعض الوقت."), fontFamily = FontFamily.Serif,
                        fontSize = 23.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(words("Leave the first letter in your little world.", "اترك أول رسالة في عالمكما الصغير."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            items(state.visibleEnvelopes, key = { it.envelopeId }) { envelope ->
                WaxSealEnvelopeCard(envelope, envelope.senderId == state.user?.uid, onOpen = { onOpen(envelope) })
            }
        }
    }
}
