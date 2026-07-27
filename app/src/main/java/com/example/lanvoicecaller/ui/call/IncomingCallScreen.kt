package com.example.lanvoicecaller.ui.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.theme.*

/**
 * Screen shown when an incoming call arrives — Accept / Reject buttons.
 */
@Composable
fun IncomingCallScreen(
    peer: PeerDevice,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    // Pulsing ring animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_scale"
    )

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
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // Incoming call label
            Text(
                "Incoming Call",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface
            )

            // Pulsing avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
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

            // Caller name
            Text(
                text = peer.name,
                style = MaterialTheme.typography.titleLarge,
                color = OnBg,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = peer.ipAddress,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )

            Spacer(Modifier.height(24.dp))

            // Accept / Reject
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reject
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onReject,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(RedCall)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = OnBg, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Decline", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                }

                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(GreenCall)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept", tint = OnBg, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Accept", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                }
            }
        }
    }
}
