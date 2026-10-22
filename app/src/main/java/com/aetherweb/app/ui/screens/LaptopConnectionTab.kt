package com.aetherweb.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetherweb.app.MeshState

@Composable
fun LaptopConnectionTab(
    uiState: MeshState,
    wifiIp: String?,
    hotspotIp: String?,
    resolvedIp: String,
    clipboard: ClipboardManager,
    context: Context,
    onStartHotspot: () -> Unit
) {
    val emeraldGreen = Color(0xFF25D366)
    val skyBlue = Color(0xFF38BDF8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "💻 Laptop, PC & Desktop Access",
            style = MaterialTheme.typography.titleLarge,
            color = emeraldGreen,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            "Use AirChat directly on any Windows PC, Mac, Linux, or Chromebook web browser with zero installation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Web Server Status Indicator
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF00E676).copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color(0xFF00E676)),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "🟢 Web Server Active on Port 8080 • AES-256-GCM E2EE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // METHOD 1: SAME WI-FI NETWORK (NO HOTSPOT REQUIRED)
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = emeraldGreen)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Method 1: Same Wi-Fi (Recommended)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("Zero setup • No hotspot passwords needed", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }

                Spacer(Modifier.height(10.dp))
                val targetWifiIp = wifiIp ?: resolvedIp
                val wifiUrl = "http://$targetWifiIp:8080"

                Text(
                    "1. Connect your laptop to the same Wi-Fi router as this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "2. Open Chrome, Edge, Brave, or Firefox on your laptop and enter:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, emeraldGreen.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = wifiUrl,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = emeraldGreen,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Laptop WebChat URL", wifiUrl))
                            Toast.makeText(context, "URL Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Laptop URL")
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(wifiUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test in Browser")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // METHOD 2: FIELD MESH HOTSPOT (FOR OUTDOOR / NO ROUTER)
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = skyBlue)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Method 2: Phone Mesh Hotspot", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (uiState.isHotspotActive) "🟢 Hotspot is Active" else "⚪ Hotspot is Off",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isHotspotActive) emeraldGreen else Color.Gray
                            )
                        }
                    }
                    if (!uiState.isHotspotActive) {
                        FilledTonalButton(
                            onClick = onStartHotspot,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Start Hotspot", fontWeight = FontWeight.Bold, color = emeraldGreen, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (uiState.isHotspotActive) {
                    Text("1. On your laptop Wi-Fi settings, connect to:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Network (SSID):", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(uiState.hotspotSsid, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("SSID", uiState.hotspotSsid))
                                Toast.makeText(context, "SSID Copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(34.dp)
                        ) { Text("Copy SSID", style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Password:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(uiState.hotspotPassword, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Password", uiState.hotspotPassword))
                                Toast.makeText(context, "Password Copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(34.dp)
                        ) { Text("Copy Pwd", style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(10.dp))
                    val hotspotUrl = "http://${hotspotIp ?: uiState.hotspotIp.ifEmpty { "192.168.43.1" }}:8080"
                    Text("2. Once connected, open your laptop browser to:", style = MaterialTheme.typography.bodySmall)
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(hotspotUrl, color = emeraldGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                    }
                } else {
                    Text(
                        "When away from Wi-Fi routers, tap 'Start Hotspot' to broadcast your offline mesh network for laptops and iPhones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // E2EE & INSTANT JOIN STATUS
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = emeraldGreen)
                        Spacer(Modifier.width(10.dp))
                        Text("Security & PIN", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        if (uiState.isPrivateRoom) "🔒 Protected Room" else "🔓 Public Room (Zero-PIN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.isPrivateRoom) Color(0xFFFF9800) else emeraldGreen
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (uiState.isPrivateRoom) {
                    Text("Your room is Private. When your laptop opens the URL, enter this 6-digit Host Room PIN:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    val formattedPin = if (uiState.webSessionPin.length == 6) "${uiState.webSessionPin.take(3)} - ${uiState.webSessionPin.takeLast(3)}" else uiState.webSessionPin.ifEmpty { "842 - 195" }
                    Text(
                        formattedPin,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = emeraldGreen
                    )
                } else {
                    Text(
                        "Your room is in standard Public Mesh mode. Connected laptops will join and seal AES-256-GCM encryption automatically without asking you to type a PIN!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
