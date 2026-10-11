package com.aetherweb.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.MeshNetworkManager
import com.aetherweb.app.MeshState
import com.aetherweb.app.MeshViewModel
import com.aetherweb.app.RandomizerState

@Composable
fun TruthDareScreen(
    uiState: MeshState,
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val active = uiState.activeRandomizer
    var selectedCategory by remember { mutableStateOf("All") } // "All", "Truth", "Dare"
    var customPrompt by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }

    // Bottle spin animation state
    var bottleAngle by remember { mutableFloatStateOf(0f) }
    var isSpinning by remember { mutableStateOf(false) }
    val animatedRotation by animateFloatAsState(
        targetValue = bottleAngle,
        animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
        finishedListener = { isSpinning = false },
        label = "BottleRotation"
    )

    val truthColor = Color(0xFF10B981)
    val dareColor = Color(0xFFEF4444)

    val sampleTruths = remember {
        listOf(
            "What is the most embarrassing thing on your phone right now?",
            "Who in this room/mesh would you trust with your darkest secret?",
            "What is a lie you recently told without getting caught?",
            "What is your biggest irrational fear?",
            "What is the worst message you sent to the wrong person?",
            "If you could trade lives with one mesh peer for 24h, who would it be?",
            "What is something you pretend to understand but actually don't?"
        )
    }

    val sampleDares = remember {
        listOf(
            "Send an anonymous silly selfie or voice note to the mesh right now!",
            "Speak in an accent or whisper until your next turn.",
            "Do 15 pushups or jumping jacks on camera / audio stream.",
            "Let another mesh peer write your next status message.",
            "Sing the chorus of your favorite song out loud.",
            "Show the last 3 photos in your gallery without skipping.",
            "Act like a robot for the next 2 minutes."
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFEAB308), Color(0xFFF97316))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎯", fontSize = 24.sp)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Truth or Dare Arena",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Mesh party game • Broadcasts live to everyone nearby",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Challenge Card (if rolled)
            AnimatedVisibility(
                visible = active != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                active?.let { state ->
                    val isTruth = state.type.equals("Truth", ignoreCase = true)
                    val cardColor = if (isTruth) truthColor else dareColor

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .shadow(12.dp, RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = if (isTruth) "😇 TRUTH" else "😈 DARE",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                Text(
                                    text = "By ${state.playerName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )

                                IconButton(
                                    onClick = { viewModel.dismissRandomizer() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Surface(
                                color = Color.Black.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.prompt,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Spin The Bottle / Quick Action Interactive Area
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "🍾 Spin the Bottle & Pick Challenge",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Animated Rotating Bottle
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(2.dp, Color(0xFF334155), CircleShape)
                            .clickable(enabled = !isSpinning) {
                                isSpinning = true
                                bottleAngle += 720f + (0..360).random()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🍾",
                            fontSize = 48.sp,
                            modifier = Modifier.rotate(animatedRotation)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        if (isSpinning) "Spinning... who will it point to?!" else "Tap bottle to spin for a player",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Roll Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.rollRandomizer(specificType = "Truth") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = truthColor),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("😇 Roll Truth", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.rollRandomizer(specificType = "Dare") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = dareColor),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("😈 Roll Dare", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.rollRandomizer() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAB308))
                        ) {
                            Text("🎲 Random (50/50)", color = Color(0xFFEAB308), fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showCustomDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                        ) {
                            Text("✍️ Custom", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Prompt Ideas List
            Text(
                "🔥 Instant Challenge Deck (Tap to send)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFCBD5E1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sampleTruths) { truthText ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.broadcastCustomRandomizer("Truth", truthText)
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("😇", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = truthText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Broadcast",
                                tint = truthColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                items(sampleDares) { dareText ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.broadcastCustomRandomizer("Dare", dareText)
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("😈", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = dareText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Broadcast",
                                tint = dareColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Custom Prompt Dialog
        if (showCustomDialog) {
            var isDareSelected by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showCustomDialog = false },
                title = {
                    Text(
                        "Create Custom Challenge",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Column {
                        Text(
                            "Broadcast your own Truth or Dare challenge to everyone on the offline mesh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !isDareSelected,
                                onClick = { isDareSelected = false },
                                label = { Text("😇 Truth") }
                            )
                            FilterChip(
                                selected = isDareSelected,
                                onClick = { isDareSelected = true },
                                label = { Text("😈 Dare") }
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it },
                            label = { Text("Enter Challenge Question / Dare") },
                            placeholder = { Text("e.g. Who has the coolest wallpaper?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (customPrompt.isNotBlank()) {
                                viewModel.broadcastCustomRandomizer(
                                    type = if (isDareSelected) "Dare" else "Truth",
                                    prompt = customPrompt.trim()
                                )
                                customPrompt = ""
                                showCustomDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDareSelected) dareColor else truthColor
                        )
                    ) {
                        Text("Broadcast Live", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
