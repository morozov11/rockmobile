package com.rockmobile.devicecontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.Instant

class TargetDirectoryViewModel internal constructor(
    private val repository: TargetDirectoryRepository,
    private val sessionProvider: suspend () -> ControllerSession?,
) : ViewModel() {
    val state = repository.state
    val commands = repository.commands
    val receivers = repository.receivers

    fun useCurrentAccount() = viewModelScope.launch {
        sessionProvider()?.let { repository.start(viewModelScope, it) } ?: repository.stop()
    }
    fun refresh() = viewModelScope.launch { sessionProvider()?.let { repository.start(viewModelScope, it); repository.refresh() } }
    fun select(targetId: String) = repository.select(targetId)
    fun dispatch(command: RemoteCommand) = repository.dispatch(command, Instant.now())
    override fun onCleared() { repository.stop() }
}
