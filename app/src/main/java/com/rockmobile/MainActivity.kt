package com.rockmobile

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rockmobile.data.api.RockserverApi
import com.rockmobile.data.repository.StationRepository
import com.rockmobile.data.stations.RockcastAssetStationSource
import com.rockmobile.data.stations.ExtendedCatalogStationSource
import com.rockmobile.data.stations.FallbackLocalStationSource
import com.rockmobile.data.stations.RockserverStationSource
import com.rockmobile.playback.PlaybackController
import com.rockmobile.settings.SettingsRepository
import com.rockmobile.settings.UnavailableVoiceStationStore
import com.rockmobile.data.personal.PersonalDataStore
import com.rockmobile.ui.stations.StationsScreen
import com.rockmobile.ui.stations.StationsViewModel
import com.rockmobile.ui.stations.PlayerScreen
import com.rockmobile.ui.theme.RockmobileTheme
import com.rockmobile.voice.AndroidVoiceRecorder
import com.rockmobile.voice.RockserverVoiceClient
import com.rockmobile.voice.VoiceCommandController
import com.rockmobile.voice.VoicePlaybackActions
import com.rockmobile.account.AccountDialog
import com.rockmobile.account.AccountViewModel
import com.rockmobile.account.KeystoreCredentialStore
import com.rockmobile.account.RockserverAccountGateway
import com.rockmobile.account.accountSessionActive
import com.rockmobile.devicecontrol.DeviceControlDirectoryApi
import com.rockmobile.devicecontrol.OkHttpDirectorySocketFactory
import com.rockmobile.devicecontrol.TargetDirectoryRepository
import com.rockmobile.devicecontrol.TargetDirectoryViewModel

class MainActivity : ComponentActivity() {
    private var accountViewModel: AccountViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(this)
        val unavailableVoiceStations = UnavailableVoiceStationStore(this)
        val personalData = PersonalDataStore(this)
        val baseline = RockcastAssetStationSource(assets, unavailableVoiceStations::migrateLegacyIds)
        personalData.reconcile(baseline.personalCatalogIndex())
        val repository = StationRepository(
            RockserverStationSource(RockserverApi(), settings::rockserverUrl, settings::bearerToken),
            primary = baseline,
            offlineSearch = FallbackLocalStationSource(ExtendedCatalogStationSource(this), baseline),
        )
        setContent {
            RockmobileTheme {
            val model: StationsViewModel = viewModel(factory = StationsViewModelFactory(repository, unavailableVoiceStations::unavailableStationIds))
            val account = viewModel<AccountViewModel>(factory = AccountViewModelFactory(RockserverAccountGateway(RockserverApi(), settings::rockserverUrl), KeystoreCredentialStore(applicationContext))).also { accountViewModel = it }
            val targetDirectory = viewModel<TargetDirectoryViewModel>(factory = TargetDirectoryViewModelFactory(
                TargetDirectoryRepository(DeviceControlDirectoryApi(RockserverApi(), settings::rockserverUrl), OkHttpDirectorySocketFactory(), settings),
                account::directorySession,
            ))
            val targetDirectoryState = targetDirectory.state.collectAsStateWithLifecycle().value
            val targetCommands = targetDirectory.commands.collectAsStateWithLifecycle().value
            val accountState = account.state.collectAsStateWithLifecycle().value
            val accountConnected = accountSessionActive(accountState)
            androidx.compose.runtime.LaunchedEffect(accountConnected) {
                if (accountConnected) targetDirectory.useCurrentAccount()
            }
            androidx.compose.runtime.LaunchedEffect(account) {
                account.ensureSessionVisible()
                if (isRockmobileReturnIntent(intent)) account.resumePairing(fromBrowser = true)
            }
            val state = model.state.collectAsStateWithLifecycle().value
            val personal = personalData.state.collectAsStateWithLifecycle().value
            val playback = androidx.compose.runtime.remember { PlaybackController(this, unavailableVoiceStations) }
            val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
            val playOnDeviceAction: (com.rockmobile.domain.model.Station) -> Unit = { station ->
                val support = com.rockmobile.devicecontrol.checkDevicePlaySupport(targetDirectoryState)
                val target = support.target
                val command = com.rockmobile.devicecontrol.buildPlayStationCommand(station, target)
                if (command == null || !support.supported) {
                    lifecycleScope.launch {
                        snackbarHostState.showSnackbar(support.reason ?: "Воспроизведение на устройстве недоступно")
                    }
                } else {
                    val commandId = targetDirectory.dispatch(command)
                    if (commandId == null) {
                        lifecycleScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Не удалось отправить команду на «${target?.name ?: "устройство"}»",
                                actionLabel = "Повторить",
                            )
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                targetDirectory.dispatch(command)
                            }
                        }
                    }
                }
            }
            var lastFailedCommandId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(targetCommands) {
                val failed = targetCommands.values
                    .filter { it.phase == com.rockmobile.devicecontrol.CommandPhase.Failed && it.commandId != lastFailedCommandId }
                    .maxByOrNull { it.commandId }
                if (failed != null) {
                    lastFailedCommandId = failed.commandId
                    val result = snackbarHostState.showSnackbar(
                        message = failed.detail ?: "Команда на устройстве не выполнена",
                        actionLabel = "Повторить",
                    )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        val stationId = failed.actionKey.removePrefix("station.play_station:")
                        val stationToRetry = (state as? com.rockmobile.ui.stations.StationsUiState.Content)?.catalogue?.stations?.find { it.id == stationId }
                            ?: playback.state.value.station?.takeIf { it.id == stationId }
                        if (stationToRetry != null) {
                            playOnDeviceAction(stationToRetry)
                        }
                    }
                }
            }
            val voice = androidx.compose.runtime.remember(account) {
                VoiceCommandController(
                    AndroidVoiceRecorder(this), RockserverVoiceClient(), settings::rockserverUrl,
                    bearerToken = { account.voiceAccessToken().orEmpty() },
                    object : VoicePlaybackActions {
                        override fun beginVoiceCapture() = playback.beginVoiceCapture()
                        override fun endVoiceCapture() = playback.endVoiceCapture()
                        override fun showCandidates(stations: List<com.rockmobile.domain.model.Station>) = model.showVoiceCandidates(stations)
                        override fun play(station: com.rockmobile.domain.model.Station, queue: List<com.rockmobile.domain.model.Station>) { personalData.recordPlay(station, "remote"); playback.play(station, queue, fromVoiceResult = true) }
                    },
                    lifecycleScope,
                )
            }
            val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                voice.permissionResult(granted, !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            }
            var playerScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            var accountOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.cancel(); playback.release() } }
            val playbackState = playback.state.collectAsStateWithLifecycle().value
            val voiceState = voice.state.collectAsStateWithLifecycle().value
            if (playerScreen) PlayerScreen(
                state = playbackState,
                back = { playerScreen = false },
                toggle = playback::toggle,
                previous = playback::skipToPrevious,
                next = playback::skipToNext,
                retry = playback::retry,
                targetDirectoryState = targetDirectoryState,
                commands = targetCommands,
                onPlayOnDevice = playOnDeviceAction,
                snackbarHostState = snackbarHostState,
            )
            else StationsScreen(
                state = state,
                playback = playbackState,
                voice = voiceState,
                retry = model::retryRockserver,
                updateFilters = model::updateFilters,
                play = { station, queue -> personalData.recordPlay(station, "catalog"); playback.play(station, queue) },
                toggle = playback::toggle,
                onVoice = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
                    else { voice.requestPermission(); microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                },
                onFinishVoice = voice::finishRecording,
                onCancelVoice = voice::cancel,
                onDismissVoice = voice::dismiss,
                openPlayer = { playerScreen = true },
                personal = personal,
                toggleFavourite = { station -> personalData.toggleFavourite(station) },
                openAccount = { account.ensureSessionVisible(); accountOpen = true },
                accountConnected = accountConnected,
                clearHistory = personalData::clearHistory,
                targetDirectoryState = targetDirectoryState,
                commands = targetCommands,
                onPlayOnDevice = playOnDeviceAction,
                snackbarHostState = snackbarHostState,
            )
            if (accountOpen) AccountDialog(account, settings.rockserverUrl(), targetDirectory) { accountOpen = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isRockmobileReturnIntent(intent)) accountViewModel?.resumePairing(fromBrowser = true)
    }
}

/** Accepts only the credential-free Android App Link that resumes an in-memory pairing poll. */
internal fun isRockmobileReturnIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_VIEW && isRockmobileReturnUri(intent.data)

/** Matches the exact, credential-free browser return endpoint owned by RockMobile. */
internal fun isRockmobileReturnUri(uri: Uri?): Boolean =
    uri != null && isRockmobileReturnTarget(uri.scheme, uri.host, uri.path, uri.query, uri.fragment)

/** Checks return-link components separately so the credential boundary has a JVM unit test. */
internal fun isRockmobileReturnTarget(
    scheme: String?,
    host: String?,
    path: String?,
    query: String?,
    fragment: String?,
): Boolean =
    scheme == "https" && host == "alex.vault57.ru" && path == "/return/rockmobile" &&
        query == null && fragment == null

private class StationsViewModelFactory(
    private val repository: StationRepository,
    private val unavailableVoiceStationIds: () -> Set<String>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StationsViewModel(repository, unavailableVoiceStationIds = unavailableVoiceStationIds) as T
}

private class AccountViewModelFactory(
    private val gateway: RockserverAccountGateway,
    private val store: KeystoreCredentialStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(gateway, store) as T
}

private class TargetDirectoryViewModelFactory(
    private val repository: TargetDirectoryRepository,
    private val sessionProvider: suspend () -> com.rockmobile.devicecontrol.ControllerSession?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TargetDirectoryViewModel(repository, sessionProvider) as T
}
