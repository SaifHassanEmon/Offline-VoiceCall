package com.example.lanvoicecaller.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lanvoicecaller.theme.*
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onDone: () -> Unit
) {
    val name by viewModel.name.collectAsState()
    val done by viewModel.done.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); visible = true }
    LaunchedEffect(done) { if (done) onDone() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Violet20, Background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it / 2 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Glass),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Violet100,
                        modifier = Modifier.size(52.dp)
                    )
                }

                // Title
                Text(
                    text = "Welcome to\nLAN Voice",
                    style = MaterialTheme.typography.displayLarge,
                    color = OnBg,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Call anyone on the same Wi‑Fi,\nno internet needed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Your display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Violet100,
                        unfocusedBorderColor = Divider,
                        focusedLabelColor = Violet100,
                        cursorColor = Violet100,
                        focusedTextColor = OnBg,
                        unfocusedTextColor = OnBg
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        viewModel.onConfirm()
                    })
                )

                // Confirm button
                Button(
                    onClick = {
                        keyboard?.hide()
                        viewModel.onConfirm()
                    },
                    enabled = name.trim().isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet100)
                ) {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnBg
                    )
                }
            }
        }
    }
}
