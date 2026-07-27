package com.example.lanvoicecaller.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.theme.*
import kotlinx.coroutines.delay

/**
 * Active call screen — shows timer, mute toggle, and end-call button.
 */
@Composable
fun ActiveCallScreen(
    peer: PeerDevice,
    startedAtMs: Long,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit
) {
    // Call duration timer
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000
            delay(1000)
        }
    }

    val timerText = remember(elapsedSeconds) {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Violet20, Background))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Spacer(Modifier.height(40.dp))

            // Active label
            Text(
                "Active Call",
                style = MaterialTheme.typography.labelLarge,
                color = GreenOnline
            )

            // Avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Violet100, Violet20))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    color = OnBg,
                    fontWeight = FontWeight.Bold
                )
            }

            // Name
            Text(
                text = peer.name,
                style = MaterialTheme.typography.titleLarge,
                color = OnBg,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            // Timer
            Text(
                text = timerText,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface
            )

            Spacer(Modifier.height(32.dp))

            // Controls row
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute toggle
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) AmberMuted else Glass)
                    ) {
                        Icon(
                            if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = OnBg,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isMuted) "Unmute" else "Mute",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface
                    )
                }

                // End call
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onEndCall,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(RedCall)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = OnBg,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("End", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                }
            }
        }
    }
}
