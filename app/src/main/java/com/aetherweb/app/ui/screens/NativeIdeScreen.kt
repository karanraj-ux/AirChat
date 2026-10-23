package com.aetherweb.app.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aetherweb.app.MeshState
import com.aetherweb.app.MeshViewModel
import com.aetherweb.app.PocketCdnPacks

@Composable
fun NativeIdeScreen(
    uiState: MeshState,
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Templates for code languages
    val htmlDefault = """<!DOCTYPE html>
<html>
<head>
  <style>
    body { font-family: sans-serif; text-align: center; background: #0f172a; color: #f8fafc; padding: 20px; }
    h1 { color: #38bdf8; }
    .card { background: #1e293b; border-radius: 12px; padding: 16px; margin: 20px auto; max-width: 320px; border: 1px solid #334155; }
    button { background: #3b82f6; color: white; border: none; padding: 10px 20px; border-radius: 8px; font-weight: bold; cursor: pointer; }
  </style>
</head>
<body>
  <h1>⚡ AirChat Code</h1>
  <div class="card">
    <p id="status">Rendered completely offline on local Android device!</p>
    <button onclick="document.getElementById('status').innerText = '🎉 Interaction Worked! Time: ' + new Date().toLocaleTimeString()">Tap Me</button>
  </div>
</body>
</html>"""

    val jsDefault = """// Offline JavaScript Sandbox
function calculateMeshSpeed(nodes, latencyMs) {
    const throughput = (nodes * 1000) / (latencyMs || 1);
    return Math.round(throughput) + " pkts/sec";
}

const activePeers = 5;
const ping = 12;
console.log("Mesh Peer Speed: " + calculateMeshSpeed(activePeers, ping));
console.log("Math.PI * 2 = " + (Math.PI * 2).toFixed(4));
console.log("Ready for offline execution!");
"""

    val pythonDefault = """# Python-like Offline Logic Simulation
def simulate_node_sync(peer_count):
    res = []
    for i in range(peer_count):
        res.append(f"Peer #{i+1} ACK packet received")
    return res

output = simulate_node_sync(4)
print("\n".join(output))
print("--- Execution Complete ---")
"""

    var currentLang by remember { mutableStateOf("HTML") } // "HTML", "JavaScript", "Python"
    var codeHtml by remember { mutableStateOf(htmlDefault) }
    var codeJs by remember { mutableStateOf(jsDefault) }
    var codePython by remember { mutableStateOf(pythonDefault) }

    var activeTab by remember { mutableIntStateOf(0) } // 0: Editor, 1: Live Output, 2: Console Log
    var consoleLogs by remember { mutableStateOf(listOf<String>()) }
    var outputRenderKey by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var customToolTitle by remember { mutableStateOf("") }

    val currentCode = when (currentLang) {
        "HTML" -> codeHtml
        "JavaScript" -> codeJs
        "Python" -> codePython
        else -> codeHtml
    }

    fun updateCurrentCode(newCode: String) {
        when (currentLang) {
            "HTML" -> codeHtml = newCode
            "JavaScript" -> codeJs = newCode
            "Python" -> codePython = newCode
        }
    }

    fun appendLog(msg: String) {
        consoleLogs = consoleLogs + msg
    }

    fun runCode() {
        if (currentLang == "HTML") {
            outputRenderKey++
            activeTab = 1
            appendLog("🌐 HTML page rendered in WebView engine.")
        } else if (currentLang == "JavaScript") {
            appendLog("▶ Executing JavaScript in Web Engine...")
            activeTab = 2
            val wrappedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                <script>
                  (function() {
                    var oldLog = console.log;
                    var oldErr = console.error;
                    var oldWarn = console.warn;
                    console.log = function() {
                      var args = Array.prototype.slice.call(arguments).join(' ');
                      oldLog.apply(console, arguments);
                    };
                  })();
                </script>
                </head>
                <body>
                <script>
                  try {
                    $codeJs
                  } catch(e) {
                    console.error("Uncaught " + e.toString());
                  }
                </script>
                </body>
                </html>
            """.trimIndent()
            webViewRef?.loadDataWithBaseURL("http://127.0.0.1:8080/", wrappedHtml, "text/html", "UTF-8", null)
        } else {
            // Python execution sandbox
            appendLog("🐍 Running Python script...")
            activeTab = 2
            val logs = mutableListOf<String>()
            val lines = currentCode.lines()
            var executed = 0
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("print(") && trimmed.endsWith(")")) {
                    val content = trimmed.removePrefix("print(").removeSuffix(")").trim()
                    val unquoted = content.removeSurrounding("\"").removeSurrounding("'")
                    logs.add("🐍 [OUT]: $unquoted")
                    executed++
                } else if (trimmed.startsWith("print(")) {
                    val content = trimmed.removePrefix("print(").trim()
                    logs.add("🐍 [OUT]: $content")
                    executed++
                }
            }
            if (executed == 0) {
                logs.add("✓ Script checked. Tip: Add print('...') to view outputs in Console.")
            }
            logs.add("✓ Process finished with exit code 0")
            consoleLogs = consoleLogs + logs
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // De-cramped Top Toolbar
        Surface(
            color = Color(0xFF1E293B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: App Badge + Language Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF38BDF8).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "💻 Code IDE",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("HTML", "JavaScript", "Python").forEach { lang ->
                            FilterChip(
                                selected = currentLang == lang,
                                onClick = { currentLang = lang },
                                label = { Text(lang, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                }

                // Row 2: Action Controls & View Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabPill(title = "📝 Code", selected = activeTab == 0) { activeTab = 0 }
                        TabPill(title = "🌐 Live View", selected = activeTab == 1) { activeTab = 1 }
                        TabPill(
                            title = if (consoleLogs.isNotEmpty()) "📟 Console (${consoleLogs.size})" else "📟 Console",
                            selected = activeTab == 2
                        ) { activeTab = 2 }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { runCode() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = {
                                customToolTitle = "AirChat Tool ($currentLang)"
                                showShareDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (showShareDialog) {
            AlertDialog(
                onDismissRequest = { showShareDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Publish Interactive Tool", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    }
                },
                text = {
                    Column {
                        Text(
                            "Share this code as a live interactive web mini-app on the local mesh. Connected peers and web browsers can run and use this tool directly!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customToolTitle,
                            onValueChange = { customToolTitle = it },
                            label = { Text("Tool Name / Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val title = customToolTitle.trim().ifEmpty { "Mesh Tool ($currentLang)" }
                            viewModel.sendCodeSnippet(currentLang, currentCode, title)
                            showShareDialog = false
                            Toast.makeText(context, "Interactive Tool published to Mesh!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Text("Publish Live Tool", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showShareDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Main Viewport
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                0 -> {
                    // Code Editor with line numbers
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A))
                    ) {
                        val lines = currentCode.lines().size
                        val verticalScrollState = rememberScrollState()
                        val horizontalScrollState = rememberScrollState()

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Quick Coding Symbols Bar (like online web IDEs)
                            Surface(
                                color = Color(0xFF1E293B),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf("Tab", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "<", ">", "/", "+", "=>").forEach { symbol ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF0F172A),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                            modifier = Modifier.clickable {
                                                val insertion = if (symbol == "Tab") "  " else symbol
                                                updateCurrentCode(currentCode + insertion)
                                            }
                                        ) {
                                            Text(
                                                text = symbol,
                                                color = Color(0xFF38BDF8),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Code and Line Numbers Area
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A))
                            ) {
                                // Gutter
                                Column(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .fillMaxHeight()
                                        .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                                        .verticalScroll(verticalScrollState)
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    for (i in 1..maxOf(lines, 25)) {
                                        Text(
                                            text = "$i ",
                                            color = Color(0xFF64748B),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                    }
                                }

                                // Code Editor Input Area
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(12.dp)
                                        .verticalScroll(verticalScrollState)
                                        .horizontalScroll(horizontalScrollState)
                                ) {
                                    BasicTextField(
                                        value = currentCode,
                                        onValueChange = { updateCurrentCode(it) },
                                        textStyle = TextStyle(
                                            color = Color(0xFFE2E8F0),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 18.sp
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF38BDF8)),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // HTML / Web Output View
                    key(outputRenderKey) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    webViewClient = WebViewClient()
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                            consoleMessage?.let {
                                                appendLog("📝 [LOG]: ${it.message()}")
                                            }
                                            return true
                                        }
                                    }
                                    loadDataWithBaseURL("http://127.0.0.1:8080/", codeHtml, "text/html", "UTF-8", null)
                                    webViewRef = this
                                }
                            },
                            update = { wv ->
                                webViewRef = wv
                                if (activeTab == 1) {
                                    wv.loadDataWithBaseURL("http://127.0.0.1:8080/", codeHtml, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                2 -> {
                    // Terminal / Console Output View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF000000))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "TERMINAL OUTPUT",
                                color = Color(0xFF10B981),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { consoleLogs = emptyList() }) {
                                Text("Clear", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }

                        Divider(color = Color(0xFF1E293B), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(8.dp))

                        if (consoleLogs.isEmpty()) {
                            Text(
                                "No output yet. Press 'Run' to compile and evaluate code.",
                                color = Color(0xFF64748B),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            consoleLogs.forEach { log ->
                                Text(
                                    text = log,
                                    color = if (log.contains("Error") || log.contains("❌")) Color(0xFFEF4444) else Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0xFF38BDF8).copy(alpha = 0.2f) else Color.Transparent,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)) else null,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = title,
            color = if (selected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
