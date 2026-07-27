package com.example.lanvoicecaller.ui.contacts

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
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
                actions = {
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh scan", tint = Violet80)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            if (peers.isEmpty()) {
                EmptyState(onRefresh = { viewModel.rescan() })
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${peers.size} online",
                                style = MaterialTheme.typography.labelMedium,
                                color = GreenOnline,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Text(
                                text = "Wi-Fi Direct / Local network",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface
                            )
                        }
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
                        text = if (peer.ipAddress.isBlank()) "Wi-Fi Direct P2P" else peer.ipAddress,
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
private fun EmptyState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📡", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Searching for nearby devices…", style = MaterialTheme.typography.titleMedium, color = OnBg)
        Spacer(Modifier.height(8.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("📌 Check these settings on BOTH phones:", style = MaterialTheme.typography.labelLarge, color = Violet100)
                Text("1. Turn ON Location (GPS) in phone settings shade (required by Android for Wi-Fi Direct scanning).", style = MaterialTheme.typography.bodySmall, color = OnBg)
                Text("2. Turn ON Wi-Fi on both phones.", style = MaterialTheme.typography.bodySmall, color = OnBg)
                Text("3. Alternative: One phone turns on Hotspot (no internet needed) and the other phone connects to it.", style = MaterialTheme.typography.bodySmall, color = OnBg)
            }
        }

        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(containerColor = Violet100),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Tap to Rescan", color = OnBg)
        }
    }
}
