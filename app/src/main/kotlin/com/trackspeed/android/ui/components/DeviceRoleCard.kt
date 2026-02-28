package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBackground = Color(0xFF2C2C2E)

/**
 * Role assigned to a device in a multi-device timing session.
 */
enum class DeviceRole(
    val displayName: String,
    val icon: ImageVector,
    val color: Color
) {
    START(
        displayName = "Start",
        icon = Icons.Outlined.PlayArrow,
        color = Color(0xFF30D158) // green
    ),
    FINISH(
        displayName = "Finish",
        icon = Icons.Outlined.Flag,
        color = Color(0xFFFFAB00) // amber
    ),
    SPLIT(
        displayName = "Split",
        icon = Icons.Outlined.Timer,
        color = Color(0xFF0A84FF) // blue
    )
}

/**
 * Connection state of a device.
 */
enum class DeviceConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * Card showing a device with its name, assigned role, and connection status.
 * Matches iOS DeviceRoleCard component.
 *
 * @param deviceName Display name of the device.
 * @param role The role assigned to this device, or null if unassigned.
 * @param connectionState Current connection state.
 * @param isHost Whether this device is the session host.
 * @param onClick Called when the card is tapped.
 * @param modifier Modifier.
 */
@Composable
fun DeviceRoleCard(
    deviceName: String,
    role: DeviceRole?,
    connectionState: DeviceConnectionState = DeviceConnectionState.CONNECTED,
    isHost: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val roleColor = role?.color ?: Color(0xFF8E8E93)
    val connectionHealth = when (connectionState) {
        DeviceConnectionState.CONNECTED -> ConnectionHealth.CONNECTED
        DeviceConnectionState.CONNECTING -> ConnectionHealth.WEAK
        DeviceConnectionState.DISCONNECTED -> ConnectionHealth.DISCONNECTED
    }

    Card(
        onClick = { onClick?.invoke() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = if (isHost) {
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFAB00).copy(alpha = 0.5f))
        } else if (role != null) {
            androidx.compose.foundation.BorderStroke(1.dp, roleColor.copy(alpha = 0.3f))
        } else null,
        enabled = onClick != null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Role icon circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (role != null && connectionState == DeviceConnectionState.CONNECTED) {
                            roleColor
                        } else {
                            Color(0xFF8E8E93).copy(alpha = 0.2f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = role?.icon ?: Icons.Outlined.Phone,
                    contentDescription = null,
                    tint = if (role != null && connectionState == DeviceConnectionState.CONNECTED) {
                        Color.White
                    } else {
                        Color(0xFF8E8E93)
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isHost) {
                        Text(
                            text = "\u2605",
                            fontSize = 10.sp,
                            color = Color(0xFFFFAB00)
                        )
                    }
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        role != null -> role.displayName
                        connectionState == DeviceConnectionState.CONNECTING -> "Connecting..."
                        connectionState == DeviceConnectionState.CONNECTED -> "Tap to assign role"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (role != null) roleColor else Color(0xFF8E8E93)
                )
            }

            // Connection status indicator
            if (connectionState == DeviceConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF8E8E93)
                )
            } else {
                ConnectionHealthIndicator(
                    health = connectionHealth,
                    showLabel = false
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DeviceRoleCardPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DeviceRoleCard(
            deviceName = "iPhone 15 Pro",
            role = DeviceRole.START,
            isHost = true,
            connectionState = DeviceConnectionState.CONNECTED
        )
        DeviceRoleCard(
            deviceName = "Samsung Galaxy S24",
            role = DeviceRole.FINISH,
            connectionState = DeviceConnectionState.CONNECTED
        )
        DeviceRoleCard(
            deviceName = "Pixel 8",
            role = null,
            connectionState = DeviceConnectionState.CONNECTING
        )
        DeviceRoleCard(
            deviceName = "iPhone SE",
            role = DeviceRole.SPLIT,
            connectionState = DeviceConnectionState.DISCONNECTED
        )
    }
}
