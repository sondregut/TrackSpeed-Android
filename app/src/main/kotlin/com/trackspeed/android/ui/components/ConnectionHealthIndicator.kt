package com.trackspeed.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connection health status levels, matching iOS ConnectionStatus enum.
 */
enum class ConnectionHealth(
    val label: String,
    val color: Color,
    val backgroundColor: Color
) {
    CONNECTED(
        label = "Connected",
        color = Color(0xFF30D158),       // green
        backgroundColor = Color(0x2630D158) // 15% green
    ),
    WEAK(
        label = "Weak",
        color = Color(0xFFFFAB00),       // amber/yellow
        backgroundColor = Color(0x26FFAB00) // 15% amber
    ),
    DISCONNECTED(
        label = "Disconnected",
        color = Color(0xFFFF3B30),       // red
        backgroundColor = Color(0x26FF3B30) // 15% red
    )
}

/**
 * Small circular indicator showing BLE/WiFi connection quality.
 * Animated pulse when connected. Tap to expand/collapse label text.
 * Matches iOS ConnectionHealthIndicator component.
 *
 * @param health Current connection health status.
 * @param latencyMs Optional latency in milliseconds to display when expanded.
 * @param syncUncertaintyMs Optional sync uncertainty to display when expanded.
 * @param showLabel Whether to show the text label next to the dot.
 * @param modifier Modifier.
 */
@Composable
fun ConnectionHealthIndicator(
    health: ConnectionHealth,
    latencyMs: Int? = null,
    syncUncertaintyMs: Double? = null,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Pulse animation for connected state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (health == ConnectionHealth.CONNECTED) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = if (health == ConnectionHealth.CONNECTED) 0f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(health.backgroundColor)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Dot with pulse
        Box(contentAlignment = Alignment.Center) {
            // Pulse ring (only when connected)
            if (health == ConnectionHealth.CONNECTED) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(pulseScale)
                        .background(
                            health.color.copy(alpha = pulseAlpha),
                            CircleShape
                        )
                )
            }
            // Solid dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(health.color, CircleShape)
            )
        }

        // Label
        if (showLabel) {
            Text(
                text = health.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = health.color
            )
        }

        // Expanded details
        if (expanded && latencyMs != null) {
            val detailText = buildString {
                append("${latencyMs}ms")
                if (syncUncertaintyMs != null) {
                    append(" | \u00B1${String.format("%.1f", syncUncertaintyMs)}ms")
                }
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = Color(0xFF8E8E93)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ConnectionHealthPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConnectionHealthIndicator(health = ConnectionHealth.CONNECTED, latencyMs = 12)
        ConnectionHealthIndicator(health = ConnectionHealth.WEAK, latencyMs = 45)
        ConnectionHealthIndicator(health = ConnectionHealth.DISCONNECTED)
    }
}
