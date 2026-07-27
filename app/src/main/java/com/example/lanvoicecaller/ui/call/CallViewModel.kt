package com.example.lanvoicecaller.ui.call

import androidx.lifecycle.ViewModel
import com.example.lanvoicecaller.data.model.CallState
import com.example.lanvoicecaller.service.LanCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CallViewModel(private val service: LanCallService) : ViewModel() {

    val callState: StateFlow<CallState> = service.callState

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun acceptCall() = service.acceptCall()

    fun rejectCall() = service.rejectCall()

    fun endCall() = service.endCall()

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        service.setMuted(_isMuted.value)
    }
}
