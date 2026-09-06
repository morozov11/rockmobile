package com.rockmobile.devicecontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Selector only establishes an explicit future target; it does not expose or send commands. */
@Composable
fun TargetSelector(state: TargetDirectoryState, refresh: () -> Unit, select: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Устройство управления", style = MaterialTheme.typography.titleMedium)
        when (state) {
            TargetDirectoryState.Inactive -> Text("Выбор устройства появится после подключения аккаунта.")
            TargetDirectoryState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                Text("Загружаем разрешённые устройства…")
            }
            is TargetDirectoryState.Unavailable -> {
                Text(state.message, color = if (state.scopeMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                OutlinedButton(onClick = refresh) { Text("Повторить") }
            }
            is TargetDirectoryState.Available -> {
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.targets.isEmpty()) Text("Нет разрешённых устройств для управления.")
                state.targets.forEach { target ->
                    val selected = state.selectedTargetId == target.id
                    OutlinedButton(
                        onClick = { select(target.id) },
                        enabled = target.usable,
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = targetStatus(target) },
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (selected) "✓ ${target.name}" else target.name)
                            Text("${target.type} · ${target.roles.joinToString { it.name }} · ${targetStatus(target)}", style = MaterialTheme.typography.bodySmall)
                            if (target.knownCapabilities.isNotEmpty()) Text("Совместимость: ${target.knownCapabilities.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.requiresSelection) Text("Действия с одним устройством потребуют явного выбора.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = refresh) { Text("Обновить список") }
            }
        }
    }
}

private fun targetStatus(target: ControllerTarget): String = when {
    target.presence == TargetPresence.Offline -> "не в сети"
    target.freshness == TargetFreshness.Stale -> "данные устарели"
    target.freshness == TargetFreshness.Unknown -> "состояние неизвестно"
    else -> "в сети"
}
