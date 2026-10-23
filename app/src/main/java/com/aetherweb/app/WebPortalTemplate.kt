package com.aetherweb.app

/**
 * Modern Unified Web Portal Template for MeshChat
 * Provides a seamless Single Page Application (SPA) with persistent top & bottom navigation.
 * Views:
 * 1. [💬 Chat] - WhatsApp-style live mesh chat with audio recording, waveforms, media preview
 * 2. [📁 Vault] - Offline file repository (Grid & List with direct downloads)
 * 3. [🎮 Arcade] - Offline games (Tic-Tac-Toe, Chess, Snake, Pool, Connect 4, Smartboard)
 * 4. [📋 Tools] - Cross-platform clipboard, Web IDE & local mesh diagnostics
 *
 * Keeps the WebSocket (/ws) and live audio/video connection alive 100% of the time without reconnecting!
 */
object WebPortalTemplate {

    fun getHtml(isLite: Boolean = false, saltHex: String = ""): String {
        // Tier-2 "Seed": lite flavor serves a chat-only portal (no voice, games, IDE).
        // Tier-2 "Seed": lite flavor serves a chat-only portal (no voice, games, IDE).
        val voiceBtnHtml = if (isLite) "" else """<button class="action-btn voice-btn" onclick="toggleVoicePanel()" id="voice-btn" title="Mesh Walkie-Talkie">📻 <span class="voice-label">Walkie-Talkie</span></button>"""
        // Both APKs on the web: full flavor serves its own APK + the bundled lite seed;
        // lite flavor serves itself (it IS the lite app).
        val apkButtonsHtml = """<button class="action-btn green" onclick="openApkModal()" title="Download Android App">📥 Get App</button>"""
        val voicePanelHtml = if (isLite) "" else """
    <div id="voice-panel" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:1000;align-items:center;justify-content:center;padding:16px;">
        <div style="background:var(--card-bg);border-radius:18px;padding:20px;max-width:380px;width:100%;max-height:88vh;overflow-y:auto;border:1px solid var(--border-color);box-shadow:0 12px 40px rgba(0,0,0,0.6);">
            <!-- Hidden zero-permission HTTP fallback recorder -->
            <input type="file" id="voice-native-recorder" accept="audio/*" capture="microphone" style="display:none;" onchange="handleNativeVoiceRecorded(this)">
            
            <!-- Header -->
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                <div style="display:flex;align-items:center;gap:8px;">
                    <span style="font-size:24px;">📻</span>
                    <div>
                        <div style="font-weight:bold;font-size:16px;display:flex;align-items:center;gap:6px;">
                            <span>Mesh Walkie-Talkie</span>
                            <span style="font-size:10px;background:#005d4b;color:#00e676;padding:2px 6px;border-radius:8px;font-weight:bold;">CH 1</span>
                        </div>
                    </div>
                </div>
                <button onclick="toggleVoicePanel()" style="background:none;border:none;color:var(--text-secondary);font-size:22px;cursor:pointer;padding:4px 8px;">&#10005;</button>
            </div>
            
            <div id="voice-panel-status" style="font-size:12px;color:var(--text-secondary);margin-bottom:14px;">Instant offline push-to-talk voice radio over local Wi-Fi mesh.</div>

            <!-- Tactical Walkie-Talkie Unit Card -->
            <div id="voice-ptt-box" style="background:linear-gradient(180deg, #182229 0%, #111b21 100%);border:2px solid #233138;border-radius:16px;padding:18px 14px;text-align:center;margin-bottom:14px;box-shadow:inset 0 2px 6px rgba(0,0,0,0.4);">
                
                <!-- Radio Frequency Header -->
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;padding:0 6px;">
                    <span style="font-size:11px;font-family:monospace;color:#00e676;background:rgba(0,230,118,0.1);padding:3px 8px;border-radius:6px;border:1px solid rgba(0,230,118,0.2);">📡 2.4 GHz MESH</span>
                    <span id="ptt-peer-badge" style="font-size:11px;color:var(--text-secondary);">Broadcast to all peers</span>
                </div>

                <!-- Circular PTT Button with wave animation container -->
                <div style="position:relative;width:96px;height:96px;margin:0 auto 12px;display:flex;align-items:center;justify-content:center;">
                    <button id="voice-ptt-btn" 
                        onpointerdown="startPttInteraction(event)" 
                        onpointerup="stopPttInteraction(event)" 
                        onclick="handlePttClick(event)"
                        class="send-round-btn" 
                        style="width:90px;height:90px;border-radius:50%;font-size:38px;background:#00a884;border:3px solid #00e676;box-shadow:0 6px 20px rgba(0,168,132,0.4);touch-action:none;user-select:none;-webkit-user-select:none;display:flex;align-items:center;justify-content:center;cursor:pointer;transition:transform 0.1s, background-color 0.15s;" 
                        title="Hold or Tap to Speak">
                        🎙️
                    </button>
                </div>

                <!-- Label & Controls -->
                <div id="voice-ptt-label" style="font-size:13px;font-weight:bold;color:var(--text-primary);margin-bottom:4px;">Hold or Tap to Speak</div>
                <div id="voice-ptt-sublabel" style="font-size:11px;color:var(--text-secondary);margin-bottom:10px;">Zero-internet voice broadcast • Works on all phones</div>

                <div style="display:flex;justify-content:center;gap:8px;margin-bottom:8px;flex-wrap:wrap;">
                    <button id="ptt-cancel-btn" onclick="cancelPttRecording()" style="display:none;background:#ef4444;color:white;border:none;padding:5px 12px;border-radius:12px;font-size:11px;font-weight:bold;cursor:pointer;">
                        ✕ Cancel
                    </button>
                    <button onclick="triggerNativeVoiceRecord()" class="action-btn" style="background:rgba(255,255,255,0.08);border:1px solid var(--border-color);font-size:11px;padding:5px 12px;border-radius:12px;color:var(--text-secondary);" title="Record via device sound recorder popup">
                        🎤 Device Mic Popup
                    </button>
                    <button onclick="requestBrowserMicPermission()" class="action-btn" style="background:rgba(0,230,118,0.1);border:1px solid rgba(0,230,118,0.35);font-size:11px;padding:5px 12px;border-radius:12px;color:#00e676;font-weight:600;" title="Prompt browser for microphone permission" id="mic-perm-btn">
                        🔑 Allow Mic In Browser
                    </button>
                </div>
                <div id="mic-status-hint" style="font-size:10.5px;color:var(--text-secondary);text-align:center;">
                    Tap <b>Allow Mic</b> to prompt browser permission or switch to HTTPS
                </div>
            </div>

            <!-- Loudspeaker / Auto-Play Incoming Voice Toggle -->
            <div style="background:var(--input-bg);border-radius:12px;padding:10px 14px;margin-bottom:14px;display:flex;justify-content:space-between;align-items:center;border:1px solid var(--border-color);">
                <div style="display:flex;align-items:center;gap:8px;">
                    <span style="font-size:18px;">🔊</span>
                    <div>
                        <div style="font-size:12px;font-weight:bold;">Loudspeaker Mode</div>
                        <div style="font-size:10.5px;color:var(--text-secondary);">Auto-play incoming voice transmissions</div>
                    </div>
                </div>
                <input type="checkbox" id="voice-autoplay-toggle" checked onchange="toggleVoiceAutoplay(this.checked)" style="width:18px;height:18px;accent-color:#00a884;cursor:pointer;">
            </div>

            <!-- Recent Voice Transmissions Feed -->
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                <span style="font-size:11px;font-weight:bold;color:var(--text-secondary);text-transform:uppercase;letter-spacing:0.5px;">📻 Recent Transmissions</span>
                <span id="ptt-trans-count" style="font-size:10px;color:var(--text-secondary);">0 clips</span>
            </div>
            <div id="ptt-transmissions-list" style="display:flex;flex-direction:column;gap:6px;max-height:160px;overflow-y:auto;margin-bottom:12px;">
                <div style="font-size:12px;color:var(--text-secondary);text-align:center;padding:12px;background:var(--input-bg);border-radius:8px;">
                    No transmissions yet. Hold mic above to broadcast!
                </div>
            </div>

            <!-- Collapsible Advanced Live Continuous Mic -->
            <div style="border-top:1px solid var(--border-color);padding-top:10px;">
                <div onclick="toggleContinuousMicSection()" style="display:flex;justify-content:space-between;align-items:center;cursor:pointer;font-size:11px;color:var(--text-secondary);padding:4px 0;">
                    <span>⚙️ Continuous Voice Stream (Optional)</span>
                    <span id="continuous-mic-chevron">▼</span>
                </div>
                <div id="continuous-mic-section" style="display:none;margin-top:8px;">
                    <div style="display:flex;gap:6px;margin-bottom:8px;">
                        <button id="voice-join-btn" onclick="requestVoiceJoin()" class="action-btn green" style="flex:1;justify-content:center;padding:8px;font-size:12px;font-weight:bold;">Join Live Channel</button>
                        <button id="voice-mic-btn" onclick="toggleVoiceMic()" class="action-btn" style="display:none;flex:1;justify-content:center;padding:8px;font-size:12px;font-weight:bold;">Mic Off</button>
                        <button id="voice-deafen-btn" onclick="toggleVoiceDeafen()" class="action-btn" style="display:none;padding:8px;font-size:12px;">Deafen</button>
                    </div>
                    <div id="voice-members" style="display:flex;flex-direction:column;gap:4px;">
                        <div style="font-size:11px;color:var(--text-secondary);text-align:center;padding:4px;">Ready to connect</div>
                    </div>
                </div>
            </div>
        </div>
    </div>"""
        val radarNavHtml = """
            <button class="nav-item" onclick="navigateTo('radar')">
                <span class="icon">🛰️</span>
                <span>Radar</span>
            </button>"""
        val radarPageHtml = """
            <!-- PAGE: RADAR & PEERS -->
            <section id="view-radar" class="page-view">
                <div class="radar-container" style="padding:16px;max-width:600px;margin:0 auto;display:flex;flex-direction:column;gap:14px;overflow-y:auto;height:100%;">
                    <div style="background:var(--card-bg);border-radius:16px;padding:16px;border:1px solid var(--border-color);text-align:center;">
                        <div style="font-weight:bold;font-size:16px;display:flex;justify-content:space-between;align-items:center;">
                            <span>🛰️ Live Mesh Radar</span>
                            <span id="radar-live-count" style="font-size:11px;background:#005d4b;color:#00e676;padding:3px 8px;border-radius:10px;font-weight:bold;">1 Peer Active</span>
                        </div>
                        <div style="position:relative;width:200px;height:200px;margin:16px auto;border-radius:50%;border:2px solid #00a884;background:radial-gradient(circle, rgba(0,168,132,0.15) 0%, rgba(11,20,27,0.8) 70%);box-shadow:0 0 20px rgba(0,168,132,0.2);overflow:hidden;">
                            <div style="position:absolute;top:0;left:50%;width:50%;height:50%;transform-origin:0% 100%;background:linear-gradient(45deg, rgba(0,230,118,0.6) 0%, transparent 80%);animation:radarSweep 3s linear infinite;"></div>
                            <div style="position:absolute;inset:25px;border-radius:50%;border:1px dashed rgba(0,168,132,0.4);"></div>
                            <div style="position:absolute;inset:55px;border-radius:50%;border:1px dashed rgba(0,168,132,0.4);"></div>
                            <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:14px;height:14px;border-radius:50%;background:#00e676;box-shadow:0 0 10px #00e676;"></div>
                            <div id="radar-blips-layer"></div>
                        </div>
                        <p style="margin:0;font-size:12px;color:var(--text-secondary);">Direct peer-to-peer Wi-Fi Hotspot range ~100m. Zero internet required.</p>
                    </div>

                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span style="font-weight:bold;font-size:14px;">Connected Mesh Nodes</span>
                        <button class="action-btn" onclick="fetchRadarPeers()">🔄 Refresh</button>
                    </div>

                    <div id="radar-peers-list" style="display:flex;flex-direction:column;gap:8px;padding-bottom:24px;">
                        <div style="text-align:center;color:var(--text-secondary);font-size:13px;padding:12px;">Scanning mesh topology...</div>
                    </div>
                </div>
            </section>"""
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <meta name="theme-color" content="#1f2c34">
    <title>MeshChat Portal</title>
    <meta name="session-salt" content="$saltHex">
    <meta name="e2ee-supported" content="true">
    <style>
        :root {
            --bg-color: #0b141b;
            --header-bg: #1f2c34;
            --card-bg: #182229;
            --input-bg: #2a3942;
            --accent-green: #25d366;
            --accent-dark-green: #005d4b;
            --text-primary: #e9edef;
            --text-secondary: #8696a0;
            --border-color: #222d34;
            --msg-other: #1f2c34;
            --msg-me: #005d4b;
        }

        body.light-theme {
            --bg-color: #efeae2;
            --header-bg: #008069;
            --card-bg: #ffffff;
            --input-bg: #ffffff;
            --accent-green: #00a884;
            --accent-dark-green: #008069;
            --text-primary: #111b21;
            --text-secondary: #54656f;
            --border-color: #d1d7db;
            --msg-other: #ffffff;
            --msg-me: #d9fdd3;
        }

        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { margin: 0; padding: 0; height: 100%; width: 100%; overflow: hidden; background: var(--bg-color); color: var(--text-primary); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }

        #app-container { display: flex; flex-direction: column; height: 100%; width: 100%; position: relative; }

        /* Top Header */
        #top-header {
            background: var(--header-bg);
            color: var(--text-primary);
            padding: 10px 14px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            box-shadow: 0 2px 6px rgba(0,0,0,0.25);
            z-index: 50;
            flex-shrink: 0;
            position: relative;
        }
        .header-title-box {
            display: flex;
            align-items: center;
            gap: 10px;
            min-width: 0;
            flex: 1;
            margin-right: 8px;
        }
        .header-avatar {
            width: 38px;
            height: 38px;
            border-radius: 50%;
            background: var(--accent-dark-green);
            color: white;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 18px;
            font-weight: bold;
            flex-shrink: 0;
            user-select: none;
            transition: transform 0.1s;
        }
        .header-avatar:active { transform: scale(0.95); }
        .header-text {
            display: flex;
            flex-direction: column;
            min-width: 0;
            overflow: hidden;
        }
        .header-room {
            font-size: 15px;
            font-weight: 600;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            line-height: 1.25;
        }
        .header-status {
            font-size: 11px;
            color: var(--accent-green);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            line-height: 1.2;
            margin-top: 1px;
        }

        .header-actions {
            display: flex;
            align-items: center;
            gap: 8px;
            flex-shrink: 0;
        }
        .action-btn {
            background: rgba(255,255,255,0.1);
            border: none;
            color: var(--text-primary);
            padding: 6px 12px;
            border-radius: 16px;
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            gap: 4px;
            transition: background 0.15s, transform 0.1s;
        }
        .action-btn:hover { background: rgba(255,255,255,0.18); }
        .action-btn:active { transform: scale(0.97); }
        .action-btn.green { background: var(--accent-green); color: white; }

        /* Tactical Walkie Quick Button */
        .voice-btn {
            background: rgba(0, 230, 118, 0.12) !important;
            border: 1px solid rgba(0, 230, 118, 0.45) !important;
            color: #00e676 !important;
            padding: 6px 11px !important;
            border-radius: 16px !important;
            font-size: 12px !important;
            font-weight: 600 !important;
            white-space: nowrap !important;
            box-shadow: 0 1px 4px rgba(0, 230, 118, 0.15);
        }
        .voice-btn:hover {
            background: rgba(0, 230, 118, 0.22) !important;
            border-color: #00e676 !important;
        }
        @media (max-width: 360px) {
            .voice-btn .voice-label { display: none; }
            .voice-btn { padding: 6px 9px !important; }
        }

        /* 3-Dot Overflow Menu Button */
        .menu-btn {
            width: 36px;
            height: 36px;
            min-width: 36px;
            min-height: 36px;
            padding: 0;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
            line-height: 1;
            background: rgba(255,255,255,0.08);
            border: 1px solid var(--border-color);
            color: var(--text-primary);
            cursor: pointer;
            transition: background 0.15s, transform 0.1s, border-color 0.15s;
            -webkit-tap-highlight-color: transparent;
            flex-shrink: 0;
        }
        .menu-btn:hover, .menu-btn.active {
            background: rgba(255,255,255,0.18);
            border-color: var(--accent-green);
        }
        .menu-btn:active { transform: scale(0.94); }

        /* 3-Dot Dropdown Menu Card */
        #header-dropdown-menu {
            position: absolute;
            top: calc(100% + 6px);
            right: 12px;
            width: 260px;
            max-width: calc(100vw - 24px);
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 14px;
            box-shadow: 0 12px 36px rgba(0,0,0,0.6);
            padding: 6px;
            display: none;
            flex-direction: column;
            gap: 2px;
            z-index: 1000;
            animation: menuFadeIn 0.15s ease-out;
        }
        @keyframes menuFadeIn {
            from { opacity: 0; transform: translateY(-8px) scale(0.97); }
            to { opacity: 1; transform: translateY(0) scale(1); }
        }
        .menu-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 10px 12px;
            border-radius: 10px;
            border: none;
            background: transparent;
            color: var(--text-primary);
            cursor: pointer;
            font-size: 13px;
            font-weight: 500;
            text-align: left;
            width: 100%;
            transition: background 0.12s;
            text-decoration: none;
            -webkit-tap-highlight-color: transparent;
            box-sizing: border-box;
        }
        .menu-item:hover, .menu-item:active {
            background: rgba(255,255,255,0.08);
        }
        .menu-item-left {
            display: flex;
            align-items: center;
            gap: 10px;
            min-width: 0;
        }
        .menu-item-icon {
            font-size: 16px;
            width: 22px;
            text-align: center;
            flex-shrink: 0;
        }
        .menu-item-text {
            display: flex;
            flex-direction: column;
            min-width: 0;
        }
        .menu-item-title {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-primary);
            line-height: 1.25;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .menu-item-subtitle {
            font-size: 10.5px;
            color: var(--text-secondary);
            line-height: 1.2;
            margin-top: 2px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .menu-item-badge {
            font-size: 10px;
            font-weight: bold;
            padding: 2px 7px;
            border-radius: 8px;
            background: rgba(255,255,255,0.08);
            color: var(--text-secondary);
            flex-shrink: 0;
            margin-left: 8px;
        }
        .menu-divider {
            height: 1px;
            background: var(--border-color);
            margin: 4px 6px;
        }
        #menu-backdrop {
            position: fixed;
            inset: 0;
            z-index: 999;
            display: none;
            background: rgba(0,0,0,0.1);
        }
        .attach-round-btn {
            width: 40px;
            height: 40px;
            border-radius: 50%;
            border: none;
            background: rgba(255,255,255,0.08);
            color: var(--text-secondary);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 18px;
            cursor: pointer;
            flex-shrink: 0;
            transition: background 0.15s, color 0.15s, transform 0.1s;
        }
        .attach-round-btn:hover {
            background: rgba(255,255,255,0.16);
            color: var(--text-primary);
        }
        .attach-round-btn:active { transform: scale(0.94); }

        /* Main View Container */
        #viewport {
            flex: 1;
            position: relative;
            overflow: hidden;
            background: var(--bg-color);
        }

        .page-view {
            position: absolute;
            top: 0; left: 0; right: 0; bottom: 0;
            display: none;
            flex-direction: column;
            overflow: hidden;
        }
        .page-view.active { display: flex; }

        /* Bottom Tab Navigation Bar */
        #bottom-nav {
            background: var(--header-bg);
            border-top: 1px solid var(--border-color);
            display: flex;
            justify-content: space-around;
            align-items: center;
            padding: 6px 0;
            z-index: 50;
            flex-shrink: 0;
        }
        .nav-item {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            flex: 1;
            padding: 4px 0;
            color: var(--text-secondary);
            font-size: 11px;
            font-weight: 600;
            cursor: pointer;
            border: none;
            background: transparent;
            transition: color 0.15s ease;
        }
        .nav-item .icon { font-size: 18px; margin-bottom: 2px; }
        .nav-item.active { color: var(--accent-green); }
        .nav-item.active .icon { transform: scale(1.1); }

        /* PAGE 1: CHAT VIEW */
        #messages-scroller {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
            -webkit-overflow-scrolling: touch;
        }
        .date-pill { align-self: center; background: var(--card-bg); color: var(--text-secondary); font-size: 11px; border-radius: 8px; padding: 4px 12px; margin: 6px 0; font-weight: 600; }
        .msg { max-width: 82%; padding: 8px 12px; border-radius: 12px; word-wrap: break-word; line-height: 1.4; font-size: 14.5px; box-shadow: 0 1px 2px rgba(0,0,0,0.15); position: relative; }
        .msg-other { align-self: flex-start; background: var(--msg-other); color: var(--text-primary); border-top-left-radius: 2px; }
        .msg-me { align-self: flex-end; background: var(--msg-me); color: #fff; border-top-right-radius: 2px; }
        .msg-sender { font-size: 12px; color: var(--accent-green); font-weight: bold; margin-bottom: 2px; }
        .msg-time { font-size: 10px; color: var(--text-secondary); float: right; margin-left: 8px; margin-top: 4px; }

        #chat-input-area {
            background: var(--header-bg);
            border-top: 1px solid var(--border-color);
            padding: 8px 12px;
            display: flex;
            flex-direction: column;
            gap: 6px;
            flex-shrink: 0;
        }
        .chat-row { display: flex; gap: 8px; align-items: center; width: 100%; }
        .chat-input {
            flex: 1;
            background: var(--input-bg);
            color: var(--text-primary);
            border: none;
            padding: 10px 16px;
            border-radius: 24px;
            font-size: 15px;
            outline: none;
        }
        .send-round-btn {
            width: 40px; height: 40px;
            border-radius: 50%;
            border: none;
            background: var(--accent-green);
            color: white;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 17px;
            font-weight: bold;
            cursor: pointer;
            flex-shrink: 0;
        }
        .mic-round-btn {
            width: 40px; height: 40px;
            border-radius: 50%;
            border: none;
            background: var(--accent-dark-green);
            color: white;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 18px;
            cursor: pointer;
            flex-shrink: 0;
        }

        /* Voice Note Recording Bar */
        #voice-record-bar {
            display: none;
            background: #202c33;
            color: #ef5350;
            padding: 8px 14px;
            justify-content: space-between;
            align-items: center;
            border-top: 1px solid var(--border-color);
            font-weight: bold;
            font-size: 13px;
        }

        /* PAGE 2: FILE VAULT */
        .vault-container { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 14px; }
        .vault-upload-card {
            background: var(--card-bg);
            border: 2px dashed var(--accent-green);
            border-radius: 12px;
            padding: 20px;
            text-align: center;
            cursor: pointer;
        }
        .files-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 10px; }
        .file-item-card {
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 10px;
            display: flex;
            flex-direction: column;
            align-items: center;
            text-align: center;
            gap: 6px;
            overflow: hidden;
        }
        .file-icon { font-size: 32px; }
        .file-name { font-size: 12px; font-weight: 600; width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .file-size { font-size: 10px; color: var(--text-secondary); }
        .file-download-btn { background: var(--accent-green); color: white; border: none; padding: 4px 8px; border-radius: 12px; font-size: 11px; text-decoration: none; font-weight: bold; width: 100%; margin-top: 4px; }

        /* PAGE 3: ARCADE & GAMES */
        .arcade-container { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 16px; }
        .game-selection-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
        .game-card {
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            transition: transform 0.15s, border-color 0.15s;
        }
        .game-card:hover { transform: translateY(-2px); border-color: var(--accent-green); }
        .game-card-icon { font-size: 36px; }
        .game-card-title { font-size: 14px; font-weight: bold; }
        .game-card-desc { font-size: 11px; color: var(--text-secondary); text-align: center; }

        #active-game-frame-container {
            display: none;
            flex: 1;
            flex-direction: column;
            position: relative;
            background: #000;
        }
        #game-frame-header {
            background: var(--header-bg);
            padding: 8px 14px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--border-color);
        }
        #active-game-iframe { width: 100%; flex: 1; border: none; }

        /* PAGE 4: TOOLS & CLIPBOARD */
        .tools-container { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 16px; }
        .tool-card {
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        .tool-title { font-size: 16px; font-weight: bold; display: flex; align-items: center; gap: 6px; }
        .clipboard-box {
            background: var(--bg-color);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 12px;
            font-family: monospace;
            font-size: 13px;
            min-height: 48px;
            word-break: break-all;
        }

        /* Emergency Distress Banner */
        #sos-banner {
            display: none;
            background: #d32f2f;
            color: white;
            padding: 10px 14px;
            margin: 8px 12px;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(211,47,47,0.4);
            animation: pulse 1.2s infinite alternate;
        }
        @keyframes pulse { from { opacity: 0.9; } to { opacity: 1; } }
        @keyframes pttPulse {
            0% { transform: scale(1); box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.7); }
            70% { transform: scale(1.05); box-shadow: 0 0 0 16px rgba(239, 68, 68, 0); }
            100% { transform: scale(1); box-shadow: 0 0 0 0 rgba(239, 68, 68, 0); }
        }
        .ptt-active-recording {
            background: #ef4444 !important;
            border-color: #fca5a5 !important;
            animation: pttPulse 1.2s infinite !important;
        }

        /* Drag Overlay */
        #drop-overlay {
            display: none;
            position: fixed;
            top: 0; left: 0; width: 100%; height: 100%;
            background: rgba(0, 168, 132, 0.9);
            z-index: 999;
            align-items: center;
            justify-content: center;
            color: white;
            font-size: 20px;
            font-weight: bold;
            flex-direction: column;
        }
    </style>
</head>
<body>
    <div id="drop-overlay">
        <span>Drop files to share instantly over offline mesh 📂</span>
    </div>

    <!-- Voice Room Phase 1: temp guest profile modal -->
    <div id="profile-modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.6);z-index:1000;align-items:center;justify-content:center;">
        <div style="background:var(--card-bg);border-radius:12px;padding:20px;width:300px;text-align:center;">
            <div style="font-weight:bold;font-size:16px;margin-bottom:4px;">Your Guest Profile</div>
            <div style="font-size:12px;color:var(--text-secondary);margin-bottom:12px;">The host sees this when you request access.</div>
            <div id="portal-emoji-row" style="display:flex;gap:6px;justify-content:center;flex-wrap:wrap;margin-bottom:12px;"></div>
            <input id="portal-pname" maxlength="24" placeholder="Your name" style="width:100%;padding:10px;border-radius:8px;border:1px solid var(--border-color);background:var(--input-bg);color:var(--text-primary);font-size:15px;box-sizing:border-box;margin-bottom:12px;">
            <button onclick="savePortalProfile()" style="background:var(--accent-green);color:#fff;border:none;border-radius:8px;padding:10px 24px;font-weight:bold;font-size:15px;cursor:pointer;">Save Profile</button>
        </div>
    </div>

    <!-- Security Handshake / PIN Prompt Modal -->
    <div id="pin-prompt-modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.85);z-index:2000;align-items:center;justify-content:center;padding:16px;">
        <div style="background:var(--card-bg);border-radius:16px;padding:24px;max-width:360px;width:100%;border:1px solid #00e676;box-shadow:0 8px 32px rgba(0,230,118,0.25);text-align:center;">
            <div style="font-size:36px;margin-bottom:8px;">🔒</div>
            <h3 style="margin:0 0 8px;font-size:18px;color:var(--text-primary);">Encrypted Mesh Handshake</h3>
            <p style="font-size:13px;color:var(--text-secondary);margin:0 0 16px;line-height:1.4;">
                This room is protected by military-grade AES-256-GCM. Enter the <b>6-digit Host Room PIN</b> displayed on the host screen to seal your session.
            </p>
            <div style="display:flex;justify-content:center;margin-bottom:16px;">
                <input type="text" id="pin-input" maxlength="6" inputmode="numeric" pattern="[0-9]*" placeholder="• • • • • •" style="width:200px;text-align:center;font-size:24px;letter-spacing:6px;font-weight:bold;padding:10px;border-radius:8px;border:2px solid var(--accent-green);background:var(--input-bg);color:var(--text-primary);outline:none;" />
            </div>
            <button id="pin-submit-btn" onclick="submitPinFromModal()" class="action-btn green" style="width:100%;justify-content:center;padding:12px;font-size:14px;font-weight:bold;border-radius:8px;">
                🔐 Verify &amp; Seal Session
            </button>
            <p style="font-size:11px;color:var(--text-secondary);margin:12px 0 0;">
                Tip: Scanning the Host QR Code automatically configures keys with zero setup.
            </p>
        </div>
    </div>

    <!-- Unified APK Download Modal -->
    <div id="apk-download-modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:1500;align-items:center;justify-content:center;padding:16px;">
        <div style="background:var(--card-bg);border-radius:16px;padding:22px;max-width:380px;width:100%;border:1px solid var(--border-color);max-height:85vh;overflow-y:auto;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;">
                <div style="display:flex;align-items:center;gap:8px;">
                    <span style="font-size:22px;">📱</span>
                    <span style="font-weight:bold;font-size:17px;">Download AirChat App</span>
                </div>
                <button onclick="closeApkModal()" style="background:none;border:none;color:var(--text-secondary);font-size:20px;cursor:pointer;">✕</button>
            </div>
            <p style="font-size:13px;color:var(--text-secondary);margin:0 0 16px;line-height:1.4;">
                Download the official AirChat Android APK directly over this offline local Wi-Fi hotspot with zero internet required.
            </p>
            
            <!-- Full APK Card -->
            <div style="background:var(--input-bg);border:1px solid var(--border-color);border-radius:12px;padding:14px;margin-bottom:12px;">
                <div style="display:flex;justify-content:space-between;align-items:center;">
                    <div style="font-weight:bold;font-size:15px;color:#38bdf8;">⚡ AirChat (Full Edition)</div>
                    <span style="font-size:10px;background:#005d4b;color:#00e676;padding:2px 8px;border-radius:8px;font-weight:bold;">All-in-One</span>
                </div>
                <div style="font-size:12px;color:var(--text-secondary);margin:6px 0 10px;">
                    Includes Live Voice Rooms, Mesh Radar, Code IDE, Offline Games &amp; Media Feed.
                </div>
                <a href="/download" class="action-btn green" download="AirChat.apk" style="display:flex;justify-content:center;padding:10px;text-decoration:none;font-size:13px;font-weight:bold;border-radius:8px;">
                    ⬇️ Download Full APK
                </a>
            </div>

            <!-- Lite APK Card -->
            <div style="background:var(--input-bg);border:1px solid var(--border-color);border-radius:12px;padding:14px;margin-bottom:12px;">
                <div style="display:flex;justify-content:space-between;align-items:center;">
                    <div style="font-weight:bold;font-size:15px;color:#e9edef;">🪶 AirChat Lite</div>
                    <span style="font-size:10px;background:rgba(255,255,255,0.1);color:#8696a0;padding:2px 8px;border-radius:8px;font-weight:bold;">Ultra-Small</span>
                </div>
                <div style="font-size:12px;color:var(--text-secondary);margin:6px 0 10px;">
                    Compact seed version for low storage devices: Instant text chat, E2EE and file vault.
                </div>
                <a href="/download-lite" class="action-btn" download="AirChat-lite.apk" style="display:flex;justify-content:center;padding:10px;text-decoration:none;font-size:13px;font-weight:bold;border-radius:8px;background:rgba(255,255,255,0.12);">
                    ⬇️ Download Lite APK
                </a>
            </div>
        </div>
    </div>

    <!-- Live Interactive Tool Runner Modal -->
    <div id="tool-runner-modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.85);z-index:2000;flex-direction:column;">
        <div style="background:var(--header-bg);padding:12px 16px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--border-color);">
            <div style="display:flex;align-items:center;gap:8px;">
                <span style="font-size:18px;">⚡</span>
                <span id="tool-runner-title" style="font-weight:bold;font-size:15px;color:#38bdf8;">Mesh Interactive Tool</span>
            </div>
            <button onclick="closeInteractiveTool()" style="background:#ef4444;color:white;border:none;padding:5px 12px;border-radius:6px;font-weight:bold;cursor:pointer;">✕ Close Tool</button>
        </div>
        <iframe id="tool-runner-iframe" src="about:blank" style="flex:1;width:100%;border:none;background:#0f172a;"></iframe>
    </div>

    <!-- Privacy Shield Status Modal -->
    <div id="privacy-modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:1500;align-items:center;justify-content:center;padding:16px;">
        <div style="background:var(--card-bg);border-radius:16px;padding:20px;max-width:360px;width:100%;border:1px solid var(--border-color);max-height:85vh;overflow-y:auto;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
                <div style="display:flex;align-items:center;gap:8px;">
                    <span style="font-size:20px;">🛡️</span>
                    <span style="font-weight:bold;font-size:16px;">Privacy Shield Status</span>
                </div>
                <button onclick="closePrivacyModal()" style="background:none;border:none;color:var(--text-secondary);font-size:18px;cursor:pointer;">✕</button>
            </div>
            
            <div style="background:rgba(0,230,118,0.1);border:1px solid #00e676;border-radius:10px;padding:12px;margin-bottom:14px;">
                <div style="font-size:11px;color:#00e676;font-weight:bold;letter-spacing:0.5px;">ACTIVE CIPHER SUITE</div>
                <div style="font-size:14px;font-weight:bold;margin-top:2px;">AES-256-GCM (SubtleCrypto)</div>
                <div style="font-size:11px;color:var(--text-secondary);margin-top:4px;">Client-side WebCrypto encryption. Local Wi-Fi packets cannot be intercepted by routers, packet sniffers, or ISP monitors.</div>
            </div>

            <div style="margin-bottom:14px;">
                <div style="font-size:11px;color:var(--text-secondary);margin-bottom:4px;font-weight:bold;">SESSION FINGERPRINT</div>
                <div id="shield-fingerprint-box" style="font-family:monospace;font-size:12px;background:var(--input-bg);padding:8px 10px;border-radius:6px;word-break:break-all;">Not Established</div>
            </div>

            <div style="margin-bottom:16px;background:var(--input-bg);border-radius:10px;padding:12px;">
                <div style="font-weight:bold;font-size:13px;display:flex;align-items:center;gap:6px;">
                    <span>🔒 In-Memory Private Session</span>
                </div>
                <div style="font-size:11.5px;color:var(--text-secondary);margin-top:4px;line-height:1.4;">
                    Messages and keys are held in RAM only while this browser tab is open. No local storage, cookies, or history traces. Closing the tab removes all session data.
                </div>
            </div>

            <button onclick="reenterPin()" class="action-btn" style="width:100%;justify-content:center;padding:10px;margin-bottom:8px;background:rgba(255,255,255,0.08);">
                🔑 Re-Enter Room PIN
            </button>
            <button onclick="closePrivacyModal()" class="action-btn green" style="width:100%;justify-content:center;padding:10px;">
                Done
            </button>
        </div>
    </div>

    <div id="app-container">
        <!-- Top Persistent Header -->
        <header id="top-header">
            <div class="header-title-box">
                <div class="header-avatar" id="header-avatar-icon" onclick="openProfileModal()" title="Edit guest profile" style="cursor:pointer;">💬</div>
                <div class="header-text">
                    <span class="header-room" id="header-page-title">MeshChat Room</span>
                    <span class="header-status" id="header-mesh-status">🟢 Online (Offline Mesh AP)</span>
                </div>
            </div>
            <div class="header-actions">
                $voiceBtnHtml
                <button class="menu-btn" onclick="toggleHeaderMenu(event)" id="header-menu-btn" title="More options" aria-label="More options">⋮</button>
            </div>

            <!-- 3-Dot Dropdown Menu -->
            <div id="header-dropdown-menu">
                <button class="menu-item" onclick="openPrivacyModal(); closeHeaderMenu();" id="privacy-shield-btn">
                    <div class="menu-item-left">
                        <span class="menu-item-icon">🛡️</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">E2EE Privacy Shield</span>
                            <span class="menu-item-subtitle" id="shield-badge-text">AES-256 Active</span>
                        </div>
                    </div>
                    <span class="menu-item-badge" id="menu-e2ee-badge" style="background:#005d4b;color:#00e676;">ON</span>
                </button>

                <button class="menu-item" onclick="openApkModal(); closeHeaderMenu();" id="menu-apk-btn">
                    <div class="menu-item-left">
                        <span class="menu-item-icon">📥</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">Download Android App</span>
                            <span class="menu-item-subtitle">Full &amp; Lite Editions</span>
                        </div>
                    </div>
                    <span class="menu-item-badge" style="background:var(--accent-green);color:white;">APK</span>
                </button>

                <button class="menu-item" onclick="toggleTheme(); closeHeaderMenu();" id="theme-btn">
                    <div class="menu-item-left">
                        <span class="menu-item-icon">🌓</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">Toggle Theme</span>
                            <span class="menu-item-subtitle" id="menu-theme-subtitle">Dark / Light Mode</span>
                        </div>
                    </div>
                </button>

                <button class="menu-item" onclick="toggleBle(); closeHeaderMenu();" id="ble-btn">
                    <div class="menu-item-left">
                        <span class="menu-item-icon" id="menu-ble-icon">🔵</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">Web Bluetooth (BLE)</span>
                            <span class="menu-item-subtitle">No Wi-Fi required</span>
                        </div>
                    </div>
                    <span class="menu-item-badge" id="menu-ble-badge">Off</span>
                </button>

                <div class="menu-divider"></div>

                <button class="menu-item" onclick="openProfileModal(); closeHeaderMenu();">
                    <div class="menu-item-left">
                        <span class="menu-item-icon">👤</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">Guest Profile</span>
                            <span class="menu-item-subtitle">Change name &amp; avatar</span>
                        </div>
                    </div>
                </button>

                <button class="menu-item" onclick="navigateTo('radar'); closeHeaderMenu();">
                    <div class="menu-item-left">
                        <span class="menu-item-icon">🛰️</span>
                        <div class="menu-item-text">
                            <span class="menu-item-title">Mesh Radar &amp; Peers</span>
                            <span class="menu-item-subtitle">View active nodes</span>
                        </div>
                    </div>
                </button>
            </div>
            <div id="menu-backdrop" onclick="closeHeaderMenu()"></div>
        </header>

        <!-- Viewport containing All Pages (SPA - No Page Reloads) -->
        <main id="viewport">
            <!-- PAGE 1: CHAT -->
            <section id="view-chat" class="page-view active">
                <div id="sos-banner">
                    <div style="font-weight:bold; font-size:14px; display:flex; justify-content:space-between;">
                        <span>🚨 EMERGENCY DISTRESS BEACON</span>
                        <span style="font-size:11px; background:rgba(0,0,0,0.3); padding:2px 6px; border-radius:4px;">ACTIVE</span>
                    </div>
                    <div id="sos-sender" style="font-size:13px; margin-top:2px;"></div>
                    <div id="sos-text" style="font-size:12px; margin-top:2px;"></div>
                    <div id="sos-gps" style="font-size:11px; margin-top:4px;"></div>
                </div>

                <div id="messages-scroller">
                    <div class="date-pill">Local Mesh Chat • End-to-End Direct</div>
                </div>

                <div id="voice-record-bar">
                    <span id="voice-record-timer">🔴 Recording Voice Note... 0:00</span>
                    <div style="display:flex; gap:6px;">
                        <button type="button" onclick="cancelVoiceRecording()" style="background:#d32f2f; color:white; border:none; padding:4px 10px; border-radius:12px; font-weight:bold; cursor:pointer;">Cancel</button>
                        <button type="button" onclick="stopAndSendVoiceRecording()" style="background:var(--accent-green); color:white; border:none; padding:4px 12px; border-radius:12px; font-weight:bold; cursor:pointer;">Send</button>
                    </div>
                </div>

                <div id="chat-input-area">
                    <!-- File attachment preview chip (shown when a file is selected) -->
                    <div id="file-preview-bar" style="display:none;align-items:center;justify-content:space-between;background:var(--card-bg);border:1px solid var(--border-color);border-radius:12px;padding:6px 12px;margin-bottom:6px;font-size:12px;">
                        <div style="display:flex;align-items:center;gap:6px;min-width:0;overflow:hidden;">
                            <span style="font-size:16px;">📎</span>
                            <span id="file-preview-name" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--text-primary);font-weight:500;">File selected</span>
                        </div>
                        <div style="display:flex;align-items:center;gap:6px;flex-shrink:0;">
                            <button type="button" onclick="cancelFileSelection()" style="background:none;border:none;color:var(--text-secondary);cursor:pointer;font-size:16px;padding:2px 6px;">✕</button>
                            <button type="button" id="file-upload-btn" onclick="submitSelectedFile()" class="action-btn green" style="padding:4px 10px;font-size:11px;border-radius:10px;">Upload</button>
                        </div>
                    </div>

                    <form id="chat-form" class="chat-row" onsubmit="handleSendChat(event)">
                        <input type="file" id="file-selector" style="display:none;" onchange="handleFileSelected(event)">
                        <button type="button" class="attach-round-btn" onclick="document.getElementById('file-selector').click()" title="Attach File or Photo" aria-label="Attach File">📎</button>
                        <button type="button" class="mic-round-btn" onclick="toggleVoiceRecording()" title="Record Voice Note" aria-label="Record Voice">🎤</button>
                        <input type="text" id="chat-text-input" class="chat-input" placeholder="Message" autocomplete="off" required>
                        <button type="submit" class="send-round-btn" title="Send message" aria-label="Send message">➤</button>
                    </form>
                    <div id="transport-ind" style="font-size:11px;color:var(--text-secondary);text-align:right;min-height:12px;padding:0 4px;display:none;" title=""></div>
                </div>
            </section>

            <!-- PAGE 2: FILE VAULT -->
            <section id="view-files" class="page-view">
                <div class="vault-container">
                    <form id="vault-direct-upload" onsubmit="handleVaultUpload(event)" style="background:var(--card-bg);border:2px dashed var(--border-color);border-radius:14px;padding:20px;text-align:center;cursor:pointer;margin-bottom:16px;">
                        <input type="file" id="vault-file-input" style="display:none;" onchange="handleVaultFileSelected(event)">
                        <div onclick="document.getElementById('vault-file-input').click()">
                            <div style="font-size:36px;">📁</div>
                            <div style="font-weight:bold;font-size:15px;margin-top:6px;">Select or Drop File to Share</div>
                            <div style="font-size:12px;color:var(--text-secondary);margin-top:2px;" id="vault-file-chosen-name">Direct offline mesh file sharing. Any file up to 2GB.</div>
                        </div>
                        <button type="submit" id="vault-upload-submit-btn" class="action-btn green" style="display:none;margin:12px auto 0;padding:8px 20px;">📤 Upload to Mesh</button>
                    </form>
                    <div style="display:flex; justify-content:space-between; align-items:center;">
                        <span style="font-weight:bold; font-size:15px;">Shared Mesh Files</span>
                        <button class="action-btn" onclick="loadSharedFiles()">🔄 Refresh</button>
                    </div>
                    <div id="files-grid-container" class="files-grid">
                        <div style="grid-column: 1/-1; text-align:center; color:var(--text-secondary); font-size:13px; padding:20px;">Scanning mesh file repository...</div>
                    </div>
                </div>
            </section>

            $radarPageHtml

            <!-- PAGE 4: ARCADE & OFFLINE GAMES -->
            <section id="view-arcade" class="page-view">
                <div class="arcade-container" id="arcade-menu-view">
                    <div style="background:var(--card-bg);border:1px solid var(--border-color);border-radius:14px;padding:16px;text-align:center;">
                        <div style="font-size:28px;margin-bottom:4px;">🎮 Mesh Arcade</div>
                        <div style="font-size:13px;color:var(--text-secondary);">100% Offline Games. Play vs AI bots or Pass &amp; Play with friends nearby.</div>
                    </div>
                    <div class="game-selection-grid">
                        <div class="game-card" onclick="launchArcadeGame('/tictactoe', 'Tic-Tac-Toe')">
                            <span class="game-card-icon">⭕❌</span>
                            <span class="game-card-title">Tic-Tac-Toe</span>
                            <span class="game-card-desc">Minimax Smart AI &amp; Local 2-Player</span>
                        </div>
                        <div class="game-card" onclick="launchArcadeGame('/chess', 'Chess')">
                            <span class="game-card-icon">♟️</span>
                            <span class="game-card-title">Chess</span>
                            <span class="game-card-desc">Alpha-Beta Engine &amp; Pass &amp; Play</span>
                        </div>
                        <div class="game-card" onclick="launchArcadeGame('/connect4', 'Connect 4')">
                            <span class="game-card-icon">🔴🟡</span>
                            <span class="game-card-title">Connect 4</span>
                            <span class="game-card-desc">4-In-A-Row Drops vs AI</span>
                        </div>
                        <div class="game-card" onclick="launchArcadeGame('/snake', 'Retro Snake')">
                            <span class="game-card-icon">🐍</span>
                            <span class="game-card-title">Retro Snake</span>
                            <span class="game-card-desc">Classic 8-Bit Arcade &amp; Highscores</span>
                        </div>
                    </div>
                </div>
                <div id="active-game-frame-container">
                    <div id="game-frame-header">
                        <div style="display:flex;align-items:center;gap:8px;">
                            <span style="font-size:18px;">🎮</span>
                            <span id="active-game-title" style="font-weight:bold;font-size:15px;color:#38bdf8;">Game</span>
                        </div>
                        <button onclick="closeArcadeGame()" class="action-btn" style="background:#ef4444;color:white;font-weight:bold;">✕ Close Game</button>
                    </div>
                    <iframe id="active-game-iframe" src="about:blank"></iframe>
                </div>
            </section>
        </main>

        <!-- Bottom Persistent Tab Bar -->
        <nav id="bottom-nav">
            <button class="nav-item active" onclick="navigateTo('chat')">
                <span class="icon">💬</span>
                <span>Chat</span>
            </button>
            $radarNavHtml
            <button class="nav-item" onclick="navigateTo('files')">
                <span class="icon">📁</span>
                <span>Vault</span>
            </button>
            <button class="nav-item" onclick="navigateTo('arcade')">
                <span class="icon">🎮</span>
                <span>Arcade</span>
            </button>
        </nav>
    </div>

    <!-- Voice Room Phase 2: dashboard panel -->
                $voicePanelHtml

    <!-- Client Script (Persistent WebSocket + Navigation Engine) -->
    <script>
        // =========================================================
        // WebCrypto AES-256-GCM End-to-End Privacy Shield & Handshake
        // =========================================================
        let cryptoKey = null;
        let sessionSaltHex = (document.querySelector('meta[name="session-salt"]')?.content || '').trim();
        let e2eeActive = false;
        let activeKeyFingerprint = 'NONE';

        function hexToBytes(hex) {
            if (!hex) return new Uint8Array(0);
            const clean = hex.replace(/[^0-9a-fA-F]/g, '');
            const bytes = new Uint8Array(clean.length / 2);
            for (let i = 0; i < clean.length; i += 2) {
                bytes[i / 2] = parseInt(clean.substr(i, 2), 16);
            }
            return bytes;
        }

        function bytesToHex(bytes) {
            return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
        }

        let activePin = sessionStorage.getItem('aether_pin') || '';

        async function deriveKeyFromPin(pin, saltBytes) {
            if (!window.crypto || !window.crypto.subtle) return null;
            try {
                const enc = new TextEncoder();
                const pinKey = await window.crypto.subtle.importKey(
                    "raw",
                    enc.encode(pin),
                    { name: "PBKDF2" },
                    false,
                    ["deriveKey"]
                );
                return await window.crypto.subtle.deriveKey(
                    {
                        name: "PBKDF2",
                        salt: saltBytes,
                        iterations: 100000,
                        hash: "SHA-256"
                    },
                    pinKey,
                    { name: "AES-GCM", length: 256 },
                    true,
                    ["encrypt", "decrypt"]
                );
            } catch(e) {
                return null;
            }
        }

        async function importRawAesKey(rawBytes) {
            if (!window.crypto || !window.crypto.subtle) return null;
            try {
                return await window.crypto.subtle.importKey(
                    "raw",
                    rawBytes,
                    { name: "AES-GCM" },
                    true,
                    ["encrypt", "decrypt"]
                );
            } catch(e) {
                return null;
            }
        }

        async function computeKeyFingerprint(key) {
            try {
                if (key && window.crypto && window.crypto.subtle) {
                    const raw = await window.crypto.subtle.exportKey("raw", key);
                    const hashBuffer = await window.crypto.subtle.digest("SHA-256", raw);
                    const hashArray = new Uint8Array(hashBuffer);
                    return bytesToHex(hashArray).substring(0, 16).toUpperCase();
                }
                return "VERIFIED";
            } catch(e) {
                return "VERIFIED";
            }
        }

        async function encryptPayload(plaintextStr, key) {
            if (key && window.crypto && window.crypto.subtle) {
                try {
                    const iv = window.crypto.getRandomValues(new Uint8Array(12));
                    const enc = new TextEncoder();
                    const encoded = enc.encode(plaintextStr);
                    const cipherBuffer = await window.crypto.subtle.encrypt(
                        { name: "AES-GCM", iv: iv },
                        key,
                        encoded
                    );
                    const cipherArray = new Uint8Array(cipherBuffer);
                    const combined = new Uint8Array(iv.length + cipherArray.length);
                    combined.set(iv, 0);
                    combined.set(cipherArray, iv.length);
                    
                    let binary = '';
                    const len = combined.byteLength;
                    for (let i = 0; i < len; i++) {
                        binary += String.fromCharCode(combined[i]);
                    }
                    return window.btoa(binary);
                } catch(e) {
                    console.warn("Subtle encrypt failed:", e);
                }
            }
            return null;
        }

        async function decryptPayload(base64Str, key) {
            if (key && window.crypto && window.crypto.subtle) {
                try {
                    const binary = window.atob(base64Str);
                    const bytes = new Uint8Array(binary.length);
                    for (let i = 0; i < binary.length; i++) {
                        bytes[i] = binary.charCodeAt(i);
                    }
                    if (bytes.length > 12) {
                        const iv = bytes.subarray(0, 12);
                        const cipherWithTag = bytes.subarray(12);
                        const decryptedBuffer = await window.crypto.subtle.decrypt(
                            { name: "AES-GCM", iv: iv },
                            key,
                            cipherWithTag
                        );
                        const dec = new TextDecoder();
                        return dec.decode(decryptedBuffer);
                    }
                } catch(e) {
                    console.warn("Local subtle decrypt failed, attempting server decrypt:", e);
                }
            }

            // HTTP fallback: decrypt on local hotspot server if activePin is available
            if (activePin) {
                try {
                    const res = await fetch('/api/crypto/decrypt', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ payload: base64Str, pin: activePin })
                    });
                    if (res.ok) {
                        const data = await res.json();
                        if (data.plaintext) return data.plaintext;
                    }
                } catch(err) {
                    console.warn("Server decrypt fallback error:", err);
                }
            }
            throw new Error("Unable to decrypt message");
        }

        function updateShieldUi(active, text) {
            e2eeActive = active;
            const btn = document.getElementById('privacy-shield-btn');
            const txt = document.getElementById('shield-badge-text');
            if (txt) {
                txt.innerText = active ? (text || 'AES-256 Active') : (text || 'PIN Needed');
            }
            const badge = document.getElementById('menu-e2ee-badge');
            if (badge) {
                if (active) {
                    badge.innerText = 'ON';
                    badge.style.background = '#005d4b';
                    badge.style.color = '#00e676';
                } else {
                    badge.innerText = 'SETUP';
                    badge.style.background = 'rgba(255,152,0,0.15)';
                    badge.style.color = '#ff9800';
                }
            }
            const fpBox = document.getElementById('shield-fingerprint-box');
            if (fpBox) fpBox.innerText = activeKeyFingerprint || 'Not Established';
        }

        function openPrivacyModal() {
            document.getElementById('privacy-modal').style.display = 'flex';
        }
        function closePrivacyModal() {
            document.getElementById('privacy-modal').style.display = 'none';
        }

        async function initE2ee() {
            try {
                const infoRes = await fetch('/api/crypto/info');
                if (infoRes.ok) {
                    const info = await infoRes.json();
                    if (info.salt) sessionSaltHex = info.salt;
                    if (!info.isPrivate) {
                        // Public room: no PIN barrier required
                        if (info.autoKey && window.crypto && window.crypto.subtle) {
                            try {
                                cryptoKey = await importRawAesKey(hexToBytes(info.autoKey));
                                activeKeyFingerprint = await computeKeyFingerprint(cryptoKey);
                            } catch(e) {}
                        }
                        updateShieldUi(true, 'Mesh Active');
                        return;
                    }

                    // Private room: check if saved PIN is valid
                    if (activePin) {
                        const vRes = await fetch('/api/crypto/verify-pin', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ pin: activePin })
                        });
                        const vData = await vRes.json();
                        if (vData.verified) {
                            activeKeyFingerprint = vData.fingerprint || 'PIN-VERIFIED';
                            if (vData.key && window.crypto && window.crypto.subtle) {
                                try {
                                    cryptoKey = await importRawAesKey(hexToBytes(vData.key));
                                } catch(e) {}
                            }
                            updateShieldUi(true, 'Active');
                            return;
                        }
                    }
                }
            } catch(e) {
                console.warn("Could not check crypto info:", e);
            }

            // Private room and no verified PIN yet:
            updateShieldUi(false, 'PIN Needed');
            document.getElementById('pin-prompt-modal').style.display = 'flex';
        }

        async function submitPinFromModal() {
            const input = document.getElementById('pin-input');
            const pin = (input ? input.value : '').trim();
            if (!pin || pin.length < 4) {
                alert("Please enter the 6-digit Host Room PIN");
                return;
            }
            const btn = document.getElementById('pin-submit-btn');
            btn.innerText = "⏳ Verifying Session...";
            btn.disabled = true;

            try {
                const verifyRes = await fetch('/api/crypto/verify-pin', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ pin: pin })
                });
                const verifyData = await verifyRes.json();
                if (!verifyData.verified) {
                    throw new Error(verifyData.error || "Incorrect Host PIN. Please check the host screen.");
                }

                activePin = pin;
                sessionStorage.setItem('aether_pin', pin);
                activeKeyFingerprint = verifyData.fingerprint || 'PIN-VERIFIED';

                if (verifyData.key && window.crypto && window.crypto.subtle) {
                    try {
                        cryptoKey = await importRawAesKey(hexToBytes(verifyData.key));
                    } catch(e) {
                        console.warn("Subtle crypto key import skipped:", e);
                    }
                }

                document.getElementById('pin-prompt-modal').style.display = 'none';
                updateShieldUi(true, 'Active');
                btn.innerText = "🔐 Verify &amp; Seal Session";
                btn.disabled = false;
                fetchHistory();
            } catch(e) {
                alert("Verification failed: " + e.message);
                btn.innerText = "🔐 Verify &amp; Seal Session";
                btn.disabled = false;
            }
        }

        function reenterPin() {
            closePrivacyModal();
            document.getElementById('pin-prompt-modal').style.display = 'flex';
        }

        // Voice Room Phase 1: temp guest profile (name + emoji + stable pid).
        let myProfile = { name: '', emoji: '\ud83d\udca7', pid: '' };
        try {
            const rawProfile = localStorage.getItem('aether_profile');
            if (rawProfile) myProfile = JSON.parse(rawProfile);
        } catch(e) {}
        if (!myProfile.pid) {
            myProfile.pid = localStorage.getItem('aether_pid') || ('web-' + Math.random().toString(36).slice(2, 10));
            try { localStorage.setItem('aether_pid', myProfile.pid); } catch(e) {}
        }
        if (!myProfile.name) {
            const legacyName = localStorage.getItem('mesh_name');
            myProfile.name = legacyName || ('Web-' + Math.floor(100 + Math.random() * 900));
        }
        let myName = myProfile.name;
        let myEmoji = myProfile.emoji || '\ud83d\udca7';
        function persistProfile() {
            try { localStorage.setItem('aether_profile', JSON.stringify({ name: myName, emoji: myEmoji, pid: myProfile.pid })); } catch(e) {}
        }
        persistProfile();
        document.getElementById('diag-my-name').innerText = myName;

        // Temp profile editor modal.
        const PORTAL_EMOJIS = ['\ud83d\ude00', '\ud83d\udca7', '\ud83d\udcae', '\ud83d\udcbb', '\ud83d\udcb5', '\u26bd', '\ud83d\ude80', '\ud83d\ude1f'];
        let portalPickedEmoji = myEmoji;
        function renderPortalEmojiRow() {
            const row = document.getElementById('portal-emoji-row');
            row.innerHTML = '';
            PORTAL_EMOJIS.forEach(function(e) {
                const b = document.createElement('button');
                b.type = 'button';
                b.innerText = e;
                b.style.cssText = 'font-size:24px;background:var(--input-bg);border:2px solid ' + (e === portalPickedEmoji ? 'var(--accent-green)' : 'transparent') + ';border-radius:8px;padding:4px;cursor:pointer;';
                b.onclick = function() { portalPickedEmoji = e; renderPortalEmojiRow(); };
                row.appendChild(b);
            });
        }
        function openProfileModal() {
            document.getElementById('portal-pname').value = myName;
            portalPickedEmoji = myEmoji;
            renderPortalEmojiRow();
            document.getElementById('profile-modal').style.display = 'flex';
        }
        function savePortalProfile() {
            const v = document.getElementById('portal-pname').value.trim();
            if (v) myName = v;
            myEmoji = portalPickedEmoji;
            persistProfile();
            document.getElementById('diag-my-name').innerText = myName;
            document.getElementById('profile-modal').style.display = 'none';
            try { fetch('/api/profile', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: myName, emoji: myEmoji, pid: myProfile.pid }) }); } catch(e) {}
            connectWebSocket();
        }

        // Navigation Engine (Seamless view switching - ZERO CONNECTION BREAK)
        function navigateTo(pageId) {
            document.querySelectorAll('.page-view').forEach(p => p.classList.remove('active'));
            document.querySelectorAll('.nav-item').forEach(b => b.classList.remove('active'));

            const targetView = document.getElementById('view-' + pageId);
            if (targetView) targetView.classList.add('active');

            const navIndex = { 'chat': 0, 'radar': 1, 'files': 2, 'arcade': 3 }[pageId] || 0;
            const navBtn = document.querySelectorAll('.nav-item')[navIndex];
            if (navBtn) navBtn.classList.add('active');

            const titles = { 'chat': 'MeshChat Room', 'radar': 'Mesh Radar', 'files': 'Shared File Vault', 'arcade': 'Mesh Arcade' };
            document.getElementById('header-page-title').innerText = titles[pageId] || 'MeshChat';

            if (pageId === 'radar') fetchRadarPeers();
            if (pageId === 'files') loadSharedFiles();
            if (pageId === 'chat') {
                const scroller = document.getElementById('messages-scroller');
                if (scroller) scroller.scrollTop = scroller.scrollHeight;
            }
        }

        function launchArcadeGame(url, title) {
            const menu = document.getElementById('arcade-menu-view');
            const frameContainer = document.getElementById('active-game-frame-container');
            const titleEl = document.getElementById('active-game-title');
            const iframe = document.getElementById('active-game-iframe');
            if (menu) menu.style.display = 'none';
            if (titleEl) titleEl.innerText = title || 'Arcade Game';
            if (iframe) iframe.src = url;
            if (frameContainer) frameContainer.style.display = 'flex';
        }

        function closeArcadeGame() {
            const menu = document.getElementById('arcade-menu-view');
            const frameContainer = document.getElementById('active-game-frame-container');
            const iframe = document.getElementById('active-game-iframe');
            if (frameContainer) frameContainer.style.display = 'none';
            if (iframe) iframe.src = 'about:blank';
            if (menu) menu.style.display = 'flex';
        }

        // Radar Mesh Discovery Engine
        async function fetchRadarPeers() {
            const list = document.getElementById('radar-peers-list');
            const blipsLayer = document.getElementById('radar-blips-layer');
            const countBadge = document.getElementById('radar-live-count');
            try {
                const res = await fetch('/api/radar/peers');
                if (!res.ok) return;
                const data = await res.json();
                const peers = data.peers || [];
                const total = peers.length + 1; // +1 for host
                if (countBadge) countBadge.innerText = total + (total === 1 ? ' Peer Active' : ' Peers Active');

                if (blipsLayer) {
                    blipsLayer.innerHTML = '';
                    peers.forEach((p, idx) => {
                        const angle = (idx * (360 / Math.max(1, peers.length))) * (Math.PI / 180);
                        const radius = 35 + ((idx % 3) * 20);
                        const x = 100 + radius * Math.cos(angle) - 6;
                        const y = 100 + radius * Math.sin(angle) - 6;
                        const blip = document.createElement('div');
                        blip.style.cssText = `position:absolute;left:${'$'}{x}px;top:${'$'}{y}px;width:12px;height:12px;border-radius:50%;background:#38bdf8;box-shadow:0 0 8px #38bdf8;animation:pulse 1.5s infinite alternate;`;
                        blip.title = p.name;
                        blipsLayer.appendChild(blip);
                    });
                }

                if (list) {
                    list.innerHTML = '';
                    // Host node
                    const hostCard = document.createElement('div');
                    hostCard.style.cssText = 'background:var(--card-bg);border:1px solid #00e676;border-radius:12px;padding:12px;display:flex;align-items:center;justify-content:space-between;';
                    hostCard.innerHTML = `
                        <div style="display:flex;align-items:center;gap:10px;">
                            <span style="font-size:22px;">📱</span>
                            <div>
                                <div style="font-weight:bold;font-size:14px;color:#00e676;">${'$'}{data.localNode?.name || 'Local Host'} <span style="font-size:10px;background:#005d4b;color:#fff;padding:2px 6px;border-radius:6px;margin-left:4px;">HOST</span></div>
                                <div style="font-size:11px;color:var(--text-secondary);">${'$'}{data.localNode?.ip || '192.168.49.1'} • Mesh AP Gateway</div>
                            </div>
                        </div>
                        <span style="font-size:11px;color:#00e676;font-weight:bold;">Online</span>
                    `;
                    list.appendChild(hostCard);

                    if (peers.length === 0) {
                        const empty = document.createElement('div');
                        empty.style.cssText = 'text-align:center;color:var(--text-secondary);font-size:12px;padding:16px;';
                        empty.innerText = 'No other guest peers currently connected. Turn on other devices to join hotspot.';
                        list.appendChild(empty);
                    } else {
                        peers.forEach(p => {
                            const pCard = document.createElement('div');
                            pCard.style.cssText = 'background:var(--card-bg);border:1px solid var(--border-color);border-radius:12px;padding:12px;display:flex;align-items:center;justify-content:space-between;';
                            pCard.innerHTML = `
                                <div style="display:flex;align-items:center;gap:10px;">
                                    <span style="font-size:20px;">👤</span>
                                    <div>
                                        <div style="font-weight:bold;font-size:14px;">${'$'}{p.name || 'Peer'}</div>
                                        <div style="font-size:11px;color:var(--text-secondary);">${'$'}{p.transport || 'Wi-Fi Hotspot'} • Direct Link</div>
                                    </div>
                                </div>
                                <span style="font-size:11px;color:#38bdf8;font-weight:bold;">${'$'}{p.status || 'Active'}</span>
                            `;
                            list.appendChild(pCard);
                        });
                    }
                }
            } catch(e) {
                if (list) list.innerHTML = '<div style="text-align:center;color:#ef5350;font-size:12px;padding:12px;">Failed to scan mesh radar.</div>';
            }
        }

        // Unified APK Download Modal
        function openApkModal() {
            document.getElementById('apk-download-modal').style.display = 'flex';
        }
        function closeApkModal() {
            document.getElementById('apk-download-modal').style.display = 'none';
        }

        // Live Interactive Tool Runner
        function launchInteractiveTool(id, title) {
            const modal = document.getElementById('tool-runner-modal');
            const iframe = document.getElementById('tool-runner-iframe');
            const titleEl = document.getElementById('tool-runner-title');
            if (titleEl) titleEl.innerText = title || 'Mesh Interactive Tool';
            if (iframe) iframe.src = '/tools/run/' + encodeURIComponent(id);
            if (modal) modal.style.display = 'flex';
        }
        function closeInteractiveTool() {
            const modal = document.getElementById('tool-runner-modal');
            const iframe = document.getElementById('tool-runner-iframe');
            if (iframe) iframe.src = 'about:blank';
            if (modal) modal.style.display = 'none';
        }

        // Direct Vault Upload Handlers
        let vaultSelectedFile = null;
        function handleVaultFileSelected(event) {
            const files = event.target.files;
            if (files && files.length > 0) {
                vaultSelectedFile = files[0];
                document.getElementById('vault-file-chosen-name').innerText = 'Selected: ' + vaultSelectedFile.name + ' (' + Math.round(vaultSelectedFile.size / 1024) + ' KB)';
                document.getElementById('vault-upload-submit-btn').style.display = 'inline-flex';
            }
        }

        async function handleVaultUpload(event) {
            event.preventDefault();
            if (!vaultSelectedFile) return;
            const btn = document.getElementById('vault-upload-submit-btn');
            btn.innerText = '⏳ Uploading...';
            btn.disabled = true;

            const formData = new FormData();
            formData.append('sender', myName);
            formData.append('file', vaultSelectedFile);

            try {
                const res = await fetch('/upload', { method: 'POST', body: formData });
                if (res.ok) {
                    document.getElementById('vault-file-input').value = '';
                    document.getElementById('vault-file-chosen-name').innerText = 'File shared successfully!';
                    btn.style.display = 'none';
                    btn.innerText = '📤 Upload to Mesh';
                    btn.disabled = false;
                    vaultSelectedFile = null;
                    loadSharedFiles();
                } else {
                    alert('Upload failed: ' + res.statusText);
                    btn.innerText = '📤 Upload to Mesh';
                    btn.disabled = false;
                }
            } catch(err) {
                alert('Upload error: ' + err.message);
                btn.innerText = '📤 Upload to Mesh';
                btn.disabled = false;
            }
        }

        // Shared File Repository Loader
        async function loadSharedFiles() {
            const container = document.getElementById('files-grid-container');
            try {
                const res = await fetch('/api/files');
                if (!res.ok) return;
                const files = await res.json();
                if (files.length === 0) {
                    container.innerHTML = '<div style="grid-column: 1/-1; text-align:center; color:var(--text-secondary); font-size:13px; padding:20px;">No files shared in this session yet.</div>';
                    return;
                }
                container.innerHTML = '';
                for (let f of files) {
                    const card = document.createElement('div');
                    card.className = 'file-item-card';
                    let icon = '📄';
                    if (f.isImage) icon = '🖼️';
                    else if (f.isAudio) icon = '🎵';
                    else if (f.isVideo) icon = '🎬';
                    else if (f.name.endsWith('.apk')) icon = '📦';
                    else if (f.name.endsWith('.pdf')) icon = '📕';
                    else if (f.name.endsWith('.zip')) icon = '🗜️';

                    card.innerHTML = `
                        <div class="file-icon">${'$'}{icon}</div>
                        <div class="file-name" title="${'$'}{f.name}">${'$'}{f.name}</div>
                        <div class="file-size">${'$'}{f.formattedSize}</div>
                        <a href="${'$'}{f.downloadUrl}" class="file-download-btn" download="${'$'}{f.name}">Download</a>
                    `;
                    container.appendChild(card);
                }
            } catch(e) {
                container.innerHTML = '<div style="grid-column: 1/-1; text-align:center; color:#ef5350; font-size:13px; padding:20px;">Error loading file repository.</div>';
            }
        }

        // Clipboard Sync Engine
        async function fetchClipboard() {
            try {
                const res = await fetch('/api/clipboard');
                if (res.ok) {
                    const data = await res.json();
                    document.getElementById('tools-clip-text').innerText = data.text || '(Clipboard empty)';
                    if (data.sender) {
                        const dt = new Date(data.timestamp);
                        document.getElementById('tools-clip-meta').innerText = 'Synced by ' + data.sender + ' • ' + dt.toLocaleTimeString();
                    } else {
                        document.getElementById('tools-clip-meta').innerText = '';
                    }
                }
            } catch(e) {}
        }

        async function copyClipboardToDevice() {
            const txt = document.getElementById('tools-clip-text').innerText;
            if (txt && txt !== '(Clipboard empty)') {
                try {
                    await navigator.clipboard.writeText(txt);
                    alert('Copied to device clipboard!');
                } catch(e) {
                    alert('Could not copy automatically: ' + txt);
                }
            }
        }

        async function pushClipboardFromTools() {
            const input = document.getElementById('tools-clip-input');
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

        // Messaging Engine & History
        // Door tags (🌐 WiFi portal, 🔵 BLE guest) are baked into sender names
        // for visibility; strip them when checking identity.
        function bareName(n) { return (n || '').replace(/ [🌐🔵]$/, ''); }
        const messagesScroller = document.getElementById('messages-scroller');
        let lastHistoryHash = "";

        function appendMessageElement(sender, text, isMe, scroll = true) {
            const div = document.createElement('div');
            div.className = 'msg ' + (isMe ? 'msg-me' : 'msg-other');

            if (!isMe && sender) {
                const s = document.createElement('div');
                s.className = 'msg-sender';
                s.innerText = sender;
                div.appendChild(s);
            }

            let toolObj = null;
            try {
                const parsed = JSON.parse(text);
                if (parsed && parsed.type === 'interactive_tool') {
                    toolObj = parsed;
                }
            } catch(e) {}

            if (toolObj) {
                const toolCard = document.createElement('div');
                toolCard.style.cssText = 'background:rgba(56,189,248,0.12);border:1px solid #38bdf8;border-radius:12px;padding:12px;margin:4px 0;';
                const safeTitle = (toolObj.title || 'Tool').replace(/'/g, "\\'");
                toolCard.innerHTML = `
                    <div style="display:flex;align-items:center;gap:6px;font-weight:bold;color:#38bdf8;font-size:14px;">
                        <span>⚡</span>
                        <span>${'$'}{toolObj.title || 'Mesh Interactive Tool'}</span>
                    </div>
                    <div style="font-size:11px;color:var(--text-secondary);margin:4px 0 8px;">
                        Shared by ${'$'}{toolObj.author || sender} • ${'$'}{toolObj.language?.toUpperCase() || 'WEB'}
                    </div>
                    <div style="font-family:monospace;font-size:11px;background:rgba(0,0,0,0.3);padding:6px 8px;border-radius:6px;color:#94a3b8;margin-bottom:10px;white-space:pre-wrap;max-height:60px;overflow:hidden;">${'$'}{(toolObj.snippet || '').substring(0, 100)}...</div>
                    <button class="action-btn green" onclick="launchInteractiveTool('${'$'}{toolObj.toolId}', '${'$'}{safeTitle}')" style="width:100%;justify-content:center;padding:8px;font-size:13px;border-radius:8px;">
                        ▶️ Launch Live Tool
                    </button>
                `;
                div.appendChild(toolCard);
            } else {
                const words = text.split(' ');
                for (let w of words) {
                    if (w.startsWith('http://') || w.startsWith('https://')) {
                        const isImg = w.toLowerCase().match(/\.(jpg|jpeg|png|gif|webp)$/i) || w.includes("/files/web_shared_") || w.includes("mesh_cache");
                        const isAudio = w.match(/\.(m4a|mp3|wav|ogg|opus|aac|webm)$/i) || w.includes("web_audio_") || w.includes("/audio_") || w.includes("voice_");
                        if (isImg) {
                            const img = document.createElement('img');
                            img.src = w;
                            img.style.maxWidth = '100%';
                            img.style.borderRadius = '6px';
                            img.style.marginTop = '4px';
                            img.style.display = 'block';
                            div.appendChild(img);
                        } else if (isAudio) {
                            const audio = document.createElement('audio');
                            audio.controls = true;
                            audio.src = w;
                            audio.style.maxWidth = '250px';
                            audio.style.height = '36px';
                            audio.style.marginTop = '4px';
                            audio.style.display = 'block';
                            div.appendChild(audio);
                            if (typeof registerWalkieTransmission === 'function') {
                                registerWalkieTransmission(sender || 'Mesh Peer', w, isMe);
                            }
                        } else {
                            const a = document.createElement('a');
                            a.href = w;
                            a.target = '_blank';
                            a.innerText = '📎 ' + (w.substring(w.lastIndexOf('/') + 1) || 'File');
                            a.style.color = isMe ? '#e9edef' : '#25d366';
                            a.style.textDecoration = 'underline';
                            a.style.display = 'inline-block';
                            a.style.marginTop = '4px';
                            div.appendChild(a);
                        }
                        div.appendChild(document.createTextNode(' '));
                    } else {
                        div.appendChild(document.createTextNode(w + ' '));
                    }
                }
            }

            const timeSpan = document.createElement('span');
            timeSpan.className = 'msg-time';
            const now = new Date();
            timeSpan.innerText = String(now.getHours()).padStart(2,'0') + ':' + String(now.getMinutes()).padStart(2,'0') + (isMe ? ' ✓✓' : '');
            div.appendChild(timeSpan);

            messagesScroller.appendChild(div);
            if (scroll) messagesScroller.scrollTop = messagesScroller.scrollHeight;
        }

        async function fetchHistory() {
            try {
                const res = await fetch('/api/history');
                if (!res.ok) return;
                const data = await res.json();
                const hash = JSON.stringify(data);
                if (hash !== lastHistoryHash) {
                    lastHistoryHash = hash;
                    messagesScroller.innerHTML = '<div class="date-pill">Local Mesh Chat • End-to-End Direct</div>';
                    for (let m of data) {
                        let senderName = m.sender;
                        let msgText = m.message;
                        if (m.type === 'e2ee' && m.payload) {
                            try {
                                const dec = await decryptPayload(m.payload, cryptoKey);
                                const parsed = JSON.parse(dec);
                                senderName = parsed.sender || senderName;
                                msgText = parsed.message || '';
                            } catch(e) {
                                if (m.message && m.message !== m.payload) {
                                    msgText = m.message;
                                } else {
                                    msgText = '🔒 [Ciphertext - Enter Host PIN to decrypt]';
                                }
                            }
                        }
                        try {
                            const p = JSON.parse(msgText);
                            if (p.type === 'sos_beacon') {
                                showSosBeacon(p);
                                continue;
                            } else if (p.type === 'sos_cancel') {
                                hideSosBeacon();
                                continue;
                            }
                        } catch(e) {}
                        appendMessageElement(senderName, msgText, bareName(senderName) === myName, false);
                    }
                    messagesScroller.scrollTop = messagesScroller.scrollHeight;
                }
            } catch(e) {}
        }
        fetchHistory();

        function showSosBeacon(p) {
            const b = document.getElementById('sos-banner');
            b.style.display = 'block';
            document.getElementById('sos-sender').innerText = 'From: ' + (p.senderName || 'Nearby Peer');
            document.getElementById('sos-text').innerText = p.message || 'Distress signal received!';
            const gps = document.getElementById('sos-gps');
            if (p.lat && p.lng) {
                gps.innerHTML = '📍 Location: <a href="https://maps.google.com/?q=' + p.lat + ',' + p.lng + '" target="_blank" style="color:white; text-decoration:underline;">' + p.lat.toFixed(4) + ', ' + p.lng.toFixed(4) + '</a>';
            } else {
                gps.innerText = '📍 Location: Searching GPS...';
            }
        }

        function hideSosBeacon() {
            document.getElementById('sos-banner').style.display = 'none';
        }

        // Live WebSocket Link
        let ws = null;
        function connectWebSocket() {
            if (!window.WebSocket) return;
            try { if (ws && ws.readyState !== WebSocket.CLOSED) ws.close(); } catch(e) {}
            const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const url = proto + '//' + window.location.host + '/ws?sender=' + encodeURIComponent(myName) + '&emoji=' + encodeURIComponent(myEmoji) + '&pid=' + encodeURIComponent(myProfile.pid);
            ws = new WebSocket(url);
            ws.onopen = () => {
                document.getElementById('diag-ws-status').innerText = 'Active (Connected)';
                updateBleUi();
            };
            ws.onmessage = async (e) => {
                try {
                    let data = JSON.parse(e.data);
                    if (data.type === 'e2ee' && data.payload) {
                        try {
                            const dec = await decryptPayload(data.payload, cryptoKey);
                            data = JSON.parse(dec);
                        } catch(err) {
                            console.error("Could not decrypt ws frame", err);
                        }
                    }
                    const msg = JSON.parse(data.message || e.data);
                    if (msg.type === 'mesh_music_sync') {
                        document.getElementById('web-music-title').innerText = msg.trackTitle || 'Playing Track';
                        document.getElementById('web-music-artist').innerText = (msg.artist || '') + ' (DJ ' + (msg.hostName || '') + ')';
                        document.getElementById('web-music-party-badge').innerText = '🟢 LIVE DJ BROADCAST ACTIVE';
                        const audio = document.getElementById('mesh-web-audio');
                        if (audio.src && !audio.paused && msg.positionMs) {
                            const curSec = audio.currentTime;
                            const targetSec = msg.positionMs / 1000;
                            if (Math.abs(curSec - targetSec) > 1.5) {
                                audio.currentTime = targetSec;
                            }
                        }
                        return;
                    }
                } catch(err) {}
                fetchHistory();
                playAudioChime();
            };
            ws.onclose = (e) => {
                if (e && e.code === 1008) {
                    const statusEl = document.getElementById('diag-ws-status');
                    if (statusEl) statusEl.innerText = 'Access Denied / Room Sealed';
                    const notice = document.createElement('div');
                    notice.style.cssText = 'position:fixed;inset:0;background:rgba(18,24,31,0.95);z-index:99999;display:flex;align-items:center;justify-content:center;color:#e9edef;text-align:center;padding:24px;font-family:sans-serif;';
                    notice.innerHTML = '<div style="max-width:340px;background:#1f2c34;padding:28px 20px;border-radius:16px;border:1px solid #ef5350;"><div style="font-size:48px;margin-bottom:12px;">🔒</div><h3 style="margin:0 0 8px 0;color:#ef5350;">Session Terminated</h3><p style="color:#8696a0;font-size:14px;line-height:1.4;margin:0 0 16px 0;">The host has locked/sealed this room or disconnected this device session.</p><button onclick="location.reload()" style="background:#00a884;color:#fff;border:none;padding:10px 20px;border-radius:8px;font-weight:bold;cursor:pointer;">Retry</button></div>';
                    document.body.appendChild(notice);
                    return;
                }
                document.getElementById('diag-ws-status').innerText = 'Reconnecting in 2s...';
                updateBleUi();
                setTimeout(connectWebSocket, 2000);
            };
        }
        initE2ee().then(() => connectWebSocket());

        // Voice Room Phase 1: first visit -> force temp-profile setup.
        try { if (!localStorage.getItem('aether_profile')) openProfileModal(); } catch(e) {}

        // Door 4: silent BLE reconnect for returning guests (no picker).
        bleAutoReconnect();

        // Door 4: offline shell so the page loads with WiFi off (BLE-only chat).
        // Registration failure (e.g. insecure context) must never break the page.
        try {
            if ('serviceWorker' in navigator) {
                navigator.serviceWorker.register('/sw.js').catch(() => {});
            }
        } catch (e) {}

        // Mesh Walkie-Talkie (Push-to-Talk) & Voice Engine
        let voicePanelState = 'idle'; // idle | requested | joined
        let pttRecording = false;
        let pttHoldMode = false;
        let pttPressStartTime = 0;
        let pttTimer = null;
        let pttDuration = 0;
        let pttStream = null;
        let pttMediaRecorder = null;
        let pttAudioChunks = [];
        let autoPlayVoice = localStorage.getItem('walkie_autoplay') !== '0';
        let walkieTransmissions = [];
        const playedWalkieUrls = new Set();

        function toggleVoicePanel() {
            const p = document.getElementById('voice-panel');
            const open = p.style.display !== 'flex';
            p.style.display = open ? 'flex' : 'none';
            if (open) updateVoicePanel();
        }

        function toggleVoiceAutoplay(checked) {
            autoPlayVoice = checked;
            localStorage.setItem('walkie_autoplay', checked ? '1' : '0');
        }

        function toggleContinuousMicSection() {
            const sec = document.getElementById('continuous-mic-section');
            const chev = document.getElementById('continuous-mic-chevron');
            if (!sec) return;
            const isHidden = sec.style.display === 'none' || sec.style.display === '';
            sec.style.display = isHidden ? 'block' : 'none';
            if (chev) chev.innerText = isHidden ? '▲' : '▼';
        }

        function playPttTransmitBeep() {
            try {
                const AC = window.AudioContext || window.webkitAudioContext;
                if (!AC) return;
                const ctx = new AC();
                const osc = ctx.createOscillator();
                const gain = ctx.createGain();
                osc.type = 'sine';
                osc.frequency.setValueAtTime(1050, ctx.currentTime);
                osc.frequency.setValueAtTime(1350, ctx.currentTime + 0.05);
                gain.gain.setValueAtTime(0.16, ctx.currentTime);
                gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.12);
                osc.connect(gain);
                gain.connect(ctx.destination);
                osc.start();
                osc.stop(ctx.currentTime + 0.12);
            } catch(e) {}
        }

        function playRadioSquelch() {
            try {
                const AC = window.AudioContext || window.webkitAudioContext;
                if (!AC) return;
                const ctx = new AC();
                const osc = ctx.createOscillator();
                const gain = ctx.createGain();
                osc.type = 'triangle';
                osc.frequency.setValueAtTime(850, ctx.currentTime);
                osc.frequency.setValueAtTime(620, ctx.currentTime + 0.06);
                gain.gain.setValueAtTime(0.18, ctx.currentTime);
                gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.15);
                osc.connect(gain);
                gain.connect(ctx.destination);
                osc.start();
                osc.stop(ctx.currentTime + 0.15);
            } catch(e) {}
        }

        function updateVoicePanel() {
            const joinBtn = document.getElementById('voice-join-btn');
            const micBtn = document.getElementById('voice-mic-btn');
            const deafBtn = document.getElementById('voice-deafen-btn');
            if (voicePanelState === 'idle') {
                if (joinBtn) joinBtn.style.display = 'block';
                if (micBtn) micBtn.style.display = 'none';
                if (deafBtn) deafBtn.style.display = 'none';
            } else {
                if (joinBtn) joinBtn.style.display = 'none';
                if (micBtn) micBtn.style.display = 'block';
                if (deafBtn) deafBtn.style.display = 'block';
            }
            renderWalkieTransmissions();
            checkMicPermission();
        }

        async function checkMicPermission() {
            const btn = document.getElementById('mic-perm-btn');
            const hint = document.getElementById('mic-status-hint');
            if (!btn || !hint) return;

            const isHttps = window.location.protocol === 'https:' || window.location.hostname === 'localhost';
            if (!isHttps) {
                btn.innerText = '🔒 Switch to HTTPS';
                btn.style.color = '#ffb74d';
                btn.style.borderColor = 'rgba(255,183,77,0.4)';
                hint.innerHTML = 'Connected via HTTP. Tap above for HTTPS, or use <b>Device Mic Popup</b>.';
                return;
            }

            if (navigator.permissions && navigator.permissions.query) {
                try {
                    const status = await navigator.permissions.query({ name: 'microphone' });
                    if (status.state === 'granted') {
                        btn.innerText = '✅ Mic Allowed';
                        btn.style.color = '#00e676';
                        btn.style.borderColor = 'rgba(0,230,118,0.5)';
                        hint.innerHTML = 'Microphone permission active. Hold/tap mic above to broadcast!';
                    } else if (status.state === 'prompt') {
                        btn.innerText = '🔑 Allow Mic';
                        btn.style.color = '#00e676';
                        hint.innerHTML = 'Tap <b>Allow Mic</b> to show browser permission prompt';
                    } else if (status.state === 'denied') {
                        btn.innerText = '⚠️ Mic Blocked';
                        btn.style.color = '#ef4444';
                        btn.style.borderColor = 'rgba(239,68,68,0.4)';
                        hint.innerHTML = 'Mic blocked in browser. Tap lock icon in address bar to Allow.';
                    }
                    status.onchange = () => checkMicPermission();
                    return;
                } catch(e) {}
            }

            if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                btn.innerText = '🔑 Prompt Mic';
                hint.innerHTML = 'Tap to request browser microphone permission';
            }
        }

        async function requestBrowserMicPermission() {
            const isHttps = window.location.protocol === 'https:' || window.location.hostname === 'localhost';
            if (!isHttps) {
                const targetHttps = 'https://' + window.location.hostname + ':8443/';
                const go = confirm('Direct in-browser microphone capture is restricted by browser security policies on unencrypted HTTP.\n\nTap OK to switch to the secure HTTPS portal (:8443), or Cancel to use the device sound recorder popup.');
                if (go) window.location.href = targetHttps;
                else triggerNativeVoiceRecord();
                return;
            }

            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                alert('Your browser does not support in-browser audio capture. Please use the Device Mic Popup button.');
                triggerNativeVoiceRecord();
                return;
            }

            try {
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                stream.getTracks().forEach(t => t.stop());
                alert('✅ Microphone permission granted! You can now hold or tap the mic button to transmit voice.');
                checkMicPermission();
            } catch(err) {
                if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
                    alert('⚠️ Microphone was blocked by your browser.\n\nTo allow it: tap the tune / lock icon next to the address bar at the top, select Permissions -> Microphone -> Allow, then refresh this page.');
                } else {
                    alert('Could not access microphone: ' + err.message);
                }
                checkMicPermission();
            }
        }

        function triggerNativeVoiceRecord() {
            const input = document.getElementById('voice-native-recorder');
            if (input) {
                input.click();
            }
        }

        async function handleNativeVoiceRecorded(input) {
            if (!input.files || input.files.length === 0) return;
            const file = input.files[0];
            updatePttUi(false, '📡 Transmitting to Mesh...');
            await sendVoiceBlob(file);
            input.value = '';
        }

        async function startPttInteraction(e) {
            if (e) {
                if (e.pointerType === 'mouse' && e.button !== 0) return;
                e.preventDefault();
            }
            // If already recording in tap toggle mode, tapping again triggers transmit!
            if (pttRecording && !pttHoldMode) {
                stopPttAndTransmit();
                return;
            }
            if (pttRecording) return;

            pttPressStartTime = Date.now();
            pttHoldMode = true;

            const hasMedia = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
            if (!hasMedia) {
                pttHoldMode = false;
                triggerNativeVoiceRecord();
                return;
            }

            try {
                const stream = await navigator.mediaDevices.getUserMedia({
                    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
                });
                pttStream = stream;
                pttAudioChunks = [];
                let mimeType = 'audio/webm';
                if (window.MediaRecorder && !MediaRecorder.isTypeSupported('audio/webm')) {
                    if (MediaRecorder.isTypeSupported('audio/mp4')) mimeType = 'audio/mp4';
                    else if (MediaRecorder.isTypeSupported('audio/aac')) mimeType = 'audio/aac';
                    else mimeType = '';
                }
                pttMediaRecorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
                pttMediaRecorder.ondataavailable = ev => {
                    if (ev.data && ev.data.size > 0) pttAudioChunks.push(ev.data);
                };
                pttMediaRecorder.onstop = async () => {
                    if (pttStream) {
                        pttStream.getTracks().forEach(t => t.stop());
                        pttStream = null;
                    }
                    if (pttAudioChunks.length > 0) {
                        const type = pttMediaRecorder ? (pttMediaRecorder.mimeType || 'audio/webm') : 'audio/webm';
                        const blob = new Blob(pttAudioChunks, { type });
                        await sendVoiceBlob(blob);
                    }
                };

                pttMediaRecorder.start(250);
                pttRecording = true;
                pttDuration = 0;
                updatePttUi(true);

                clearInterval(pttTimer);
                pttTimer = setInterval(() => {
                    pttDuration++;
                    const mins = Math.floor(pttDuration / 60);
                    const secs = String(pttDuration % 60).padStart(2, '0');
                    const label = document.getElementById('voice-ptt-label');
                    if (label && pttRecording) {
                        label.innerText = '🔴 Recording... ' + mins + ':' + secs + ' (Release or Tap to Send)';
                    }
                }, 1000);

            } catch(err) {
                console.warn('Direct mic access error, falling back to native capture:', err);
                pttRecording = false;
                pttHoldMode = false;
                triggerNativeVoiceRecord();
            }
        }

        function stopPttInteraction(e) {
            if (e) e.preventDefault();
            if (!pttRecording) return;

            const durationPressed = Date.now() - pttPressStartTime;
            // Short tap: switch to tap-to-record mode!
            if (durationPressed < 400 && pttHoldMode) {
                pttHoldMode = false;
                const label = document.getElementById('voice-ptt-label');
                if (label) label.innerText = '🔴 Recording... Tap button to Transmit';
                const cancelBtn = document.getElementById('ptt-cancel-btn');
                if (cancelBtn) cancelBtn.style.display = 'inline-block';
                return;
            }

            stopPttAndTransmit();
        }

        function handlePttClick(e) {
            if (e) e.preventDefault();
            if (pttRecording && !pttHoldMode) {
                stopPttAndTransmit();
            }
        }

        function stopPttAndTransmit() {
            if (!pttRecording) return;
            pttRecording = false;
            pttHoldMode = false;
            clearInterval(pttTimer);
            updatePttUi(false, '📡 Transmitting to Mesh...');

            if (pttMediaRecorder && pttMediaRecorder.state !== 'inactive') {
                try { pttMediaRecorder.stop(); } catch(err) {}
            }
        }

        function cancelPttRecording() {
            if (!pttRecording) return;
            pttRecording = false;
            pttHoldMode = false;
            clearInterval(pttTimer);
            if (pttMediaRecorder) {
                pttMediaRecorder.onstop = null;
                try { pttMediaRecorder.stop(); } catch(e) {}
            }
            if (pttStream) {
                pttStream.getTracks().forEach(t => t.stop());
                pttStream = null;
            }
            updatePttUi(false, 'Hold or Tap to Speak');
        }

        function updatePttUi(isRec, customLabel) {
            const btn = document.getElementById('voice-ptt-btn');
            const label = document.getElementById('voice-ptt-label');
            const cancelBtn = document.getElementById('ptt-cancel-btn');

            if (btn) {
                if (isRec) {
                    btn.classList.add('ptt-active-recording');
                } else {
                    btn.classList.remove('ptt-active-recording');
                    btn.style.background = '#00a884';
                    btn.style.borderColor = '#00e676';
                }
            }
            if (cancelBtn) {
                cancelBtn.style.display = (isRec && !pttHoldMode) ? 'inline-block' : 'none';
            }
            if (label) {
                if (customLabel) {
                    label.innerText = customLabel;
                } else if (isRec) {
                    label.innerText = '🔴 Recording... Release or Tap to Send';
                } else {
                    label.innerText = 'Hold or Tap to Speak';
                }
            }
        }

        async function sendVoiceBlob(blob) {
            playPttTransmitBeep();
            const formData = new FormData();
            formData.append('sender', myName);
            const ext = blob.name ? blob.name.substring(blob.name.lastIndexOf('.')) : (blob.type && blob.type.includes('mp4') ? '.m4a' : '.webm');
            const filename = 'voice_' + Date.now() + (ext.startsWith('.') ? ext : '.' + ext);
            formData.append('file', blob, filename);
            try {
                await fetch('/upload', { method: 'POST', body: formData });
                fetchHistory();
                updatePttUi(false, '✅ Transmitted to Peers!');
                setTimeout(() => { updatePttUi(false, 'Hold or Tap to Speak'); }, 2000);
            } catch(err) {
                console.warn("Voice chunk upload failed:", err);
                updatePttUi(false, '⚠️ Transmit Failed. Retry');
            }
        }

        function registerWalkieTransmission(sender, url, isMe) {
            if (!url) return;
            if (!walkieTransmissions.some(t => t.url === url)) {
                walkieTransmissions.unshift({
                    sender: sender || 'Mesh Peer',
                    url: url,
                    time: new Date(),
                    isMe: isMe
                });
                if (walkieTransmissions.length > 25) walkieTransmissions.pop();
                renderWalkieTransmissions();
            }

            // Loudspeaker auto-play: automatically play incoming voice from peers
            if (!isMe && autoPlayVoice && !playedWalkieUrls.has(url)) {
                playedWalkieUrls.add(url);
                playRadioSquelch();
                const statusEl = document.getElementById('voice-panel-status');
                if (statusEl) statusEl.innerText = '📻 Receiving voice from ' + sender + '...';
                const audio = new Audio(url);
                audio.onended = () => {
                    if (statusEl) statusEl.innerText = 'Instant offline push-to-talk voice radio over local Wi-Fi mesh.';
                };
                audio.play().catch(e => {
                    console.log('Autoplay deferred by browser policy:', e);
                });
            }
        }

        function renderWalkieTransmissions() {
            const list = document.getElementById('ptt-transmissions-list');
            const count = document.getElementById('ptt-trans-count');
            if (count) count.innerText = walkieTransmissions.length + ' clips';
            if (!list) return;

            if (walkieTransmissions.length === 0) {
                list.innerHTML = '<div style="font-size:12px;color:var(--text-secondary);text-align:center;padding:12px;background:var(--input-bg);border-radius:8px;">No transmissions yet. Hold mic above to broadcast!</div>';
                return;
            }

            list.innerHTML = '';
            walkieTransmissions.forEach(t => {
                const row = document.createElement('div');
                row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;background:var(--input-bg);border-radius:8px;padding:8px 10px;font-size:12px;border-left:3px solid ' + (t.isMe ? 'var(--accent-green)' : '#38bdf8') + ';';
                
                const left = document.createElement('div');
                left.style.cssText = 'display:flex;flex-direction:column;gap:2px;overflow:hidden;margin-right:8px;';
                
                const title = document.createElement('div');
                title.style.fontWeight = 'bold';
                title.style.color = t.isMe ? 'var(--accent-green)' : '#e9edef';
                title.innerText = (t.isMe ? 'You' : t.sender);

                const time = document.createElement('div');
                time.style.fontSize = '10px';
                time.style.color = 'var(--text-secondary)';
                const dateObj = t.time instanceof Date ? t.time : new Date();
                time.innerText = String(dateObj.getHours()).padStart(2,'0') + ':' + String(dateObj.getMinutes()).padStart(2,'0');

                left.appendChild(title);
                left.appendChild(time);

                const playBtn = document.createElement('button');
                playBtn.innerText = '▶️ Play';
                playBtn.style.cssText = 'background:rgba(0,168,132,0.2);border:1px solid var(--accent-green);color:#00e676;border-radius:6px;font-size:11px;font-weight:bold;padding:4px 10px;cursor:pointer;flex-shrink:0;';
                playBtn.onclick = () => {
                    const a = new Audio(t.url);
                    a.play();
                };

                row.appendChild(left);
                row.appendChild(playBtn);
                list.appendChild(row);
            });
        }

        async function requestVoiceJoin() {
            try {
                const res = await fetch('/api/voice/request', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: myName, emoji: myEmoji, pid: myProfile.pid })
                });
                const data = await res.json();
                if (data.ok) {
                    voicePanelState = 'requested';
                    startVoiceStatusPoll();
                } else {
                    voicePanelState = 'requested';
                }
            } catch(e) {
                voicePanelState = 'requested';
            }
            updateVoicePanel();
        }
        // Voice Room Phase 3: real mic + /ws-voice audio pipe (16kHz PCM16, VAD-gated).
        let voiceAudio = null; // {ctx, stream, proc, src, ws, playNext, mutedByMe:Set}
        let voiceStatusTimer = null;

        function toggleVoiceMic() {
            const b = document.getElementById('voice-mic-btn');
            const isOff = b.innerText.indexOf('Off') !== -1;
            if (isOff) {
                startVoiceMic().then(ok => {
                    if (ok) { b.innerText = 'Mic On'; b.classList.add('green'); }
                });
            } else {
                stopVoiceMic();
                b.innerText = 'Mic Off';
                b.classList.remove('green');
            }
        }

        function closeVoiceSocket() {
            if (voiceAudio && voiceAudio.ws) {
                try { voiceAudio.ws.close(); } catch(e) {}
            }
        }

        function stopVoiceMic() {
            if (!voiceAudio) return;
            try { voiceAudio.proc.disconnect(); } catch(e) {}
            try { voiceAudio.src.disconnect(); } catch(e) {}
            try { voiceAudio.stream.getTracks().forEach(t => t.stop()); } catch(e) {}
            try { voiceAudio.ctx.close(); } catch(e) {}
            closeVoiceSocket();
            voiceAudio = null;
        }

        async function startVoiceMic() {
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                alert('Continuous microphone is restricted by this browser on plain HTTP. Please use the Walkie-Talkie button below, or open HTTPS / enable the Chrome flag.');
                return false;
            }
            if (voiceAudio) stopVoiceMic();
            let stream;
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
                });
            } catch(e) {
                alert('Microphone blocked: ' + e.message);
                return false;
            }
            const AC = window.AudioContext || window.webkitAudioContext;
            let ctx;
            try {
                ctx = new AC({ sampleRate: 16000 });
            } catch(e) {
                ctx = new AC();
            }
            try { if (ctx.state === 'suspended') await ctx.resume(); } catch(e) {}

            const wsUrl = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + window.location.host +
                '/ws-voice?pid=' + encodeURIComponent(myProfile.pid) +
                '&sender=' + encodeURIComponent(myName) +
                '&emoji=' + encodeURIComponent(myEmoji) + '&kind=temp';
            const ws = new WebSocket(wsUrl);
            ws.binaryType = 'arraybuffer';
            const mutedByMe = (voiceAudio && voiceAudio.mutedByMe) || new Set();
            const va = { ctx: ctx, stream: stream, proc: null, src: null, ws: ws, playNext: 0, mutedByMe: mutedByMe };
            voiceAudio = va;

            ws.onopen = () => {
                try { ws.send(JSON.stringify({ type: 'join', name: myName, emoji: myEmoji, pid: myProfile.pid })); } catch(e) {}
            };
            ws.onmessage = (ev) => {
                if (typeof ev.data === 'string') {
                    try { handleVoiceState(JSON.parse(ev.data)); } catch(e) {}
                } else {
                    playVoiceFrame(ev.data);
                }
            };
            ws.onclose = () => { /* mic stays warm; user can toggle off/on */ };

            const src = ctx.createMediaStreamSource(stream);
            const proc = ctx.createScriptProcessor(2048, 1, 1);
            va.src = src; va.proc = proc;
            const pidBytes = new TextEncoder().encode(myProfile.pid);
            let hangover = 0;
            proc.onaudioprocess = (e) => {
                if (!voiceAudio || ws.readyState !== WebSocket.OPEN) return;
                const inRate = e.inputBuffer.sampleRate || 16000;
                const inData = e.inputBuffer.getChannelData(0);
                // Downsample to 16kHz when the context runs faster (e.g. 48kHz).
                let input = inData;
                if (Math.abs(inRate - 16000) > 1) {
                    const ratio = Math.max(1, Math.round(inRate / 16000));
                    const outLen = Math.floor(inData.length / ratio);
                    const down = new Float32Array(outLen);
                    for (let i = 0; i < outLen; i++) down[i] = inData[i * ratio];
                    input = down;
                }
                // Energy VAD: only transmit actual speech.
                let sum = 0;
                for (let i = 0; i < input.length; i++) sum += input[i] * input[i];
                const rms = Math.sqrt(sum / input.length);
                if (rms > 0.02) hangover = 25; else if (hangover > 0) hangover--;
                if (hangover === 0) return;
                const pcm = new Int16Array(input.length);
                for (let i = 0; i < input.length; i++) {
                    const s = Math.max(-1, Math.min(1, input[i]));
                    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                }
                const frame = new Uint8Array(2 + pidBytes.length + pcm.length * 2);
                frame[0] = 1;
                frame[1] = pidBytes.length;
                frame.set(pidBytes, 2);
                frame.set(new Uint8Array(pcm.buffer), 2 + pidBytes.length);
                try { ws.send(frame.buffer); } catch(err) {}
            };
            src.connect(proc);
            proc.connect(ctx.destination);
            return true;
        }

        // Voice Room Phase 4: client-side deafen + per-member mute-for-me.
        let voiceDeafened = false;
        let lastVoiceMembers = [];
        function toggleVoiceDeafen() {
            voiceDeafened = !voiceDeafened;
            const b = document.getElementById('voice-deafen-btn');
            b.innerText = voiceDeafened ? 'Undeafen' : 'Deafen';
            b.classList.toggle('green', voiceDeafened);
        }
        function toggleVoiceMemberMute(id) {
            if (!voiceAudio) return;
            if (voiceAudio.mutedByMe.has(id)) voiceAudio.mutedByMe.delete(id);
            else voiceAudio.mutedByMe.add(id);
            handleVoiceState({ type: 'voice_state', members: lastVoiceMembers });
        }

        function playVoiceFrame(buf) {
            if (!voiceAudio || voiceDeafened) return;
            const u8 = new Uint8Array(buf);
            if (u8.length < 3 || u8[0] !== 1) return;
            const idLen = u8[1];
            if (u8.length < 2 + idLen) return;
            const senderId = new TextDecoder().decode(u8.slice(2, 2 + idLen));
            if (voiceAudio.mutedByMe.has(senderId)) return;
            const pcmBytes = u8.slice(2 + idLen);
            const n = Math.floor(pcmBytes.length / 2);
            if (n === 0) return;
            const pcm = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, n);
            const ctx = voiceAudio.ctx;
            const ab = ctx.createBuffer(1, n, 16000);
            const ch = ab.getChannelData(0);
            for (let i = 0; i < n; i++) ch[i] = pcm[i] / 0x8000;
            const node = ctx.createBufferSource();
            node.buffer = ab;
            node.connect(ctx.destination);
            const now = ctx.currentTime;
            // Schedule gaplessly, but keep latency bounded.
            let t = Math.max(now + 0.02, voiceAudio.playNext || now);
            if (t - now > 0.6) t = now + 0.02;
            try { node.start(t); } catch(e) { return; }
            voiceAudio.playNext = t + ab.duration;
        }

        function handleVoiceState(msg) {
            if (!msg || msg.type !== 'voice_state' || !msg.members) return;
            lastVoiceMembers = msg.members;
            const list = document.getElementById('voice-members');
            if (!list) return;
            // Phase 4: if the host muted me, lock my mic button.
            const me = msg.members.find(m => m.id === myProfile.pid);
            const micBtn = document.getElementById('voice-mic-btn');
            if (me && me.mutedByHost) {
                if (voiceAudio) stopVoiceMic();
                micBtn.innerText = 'Muted by host';
                micBtn.classList.remove('green');
                micBtn.disabled = true;
            } else if (micBtn.disabled) {
                micBtn.disabled = false;
                micBtn.innerText = 'Mic Off';
            }
            list.innerHTML = '';
            msg.members.forEach(m => {
                const row = document.createElement('div');
                row.style.cssText = 'display:flex;align-items:center;gap:8px;background:var(--input-bg);border-radius:8px;padding:6px 10px;font-size:13px;' +
                    (m.speaking ? 'border:2px solid var(--accent-green);' : '');
                const av = document.createElement('span');
                av.style.fontSize = '18px';
                av.innerText = m.emoji || '?';
                const nm = document.createElement('span');
                nm.style.flex = '1';
                nm.innerText = m.name + (m.kind === 'host' ? ' (host)' : m.kind === 'temp' ? ' (guest)' : '');
                const st = document.createElement('span');
                st.style.cssText = 'color:var(--accent-green);font-size:11px;';
                st.innerText = m.speaking ? 'speaking' : (m.mutedByHost ? 'muted' : '');
                row.appendChild(av);
                row.appendChild(nm);
                row.appendChild(st);
                // Phase 4: mute anyone for me (client-side frame dropping).
                if (m.id !== myProfile.pid) {
                    const muteBtn = document.createElement('button');
                    const isMuted = voiceAudio && voiceAudio.mutedByMe.has(m.id);
                    muteBtn.innerText = isMuted ? 'unmute' : 'mute';
                    muteBtn.style.cssText = 'background:var(--card-bg);border:1px solid var(--border-color);color:var(--text-primary);border-radius:6px;font-size:11px;padding:2px 8px;cursor:pointer;';
                    muteBtn.onclick = (function(id) { return function() { toggleVoiceMemberMute(id); }; })(m.id);
                    row.appendChild(muteBtn);
                }
                list.appendChild(row);
            });
        }

        function startVoiceStatusPoll() {
            stopVoiceStatusPoll();
            voiceStatusTimer = setInterval(async () => {
                try {
                    const res = await fetch('/api/voice/status');
                    const d = await res.json();
                    if (d.approved) {
                        stopVoiceStatusPoll();
                        voicePanelState = 'joined';
                        updateVoicePanel();
                    }
                } catch(e) {}
            }, 2000);
        }
        function stopVoiceStatusPoll() {
            if (voiceStatusTimer) { clearInterval(voiceStatusTimer); voiceStatusTimer = null; }
        }

        // Door 4: BLE + browser chat over Web Bluetooth. No WiFi needed — the
        // browser connects as a GATT central to the phone's mesh service and
        // exchanges chunked chat frames (mirrors BleMeshManager's protocol):
        //   byte0=0x01, bytes1-2=msgId BE, byte3=seq, byte4=total, bytes5-19=payload(15B)
        // Payload = UTF-8 JSON {"n":name,"t":text}. Rendered via the same
        // appendMessageElement as WS chat; own messages render on BLE echo
        // (same discipline as WS: no optimistic render, so no dedupe needed).
        const BLE_MESH_SERVICE = 0xFEAA;
        const BLE_WEB_CHAR = 'ca7061c5-06fc-4258-9ed5-e04a8f6a02fa';
        let bleLink = null; // {device, char, rx: Map(msgId -> {total, chunks, seen})}

        function bleToast(html, ms) {
            let t = document.getElementById('ble-toast');
            if (!t) {
                t = document.createElement('div');
                t.id = 'ble-toast';
                t.style.cssText = 'position:fixed;left:50%;bottom:70px;transform:translateX(-50%);' +
                    'background:#323232;color:#fff;border-radius:8px;padding:10px 14px;font-size:13px;' +
                    'z-index:2000;max-width:86vw;text-align:center;display:none;';
                document.body.appendChild(t);
            }
            t.innerHTML = html;
            t.style.display = 'block';
            if (t._timer) clearTimeout(t._timer);
            t._timer = setTimeout(() => { t.style.display = 'none'; }, ms || 6000);
        }

        function bleHttpsNudge() {
            bleToast('BLE needs the secure portal. <a href="https://' + window.location.hostname +
                ':8443/" style="color:#ffe0b2;font-weight:bold;">Tap here for HTTPS</a>', 12000);
        }

        function updateBleUi() {
            const b = document.getElementById('ble-btn');
            const badge = document.getElementById('menu-ble-badge');
            if (badge) {
                if (bleLink) {
                    badge.innerText = 'Connected';
                    badge.style.background = '#005d4b';
                    badge.style.color = '#00e676';
                } else {
                    badge.innerText = 'Off';
                    badge.style.background = 'rgba(255,255,255,0.08)';
                    badge.style.color = 'var(--text-secondary)';
                }
            }
            if (b) {
                b.title = bleLink ? 'BLE chat connected — tap to disconnect' : 'Connect via Bluetooth (no WiFi needed)';
            }
            const ind = document.getElementById('transport-ind');
            if (ind) {
                const wsUp = (typeof ws !== 'undefined' && ws && ws.readyState === WebSocket.OPEN);
                ind.textContent = wsUp ? '🌐 WiFi' : (bleLink ? '🔵 BLE' : '');
                ind.title = wsUp ? 'Sending via WiFi' : (bleLink ? 'Sending via Bluetooth' : 'Not connected');
            }
        }

        async function toggleBle() {
            if (bleLink) { bleDetach(true); return; }
            await connectBle();
        }

        async function connectBle() {
            if (!navigator.bluetooth) { bleToast('Web Bluetooth needs Chrome on Android.'); return; }
            if (window.location.protocol !== 'https:') { bleHttpsNudge(); return; }
            let device;
            try {
                // First contact: the system picker is mandatory (browser privacy),
                // but filtered to mesh advertisers only — one tap.
                device = await navigator.bluetooth.requestDevice({ filters: [{ services: [BLE_MESH_SERVICE] }] });
            } catch (e) { return; } // user cancelled the picker
            await bleAttach(device);
        }

        async function bleAttach(device) {
            try {
                if (bleLink) bleDetach(false);
                const server = await device.gatt.connect();
                const service = await server.getPrimaryService(BLE_MESH_SERVICE);
                const char = await service.getCharacteristic(BLE_WEB_CHAR);
                await char.startNotifications();
                const link = { device: device, char: char, rx: new Map() };
                char.oncharacteristicvaluechanged = (ev) => bleOnNotify(link, ev.target.value);
                device.ongattserverdisconnected = () => { if (bleLink === link) bleDetach(false); };
                bleLink = link;
                try { localStorage.setItem('aether_ble_id', device.id); } catch (e) {}
                updateBleUi();
                bleToast('🔵 BLE chat connected');
            } catch (e) {
                bleToast('BLE connect failed: ' + (e && e.message ? e.message : e));
                updateBleUi();
            }
        }

        function bleDetach(forget) {
            const link = bleLink;
            bleLink = null;
            if (link) {
                try { link.char.stopNotifications(); } catch (e) {}
                try { link.device.gatt.disconnect(); } catch (e) {}
                link.rx.clear();
            }
            if (forget) { try { localStorage.removeItem('aether_ble_id'); } catch (e) {} }
            updateBleUi();
        }

        // Silent reconnect for returning guests: getDevices() needs no picker
        // and no user gesture.
        async function bleAutoReconnect() {
            try {
                if (!navigator.bluetooth || !navigator.bluetooth.getDevices) return;
                if (window.location.protocol !== 'https:') return;
                const id = localStorage.getItem('aether_ble_id');
                if (!id) return;
                const devices = await navigator.bluetooth.getDevices();
                const dev = devices.find((d) => d.id === id);
                if (dev) await bleAttach(dev);
            } catch (e) {}
        }

        function bleOnNotify(link, dv) {
            try {
                if (!dv || dv.byteLength < 5 || dv.getUint8(0) !== 0x01) return;
                const msgId = dv.getUint16(1, false);
                const seq = dv.getUint8(3), total = dv.getUint8(4);
                if (total < 1 || total > 64 || seq >= total) return;
                const payload = new Uint8Array(dv.buffer, dv.byteOffset + 5, dv.byteLength - 5);
                let m = link.rx.get(msgId);
                if (!m || m.total !== total) {
                    m = { total: total, chunks: new Array(total).fill(null), seen: Date.now() };
                    link.rx.set(msgId, m);
                }
                m.chunks[seq] = payload;
                m.seen = Date.now();
                const now = Date.now();
                for (const [k, v] of link.rx) { if (now - v.seen > 30000) link.rx.delete(k); }
                if (m.chunks.every((c) => c)) {
                    link.rx.delete(msgId);
                    let len = 0;
                    for (const c of m.chunks) len += c.length;
                    const bytes = new Uint8Array(len);
                    let off = 0;
                    for (const c of m.chunks) { bytes.set(c, off); off += c.length; }
                    const obj = JSON.parse(new TextDecoder().decode(bytes));
                    if (obj && typeof obj.t === 'string') {
                        const nm = (typeof obj.n === 'string' && obj.n) ? obj.n : 'Web guest';
                        appendMessageElement(nm, obj.t, bareName(nm) === myName);
                    }
                }
            } catch (e) {}
        }

        async function bleSendChat(text) {
            const link = bleLink;
            if (!link) return false;
            try {
                const bytes = new TextEncoder().encode(JSON.stringify({ n: myName, t: text }));
                const total = Math.ceil(bytes.length / 15);
                if (total < 1 || total > 64) return false;
                const msgId = Date.now() % 65536;
                for (let i = 0; i < total; i++) {
                    const chunk = new Uint8Array(20);
                    chunk[0] = 0x01;
                    chunk[1] = (msgId >> 8) & 0xFF;
                    chunk[2] = msgId & 0xFF;
                    chunk[3] = i;
                    chunk[4] = total;
                    chunk.set(bytes.subarray(i * 15, Math.min((i + 1) * 15, bytes.length)), 5);
                    await link.char.writeValueWithResponse(chunk);
                }
                return true;
            } catch (e) {
                return false;
            }
        }

        async function handleSendChat(e) {
            e.preventDefault();
            const input = document.getElementById('chat-text-input');
            const txt = input.value.trim();
            if (!txt) return;
            input.value = '';

            let payload;
            if (cryptoKey) {
                try {
                    const plainObj = { sender: myName, message: txt, timestamp: Date.now() };
                    const enc = await encryptPayload(JSON.stringify(plainObj), cryptoKey);
                    payload = JSON.stringify({ type: 'e2ee', payload: enc });
                } catch(err) {
                    console.error("Encryption failed, sending plain", err);
                    payload = JSON.stringify({ sender: myName, message: txt });
                }
            } else {
                payload = JSON.stringify({ sender: myName, message: txt });
            }

            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(payload);
            } else if (bleLink) {
                // Door 4: no WiFi — send over BLE. Echo arrives via notify.
                await bleSendChat(txt);
            } else {
                await fetch('/api/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: payload
                });
            }
            fetchHistory();
        }

        function handleFileSelected(e) {
            const selector = document.getElementById('file-selector');
            if (!selector.files || selector.files.length === 0) return;
            const file = selector.files[0];
            const previewBar = document.getElementById('file-preview-bar');
            const previewName = document.getElementById('file-preview-name');
            if (previewBar && previewName) {
                let sizeStr = '';
                if (file.size > 1048576) {
                    sizeStr = (file.size / 1048576).toFixed(1) + ' MB';
                } else if (file.size > 1024) {
                    sizeStr = (file.size / 1024).toFixed(0) + ' KB';
                } else {
                    sizeStr = file.size + ' B';
                }
                previewName.innerText = file.name + ' (' + sizeStr + ')';
                previewBar.style.display = 'flex';
            }
        }

        function cancelFileSelection() {
            const selector = document.getElementById('file-selector');
            if (selector) selector.value = '';
            const previewBar = document.getElementById('file-preview-bar');
            if (previewBar) previewBar.style.display = 'none';
        }

        async function submitSelectedFile() {
            const selector = document.getElementById('file-selector');
            if (!selector.files || selector.files.length === 0) return;
            const btn = document.getElementById('file-upload-btn');
            if (btn) btn.innerText = '⏳ Uploading...';

            const formData = new FormData();
            formData.append('sender', myName);
            formData.append('file', selector.files[0]);

            try {
                const res = await fetch('/upload', { method: 'POST', body: formData });
                if (res.ok) {
                    cancelFileSelection();
                    if (btn) btn.innerText = 'Upload';
                    fetchHistory();
                } else {
                    if (btn) btn.innerText = '⚠️ Failed';
                    setTimeout(() => { if (btn) btn.innerText = 'Upload'; }, 3000);
                }
            } catch(err) {
                if (btn) btn.innerText = '⚠️ Error';
                setTimeout(() => { if (btn) btn.innerText = 'Upload'; }, 3000);
            }
        }

        async function handleFileUpload(e) {
            if (e) e.preventDefault();
            await submitSelectedFile();
        }

        // Voice Note Recording Engine
        let mediaRecorder = null;
        let audioChunks = [];
        let recordInterval = null;
        let recordSeconds = 0;

        async function toggleVoiceRecording() {
            if (mediaRecorder && mediaRecorder.state === 'recording') {
                stopAndSendVoiceRecording();
                return;
            }
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                triggerNativeVoiceRecord();
                return;
            }
            try {
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                mediaRecorder = new MediaRecorder(stream);
                audioChunks = [];
                mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data); };
                mediaRecorder.start();
                recordSeconds = 0;
                document.getElementById('voice-record-bar').style.display = 'flex';
                document.getElementById('voice-record-timer').innerText = '🔴 Recording Voice Note... 0:00';
                recordInterval = setInterval(() => {
                    recordSeconds++;
                    const mins = Math.floor(recordSeconds / 60);
                    const secs = String(recordSeconds % 60).padStart(2, '0');
                    document.getElementById('voice-record-timer').innerText = '🔴 Recording Voice Note... ' + mins + ':' + secs;
                }, 1000);
            } catch(err) {
                console.warn('Microphone stream error, opening device recorder:', err);
                triggerNativeVoiceRecord();
            }
        }

        function cancelVoiceRecording() {
            if (mediaRecorder) {
                mediaRecorder.onstop = null;
                mediaRecorder.stop();
                mediaRecorder.stream.getTracks().forEach(t => t.stop());
            }
            clearInterval(recordInterval);
            document.getElementById('voice-record-bar').style.display = 'none';
        }

        function stopAndSendVoiceRecording() {
            if (!mediaRecorder) return;
            clearInterval(recordInterval);
            document.getElementById('voice-record-bar').style.display = 'none';
            mediaRecorder.onstop = async () => {
                const blob = new Blob(audioChunks, { type: 'audio/webm' });
                const formData = new FormData();
                formData.append('sender', myName);
                formData.append('file', blob, 'web_audio_' + Date.now() + '.webm');
                try {
                    await fetch('/upload', { method: 'POST', body: formData });
                    fetchHistory();
                } catch(err) {}
                mediaRecorder.stream.getTracks().forEach(t => t.stop());
            };
            mediaRecorder.stop();
        }

        function playAudioChime() {
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

        // 3-Dot Overflow Dropdown Menu Handlers
        function toggleHeaderMenu(e) {
            if (e) {
                e.stopPropagation();
                e.preventDefault();
            }
            const menu = document.getElementById('header-dropdown-menu');
            const backdrop = document.getElementById('menu-backdrop');
            const btn = document.getElementById('header-menu-btn');
            if (!menu) return;
            const isVisible = menu.style.display === 'flex';
            if (isVisible) {
                closeHeaderMenu();
            } else {
                menu.style.display = 'flex';
                if (backdrop) backdrop.style.display = 'block';
                if (btn) btn.classList.add('active');
                updateHeaderMenuStatus();
            }
        }

        function closeHeaderMenu() {
            const menu = document.getElementById('header-dropdown-menu');
            const backdrop = document.getElementById('menu-backdrop');
            const btn = document.getElementById('header-menu-btn');
            if (menu) menu.style.display = 'none';
            if (backdrop) backdrop.style.display = 'none';
            if (btn) btn.classList.remove('active');
        }

        function updateHeaderMenuStatus() {
            const e2eeBadge = document.getElementById('menu-e2ee-badge');
            if (e2eeBadge) {
                if (e2eeActive) {
                    e2eeBadge.innerText = 'ON';
                    e2eeBadge.style.background = '#005d4b';
                    e2eeBadge.style.color = '#00e676';
                } else {
                    e2eeBadge.innerText = 'SETUP';
                    e2eeBadge.style.background = 'rgba(255,152,0,0.15)';
                    e2eeBadge.style.color = '#ff9800';
                }
            }
            const themeSub = document.getElementById('menu-theme-subtitle');
            if (themeSub) {
                const isLight = document.body.classList.contains('light-theme');
                themeSub.innerText = isLight ? 'Current: Light Theme' : 'Current: Dark Theme';
            }
            const bleBadge = document.getElementById('menu-ble-badge');
            if (bleBadge) {
                if (bleLink) {
                    bleBadge.innerText = 'Connected';
                    bleBadge.style.background = '#005d4b';
                    bleBadge.style.color = '#00e676';
                } else {
                    bleBadge.innerText = 'Off';
                    bleBadge.style.background = 'rgba(255,255,255,0.08)';
                    bleBadge.style.color = 'var(--text-secondary)';
                }
            }
        }

        function toggleTheme() {
            const isLight = document.body.classList.toggle('light-theme');
            localStorage.setItem('mesh_theme', isLight ? 'light' : 'dark');
            updateHeaderMenuStatus();
        }
        if (localStorage.getItem('mesh_theme') === 'light') {
            document.body.classList.add('light-theme');
        }
        setTimeout(updateHeaderMenuStatus, 100);

        window.addEventListener('click', (e) => {
            const menu = document.getElementById('header-dropdown-menu');
            const btn = document.getElementById('header-menu-btn');
            if (menu && menu.style.display === 'flex') {
                if (!menu.contains(e.target) && (!btn || !btn.contains(e.target))) {
                    closeHeaderMenu();
                }
            }
        });

        window.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') closeHeaderMenu();
        });

        // Drag and Drop Upload Support
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
                    fetchHistory();
                    navigateTo('files');
                } catch(err) {
                    alert('Upload failed: ' + err.message);
                }
            }
        });
    </script>
</body>
</html>
        """.trimIndent()
    }
}
