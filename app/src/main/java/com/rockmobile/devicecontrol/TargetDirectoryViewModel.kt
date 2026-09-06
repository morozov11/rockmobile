package com.rockmobile.devicecontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class TargetDirectoryViewModel internal constructor(
    private val repository: TargetDirectoryRepository,
    private val sessionProvider: suspend () -> ControllerSession?,
) : ViewModel() {
    val state = repository.state

    fun useCurrentAccount() = viewModelScope.launch {
        sessionProvider()?.let { repository.start(viewModelScope, it) } ?: repository.stop()
    }
    fun refresh() = viewModelScope.launch { sessionProvider()?.let { repository.start(viewModelScope, it); repository.refresh() } }
    fun select(targetId: String) = repository.select(targetId)
    override fun onCleared() { repository.stop() }
}
