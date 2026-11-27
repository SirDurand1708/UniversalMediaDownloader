package com.amurayada.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurayada.music.ui.theme.LocalAuraPalette
import kotlin.math.roundToInt

@Composable
fun VolumeBoostHud(
    visible: Boolean,
    percent: Int,
    onPercentChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAuraPalette.current
    val amberText = if (palette.isDark) Color(0xFFFFB300) else Color(0xFFD97706)
    val activeColor = when {
        percent > 200 -> Color(0xFFFF5252)
        percent > 100 -> amberText
        else -> palette.accent
    }
    val icon = when {
        percent <= 0 -> Icons.AutoMirrored.Rounded.VolumeMute
        percent <= 50 -> Icons.AutoMirrored.Rounded.VolumeDown
        percent <= 100 -> Icons.AutoMirrored.Rounded.VolumeUp
        else -> Icons.Rounded.GraphicEq
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 }
        ) {
            Surface(
                color = palette.cardBg.copy(alpha = 0.96f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.2.dp, if (percent > 100) activeColor.copy(alpha = 0.7f) else palette.border),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = "Volumen",
                                tint = activeColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (percent > 100) "SUPER VOLUMEN" else "VOLUMEN",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (percent > 100) activeColor else palette.textPrimary,
                                letterSpacing = 0.8.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = activeColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = activeColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                text = "Cerrar",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                modifier = Modifier.clickable { onDismiss() }
                            )
                        }
                    }

                    Slider(
                        value = percent.coerceIn(0, 300).toFloat(),
                        onValueChange = { onPercentChange(it.roundToInt().coerceIn(0, 300)) },
                        valueRange = 0f..300f,
                        colors = SliderDefaults.colors(
                            thumbColor = activeColor,
                            activeTrackColor = activeColor,
                            inactiveTrackColor = palette.trackBg
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(100, 200, 300).forEach { preset ->
                            val selected = (preset == 100 && percent in 95..105) ||
                                (preset == 200 && percent in 195..205) ||
                                (preset == 300 && percent >= 295)
                            val presetColor = when (preset) {
                                200 -> Color(0xFFFFB300)
                                300 -> Color(0xFFFF5252)
                                else -> palette.accent
                            }
                            Surface(
                                onClick = { onPercentChange(preset) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) presetColor else palette.chipUnselectedBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) presetColor else palette.chipUnselectedBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                ) {
                                    val selectedTextColor = when (preset) {
                                        300 -> Color.White
                                        200 -> Color.Black
                                        else -> palette.onAccent
                                    }
                                    Text(
                                        text = "$preset%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) selectedTextColor else palette.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
