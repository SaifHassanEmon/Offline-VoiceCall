package com.example.lanvoicecaller.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.lanvoicecaller.data.model.ChatMessage
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peer: PeerDevice,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onCallStarted: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val peerMessages = messages.filter { it.senderId == peer.id || it.isFromMe }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(peerMessages.size) {
        if (peerMessages.isNotEmpty()) listState.animateScrollToItem(peerMessages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnBg)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Violet100, Violet80))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(peer.name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = OnBg)
                        }
                        Column {
                            Text(peer.name, style = MaterialTheme.typography.titleMedium, color = OnBg)
                            Text("Online", style = MaterialTheme.typography.bodySmall, color = GreenOnline)
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.startCall(peer); onCallStarted() },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(CircleShape)
                            .background(GreenCall)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Call", tint = OnBg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (peerMessages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "No messages yet. Say hello! 👋",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface
                            )
                        }
                    }
                }
                items(peerMessages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            // Input bar
            Surface(color = Surface, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message…", color = OnSurface) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Violet100,
                            unfocusedBorderColor = Divider,
                            cursorColor = Violet100,
                            focusedTextColor = OnBg,
                            unfocusedTextColor = OnBg
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank()) {
                                viewModel.sendMessage(peer, draft.trim())
                                draft = ""
                                keyboard?.hide()
                            }
                        })
                    )
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                viewModel.sendMessage(peer, draft.trim())
                                draft = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (draft.isNotBlank()) Violet100 else Glass)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = OnBg)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val timeStr = remember(msg.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestampMs))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (msg.isFromMe) 18.dp else 4.dp,
                    bottomEnd = if (msg.isFromMe) 4.dp else 18.dp
                ),
                color = if (msg.isFromMe) Violet100 else SurfaceVar,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBg,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Text(
                text = timeStr,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
