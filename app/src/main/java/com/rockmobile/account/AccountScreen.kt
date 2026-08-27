package com.rockmobile.account

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

@Composable
fun AccountDialog(viewModel: AccountViewModel, baseUrl: String, dismiss: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Account & devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Optional account connection. Radio continues to work without RockServer.")
                when (state) {
                    AccountUiState.Disconnected -> Button(onClick = { viewModel.connect("RockMobile on ${android.os.Build.MODEL}") }) { Text("Connect account") }
                    is AccountUiState.Pairing -> {
                        val link = state.request.browserLink(baseUrl)
                        Text("Open this link in a browser, create or use a passkey, then approve this phone.")
                        QrCode(link)
                        Text("Short code: ${state.request.shortCode}")
                        Text("Verification phrase: ${state.request.verificationPhrase}")
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) }) { Text("Open secure pairing link") }
                        Text("Waiting for approval (up to 10 minutes). The one-time pairing secrets are held only in memory.")
                        OutlinedButton(onClick = viewModel::cancelPairing) { Text("Cancel pairing") }
                    }
                    is AccountUiState.Connected -> {
                        Text("Connected on this device")
                        Text("Device: ${state.profile.deviceId}")
                        OutlinedButton(onClick = viewModel::refreshAccount) { Text("Refresh account & devices") }
                        state.devices.forEach { device ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${device.name} (${device.platform})", modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { viewModel.revoke(device.deviceId) }) { Text("Revoke") }
                            }
                        }
                        Button(onClick = viewModel::logout) { Text("Log out on this phone") }
                    }
                    is AccountUiState.Error -> {
                        Text(state.message)
                        Button(onClick = { viewModel.connect("RockMobile on ${android.os.Build.MODEL}") }) { Text("Try again") }
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun QrCode(value: String) {
    val bitmap = remember(value) {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 360, 360)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    Image(bitmap.asImageBitmap(), "Pairing QR code", Modifier.size(180.dp))
}
