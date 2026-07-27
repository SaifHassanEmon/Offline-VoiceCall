package com.example.lanvoicecaller

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.lanvoicecaller.data.model.CallState
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.data.prefs.AppPreferences
import com.example.lanvoicecaller.service.LanCallService
import com.example.lanvoicecaller.ui.call.ActiveCallScreen
import com.example.lanvoicecaller.ui.call.CallViewModel
import com.example.lanvoicecaller.ui.call.IncomingCallScreen
import com.example.lanvoicecaller.ui.chat.ChatScreen
import com.example.lanvoicecaller.ui.chat.ChatViewModel
import com.example.lanvoicecaller.ui.contacts.ContactsScreen
import com.example.lanvoicecaller.ui.contacts.ContactsViewModel
import com.example.lanvoicecaller.ui.setup.SetupScreen
import com.example.lanvoicecaller.ui.setup.SetupViewModel

@Composable
fun MainNavigation(service: LanCallService) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    val startDest = if (prefs.isSetupDone) Contacts else Setup
    val backStack = rememberNavBackStack(startDest)

    val callState by service.callState.collectAsState()
    val isMuted by remember { derivedStateOf { false } }

    val callViewModel = remember { CallViewModel(service) }
    val isMutedState by callViewModel.isMuted.collectAsState()

    // Overlay call screens on top of whatever is current
    when (val cs = callState) {
        is CallState.Incoming -> {
            IncomingCallScreen(
                peer = cs.peer,
                onAccept = { callViewModel.acceptCall() },
                onReject = { callViewModel.rejectCall() }
            )
            return
        }
        is CallState.Calling -> {
            IncomingCallScreen(
                peer = cs.peer,
                onAccept = {},       // waiting for other side — just show same UI
                onReject = { callViewModel.endCall() }
            )
            return
        }
        is CallState.Active -> {
            ActiveCallScreen(
                peer = cs.peer,
                startedAtMs = cs.startedAtMs,
                isMuted = isMutedState,
                onToggleMute = callViewModel::toggleMute,
                onEndCall = callViewModel::endCall
            )
            return
        }
        is CallState.Ended -> {
            // Pop call screen, fall through to main nav
            LaunchedEffect(cs) {
                // Transition back — nothing to do, nav continues below
            }
        }
        else -> {}
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {

            entry<Setup> {
                SetupScreen(
                    viewModel = remember { SetupViewModel(prefs) },
                    onDone = {
                        backStack.removeLastOrNull()
                        backStack.add(Contacts)
                    }
                )
            }

            entry<Contacts> {
                ContactsScreen(
                    viewModel = remember { ContactsViewModel(service) },
                    myName = prefs.displayName,
                    onOpenChat = { peer ->
                        backStack.add(Chat(peer.id, peer.name, peer.ipAddress, peer.port))
                    },
                    onCallStarted = {}
                )
            }

            entry<Chat> { key ->
                val peer = PeerDevice(
                    id = key.peerId,
                    name = key.peerName,
                    ipAddress = key.peerIp,
                    port = key.peerPort
                )
                ChatScreen(
                    peer = peer,
                    viewModel = remember { ChatViewModel(service) },
                    onBack = { backStack.removeLastOrNull() },
                    onCallStarted = {}
                )
            }
        }
    )
}
