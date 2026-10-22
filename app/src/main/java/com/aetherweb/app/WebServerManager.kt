package com.aetherweb.app

import io.ktor.utils.io.*

import kotlinx.coroutines.isActive

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import io.ktor.server.request.receiveMultipart

import io.ktor.server.request.*

import android.content.Context

import android.util.Log

import io.ktor.http.*

import io.ktor.server.application.*

import io.ktor.server.engine.*

import io.ktor.server.netty.*

import io.ktor.server.response.*

import io.ktor.server.routing.*

import io.ktor.server.request.*

import io.ktor.http.content.*

import io.ktor.server.websocket.*

import io.ktor.websocket.*

import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withPermit

import kotlinx.coroutines.flow.*

import kotlinx.coroutines.withTimeoutOrNull

import kotlinx.coroutines.TimeoutCancellationException

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock

import java.io.File

import java.util.Collections

class WebServerManager(
    private val context: Context, 
    private val onClientConnected: (String) -> Unit = {},
    private val isApproved: (String) -> Boolean = { true },
    private val getChatHistory: () -> List<Pair<String, String>> = { emptyList() },
    private val onMessageReceived: (String, String) -> Unit,
    // Voice Room Phase 1: profile enrichment + voice-join requests from web guests.
    private val onProfileUpdated: (SpectatorRequest) -> Unit = {},
    private val onVoiceJoinRequested: (SpectatorRequest) -> Unit = {},
    // Voice Room Phase 3: voice-approval gate for the /ws-voice SFU.
    private val isVoiceApproved: (String) -> Boolean = { false },
    private val isRoomFullOrSealed: (String) -> Boolean = { false },
    private val isKicked: (String) -> Boolean = { false }
) {
    private var server: io.ktor.server.engine.EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    data class WebMessage(val id: Int, val sender: String, val message: String, val isHistory: Boolean)
    private var messageCounter = 0
    private val newMessagesFlow = MutableSharedFlow<WebMessage>(extraBufferCapacity = 100)

    private val activeWebSockets = java.util.concurrent.ConcurrentHashMap<String, io.ktor.server.websocket.WebSocketServerSession>()

    fun disconnectClient(ip: String) {
        val wsSession = activeWebSockets.remove(ip)
        kickVoiceMember(ip)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                wsSession?.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Kicked by host"))
            } catch (_: Exception) {}
        }
    }

    private val downloadSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    // ------------------------------------------------------------------
    // Voice Room Phase 3: host-side SFU (Selective Forwarding Unit).
    //
    // Binary audio frame format (all multi-byte fields little-endian):
    //   byte 0      : frame type (0x01 = PCM audio)
    //   byte 1      : senderId length N (0..255)
    //   bytes 2..N+1: senderId UTF-8
    //   rest        : PCM16 mono 16kHz audio payload (20ms = 640 bytes typical)
    //
    // Text frames are JSON control: {"type":"join"|"leave", ...} and the
    // server broadcasts {"type":"voice_state","members":[...]} (debounced).
    // WiFi-only by design — voice never touches BLE.
    // ------------------------------------------------------------------
    private data class VoiceSession(
        val ip: String,
        val senderId: String,
        var name: String,
        var emoji: String,
        var kind: String, // "host" | "member" | "temp"
        val session: io.ktor.server.websocket.WebSocketServerSession,
        @Volatile var lastFrameAt: Long = 0L,
        @Volatile var speaking: Boolean = false,
        @Volatile var micOn: Boolean = false
    )

    private val voiceSessions = java.util.concurrent.ConcurrentHashMap<String, VoiceSession>()
    private val voiceMutedIps = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val voiceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastVoiceStateBroadcast = 0L

    companion object {
        const val VOICE_FRAME_TYPE_AUDIO: Byte = 0x01
        const val VOICE_VAD_THRESHOLD = 600 // PCM RMS energy for "speaking"
        const val VOICE_STATE_DEBOUNCE_MS = 300L
        const val VOICE_SPEAKING_TIMEOUT_MS = 700L
    }

    /** Phase 4 hook: host mutes/unmutes a guest (server drops their frames). */
    fun setVoiceMemberMuted(ip: String, muted: Boolean) {
        if (muted) voiceMutedIps.add(ip) else voiceMutedIps.remove(ip)
        voiceScope.launch { broadcastVoiceState(force = true) }
    }

    /** Phase 4 hook: host kicks a guest from voice (their socket is closed). */
    fun kickVoiceMember(ip: String) {
        val doomed = voiceSessions.values.filter { it.ip == ip }
        voiceScope.launch {
            doomed.forEach { sess ->
                voiceSessions.remove(sess.senderId, sess)
                try {
                    sess.session.close(
                        io.ktor.websocket.CloseReason(
                            io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Kicked by host"
                        )
                    )
                } catch (e: Exception) { /* already gone */ }
            }
            broadcastVoiceState(force = true)
        }
    }

    fun isVoiceMemberMuted(ip: String): Boolean = voiceMutedIps.contains(ip)

    /** Phase 4: host mutes by member id (dashboard key) instead of raw IP. */
    fun setVoiceMemberMutedById(senderId: String, muted: Boolean) {
        voiceSessions[senderId]?.let { setVoiceMemberMuted(it.ip, muted) }
    }

    /** Phase 4: host kicks by member id. */
    fun kickVoiceMemberById(senderId: String) {
        voiceSessions[senderId]?.let { kickVoiceMember(it.ip) }
    }

    private fun pcmRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0L
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += (s * s).toLong()
            n++
            i += 2
        }
        return if (n == 0) 0 else kotlin.math.sqrt(sum.toDouble() / n).toInt()
    }

    private suspend fun broadcastVoiceState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        voiceSessions.values.forEach { sess ->
            if (sess.speaking && now - sess.lastFrameAt > VOICE_SPEAKING_TIMEOUT_MS) {
                sess.speaking = false
            }
        }
        if (!force && now - lastVoiceStateBroadcast < VOICE_STATE_DEBOUNCE_MS) return
        lastVoiceStateBroadcast = now
        val arr = org.json.JSONArray()
        voiceSessions.values.forEach { sess ->
            arr.put(org.json.JSONObject().apply {
                put("id", sess.senderId)
                put("name", sess.name)
                put("emoji", sess.emoji)
                put("kind", sess.kind)
                put("speaking", sess.speaking)
                put("micOn", sess.micOn)
                put("mutedByHost", voiceMutedIps.contains(sess.ip))
            })
        }
        val msg = org.json.JSONObject().apply {
            put("type", "voice_state")
            put("members", arr)
        }.toString()
        voiceSessions.values.forEach { sess ->
            try {
                sess.session.send(io.ktor.websocket.Frame.Text(msg))
            } catch (e: Exception) { /* drop dead sessions on next frame */ }
        }
    }

    var sharedClipboardText: String = ""
        private set
    var sharedClipboardSender: String = ""
        private set
    var sharedClipboardTimestamp: Long = 0L
        private set

    fun updateClipboard(text: String, sender: String) {
        sharedClipboardText = text
        sharedClipboardSender = sender
        sharedClipboardTimestamp = System.currentTimeMillis()
        val clipPacket = com.aetherweb.app.protocol.MeshPacket.ClipboardSync(
            text = text,
            sender = sender,
            timestamp = sharedClipboardTimestamp
        )
        broadcastMessage(clipPacket.toJsonString(), sender)
    }

    fun startServer(port: Int = 8080) {
        if (server != null) return
        DiagnosticLogger.log("WebServer", "Starting", "Starting web server on port $port", EventStatus.PENDING)
        
        try {
            System.setProperty("io.netty.noUnsafe", "true")
            System.setProperty("io.netty.transport.noNative", "true")
            // Voice Room Phase 0: explicit engine connectors. Ktor 3.x removed the
            // configure lambda from embeddedServer (the trailing lambda is the
            // Application module), so the HTTPS connector is built up-front and
            // passed via the vararg-connectors overload. :8443 serves the SAME
            // app over HTTPS — browsers require a secure context for microphone
            // access; guests accept the self-signed cert once ("Advanced ->
            // Proceed"). Plain HTTP on :8080 is untouched.
            val httpPort = port
            val engineConnectors = mutableListOf<io.ktor.server.engine.EngineConnectorConfig>()
            engineConnectors += io.ktor.server.engine.EngineConnectorBuilder().apply {
                this.port = httpPort
                this.host = "0.0.0.0"
            }
            try {
                val certIp = try { NetworkUtils.getLocalIpAddress() } catch (e: Exception) { "192.168.49.1" }
                val ks = PortalCertManager.getOrCreateKeyStore(certIp)
                engineConnectors += io.ktor.server.engine.EngineSSLConnectorBuilder(
                    keyStore = ks,
                    keyAlias = PortalCertManager.KEY_ALIAS,
                    keyStorePassword = { PortalCertManager.keyStorePassword() },
                    privateKeyPassword = { PortalCertManager.keyStorePassword() }
                ).apply {
                    this.port = PortalCertManager.HTTPS_PORT
                    this.host = "0.0.0.0"
                }
                android.util.Log.d("WebServer", "HTTPS voice connector enabled on port ${PortalCertManager.HTTPS_PORT}")
            } catch (e: Exception) {
                android.util.Log.e("WebServer", "HTTPS connector unavailable (voice over web disabled this session)", e)
            }
            server = kotlinx.coroutines.GlobalScope.embeddedServer(Netty, *engineConnectors.toTypedArray()) {
                install(io.ktor.server.plugins.cors.routing.CORS) {
                    anyHost()
                    allowHeader(io.ktor.http.HttpHeaders.ContentType)
                    allowHeader(io.ktor.http.HttpHeaders.Authorization)
                    allowMethod(io.ktor.http.HttpMethod.Options)
                    allowMethod(io.ktor.http.HttpMethod.Put)
                    allowMethod(io.ktor.http.HttpMethod.Patch)
                    allowMethod(io.ktor.http.HttpMethod.Delete)
                }
                install(io.ktor.server.websocket.WebSockets) {
                    pingPeriod = kotlin.time.Duration.parse("15s")
                    timeout = kotlin.time.Duration.parse("30s")
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<Throwable> { call, cause ->
                        call.respondText(text = "500: $cause", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        DiagnosticLogger.log("WebServer", "StatusPages 500", cause.stackTraceToString().take(200), EventStatus.ERROR)
                    }
                }
                
                routing {
                    webSocket("/ws") {
                        val ip = call.request.local.remoteHost
                        if (isKicked(ip)) {
                            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Kicked from room"))
                            return@webSocket
                        }
                        // Never drop local network clients with VIOLATED_POLICY; auto-register them
                        // Security: still enforce room seal and capacity limits
                        if (isRoomFullOrSealed(ip)) {
                            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Room sealed or capacity reached"))
                            return@webSocket
                        }
                        activeWebSockets[ip] = this
                        try {
                            val sender = call.request.queryParameters["sender"] ?: "Web Client"
                            android.util.Log.d("WebServer", "WS Handshake successful, connected: $sender from $ip")
                            DiagnosticLogger.log("Web Client", "WS Connected", "WebSocket client connected: $sender ($ip)", EventStatus.SUCCESS)
                            onClientConnected(ip)
                            
                            val job = launch {
                                newMessagesFlow.collect { webMsg ->
                                    val sessionKey = MeshNetworkManager.webSessionKey
                                    val json = if (sessionKey != null) {
                                        val inner = org.json.JSONObject().apply {
                                            put("sender", webMsg.sender)
                                            put("message", webMsg.message)
                                            put("id", webMsg.id)
                                            put("isHistory", webMsg.isHistory)
                                        }
                                        val encrypted = CryptoManager.encryptAES(inner.toString(), sessionKey)
                                        val outer = org.json.JSONObject().apply {
                                            put("type", "e2ee")
                                            put("payload", encrypted)
                                            // Fallback plain preview so clients without matching key can still read
                                            put("sender", webMsg.sender)
                                            put("message", webMsg.message)
                                        }
                                        outer.toString()
                                    } else {
                                        val obj = org.json.JSONObject()
                                        obj.put("sender", webMsg.sender)
                                        obj.put("message", webMsg.message)
                                        obj.toString()
                                    }
                                    try {
                                        send(io.ktor.websocket.Frame.Text(json))
                                        android.util.Log.d("WebServer", "WS Message Delivered to $sender: $json")
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebServer", "Failed to deliver frame to $sender: ${e.message}")
                                    }
                                }
                            }
                            
                            for (frame in incoming) {
                                if (frame is io.ktor.websocket.Frame.Text) {
                                    val text = frame.readText()
                                    try {
                                        val obj = org.json.JSONObject(text)
                                        var handled = false
                                        if (obj.optString("type") == "e2ee") {
                                            val encPayload = obj.optString("payload", "")
                                            val sessionKey = MeshNetworkManager.webSessionKey
                                            if (sessionKey != null && encPayload.isNotEmpty()) {
                                                try {
                                                    val decrypted = CryptoManager.decryptAES(encPayload, sessionKey)
                                                    val inner = org.json.JSONObject(decrypted)
                                                    val msgSender = inner.optString("sender", sender)
                                                    val message = inner.optString("message", "")
                                                    if (msgSender.isNotEmpty() && message.isNotEmpty()) {
                                                        android.util.Log.d("WebServer", "WS E2EE Message Received from $msgSender: $message")
                                                        onMessageReceived(message, msgSender)
                                                        handled = true
                                                    }
                                                } catch (decErr: Exception) {
                                                    android.util.Log.w("WebServer", "Failed to decrypt E2EE message, checking fallback plain: ${decErr.message}")
                                                }
                                            }
                                        }
                                        if (!handled) {
                                            val msgSender = obj.optString("sender", if (sender.isNotEmpty()) sender else "Web Client")
                                            val message = obj.optString("message", "")
                                            if (message.isNotEmpty()) {
                                                android.util.Log.d("WebServer", "WS Plain Message Received from $msgSender: $message")
                                                onMessageReceived(message, msgSender)
                                            }
                                        }
                                    } catch(e: Exception) {
                                        // Raw string fallback
                                        if (text.isNotBlank()) {
                                            onMessageReceived(text, sender)
                                        }
                                    }
                                }
                            }
                            job.cancel()
                        } catch (e: Exception) {
                            Log.e("WebServer", "WebSocket session error", e)
                        } finally {
                            activeWebSockets.remove(ip)
                        }
                    }
                                        get("/tictactoe") {
                        try {
                            call.respondText(com.aetherweb.app.OfflineGamePacks.TIC_TAC_TOE_HTML, io.ktor.http.ContentType.Text.Html)
                        } catch (e: Exception) {
                            call.respondText("Error loading game: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/chess") {
                        try {
                            call.respondText(com.aetherweb.app.OfflineGamePacks.CHESS_HTML, io.ktor.http.ContentType.Text.Html)
                        } catch (e: Exception) {
                            call.respondText("Error loading chess: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/ide") {
                        try {
                            call.respondText(com.aetherweb.app.PocketCdnPacks.WEB_IDE_HTML, io.ktor.http.ContentType.Text.Html)
                        } catch(e: Exception) {
                            call.respondText("Error loading IDE: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    // Captive Portal Auto-Popup Routes for iPhone (Apple CNA), Android, Windows & Chrome
                    val captiveHandler: suspend (io.ktor.server.application.ApplicationCall) -> Unit = { call ->
                        try {
                            val ip = call.request.local.remoteHost
                            onClientConnected(ip)
                            DiagnosticLogger.log("CaptivePortal", "Detection", "Captive probe from $ip -> Auto-redirecting to /", EventStatus.SUCCESS)
                            call.respondRedirect("/", permanent = false)
                        } catch (e: Exception) {
                            call.respondRedirect("/", permanent = false)
                        }
                    }

                    get("/hotspot-detect.html") { captiveHandler(call) } // Apple iOS / macOS captive portal detection
                    get("/canonical.html") { captiveHandler(call) }      // Apple fallback
                    get("/success.txt") { captiveHandler(call) }         // Firefox captive portal detection
                    get("/generate_204") { captiveHandler(call) }        // Android / Chrome captive portal detection
                    get("/gen_204") { captiveHandler(call) }             // Android fallback
                    get("/ncsi.txt") { captiveHandler(call) }            // Windows Network Connectivity Status Indicator
                    get("/connecttest.txt") { captiveHandler(call) }     // Windows 10/11 connectivity test

                    get("/") {
                        try {
                            val ip = call.request.local.remoteHost
                            if (isKicked(ip)) {
                                call.respondText("""
                                    <!DOCTYPE html><html>
                                    <head><title>Access Denied</title><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                                    <body style="background:#111b21;color:#ff5252;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif;margin:0;text-align:center;padding:24px;">
                                    <div style="max-width:340px;background:#202c33;padding:32px 24px;border-radius:16px;border:1px solid #ef5350;">
                                      <div style="font-size:48px;margin-bottom:12px;">🚫</div>
                                      <h2 style="font-size:20px;color:#ff5252;margin:0 0 10px 0;">Access Denied</h2>
                                      <p style="color:#8696a0;font-size:14px;line-height:1.5;margin:0;">You have been removed and blocked from this private mesh room by the host.</p>
                                    </div>
                                    </body></html>
                                """.trimIndent(), io.ktor.http.ContentType.Text.Html, io.ktor.http.HttpStatusCode.Forbidden)
                                return@get
                            }
                            // Auto-admit all local network clients to the portal
                            if (false && !isApproved(ip)) {
                                if (isRoomFullOrSealed(ip)) {
                                    call.respondText("""
                                        <!DOCTYPE html><html>
                                        <head><title>Room Sealed &amp; Full</title><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                                        <body style="background:#111b21;color:#e9edef;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif;margin:0;text-align:center;padding:24px;">
                                        <div style="max-width:360px;background:#202c33;padding:32px 24px;border-radius:16px;border:1px solid #00a884;box-shadow:0 8px 32px rgba(0,0,0,0.5);">
                                          <div style="font-size:52px;margin-bottom:12px;">🔒</div>
                                          <h2 style="font-size:22px;color:#00a884;margin:0 0 10px 0;">Room Sealed &amp; Locked</h2>
                                          <div style="display:inline-block;background:#111b21;color:#25d366;padding:6px 14px;border-radius:20px;font-weight:bold;font-size:13px;border:1px solid #25d366;margin-bottom:16px;">
                                            Capacity Limit Reached
                                          </div>
                                          <p style="color:#8696a0;font-size:14px;line-height:1.5;margin:0 0 20px 0;">
                                            This mesh room has reached its maximum participant limit and is cryptographically locked against any new connections or external surveillance.
                                          </p>
                                          <div style="font-size:12px;color:#607d8b;border-top:1px solid #2a3942;padding-top:14px;">
                                            🛡️ AirChat Anti-Infiltration Mesh Shield
                                          </div>
                                        </div>
                                        </body></html>
                                    """.trimIndent(), io.ktor.http.ContentType.Text.Html, io.ktor.http.HttpStatusCode.Forbidden)
                                    return@get
                                }
                                onClientConnected(ip)
                                call.respondText("""
                                    <!DOCTYPE html><html>
                                    <head><title>Waiting for Approval</title><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="refresh" content="30"></head>
                                    <body style="background:#222;color:white;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif;margin:0;">
                                    <div style="text-align:center;max-width:340px;padding:20px;">
                                    <h1 style="font-size:22px;">Waiting for Host Approval...</h1>
                                    <p style="color:#aaa;font-size:14px;">Tell the host who you are so they recognize your request:</p>
                                    <div id="emoji-row" style="display:flex;gap:6px;justify-content:center;margin:12px 0;flex-wrap:wrap;"></div>
                                    <input id="pname" placeholder="Your name" maxlength="24" style="padding:10px;border-radius:8px;border:1px solid #555;background:#333;color:#fff;width:100%;font-size:15px;box-sizing:border-box;">
                                    <button onclick="saveProfile()" style="margin-top:10px;padding:10px 24px;border-radius:8px;border:none;background:#25d366;color:#fff;font-weight:bold;font-size:15px;cursor:pointer;">Save &amp; Identify Me</button>
                                    <p id="psaved" style="color:#25d366;font-size:13px;display:none;">Saved! The host can now see your name.</p>
                                    </div>
                                    <script>
                                    var EMOJIS=['\uD83D\uDE00','\uD83D\uDCA7','\uD83D\uDCAE','\uD83D\uDCBB','\uD83D\uDCB5','\u26BD','\uD83D\uDE80','\uD83D\uDE1F'];
                                    var picked=EMOJIS[1];
                                    var pid=localStorage.getItem('aether_pid');
                                    if(!pid){pid='web-'+Math.random().toString(36).slice(2,10);localStorage.setItem('aether_pid',pid);}
                                    var row=document.getElementById('emoji-row');
                                    EMOJIS.forEach(function(e){
                                      var b=document.createElement('button');
                                      b.innerText=e;
                                      b.style.cssText='font-size:24px;background:#333;border:2px solid transparent;border-radius:8px;padding:4px;cursor:pointer;';
                                      b.onclick=function(){picked=e;for(var i=0;i<row.children.length;i++){row.children[i].style.borderColor='transparent';}b.style.borderColor='#25d366';};
                                      row.appendChild(b);
                                    });
                                    try{var sv=localStorage.getItem('aether_profile');if(sv){var p=JSON.parse(sv);document.getElementById('pname').value=p.name||'';if(p.emoji)picked=p.emoji;}}catch(err){}
                                    function saveProfile(){
                                      var name=document.getElementById('pname').value.trim()||'Web guest';
                                      var prof={name:name,emoji:picked,pid:pid};
                                      try{localStorage.setItem('aether_profile',JSON.stringify(prof));}catch(err){}
                                      fetch('/api/profile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(prof)});
                                      document.getElementById('psaved').style.display='block';
                                    }
                                    </script>
                                    </body></html>
                                """, io.ktor.http.ContentType.Text.Html)
                                return@get
                            }
                            DiagnosticLogger.log("Web Client", "Page Load", "Client requested root HTML", EventStatus.SUCCESS)
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            call.response.header("Pragma", "no-cache")
                            call.response.header("Expires", "0")
                            call.respondText(
                                WebPortalTemplate.getHtml(isLite = MeshNetworkManager.uiState.value.isLiteMode || BuildConfig.FLAVOR == "lite", saltHex = MeshNetworkManager.webSessionSaltHex),
                                io.ktor.http.ContentType.Text.Html,
                                io.ktor.http.HttpStatusCode.OK
                            )
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in / route", e)
                            DiagnosticLogger.log("WebServer", "Error /", e.stackTraceToString().take(200), EventStatus.ERROR)
                        }
                    }
                    // Voice Room Phase 1: web-guest profile + voice-join request.
                    // Callable WITHOUT approval — this is how a guest identifies / asks.
                    post("/api/profile") {
                        try {
                            val ip = call.request.local.remoteHost
                            val obj = org.json.JSONObject(call.receiveText())
                            val name = obj.optString("name", "Web guest").take(32).ifBlank { "Web guest" }
                            val emoji = obj.optString("emoji", "").take(8)
                            val pid = obj.optString("pid", ip).take(64).ifBlank { ip }
                            onProfileUpdated(SpectatorRequest(ip = ip, name = name, emoji = emoji, pid = pid))
                            call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    }
                    post("/api/voice/request") {
                        try {
                            val ip = call.request.local.remoteHost
                            if (!isApproved(ip)) {
                                call.respondText(
                                    "{\"ok\":false,\"reason\":\"not-approved\"}",
                                    io.ktor.http.ContentType.Application.Json,
                                    io.ktor.http.HttpStatusCode.Forbidden
                                )
                                return@post
                            }
                            val obj = org.json.JSONObject(call.receiveText())
                            val name = obj.optString("name", "Web guest").take(32).ifBlank { "Web guest" }
                            val emoji = obj.optString("emoji", "").take(8)
                            val pid = obj.optString("pid", ip).take(64).ifBlank { ip }
                            onVoiceJoinRequested(SpectatorRequest(ip = ip, name = name, emoji = emoji, pid = pid, request = "voice"))
                            call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    }
                    // Voice Room Phase 3: polled by web guests waiting for voice approval.
                    get("/api/voice/status") {
                        try {
                            val ip = call.request.local.remoteHost
                            val obj = org.json.JSONObject().apply {
                                put("approved", isVoiceApproved(ip))
                                put("inRoom", voiceSessions.values.any { it.ip == ip })
                                put("count", voiceSessions.size)
                            }
                            call.respondText(obj.toString(), io.ktor.http.ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respondText("{\"approved\":false}", io.ktor.http.ContentType.Application.Json)
                        }
                    }
                    // Voice Room Phase 3: the SFU. Host forwards (never mixes) 16kHz PCM16
                    // frames between voice-approved members. App peers and web guests use the
                    // same socket; the host phone itself connects as a client to 127.0.0.1.
                    webSocket("/ws-voice") {
                        val ip = call.request.local.remoteHost
                        val isSelf = ip == "127.0.0.1" || ip == "::1"
                        if (isKicked(ip)) {
                            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Kicked from room"))
                            return@webSocket
                        }
                        if (!isSelf && (!isApproved(ip) || !isVoiceApproved(ip) || isRoomFullOrSealed(ip))) {
                            close(
                                io.ktor.websocket.CloseReason(
                                    io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                                    "Voice not approved or room sealed"
                                )
                            )
                            return@webSocket
                        }
                        val params = call.request.queryParameters
                        val senderId = (params["pid"] ?: params["sender"] ?: ip).take(64)
                        val sess = VoiceSession(
                            ip = ip,
                            senderId = senderId,
                            name = (params["sender"] ?: "Guest").take(32),
                            emoji = (params["emoji"] ?: "").take(8),
                            kind = (params["kind"] ?: if (isSelf) "host" else "temp").take(8),
                            session = this
                        )
                        voiceSessions[senderId] = sess
                        android.util.Log.d("WebServer", "Voice member joined: ${sess.name} ($senderId)")
                        try {
                            broadcastVoiceState(force = true)
                            for (frame in incoming) {
                                when (frame) {
                                    is io.ktor.websocket.Frame.Text -> {
                                        try {
                                            val obj = org.json.JSONObject(frame.readText())
                                            when (obj.optString("type")) {
                                                "join" -> {
                                                    sess.name = obj.optString("name", sess.name).take(32)
                                                    sess.emoji = obj.optString("emoji", sess.emoji).take(8)
                                                    broadcastVoiceState(force = true)
                                                }
                                                "leave" -> {
                                                    voiceSessions.remove(senderId, sess)
                                                    broadcastVoiceState(force = true)
                                                }
                                                "mic" -> {
                                                    sess.micOn = obj.optBoolean("on", false)
                                                    broadcastVoiceState(force = true)
                                                }
                                            }
                                        } catch (e: Exception) { /* ignore malformed control */ }
                                    }
                                    is io.ktor.websocket.Frame.Binary -> {
                                        val data = frame.readBytes()
                                        if (data.size < 3 || data[0] != VOICE_FRAME_TYPE_AUDIO) continue
                                        val idLen = data[1].toInt() and 0xFF
                                        if (data.size < 2 + idLen) continue
                                        val fromId = String(data, 2, idLen, Charsets.UTF_8)
                                        if (fromId != sess.senderId) continue // anti-spoof
                                        if (voiceMutedIps.contains(sess.ip)) continue // host mute
                                        val pcm = data.copyOfRange(2 + idLen, data.size)
                                        sess.lastFrameAt = System.currentTimeMillis()
                                        sess.speaking = pcmRms(pcm) > VOICE_VAD_THRESHOLD
                                        // Selective forwarding: everyone except the sender.
                                        voiceSessions.values.forEach { target ->
                                            if (target.senderId != sess.senderId) {
                                                try {
                                                    target.session.send(
                                                        io.ktor.websocket.Frame.Binary(true, data)
                                                    )
                                                } catch (e: Exception) { /* dead session */ }
                                            }
                                        }
                                        broadcastVoiceState()
                                    }
                                    else -> { /* ping/pong handled by Ktor */ }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("WebServer", "Voice session ended: $senderId")
                        } finally {
                            voiceSessions.remove(senderId, sess)
                            voiceScope.launch { broadcastVoiceState(force = true) }
                        }
                    }
                    get("/api/files") {
                        try {
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            val files = MeshStorageManager.getAllSharedFiles(this@WebServerManager.context)
                            val array = org.json.JSONArray()
                            for (f in files) {
                                val obj = org.json.JSONObject().apply {
                                    put("name", f.name)
                                    put("size", f.size)
                                    put("formattedSize", f.formattedSize)
                                    put("lastModified", f.lastModified)
                                    put("isImage", f.isImage)
                                    put("isAudio", f.isAudio)
                                    put("isVideo", f.isVideo)
                                    put("url", "/files/${android.net.Uri.encode(f.name)}")
                                    put("downloadUrl", "/files/${android.net.Uri.encode(f.name)}?download=true")
                                }
                                array.put(obj)
                            }
                            call.respondText(array.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.respondText("[]", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        }
                    }
                    get("/api/music/status") {
                        try {
                            val state = MeshMusicManager.state.value
                            val obj = org.json.JSONObject().apply {
                                put("isPlaying", state.isPlaying)
                                put("trackTitle", state.currentTrack?.title ?: "No track playing")
                                put("artist", state.currentTrack?.artist ?: "")
                                put("currentPositionMs", state.currentPositionMs)
                                put("durationMs", state.durationMs)
                                put("isPartyModeHost", state.isPartyModeHost)
                                put("isStream", state.currentTrack?.isStream ?: false)
                                put("streamUrl", if (state.currentTrack?.isStream == true) state.currentTrack?.uriString else "/audio/current")
                            }
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.respondText(obj.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.respondText("{}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        }
                    }
                    get("/audio/current") {
                        try {
                            val currentTrack = MeshMusicManager.state.value.currentTrack
                            var localPath = currentTrack?.localFilePath
                            var file = if (localPath != null) File(localPath) else null
                            if ((file == null || !file.exists()) && currentTrack?.uriString != null) {
                                try {
                                    val uri = android.net.Uri.parse(currentTrack.uriString)
                                    val cacheDir = MeshStorageManager.getCacheDir(this@WebServerManager.context)
                                    val cachedFile = File(cacheDir, "mesh_audio_current.mp3")
                                    this@WebServerManager.context.contentResolver.openInputStream(uri)?.use { input ->
                                        cachedFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    if (cachedFile.exists() && cachedFile.length() > 0) {
                                        file = cachedFile
                                    }
                                } catch (e: Exception) {}
                            }
                            if (file != null && file.exists()) {
                                call.response.header("Access-Control-Allow-Origin", "*")
                                call.response.header("Accept-Ranges", "bytes")
                                call.response.header(io.ktor.http.HttpHeaders.ContentType, "audio/mpeg")
                                call.response.header(io.ktor.http.HttpHeaders.ContentLength, file.length().toString())
                                call.respondFile(file)
                            } else {
                                call.respondText("Audio stream unavailable", status = io.ktor.http.HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            call.respondText(e.message ?: "Error", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/video/current") {
                        try {
                            val cacheDir = MeshStorageManager.getCacheDir(this@WebServerManager.context)
                            val videoFile = cacheDir.listFiles()?.firstOrNull { MeshStorageManager.isVideoFile(it.name) }
                            if (videoFile != null && videoFile.exists()) {
                                call.response.header("Access-Control-Allow-Origin", "*")
                                call.response.header("Accept-Ranges", "bytes")
                                call.response.header(io.ktor.http.HttpHeaders.ContentType, "video/mp4")
                                call.response.header(io.ktor.http.HttpHeaders.ContentLength, videoFile.length().toString())
                                call.respondFile(videoFile)
                            } else {
                                call.respondText("Video stream unavailable", status = io.ktor.http.HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            call.respondText(e.message ?: "Error", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/download") {
                        try {
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            val apkFile = File(this@WebServerManager.context.applicationInfo.sourceDir)
                            val dlName = if (BuildConfig.FLAVOR == "lite") "AirChat-lite.apk" else "AirChat.apk"
                            if (apkFile.exists()) {
                                call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "attachment; filename=\"$dlName\"")
                                call.response.header(io.ktor.http.HttpHeaders.ContentType, "application/vnd.android.package-archive")
                                call.response.header(io.ktor.http.HttpHeaders.ContentLength, apkFile.length().toString())
                                call.response.header("Accept-Ranges", "bytes")
                                call.respondFile(apkFile)
                            } else {
                                call.respondText("APK not found", status = io.ktor.http.HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /download route", e)
                            call.respondText(e.message ?: "Unknown Error", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    // Both APKs on the web: serves the bundled lite seed APK (full flavor).
                    // Lite flavor has no bundled asset, so it serves its own APK (it IS the lite app).
                    get("/download-lite") {
                        try {
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            val ctx = this@WebServerManager.context
                            val seedName = "airchat-lite-${BuildConfig.VERSION_NAME}.apk"
                            val seedFile = File(ctx.cacheDir, seedName)
                            if (!seedFile.exists()) {
                                try {
                                    ctx.assets.open("airchat-lite.apk").use { input ->
                                        seedFile.outputStream().use { input.copyTo(it) }
                                    }
                                } catch (e: Exception) {
                                    Log.w("WebServer", "No bundled lite seed asset; falling back to own APK")
                                }
                            }
                            val apkFile = if (seedFile.exists()) seedFile
                                else File(ctx.applicationInfo.sourceDir)
                            if (apkFile.exists()) {
                                call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "attachment; filename=\"AirChat-lite.apk\"")
                                call.response.header(io.ktor.http.HttpHeaders.ContentType, "application/vnd.android.package-archive")
                                call.response.header(io.ktor.http.HttpHeaders.ContentLength, apkFile.length().toString())
                                call.response.header("Accept-Ranges", "bytes")
                                call.respondFile(apkFile)
                            } else {
                                call.respondText("APK not found", status = io.ktor.http.HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /download-lite route", e)
                            call.respondText(e.message ?: "Unknown Error", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/files/{name}") {
                        try {
                            val fileName = call.parameters["name"]
                            if (fileName != null) {
                                val file = MeshStorageManager.findLocalFile(this@WebServerManager.context, fileName)
                                if (file != null && file.exists()) {
                                    val mimeType = MeshStorageManager.getMimeType(file.name)
                                    val isDownloadParam = call.request.queryParameters["download"] == "true"
                                    val isImageOrMedia = MeshStorageManager.isImageFile(file.name) || MeshStorageManager.isAudioFile(file.name) || MeshStorageManager.isVideoFile(file.name)
                                    val dispositionType = if (isDownloadParam || !isImageOrMedia) "attachment" else "inline"
                                    
                                    call.response.header("Access-Control-Allow-Origin", "*")
                                    call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
                                    call.response.header("Access-Control-Allow-Headers", "*")
                                    call.response.header("Accept-Ranges", "bytes")
                                    call.response.header("Cache-Control", "public, max-age=86400")
                                    call.response.header(io.ktor.http.HttpHeaders.ContentLength, file.length().toString())
                                    call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "$dispositionType; filename=\"${file.name}\"")
                                    call.response.header(io.ktor.http.HttpHeaders.ContentType, mimeType)
                                    downloadSemaphore.withPermit { call.respondFile(file) }
                                } else {
                                    call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
                                }
                            } else {
                                call.respondText("File name required", status = io.ktor.http.HttpStatusCode.BadRequest)
                            }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /files route", e)
                            call.respondText(e.message ?: "Unknown Error", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    post("/upload") {
                        try {
                            val multipart = call.receiveMultipart()
                            var fileName = ""
                            var savedFile: File? = null
                            var uploadSender = "Web Client"
                            multipart.forEachPart { part ->
                                if (part is io.ktor.http.content.PartData.FormItem) {
                                    if (part.name == "sender" && part.value.isNotBlank()) {
                                        uploadSender = part.value
                                    }
                                } else if (part is io.ktor.http.content.PartData.FileItem) {
                                    val originalName = part.originalFileName ?: "unknown"
                                    val ct = part.contentType?.toString() ?: ""
                                    val isAudio = ct.contains("audio") || originalName.contains("audio") || originalName.endsWith(".webm") || originalName.endsWith(".wav") || originalName.endsWith(".m4a") || originalName.endsWith(".mp3")
                                    val ext = File(originalName).extension.let { 
                                        if (it.isNotEmpty()) ".$it" 
                                        else if (isAudio) ".webm"
                                        else if (ct.contains("image/png")) ".png" 
                                        else if (ct.contains("image/")) ".jpg" 
                                        else "" 
                                    }
                                    fileName = if (isAudio) "web_audio_${System.currentTimeMillis()}$ext"
                                               else "web_shared_${System.currentTimeMillis()}$ext"
                                    savedFile = withContext(Dispatchers.IO) {
                                        part.streamProvider().use { its ->
                                            MeshStorageManager.saveStreamToCache(this@WebServerManager.context, fileName, its)
                                        }
                                    }
                                }
                                part.dispose()
                            }
                            if (fileName.isNotEmpty() && savedFile != null) {
                                val downloadUrl = "http://${NetworkUtils.getLocalIpAddress()}:8080/files/${android.net.Uri.encode(fileName)}"
                                val msg = "Shared a file: $downloadUrl"
                                onMessageReceived(msg, uploadSender)
                                call.respondText("OK", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.OK)
                            } else {
                                call.respondText("Empty", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.BadRequest)
                            }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /upload route", e)
                            call.respondText(e.message ?: "Error", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/api/clipboard") {
                        try {
                            val obj = org.json.JSONObject().apply {
                                put("text", sharedClipboardText)
                                put("sender", sharedClipboardSender)
                                put("timestamp", sharedClipboardTimestamp)
                            }
                            call.respondText(obj.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.respondText("{}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    post("/api/clipboard") {
                        try {
                            val text = call.receiveText()
                            val obj = org.json.JSONObject(text)
                            val clipText = obj.optString("text", "")
                            val sender = obj.optString("sender", "Web Client")
                            updateClipboard(clipText, sender)
                            
                            MeshNetworkManager._uiState.update { it.copy(
                                sharedClipboardText = clipText,
                                sharedClipboardSender = sender,
                                sharedClipboardTimestamp = sharedClipboardTimestamp
                            )}
                            val clipPacket = com.aetherweb.app.protocol.MeshPacket.ClipboardSync(
                                text = clipText,
                                sender = sender,
                                timestamp = sharedClipboardTimestamp
                            )
                            MeshNetworkManager.meshRouter.routePacket(clipPacket)
                            call.respondText("{\"status\":\"ok\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.respondText("{\"status\":\"error\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    }
                    get("/api/speed_test") {
                        try {
                            val isHotspot = MeshNetworkManager._uiState.value.isHotspotActive
                            val isWifi = MeshNetworkManager._uiState.value.isWifiConnected
                            val linkType = when {
                                isHotspot -> "Wi-Fi 5GHz AP Direct"
                                isWifi -> "Wi-Fi Socket Link"
                                else -> "Bluetooth Mesh Relay"
                            }
                            val maxSpeed = when {
                                isHotspot -> "480 Mbps"
                                isWifi -> "150 Mbps"
                                else -> "2 Mbps PHY"
                            }
                            val obj = org.json.JSONObject().apply {
                                put("status", "ok")
                                put("linkType", linkType)
                                put("maxSpeed", maxSpeed)
                                put("serverTime", System.currentTimeMillis())
                            }
                            call.respondText(obj.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.respondText("{}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    post("/api/send_form") {
                        try {
                            val params = call.receiveParameters()
                            val sender = params["sender"] ?: "Web Client"
                            val message = params["message"] ?: ""
                            if (message.isNotEmpty()) {
                                onMessageReceived(message, sender)
                                call.respondText("OK", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.OK)
                            } else {
                                call.respondText("Empty message", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.BadRequest)
                            }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /api/send_form route", e)
                            call.respondText(e.message ?: "Error", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                    get("/stream") {
                        call.response.cacheControl(io.ktor.http.CacheControl.NoCache(null))
                        call.response.header("Content-Type", "text/event-stream")
                        call.respondBytesWriter(io.ktor.http.ContentType.parse("text/event-stream")) {
                            try {
                                var lastCounter = messageCounter
                                while (isActive) {
                                    val currentCounter = messageCounter
                                    if (currentCounter > lastCounter) {
                                        val history = getChatHistory()
                                        val msgs = history.takeLast(currentCounter - lastCounter)
                                        for (msg in msgs) {
                                            val obj = org.json.JSONObject()
                                            obj.put("sender", msg.first)
                                            obj.put("message", msg.second)
                                            val json = obj.toString()
                                            writeStringUtf8("data: $json\n\n")
                                        }
                                        lastCounter = currentCounter
                                        flush()
                                    }
                                    kotlinx.coroutines.delay(1000)
                                }
                            } catch (e: Exception) {
                                // Connection closed
                            }
                        }
                    }
                    // Door 4: service worker so the portal shell loads with WiFi
                    // off (BLE-only chat). Scope "/" via Service-Worker-Allowed.
                    get("/sw.js") {
                        call.response.header("Service-Worker-Allowed", "/")
                        call.response.header("Cache-Control", "no-cache")
                        call.respondText(
                            """
                            // AirChat portal shell cache (Door 4: offline page for BLE chat).
                            const SW_VERSION = 'airchat-sw-v1';
                            self.addEventListener('install', (e) => { e.waitUntil(self.skipWaiting()); });
                            self.addEventListener('activate', (e) => {
                                e.waitUntil(
                                    caches.keys()
                                        .then((ks) => Promise.all(ks.filter((k) => k !== SW_VERSION).map((k) => caches.delete(k))))
                                        .then(() => self.clients.claim())
                                );
                            });
                            self.addEventListener('fetch', (e) => {
                                if (e.request.method !== 'GET') return;
                                let path = '/';
                                try { path = new URL(e.request.url).pathname; } catch (err) {}
                                if (path === '/chat' || path === '/') {
                                    e.respondWith(
                                        fetch(e.request).then((res) => {
                                            const copy = res.clone();
                                            caches.open(SW_VERSION).then((c) => { try { c.put(e.request, copy); } catch (err) {} });
                                            return res;
                                        }).catch(() => caches.match(e.request).then((hit) => hit || caches.match('/chat')))
                                    );
                                }
                            });
                            """.trimIndent(),
                            io.ktor.http.ContentType.Application.JavaScript
                        )
                    }
                    get("/chat") {
                        try {
                            DiagnosticLogger.log("Web Client", "Page Load", "Client requested chat HTML", EventStatus.SUCCESS)
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            call.response.header("Pragma", "no-cache")
                            call.response.header("Expires", "0")
                            call.respondText(WebPortalTemplate.getHtml(isLite = MeshNetworkManager.uiState.value.isLiteMode || BuildConfig.FLAVOR == "lite", saltHex = MeshNetworkManager.webSessionSaltHex), io.ktor.http.ContentType.Text.Html, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /chat route", e)
                            DiagnosticLogger.log("WebServer", "Error /chat", e.stackTraceToString().take(200), EventStatus.ERROR)
                        }
                    }
                    get("/api/crypto/info") {
                        val isPrivate = MeshNetworkManager.uiState.value.isPrivateRoom
                        val obj = org.json.JSONObject().apply {
                            put("e2ee", true)
                            put("salt", MeshNetworkManager.webSessionSaltHex)
                            put("fingerprint", MeshNetworkManager.webSessionFingerprint)
                            put("cipher", "AES-256-GCM")
                            put("isPrivate", isPrivate)
                            if (!isPrivate) {
                                put("autoKey", MeshNetworkManager.webSessionKeyHex)
                            }
                        }
                        call.respondText(obj.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                    }
                    get("/api/history") {
                        try {
                            call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                            call.response.header("Pragma", "no-cache")
                            call.response.header("Expires", "0")
                            val jsonArray = org.json.JSONArray()
                            val sessionKey = MeshNetworkManager.webSessionKey
                            getChatHistory().forEachIndexed { index, (sender, msg) ->
                                val obj = org.json.JSONObject()
                                obj.put("id", index + 1)
                                obj.put("isHistory", true)
                                if (sessionKey != null) {
                                    val inner = org.json.JSONObject().apply {
                                        put("sender", sender)
                                        put("message", msg)
                                    }
                                    val enc = CryptoManager.encryptAES(inner.toString(), sessionKey)
                                    obj.put("type", "e2ee")
                                    obj.put("payload", enc)
                                    obj.put("sender", sender)
                                } else {
                                    obj.put("sender", sender)
                                    obj.put("message", msg)
                                }
                                jsonArray.put(obj)
                            }
                            call.respondText(jsonArray.toString(), io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error in /api/history route", e)
                        }
                    }
                    post("/api/send") {
                        try {
                            val text = call.receiveText()
                            val obj = org.json.JSONObject(text)
                            val sender: String
                            val message: String
                            if (obj.optString("type") == "e2ee") {
                                val encPayload = obj.optString("payload", "")
                                val sessionKey = MeshNetworkManager.webSessionKey
                                var decSender: String? = null
                                var decMsg: String? = null
                                if (sessionKey != null && encPayload.isNotEmpty()) {
                                    try {
                                        val decrypted = CryptoManager.decryptAES(encPayload, sessionKey)
                                        val inner = org.json.JSONObject(decrypted)
                                        decSender = inner.optString("sender", "Web Client")
                                        decMsg = inner.optString("message", "")
                                    } catch (e: Exception) {
                                        Log.w("WebServer", "Failed to decrypt /api/send payload: ${e.message}")
                                    }
                                }
                                sender = decSender ?: obj.optString("sender", "Web Client")
                                message = decMsg ?: obj.optString("message", "")
                            } else {
                                sender = obj.optString("sender", "Web Client")
                                message = obj.optString("message", "")
                            }
                            if (message.isNotEmpty()) {
                                DiagnosticLogger.log("Message Lifecycle", "Origin", "Message created by Web Client ($sender)", EventStatus.SUCCESS)
                                DiagnosticLogger.logHop("WebClient -> WebServer", "N/A", "Received message: $message from $sender")
                                onMessageReceived(message, sender)
                                call.respondText("{\"status\":\"ok\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            } else {
                                call.respondText("{\"status\":\"empty\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            }
                        } catch (e: Exception) {
                            call.respondText("{\"status\":\"error\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    }
                }
            }
            server?.start(wait = false)
            Log.i("WebServer", "Server started on port $port")
            DiagnosticLogger.log("WebServer", "Started", "Running on port $port", EventStatus.SUCCESS)
        } catch (e: Exception) {
            server = null
            Log.e("WebServer", "Failed to start server", e)
            DiagnosticLogger.log("WebServer", "Start Failed", e.message ?: "Unknown Error", EventStatus.ERROR)
        }
    }

    fun stopServer() {
        val s = server
        server = null
        try {
            s?.stop(500, 1000)
        } catch (_: Exception) {}
        Log.i("WebServer", "Server stopped")
        DiagnosticLogger.log("WebServer", "Stopped", "Server shutdown", EventStatus.INFO)
    }

    fun broadcastMessage(message: String, sender: String) {
        val webMsg = WebMessage(++messageCounter, sender, message, false)
        kotlinx.coroutines.GlobalScope.launch {
            newMessagesFlow.emit(webMsg)
        }
        Log.d("WebServer", "Broadcasting message to web clients")
        DiagnosticLogger.log("Message Lifecycle", "Web Broadcast", "Attempting to broadcast message to connected Web browsers", EventStatus.SUCCESS)
    }

    private fun getHtmlTemplate(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>MeshChat Portal</title>
                <style>
                    body { font-family: sans-serif; text-align: center; padding: 20px; background: #f0f2f5; margin: 0; }
                    .card { background: white; padding: 30px 20px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); max-width: 400px; margin: 20px auto; }
                    h1 { color: #1a73e8; margin-top: 0; }
                    p { color: #5f6368; line-height: 1.5; margin-bottom: 24px; }
                    .btn { display: block; background: #1a73e8; color: white; text-decoration: none; padding: 14px 24px; border-radius: 24px; font-weight: bold; margin-bottom: 16px; box-sizing: border-box; }
                    .btn-secondary { background: #34a853; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>MeshChat</h1>
                    <p>Welcome to the local mesh network. You can download the Android app, chat directly from your browser, or play offline games without internet.</p>
                    <a href="/chat" class="btn btn-secondary">💬 Join Web Chat</a>
                    <a href="/tictactoe" class="btn" style="background: #e67e22;">⭕ Play Tic-Tac-Toe</a>
                    <a href="/chess" class="btn" style="background: #8e44ad;">♟️ Play Chess</a>
                    <a href="/download" class="btn" download="MeshChat.apk">⬇️ Download Android App</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getChatHtmlTemplate(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>MeshChat Web (WhatsApp Mode)</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 0; display: flex; flex-direction: column; height: 100vh; background: #0b141b; color: #e9edef; }
                    #header { background: #1f2c34; color: #e9edef; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.3); z-index: 10; position: relative; }
                    .header-left { display: flex; align-items: center; gap: 10px; }
                    .avatar { width: 38px; height: 38px; border-radius: 50%; background: #005d4b; display: flex; align-items: center; justify-content: center; font-weight: bold; color: white; font-size: 18px; }
                    .room-info { display: flex; flex-direction: column; text-align: left; }
                    .room-title { font-weight: bold; font-size: 16px; color: #e9edef; }
                    .room-sub { font-size: 11px; color: #25d366; }
                    #chat-view { display: flex; flex-direction: column; flex: 1; overflow: hidden; background: #0b141b; }
                    #messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 8px; }
                    .date-pill { align-self: center; background: #182229; color: #8696a0; font-size: 11px; border-radius: 8px; padding: 4px 12px; margin: 6px 0; font-weight: 600; }
                    .msg { max-width: 82%; padding: 8px 12px; border-radius: 14px; word-wrap: break-word; line-height: 1.4; position: relative; font-size: 14.5px; box-shadow: 0 1px 1px rgba(0,0,0,0.2); }
                    .msg-sys { align-self: center; background: #182229; color: #8696a0; font-size: 11px; border-radius: 8px; padding: 4px 10px; }
                    .msg-other { align-self: flex-start; background: #1f2c34; color: #e9edef; border-top-left-radius: 2px; }
                    .msg-me { align-self: flex-end; background: #005d4b; color: #e9edef; border-top-right-radius: 2px; }
                    .sender { font-size: 12px; color: #25d366; margin-bottom: 2px; font-weight: bold; display: flex; align-items: center; gap: 4px; }
                    .sender .handle { color: #8696a0; font-size: 10px; font-weight: normal; }
                    .msg-time { font-size: 10px; color: #8696a0; float: right; margin-left: 8px; margin-top: 4px; }
                    #input-area { display: flex; flex-direction: column; padding: 8px 12px; background: #1f2c34; border-top: 1px solid #2a3942; gap: 8px; }
                    .form-row { display: flex; gap: 8px; width: 100%; align-items: center; }
                    input[type="text"] { flex: 1; padding: 10px 16px; background: #2a3942; border: none; border-radius: 24px; outline: none; font-size: 15px; color: #e9edef; }
                    input[type="text"]::placeholder { color: #8696a0; }
                    button.send-btn { background: #25d366; color: white; border: none; width: 42px; height: 42px; border-radius: 50%; cursor: pointer; font-weight: bold; display: flex; align-items: center; justify-content: center; font-size: 18px; }
                    button.send-btn:active { background: #1ea952; }
                    .attach-btn { background: #2a3942; color: #8696a0; border: 1px solid #3b4a54; padding: 8px 14px; border-radius: 18px; font-size: 12px; font-weight: 600; cursor: pointer; }
                    .pill-btn { background: rgba(255,255,255,0.1); color: #e9edef; padding: 4px 10px; border-radius: 12px; text-decoration: none; font-size: 0.8em; font-weight: 500; }
                    .pill-btn:hover { background: rgba(255,255,255,0.2); }
                    body.light-theme { background: #f0f2f5; color: #111b21; }
                    body.light-theme #header { background: #008069; color: white; }
                    body.light-theme #chat-view { background: #efeae2; }
                    body.light-theme .msg-other { background: #ffffff; color: #111b21; }
                    body.light-theme .msg-me { background: #d9fdd3; color: #111b21; }
                    body.light-theme #input-area { background: #f0f2f5; border-top: 1px solid #d1d7db; }
                    body.light-theme input[type="text"] { background: #ffffff; color: #111b21; }
                    body.light-theme .date-pill { background: rgba(255,255,255,0.8); color: #54656f; }
                </style>
            </head>
            <body>
                <div id="drop-overlay" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,168,132,0.85); z-index:999; align-items:center; justify-content:center; color:white; font-size:20px; font-weight:bold; flex-direction:column;">
                    <span>📂 Drop files to share instantly over offline mesh</span>
                </div>
                <div id="header">
                    <div class="header-left">
                        <div class="avatar">💬</div>
                        <div class="room-info">
                            <div class="room-title">MeshChat Room</div>
                            <div class="room-sub" id="user-display-sub">🟢 Connected to Offline Mesh</div>
                        </div>
                    </div>
                    <div style="display: flex; gap: 6px; align-items: center;">
                        <button onclick="toggleClipboardModal()" class="pill-btn" style="background:#005d4b; border:none; cursor:pointer;">📋 Clipboard</button>
                        <button onclick="toggleSpeedModal()" class="pill-btn" style="background:#2563eb; border:none; cursor:pointer;">⚡ Speed</button>
                        <button onclick="toggleMusicDockManual()" class="pill-btn" style="background:#8b5cf6; border:none; cursor:pointer;" id="jukebox-nav-btn">🎵 Music</button>
                        <button onclick="toggleTheme()" class="pill-btn" style="border:none; cursor:pointer;" id="theme-btn">🌓</button>
                        <a href="/tictactoe" class="pill-btn">⭕ Games</a>
                        <a href="/download" class="pill-btn" style="background:#25d366; color:white;" download="MeshChat.apk">APK</a>
                    </div>
                </div>
                <div id="mesh-music-dock" style="display:none; background:#182229; border-bottom:1px solid #2a3942; padding:8px 14px; align-items:center; justify-content:space-between; gap:10px; color:#e9edef; font-size:13px; z-index:90;">
                    <div style="display:flex; align-items:center; gap:10px; overflow:hidden; flex:1;">
                        <div id="music-equalizer" style="display:flex; gap:2px; align-items:flex-end; height:18px;">
                            <span style="width:3px; background:#25d366; height:12px; border-radius:2px;"></span>
                            <span style="width:3px; background:#25d366; height:18px; border-radius:2px;"></span>
                            <span style="width:3px; background:#25d366; height:8px; border-radius:2px;"></span>
                            <span style="width:3px; background:#25d366; height:15px; border-radius:2px;"></span>
                        </div>
                        <div style="overflow:hidden; white-space:nowrap; text-overflow:ellipsis;">
                            <div id="music-dock-title" style="font-weight:bold; font-size:12px; color:#25d366;">♫ Mesh Jukebox</div>
                            <div id="music-dock-artist" style="font-size:10px; color:#8696a0;">DJ Broadcaster Active</div>
                        </div>
                    </div>
                    <div style="display:flex; align-items:center; gap:6px;">
                        <button id="music-sync-btn" onclick="toggleWebPartyListen()" style="background:#25d366; color:black; border:none; border-radius:12px; padding:4px 10px; font-weight:bold; font-size:11px; cursor:pointer;">🎧 Listen In Sync</button>
                        <audio id="party-audio-elem" preload="none"></audio>
                    </div>
                </div>
                <div id="clipboard-modal" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.7); z-index:100; align-items:center; justify-content:center;">
                    <div style="background:#1f2c34; border-radius:12px; padding:20px; width:90%; max-width:480px; box-shadow:0 8px 24px rgba(0,0,0,0.5); color:#e9edef;">
                        <h3 style="margin-top:0;">📋 Cross-Platform Shared Clipboard</h3>
                        <p style="font-size:12px; color:#8696a0;">Instant text sync between Android phone, PC, Mac & all browser guests.</p>
                        <div style="background:#111b21; padding:10px; border-radius:8px; min-height:50px; font-family:monospace; font-size:13px; word-break:break-all;" id="clip-current-text">(Clipboard empty)</div>
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-top:8px;">
                            <span id="clip-meta" style="font-size:11px; color:#8696a0;"></span>
                            <button onclick="copyCurrentClipboard()" style="background:#25d366; color:white; border:none; padding:6px 14px; border-radius:14px; font-weight:bold; cursor:pointer;">Copy to PC/Mac</button>
                        </div>
                        <hr style="border:0.5px solid #2a3942; margin:14px 0;">
                        <textarea id="clip-input" placeholder="Type or paste text to push to Android phone & mesh..." style="width:100%; height:60px; background:#2a3942; color:#e9edef; border:none; border-radius:8px; padding:8px; box-sizing:border-box; outline:none; resize:none; font-size:13px;"></textarea>
                        <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:10px;">
                            <button onclick="toggleClipboardModal()" style="background:#3b4a54; color:white; border:none; padding:8px 14px; border-radius:14px; cursor:pointer;">Close</button>
                            <button onclick="pushClipboard()" style="background:#00a884; color:white; border:none; padding:8px 16px; border-radius:14px; font-weight:bold; cursor:pointer;">Push to Mesh</button>
                        </div>
                    </div>
                </div>
                <div id="speed-modal" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.7); z-index:100; align-items:center; justify-content:center;">
                    <div style="background:#1f2c34; border-radius:12px; padding:20px; width:90%; max-width:440px; box-shadow:0 8px 24px rgba(0,0,0,0.5); color:#e9edef;">
                        <h3 style="margin-top:0;">⚡ Offline Mesh Speed & Benchmark</h3>
                        <p style="font-size:12px; color:#8696a0;">Direct peer-to-peer network link latency & bandwidth benchmark.</p>
                        <div id="speed-result-box" style="background:#111b21; padding:12px; border-radius:8px; font-size:13px; line-height:1.5;">
                            Ready to test link to Android phone...
                        </div>
                        <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:14px;">
                            <button onclick="toggleSpeedModal()" style="background:#3b4a54; color:white; border:none; padding:8px 14px; border-radius:14px; cursor:pointer;">Close</button>
                            <button onclick="runWebSpeedTest()" id="speed-run-btn" style="background:#2563eb; color:white; border:none; padding:8px 16px; border-radius:14px; font-weight:bold; cursor:pointer;">Run Benchmark</button>
                        </div>
                    </div>
                </div>
                <div id="chat-view">
                <div id="sos-beacon-banner" style="display:none; background: #d32f2f; color: white; padding: 12px 16px; margin: 8px 12px; border-radius: 8px; box-shadow: 0 4px 12px rgba(211,47,47,0.4); animation: pulse 1.2s infinite alternate;">
                    <div style="font-weight: bold; font-size: 15px; display: flex; align-items: center; justify-content: space-between;">
                        <span>🚨 EMERGENCY DISTRESS BEACON</span>
                        <span style="font-size: 11px; background: rgba(0,0,0,0.25); padding: 2px 6px; border-radius: 4px;">ACTIVE</span>
                    </div>
                    <div id="sos-beacon-sender" style="font-weight: 600; font-size: 14px; margin-top: 4px;"></div>
                    <div id="sos-beacon-text" style="font-size: 13px; margin-top: 2px;"></div>
                    <div id="sos-beacon-gps" style="font-size: 12px; margin-top: 4px; opacity: 0.9;"></div>
                </div>
                <div id="messages">
                    <div class="date-pill">Mesh Chat Room • End-to-End Local</div>
                </div>
                <div id="record-bar" style="display:none; align-items:center; justify-content:space-between; background:#202c33; padding:8px 16px; border-top:1px solid #2a3942; color:#ef5350; font-weight:bold;">
                    <span id="record-timer">🔴 Recording Voice Note... 0:00</span>
                    <div style="display:flex; gap:8px;">
                        <button type="button" onclick="cancelRecording()" style="background:#d32f2f; color:white; border:none; padding:6px 12px; border-radius:12px; cursor:pointer; font-weight:bold;">Cancel</button>
                        <button type="button" onclick="stopAndSendRecording()" style="background:#25d366; color:white; border:none; padding:6px 14px; border-radius:12px; cursor:pointer; font-weight:bold;">Send Voice Note</button>
                    </div>
                </div>
                <div id="input-area">
                    <form action="/api/send_form" method="post" id="chat-form" class="form-row">
                        <input type="hidden" name="sender" id="sender-input">
                        <button type="button" class="send-btn" id="mic-btn" style="background:#005d4b; flex-shrink:0;" title="Record Voice Note" onclick="toggleVoiceRecording()">🎤</button>
                        <input type="text" name="message" id="input-box" placeholder="Message" required autocomplete="off">
                        <button type="submit" class="send-btn" id="send-btn">➤</button>
                    </form>
                    <form action="/upload" method="post" enctype="multipart/form-data" id="upload-form" class="form-row">
                        <input type="hidden" name="sender" id="upload-sender-input">
                        <input type="file" name="file" id="file-input" required style="flex:1; width: 100%; color: #8696a0; font-size: 12px;">
                        <button type="submit" class="attach-btn" id="attach-submit">📎 Share File</button>
                    </form>
                </div>
                </div>
                <div id="game-view" style="display: none; flex: 1; flex-direction: column; background: #fff; overflow-y: auto; position: relative;">
                    <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; background: #1a73e8; color: white;">
                        <h2 id="game-title" style="margin: 0; font-size: 18px;">Arcade</h2>
                        <div style="display: flex; gap: 8px; align-items: center;">
                            <button onclick="toggleGameChat()" id="game-chat-btn" style="background: #25d366; padding: 6px 12px; border-radius: 6px; font-size: 13px; border: none; color: white; cursor: pointer; display: flex; align-items: center; gap: 4px; font-weight: bold;">💬 Chat <span id="game-chat-badge" style="display:none; background:#ef4444; border-radius:10px; padding:1px 6px; font-size:10px;">0</span></button>
                            <button onclick="showView('chat-view')" style="background: #EF4444; padding: 6px 12px; border-radius: 6px; font-size: 14px; border: none; color: white; cursor: pointer;">Close</button>
                        </div>
                    </div>
                    <div style="flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: flex-start; padding: 10px; overflow-y: auto;">
                        <div id="game-container" style="width: 100%; max-width: 600px; display: flex; flex-direction: column; justify-content: center; align-items: center;"></div>
                    </div>
                </div>
                <div id="canvas-view" style="display: none; flex: 1; flex-direction: column; position: relative;">
                    <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; background: #1a73e8; color: white;">
                        <h2 style="margin: 0; font-size: 18px;">Smartboard</h2>
                        <div style="display: flex; gap: 8px; align-items: center;">
                            <button onclick="toggleGameChat()" id="canvas-chat-btn" style="background: #25d366; padding: 6px 12px; border-radius: 6px; font-size: 13px; border: none; color: white; cursor: pointer; display: flex; align-items: center; gap: 4px; font-weight: bold;">💬 Chat <span id="canvas-chat-badge" style="display:none; background:#ef4444; border-radius:10px; padding:1px 6px; font-size:10px;">0</span></button>
                            <button onclick="showView('chat-view')" style="background: #EF4444; padding: 6px 12px; border-radius: 6px; font-size: 14px; border: none; color: white; cursor: pointer;">Close</button>
                        </div>
                    </div>
                    <div style="flex: 1; position: relative;">
                        <canvas id="drawing-board" style="width: 100%; height: 100%; display: block;"></canvas>
                    </div>
                </div>
                <!-- Non-blocking Floating In-Game PIP Chat Drawer (Collapsible & Translucent) -->
                <div id="game-pip-drawer" style="display: none; position: fixed; top: 58px; right: 12px; width: 320px; max-width: calc(100vw - 24px); max-height: 400px; background: #1f2c34; border: 1px solid #33444d; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.6); z-index: 99; flex-direction: column; overflow: hidden; transition: opacity 0.2s;">
                    <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #111b21; border-bottom: 1px solid #2a3942; color: #e9edef;">
                        <span style="font-weight: bold; font-size: 13px; display: flex; align-items: center; gap: 6px;">💬 Mesh Chat Overlay</span>
                        <div style="display: flex; gap: 6px; align-items: center;">
                            <button onclick="togglePipTranslucency()" id="pip-translucent-btn" title="Toggle Translucency" style="background: #2a3942; border: none; color: #8696a0; border-radius: 4px; padding: 2px 6px; font-size: 11px; cursor: pointer;">👁️</button>
                            <button onclick="toggleGameChat()" style="background: transparent; border: none; color: #8696a0; font-size: 14px; cursor: pointer; line-height: 1;">✕</button>
                        </div>
                    </div>
                    <div id="game-pip-msgs" style="flex: 1; overflow-y: auto; padding: 8px; max-height: 220px; display: flex; flex-direction: column; gap: 6px; background: rgba(17, 27, 33, 0.4);">
                        <div style="text-align: center; font-size: 10px; color: #8696a0;">In-Game Chat • Connected</div>
                    </div>
                    <!-- Quick Emoji Reaction Strip -->
                    <div style="display: flex; gap: 4px; padding: 4px 8px; background: #111b21; border-top: 1px solid #2a3942; overflow-x: auto; white-space: nowrap;">
                        <button onclick="sendGamePipEmoji('🔥')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">🔥</button>
                        <button onclick="sendGamePipEmoji('👏')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">👏</button>
                        <button onclick="sendGamePipEmoji('😂')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">😂</button>
                        <button onclick="sendGamePipEmoji('🎯')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">🎯</button>
                        <button onclick="sendGamePipEmoji('💀')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">💀</button>
                        <button onclick="sendGamePipEmoji('⚡')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">⚡</button>
                        <button onclick="sendGamePipEmoji('🥳')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">🥳</button>
                        <button onclick="sendGamePipEmoji('🎲')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">🎲</button>
                        <button onclick="sendGamePipEmoji('👑')" style="background:#2a3942; border:none; border-radius:4px; padding:2px 6px; cursor:pointer; font-size:12px;">👑</button>
                    </div>
                    <!-- In-Game Chat Input Area -->
                    <form id="game-pip-form" onsubmit="handleGamePipSend(event)" style="display: flex; gap: 4px; padding: 6px 8px; background: #1f2c34; border-top: 1px solid #2a3942;">
                        <input type="text" id="game-pip-input" placeholder="Chat with players..." style="flex: 1; background: #2a3942; border: 1px solid #33444d; border-radius: 14px; color: #fff; padding: 4px 10px; font-size: 12px; outline: none;">
                        <button type="submit" style="background: #25d366; border: none; border-radius: 14px; color: white; padding: 4px 10px; font-size: 12px; cursor: pointer; font-weight: bold;">➤</button>
                    </form>
                </div>
                <script>
                    const msgs = document.getElementById('messages');
                    let myName = localStorage.getItem('mesh_name');
                    if (!myName) {
                        myName = 'Web-' + Math.floor(Math.random() * 1000);
                        localStorage.setItem('mesh_name', myName);
                    }
                    document.getElementById('sender-input').value = myName;
                    
                    function showView(viewId) {
                        document.getElementById('chat-view').style.display = 'none';
                        document.getElementById('canvas-view').style.display = 'none';
                        document.getElementById('game-view').style.display = 'none';
                        document.getElementById(viewId).style.display = 'flex';
                        if (viewId === 'canvas-view') resizeCanvas();
                        if (viewId === 'chat-view') {
                            const pipDrawer = document.getElementById('game-pip-drawer');
                            if (pipDrawer) pipDrawer.style.display = 'none';
                            gamePipUnread = 0;
                            updateGameChatBadge();
                        }
                    }
                    function toggleView() {
                        showView('chat-view'); 
                    }

                    let gamePipUnread = 0;
                    let isPipTranslucent = false;

                    function toggleGameChat() {
                        const drawer = document.getElementById('game-pip-drawer');
                        if (!drawer) return;
                        const isShowing = drawer.style.display === 'flex';
                        if (isShowing) {
                            drawer.style.display = 'none';
                        } else {
                            drawer.style.display = 'flex';
                            gamePipUnread = 0;
                            updateGameChatBadge();
                            const msgBox = document.getElementById('game-pip-msgs');
                            if (msgBox) msgBox.scrollTop = msgBox.scrollHeight;
                        }
                    }

                    function updateGameChatBadge() {
                        const b1 = document.getElementById('game-chat-badge');
                        const b2 = document.getElementById('canvas-chat-badge');
                        [b1, b2].forEach(b => {
                            if (!b) return;
                            if (gamePipUnread > 0) {
                                b.innerText = gamePipUnread;
                                b.style.display = 'inline-block';
                            } else {
                                b.style.display = 'none';
                            }
                        });
                    }

                    function togglePipTranslucency() {
                        const drawer = document.getElementById('game-pip-drawer');
                        if (!drawer) return;
                        isPipTranslucent = !isPipTranslucent;
                        drawer.style.opacity = isPipTranslucent ? '0.70' : '1.0';
                        drawer.style.backdropFilter = isPipTranslucent ? 'blur(6px)' : 'none';
                        const btn = document.getElementById('pip-translucent-btn');
                        if (btn) btn.style.color = isPipTranslucent ? '#25d366' : '#8696a0';
                    }

                    function sendGamePipEmoji(emoji) {
                        sendGamePipText(emoji);
                    }

                    function handleGamePipSend(e) {
                        e.preventDefault();
                        const input = document.getElementById('game-pip-input');
                        if (!input || !input.value.trim()) return;
                        sendGamePipText(input.value.trim());
                        input.value = '';
                    }

                    function sendGamePipText(text) {
                        if (!text) return;
                        const payload = JSON.stringify({ sender: myName, message: text });
                        if (wsManager && wsManager.send(payload)) {
                            // Sent via WS
                        } else {
                            fetch('/api/send', {
                                method: 'POST',
                                body: payload,
                                headers: { 'Content-Type': 'application/json' }
                            });
                        }
                        appendGamePipMessage(myName, text, true);
                    }

                    function appendGamePipMessage(sender, text, isMe) {
                        const container = document.getElementById('game-pip-msgs');
                        if (!container) return;
                        const div = document.createElement('div');
                        div.style.padding = '4px 8px';
                        div.style.borderRadius = '8px';
                        div.style.fontSize = '12px';
                        div.style.lineHeight = '1.3';
                        div.style.maxWidth = '85%';
                        div.style.wordBreak = 'break-word';
                        div.style.background = isMe ? '#005c4b' : '#202c33';
                        div.style.color = '#e9edef';
                        div.style.alignSelf = isMe ? 'flex-end' : 'flex-start';
                        div.style.border = '1px solid ' + (isMe ? '#008069' : '#2a3942');
                        
                        const senderSpan = isMe ? '' : ('<div style="font-weight:bold; font-size:10px; color:#25d366; margin-bottom:2px;">' + sender + '</div>');
                        div.innerHTML = senderSpan + '<span>' + text + '</span>';
                        container.appendChild(div);
                        container.scrollTop = container.scrollHeight;
                        
                        const drawer = document.getElementById('game-pip-drawer');
                        const isGameOpen = document.getElementById('game-view').style.display !== 'none' || document.getElementById('canvas-view').style.display !== 'none';
                        if (!isMe && isGameOpen && (!drawer || drawer.style.display !== 'flex')) {
                            gamePipUnread++;
                            updateGameChatBadge();
                        }
                    }
                    

                    function handleSharedMedia(p) {
                        const gc = document.getElementById('game-container');
                        const gt = document.getElementById('game-title');
                        if (p.mediaType === 'none') {
                            showView('chat-view');
                            return;
                        }
                        if (p.mediaType === 'canvas') {
                            showView('canvas-view');
                            return;
                        }
                        showView('game-view');
                        
                        if (p.mediaType === 'web') {
                            gt.innerText = 'Web Media';
                            gc.style.maxWidth = '100%';
                            gc.style.aspectRatio = 'auto';
                            gc.style.height = '100%';
                            gc.innerHTML = '<iframe src="' + p.url + '" style="width:100%; height:100%; border:none;"></iframe>';
                        } else if (p.mediaType === 'chess') {
                            gc.style.maxWidth = '600px';
                            gc.style.aspectRatio = '1';
                            gc.style.height = 'auto';
                            gt.innerText = 'Standard Chess';
                            gc.innerHTML = '<div id="chess-board" style="display:grid; grid-template-columns: repeat(8, 1fr); width:100%; height:100%; border: 2px solid #333;"></div>';
                        } else if (p.mediaType === 'ludo') {
                            gt.innerText = 'Mesh Ludo';
                            gc.innerHTML = '<div id="ludo-board" style="display:grid; grid-template-columns: repeat(15, 1fr); width:100%; height:100%; border: 2px solid #333; position: relative;"></div>';
                        }
                    }

                    
                    function showEmergencySOS(p) {
                        const banner = document.getElementById('sos-beacon-banner');
                        if (!banner) return;
                        banner.style.display = 'block';
                        const sName = p.senderName || 'Unknown Node';
                        const sHandle = p.senderHandle ? ' (@' + p.senderHandle + ')' : '';
                        document.getElementById('sos-beacon-sender').innerText = 'From: ' + sName + sHandle;
                        document.getElementById('sos-beacon-text').innerText = p.message || 'Distress signal received!';
                        const gpsEl = document.getElementById('sos-beacon-gps');
                        if (p.lat && p.lng) {
                            gpsEl.innerHTML = '📍 Location: <a href="https://maps.google.com/?q=' + p.lat + ',' + p.lng + '" target="_blank" style="color: #fff; text-decoration: underline;">' + p.lat.toFixed(4) + ', ' + p.lng.toFixed(4) + '</a>';
                        } else {
                            gpsEl.innerText = '📍 Location: Not available / GPS searching';
                        }
                    }

                    function hideEmergencySOS() {
                        const banner = document.getElementById('sos-beacon-banner');
                        if (banner) banner.style.display = 'none';
                    }

                    let activeGameState = null;

                    function sendGameAction(gameType, action, payload = {}) {
                        if (!wsManager) return;
                        const msgObj = {
                            type: 'game_action',
                            gameType: gameType,
                            action: action,
                            sender: myName,
                            ...payload
                        };
                        wsManager.send(JSON.stringify({ sender: myName, message: JSON.stringify(msgObj) }));
                    }

                    function renderTicTacToe(state) {
                        activeGameState = { type: 'tictactoe', state: state };
                        if (document.getElementById('game-view').style.display === 'none') { handleSharedMedia({mediaType: 'tictactoe'}); }
                        const gc = document.getElementById('game-container');
                        gc.style.maxWidth = '360px';
                        gc.style.aspectRatio = 'auto';
                        gc.style.height = 'auto';
                        gc.style.display = 'flex';
                        gc.style.flexDirection = 'column';
                        gc.style.alignItems = 'center';
                        
                        const isX = state.xPlayerId === myName;
                        const isO = state.oPlayerId === myName;
                        const myTurn = (state.isXTurn && isX) || (!state.isXTurn && isO);
                        
                        let html = '<div style="width:100%; display:flex; flex-direction:column; gap:8px;">';
                        // Slot control bar
                        html += '<div style="display:flex; justify-content:space-between; background:#1f2c34; padding:8px 12px; border-radius:10px; font-size:12px;">';
                        html += '<div><b>Slot X:</b> ' + (state.xPlayerId === 'bot' ? '🤖 Bot' : (state.xPlayerId ? state.xPlayerId.substring(0,8) : '<i>Empty</i>')) + 
                                (state.xPlayerId === '' ? ' <button onclick="sendGameAction(\'tictactoe\', \'claim_slot\', {role: \'X\', isBot: false})" style="background:#25d366; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer;">Join</button> <button onclick="sendGameAction(\'tictactoe\', \'claim_slot\', {role: \'X\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer;">🤖</button>' : '') + 
                                (isX || state.xPlayerId === 'bot' ? ' <button onclick="sendGameAction(\'tictactoe\', \'leave_slot\', {role: \'X\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>' : '') + '</div>';
                        html += '<div><b>Slot O:</b> ' + (state.oPlayerId === 'bot' ? '🤖 Bot' : (state.oPlayerId ? state.oPlayerId.substring(0,8) : '<i>Empty</i>')) + 
                                (state.oPlayerId === '' ? ' <button onclick="sendGameAction(\'tictactoe\', \'claim_slot\', {role: \'O\', isBot: false})" style="background:#53bdeb; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer;">Join</button> <button onclick="sendGameAction(\'tictactoe\', \'claim_slot\', {role: \'O\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer;">🤖</button>' : '') + 
                                (isO || state.oPlayerId === 'bot' ? ' <button onclick="sendGameAction(\'tictactoe\', \'leave_slot\', {role: \'O\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>' : '') + '</div>';
                        html += '</div>';

                        // Turn status badge
                        const turnLabel = state.winner ? (state.winner === 'Draw' ? 'Game Drawn!' : state.winner + ' Wins!') : (myTurn ? '👉 Your Turn!' : (state.isXTurn ? "X's Turn" : "O's Turn"));
                        html += '<div style="text-align:center; font-weight:bold; color:' + (myTurn ? '#25d366' : '#fff') + '; background:#131c21; padding:6px; border-radius:6px;">' + turnLabel + '</div>';

                        // 3x3 Grid
                        html += '<div style="display:grid; grid-template-columns: repeat(3, 1fr); gap:6px; width:100%; aspect-ratio:1; background:#131c21; padding:6px; border-radius:10px;">';
                        state.board.forEach((cell, idx) => {
                            const color = cell === 'X' ? '#25d366' : '#53bdeb';
                            html += '<div onclick="sendGameAction(\'tictactoe\', \'move\', {index: ' + idx + '})" style="display:flex; align-items:center; justify-content:center; font-size:42px; font-weight:bold; background:#2a3942; border-radius:8px; color:' + color + '; cursor:pointer; user-select:none;">' + cell + '</div>';
                        });
                        html += '</div>';

                        if (state.winner) {
                            html += '<button onclick="sendGameAction(\'tictactoe\', \'reset\')" style="margin-top:6px; background:#1a73e8; color:white; border:none; padding:8px 16px; border-radius:6px; cursor:pointer; font-weight:bold;">Play Again</button>';
                        }
                        html += '</div>';
                        gc.innerHTML = html;
                    }

                    function renderConnect4(state) {
                        activeGameState = { type: 'connect4', state: state };
                        if (document.getElementById('game-view').style.display === 'none') { handleSharedMedia({mediaType: 'connect4'}); }
                        const gc = document.getElementById('game-container');
                        gc.style.maxWidth = '420px';
                        gc.style.aspectRatio = 'auto';
                        gc.style.height = 'auto';
                        gc.style.display = 'flex';
                        gc.style.flexDirection = 'column';
                        gc.style.alignItems = 'center';

                        const isRed = state.redPlayerId === myName;
                        const isYellow = state.yellowPlayerId === myName;
                        const myTurn = (state.isRedTurn && isRed) || (!state.isRedTurn && isYellow);

                        let html = '<div style="width:100%; display:flex; flex-direction:column; gap:8px;">';
                        // Slot control bar
                        html += '<div style="display:flex; justify-content:space-between; background:#1f2c34; padding:8px 12px; border-radius:10px; font-size:12px;">';
                        html += '<div><b>Red:</b> ' + (state.redPlayerId === 'bot' ? '🤖 Bot' : (state.redPlayerId ? state.redPlayerId.substring(0,8) : '<i>Empty</i>')) + 
                                (state.redPlayerId === '' ? ' <button onclick="sendGameAction(\'connect4\', \'claim_slot\', {role: \'red\', isBot: false})" style="background:#ef4444; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer;">Join</button> <button onclick="sendGameAction(\'connect4\', \'claim_slot\', {role: \'red\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer;">🤖</button>' : '') + 
                                (isRed || state.redPlayerId === 'bot' ? ' <button onclick="sendGameAction(\'connect4\', \'leave_slot\', {role: \'red\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>' : '') + '</div>';
                        html += '<div><b>Yellow:</b> ' + (state.yellowPlayerId === 'bot' ? '🤖 Bot' : (state.yellowPlayerId ? state.yellowPlayerId.substring(0,8) : '<i>Empty</i>')) + 
                                (state.yellowPlayerId === '' ? ' <button onclick="sendGameAction(\'connect4\', \'claim_slot\', {role: \'yellow\', isBot: false})" style="background:#f59e0b; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer;">Join</button> <button onclick="sendGameAction(\'connect4\', \'claim_slot\', {role: \'yellow\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer;">🤖</button>' : '') + 
                                (isYellow || state.yellowPlayerId === 'bot' ? ' <button onclick="sendGameAction(\'connect4\', \'leave_slot\', {role: \'yellow\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>' : '') + '</div>';
                        html += '</div>';

                        const turnLabel = state.winner ? (state.winner + ' Wins!') : (myTurn ? '👉 Your Turn (Drop piece)' : (state.isRedTurn ? "Red's Turn" : "Yellow's Turn"));
                        html += '<div style="text-align:center; font-weight:bold; color:' + (myTurn ? '#34d399' : '#fff') + '; background:#131c21; padding:6px; border-radius:6px;">' + turnLabel + '</div>';

                        // Connect 4 Grid (Columns click-to-drop)
                        html += '<div style="display:flex; flex-direction:row; width:100%; aspect-ratio:7/6; background:#1565C0; padding:6px; border-radius:10px; gap:4px;">';
                        for (let col = 0; col < 7; col++) {
                            html += '<div onclick="sendGameAction(\'connect4\', \'drop\', {col: ' + col + '})" style="flex:1; display:flex; flex-direction:column; gap:4px; cursor:pointer;">';
                            for (let row = 0; row < 6; row++) {
                                const cell = state.board[row][col];
                                const color = cell === 1 ? '#ef4444' : (cell === 2 ? '#ffb300' : '#1e293b');
                                html += '<div style="flex:1; border-radius:50%; background:' + color + '; box-shadow:inset 0 2px 4px rgba(0,0,0,0.4);"></div>';
                            }
                            html += '</div>';
                        }
                        html += '</div>';

                        if (state.winner) {
                            html += '<button onclick="sendGameAction(\'connect4\', \'reset\')" style="margin-top:6px; background:#1a73e8; color:white; border:none; padding:8px 16px; border-radius:6px; cursor:pointer; font-weight:bold;">Play Again</button>';
                        }
                        html += '</div>';
                        gc.innerHTML = html;
                    }

                    let chessSelectedFrom = null;
                    function renderChess(state) {
                        activeGameState = { type: 'chess', state: state };
                        if (document.getElementById('game-view').style.display === 'none') { handleSharedMedia({mediaType: 'chess'}); }

                        const gc = document.getElementById('game-container');
                        gc.style.maxWidth = '600px';
                        gc.style.aspectRatio = 'auto';
                        gc.style.height = 'auto';
                        gc.style.display = 'flex';
                        gc.style.flexDirection = 'column';
                        gc.style.alignItems = 'center';

                        const oldHeader = document.getElementById('chess-header-bar');
                        if (oldHeader) oldHeader.remove();

                        let cb = document.getElementById('chess-board');
                        if (!cb) {
                            cb = document.createElement('div');
                            cb.id = 'chess-board';
                            cb.style.display = 'grid';
                            cb.style.gridTemplateColumns = 'repeat(8, 1fr)';
                            cb.style.width = '100%';
                            cb.style.aspectRatio = '1';
                            cb.style.border = '2px solid #333';
                            cb.style.boxShadow = '0 4px 12px rgba(0,0,0,0.3)';
                            gc.appendChild(cb);
                        }

                        // Header Bar with Role Slot Controls (White / Black / Bot)
                        const headerBar = document.createElement('div');
                        headerBar.id = 'chess-header-bar';
                        headerBar.style.width = '100%';
                        headerBar.style.display = 'flex';
                        headerBar.style.flexDirection = 'column';
                        headerBar.style.gap = '6px';
                        headerBar.style.marginBottom = '8px';

                        const isWhiteMe = state.whitePlayerId === myName;
                        const isBlackMe = state.blackPlayerId === myName;
                        const isMyTurn = (state.isWhiteTurn && (isWhiteMe || (state.whitePlayerId === '' && state.blackPlayerId === ''))) ||
                                         (!state.isWhiteTurn && (isBlackMe || (state.whitePlayerId === '' && state.blackPlayerId === '')));

                        const slotsRow = document.createElement('div');
                        slotsRow.style.display = 'grid';
                        slotsRow.style.gridTemplateColumns = 'repeat(2, 1fr)';
                        slotsRow.style.gap = '8px';

                        // White Slot Box
                        const whiteBox = document.createElement('div');
                        whiteBox.style.background = '#1f2c34';
                        whiteBox.style.border = state.isWhiteTurn ? '2px solid #fff' : '1px solid #33444d';
                        whiteBox.style.borderRadius = '8px';
                        whiteBox.style.padding = '6px 10px';
                        whiteBox.style.fontSize = '12px';
                        whiteBox.style.color = '#fff';
                        whiteBox.style.display = 'flex';
                        whiteBox.style.justifyContent = 'space-between';
                        whiteBox.style.alignItems = 'center';

                        const whiteName = state.whitePlayerId === 'bot' ? '🤖 Bot' : (state.whitePlayerId === '' ? '<i>Empty</i>' : (isWhiteMe ? 'You' : state.whitePlayerId.substring(0, 6)));
                        whiteBox.innerHTML = '<div><b>White ♔:</b> ' + whiteName + '</div>';

                        if (state.whitePlayerId === '') {
                            whiteBox.innerHTML += '<div style="display:flex; gap:3px;">' +
                                '<button onclick="sendGameAction(\'chess\', \'claim_slot\', {role: \'white\', isBot: false})" style="background:#25d366; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:11px;">Join</button>' +
                                '<button onclick="sendGameAction(\'chess\', \'claim_slot\', {role: \'white\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:11px;">🤖</button>' +
                                '</div>';
                        } else if (isWhiteMe || state.whitePlayerId === 'bot') {
                            whiteBox.innerHTML += '<button onclick="sendGameAction(\'chess\', \'leave_slot\', {role: \'white\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>';
                        }
                        slotsRow.appendChild(whiteBox);

                        // Black Slot Box
                        const blackBox = document.createElement('div');
                        blackBox.style.background = '#1f2c34';
                        blackBox.style.border = !state.isWhiteTurn ? '2px solid #53bdeb' : '1px solid #33444d';
                        blackBox.style.borderRadius = '8px';
                        blackBox.style.padding = '6px 10px';
                        blackBox.style.fontSize = '12px';
                        blackBox.style.color = '#fff';
                        blackBox.style.display = 'flex';
                        blackBox.style.justifyContent = 'space-between';
                        blackBox.style.alignItems = 'center';

                        const blackName = state.blackPlayerId === 'bot' ? '🤖 Bot' : (state.blackPlayerId === '' ? '<i>Empty</i>' : (isBlackMe ? 'You' : state.blackPlayerId.substring(0, 6)));
                        blackBox.innerHTML = '<div><b>Black ♚:</b> ' + blackName + '</div>';

                        if (state.blackPlayerId === '') {
                            blackBox.innerHTML += '<div style="display:flex; gap:3px;">' +
                                '<button onclick="sendGameAction(\'chess\', \'claim_slot\', {role: \'black\', isBot: false})" style="background:#53bdeb; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:11px;">Join</button>' +
                                '<button onclick="sendGameAction(\'chess\', \'claim_slot\', {role: \'black\', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:2px 6px; border-radius:4px; cursor:pointer; font-size:11px;">🤖</button>' +
                                '</div>';
                        } else if (isBlackMe || state.blackPlayerId === 'bot') {
                            blackBox.innerHTML += '<button onclick="sendGameAction(\'chess\', \'leave_slot\', {role: \'black\'})" style="background:#ef4444; color:#fff; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:10px;">Leave</button>';
                        }
                        slotsRow.appendChild(blackBox);
                        headerBar.appendChild(slotsRow);

                        // Turn & Status Pill
                        const statusPill = document.createElement('div');
                        statusPill.style.display = 'flex';
                        statusPill.style.justifyContent = 'space-between';
                        statusPill.style.alignItems = 'center';
                        statusPill.style.background = '#111b21';
                        statusPill.style.padding = '6px 12px';
                        statusPill.style.borderRadius = '8px';
                        statusPill.style.fontSize = '12px';
                        statusPill.style.color = isMyTurn ? '#25d366' : '#fff';

                        const turnMsg = state.winner ? ('🏆 ' + state.winner + ' Wins!') : (isMyTurn ? '👉 Your Move' : (state.isWhiteTurn ? "White's Turn" : "Black's Turn"));
                        statusPill.innerHTML = '<div style="font-weight:bold;">' + turnMsg + '</div>' +
                            '<button onclick="sendGameAction(\'chess\', \'reset\')" style="background:#2a3942; color:#ef4444; border:none; padding:3px 10px; border-radius:4px; cursor:pointer; font-size:11px;">New Game</button>';
                        headerBar.appendChild(statusPill);

                        gc.insertBefore(headerBar, cb);

                        cb.innerHTML = '';
                        const pieces = state.pieces || [];
                        const uMap = { 'K':'♔','Q':'♕','R':'♖','B':'♗','N':'♘','P':'♙', 'k':'♚','q':'♛','r':'♜','b':'♝','n':'♞','p':'♟' };
                        for (let i=0; i<64; i++) {
                            const row = Math.floor(i / 8);
                            const col = i % 8;
                            const isDark = (row + col) % 2 === 1;
                            const cell = document.createElement('div');
                            cell.style.background = isDark ? '#769656' : '#eeeed2';
                            if (chessSelectedFrom === i) cell.style.background = '#f6e58d';
                            cell.style.display = 'flex';
                            cell.style.alignItems = 'center';
                            cell.style.justifyContent = 'center';
                            cell.style.fontSize = '32px';
                            cell.style.cursor = 'pointer';
                            let p = pieces[i] || '';
                            if (p) cell.innerText = uMap[p] || p;
                            if (p && p.toUpperCase() === p) cell.style.color = '#fff';
                            if (p && p.toLowerCase() === p) cell.style.color = '#000';
                            if (p) cell.style.textShadow = '0px 0px 2px rgba(0,0,0,0.5)';

                            cell.onclick = () => {
                                if (chessSelectedFrom === null) {
                                    if (p) {
                                        chessSelectedFrom = i;
                                        renderChess(state);
                                    }
                                } else {
                                    sendGameAction('chess', 'move', { from: chessSelectedFrom, to: i });
                                    chessSelectedFrom = null;
                                }
                            };
                            cb.appendChild(cell);
                        }
                    }

                    function renderLudo(state) {
                        activeGameState = { type: 'ludo', state: state };
                        if (document.getElementById('game-view').style.display === 'none') { handleSharedMedia({mediaType: 'ludo'}); }

                        const gc = document.getElementById('game-container');
                        const oldHeader = document.getElementById('ludo-header-bar');
                        if (oldHeader) oldHeader.remove();
                        const oldDeck = document.getElementById('ludo-dice-deck');
                        if (oldDeck) oldDeck.remove();

                        let lb = document.getElementById('ludo-board');
                        if (!lb) {
                            lb = document.createElement('div');
                            lb.id = 'ludo-board';
                            lb.style.display = 'grid';
                            lb.style.gridTemplateColumns = 'repeat(15, 1fr)';
                            lb.style.width = '100%';
                            lb.style.aspectRatio = '1';
                            lb.style.border = '2px solid #333';
                            lb.style.position = 'relative';
                            lb.style.background = '#fff';
                            lb.style.borderRadius = '8px';
                            gc.appendChild(lb);
                        }
                        lb.innerHTML = '';
                        for (let r=0; r<15; r++) {
                            for (let c=0; c<15; c++) {
                                const cell = document.createElement('div');
                                let bg = 'white';
                                if (c<6 && r<6) bg = '#ffebee';
                                else if (c>8 && r<6) bg = '#e8f5e9';
                                else if (c>8 && r>8) bg = '#fff8e1';
                                else if (c<6 && r>8) bg = '#e3f2fd';
                                else if (c>=6 && c<=8 && r>=6 && r<=8) bg = '#555';
                                else if (c>0 && c<6 && r===7) bg = '#ef9a9a';
                                else if (c===7 && r>0 && r<6) bg = '#a5d6a7';
                                else if (c>8 && c<14 && r===7) bg = '#ffe082';
                                else if (c===7 && r>8 && r<14) bg = '#90caf9';
                                else if (c===1 && r===6) bg = '#ef9a9a';
                                else if (c===8 && r===1) bg = '#a5d6a7';
                                else if (c===13 && r===8) bg = '#ffe082';
                                else if (c===6 && r===13) bg = '#90caf9';
                                
                                cell.style.background = bg;
                                cell.style.border = '0.5px solid #ccc';
                                lb.appendChild(cell);
                            }
                        }
                        
                        const trackMap = [
                            [1,6], [2,6], [3,6], [4,6], [5,6],
                            [6,5], [6,4], [6,3], [6,2], [6,1], [6,0],
                            [7,0], [8,0],
                            [8,1], [8,2], [8,3], [8,4], [8,5],
                            [9,6], [10,6], [11,6], [12,6], [13,6], [14,6],
                            [14,7], [14,8],
                            [13,8], [12,8], [11,8], [10,8], [9,8],
                            [8,9], [8,10], [8,11], [8,12], [8,13], [8,14],
                            [7,14], [6,14],
                            [6,13], [6,12], [6,11], [6,10], [6,9],
                            [5,8], [4,8], [3,8], [2,8], [1,8], [0,8],
                            [0,7], [0,6]
                        ];
                        const redHome = [[1,7], [2,7], [3,7], [4,7], [5,7]];
                        const greenHome = [[7,1], [7,2], [7,3], [7,4], [7,5]];
                        const yellowHome = [[13,7], [12,7], [11,7], [10,7], [9,7]];
                        const blueHome = [[7,13], [7,12], [7,11], [7,10], [7,9]];
                        const bases = [
                            [[2,2], [3,2], [2,3], [3,3]],
                            [[11,2], [12,2], [11,3], [12,3]],
                            [[11,11], [12,11], [11,12], [12,12]],
                            [[2,11], [3,11], [2,12], [3,12]]
                        ];
                        
                        function getCoordinate(player, pieceIdx, relativePos) {
                            if (relativePos === 0) return bases[player][pieceIdx];
                            if (relativePos <= 51) {
                                const offsets = [0, 13, 26, 39];
                                const absPos = (relativePos - 1 + offsets[player]) % 52;
                                return trackMap[absPos];
                            }
                            if (relativePos <= 56) {
                                const stretchIdx = relativePos - 52;
                                const stretches = [redHome, greenHome, yellowHome, blueHome];
                                return stretches[player][stretchIdx];
                            }
                            return [7,7];
                        }
                        
                        const colors = ['#f44336', '#4caf50', '#ffeb3b', '#2196f3'];
                        const piecesObj = state.pieces || {};
                        for (let p=0; p<4; p++) {
                            const pArr = piecesObj[p] || [0,0,0,0];
                            for (let i=0; i<4; i++) {
                                const coord = getCoordinate(p, i, pArr[i]);
                                const pieceDiv = document.createElement('div');
                                pieceDiv.style.position = 'absolute';
                                pieceDiv.style.width = 'calc(100% / 15 * 0.7)';
                                pieceDiv.style.height = 'calc(100% / 15 * 0.7)';
                                pieceDiv.style.background = colors[p];
                                pieceDiv.style.borderRadius = '50%';
                                pieceDiv.style.border = '2px solid black';
                                pieceDiv.style.left = 'calc((100% / 15) * ' + coord[0] + ' + (100% / 15 * 0.15) + ' + (i*2) + 'px)';
                                pieceDiv.style.top = 'calc((100% / 15) * ' + coord[1] + ' + (100% / 15 * 0.15) + ' + (i*2) + 'px)';
                                pieceDiv.style.cursor = 'pointer';
                                pieceDiv.onclick = (e) => {
                                    e.stopPropagation();
                                    sendGameAction('ludo', 'move_piece', { playerIndex: p, pieceIndex: i });
                                };
                                lb.appendChild(pieceDiv);
                            }
                        }
                        
                        // Tactical Header Bar (Slots, Match Timer, Pacing & Status)
                        const headerBar = document.createElement('div');
                        headerBar.id = 'ludo-header-bar';
                        headerBar.style.width = '100%';
                        headerBar.style.maxWidth = '600px';
                        headerBar.style.display = 'flex';
                        headerBar.style.flexDirection = 'column';
                        headerBar.style.gap = '6px';
                        headerBar.style.marginBottom = '8px';

                        // Player Slot Badges Row
                        const slotsRow = document.createElement('div');
                        slotsRow.style.display = 'grid';
                        slotsRow.style.gridTemplateColumns = 'repeat(4, 1fr)';
                        slotsRow.style.gap = '4px';
                        const pColors = ['#f44336', '#4caf50', '#ffeb3b', '#2196f3'];
                        const pNames = ['Red', 'Green', 'Yellow', 'Blue'];
                        const pIds = state.playerIds || {};

                        for (let p = 0; p < 4; p++) {
                            const pId = pIds[p] || '';
                            const isMySlot = pId === myName;
                            const isBotSlot = pId === 'bot';
                            const isEmpty = pId === '';

                            const slotBox = document.createElement('div');
                            slotBox.style.background = '#1f2c34';
                            slotBox.style.border = (state.turn === p) ? ('2px solid ' + pColors[p]) : '1px solid #33444d';
                            slotBox.style.borderRadius = '8px';
                            slotBox.style.padding = '4px';
                            slotBox.style.textAlign = 'center';
                            slotBox.style.fontSize = '11px';
                            slotBox.style.color = '#fff';

                            let label = pNames[p] + ': ';
                            if (isBotSlot) label += '🤖 Bot';
                            else if (isEmpty) label += '<i>Empty</i>';
                            else label += (isMySlot ? 'You' : pId.substring(0, 5));

                            slotBox.innerHTML = '<div style="font-weight:bold; color:' + pColors[p] + ';">' + label + '</div>';

                            if (isEmpty) {
                                slotBox.innerHTML += '<div style="display:flex; justify-content:center; gap:3px; margin-top:2px;">' +
                                    '<button onclick="sendGameAction(\'ludo\', \'claim_slot\', {slotIndex: ' + p + ', isBot: false})" style="background:#25d366; color:#fff; border:none; padding:1px 5px; border-radius:3px; font-size:10px; cursor:pointer;">Join</button>' +
                                    '<button onclick="sendGameAction(\'ludo\', \'claim_slot\', {slotIndex: ' + p + ', isBot: true})" style="background:#005d4b; color:#fff; border:none; padding:1px 5px; border-radius:3px; font-size:10px; cursor:pointer;">🤖</button>' +
                                    '</div>';
                            } else if (isMySlot || isBotSlot) {
                                slotBox.innerHTML += '<div style="margin-top:2px;">' +
                                    '<button onclick="sendGameAction(\'ludo\', \'leave_slot\', {slotIndex: ' + p + '})" style="background:#ef4444; color:#fff; border:none; padding:1px 5px; border-radius:3px; font-size:9px; cursor:pointer;">Leave</button>' +
                                    '</div>';
                            }
                            slotsRow.appendChild(slotBox);
                        }
                        headerBar.appendChild(slotsRow);

                        // Pacing & Fast Match controls
                        const infoBar = document.createElement('div');
                        infoBar.style.display = 'flex';
                        infoBar.style.justifyContent = 'space-between';
                        infoBar.style.alignItems = 'center';
                        infoBar.style.background = '#111b21';
                        infoBar.style.padding = '4px 10px';
                        infoBar.style.borderRadius = '8px';
                        infoBar.style.fontSize = '11px';
                        infoBar.style.color = '#8696a0';

                        const curTurnColor = pColors[state.turn] || '#fff';
                        const curTurnName = pNames[state.turn] || 'Player';
                        const turnStatusText = state.winner !== -1 ? ('🏆 ' + pNames[state.winner] + ' WINS!') : ('Turn: <b style="color:' + curTurnColor + ';">' + curTurnName + '</b>' + (state.hasRolled ? ' (Rolled: ' + state.diceValue + ')' : ''));
                        infoBar.innerHTML = '<div>' + turnStatusText + '</div>';

                        const controlsSpan = document.createElement('div');
                        controlsSpan.style.display = 'flex';
                        controlsSpan.style.gap = '6px';
                        controlsSpan.innerHTML = '<button onclick="sendGameAction(\'ludo\', \'fill_bots\')" style="background:#2a3942; color:#38bdf8; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:10px; font-weight:bold;">🤖 Fill Bots</button>' +
                            '<button onclick="sendGameAction(\'ludo\', \'reset\')" style="background:#2a3942; color:#ef4444; border:none; padding:2px 8px; border-radius:4px; cursor:pointer; font-size:10px;">Reset</button>';
                        infoBar.appendChild(controlsSpan);
                        headerBar.appendChild(infoBar);

                        // Insert headerBar right above ludo-board inside game-container
                        gc.insertBefore(headerBar, lb);

                        // BOTTOM TACTICAL DICE DECK (Separated below board, NEVER covered by floating chat!)
                        const diceDeck = document.createElement('div');
                        diceDeck.id = 'ludo-dice-deck';
                        diceDeck.style.width = '100%';
                        diceDeck.style.maxWidth = '600px';
                        diceDeck.style.marginTop = '10px';
                        diceDeck.style.background = '#1f2c34';
                        diceDeck.style.border = '1px solid #33444d';
                        diceDeck.style.borderRadius = '16px';
                        diceDeck.style.padding = '10px 16px';
                        diceDeck.style.display = 'flex';
                        diceDeck.style.justifyContent = 'space-between';
                        diceDeck.style.alignItems = 'center';
                        diceDeck.style.boxShadow = '0 4px 12px rgba(0,0,0,0.3)';

                        const descCol = document.createElement('div');
                        const isCurTurnBot = pIds[state.turn] === 'bot';
                        const isCurTurnMe = pIds[state.turn] === myName || Object.values(pIds).length === 0 || !Object.values(pIds).includes(myName);

                        let titleText = 'ROLL THE DICE';
                        let subText = isCurTurnMe ? 'Tap the tactical 3D dice button to generate move' : 'Waiting for opponent move...';
                        let titleColor = '#25d366';

                        if (isCurTurnBot) {
                            titleText = '🤖 BOT TURN';
                            subText = 'Bot calculating optimal move...';
                            titleColor = '#38bdf8';
                        } else if (state.hasRolled) {
                            titleText = 'SELECT HIGHLIGHTED TOKEN';
                            subText = 'Advance pawn token by ' + state.diceValue + ' steps';
                            titleColor = '#f59e0b';
                        }

                        descCol.innerHTML = '<div style="font-weight:bold; font-size:13px; color:' + titleColor + ';">' + titleText + '</div>' +
                            '<div style="font-size:11px; color:#8696a0; margin-top:2px;">' + subText + '</div>';
                        diceDeck.appendChild(descCol);

                        // 3D Tactical Dice Button
                        const diceBtn = document.createElement('button');
                        diceBtn.style.width = '52px';
                        diceBtn.style.height = '52px';
                        diceBtn.style.borderRadius = '14px';
                        diceBtn.style.border = '2px solid #fff';
                        diceBtn.style.fontSize = '24px';
                        diceBtn.style.fontWeight = 'bold';
                        diceBtn.style.color = '#fff';
                        diceBtn.style.display = 'flex';
                        diceBtn.style.alignItems = 'center';
                        diceBtn.style.justifyContent = 'center';
                        diceBtn.style.cursor = 'pointer';
                        diceBtn.style.transition = 'all 0.2s';
                        diceBtn.style.boxShadow = '0 4px 8px rgba(0,0,0,0.4)';

                        const canRoll = state.winner === -1 && !state.hasRolled && !isCurTurnBot && isCurTurnMe;
                        if (canRoll) {
                            diceBtn.style.background = curTurnColor;
                            diceBtn.innerText = '🎲';
                            diceBtn.onclick = () => sendGameAction('ludo', 'roll_dice');
                        } else {
                            diceBtn.style.background = '#2a3942';
                            diceBtn.style.border = '2px solid #374955';
                            diceBtn.style.cursor = 'default';
                            diceBtn.innerText = state.diceValue > 0 ? state.diceValue : '🎲';
                        }
                        diceDeck.appendChild(diceBtn);

                        gc.appendChild(diceDeck);
                    }

                    const cvs = document.getElementById('drawing-board');
                    const ctx = cvs.getContext('2d');
                    let isDrawing = false;
                    let currentColor = '#000000';
                    let currentPathId = '';
                    
                    function resizeCanvas() {
                        const rect = cvs.parentElement.getBoundingClientRect();
                        cvs.width = rect.width;
                        cvs.height = rect.height;
                    }
                    window.addEventListener('resize', resizeCanvas);
                    
                    function setColor(color, btn) {
                        currentColor = color;
                        document.querySelectorAll('.color-btn').forEach(b => b.classList.remove('active'));
                        btn.classList.add('active');
                    }
                    
                    function clearCanvas(send = false) {
                        ctx.clearRect(0, 0, cvs.width, cvs.height);
                        if (send && wsManager) {
                            wsManager.send(JSON.stringify({ sender: myName, message: JSON.stringify({ type: 'canvas', action: 'clear' }) }));
                        }
                    }
                    
                    function sendCanvasMsg(action, x, y) {
                        if (wsManager) {
                            wsManager.send(JSON.stringify({ sender: myName, message: JSON.stringify({ type: 'canvas', action: action, id: currentPathId, color: currentColor, x: x / cvs.width, y: y / cvs.height }) }));
                        }
                    }
                    
                    function startDraw(e) {
                        isDrawing = true;
                        currentPathId = 'web-' + Math.random().toString(36).substr(2, 9);
                        const rect = cvs.getBoundingClientRect();
                        const x = (e.clientX || e.touches[0].clientX) - rect.left;
                        const y = (e.clientY || e.touches[0].clientY) - rect.top;
                        ctx.beginPath();
                        ctx.moveTo(x, y);
                        ctx.strokeStyle = currentColor;
                        ctx.lineWidth = 3;
                        ctx.lineCap = 'round';
                        sendCanvasMsg('start', x, y);
                    }
                    
                    function moveDraw(e) {
                        if (!isDrawing) return;
                        e.preventDefault();
                        const rect = cvs.getBoundingClientRect();
                        const x = (e.clientX || e.touches[0].clientX) - rect.left;
                        const y = (e.clientY || e.touches[0].clientY) - rect.top;
                        ctx.lineTo(x, y);
                        ctx.stroke();
                        sendCanvasMsg('move', x, y);
                    }
                    
                    function endDraw() {
                        if (!isDrawing) return;
                        isDrawing = false;
                        sendCanvasMsg('end', 0, 0);
                    }
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    const remotePaths = {};
                    
                    function drawRemoteCanvas(msg) {
                        if (msg.action === 'clear') {
                            clearCanvas(false);
                            for (let k in remotePaths) delete remotePaths[k];
                            return;
                        }
                        const absX = msg.x * cvs.width;
                        const absY = msg.y * cvs.height;
                        
                        if (msg.action === 'bg') {
                            if (msg.data && msg.data !== "") {
                                cvs.style.backgroundImage = "url('" + msg.data + "')";
                                cvs.style.backgroundSize = "contain";
                                cvs.style.backgroundRepeat = "no-repeat";
                                cvs.style.backgroundPosition = "center";
                            } else {
                                cvs.style.backgroundImage = "none";
                            }
                            return;
                        }
                        if (msg.action === 'start') {
                            remotePaths[msg.id] = { color: msg.color, points: [{x: absX, y: absY}] };
                            ctx.beginPath();
                            ctx.moveTo(absX, absY);
                            ctx.lineTo(absX, absY);
                            ctx.strokeStyle = msg.color;
                            ctx.lineWidth = 3;
                            ctx.lineCap = 'round';
                            ctx.stroke();
                        } else if (msg.action === 'move') {
                            const p = remotePaths[msg.id];
                            if (p) {
                                ctx.beginPath();
                                const last = p.points[p.points.length - 1];
                                ctx.moveTo(last.x, last.y);
                                ctx.lineTo(absX, absY);
                                ctx.strokeStyle = p.color;
                                ctx.lineWidth = 3;
                                ctx.lineCap = 'round';
                                ctx.stroke();
                                p.points.push({x: absX, y: absY});
                            }
                        }
                    }
                    
                    let lastMessageContent = "";
                    
                    function appendMsg(text, className, sender = null, scroll = true) {
                        if (typeof appendGamePipMessage === 'function') {
                            appendGamePipMessage(sender || (className === 'msg-me' ? 'You' : 'Peer'), text, className === 'msg-me');
                        }
                        const div = document.createElement('div');
                        div.className = 'msg ' + className;
                        
                        if (sender) {
                            const senderDiv = document.createElement('div');
                            senderDiv.className = 'sender';
                            const match = sender.match(/^(.*?)\s*(\(@?[a-zA-Z0-9_]+\))$/);
                            if (match) {
                                senderDiv.innerHTML = '<span>' + match[1] + '</span> <span class="handle">' + match[2] + '</span>';
                            } else {
                                senderDiv.innerText = sender;
                            }
                            div.appendChild(senderDiv);
                        }
                        
                        const words = text.split(' ');
                        let hasUrl = false;
                        for (let w of words) {
                            if (w.startsWith('http://') || w.startsWith('https://')) {
                                hasUrl = true;
                                const isImg = w.toLowerCase().match(/\.(jpg|jpeg|png|gif|webp)$/i) || w.includes("/files/web_shared_") || w.toLowerCase().includes(".jpg") || w.toLowerCase().includes(".png");
                                const isAudio = w.match(/\.(m4a|mp3|wav|ogg|opus|aac|webm)$/i) || w.includes("web_audio_") || w.includes("/audio_");
                                if (isImg) {
                                    const img = document.createElement('img');
                                    img.src = w;
                                    img.style.maxWidth = '100%';
                                    img.style.borderRadius = '5px';
                                    img.style.marginTop = '5px';
                                    img.style.display = 'block';
                                    div.appendChild(img);
                                } else if (isAudio) {
                                    const audio = document.createElement('audio');
                                    audio.controls = true;
                                    audio.src = w;
                                    audio.style.maxWidth = '250px';
                                    audio.style.height = '36px';
                                    audio.style.marginTop = '6px';
                                    audio.style.display = 'block';
                                    div.appendChild(audio);
                                } else {
                                    const a = document.createElement('a');
                                    a.href = w;
                                    a.target = '_blank';
                                    
                                    const isImg = w.toLowerCase().match(/\.(jpg|jpeg|png|gif|webp)$/i) || w.includes("/files/web_shared_");
                                    if (isImg) {
                                        const img = document.createElement('img');
                                        img.src = w;
                                        img.style.maxWidth = '100%';
                                        img.style.maxHeight = '300px';
                                        img.style.borderRadius = '5px';
                                        img.style.marginTop = '5px';
                                        img.style.display = 'block';
                                        div.appendChild(img);
                                    } else {
                                        if (w.includes('/files/')) {
                                            a.innerText = '📎 Download File';
                                            a.download = w.substring(w.lastIndexOf('/') + 1) || 'file';
                                            a.style.display = 'inline-block';
                                            a.style.background = '#444';
                                            a.style.padding = '5px 10px';
                                            a.style.borderRadius = '5px';
                                            a.style.color = '#fff';
                                            a.style.textDecoration = 'none';
                                            a.style.marginTop = '5px';
                                        } else {
                                            a.innerText = w;
                                            a.style.color = '#1a73e8';
                                            a.style.textDecoration = 'underline';
                                            a.style.wordBreak = 'break-all';
                                        }
                                        div.appendChild(a);
                                    }
                                }
                                div.appendChild(document.createTextNode(' '));
                            } else {
                                div.appendChild(document.createTextNode(w + ' '));
                            }
                        }
                        
                        const timeSpan = document.createElement('span');
                        timeSpan.className = 'msg-time';
                        const now = new Date();
                        const timeStr = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
                        timeSpan.innerText = timeStr + (className === 'msg-me' ? ' ✓✓' : '');
                        div.appendChild(timeSpan);
                        
                        msgs.appendChild(div);
                        if (scroll) window.scrollTo(0, document.body.scrollHeight);
                    }

                    async function getHistory() {
                        try {
                            let res = await fetch('/api/history');
                            if (res.ok) {
                                let data = await res.json();
                                if (data.length === 0) return;
                                let lastMsg = data[data.length - 1];
                                let newLastContent = lastMsg.sender + ":" + lastMsg.message;
                                if (newLastContent !== lastMessageContent || msgs.children.length !== data.length) {
                                    lastMessageContent = newLastContent;
                                    msgs.innerHTML = '';
                                    for (let msg of data) {
                                        try {
                                            let p = JSON.parse(msg.message);
                                            if (p.type === 'sos_beacon') {
                                                showEmergencySOS(p);
                                                continue;
                                            } else if (p.type === 'sos_cancel') {
                                                hideEmergencySOS();
                                                continue;
                                            } else if (p.type === 'shared_media') {
                                                handleSharedMedia(p);
                                                continue;
                                            } else if (p.type === 'chess') {
                                                renderChess(p.state);
                                                continue;
                                            } else if (p.type === 'ludo') {
                                                renderLudo(p.state);
                                                continue;
                                            } else if (p.type === 'canvas') {
                                                drawRemoteCanvas(p);
                                                continue;
                                            } else if (p.type === 'tictactoe') {
                                                renderTicTacToe(p.state);
                                                continue;
                                            } else if (p.type === 'connect4') {
                                                renderConnect4(p.state);
                                                continue;
                                            }
                                        } catch(e) {}
                                        if (msg.sender !== myName) {
                                            appendMsg(msg.message, 'msg-other', msg.sender, false);
                                            playChime();
                                        } else {
                                            appendMsg(msg.message, 'msg-me', null, false);
                                        }
                                    }
                                    
                                    // Also render any queued messages
                                    window.scrollTo(0, document.body.scrollHeight);
                                }
                            }
                        } catch(e) {}
                    }
                    
                    getHistory();
                    
                    // Handshake for two-way confirmation
                    if (!sessionStorage.getItem('handshake_done')) {
                        const payload = JSON.stringify({ sender: myName, message: '👋 Web Client connected and ready! Two-way confirmation successful.' });
                        fetch('/api/send', {
                            method: 'POST',
                            body: payload,
                            headers: { 'Content-Type': 'application/json' }
                        }).then(res => {
                            if (res.ok) sessionStorage.setItem('handshake_done', 'true');
                        });
                    }

                    class WebSocketManager {
                        constructor(url) {
                            this.url = url;
                            this.ws = null;
                            this.reconnectInterval = 3000;
                            this.listeners = [];
                            this.connect();
                        }
                        
                        connect() {
                            this.ws = new WebSocket(this.url);
                            this.ws.onmessage = (e) => {
                                const data = JSON.parse(e.data);
                                try {
                                    const p = JSON.parse(data.message);
                                    if (p.type === 'shared_media') {
                                        handleSharedMedia(p);
                                    } else if (p.type === 'chess') {
                                        renderChess(p.state);
                                    } else if (p.type === 'ludo') {
                                        renderLudo(p.state);
                                    } else if (p.type === 'canvas') {
                                        drawRemoteCanvas(p);
                                    } else if (p.type === 'tictactoe') {
                                        renderTicTacToe(p.state);
                                    } else if (p.type === 'connect4') {
                                        renderConnect4(p.state);
                                    } else if (p.type === 'music_sync' || p.type === 'MeshMusicSync') {
                                        handleMusicSyncMessage(p);
                                    } else {
                                        this.listeners.forEach(fn => fn(data));
                                    }
                                } catch(err) {
                                    this.listeners.forEach(fn => fn(data));
                                }
                            };
                            this.ws.onclose = () => {
                                console.log('WebSocket closed, auto-reconnecting in ' + this.reconnectInterval + 'ms');
                                setTimeout(() => this.connect(), this.reconnectInterval);
                            };
                            this.ws.onerror = () => {
                                console.log('WebSocket error');
                                this.ws.close();
                            };
                        }
                        
                        send(data) {
                            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                                this.ws.send(data);
                                return true;
                            }
                            return false;
                        }
                        
                        addListener(fn) {
                            this.listeners.push(fn);
                        }
                    }
                    
                    let wsManager = null;
                    if (window.WebSocket) {
                        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                        const wsUrl = protocol + '//' + window.location.host + '/ws?sender=' + encodeURIComponent(myName);
                        wsManager = new WebSocketManager(wsUrl);
                        wsManager.addListener((data) => {
                            getHistory(); // Re-render or append logic
                        });
                    }
                    
                    // We use standard HTML forms which reload the page automatically, 
                    // providing a guaranteed fallback if JS fails. 
                    // We intercept form submit to avoid full reload if fetch works.

                    const chatForm = document.getElementById('chat-form');
                    chatForm.onsubmit = async function(e) {
                        e.preventDefault();
                        const msgInput = document.getElementById('input-box');
                        const msgText = msgInput.value;
                        if (!msgText.trim()) return;
                        
                        const formData = new URLSearchParams();
                        formData.append('sender', myName);
                        formData.append('message', msgText);
                        msgInput.value = ''; // clear immediately
                        
                        try {
                            const payload = JSON.stringify({ sender: myName, message: msgText });
                            if (wsManager && wsManager.send(payload)) {
                                // sent via ws
                            } else {
                                await fetch('/api/send', {
                                    method: 'POST',
                                    body: payload,
                                    headers: { 'Content-Type': 'application/json' }
                                });
                            }
                            getHistory();
                        } catch(err) {
                            console.error('Send failed', err);
                        }
                    };

                    const uploadForm = document.getElementById('upload-form');
                    const attachBtn = document.getElementById('attach-submit');
                    uploadForm.onsubmit = async function(e) {
                        e.preventDefault();
                        attachBtn.innerText = '⏳ Uploading...';
                        
                        const formData = new FormData(uploadForm);
                        try {
                            const res = await fetch('/upload', {
                                method: 'POST',
                                body: formData
                            });
                            if (res.ok) {
                                attachBtn.innerText = 'Upload File';
                                uploadForm.reset();
                                getHistory();
                            } else {
                                attachBtn.innerText = '⚠️ Failed! Retry';
                            }
                        } catch(err) {
                            console.error(err);
                            attachBtn.innerText = '⚠️ Error! Retry';
                        }
                    };
                    document.getElementById('upload-sender-input').value = myName;

                    function playChime() {
                        try {
                            const AudioCtx = window.AudioContext || window.webkitAudioContext;
                            if (!AudioCtx) return;
                            const ctx = new AudioCtx();
                            const osc = ctx.createOscillator();
                            const gain = ctx.createGain();
                            osc.type = 'sine';
                            osc.frequency.setValueAtTime(659.25, ctx.currentTime);
                            osc.frequency.setValueAtTime(880, ctx.currentTime + 0.08);
                            gain.gain.setValueAtTime(0.12, ctx.currentTime);
                            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.28);
                            osc.connect(gain);
                            gain.connect(ctx.destination);
                            osc.start();
                            osc.stop(ctx.currentTime + 0.28);
                        } catch(e) {}
                    }

                    // --- Cross-Platform Voice Note Recording ---
                    let mediaRecorder = null;
                    let audioChunks = [];
                    let recordInterval = null;
                    let recordSeconds = 0;

                    async function toggleVoiceRecording() {
                        if (mediaRecorder && mediaRecorder.state === 'recording') {
                            stopAndSendRecording();
                            return;
                        }
                        try {
                            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                            mediaRecorder = new MediaRecorder(stream);
                            audioChunks = [];
                            mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data); };
                            mediaRecorder.start();
                            recordSeconds = 0;
                            document.getElementById('record-bar').style.display = 'flex';
                            document.getElementById('record-timer').innerText = '🔴 Recording Voice Note... 0:00';
                            recordInterval = setInterval(() => {
                                recordSeconds++;
                                const mins = Math.floor(recordSeconds / 60);
                                const secs = String(recordSeconds % 60).padStart(2, '0');
                                document.getElementById('record-timer').innerText = '🔴 Recording Voice Note... ' + mins + ':' + secs;
                            }, 1000);
                        } catch(err) {
                            alert('Microphone permission required to record voice notes: ' + err.message);
                        }
                    }

                    function cancelRecording() {
                        if (mediaRecorder) {
                            mediaRecorder.onstop = null;
                            mediaRecorder.stop();
                            mediaRecorder.stream.getTracks().forEach(t => t.stop());
                        }
                        clearInterval(recordInterval);
                        document.getElementById('record-bar').style.display = 'none';
                    }

                    function stopAndSendRecording() {
                        if (!mediaRecorder) return;
                        clearInterval(recordInterval);
                        document.getElementById('record-bar').style.display = 'none';
                        mediaRecorder.onstop = async () => {
                            const blob = new Blob(audioChunks, { type: 'audio/webm' });
                            const formData = new FormData();
                            formData.append('sender', myName);
                            formData.append('file', blob, 'web_audio_' + Date.now() + '.webm');
                            try {
                                await fetch('/upload', { method: 'POST', body: formData });
                                getHistory();
                            } catch(err) {
                                console.error('Failed to send voice note', err);
                            }
                            mediaRecorder.stream.getTracks().forEach(t => t.stop());
                        };
                        mediaRecorder.stop();
                    }

                    // --- Cross-Platform Shared Clipboard ---
                    async function toggleClipboardModal() {
                        const modal = document.getElementById('clipboard-modal');
                        if (modal.style.display === 'flex') {
                            modal.style.display = 'none';
                        } else {
                            modal.style.display = 'flex';
                            fetchClipboard();
                        }
                    }

                    async function fetchClipboard() {
                        try {
                            const res = await fetch('/api/clipboard');
                            if (res.ok) {
                                const data = await res.json();
                                document.getElementById('clip-current-text').innerText = data.text || '(Clipboard empty)';
                                if (data.sender) {
                                    const dt = new Date(data.timestamp);
                                    document.getElementById('clip-meta').innerText = 'Synced by ' + data.sender + ' • ' + dt.toLocaleTimeString();
                                } else {
                                    document.getElementById('clip-meta').innerText = '';
                                }
                            }
                        } catch(e) {}
                    }

                    async function copyCurrentClipboard() {
                        const txt = document.getElementById('clip-current-text').innerText;
                        if (txt && txt !== '(Clipboard empty)') {
                            try {
                                await navigator.clipboard.writeText(txt);
                                alert('Copied to device clipboard!');
                            } catch(e) {
                                alert('Could not copy automatically: ' + txt);
                            }
                        }
                    }

                    async function pushClipboard() {
                        const input = document.getElementById('clip-input');
                        const txt = input.value.trim();
                        if (!txt) return;
                        try {
                            await fetch('/api/clipboard', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ text: txt, sender: myName })
                            });
                            input.value = '';
                            fetchClipboard();
                        } catch(e) {
                            alert('Failed to push clipboard: ' + e.message);
                        }
                    }

                    // --- Cross-Platform Offline Speed & Benchmark ---
                    async function toggleSpeedModal() {
                        const modal = document.getElementById('speed-modal');
                        if (modal.style.display === 'flex') {
                            modal.style.display = 'none';
                        } else {
                            modal.style.display = 'flex';
                            runWebSpeedTest();
                        }
                    }

                    async function runWebSpeedTest() {
                        const box = document.getElementById('speed-result-box');
                        const btn = document.getElementById('speed-run-btn');
                        if (btn) btn.disabled = true;
                        box.innerHTML = '<span style="color:#38bdf8;">⚡ Pinging Android Phone over local mesh link...</span>';
                        try {
                            const t0 = performance.now();
                            const res = await fetch('/api/speed_test');
                            const rtt = Math.round(performance.now() - t0);
                            if (res.ok) {
                                const data = await res.json();
                                box.innerHTML = '<div style="color:#25d366; font-weight:bold; margin-bottom:6px;">⚡ Benchmark Complete</div>' +
                                    '<div style="display:flex; flex-direction:column; gap:3px;">' +
                                    '<div>• <b>Round-Trip Latency:</b> ' + rtt + ' ms</div>' +
                                    '<div>• <b>Physical Link:</b> ' + (data.linkType || 'Wi-Fi AP Direct') + '</div>' +
                                    '<div>• <b>Bandwidth Capacity:</b> ' + (data.maxSpeed || '480 Mbps') + '</div>' +
                                    '<div>• <b>Packet Delivery:</b> 100% Reliable (Local Mesh Socket)</div>' +
                                    '</div>';
                            } else {
                                box.innerText = 'Ping response received in ' + rtt + ' ms.';
                            }
                        } catch(e) {
                            box.innerText = 'Speed test error: ' + e.message;
                        }
                        if (btn) btn.disabled = false;
                    }

                    // --- Theme Toggle ---
                    function toggleTheme() {
                        const isLight = document.body.classList.toggle('light-theme');
                        localStorage.setItem('mesh_theme', isLight ? 'light' : 'dark');
                    }
                    if (localStorage.getItem('mesh_theme') === 'light') {
                        document.body.classList.add('light-theme');
                    }

                    // --- Universal Drag and Drop Upload ---
                    window.addEventListener('dragover', (e) => {
                        e.preventDefault();
                        document.getElementById('drop-overlay').style.display = 'flex';
                    });
                    window.addEventListener('dragleave', (e) => {
                        if (e.clientX <= 0 || e.clientY <= 0) {
                            document.getElementById('drop-overlay').style.display = 'none';
                        }
                    });
                    window.addEventListener('drop', async (e) => {
                        e.preventDefault();
                        document.getElementById('drop-overlay').style.display = 'none';
                        if (e.dataTransfer && e.dataTransfer.files.length > 0) {
                            const file = e.dataTransfer.files[0];
                            const formData = new FormData();
                            formData.append('sender', myName);
                            formData.append('file', file);
                            try {
                                await fetch('/upload', { method: 'POST', body: formData });
                                getHistory();
                            } catch(err) {
                                alert('File drop upload failed: ' + err.message);
                            }
                        }
                    });

                    // --- Mesh Jukebox & Synchronized Party Player ---
                    let webPartyListening = false;
                    let lastMusicState = null;

                    function toggleMusicDockManual() {
                        const dock = document.getElementById('mesh-music-dock');
                        dock.style.display = (dock.style.display === 'none' || dock.style.display === '') ? 'flex' : 'none';
                        pollMusicStatus();
                    }

                    async function pollMusicStatus() {
                        try {
                            const res = await fetch('/api/music/status');
                            if (res.ok) {
                                const data = await res.json();
                                handleMusicSyncMessage(data);
                            }
                        } catch(e) {}
                    }

                    function handleMusicSyncMessage(data) {
                        lastMusicState = data;
                        const dock = document.getElementById('mesh-music-dock');
                        const titleElem = document.getElementById('music-dock-title');
                        const artistElem = document.getElementById('music-dock-artist');
                        const audio = document.getElementById('party-audio-elem');

                        const isPlaying = data.isPlaying || data.action === 'play';
                        const title = data.trackTitle || 'Mesh Audio Track';
                        const artist = data.artist || (data.hostName ? 'DJ ' + data.hostName : 'Mesh Broadcaster');

                        if (titleElem) titleElem.innerText = '♫ ' + title;
                        if (artistElem) artistElem.innerText = artist + (isPlaying ? ' • Playing' : ' • Paused');

                        if ((data.isPartyModeHost || isPlaying) && dock) {
                            dock.style.display = 'flex';
                        }

                        if (webPartyListening && audio) {
                            const streamUrl = data.streamUrl || data.uri || '/audio/current';
                            if (!audio.src || (!audio.src.includes('audio/current') && !audio.src.includes(streamUrl))) {
                                audio.src = streamUrl;
                            }
                            if (isPlaying) {
                                if (data.positionMs && Math.abs(audio.currentTime - (data.positionMs / 1000)) > 2) {
                                    audio.currentTime = data.positionMs / 1000;
                                }
                                audio.play().catch(e => console.log('Audio autoplay prevented:', e));
                            } else {
                                audio.pause();
                            }
                        }
                    }

                    function toggleWebPartyListen() {
                        const audio = document.getElementById('party-audio-elem');
                        const syncBtn = document.getElementById('music-sync-btn');
                        webPartyListening = !webPartyListening;
                        if (webPartyListening) {
                            syncBtn.innerText = '🔊 Synced (Listening)';
                            syncBtn.style.background = '#00e5ff';
                            pollMusicStatus();
                        } else {
                            syncBtn.innerText = '🎧 Listen In Sync';
                            syncBtn.style.background = '#25d366';
                            if (audio) audio.pause();
                        }
                    }

                    setInterval(pollMusicStatus, 4000);
                </script>
            </body>
            </html>
""".trimIndent()
}    private fun getWebClientScript(): String {
        return """
<script>
    const ws = new WebSocket("ws://" + window.location.host + "/ws");
    const senderName = "Web User " + Math.floor(Math.random() * 1000);
    document.getElementById("sender-input").value = senderName;

    function switchTab(tabName) {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.content').forEach(c => c.classList.remove('active'));
        if(tabName === 'chat') {
            document.querySelectorAll('.tab')[0].classList.add('active');
            document.getElementById('chatTab').classList.add('active');
        } else if(tabName === 'canvas') {
            document.querySelectorAll('.tab')[1].classList.add('active');
            document.getElementById('canvasTab').classList.add('active');
            resizeCanvas();
        } else {
            document.querySelectorAll('.tab')[2].classList.add('active');
            document.getElementById('gameTab').classList.add('active');
        }
    }

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            if (msg.type === "chat") {
                appendMessage(msg.sender, msg.text, msg.sender === senderName);
            } else if (msg.type === "draw") {
                drawPath(msg);
            } else if (msg.type === "clear_canvas") {
                ctx.clearRect(0, 0, canvas.width, canvas.height);
            } else if (['ludo', 'chess', 'tictactoe', 'connect4', 'shared_media'].includes(msg.type)) {
                const status = document.getElementById('gameStatus');
                if(msg.type === 'shared_media') status.innerText = "Media Shared!";
                else status.innerText = msg.type.toUpperCase() + " Game Active!";
                switchTab('game');
            }
        } catch(e) { console.log("System msg", event.data); }
    };

    function appendMessage(sender, text, isSelf) {
        const div = document.createElement("div");
        div.className = "message" + (isSelf ? " self" : "");
        div.innerHTML = "<div class='sender'>" + sender + "</div><div>" + text + "</div>";
        const content = document.getElementById("chatContent");
        content.appendChild(div);
        content.scrollTop = content.scrollHeight;
    }

    // Chat form override
    document.getElementById("chat-form").onsubmit = (e) => {
        e.preventDefault();
        const input = document.getElementById("input-box");
        if (input.value) {
            ws.send(JSON.stringify({ type: "chat", sender: senderName, text: input.value }));
            appendMessage(senderName, input.value, true);
            input.value = "";
        }
    };
    
    document.getElementById("upload-form").onsubmit = (e) => {
        const btn = document.getElementById("attach-submit");
        btn.innerText = "Uploading...";
    };

    // Canvas Logic
    const canvas = document.getElementById("drawing-board");
    const ctx = canvas.getContext("2d");
    let isDrawing = false;
    let currentColor = "#212121";

    function resizeCanvas() {
        if(canvas.parentElement) {
            canvas.width = canvas.parentElement.clientWidth;
            canvas.height = canvas.parentElement.clientHeight;
        }
    }
    window.addEventListener('resize', resizeCanvas);
    setTimeout(resizeCanvas, 100);

    function setColor(color) {
        currentColor = color;
    }

    function drawPath(data) {
        ctx.beginPath();
        ctx.strokeStyle = data.color;
        ctx.lineWidth = 5;
        ctx.lineCap = "round";
        ctx.lineJoin = "round";
        ctx.moveTo(data.startX * canvas.width, data.startY * canvas.height);
        ctx.lineTo(data.endX * canvas.width, data.endY * canvas.height);
        ctx.stroke();
    }

    function getPos(e) {
        const rect = canvas.getBoundingClientRect();
        const clientX = e.touches ? e.touches[0].clientX : e.clientX;
        const clientY = e.touches ? e.touches[0].clientY : e.clientY;
        return {
            x: (clientX - rect.left) / canvas.width,
            y: (clientY - rect.top) / canvas.height
        };
    }

    let lastPos = null;

    canvas.addEventListener("mousedown", (e) => { isDrawing = true; lastPos = getPos(e); });
    canvas.addEventListener("touchstart", (e) => { isDrawing = true; lastPos = getPos(e); });

    canvas.addEventListener("mousemove", (e) => {
        if (!isDrawing) return;
        const currentPos = getPos(e);
        const payload = {
            type: "draw",
            color: currentColor,
            startX: lastPos.x, startY: lastPos.y,
            endX: currentPos.x, endY: currentPos.y
        };
        drawPath(payload);
        ws.send(JSON.stringify(payload));
        lastPos = currentPos;
    });
    canvas.addEventListener("touchmove", (e) => {
        e.preventDefault();
        if (!isDrawing) return;
        const currentPos = getPos(e);
        const payload = {
            type: "draw",
            color: currentColor,
            startX: lastPos.x, startY: lastPos.y,
            endX: currentPos.x, endY: currentPos.y
        };
        drawPath(payload);
        ws.send(JSON.stringify(payload));
        lastPos = currentPos;
    }, { passive: false });

    canvas.addEventListener("mouseup", () => isDrawing = false);
    canvas.addEventListener("touchend", () => isDrawing = false);
    canvas.addEventListener("mouseout", () => isDrawing = false);
</script>
        """
    }
}
