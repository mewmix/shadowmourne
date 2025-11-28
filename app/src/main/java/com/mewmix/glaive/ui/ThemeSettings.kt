package com.mewmix.glaive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsDialog(
    currentTheme: ThemeConfig,
    onDismiss: () -> Unit,
    onApply: (ThemeConfig) -> Unit,
    onReset: () -> Unit
) {
    var background by remember { mutableStateOf(currentTheme.colors.background) }
    var surface by remember { mutableStateOf(currentTheme.colors.surface) }
    var text by remember { mutableStateOf(currentTheme.colors.text) }
    var accent by remember { mutableStateOf(currentTheme.colors.accent) }

    var cornerRadius by remember { mutableStateOf(currentTheme.shapes.cornerRadius.value) }
    var borderWidth by remember { mutableStateOf(currentTheme.shapes.borderWidth.value) }

    var fontName by remember { mutableStateOf(currentTheme.typography.name) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = currentTheme.colors.background),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, currentTheme.colors.surface, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Theme Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.colors.text
                )

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text("Colors", color = currentTheme.colors.accent, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        ColorPickerRow("Background", background) { background = it }
                        ColorPickerRow("Surface", surface) { surface = it }
                        ColorPickerRow("Text", text) { text = it }
                        ColorPickerRow("Accent", accent) { accent = it }
                    }

                    item {
                        Text("Shapes", color = currentTheme.colors.accent, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        SliderRow("Corner Radius", cornerRadius, 0f, 32f) { cornerRadius = it }
                        SliderRow("Border Width", borderWidth, 0f, 5f) { borderWidth = it }
                    }

                    item {
                        Text("Typography", color = currentTheme.colors.accent, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        FontSelector(fontName) { fontName = it }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) {
                        Text("Reset", color = currentTheme.colors.error)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = currentTheme.colors.text)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val fontFamily = when(fontName) {
                                "SansSerif" -> FontFamily.SansSerif
                                "Serif" -> FontFamily.Serif
                                "Cursive" -> FontFamily.Cursive
                                else -> FontFamily.Monospace
                            }
                            onApply(
                                ThemeConfig(
                                    colors = GlaiveColors(background, surface, text, accent, currentTheme.colors.error),
                                    shapes = GlaiveShapes(cornerRadius.dp, borderWidth.dp),
                                    typography = GlaiveTypography(fontFamily, fontName)
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Apply", color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun ColorPickerRow(label: String, color: Color, onColorChange: (Color) -> Unit) {
    var hexText by remember(color) {
        mutableStateOf(String.format("#%06X", (0xFFFFFF and color.toArgb())))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.Gray, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = hexText,
                onValueChange = {
                    hexText = it
                    try {
                        val cleanHex = it.replace("#", "")
                        if (cleanHex.length == 6) {
                            val parsedColor = Color(android.graphics.Color.parseColor("#$cleanHex"))
                            onColorChange(parsedColor)
                        }
                    } catch (e: Exception) {
                        // Ignore invalid
                    }
                },
                textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .width(80.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.Gray, fontSize = 14.sp)
            Text("${value.toInt()} dp", color = Color.Gray, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = LocalGlaiveTheme.current.colors.accent,
                activeTrackColor = LocalGlaiveTheme.current.colors.accent,
                inactiveTrackColor = LocalGlaiveTheme.current.colors.surface
            )
        )
    }
}

@Composable
fun FontSelector(currentFont: String, onFontSelected: (String) -> Unit) {
    val fonts = listOf("Monospace", "SansSerif", "Serif", "Cursive")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(fonts) { font ->
            val isSelected = font == currentFont
            val accent = LocalGlaiveTheme.current.colors.accent
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) accent else Color(0xFF222222))
                    .clickable { onFontSelected(font) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = font,
                    color = if (isSelected) Color.Black else Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
