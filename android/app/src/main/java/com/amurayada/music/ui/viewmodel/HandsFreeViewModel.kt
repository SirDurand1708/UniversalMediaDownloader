package com.amurayada.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandsFreeViewModel : ViewModel() {

    private val _isHandsFreeMode = MutableStateFlow(false)
    val isHandsFreeMode: StateFlow<Boolean> = _isHandsFreeMode.asStateFlow()

    fun setManualMode(enabled: Boolean) {
        _isHandsFreeMode.value = enabled
    }
}
