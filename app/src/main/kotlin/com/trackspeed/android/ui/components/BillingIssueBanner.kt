package com.trackspeed.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.BackgroundDark
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

private val WarningAmber = Color(0xFFFFAB00)
private val WarningBackground = Color(0x26FFAB00) // 15% opacity amber
private val WarningBorder = Color(0x80FFAB00) // 50% opacity amber

/**
 * Dismissible warning banner for billing issues during subscription grace period.
 * Matches iOS BillingIssueBanner component.
 *
 * @param message The message to display.
 * @param actionLabel Label for the action button (e.g. "Fix", "Resolve").
 * @param onActionClick Called when the action button is tapped.
 * @param onDismiss Called when the dismiss (X) button is tapped. Null to hide dismiss button.
 */
@Composable
fun BillingIssueBanner(
    message: String = stringResource(R.string.billing_default_message),
    actionLabel: String = stringResource(R.string.billing_default_action),
    onActionClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = WarningBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, WarningBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onActionClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Credit card icon with warning badge
                Box {
                    Icon(
                        imageVector = Icons.Outlined.CreditCard,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(28.dp)
                    )
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30),
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .background(BackgroundDark, CircleShape)
                    )
                }

                // Message text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.billing_payment_issue),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Fix button
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = Color.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(WarningAmber)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                // Dismiss X button
                if (onDismiss != null) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.billing_dismiss),
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                visible = false
                                onDismiss()
                            }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BillingIssueBannerPreview() {
    BillingIssueBanner(
        onActionClick = {},
        onDismiss = {}
    )
}
