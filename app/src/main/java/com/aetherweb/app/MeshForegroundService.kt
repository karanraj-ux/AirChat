package com.aetherweb.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

object MeshNetworkManager {
    val _uiState = MutableStateFlow(MeshState())
    val uiState: StateFlow<MeshState> = _uiState.asStateFlow()

    val cryptoManager = CryptoManager()
    var localNodeId = CryptoManager.computeNodeId(cryptoManager.publicKeyBase64)
        private set
    val meshRouter = MeshRouter(localNodeId, cryptoManager)

    init {
        meshRouter.onPacketRouted = {
            _uiState.update { it.copy(packetsRoutedCount = it.packetsRoutedCount + 1) }
        }
    }

    private var identityInitialized = false

    /**
     * bitchat parity: stable identity across restarts. Swaps the ephemeral
     * keypair for the persisted one and updates the router — call once at app
     * start (idempotent). Safe to call after first access: nothing sends before
     * the service/viewmodel are up.
     */
    fun initPersistentIdentity(context: android.content.Context) {
        if (identityInitialized) return
        identityInitialized = true
        try {
            cryptoManager.loadOrCreatePersistentIdentity(context.applicationContext)
            localNodeId = CryptoManager.computeNodeId(cryptoManager.publicKeyBase64)
            meshRouter.updateLocalIdentity(localNodeId)
            android.util.Log.i("MeshNetworkManager", "Persistent mesh identity ready: $localNodeId")
        } catch (e: Exception) {
            android.util.Log.w("MeshNetworkManager", "Persistent identity init failed", e)
        }
    }
    
    var roomAesKey: javax.crypto.SecretKey? = null
    var myRsaKeyPair: java.security.KeyPair? = null

    var webSessionSalt: ByteArray = CryptoManager.generateSalt()
    var webSessionPin: String = String.format("%06d", (100000..999999).random())
    var webSessionKey: javax.crypto.SecretKey? = null
    var webSessionKeyHex: String = ""
    var webSessionSaltHex: String = ""
    var webSessionFingerprint: String = ""

    fun initializeWebSessionKeys(regenerate: Boolean = false) {
        if (webSessionKey == null || regenerate) {
            webSessionSalt = CryptoManager.generateSalt()
            webSessionPin = String.format("%06d", (100000..999999).random())
            val derived = CryptoManager.deriveKeyFromPin(webSessionPin, webSessionSalt)
            webSessionKey = derived
            webSessionKeyHex = CryptoManager.bytesToHex(derived.encoded)
            webSessionSaltHex = CryptoManager.bytesToHex(webSessionSalt)
            webSessionFingerprint = CryptoManager.fingerprint(derived)
            _uiState.update {
                it.copy(
                    webSessionPin = webSessionPin,
                    webSessionKeyHex = webSessionKeyHex,
                    webSessionSaltHex = webSessionSaltHex,
                    webSessionFingerprint = webSessionFingerprint,
                    isWebE2eeArmed = true
                )
            }
            android.util.Log.i("MeshNetworkManager", "E2EE WebCrypto initialized. PIN: $webSessionPin, Fingerprint: $webSessionFingerprint")
        }
    }

    fun initializeKeys() {
        if (myRsaKeyPair == null) {
            myRsaKeyPair = CryptoManager.generateRSAKeyPair()
        }
        if (roomAesKey == null) {
            roomAesKey = CryptoManager.generateAESKey()
        }
        initializeWebSessionKeys()
    }

    @SuppressLint("StaticFieldLeak")
    var webServerManager: WebServerManager? = null
    @SuppressLint("StaticFieldLeak")
    var hotspotManager: HotspotManager? = null
    @SuppressLint("StaticFieldLeak")
    var bleMeshManager: BleMeshManager? = null
    @SuppressLint("StaticFieldLeak")
    var wifiSocketManager: WifiSocketManager? = null
    @SuppressLint("StaticFieldLeak")
    var highSpeedFileTransferManager: HighSpeedFileTransferManager? = null
    
    var isInitialized = false

    // Door 4 fan-out: every guest-visible message goes to WS browsers AND
    // subscribed BLE web guests. Safe to call before either manager exists
    // (both calls no-op on null). Never throws.
    fun fanOutToGuests(text: String, sender: String) {
        try { webServerManager?.broadcastMessage(text, sender) } catch (e: Exception) { }
        try { bleMeshManager?.notifyWebBleGuests(text, sender) } catch (e: Exception) { }
    }

    fun kickParticipant(idOrIp: String, displayName: String = idOrIp) {
        webServerManager?.disconnectClient(idOrIp)
        wifiSocketManager?.disconnectClient(idOrIp)
        _uiState.update { state ->
            val nextApproved = state.approvedSpectators.filter { it != idOrIp }
            val nextVoice = state.approvedVoiceGuests.filter { it != idOrIp }
            val nextNodes = state.connectedNodes.filter { it.id != idOrIp }
            state.copy(
                approvedSpectators = nextApproved,
                approvedVoiceGuests = nextVoice,
                connectedNodes = nextNodes,
                pendingSpectators = state.pendingSpectators.filter { it.ip != idOrIp },
                pendingVoiceRequests = state.pendingVoiceRequests.filter { it.ip != idOrIp },
                kickedIps = state.kickedIps + idOrIp,
                messages = state.messages + ChatMessage(
                    senderName = "System",
                    senderId = "system",
                    message = "🚫 Host removed & blocked participant: $displayName",
                    isFromMe = false
                )
            )
        }
    }

    fun handleWifiPeerDisconnected(disconnectedIp: String) {
        val currentState = _uiState.value
        if (!currentState.isHotspotActive) return

        // 1. Tic-Tac-Toe disconnect fallback to bot
        var tttChanged = false
        var ttt = currentState.ticTacToeState
        if (ttt.xPlayerId.contains(disconnectedIp) || (ttt.xPlayerId.isNotEmpty() && ttt.xPlayerId != localNodeId && ttt.xPlayerId != "bot" && !currentState.connectedNodes.any { it.id == ttt.xPlayerId })) {
            ttt = ttt.copy(xPlayerId = "bot")
            tttChanged = true
        }
        if (ttt.oPlayerId.contains(disconnectedIp) || (ttt.oPlayerId.isNotEmpty() && ttt.oPlayerId != localNodeId && ttt.oPlayerId != "bot" && !currentState.connectedNodes.any { it.id == ttt.oPlayerId })) {
            ttt = ttt.copy(oPlayerId = "bot")
            tttChanged = true
        }
        if (tttChanged) {
            _uiState.update { it.copy(ticTacToeState = ttt) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("tictactoe", ttt.toJson())
            meshRouter.routePacket(packet)
            fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 2. Connect 4 disconnect fallback to bot
        var c4Changed = false
        var c4 = currentState.connect4State
        if (c4.redPlayerId.contains(disconnectedIp) || (c4.redPlayerId.isNotEmpty() && c4.redPlayerId != localNodeId && c4.redPlayerId != "bot" && !currentState.connectedNodes.any { it.id == c4.redPlayerId })) {
            c4 = c4.copy(redPlayerId = "bot")
            c4Changed = true
        }
        if (c4.yellowPlayerId.contains(disconnectedIp) || (c4.yellowPlayerId.isNotEmpty() && c4.yellowPlayerId != localNodeId && c4.yellowPlayerId != "bot" && !currentState.connectedNodes.any { it.id == c4.yellowPlayerId })) {
            c4 = c4.copy(yellowPlayerId = "bot")
            c4Changed = true
        }
        if (c4Changed) {
            _uiState.update { it.copy(connect4State = c4) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("connect4", c4.toJson())
            meshRouter.routePacket(packet)
            fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 3. Chess disconnect fallback to bot
        var chessChanged = false
        var chess = currentState.chessState
        if (chess.whitePlayerId.contains(disconnectedIp) || (chess.whitePlayerId.isNotEmpty() && chess.whitePlayerId != localNodeId && chess.whitePlayerId != "bot" && !currentState.connectedNodes.any { it.id == chess.whitePlayerId })) {
            chess = chess.copy(whitePlayerId = "bot")
            chessChanged = true
        }
        if (chess.blackPlayerId.contains(disconnectedIp) || (chess.blackPlayerId.isNotEmpty() && chess.blackPlayerId != localNodeId && chess.blackPlayerId != "bot" && !currentState.connectedNodes.any { it.id == chess.blackPlayerId })) {
            chess = chess.copy(blackPlayerId = "bot")
            chessChanged = true
        }
        if (chessChanged) {
            _uiState.update { it.copy(chessState = chess) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("chess", chess.toJson().toString())
            meshRouter.routePacket(packet)
            fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 4. Ludo disconnect fallback to bot
        var ludoChanged = false
        var ludo = currentState.ludoState
        val updatedIds = ludo.playerIds.toMutableMap()
        for (i in 0..3) {
            val pid = updatedIds[i] ?: ""
            if (pid.contains(disconnectedIp) || (pid.isNotEmpty() && pid != localNodeId && pid != "bot" && !currentState.connectedNodes.any { it.id == pid })) {
                updatedIds[i] = "bot"
                ludoChanged = true
            }
        }
        if (ludoChanged) {
            ludo = ludo.copy(playerIds = updatedIds)
            _uiState.update { it.copy(ludoState = ludo) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("ludo", ludo.toJson().toString())
            meshRouter.routePacket(packet)
            fanOutToGuests(packet.toJsonString(), "Host")
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        // bitchat parity: stable identity across restarts — before anything sends
        initPersistentIdentity(context)
        meshRouter.localNodeName = android.os.Build.MODEL

        val appContext = context.applicationContext

        // Handle Web Game Actions (moves, slot claims, rolls)
        fun handleWebGameAction(json: JSONObject, sender: String): Boolean {
            val gameType = json.optString("gameType")
            val action = json.optString("action")
            val currentState = MeshNetworkManager._uiState.value

            when (gameType) {
                "tictactoe" -> {
                    var state = currentState.ticTacToeState
                    when (action) {
                        "claim_slot" -> {
                            val role = json.optString("role")
                            val isBot = json.optBoolean("isBot", false)
                            val occupant = if (isBot) "bot" else sender
                            state = if (role == "X") state.copy(xPlayerId = occupant) else state.copy(oPlayerId = occupant)
                        }
                        "leave_slot" -> {
                            val role = json.optString("role")
                            state = if (role == "X") state.copy(xPlayerId = "") else state.copy(oPlayerId = "")
                        }
                        "move" -> {
                            val idx = json.optInt("index", -1)
                            if (idx in 0..8 && state.board[idx].isEmpty() && state.winner.isEmpty()) {
                                val isX = state.xPlayerId == sender || state.xPlayerId.isEmpty()
                                val isO = state.oPlayerId == sender || state.oPlayerId.isEmpty()
                                if (state.isXTurn && isX) {
                                    val newB = state.board.toMutableList()
                                    newB[idx] = "X"
                                    state = state.copy(board = newB, isXTurn = false).checkWinner()
                                } else if (!state.isXTurn && isO) {
                                    val newB = state.board.toMutableList()
                                    newB[idx] = "O"
                                    state = state.copy(board = newB, isXTurn = true).checkWinner()
                                }
                            }
                        }
                        "reset" -> {
                            state = com.aetherweb.app.TicTacToeState(
                                xPlayerId = state.xPlayerId,
                                oPlayerId = state.oPlayerId
                            )
                        }
                    }
                    MeshNetworkManager._uiState.update { it.copy(ticTacToeState = state) }
                    val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("tictactoe", state.toJson())
                    MeshNetworkManager.meshRouter.routePacket(packet)
                    fanOutToGuests(packet.toJsonString(), "Host")
                    return true
                }
                "connect4" -> {
                    var state = currentState.connect4State
                    when (action) {
                        "claim_slot" -> {
                            val role = json.optString("role")
                            val isBot = json.optBoolean("isBot", false)
                            val occupant = if (isBot) "bot" else sender
                            state = if (role == "red") state.copy(redPlayerId = occupant) else state.copy(yellowPlayerId = occupant)
                        }
                        "leave_slot" -> {
                            val role = json.optString("role")
                            state = if (role == "red") state.copy(redPlayerId = "") else state.copy(yellowPlayerId = "")
                        }
                        "drop" -> {
                            val col = json.optInt("col", -1)
                            if (col in 0..6 && state.winner.isEmpty() && state.board[0][col] == 0) {
                                val isRed = state.redPlayerId == sender || state.redPlayerId.isEmpty()
                                val isYellow = state.yellowPlayerId == sender || state.yellowPlayerId.isEmpty()
                                if ((state.isRedTurn && isRed) || (!state.isRedTurn && isYellow)) {
                                    val piece = if (state.isRedTurn) 1 else 2
                                    val newBoard = state.board.map { it.toMutableList() }
                                    for (r in 5 downTo 0) {
                                        if (newBoard[r][col] == 0) {
                                            newBoard[r][col] = piece
                                            break
                                        }
                                    }
                                    state = state.copy(board = newBoard, isRedTurn = !state.isRedTurn).checkWinner()
                                }
                            }
                        }
                        "reset" -> {
                            state = com.aetherweb.app.Connect4State(
                                redPlayerId = state.redPlayerId,
                                yellowPlayerId = state.yellowPlayerId
                            )
                        }
                    }
                    MeshNetworkManager._uiState.update { it.copy(connect4State = state) }
                    val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("connect4", state.toJson())
                    MeshNetworkManager.meshRouter.routePacket(packet)
                    fanOutToGuests(packet.toJsonString(), "Host")
                    return true
                }
                "chess" -> {
                    var state = currentState.chessState
                    when (action) {
                        "claim_slot" -> {
                            val role = json.optString("role")
                            val isBot = json.optBoolean("isBot", false)
                            val occupant = if (isBot) "bot" else sender
                            state = if (role == "white") state.copy(whitePlayerId = occupant) else state.copy(blackPlayerId = occupant)
                        }
                        "leave_slot" -> {
                            val role = json.optString("role")
                            state = if (role == "white") state.copy(whitePlayerId = "") else state.copy(blackPlayerId = "")
                        }
                        "reset" -> {
                            state = com.aetherweb.app.ChessEngine.reset().copy(
                                whitePlayerId = state.whitePlayerId,
                                blackPlayerId = state.blackPlayerId
                            )
                        }
                        "move" -> {
                            val from = json.optInt("from", -1)
                            val to = json.optInt("to", -1)
                            if (from in 0..63 && to in 0..63) {
                                val pieces = state.pieces.toMutableList()
                                val moving = pieces[from]
                                pieces[to] = moving
                                pieces[from] = ""
                                state = state.copy(pieces = pieces, isWhiteTurn = !state.isWhiteTurn)
                            }
                        }
                    }
                    MeshNetworkManager._uiState.update { it.copy(chessState = state) }
                    val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("chess", state.toJson().toString())
                    MeshNetworkManager.meshRouter.routePacket(packet)
                    fanOutToGuests(packet.toJsonString(), "Host")
                    return true
                }
                "ludo" -> {
                    var state = currentState.ludoState
                    when (action) {
                        "claim_slot" -> {
                            val slot = json.optInt("slotIndex", -1)
                            val isBot = json.optBoolean("isBot", false)
                            if (slot in 0..3) {
                                val newMap = state.playerIds.toMutableMap()
                                newMap[slot] = if (isBot) "bot" else sender
                                state = state.copy(playerIds = newMap)
                            }
                        }
                        "leave_slot" -> {
                            val slot = json.optInt("slotIndex", -1)
                            if (slot in 0..3) {
                                val newMap = state.playerIds.toMutableMap()
                                newMap.remove(slot)
                                state = state.copy(playerIds = newMap)
                            }
                        }
                        "fill_bots" -> {
                            val newMap = state.playerIds.toMutableMap()
                            for (p in 0..3) {
                                if (!newMap.containsKey(p)) {
                                    newMap[p] = "bot"
                                }
                            }
                            state = state.copy(playerIds = newMap)
                        }
                        "reset" -> {
                            state = com.aetherweb.app.LudoState(playerIds = state.playerIds)
                        }
                        "roll_dice" -> {
                            if (!state.hasRolled && state.winner == -1) {
                                val roll = (1..6).random()
                                state = state.copy(diceValue = roll, hasRolled = true)
                            }
                        }
                        "move_piece" -> {
                            val playerIdx = json.optInt("playerIndex", -1)
                            val pieceIdx = json.optInt("pieceIndex", -1)
                            if (playerIdx in 0..3 && pieceIdx in 0..3 && state.hasRolled && state.turn == playerIdx) {
                                state = com.aetherweb.app.LudoEngine.movePiece(state, playerIdx, pieceIdx)
                            }
                        }
                    }
                    MeshNetworkManager._uiState.update { it.copy(ludoState = state) }
                    val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("ludo", state.toJson().toString())
                    MeshNetworkManager.meshRouter.routePacket(packet)
                    fanOutToGuests(packet.toJsonString(), "Host")
                    return true
                }
            }
            return false
        }

        // Door 4: single handler for guest chat arriving over any web transport
        // (WebSocket via WebServerManager, or BLE GATT via BleMeshManager).
        // Shared by both managers so every guest message gets identical handling.
        val handleGuestChat: (String, String) -> Unit = msgLambda@ { text, sender ->
                try {
                    val rootJson = org.json.JSONObject(text)
                    if (rootJson.optString("type") == "game_action") {
                        if (handleWebGameAction(rootJson, sender)) {
                            return@msgLambda
                        }
                    }
                } catch (e: Exception) { }

                try {
                    val packet = com.aetherweb.app.protocol.MeshPacketCodec.decode(text)
                    if (packet !is com.aetherweb.app.protocol.MeshPacket.Raw) {
                        val handled = com.aetherweb.app.protocol.MeshPacketDispatcher.dispatch(
                            packet = packet,
                            senderId = "web_client",
                            senderName = sender,
                            messageId = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            isFromWeb = true,
                            context = appContext
                        )
                        if (handled) {
                            meshRouter.routeLocalMessage(text)
                            fanOutToGuests(text, sender)
                            return@msgLambda
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }

                DiagnosticLogger.log("Message Lifecycle", "Host Received", "Host phone successfully parsed the JSON payload", EventStatus.SUCCESS)
                val webMsg = ChatMessage(
                    senderName = sender,
                    senderId = "web_client",
                    message = text,
                    isFromMe = false
 )
                _uiState.update { it.copy(
                    messages = it.messages + webMsg
 ) }
                AetherFeedManager.handleIncomingChatMessage(text, sender)
                meshRouter.routeLocalMessage("${sender}: $text")
                fanOutToGuests(text, sender)
        }

        webServerManager = WebServerManager(
            context = appContext,
            onClientConnected = { ip ->
                val state = _uiState.value
                val currentCount = 1 + state.approvedSpectators.size + state.connectedNodes.size
                val isFull = state.roomCapacityLimit > 0 && currentCount >= state.roomCapacityLimit
                if (state.isRoomSealed || isFull || state.kickedIps.contains(ip)) {
                    return@WebServerManager
                }
                if (!state.approvedSpectators.contains(ip)) {
                    if (state.pendingSpectators.none { it.ip == ip }) {
                        // Voice Room Phase 1: request carries a temp profile (name filled in
                        // later via POST /api/profile from the waiting page).
                        _uiState.value = state.copy(pendingSpectators = state.pendingSpectators +
                            SpectatorRequest(ip = ip, name = "Web guest", emoji = "\uD83C\uDF10", pid = ip))
                    }
                }
            },
            onProfileUpdated = { req ->
                _uiState.update { state ->
                    fun enrich(list: List<SpectatorRequest>) =
                        list.map { if (it.ip == req.ip) req.copy(request = it.request) else it }
                    state.copy(
                        pendingSpectators = enrich(state.pendingSpectators),
                        pendingVoiceRequests = enrich(state.pendingVoiceRequests)
                    )
                }
            },
            onVoiceJoinRequested = { req ->
                _uiState.update { state ->
                    val already = state.approvedVoiceGuests.contains(req.ip) ||
                        state.pendingVoiceRequests.any { it.ip == req.ip }
                    if (already) state else state.copy(
                        pendingVoiceRequests = state.pendingVoiceRequests + req.copy(request = "voice")
                    )
                }
            },
            isApproved = { ip -> _uiState.value.approvedSpectators.contains(ip) },
            // Voice Room Phase 3: gate for the /ws-voice SFU.
            isVoiceApproved = { ip -> _uiState.value.approvedVoiceGuests.contains(ip) },
            isRoomFullOrSealed = { ip ->
                val s = _uiState.value
                val currentCount = 1 + s.approvedSpectators.size + s.connectedNodes.size
                !s.approvedSpectators.contains(ip) && (s.isRoomSealed || (s.roomCapacityLimit > 0 && currentCount >= s.roomCapacityLimit))
            },
            isKicked = { ip -> _uiState.value.kickedIps.contains(ip) },
            getChatHistory = {
                _uiState.value.messages.takeLast(50).map { Pair(it.senderName, it.message) }
            },
            onMessageReceived = { text, sender -> handleGuestChat(text, "$sender \uD83C\uDF10") },
        )

        hotspotManager = HotspotManager(appContext)
        bleMeshManager = BleMeshManager(appContext, onWebBleMessage = { text, sender -> handleGuestChat(text, "$sender \uD83D\uDD35") })
        bleMeshManager?.initAutoThrottle() // Phase 1: auto-throttle BLE when screen off / battery low
        wifiSocketManager = WifiSocketManager(appContext, localNodeId).apply {
            onClientDisconnected = { disconnectedIp ->
                handleWifiPeerDisconnected(disconnectedIp)
            }
        }
        
        initializeKeys()
        CallManager.init(appContext)
        if (highSpeedFileTransferManager == null) {
            highSpeedFileTransferManager = HighSpeedFileTransferManager(appContext).apply {
                startServer()
            }
        }
        
        wifiSocketManager?.start()
        
        InternetRelayManager.init(appContext, localNodeId, meshRouter.localPublicKey)
        
        _uiState.value = _uiState.value.copy(
            messages = listOf(
                ChatMessage(
                    senderName = "System",
                    senderId = "system",
                    message = "Network Ready.",
                    isFromMe = false
 )
 )
 )
    }
}

class MeshForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun pulseWakeLock(durationMs: Long = 10_000L) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock?.acquire(durationMs)
        } catch (e: Exception) {}
    }

    private fun updateWifiLockState(shouldHold: Boolean) {
        try {
            if (shouldHold) {
                if (wifiLock?.isHeld != true) {
                    wifiLock?.acquire()
                    Log.d("MeshForegroundService", "Acquired WifiLock for active high-speed networking")
                }
            } else {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                    Log.d("MeshForegroundService", "Released WifiLock to conserve battery")
                }
            }
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "MeshChatServiceChannel")
            .setContentTitle("MeshChat Network Alive")
            .setContentText("Routing messages in the background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        
        scope.launch {
            try {
                MeshNetworkManager.initialize(applicationContext)

                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshChat::WakeLock")
                wakeLock?.acquire(15_000L) // 15-second initialization wake lock, then release to deep sleep

                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "MeshChat::WifiLock")

                startObserving()
                // bitchat parity: BLE presence announcements (30 s cadence) + peer table
                com.aetherweb.app.PresenceManager.startAnnouncer(
                    scope = scope,
                    router = MeshNetworkManager.meshRouter,
                    nameProvider = {
                        val n = MeshNetworkManager._uiState.value.localUserName
                        if (n.isNotBlank()) n else android.os.Build.MODEL
                    }
                )
            } catch (e: Exception) {
                Log.e("MeshForegroundService", "Initialization error", e)
            }
        }
    }

    private fun startObserving() {
        val manager = MeshNetworkManager
        
        scope.launch {
            var lastIp = ""
            while (true) {
                val ip = NetworkUtils.getLocalIpAddress()
                if (ip != lastIp) {
                    if (ip != "127.0.0.1" && ip.isNotEmpty()) {
                        DiagnosticLogger.log("Network", "IP Assignment", "OS granted valid local IP: $ip", EventStatus.SUCCESS)
                    } else if (ip == "127.0.0.1") {
                        DiagnosticLogger.log("Network", "IP Assignment", "No external IP, using loopback (127.0.0.1)", EventStatus.INFO)
                    }
                    lastIp = ip
                }
                com.aetherweb.app.MeshNetworkManager._uiState.value = com.aetherweb.app.MeshNetworkManager._uiState.value.copy(hotspotIp = ip)
                kotlinx.coroutines.delay(3000)
            }
        }
        
        scope.launch {
            InternetRelayManager.inboundRelayMessages.collect { envelope ->
                try {
                    val payload = envelope.optString("payload")
                    val fromMailbox = envelope.optString("fromMailbox")
                    val fromNodeId = envelope.optString("fromNodeId", fromMailbox)
                    val fromPublicKey = envelope.optString("fromPublicKey", "")

                    if (payload.startsWith("{")) {
                        val obj = org.json.JSONObject(payload)
                        val type = obj.optString("type")
                        if (type == "delivery_ack") {
                            val ackMsgId = obj.optString("originalMsgId")
                            com.aetherweb.app.MeshNetworkManager._uiState.update { state ->
                                state.copy(
                                    messages = state.messages.map { msg ->
                                        if (msg.id == ackMsgId) msg.copy(deliveryStatus = DeliveryStatus.DELIVERED) else msg
                                    }
                                )
                            }
                            return@collect
                        }
                    }

                    val networkMessage = com.aetherweb.app.MeshRouter.NetworkMessage(
                        messageId = envelope.optString("msgId", java.util.UUID.randomUUID().toString()),
                        senderId = fromNodeId,
                        senderName = envelope.optString("fromName", "Remote Node"),
                        ttl = 1,
                        payload = payload,
                        timestamp = envelope.optLong("timestamp", System.currentTimeMillis()),
                        signature = "",
                        publicKey = fromPublicKey
                    )
                    manager.meshRouter.processIncomingPacket(networkMessage)
                } catch (e: Exception) {
                    Log.e("MeshForegroundService", "Error handling inbound relay envelope", e)
                }
            }
        }
        
        scope.launch {
            manager.meshRouter.incomingMessages.collect { networkMessage ->
                DiagnosticLogger.logMeshToWebHop(networkMessage.messageId, networkMessage.senderId, networkMessage.payload)
                // bitchat parity: presence announces update the peer table — never shown as chat
                if (com.aetherweb.app.PresenceManager.handleIncomingAnnounce(networkMessage.payload, networkMessage.senderId)) {
                    return@collect
                }
                var handledSpecial = false
                try {
                    val packet = com.aetherweb.app.protocol.MeshPacketCodec.decode(networkMessage.payload)
                    handledSpecial = com.aetherweb.app.protocol.MeshPacketDispatcher.dispatch(
                        packet = packet,
                        senderId = networkMessage.senderId,
                        senderName = networkMessage.senderName,
                        messageId = networkMessage.messageId,
                        timestamp = networkMessage.timestamp,
                        isFromWeb = false,
                        context = applicationContext
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                MeshNetworkManager.fanOutToGuests(
                    networkMessage.payload,
                    if (networkMessage.senderName.isNotBlank()) networkMessage.senderName else "Peer_${networkMessage.senderId.take(4)}"
                )

                if (!handledSpecial) {
                    val newChatMessage = ChatMessage(
                        id = networkMessage.messageId,
                        senderName = if (networkMessage.senderName.isNotBlank()) networkMessage.senderName else "Peer_${networkMessage.senderId.take(4)}",
                        senderId = networkMessage.senderId,
                        message = networkMessage.payload,
                        isFromMe = false,
                        timestamp = networkMessage.timestamp
                    )
                    com.aetherweb.app.MeshNetworkManager._uiState.update { it.copy(
                        messages = it.messages + newChatMessage
                    ) }
                    AetherFeedManager.handleIncomingChatMessage(newChatMessage.message, newChatMessage.senderName)
                    val isGhostMode = com.aetherweb.app.MeshNetworkManager._uiState.value.isGhostMode
                    if (!isGhostMode) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                com.aetherweb.app.data.MeshChatDatabase.getDatabase(applicationContext).chatDao().insertMessage(
                                    com.aetherweb.app.data.ChatMessageEntity(
                                        senderId = newChatMessage.senderId,
                                        senderName = newChatMessage.senderName,
                                        message = newChatMessage.message,
                                        timestamp = newChatMessage.timestamp,
                                        isFromMe = false
                                    )
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        if (!MainActivity.isAppInForeground) {
                            NotificationHelper.showMessageNotification(
                                context = applicationContext,
                                senderName = newChatMessage.senderName,
                                message = newChatMessage.message,
                                senderId = newChatMessage.senderId,
                                isGroup = true,
                                groupTitle = "Mesh & WebChat",
                                isFromWeb = false
                            )
                        }
                    }
                }
                DiagnosticLogger.logMeshToWebHop(networkMessage.messageId, networkMessage.senderId, networkMessage.payload)
            }
        }

        scope.launch {
            manager.meshRouter.outboundBroadcasts.collect { networkMessage ->
                try {
                    pulseWakeLock(8_000L) // Keep CPU awake during packet dispatch
                    val hasWifi = manager.wifiSocketManager?.hasActiveConnections() == true || MeshNetworkManager._uiState.value.isHotspotActive
                    updateWifiLockState(hasWifi)

                    val jsonObj = org.json.JSONObject().apply {
                        put("messageId", networkMessage.messageId)
                        put("senderId", networkMessage.senderId)
                        put("senderName", networkMessage.senderName)
                        put("ttl", networkMessage.ttl)
                        put("payload", networkMessage.payload)
                        put("timestamp", networkMessage.timestamp)
                        put("nonce", networkMessage.nonce)
                        put("signature", networkMessage.signature)
                        put("publicKey", networkMessage.publicKey)
                    }
                    val json = jsonObj.toString()
                    val safePayloadPreview = if (networkMessage.payload.length > 80) networkMessage.payload.take(80) + "..." else networkMessage.payload
                    
                    val originSource = if (networkMessage.senderId == manager.localNodeId) "Local App -> Mesh" else "Web/Other -> Mesh"

                    if (manager.wifiSocketManager?.hasActiveConnections() == true) {
                        DiagnosticLogger.logHop(originSource, networkMessage.messageId, "Interface: Wi-Fi TCP, Payload: $safePayloadPreview")
                        DiagnosticLogger.log("Message Lifecycle", "Wi-Fi Broadcast", "Attempting to send to all connected TCP sockets", EventStatus.PENDING)
                        manager.wifiSocketManager?.broadcastPacket(json)
                        DiagnosticLogger.log("Message Lifecycle", "Wi-Fi Broadcast", "Successfully broadcast to TCP sockets", EventStatus.SUCCESS)
                    } else {
                        DiagnosticLogger.log("Message Lifecycle", "Wi-Fi Broadcast", "No active TCP connections to broadcast to", EventStatus.INFO)
                    }

                    // SMART ROUTING LOGIC
                    val isHighBandwidth = networkMessage.payload.contains("\"type\":\"call_offer\"") || 
                                          networkMessage.payload.contains("\"type\":\"call_answer\"") || 
                                          networkMessage.payload.contains("\"type\":\"call_audio\"") ||
                                          networkMessage.payload.contains("\"type\":\"shared_media\"") ||
                                          networkMessage.payload.length > 1024

                    if (isHighBandwidth && manager.wifiSocketManager?.hasActiveConnections() == true) {
                        DiagnosticLogger.log("Message Lifecycle", "Smart Routing", "High-bandwidth packet detected. Bypassing BLE to prevent congestion. Using Wi-Fi exclusively.", EventStatus.INFO)
                    } else {
                        scope.launch {
                            DiagnosticLogger.logHop(originSource, networkMessage.messageId, "Interface: BLE Mesh, Payload: $safePayloadPreview")
                            DiagnosticLogger.log("Message Lifecycle", "BLE Broadcast", "Attempting to send to nearby Bluetooth nodes", EventStatus.PENDING)
                            manager.bleMeshManager?.broadcastPacket(json)
                            DiagnosticLogger.log("Message Lifecycle", "BLE Broadcast", "Successfully broadcast via BLE GATT", EventStatus.SUCCESS)
                            kotlinx.coroutines.delay(2500)
                        }
                    }
                } catch(e: Exception) {
                    Log.e("MeshForeground", "Error broadcasting outbound packet", e)
                    DiagnosticLogger.log("Message Lifecycle", "Broadcast Error", "Error broadcasting outbound packet: ${e.message}", EventStatus.ERROR)
                }
            }
        }
        scope.launch {
            var currentBleNodes = emptyList<MeshNode>()
            var currentWifiNodes = emptyMap<String, String>()

            fun updateCombinedNodes() {
                val wifiNodes = currentWifiNodes.map { (ip, name) -> 
                    MeshNode(id = ip, name = name, signalStrength = 100) 
                }
                val combined = (currentBleNodes + wifiNodes).distinctBy { it.id }
                val isWifi = currentWifiNodes.isNotEmpty()
                com.aetherweb.app.MeshNetworkManager._uiState.value = com.aetherweb.app.MeshNetworkManager._uiState.value.copy(
                    connectedNodes = combined,
                    isWifiConnected = isWifi
                )
                // Battery Optimization: If Wi-Fi is connected, adapt BLE duty cycle to ECO_SAVER (10% duty cycle)
                if (isWifi) {
                    manager.bleMeshManager?.setDutyCycleMode(com.aetherweb.app.BleMeshManager.BleDutyCycle.ECO_SAVER)
                } else if (!com.aetherweb.app.MeshNetworkManager._uiState.value.isEmergencyMode) {
                    manager.bleMeshManager?.setDutyCycleMode(com.aetherweb.app.BleMeshManager.BleDutyCycle.BALANCED)
                }
            }

            // Storage Footprint: Enforce 100MB threshold with periodic automatic background pruner sweeps
            scope.launch {
                while (isActive) {
                    try {
                        val result = com.aetherweb.app.MeshStorageManager.pruneCacheSweep(applicationContext)
                        val summary = "Cache: ${com.aetherweb.app.MeshStorageManager.formatFileSize(result.currentCacheSizeBytes)} / 100 MB (${result.currentFileCount} files)"
                        com.aetherweb.app.MeshNetworkManager._uiState.update { 
                            it.copy(
                                cacheSizeBytes = result.currentCacheSizeBytes,
                                cacheFileCount = result.currentFileCount,
                                lastStorageSweepSummary = summary
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("MeshForegroundService", "Cache sweep failed", e)
                    }
                    kotlinx.coroutines.delay(15 * 60 * 1000L) // Automatic sweep every 15 minutes
                }
            }

            scope.launch {
                manager.bleMeshManager?.activeNodes?.collect { nodes ->
                    currentBleNodes = nodes
                    updateCombinedNodes()
                }
            }
            
            scope.launch {
                manager.wifiSocketManager?.activeWifiNodes?.collect { nodesMap ->
                    currentWifiNodes = nodesMap
                    updateCombinedNodes()
                }
            }
        }


        scope.launch {
            manager.hotspotManager?.hotspotState?.collect { hotspotInfo ->
                com.aetherweb.app.MeshNetworkManager._uiState.value = com.aetherweb.app.MeshNetworkManager._uiState.value.copy(
                    isHotspotActive = hotspotInfo.isActive,
                    hotspotSsid = hotspotInfo.ssid,
                    hotspotPassword = hotspotInfo.password,
                    hotspotError = hotspotInfo.error
 )
                if (hotspotInfo.isActive) {
                    scope.launch {
                        var newIp = NetworkUtils.getLocalIpAddress()
                        var retries = 0
                        while (newIp == "127.0.0.1" && retries < 15) {
                            kotlinx.coroutines.delay(1000)
                            newIp = NetworkUtils.getLocalIpAddress()
                            retries++
                        }
                        com.aetherweb.app.MeshNetworkManager._uiState.value = com.aetherweb.app.MeshNetworkManager._uiState.value.copy(hotspotIp = newIp)
                        com.aetherweb.app.LocalDnsServer.start(newIp)
                        if (newIp != "127.0.0.1") {
                            // Phase 3: Initialize local cluster topology
                            WifiClusterBridgeManager.initializeLocalCluster(manager.localNodeId, newIp)

                            val currentState = com.aetherweb.app.MeshNetworkManager.uiState.value
                            val pwd = if (currentState.isPrivateRoom || !currentState.autoShareHotspotOverBle) "PRIVATE" else hotspotInfo.password
                            val hs = JSONObject()
                            hs.put("type", "sys_handshake")
                            hs.put("ssid", hotspotInfo.ssid)
                            hs.put("pwd", pwd)
                            hs.put("ip", newIp)
                            hs.put("isPrivate", currentState.isPrivateRoom)
                            // Broadcast unencrypted so discovering devices without room keys can read it
                            com.aetherweb.app.MeshNetworkManager.meshRouter.routeLocalMessage(hs.toString(), encrypt = false)
                        }
                        
                        com.aetherweb.app.MeshNetworkManager.webServerManager?.stopServer()
                        kotlinx.coroutines.delay(1000)
                        com.aetherweb.app.MeshNetworkManager.webServerManager?.startServer()
                        com.aetherweb.app.MeshNetworkManager._uiState.update { it.copy(
                            messages = it.messages + ChatMessage(
                                senderName = "System",
                                senderId = "system",
                                message = "Local Hook Active. Web Server is running on " + newIp + ". Tell others to scan QR code to connect.",
                                isFromMe = false
                            ),
                            isWebServerRunning = true
                        ) }
                    }
                } else {
                    // Ensure it is running even without hotspot
                    com.aetherweb.app.MeshNetworkManager.webServerManager?.startServer(8080)
                    com.aetherweb.app.MeshNetworkManager._uiState.update { it.copy(isWebServerRunning = true) }
                }
            }
        }

        scope.launch {
            manager.bleMeshManager?.incomingPackets?.collect { json ->
                processIncomingJson(json)
            }
        }
        scope.launch {
            manager.wifiSocketManager?.incomingPackets?.collect { json ->
                processIncomingJson(json)
            }
        }

        // Phase 3: Periodic Cluster Topology Synchronization (every 20s)
        scope.launch {
            while (isActive) {
                try {
                    val currentState = MeshNetworkManager.uiState.value
                    val currentIp = currentState.hotspotIp.ifEmpty { NetworkUtils.getLocalIpAddress() }
                    if (currentIp != "127.0.0.1") {
                        val activePeers = manager.wifiSocketManager?.activeConnections?.size ?: 0
                        val beaconJson = WifiClusterBridgeManager.createClusterBeacon(
                            localNodeId = manager.localNodeId,
                            currentIp = currentIp,
                            isHost = currentState.isHotspotActive,
                            peerCount = activePeers
                        )
                        // Broadcast over UDP & Wi-Fi sockets
                        manager.wifiSocketManager?.broadcastPacket(beaconJson)
                    }
                } catch (e: Exception) {}
                kotlinx.coroutines.delay(20000L)
            }
        }

        // Periodic Hotspot BLE Credentials Beacon (every 15s) while host is running with autoShareHotspotOverBle enabled
        scope.launch {
            while (isActive) {
                try {
                    val currentState = MeshNetworkManager.uiState.value
                    if (currentState.isHotspotActive && 
                        currentState.autoShareHotspotOverBle && 
                        currentState.hotspotSsid.isNotEmpty()) {
                        
                        val ip = currentState.hotspotIp.ifEmpty { NetworkUtils.getLocalIpAddress() }
                        val pwd = if (currentState.isPrivateRoom) "PRIVATE" else currentState.hotspotPassword
                        
                        val hs = JSONObject().apply {
                            put("type", "sys_handshake")
                            put("ssid", currentState.hotspotSsid)
                            put("pwd", pwd)
                            put("ip", ip)
                            put("isPrivate", currentState.isPrivateRoom)
                        }
                        // Broadcast unencrypted so discovering devices without room keys can read it
                        MeshNetworkManager.meshRouter.routeLocalMessage(hs.toString(), encrypt = false)
                    }
                } catch (e: Exception) {}
                kotlinx.coroutines.delay(15000L)
            }
        }
    }

    private var lastRawBleSsid: String? = null
    private var lastRawBlePwd: String? = null
    private var lastRawBleIp: String? = null

    private fun handleRawBleHandshakeChunk(chunk: String) {
        if (chunk.startsWith("S:")) {
            val raw = chunk.substring(2)
            if (raw.startsWith("PRIVATE|")) {
                lastRawBleSsid = raw.substring(8)
                lastRawBlePwd = "PRIVATE"
            } else {
                lastRawBleSsid = raw
            }
        } else if (chunk.startsWith("P:")) {
            lastRawBlePwd = chunk.substring(2)
        } else if (chunk.startsWith("I:")) {
            lastRawBleIp = chunk.substring(2)
            val ssid = lastRawBleSsid
            val pwd = lastRawBlePwd
            val ip = lastRawBleIp ?: "192.168.49.1"
            if (ssid != null && pwd != null) {
                val hs = JSONObject().apply {
                    put("type", "handshake")
                    put("ssid", ssid)
                    put("pwd", pwd)
                    put("ip", ip)
                }
                processIncomingJson(hs.toString())
            }
        }
    }

    private fun processIncomingJson(json: String) {
        pulseWakeLock(8_000L) // Ensure CPU completes processing and routing before sleeping
        if (json.startsWith("S:") || json.startsWith("P:") || json.startsWith("I:")) {
            handleRawBleHandshakeChunk(json)
            return
        }
        if (json.startsWith("SYS_WIFI_PEER_JOINED:")) {
            val ip = json.removePrefix("SYS_WIFI_PEER_JOINED:")
            // We are the host, let's sync state to this new peer
            val currentState = MeshNetworkManager._uiState.value
            if (currentState.isHotspotActive) {
                // Send chat history (last 50 messages)
                val recentMessages = currentState.messages.takeLast(50)
                val syncObj = JSONObject().apply {
                    put("type", "sys_state_sync")
                    val msgArray = org.json.JSONArray()
                    recentMessages.forEach { msg ->
                        val mObj = JSONObject().apply {
                            put("id", msg.id)
                            put("senderId", msg.senderId)
                            put("senderName", msg.senderName)
                            put("message", msg.message)
                            put("timestamp", msg.timestamp)
                        }
                        msgArray.put(mObj)
                    }
                    put("messages", msgArray)
                    
                    // Add known users
                    val usersObj = JSONObject()
                    currentState.knownUsers.forEach { (k, v) -> usersObj.put(k, v) }
                    put("users", usersObj)
                }
                
                MeshNetworkManager.meshRouter.routeLocalMessage(syncObj.toString())
                MeshNetworkManager.meshRouter.routeLocalMessage("{\"type\":\"sys_sync_request\"}")

                // Step 3: Wi-Fi Multiplayer Active Game State Sync
                // Sync any actively running game so newly connected Wi-Fi peers join with fresh state
                if (currentState.sharedMediaType != "none") {
                    when (currentState.sharedMediaType) {
                        "tictactoe" -> {
                            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("tictactoe", currentState.ticTacToeState.toJson())
                            MeshNetworkManager.meshRouter.routePacket(packet)
                        }
                        "connect4" -> {
                            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("connect4", currentState.connect4State.toJson())
                            MeshNetworkManager.meshRouter.routePacket(packet)
                        }
                        "chess" -> {
                            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("chess", currentState.chessState.toJson().toString())
                            MeshNetworkManager.meshRouter.routePacket(packet)
                        }
                        "ludo" -> {
                            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("ludo", currentState.ludoState.toJson().toString())
                            MeshNetworkManager.meshRouter.routePacket(packet)
                        }
                    }
                }
            }
            return
        } else if (json == "SYS_ROOM_CLOSED") {
            MeshNetworkManager._uiState.update { 
                it.copy(
                    isEmergencyMode = false,
                    messages = it.messages + ChatMessage(id = java.util.UUID.randomUUID().toString(), senderId = "System", senderName = "System", message = "The Host has closed the room. Disconnected.", timestamp = System.currentTimeMillis(), isFromMe = false)
                )
            }
            return
        } else if (json.startsWith("SYS_TRIGGER_SECONDARY_CLUSTER:")) {
            // Phase 3: Autonomous Cluster-of-Clusters Topology
            // If primary cluster saturated and this node is not hosting, trigger secondary cluster
            val currentState = MeshNetworkManager._uiState.value
            if (!currentState.isHotspotActive && !currentState.isWifiConnected) {
                Log.i("MeshService", "Triggering secondary cluster expansion (Cluster Beta)")
                DiagnosticLogger.log("Cluster Expansion", "Autonomous Hotspot", "Starting secondary hotspot to bridge saturated cluster", EventStatus.SUCCESS)
                MeshNetworkManager.hotspotManager?.startHotspot()
            }
            return
        }

        try {
            val obj = JSONObject(json)
            if (obj.has("type") && obj.getString("type") == "cluster_forward") {
                val targetNodeId = obj.getString("targetNodeId")
                val payload = obj.getString("payload")
                // If we are a bridge node and have a fast route to targetNodeId, forward immediately over Wi-Fi
                if (WifiClusterBridgeManager.hasFastWifiRoute(targetNodeId)) {
                    val fastIp = WifiClusterBridgeManager.getFastRouteIp(targetNodeId)
                    if (fastIp != null) {
                        MeshNetworkManager.wifiSocketManager?.broadcastPacket(payload)
                        Log.i("MeshService", "Bridge relay forwarded packet to target node $targetNodeId at $fastIp")
                    }
                }
                return
            }
            if (obj.has("type") && obj.getString("type") == "handshake") {
                val ssid = obj.getString("ssid")
                val pwd = obj.getString("pwd")
                val ip = obj.getString("ip")
                val currentState = MeshNetworkManager._uiState.value
                MeshNetworkManager._uiState.value = currentState.copy(
                    discoveredHostSsid = ssid,
                    discoveredHostPwd = pwd,
                    discoveredHostIp = ip
                )
                
                // BitChat-style BLE pulse beacon: Auto-join rooms without scanning QR if auto-join is enabled & room is public
                if (currentState.autoJoinBeaconEnabled && 
                    !currentState.isHotspotActive && 
                    !currentState.isWifiConnected && 
                    pwd != "PRIVATE") {
                    Log.i("MeshService", "BitChat pulse beacon received for $ssid - auto-joining...")
                    DiagnosticLogger.log("BLE Pulse Beacon", "Auto-Join", "Auto-joining mesh room $ssid without QR scan", EventStatus.SUCCESS)
                    MeshNetworkManager.hotspotManager?.connectToHotspot(ssid, pwd, ip)
                }
                return
            }
            val msg = MeshRouter.NetworkMessage(
                messageId = obj.getString("messageId"),
                senderId = obj.getString("senderId"),
                senderName = obj.optString("senderName", ""),
                ttl = obj.getInt("ttl"),
                payload = obj.getString("payload"),
                timestamp = obj.getLong("timestamp"),
                nonce = obj.optLong("nonce", 0L),
                signature = obj.optString("signature", ""),
                publicKey = obj.optString("publicKey", "")
            )
            MeshNetworkManager.meshRouter.processIncomingPacket(msg)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleWifiPeerDisconnected(disconnectedIp: String) {
        val currentState = MeshNetworkManager._uiState.value
        if (!currentState.isHotspotActive) return

        // 1. Tic-Tac-Toe disconnect fallback to bot
        var tttChanged = false
        var ttt = currentState.ticTacToeState
        if (ttt.xPlayerId.contains(disconnectedIp) || (ttt.xPlayerId.isNotEmpty() && ttt.xPlayerId != MeshNetworkManager.localNodeId && ttt.xPlayerId != "bot" && !currentState.connectedNodes.any { it.id == ttt.xPlayerId })) {
            ttt = ttt.copy(xPlayerId = "bot")
            tttChanged = true
        }
        if (ttt.oPlayerId.contains(disconnectedIp) || (ttt.oPlayerId.isNotEmpty() && ttt.oPlayerId != MeshNetworkManager.localNodeId && ttt.oPlayerId != "bot" && !currentState.connectedNodes.any { it.id == ttt.oPlayerId })) {
            ttt = ttt.copy(oPlayerId = "bot")
            tttChanged = true
        }
        if (tttChanged) {
            MeshNetworkManager._uiState.update { it.copy(ticTacToeState = ttt) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("tictactoe", ttt.toJson())
            MeshNetworkManager.meshRouter.routePacket(packet)
            MeshNetworkManager.fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 2. Connect 4 disconnect fallback to bot
        var c4Changed = false
        var c4 = currentState.connect4State
        if (c4.redPlayerId.contains(disconnectedIp) || (c4.redPlayerId.isNotEmpty() && c4.redPlayerId != MeshNetworkManager.localNodeId && c4.redPlayerId != "bot" && !currentState.connectedNodes.any { it.id == c4.redPlayerId })) {
            c4 = c4.copy(redPlayerId = "bot")
            c4Changed = true
        }
        if (c4.yellowPlayerId.contains(disconnectedIp) || (c4.yellowPlayerId.isNotEmpty() && c4.yellowPlayerId != MeshNetworkManager.localNodeId && c4.yellowPlayerId != "bot" && !currentState.connectedNodes.any { it.id == c4.yellowPlayerId })) {
            c4 = c4.copy(yellowPlayerId = "bot")
            c4Changed = true
        }
        if (c4Changed) {
            MeshNetworkManager._uiState.update { it.copy(connect4State = c4) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("connect4", c4.toJson())
            MeshNetworkManager.meshRouter.routePacket(packet)
            MeshNetworkManager.fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 3. Chess disconnect fallback to bot
        var chessChanged = false
        var chess = currentState.chessState
        if (chess.whitePlayerId.contains(disconnectedIp) || (chess.whitePlayerId.isNotEmpty() && chess.whitePlayerId != MeshNetworkManager.localNodeId && chess.whitePlayerId != "bot" && !currentState.connectedNodes.any { it.id == chess.whitePlayerId })) {
            chess = chess.copy(whitePlayerId = "bot")
            chessChanged = true
        }
        if (chess.blackPlayerId.contains(disconnectedIp) || (chess.blackPlayerId.isNotEmpty() && chess.blackPlayerId != MeshNetworkManager.localNodeId && chess.blackPlayerId != "bot" && !currentState.connectedNodes.any { it.id == chess.blackPlayerId })) {
            chess = chess.copy(blackPlayerId = "bot")
            chessChanged = true
        }
        if (chessChanged) {
            MeshNetworkManager._uiState.update { it.copy(chessState = chess) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("chess", chess.toJson().toString())
            MeshNetworkManager.meshRouter.routePacket(packet)
            MeshNetworkManager.fanOutToGuests(packet.toJsonString(), "Host")
        }

        // 4. Ludo disconnect fallback to bot
        var ludoChanged = false
        var ludo = currentState.ludoState
        val updatedIds = ludo.playerIds.toMutableMap()
        for (i in 0..3) {
            val pid = updatedIds[i] ?: ""
            if (pid.contains(disconnectedIp) || (pid.isNotEmpty() && pid != MeshNetworkManager.localNodeId && pid != "bot" && !currentState.connectedNodes.any { it.id == pid })) {
                updatedIds[i] = "bot"
                ludoChanged = true
            }
        }
        if (ludoChanged) {
            ludo = ludo.copy(playerIds = updatedIds)
            MeshNetworkManager._uiState.update { it.copy(ludoState = ludo) }
            val packet = com.aetherweb.app.protocol.MeshPacket.MiniGameSync("ludo", ludo.toJson().toString())
            MeshNetworkManager.meshRouter.routePacket(packet)
            MeshNetworkManager.fanOutToGuests(packet.toJsonString(), "Host")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "MeshChatServiceChannel",
                "MeshChat Background Service",
                NotificationManager.IMPORTANCE_LOW
 )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "PULSE_BLE_CHUNK") {
            val chunk = intent.getStringExtra("chunk")
            if (chunk != null) {
                MeshNetworkManager.bleMeshManager?.pulseBleChunk(chunk)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
        wakeLock = null
        wifiLock = null
        // bitchat parity: best-effort LEAVE announce so peer tables prune promptly
        try {
            val n = MeshNetworkManager._uiState.value.localUserName
            com.aetherweb.app.PresenceManager.sendLeave(
                MeshNetworkManager.meshRouter,
                n.ifBlank { android.os.Build.MODEL }
            )
        } catch (e: Exception) {}
        com.aetherweb.app.PresenceManager.stopAnnouncer()
        scope.cancel()
        MeshNetworkManager.hotspotManager?.stopHotspot()
        com.aetherweb.app.LocalDnsServer.stop()
        MeshNetworkManager.webServerManager?.stopServer()
        MeshNetworkManager.bleMeshManager?.stopScanning()
        MeshNetworkManager.bleMeshManager?.stopAdvertising()
        MeshNetworkManager.bleMeshManager?.releaseAutoThrottle()
        MeshNetworkManager.wifiSocketManager?.stop()
        super.onDestroy()
    }
}
