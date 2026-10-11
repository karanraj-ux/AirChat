package com.aetherweb.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.ChatMessage
import kotlin.math.roundToInt

/**
 * Non-Blocking Floating Picture-in-Picture (PIP) Chat Overlay:
 *
 * Placed non-intrusively in the upper right/movable area so it NEVER covers
 * the bottom dice controls, turn decks, chess promotion controls, or game columns!
 *
 * Features:
 * 1. Floating Chat Trigger with Unread Message Badge.
 * 2. Draggable anywhere on the screen so players can position it freely.
 * 3. Quick Emoji Reaction strip (send emojis without opening keyboard).
 * 4. Translucency toggle (solid vs semi-transparent) to see board while reading.
 * 5. Compact floating window (top/middle anchored) leaving bottom game deck 100% visible.
 */
@Composable
fun GamePipChatOverlay(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var isTranslucent by remember { mutableStateOf(false) }
    var lastReadCount by remember { mutableIntStateOf(messages.size) }
    val listState = rememberLazyListState()

    val quickEmojis = listOf("🔥", "👏", "😂", "🎯", "💀", "⚡", "🥳", "🎲", "👑")

    // Track unread messages when collapsed
    val unreadCount = if (isExpanded) 0 else (messages.size - lastReadCount).coerceAtLeast(0)

    LaunchedEffect(isExpanded, messages.size) {
        if (isExpanded) {
            lastReadCount = messages.size
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Draggable position offset (default starts at upper right, avoiding top bar and bottom dice)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 70.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            // EXPANDED NON-BLOCKING FLOATING CHAT CARD
            AnimatedVisibility(
                visible = isExpanded,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .width(310.dp)
                        .height(260.dp)
                        .border(
                            1.dp,
                            Color(0xFF25D366).copy(alpha = if (isTranslucent) 0.3f else 0.6f),
                            RoundedCornerShape(16.dp)
                        )
                        .shadow(12.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTranslucent) Color(0xD9111B21) else Color(0xF7111B21)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        // HEADER WITH CLOSE & TRANSLUCENCY TOGGLE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF25D366), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "In-Game Chat",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• Drag to move",
                                    fontSize = 9.sp,
                                    color = Color.Gray
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { isTranslucent = !isTranslucent },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Opacity,
                                        contentDescription = "Toggle Transparency",
                                        tint = if (isTranslucent) Color(0xFF25D366) else Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { isExpanded = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Chat",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // QUICK EMOJI BAR
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(quickEmojis) { emoji ->
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF1F2C34))
                                        .clickable { onSendMessage(emoji) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 14.sp)
                                }
                            }
                        }

                        // MESSAGE LIST
                        val recentMessages = messages.takeLast(25)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF0B141B).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (recentMessages.isEmpty()) {
                                item {
                                    Text(
                                        text = "Say hi to opponents & peers!",
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(6.dp)
                                    )
                                }
                            } else {
                                itemsIndexed(recentMessages) { _, msg ->
                                    val isMe = msg.isFromMe
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isMe) Color(0xFF005D4B) else Color(0xFF202C33))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Column {
                                                if (!isMe) {
                                                    Text(
                                                        text = msg.senderName,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF53BDEB),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Text(
                                                    text = msg.message,
                                                    fontSize = 11.sp,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // TEXT INPUT ROW
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                placeholder = { Text("Chat...", fontSize = 11.sp, color = Color.Gray) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF25D366),
                                    unfocusedBorderColor = Color(0xFF33444D),
                                    focusedContainerColor = Color(0xFF202C33),
                                    unfocusedContainerColor = Color(0xFF202C33)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF25D366))
                                    .clickable {
                                        if (chatInput.isNotBlank()) {
                                            onSendMessage(chatInput.trim())
                                            chatInput = ""
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // FLOATING TRIGGER PILL & QUICK EMOJI MINI BAR
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Quick emoji shortcut (without opening chat)
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF1F2C34),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF33444D)),
                    modifier = Modifier.clickable { onSendMessage("🎯") }
                ) {
                    Text(
                        "🎯",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Chat trigger pill with unread badge
                BadgedBox(
                    badge = {
                        if (unreadCount > 0) {
                            Badge(
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White
                            ) {
                                Text("$unreadCount", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF25D366))
                            .clickable {
                                isExpanded = !isExpanded
                                if (isExpanded) lastReadCount = messages.size
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = "In-game Chat",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isExpanded) "Hide" else "Chat",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}
