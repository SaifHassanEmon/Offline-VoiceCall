package com.example.lanvoicecaller.ui.contacts

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    myName: String,
    onOpenChat: (PeerDevice) -> Unit,
    onCallStarted: () -> Unit
) {
    val peers by viewModel.peers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LAN Voice", style = MaterialTheme.typography.titleLarge, color = OnBg)
                        Text("Signed in as $myName", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            if (peers.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "${peers.size} online",
                            style = MaterialTheme.typography.labelMedium,
                            color = GreenOnline,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(peers, key = { it.id }) { peer ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInHorizontally()
                        ) {
                            PeerCard(
                                peer = peer,
                                onCall = {
                                    viewModel.callPeer(peer)
                                    onCallStarted()
                                },
                                onChat = { onOpenChat(peer) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerDevice,
    onCall: () -> Unit,
    onChat: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Violet100, Violet80))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBg,
                    fontWeight = FontWeight.Bold
                )
            }

            // Name + IP
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.bodyLarge, color = OnBg, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(GreenOnline)
                    )
                    Text(
                        text = if (peer.ipAddress.isBlank()) "Wi-Fi Direct" else peer.ipAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface
                    )
                }
            }

            // Chat button
            IconButton(
                onClick = onChat,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Glass)
            ) {
                Icon(Icons.Default.Chat, contentDescription = "Chat", tint = Violet80)
            }

            // Call button
            IconButton(
                onClick = onCall,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GreenCall)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = OnBg)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📡", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Searching for devices…", style = MaterialTheme.typography.titleMedium, color = OnBg)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Make sure Wi-Fi is on and the other\nphone has LAN Voice open nearby.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
