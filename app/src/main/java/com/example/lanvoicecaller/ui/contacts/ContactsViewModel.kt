package com.example.lanvoicecaller.ui.contacts

import androidx.lifecycle.ViewModel
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.service.LanCallService
import kotlinx.coroutines.flow.StateFlow

class ContactsViewModel(private val service: LanCallService) : ViewModel() {
    val peers: StateFlow<List<PeerDevice>> = service.peers

    fun callPeer(peer: PeerDevice) = service.callPeer(peer)
}
