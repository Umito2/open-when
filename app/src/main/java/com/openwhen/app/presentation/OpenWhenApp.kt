package com.openwhen.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwhen.app.R
import com.openwhen.app.BuildConfig
import com.openwhen.app.notification.LetterNotificationSettingsCard
import com.openwhen.app.update.AppUpdateHost
import com.openwhen.app.update.AppUpdateSettingsCard
import com.openwhen.app.update.AppUpdateViewModel
import com.openwhen.app.presentation.screens.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OpenWhenApp(viewModel: EnvelopeViewModel = hiltViewModel(), inboxRequest: Int = 0) {
    val updateViewModel: AppUpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val updatesEnabled = BuildConfig.SPARK_MODE && !BuildConfig.DEBUG
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val recording by viewModel.recorderState.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("home") }
    var alert by remember { mutableStateOf<String?>(null) }
    var googleBusy by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val showingLetter = opened.isLoading || opened.envelope != null || opened.error != null
    val pairingRequired = dashboard.user == null || dashboard.user?.coupleSpaceId == null
    val showingDashboard = !dashboard.isLoading && !showingLetter && !pairingRequired && route == "home"

    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onAppBackgrounded()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(dashboard.user?.uid) {
        alert = null
        snackbar.currentSnackbarData?.dismiss()
        if (dashboard.user == null) route = "home"
    }
    LaunchedEffect(viewModel) {
        viewModel.alerts.collect { message -> alert = message }
    }
    LaunchedEffect(alert, showingDashboard) {
        val message = alert ?: return@LaunchedEffect
        if (showingDashboard) delay(8_000)
        else snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        if (alert == message) alert = null
    }
    LaunchedEffect(create.wasSent) {
        if (create.wasSent) route = "home"
    }
    LaunchedEffect(inboxRequest) {
        if (inboxRequest > 0) {
            viewModel.onAppBackgrounded()
            viewModel.dismissLetter()
            viewModel.selectTab(false)
            route = "home"
        }
    }
    BackHandler(enabled = showingLetter || route != "home") {
        if (showingLetter) {
            if (!opened.isLoading) viewModel.dismissLetter()
        } else if (!create.isSaving && !pairing.isBusy) {
            viewModel.stopRecording()
            route = "home"
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                dashboard.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("💌", style = MaterialTheme.typography.displayMedium)
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.loading_letters), fontFamily = FontFamily.Serif)
                    }
                }
                showingLetter -> LetterRevealScreen(
                    state = opened,
                    currentUserId = dashboard.user?.uid,
                    senderName = if (opened.envelope?.senderId == dashboard.user?.uid) dashboard.user?.name.orEmpty()
                        else dashboard.partner?.name.orEmpty(),
                    onBack = { if (!opened.isLoading) viewModel.dismissLetter() },
                    onReact = viewModel::react,
                )
                pairingRequired || route == "pair" -> PairingScreen(
                    user = dashboard.user, space = dashboard.space, isDemo = dashboard.isDemo,
                    busy = pairing.isBusy || googleBusy,
                    error = googleError ?: pairing.error ?: dashboard.error,
                    onAnonymous = { googleError = null; viewModel.signInAnonymously(it) },
                    onEmail = { name, email, password, register ->
                        googleError = null; viewModel.signInEmail(name, email, password, register)
                    },
                    onGoogle = {
                        if (dashboard.isDemo) googleError = context.getString(R.string.google_unavailable)
                        else if (!googleBusy) scope.launch {
                            googleBusy = true
                            googleError = null
                            try { viewModel.signInGoogle(googleIdToken(context)) }
                            catch (_: GetCredentialCancellationException) { }
                            catch (failure: CancellationException) { throw failure }
                            catch (failure: Exception) { googleError = failure.localizedMessage }
                            finally { googleBusy = false }
                        }
                    },
                    onCreateSpace = viewModel::createSpace,
                    onJoinSpace = viewModel::joinSpace,
                    onSignOut = { route = "home"; viewModel.signOut() },
                    onBack = if (!pairingRequired) ({ route = "home" }) else null,
                )
                route == "create" -> CreateEnvelopeScreen(
                    state = create,
                    recording = recording,
                    onUpdate = viewModel::updateDraft,
                    onStartRecording = viewModel::startRecording,
                    onStopRecording = viewModel::stopRecording,
                    onRemoveVoice = viewModel::removeVoiceNote,
                    onSeal = viewModel::sendEnvelope,
                    onBack = {
                        if (!create.isSaving) {
                            if (!create.isAwaitingConfirmation) {
                                viewModel.stopRecording(cancel = true)
                                viewModel.startNewEnvelope()
                            }
                            route = "home"
                        }
                    },
                )
                else -> EnvelopeDashboardScreen(
                    state = dashboard,
                    onCreate = {
                        if (create.wasSent) viewModel.startNewEnvelope()
                        route = "create"
                    },
                    onOpen = { viewModel.openEnvelope(it.envelopeId) },
                    onTabSelected = viewModel::selectTab,
                    onPairing = { route = "pair" }, onMood = viewModel::setMood,
                    onSignOut = viewModel::signOut,
                    alert = alert, onAlertDismiss = { alert = null },
                    notificationSettings = if (BuildConfig.SPARK_MODE && dashboard.user != null) ({
                        LetterNotificationSettingsCard(uid = dashboard.user!!.uid)
                    }) else null,
                    updateSettings = if (updatesEnabled) ({
                        AppUpdateSettingsCard(updateState,
                            onCheck = { updateViewModel.checkForUpdates(manual = true) },
                            onShow = updateViewModel::showDialog)
                    }) else null,
                    onCheckForUpdates = if (updatesEnabled) ({ updateViewModel.checkForUpdates(manual = true) }) else null,
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding())
        }
    }
    if (updatesEnabled) AppUpdateHost(
        updateViewModel, updateState,
        allowPrompt = !showingLetter && route == "home" && !pairing.isBusy && !googleBusy,
    )
}
