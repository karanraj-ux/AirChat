package com.aetherweb.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.BotThinking
import com.aetherweb.app.ChessEngine
import com.aetherweb.app.ChessState
import com.aetherweb.app.GamePacing
import com.aetherweb.app.MeshNetworkManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun ChessScreen(
    state: ChessState,
    onStateChange: (ChessState) -> Unit,
    modifier: Modifier = Modifier
) {
    val myNodeId = MeshNetworkManager.localNodeId
    
    val isWhite = state.whitePlayerId == myNodeId
    val isBlack = state.blackPlayerId == myNodeId
    val isSpectator = !isWhite && !isBlack
    val isPassAndPlay = state.whitePlayerId.isEmpty() && state.blackPlayerId.isEmpty()
    val isMyTurn = (state.isWhiteTurn && (isWhite || isPassAndPlay)) || (!state.isWhiteTurn && (isBlack || isPassAndPlay))
    val p1 = if (state.whitePlayerId == "bot") "🤖 Bot White" else if (state.whitePlayerId.isNotEmpty()) state.whitePlayerId.take(8) else "White"
    val p2 = if (state.blackPlayerId == "bot") "🤖 Bot Black" else if (state.blackPlayerId.isNotEmpty()) state.blackPlayerId.take(8) else "Black"

    // Universal Bot Coordinator: Handles White bot, Black bot, or Bot vs Bot with human pacing!
    val stateRef = rememberUpdatedState(state)
    LaunchedEffect(state.isWhiteTurn, state.whitePlayerId, state.blackPlayerId, state.winner) {
        if (state.winner.isNotEmpty()) return@LaunchedEffect
        val botToMove = (state.isWhiteTurn && state.whitePlayerId == "bot") ||
                (!state.isWhiteTurn && state.blackPlayerId == "bot")
        if (!botToMove) return@LaunchedEffect

        delay(BotThinking.getTurnAwarenessDelay())
        if (stateRef.value.winner.isNotEmpty()) return@LaunchedEffect
        
        onStateChange(stateRef.value.copy(botThinking = true))
        delay(BotThinking.getEvaluationDelay())
        
        if (stateRef.value.botThinking && stateRef.value.winner.isEmpty()) {
            val next = ChessEngine.makeBotMove(stateRef.value).copy(botThinking = false)
            onStateChange(next)
        }
    }

    var selectedIdx by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF222222)), horizontalAlignment = Alignment.CenterHorizontally) {
        
        GameTurnHeader(
            gameName = "♟️ Offline Chess",
            player1Name = p1,
            player1Color = Color(0xFFEEEEEE),
            player2Name = p2,
            player2Color = Color(0xFF769656),
            isPlayer1Turn = state.isWhiteTurn,
            isMyTurn = isMyTurn,
            winner = state.winner
        )

        // SPEED & THINKING BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.botThinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF769656))
                    Text(
                        if (state.isWhiteTurn) "🤖 Bot White evaluating moves..." else "🤖 Bot Black evaluating moves...",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                }
            } else {
                Text(
                    if (state.winner.isNotEmpty()) "Match complete" else if (isMyTurn) "👉 Your turn" else "Waiting for opponent...",
                    fontSize = 11.sp,
                    color = if (isMyTurn) Color(0xFF34D399) else Color.Gray
                )
            }

            // Pacing pill toggle
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF333333),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
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
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
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
        }

        // Roles & Robust Slot Management
        Card(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
        ) {
            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.winner.isNotEmpty()) {
                    Button(onClick = { onStateChange(ChessEngine.reset()) }, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("New Match")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // White Slot
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("White Piece", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.whitePlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, myNodeId, "white")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, "bot", "white")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isWhite -> {
                                Button(onClick = { onStateChange(ChessEngine.leaveRole(state, myNodeId)) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.whitePlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, myNodeId, "white")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take Over", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(ChessEngine.leaveRole(state, "bot")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.whitePlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }

                    // Black Slot
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Black Piece", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF769656))
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.blackPlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, myNodeId, "black")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, "bot", "black")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isBlack -> {
                                Button(onClick = { onStateChange(ChessEngine.leaveRole(state, myNodeId)) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.blackPlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(ChessEngine.claimRole(state, myNodeId, "black")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take Over", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(ChessEngine.leaveRole(state, "bot")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.blackPlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }

        // Quick Mode Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🤖 Bot vs Bot Match",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF769656),
                modifier = Modifier
                    .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(whitePlayerId = "bot", blackPlayerId = "bot"))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )

            Text(
                "👥 Pass & Play",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(whitePlayerId = "", blackPlayerId = ""))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        // Board
        Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(6.dp).border(4.dp, Color(0xFF5D4037))) {
            Column(modifier = Modifier.fillMaxSize()) {
                for (row in 0..7) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (col in 0..7) {
                            val isLight = (row + col) % 2 == 0
                            val bgColor = if (isLight) Color(0xFFD7CCC8) else Color(0xFF795548)
                            val idx = row * 8 + col
                            val pieceStr = state.pieces[idx]
                            
                            val isSelected = selectedIdx == idx
                            val cellColor = if (isSelected) Color(0xFFFFF59D) else bgColor

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(cellColor)
                                    .clickable {
                                        val isPassAndPlay = state.whitePlayerId.isEmpty() && state.blackPlayerId.isEmpty()
                                        if ((isSpectator && !isPassAndPlay) || state.winner.isNotEmpty()) return@clickable
                                        
                                        if (selectedIdx == null) {
                                            // Select piece
                                            if (pieceStr.isNotEmpty()) {
                                                val pieceIsWhite = pieceStr.first().isUpperCase()
                                                val canSelect = (isWhite && pieceIsWhite && state.isWhiteTurn) ||
                                                                (isBlack && !pieceIsWhite && !state.isWhiteTurn) ||
                                                                (isPassAndPlay && ((pieceIsWhite && state.isWhiteTurn) || (!pieceIsWhite && !state.isWhiteTurn)))
                                                if (canSelect) {
                                                    selectedIdx = idx
                                                }
                                            }
                                        } else {
                                            // Move piece
                                            if (selectedIdx == idx) {
                                                selectedIdx = null // Deselect
                                            } else {
                                                val newState = ChessEngine.move(state, selectedIdx!!, idx, myNodeId)
                                                onStateChange(newState)
                                                selectedIdx = null
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (pieceStr.isNotEmpty()) {
                                    val pieceChar = getPieceChar(pieceStr)
                                    val isPieceWhite = pieceStr.first().isUpperCase()
                                    Text(
                                        text = pieceChar,
                                        fontSize = 32.sp,
                                        color = if (isPieceWhite) Color.White else Color.Black,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getPieceChar(piece: String): String {
    return when (piece) {
        "P" -> "♙"
        "R" -> "♖"
        "N" -> "♘"
        "B" -> "♗"
        "Q" -> "♕"
        "K" -> "♔"
        "p" -> "♟"
        "r" -> "♜"
        "n" -> "♞"
        "b" -> "♝"
        "q" -> "♛"
        "k" -> "♚"
        else -> ""
    }
}
