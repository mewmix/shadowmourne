package com.mewmix.glaive.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.mewmix.glaive.core.DebugLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(DebugLogger.getLogs()) }
    val theme = LocalGlaiveTheme.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.colors.background),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .border(1.dp, theme.colors.surface, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Debug Logs",
                        color = theme.colors.accent,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = theme.colors.text)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Log Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                        .border(1.dp, theme.colors.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    SelectionContainer {
                        LazyColumn(reverseLayout = true) {
                            item {
                                Text(
                                    text = logs,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            DebugLogger.clearLogs()
                            logs = ""
                        }
                    ) {
                        Icon(Icons.Default.Delete, null, tint = theme.colors.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", color = theme.colors.error)
                    }

                    Button(
                        onClick = {
                            val logFile = DebugLogger.getLogFile()
                            if (logFile != null && logFile.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        logFile
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share Logs"))
                                } catch (e: Exception) {
                                    DebugLogger.log("Error sharing log file, falling back to text", e)
                                    // Fallback to text sharing
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, logs)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share Logs"))
                                }
                            } else {
                                // Fallback if file doesn't exist (share text)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, logs)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Logs"))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = theme.colors.accent)
                    ) {
                        Icon(Icons.Default.Share, null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", color = Color.Black)
                    }
                }
            }
        }
    }
}
