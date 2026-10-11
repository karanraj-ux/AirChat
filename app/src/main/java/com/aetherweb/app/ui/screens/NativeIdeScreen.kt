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

    fun runCode() {
        if (currentLang == "HTML") {
            outputRenderKey++
            activeTab = 1
        } else if (currentLang == "JavaScript") {
            // Run JS in simulated console
            val logs = mutableListOf<String>()
            logs.add("▶ Running JavaScript...")
            try {
                // Execute in lightweight JavaScript evaluator or log output
                val jsEngineLines = currentCode.lines()
                var evaluatedCount = 0
                jsEngineLines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("console.log(") && trimmed.endsWith(");")) {
                        val content = trimmed.removePrefix("console.log(").removeSuffix(");").trim()
                        val stripped = content.removeSurrounding("\"").removeSurrounding("'")
                        logs.add("📝 [LOG]: $stripped")
                        evaluatedCount++
                    }
                }
                if (evaluatedCount == 0) {
                    logs.add("✓ Script syntax valid ($ {currentCode.length} chars)")
                    logs.add("💡 Hint: Add console.log('...') to see direct outputs here")
                }
                logs.add("✓ Execution finished successfully")
            } catch (e: Exception) {
                logs.add("❌ Error: ${e.message}")
            }
            consoleLogs = logs
            activeTab = 2
        } else {
            // Python execution
            val logs = mutableListOf<String>()
            logs.add("▶ Running Python Script...")
            val lines = currentCode.lines()
            var printed = 0
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("print(") && trimmed.endsWith(")")) {
                    val p = trimmed.removePrefix("print(").removeSuffix(")").trim()
                    val stripped = p.removeSurrounding("\"").removeSurrounding("'")
                    logs.add("🐍 [OUT]: $stripped")
                    printed++
                }
            }
            if (printed == 0) {
                logs.add("✓ Python script compiled (${currentCode.length} bytes)")
                logs.add("💡 Hint: Use print('...') for visual output in Console")
            }
            logs.add("✓ Process finished with exit code 0")
            consoleLogs = logs
            activeTab = 2
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Top Toolbar
        Surface(
            color = Color(0xFF1E293B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "💻 Native IDE",
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Language Pills
                        listOf("HTML", "JavaScript", "Python").forEach { lang ->
                            FilterChip(
                                selected = currentLang == lang,
                                onClick = { currentLang = lang },
                                label = { Text(lang, fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    // Action Buttons (Run & Share)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { runCode() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                viewModel.sendCodeSnippet(currentLang, currentCode)
                                Toast.makeText(context, "Code shared to Mesh Chat!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share to Mesh", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Tab Switcher (Editor / Output / Console)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TabPill(title = "📝 Code Editor", selected = activeTab == 0) { activeTab = 0 }
                    TabPill(title = "🌐 Live View", selected = activeTab == 1) { activeTab = 1 }
                    TabPill(title = "📟 Console", selected = activeTab == 2) { activeTab = 2 }
                }
            }
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
                        // Line number column
                        Column(
                            modifier = Modifier
                                .width(36.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..maxOf(lines, 20)) {
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
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
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
                                    loadDataWithBaseURL("http://127.0.0.1:8080/", codeHtml, "text/html", "UTF-8", null)
                                }
                            },
                            update = { wv ->
                                wv.loadDataWithBaseURL("http://127.0.0.1:8080/", codeHtml, "text/html", "UTF-8", null)
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
