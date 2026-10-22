package com.aetherweb.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.BotThinking
import com.aetherweb.app.GamePacing
import com.aetherweb.app.MeshNetworkManager
import kotlinx.coroutines.delay

// TIC-TAC-TOE
@Composable
fun TicTacToeScreen(
    state: com.aetherweb.app.TicTacToeState,
    onStateChange: (com.aetherweb.app.TicTacToeState) -> Unit,
    modifier: Modifier = Modifier
) {
    val myNodeId = MeshNetworkManager.localNodeId
    val isX = state.xPlayerId == myNodeId
    val isO = state.oPlayerId == myNodeId

    // Universal Bot Coordinator: Handles Bot X, Bot O, or Bot vs Bot with human pacing!
    val stateRef = rememberUpdatedState(state)
    LaunchedEffect(state.isXTurn, state.xPlayerId, state.oPlayerId, state.winner) {
        if (state.winner.isNotEmpty()) return@LaunchedEffect
        val isBotToMove = (state.isXTurn && state.xPlayerId == "bot") ||
                (!state.isXTurn && state.oPlayerId == "bot")
        if (!isBotToMove) return@LaunchedEffect

        delay(BotThinking.getTurnAwarenessDelay())
        if (stateRef.value.winner.isNotEmpty()) return@LaunchedEffect

        onStateChange(stateRef.value.copy(botThinking = true))
        delay(BotThinking.getEvaluationDelay())

        if (stateRef.value.botThinking && stateRef.value.winner.isEmpty()) {
            val next = stateRef.value.makeBotMove().copy(botThinking = false)
            onStateChange(next)
        }
    }

    val isPassAndPlay = state.xPlayerId.isEmpty() && state.oPlayerId.isEmpty()
    val isMyTurn = (state.isXTurn && (isX || isPassAndPlay)) || (!state.isXTurn && (isO || isPassAndPlay))
    val p1 = if (state.xPlayerId == "bot") "🤖 Bot X" else if (state.xPlayerId.isNotEmpty()) state.xPlayerId.take(8) else "Player X"
    val p2 = if (state.oPlayerId == "bot") "🤖 Bot O" else if (state.oPlayerId.isNotEmpty()) state.oPlayerId.take(8) else "Player O"

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GameTurnHeader(
            gameName = "⭕ Tic-Tac-Toe",
            player1Name = p1,
            player1Color = Color(0xFF25D366),
            player2Name = p2,
            player2Color = Color(0xFF53BDEB),
            isPlayer1Turn = state.isXTurn,
            isMyTurn = isMyTurn,
            winner = state.winner
        )

        // SPEED & STATUS ROW
        Row(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.botThinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.isXTurn) "🤖 Bot X evaluating grid..." else "🤖 Bot O evaluating grid...",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            } else {
                Text(
                    if (state.winner.isNotEmpty()) "Match complete" else if (isMyTurn) "👉 Your turn" else "Waiting for opponent...",
                    fontSize = 11.sp,
                    color = if (isMyTurn) Color(0xFF34D399) else Color.Gray
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // SLOTS & ROLE CONTROLS
        Card(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.winner.isNotEmpty()) {
                    Button(onClick = { onStateChange(com.aetherweb.app.TicTacToeState()) }, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("Play Again", fontSize = 11.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // Slot X
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Slot X", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF25D366))
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.xPlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(xPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(xPlayerId = "bot")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isX -> {
                                Button(onClick = { onStateChange(state.copy(xPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.xPlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(xPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(xPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.xPlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }

                    // Slot O
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Slot O", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF53BDEB))
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.oPlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(oPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(oPlayerId = "bot")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isO -> {
                                Button(onClick = { onStateChange(state.copy(oPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.oPlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(oPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(oPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.oPlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }

        // Quick Mode Row
        Row(
            modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🤖 Bot vs Bot Match",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF25D366),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(xPlayerId = "bot", oPlayerId = "bot"))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )

            Text(
                "👥 Pass & Play",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(xPlayerId = "", oPlayerId = ""))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid
        Box(modifier = Modifier.size(280.dp).background(MaterialTheme.colorScheme.onBackground).padding(4.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                for (row in 0..2) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (col in 0..2) {
                            val idx = row * 3 + col
                            val cell = state.board[idx]
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(2.dp)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        if (state.winner.isNotEmpty() || cell.isNotEmpty()) return@clickable
                                        val isPassAndPlayGrid = state.xPlayerId.isEmpty() && state.oPlayerId.isEmpty()
                                        if (state.isXTurn && (isX || isPassAndPlayGrid)) {
                                            val newBoard = state.board.toMutableList()
                                            newBoard[idx] = "X"
                                            val nextState = state.copy(board = newBoard, isXTurn = false).checkWinner()
                                            onStateChange(nextState)
                                        } else if (!state.isXTurn && (isO || isPassAndPlayGrid)) {
                                            val newBoard = state.board.toMutableList()
                                            newBoard[idx] = "O"
                                            val nextState = state.copy(board = newBoard, isXTurn = true).checkWinner()
                                            onStateChange(nextState)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cell,
                                    fontSize = 44.sp,
                                    color = if (cell == "X") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// CONNECT 4
@Composable
fun Connect4Screen(
    state: com.aetherweb.app.Connect4State,
    onStateChange: (com.aetherweb.app.Connect4State) -> Unit,
    modifier: Modifier = Modifier
) {
    val myNodeId = MeshNetworkManager.localNodeId
    val isRed = state.redPlayerId == myNodeId
    val isYellow = state.yellowPlayerId == myNodeId

    // Universal Bot Coordinator: Handles Red bot, Yellow bot, or Bot vs Bot with human pacing!
    val stateRef = rememberUpdatedState(state)
    LaunchedEffect(state.isRedTurn, state.redPlayerId, state.yellowPlayerId, state.winner) {
        if (state.winner.isNotEmpty()) return@LaunchedEffect
        val isBotToMove = (state.isRedTurn && state.redPlayerId == "bot") ||
                (!state.isRedTurn && state.yellowPlayerId == "bot")
        if (!isBotToMove) return@LaunchedEffect

        delay(BotThinking.getTurnAwarenessDelay())
        if (stateRef.value.winner.isNotEmpty()) return@LaunchedEffect

        onStateChange(stateRef.value.copy(botThinking = true))
        delay(BotThinking.getEvaluationDelay())

        if (stateRef.value.botThinking && stateRef.value.winner.isEmpty()) {
            val next = stateRef.value.makeBotMove().copy(botThinking = false)
            onStateChange(next)
        }
    }

    val isPassAndPlay = state.redPlayerId.isEmpty() && state.yellowPlayerId.isEmpty()
    val isMyTurn = (state.isRedTurn && (isRed || isPassAndPlay)) || (!state.isRedTurn && (isYellow || isPassAndPlay))
    val p1 = if (state.redPlayerId == "bot") "🤖 Bot Red" else if (state.redPlayerId.isNotEmpty()) state.redPlayerId.take(8) else "Red"
    val p2 = if (state.yellowPlayerId == "bot") "🤖 Bot Yellow" else if (state.yellowPlayerId.isNotEmpty()) state.yellowPlayerId.take(8) else "Yellow"

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GameTurnHeader(
            gameName = "🔴 Connect 4",
            player1Name = p1,
            player1Color = Color(0xFFEF4444),
            player2Name = p2,
            player2Color = Color(0xFFFFB300),
            isPlayer1Turn = state.isRedTurn,
            isMyTurn = isMyTurn,
            winner = state.winner
        )

        // SPEED & STATUS ROW
        Row(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.botThinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.isRedTurn) "🤖 Bot Red analyzing columns..." else "🤖 Bot Yellow analyzing columns...",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            } else {
                Text(
                    if (state.winner.isNotEmpty()) "Match complete" else if (isMyTurn) "👉 Your turn" else "Waiting for opponent...",
                    fontSize = 11.sp,
                    color = if (isMyTurn) Color(0xFF34D399) else Color.Gray
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // SLOTS & ROLE CONTROLS
        Card(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.winner.isNotEmpty()) {
                    Button(onClick = { onStateChange(com.aetherweb.app.Connect4State()) }, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("Play Again", fontSize = 11.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // Slot Red
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Red Disc", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.redPlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(redPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(redPlayerId = "bot")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isRed -> {
                                Button(onClick = { onStateChange(state.copy(redPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.redPlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(redPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(redPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.redPlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }

                    // Slot Yellow
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Yellow Disc", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                        Spacer(modifier = Modifier.height(2.dp))
                        when {
                            state.yellowPlayerId.isEmpty() -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(yellowPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Play", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(yellowPlayerId = "bot")) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🤖 Bot", fontSize = 10.sp) }
                                }
                            }
                            isYellow -> {
                                Button(onClick = { onStateChange(state.copy(yellowPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Leave", fontSize = 10.sp) }
                            }
                            state.yellowPlayerId == "bot" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { onStateChange(state.copy(yellowPlayerId = myNodeId)) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Take", fontSize = 10.sp) }
                                    Button(onClick = { onStateChange(state.copy(yellowPlayerId = "")) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Kick", fontSize = 10.sp) }
                                }
                            }
                            else -> Text("Taken (${state.yellowPlayerId.take(4)})", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }

        // Quick Mode Row
        Row(
            modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🤖 Bot vs Bot Match",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEF4444),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(redPlayerId = "bot", yellowPlayerId = "bot"))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )

            Text(
                "👥 Pass & Play",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable {
                        onStateChange(state.copy(redPlayerId = "", yellowPlayerId = ""))
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Connect 4 Grid
        Box(modifier = Modifier.aspectRatio(7f/6f).fillMaxWidth(0.9f).background(Color(0xFF1565C0), RoundedCornerShape(8.dp)).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxSize()) {
                val isPassAndPlayGrid = state.redPlayerId.isEmpty() && state.yellowPlayerId.isEmpty()
                for (col in 0..6) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (state.winner.isNotEmpty()) return@clickable
                                val canMove = (state.isRedTurn && (isRed || isPassAndPlayGrid)) ||
                                        (!state.isRedTurn && (isYellow || isPassAndPlayGrid))
                                if (canMove) {
                                    // Find lowest empty slot in this column
                                    for (row in 5 downTo 0) {
                                        if (state.board[row][col] == 0) {
                                            val newBoard = state.board.map { it.toMutableList() }.toMutableList()
                                            newBoard[row][col] = if (state.isRedTurn) 1 else 2
                                            val nextState = state.copy(board = newBoard, isRedTurn = !state.isRedTurn).checkWinner()
                                            onStateChange(nextState)
                                            break
                                        }
                                    }
                                }
                            }
                    ) {
                        for (row in 0..5) {
                            val cell = state.board[row][col]
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(4.dp)
                                    .background(
                                        color = when(cell) {
                                            1 -> Color.Red
                                            2 -> Color(0xFFFFB300)
                                            else -> MaterialTheme.colorScheme.background
                                        },
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
