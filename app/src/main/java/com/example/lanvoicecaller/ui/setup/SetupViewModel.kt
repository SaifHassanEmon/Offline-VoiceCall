package com.example.lanvoicecaller.ui.setup

import androidx.lifecycle.ViewModel
import com.example.lanvoicecaller.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SetupViewModel(private val prefs: AppPreferences) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun onNameChange(value: String) { _name.value = value }

    fun onConfirm() {
        val trimmed = _name.value.trim()
        if (trimmed.isBlank()) return
        prefs.displayName = trimmed
        _done.value = true
    }
}
