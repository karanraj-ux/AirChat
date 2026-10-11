package com.aetherweb.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.BotThinking
import com.aetherweb.app.GamePacing
import com.aetherweb.app.LudoEngine
import com.aetherweb.app.LudoState
import com.aetherweb.app.MeshNetworkManager
import kotlinx.coroutines.delay

/**
 * Studio-Grade Mesh Ludo with Universal Bot Pacing & Robust Slot Management:
 * 1. Observable human-like bot pacing (Roll -> Think -> Move) with no instant 0ms finishes.
 * 2. Proper slot management for all 4 players (You, Bot, Peer, Open).
 * 3. Game Pacing selector (Relaxed / Normal / Blitz).
 * 4. Non-blocking UI where bottom dice deck is completely separate from chat overlays.
 */
@Composable
fun LudoScreen(
    state: LudoState,
    onStateChange: (LudoState) -> Unit,
    modifier: Modifier = Modifier
) {
    val playerColors = listOf(
        Color(0xFFEF4444), // Red
        Color(0xFF10B981), // Green
        Color(0xFFF59E0B), // Yellow
        Color(0xFF3B82F6)  // Blue
    )
    val playerNames = listOf("Red", "Green", "Yellow", "Blue")
    val myNodeId = MeshNetworkManager.localNodeId
    val isPassAndPlay = state.playerIds.values.none { it == myNodeId }
    val isMyTurn = (state.playerIds[state.turn] == myNodeId) || isPassAndPlay
    val isBotTurn = state.playerIds[state.turn] == "bot"

    // Timer logic
    var timeLeftSeconds by remember(state.turn, state.hasRolled, state.winner) { mutableIntStateOf(30) }
    var isTimerPaused by remember { mutableStateOf(false) }

    // Slot Config Dialog State
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }

    // Human-like, observable Bot Turn State Machine
    val stateRef = rememberUpdatedState(state)
    LaunchedEffect(state.turn, state.hasRolled, state.playerIds, state.winner) {
        if (state.winner != -1) return@LaunchedEffect
        val currentTurn = state.turn
        if (state.playerIds[currentTurn] != "bot") return@LaunchedEffect

        // Phase 1: Turn awareness pause (bot notices it's its turn)
        delay(BotThinking.getTurnAwarenessDelay())
        if (stateRef.value.turn != currentTurn || stateRef.value.winner != -1) return@LaunchedEffect

        if (!state.hasRolled) {
            // Phase 2: Bot rolling dice
            onStateChange(
                stateRef.value.copy(
                    botThinking = true,
                    botStatus = "🎲 ${playerNames[currentTurn]} Bot is rolling dice..."
                )
            )
            delay(BotThinking.getDiceRollDelay())
            if (stateRef.value.turn != currentTurn || stateRef.value.winner != -1) return@LaunchedEffect

            // Execute dice roll
            val rolledState = LudoEngine.rollDice(stateRef.value)
            if (!rolledState.hasRolled) {
                // Rolled a number with NO possible moves
                onStateChange(
                    rolledState.copy(
                        botThinking = false,
                        botStatus = "⏭️ ${playerNames[currentTurn]} Bot rolled ${rolledState.diceValue} (No moves!)"
                    )
                )
                delay(BotThinking.getPostMoveDelay())
                return@LaunchedEffect
            }

            // Phase 3: Bot evaluates board & valid moves
            onStateChange(
                rolledState.copy(
                    botThinking = true,
                    botStatus = "🤔 ${playerNames[currentTurn]} Bot rolled ${rolledState.diceValue} & is planning move..."
                )
            )
            delay(BotThinking.getEvaluationDelay())
            if (stateRef.value.turn != currentTurn || stateRef.value.winner != -1) return@LaunchedEffect

            val bestPiece = LudoEngine.pickBestBotMove(stateRef.value)
            if (bestPiece != null) {
                // Highlight chosen token for a split-second
                onStateChange(
                    stateRef.value.copy(
                        selectedPieceIndex = bestPiece,
                        botStatus = "🎯 ${playerNames[currentTurn]} moving token #${bestPiece + 1}..."
                    )
                )
                delay(350L)
                if (stateRef.value.turn != currentTurn || stateRef.value.winner != -1) return@LaunchedEffect

                // Move token
                val movedState = LudoEngine.movePiece(stateRef.value, currentTurn, bestPiece)
                val summary = if (rolledState.diceValue == 6 && movedState.winner == -1) {
                    "🎉 ${playerNames[currentTurn]} Bot rolled 6! Bonus turn!"
                } else if (movedState.lastMoveSummary.isNotEmpty()) {
                    movedState.lastMoveSummary
                } else {
                    "✨ ${playerNames[currentTurn]} moved token #${bestPiece + 1}"
                }

                onStateChange(
                    movedState.copy(
                        botThinking = false,
                        botStatus = summary,
                        selectedPieceIndex = null
                    )
                )
            } else {
                onStateChange(LudoEngine.passTurn(stateRef.value))
            }
        }
    }

    // Turn countdown timer for human players
    LaunchedEffect(state.turn, state.hasRolled, isTimerPaused, state.winner) {
        if (state.winner != -1 || isTimerPaused || isBotTurn) return@LaunchedEffect
        timeLeftSeconds = 30
        while (timeLeftSeconds > 0) {
            delay(1000L)
            timeLeftSeconds--
        }
        if (timeLeftSeconds == 0 && state.winner == -1 && !isBotTurn) {
            if (!state.hasRolled) {
                val next = LudoEngine.rollDice(state)
                onStateChange(next)
            } else {
                val next = state.copy(hasRolled = false, turn = (state.turn + 1) % 4)
                onStateChange(next)
            }
        }
    }

    val timerProgress = (timeLeftSeconds / 30f).coerceIn(0f, 1f)
    val timerColor = when {
        timeLeftSeconds <= 7 -> Color(0xFFEF4444)
        timeLeftSeconds <= 15 -> Color(0xFFF59E0B)
        else -> Color(0xFF10B981)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B141B)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // TOP CHANCE & TIME LIMIT HEADER
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2C34)),
            border = androidx.compose.foundation.BorderStroke(1.dp, playerColors[state.turn].copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Active player chance avatar
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(playerColors[state.turn].copy(alpha = pulseAlpha), CircleShape)
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            playerNames[state.turn].take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.winner != -1) "🏆 ${playerNames[state.winner]} WINS!" else "${playerNames[state.turn]}'s Turn",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val pid = state.playerIds[state.turn] ?: ""
                            val label = if (pid == "bot") "🤖 Bot" else if (pid == myNodeId) "You" else if (pid.isNotEmpty()) pid.take(4) else "Open"
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = playerColors[state.turn].copy(alpha = 0.25f)
                            ) {
                                Text(
                                    label,
                                    color = playerColors[state.turn],
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = if (state.botStatus.isNotEmpty()) {
                                state.botStatus
                            } else if (isMyTurn) {
                                if (!state.hasRolled) "👉 Tap dice to roll your move" else "👉 Pick highlighted token to advance"
                            } else if (isBotTurn) {
                                "🤖 Bot calculating move..."
                            } else {
                                "Waiting for peer..."
                            },
                            fontSize = 11.sp,
                            color = if (isMyTurn) Color(0xFF34D399) else Color(0xFF94A3B8),
                            maxLines = 1
                        )
                    }
                }

                // SPEED SELECTOR & ACTIONS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Speed pill toggle
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131C21),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF33444D)),
                        modifier = Modifier.clickable {
                            BotThinking.currentPacing = when (BotThinking.currentPacing) {
                                GamePacing.NORMAL -> GamePacing.RELAXED
                                GamePacing.RELAXED -> GamePacing.BLITZ
                                GamePacing.BLITZ -> GamePacing.NORMAL
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(BotThinking.currentPacing.icon, fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                BotThinking.currentPacing.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (state.winner == -1) {
                        // Human timer
                        if (!isBotTurn) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                                CircularProgressIndicator(
                                    progress = { timerProgress },
                                    color = timerColor,
                                    trackColor = Color(0xFF33444D),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    "$timeLeftSeconds",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = timerColor,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { onStateChange(LudoState()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("New Match", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // PLAYER SLOTS BAR WITH TAP-TO-CONFIGURE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 0..3) {
                val score = state.pieces[i]?.count { it >= 57 } ?: 0
                val pid = state.playerIds[i] ?: ""
                val isCurrent = state.turn == i

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCurrent) playerColors[i].copy(alpha = 0.22f) else Color(0xFF131C21),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isCurrent) 1.5.dp else 1.dp,
                        color = if (isCurrent) playerColors[i] else Color(0xFF202C33)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedSlotIndex = i }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(7.dp).background(playerColors[i], CircleShape))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(playerNames[i], fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text("Home: $score/4", fontSize = 8.sp, color = Color(0xFF8696A0))

                        Text(
                            text = when (pid) {
                                myNodeId -> "You"
                                "bot" -> "🤖 Bot"
                                "" -> "+ Claim"
                                else -> pid.take(4)
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (pid) {
                                myNodeId -> Color(0xFF34D399)
                                "bot" -> Color(0xFF38BDF8)
                                "" -> playerColors[i]
                                else -> Color.LightGray
                            }
                        )
                    }
                }
            }
        }

        // QUICK ALL-BOTS & RESET ACTIONS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "🤖 Auto Match (4 Bots)",
                    fontSize = 10.sp,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .clickable {
                            val allBots = mapOf(0 to "bot", 1 to "bot", 2 to "bot", 3 to "bot")
                            onStateChange(state.copy(playerIds = allBots))
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Text(
                    text = "👥 Pass & Play",
                    fontSize = 10.sp,
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .clickable {
                            val openAll = mapOf(0 to "", 1 to "", 2 to "", 3 to "")
                            onStateChange(state.copy(playerIds = openAll))
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (state.lastMoveSummary.isNotEmpty()) {
                Text(
                    text = state.lastMoveSummary,
                    fontSize = 9.sp,
                    color = Color(0xFFFACC15),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // LUDO BOARD SURFACE
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(4.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
        ) {
            val cellSize = maxWidth / 15f

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellPx = cellSize.toPx()
                for (row in 0..14) {
                    for (col in 0..14) {
                        val bgColor = getCellColor(col, row)
                        drawRect(
                            color = bgColor,
                            topLeft = Offset(col * cellPx, row * cellPx),
                            size = Size(cellPx, cellPx)
                        )
                        drawRect(
                            color = Color(0x33000000),
                            topLeft = Offset(col * cellPx, row * cellPx),
                            size = Size(cellPx, cellPx),
                            style = Stroke(width = 1f)
                        )
                    }
                }

                val centerPx = 7.5f * cellPx
                drawCircle(color = Color.White.copy(alpha = 0.2f), radius = cellPx * 0.8f, center = Offset(centerPx, centerPx))
            }

            // PIECE OVERLAYS WITH SELECTION GLOW
            val piecesHere = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Int, Int>>>()
            for (p in 0..3) {
                for (i in 0..3) {
                    val coord = LudoEngine.getCoordinate(p, i, state.pieces[p]!![i])
                    piecesHere.getOrPut(coord) { mutableListOf() }.add(p to i)
                }
            }

            piecesHere.forEach { (coord, pieces) ->
                val (col, row) = coord
                pieces.forEachIndexed { index, pair ->
                    val (p, idx) = pair
                    val piecePos = state.pieces[p]!![idx]
                    val canThisPieceMove = state.turn == p && state.hasRolled && isMyTurn &&
                            (if (piecePos == 0) state.diceValue == 6 else (piecePos + state.diceValue <= 57))
                    val isSelectedByBot = state.turn == p && state.selectedPieceIndex == idx

                    Box(
                        modifier = Modifier
                            .offset(
                                x = (cellSize * col) + (cellSize * 0.12f) + (index * 4).dp,
                                y = (cellSize * row) + (cellSize * 0.12f) + (index * 4).dp
                            )
                            .size(cellSize * 0.76f)
                            .shadow(if (canThisPieceMove || isSelectedByBot) 10.dp else 2.dp, CircleShape)
                            .background(playerColors[p], CircleShape)
                            .border(
                                width = if (canThisPieceMove || isSelectedByBot) 2.5.dp else 1.5.dp,
                                color = if (isSelectedByBot) Color(0xFFFDE047) else if (canThisPieceMove) Color.White else Color(0xCC000000),
                                shape = CircleShape
                            )
                            .clickable {
                                if (canThisPieceMove && !isBotTurn) {
                                    val next = LudoEngine.movePiece(state, p, idx)
                                    onStateChange(next)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(cellSize * 0.32f)
                                .background(Color.White.copy(alpha = 0.85f), CircleShape)
                        )
                    }
                }
            }
        }

        // BOTTOM TACTICAL DICE DECK (SEPARATE & UNBLOCKED)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2C34)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF33444D))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBotTurn) {
                            "🤖 BOT TURN"
                        } else if (!state.hasRolled) {
                            "ROLL THE DICE"
                        } else {
                            "SELECT HIGHLIGHTED PIECE"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isBotTurn) Color(0xFF38BDF8) else if (!state.hasRolled) Color(0xFF25D366) else Color(0xFFF59E0B)
                    )
                    Text(
                        text = if (isBotTurn) {
                            state.botStatus.ifEmpty { "Bot is calculating move..." }
                        } else if (!state.hasRolled) {
                            if (isMyTurn) "Tap dice button to generate move" else "Waiting for opponent's turn..."
                        } else {
                            "Advance token by ${state.diceValue} steps"
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF8696A0),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 3D TACTICAL DICE BUTTON
                val diceEnabled = !state.hasRolled && isMyTurn && !isBotTurn
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (diceEnabled) playerColors[state.turn] else Color(0xFF2A3942)
                        )
                        .border(
                            2.dp,
                            if (diceEnabled) Color.White else Color(0xFF374955),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable(enabled = diceEnabled) {
                            val next = LudoEngine.rollDice(state)
                            onStateChange(next)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.hasRolled || state.diceValue > 0) {
                        Text(
                            state.diceValue.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            Icons.Default.Casino,
                            contentDescription = "Roll Dice",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }

    // SLOT CONFIGURATION DIALOG
    if (selectedSlotIndex != null) {
        val slotIdx = selectedSlotIndex!!
        val currentPid = state.playerIds[slotIdx] ?: ""
        AlertDialog(
            onDismissRequest = { selectedSlotIndex = null },
            title = {
                Text("${playerNames[slotIdx]} Player Slot", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Currently: ${
                            when (currentPid) {
                                myNodeId -> "Occupied by You"
                                "bot" -> "Controlled by 🤖 Bot"
                                "" -> "Open / Pass & Play"
                                else -> "Peer ($currentPid)"
                            }
                        }",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val newIds = state.playerIds.toMutableMap()
                            newIds[slotIdx] = myNodeId
                            onStateChange(state.copy(playerIds = newIds))
                            selectedSlotIndex = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = playerColors[slotIdx])
                    ) {
                        Text("Play Here (You)")
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            val newIds = state.playerIds.toMutableMap()
                            newIds[slotIdx] = "bot"
                            onStateChange(state.copy(playerIds = newIds))
                            selectedSlotIndex = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("Assign 🤖 Bot to Slot", color = Color(0xFF38BDF8))
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            val newIds = state.playerIds.toMutableMap()
                            newIds[slotIdx] = ""
                            onStateChange(state.copy(playerIds = newIds))
                            selectedSlotIndex = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Make Slot Open (Pass & Play)", color = Color.White)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedSlotIndex = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun getCellColor(col: Int, row: Int): Color {
    val red = Color(0xFFB91C1C)
    val green = Color(0xFF047857)
    val yellow = Color(0xFFB45309)
    val blue = Color(0xFF1D4ED8)
    val empty = Color(0xFF1E293B)
    val safeStar = Color(0xFF334155)

    // Base quadrants
    if (col in 0..5 && row in 0..5) return red
    if (col in 9..14 && row in 0..5) return green
    if (col in 9..14 && row in 9..14) return yellow
    if (col in 0..5 && row in 9..14) return blue
    if (col in 6..8 && row in 6..8) return Color(0xFF0F172A)

    // Safe home paths
    if (col in 1..5 && row == 7) return red.copy(alpha = 0.85f)
    if (col == 7 && row in 1..5) return green.copy(alpha = 0.85f)
    if (col in 9..13 && row == 7) return yellow.copy(alpha = 0.85f)
    if (col == 7 && row in 9..13) return blue.copy(alpha = 0.85f)

    // Starting arrow squares
    if (col == 1 && row == 6) return red
    if (col == 8 && row == 1) return green
    if (col == 13 && row == 8) return yellow
    if (col == 6 && row == 13) return blue

    // Safe star zones
    if ((col == 6 && row == 2) || (col == 12 && row == 6) || (col == 8 && row == 12) || (col == 2 && row == 8)) {
        return safeStar
    }

    return empty
}
