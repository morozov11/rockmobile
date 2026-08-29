package com.rockmobile.account

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rockmobile.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AccountDialog(viewModel: AccountViewModel, baseUrl: String, dismiss: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceDisplayName(Build.MODEL)) }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceToRevoke by remember { mutableStateOf<AccountDevice?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resumePairing(fromBrowser = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { viewModel.ensureSessionVisible() }

    val dismissDialog = { dismiss() }

    AlertDialog(
        onDismissRequest = dismissDialog,
        title = { Text(dialogTitle(state)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state) {
                    AccountUiState.Disconnected -> {
                        Text(disconnectedPrimaryCopy())
                        Text(disconnectedSecondaryCopy())
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = {
                                deviceName = it
                                nameError = validateDeviceDisplayName(it)
                            },
                            label = { Text("Имя устройства") },
                            supportingText = { nameError?.let { Text(it) } },
                            isError = nameError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(presentDeviceDisplayName("rockmobile_android", deviceName))
                        Button(
                            onClick = {
                                val error = validateDeviceDisplayName(deviceName)
                                nameError = error
                                if (error == null) viewModel.connect(deviceName)
                            },
                            enabled = validateDeviceDisplayName(deviceName) == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Подключить RockMobile") }
                    }

                    AccountUiState.Starting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Начинаем подключение…")
                        }
                    }

                    is AccountUiState.Pairing -> {
                        val request = state.request
                        val link = request.browserLink(baseUrl)
                        Text("Шаг 1 из 2 · Подтвердите в браузере")
                        Text(presentDeviceDisplayName(request.deviceType, request.deviceDisplayName))
                        Text(pairingExpiryDescription(request.expiresAt))
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Открыть защищённую ссылку") }
                        Text("Проверочная фраза: ${request.verificationPhrase}")
                        if (state.returningFromBrowser) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text("Завершаем подключение…")
                            }
                        }
                        var showOtherDevice by rememberSaveable(request.requestId) { mutableStateOf(false) }
                        OutlinedButton(onClick = { showOtherDevice = !showOtherDevice }) {
                            Text("Подключить через другое устройство")
                        }
                        if (showOtherDevice) {
                            QrCode(
                                link = link,
                                description = "QR-код для ${presentDeviceDisplayName(request.deviceType, request.deviceDisplayName)}; ${pairingExpiryDescription(request.expiresAt)}",
                            )
                            Text("Откройте камеру на другом устройстве, войдите с passkey, сравните фразу и подтвердите подключение.")
                        }
                        TextButton(onClick = viewModel::cancelPairing) { Text("Отменить") }
                    }

                    is AccountUiState.ConnectedFirstTime -> {
                        Text("✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                        Text("RockMobile подключён", style = MaterialTheme.typography.headlineSmall)
                        Text("Аккаунт: ${state.profile.accountDisplayName}")
                        Text("Это устройство: ${presentDeviceDisplayName(state.profile.deviceType, state.profile.deviceDisplayName)}")
                        Button(onClick = {
                            viewModel.acknowledgeFirstTimeConnection()
                            dismissDialog()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                        OutlinedButton(onClick = viewModel::openDevices, modifier = Modifier.fillMaxWidth()) { Text("Открыть устройства") }
                    }

                    is AccountUiState.Connected -> {
                        state.message?.let { message ->
                            Text(
                                message,
                                color = if (message.startsWith("Не удалось")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text("Аккаунт: ${state.profile.accountDisplayName}")
                        Text("Этот телефон: ${presentDeviceDisplayName(state.profile.deviceType, state.profile.deviceDisplayName)}")
                        Text("Лимит аккаунта: до 50 устройств")
                        if (!state.devicesAvailable) {
                            Text("Список устройств временно недоступен. Подключение телефона уже не заблокировано; попробуйте обновить позже.")
                        } else if (state.devices.isEmpty()) {
                            Text("Подключённых устройств пока нет.")
                        } else {
                            Text("Устройства аккаунта")
                            state.devices.sortedByDescending { it.deviceId == state.profile.deviceId }.forEach { device ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(presentDeviceDisplayName(device.deviceType, device.deviceDisplayName))
                                        if (device.deviceId == state.profile.deviceId) Text("Этот телефон")
                                        formatDeviceActivity(device)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    if (device.deviceId != state.profile.deviceId) {
                                        OutlinedButton(onClick = { deviceToRevoke = device }) { Text("Отключить") }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = viewModel::refreshAccount) { Text("Обновить аккаунт") }
                        Button(onClick = viewModel::logout) { Text("Выйти на этом телефоне") }
                    }

                    is AccountUiState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        when (state.action) {
                            AccountErrorAction.Retry -> Button(onClick = { viewModel.connect(deviceName) }) { Text("Повторить") }
                            AccountErrorAction.NewLink -> Button(onClick = { viewModel.connect(deviceName) }) { Text("Создать новую") }
                            AccountErrorAction.Restart -> Button(onClick = { viewModel.connect(deviceName) }) { Text("Начать заново") }
                            AccountErrorAction.OpenDevices -> OutlinedButton(onClick = viewModel::refreshAccount) { Text("Открыть устройства") }
                            AccountErrorAction.UpdateApp -> Text("Для продолжения установите новую версию RockMobile.")
                            AccountErrorAction.None -> Unit
                        }
                        Text("Анонимное радио продолжает работать без аккаунта.")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(visibleBuild(), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = dismissDialog) { Text("Закрыть") } },
    )

    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text("Отключить устройство?") },
            text = { Text("«${presentDeviceDisplayName(device.deviceType, device.deviceDisplayName)}» потеряет доступ к этому аккаунту.") },
            confirmButton = {
                Button(onClick = {
                    deviceToRevoke = null
                    viewModel.revoke(device.deviceId)
                }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { deviceToRevoke = null }) { Text("Отмена") } },
        )
    }
}

private fun dialogTitle(state: AccountUiState): String = when (state) {
    AccountUiState.Disconnected -> "Rock-аккаунт"
    AccountUiState.Starting -> "Подключение RockMobile"
    is AccountUiState.Pairing -> "Подключение телефона"
    is AccountUiState.ConnectedFirstTime -> "Подключение завершено"
    is AccountUiState.Connected -> "Аккаунт и устройства"
    is AccountUiState.Error -> "Подключение аккаунта"
}

internal fun disconnectedPrimaryCopy() = "Подключите RockMobile к существующему Rock-аккаунту."

internal fun disconnectedSecondaryCopy() = "Радио и сохранённые станции работают без аккаунта."

internal fun visibleBuild() = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_REVISION})"

@Composable
private fun QrCode(link: String, description: String) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val target = maxWidth.coerceAtMost(320.dp).coerceAtLeast(256.dp)
        val matrix = remember(link) { pairingQrMatrix(link) }
        val modulePixels = pairingQrModulePixels(matrix, with(density) { target.toPx().roundToInt() })
        val bitmap = remember(link, modulePixels) {
            val size = matrix.width * modulePixels
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    val color = if (matrix[x, y]) Color.BLACK else Color.WHITE
                    for (dy in 0 until modulePixels) for (dx in 0 until modulePixels) {
                        bitmap.setPixel(x * modulePixels + dx, y * modulePixels + dy, color)
                    }
                }
            }
        }
        Image(bitmap.asImageBitmap(), description, Modifier.size(with(density) { bitmap.width.toDp() }))
    }
}

private fun formatDeviceActivity(device: AccountDevice): String? = device.lastSeenAt?.let { value ->
    runCatching {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrNull()?.let { "Последняя активность: $it" }
}
