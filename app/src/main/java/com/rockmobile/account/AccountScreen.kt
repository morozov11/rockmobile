package com.rockmobile.account

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AccountDialog(viewModel: AccountViewModel, baseUrl: String, dismiss: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceDisplayName(Build.MODEL)) }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceToRevoke by remember { mutableStateOf<AccountDevice?>(null) }

    LaunchedEffect(Unit) { viewModel.resumePairing() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resumePairing()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val close = {
        if (state is AccountUiState.Pairing) viewModel.cancelPairing()
        dismiss()
    }

    AlertDialog(
        onDismissRequest = close,
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
                        Text("Подключите этот телефон к уже существующему RockServer аккаунту через защищённую ссылку.")
                        Text("Новый аккаунт здесь не создаётся. Анонимное радио продолжает работать без сервера.")
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = {
                                deviceName = it
                                nameError = validateDeviceDisplayName(it)
                            },
                            label = { Text("Имя телефона") },
                            supportingText = { nameError?.let { Text(it) } },
                            isError = nameError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val error = validateDeviceDisplayName(deviceName)
                                nameError = error
                                if (error == null) viewModel.connect(deviceName)
                            },
                            enabled = validateDeviceDisplayName(deviceName) == null,
                        ) { Text("Подключить этот телефон к аккаунту") }
                    }

                    is AccountUiState.Pairing -> {
                        val request = state.request
                        val link = request.browserLink(baseUrl)
                        Text("Целевое устройство: ${presentDeviceDisplayName(request.deviceType, request.deviceDisplayName)}")
                        Text("Статус: ожидаем подтверждение в браузере")
                        Text("Действует до: ${formatPairingExpiry(request.expiresAt)}")
                        Spacer(Modifier.height(4.dp))
                        QrCode(link)
                        Text("Отсканируйте QR-код на другом устройстве или откройте защищённую ссылку на этом телефоне.")
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        }) { Text("Открыть защищённую ссылку для подключения") }
                        Text("Проверочная фраза: ${request.verificationPhrase}")
                        Text("Приложение ждёт подтверждение до истечения срока. Секрет pairing хранится только в защищённой сессии приложения.")
                        OutlinedButton(onClick = viewModel::cancelPairing) { Text("Отменить подключение") }
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
                        Text("Лимит аккаунта: до 10 устройств")
                        if (!state.devicesAvailable) {
                            Text("Список устройств временно недоступен. Подключение телефона уже не заблокировано; попробуйте обновить позже.")
                        } else if (state.devices.isEmpty()) {
                            Text("Подключённых устройств пока нет.")
                        } else {
                            Text("Устройства аккаунта")
                            state.devices.forEach { device ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(presentDeviceDisplayName(device.deviceType, device.deviceDisplayName))
                                        if (device.deviceId == state.profile.deviceId) Text("Этот телефон")
                                    }
                                    OutlinedButton(onClick = { deviceToRevoke = device }) { Text("Отключить") }
                                }
                            }
                        }
                        OutlinedButton(onClick = viewModel::refreshAccount) { Text("Обновить аккаунт") }
                        Button(onClick = viewModel::logout) { Text("Выйти на этом телефоне") }
                    }

                    is AccountUiState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        if (state.canRetryConnection) {
                            Button(onClick = { viewModel.connect(deviceName) }) { Text("Попробовать снова") }
                        } else {
                            OutlinedButton(onClick = viewModel::refreshAccount) { Text("Обновить") }
                        }
                        Text("Анонимное радио продолжает работать без аккаунта.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } },
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
    AccountUiState.Disconnected -> "Аккаунт RockServer"
    is AccountUiState.Pairing -> "Подключение телефона"
    is AccountUiState.Connected -> "Аккаунт и устройства"
    is AccountUiState.Error -> "Подключение аккаунта"
}

private fun formatPairingExpiry(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value.ifBlank { "ограниченное время" })

@Composable
private fun QrCode(value: String) {
    val bitmap = remember(value) {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 360, 360)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }
    Image(bitmap.asImageBitmap(), "QR-код подключения", Modifier.size(180.dp))
}
