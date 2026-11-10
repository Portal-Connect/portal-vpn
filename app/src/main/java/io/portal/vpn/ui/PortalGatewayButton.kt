package io.portal.vpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.portal.vpn.HapticFeedbackManager

/**
 * Animated Sci-Fi Portal Gateway Button.
 * Replaces the conventional power button with an authentic, living Dimensional Portal.
 * Clean, high-contrast, theme-aware and visually captivating on both dark and light modes.
 */
@Composable
fun PortalGatewayButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "portalVortex")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    val ringRotationCw by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cwRotation"
    )

    val ringRotationCcw by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ccwRotation"
    )

    val coreGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val isDark = MaterialTheme.colorScheme.surface == Color(0xFF10141F)
    val connectedGreen = Color(0xFF00F5A0)
    val connectedCyan = Color(0xFF00E5FF)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = if (isConnected) connectedCyan else Color(0xFFA855F7)
    val auraColor = if (isConnected) connectedGreen else primaryColor
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = modifier
            .size(204.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                HapticFeedbackManager.portalClick(context)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. Outermost Hyperspace Aura (Pulsing Glow)
        Box(
            modifier = Modifier
                .size((196 * (if (isConnected) pulseScale else 1f)).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            auraColor.copy(alpha = if (isConnected) (if (isDark) 0.35f else 0.22f) else (if (isDark) 0.18f else 0.12f)),
                            secondaryColor.copy(alpha = if (isConnected) 0.14f else 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 2. Outer Stargate Ring (Clockwise Rotation)
        Box(
            modifier = Modifier
                .size(174.dp)
                .rotate(ringRotationCw)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            if (isConnected) connectedGreen else primaryColor,
                            secondaryColor.copy(alpha = 0.25f),
                            if (isConnected) connectedCyan else primaryColor.copy(alpha = 0.85f),
                            secondaryColor.copy(alpha = 0.25f),
                            if (isConnected) connectedGreen else primaryColor
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 3. Middle Quantum Acceleration Ring (Counter-Clockwise Rotation)
        Box(
            modifier = Modifier
                .size(152.dp)
                .rotate(ringRotationCcw)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            secondaryColor.copy(alpha = 0.85f),
                            Color.Transparent,
                            (if (isConnected) connectedGreen else primaryColor).copy(alpha = 0.85f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 4. Central Event Horizon (Living Portal Core)
        val coreGradient = if (isConnected) {
            Brush.radialGradient(
                listOf(
                    Color(0xFFE0FFF4),
                    Color(0xFF00F5A0),
                    Color(0xFF00C994),
                    Color(0xFF00897B),
                    Color(0xFF004D40)
                )
            )
        } else {
            if (isDark) {
                Brush.radialGradient(
                    listOf(
                        primaryColor.copy(alpha = 0.35f * coreGlowAlpha),
                        Color(0xFF1E2438),
                        Color(0xFF141926),
                        Color(0xFF0E121C)
                    )
                )
            } else {
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFF8FAFC),
                        Color(0xFFE2E8F0),
                        primaryColor.copy(alpha = 0.15f)
                    )
                )
            }
        }

        val coreBorder = if (isConnected) {
            Brush.sweepGradient(
                listOf(Color.White, connectedGreen, connectedCyan, Color.White)
            )
        } else {
            Brush.sweepGradient(
                listOf(primaryColor, primaryColor.copy(alpha = 0.4f), secondaryColor, primaryColor)
            )
        }

        Box(
            modifier = Modifier
                .size(128.dp)
                .shadow(
                    elevation = if (isConnected) 20.dp else 10.dp,
                    shape = CircleShape,
                    spotColor = auraColor
                )
                .clip(CircleShape)
                .background(coreGradient)
                .border(
                    width = 2.dp,
                    brush = coreBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color.Black.copy(alpha = 0.16f)
                            else primaryColor.copy(alpha = if (isDark) 0.18f else 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isConnected) {
                        Text(
                            text = "🌀",
                            fontSize = 28.sp,
                            modifier = Modifier.rotate(ringRotationCw * 2.2f)
                        )
                    } else {
                        Text(
                            text = "⚛️",
                            fontSize = 26.sp,
                            modifier = Modifier.rotate(ringRotationCw * 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isConnected) "В ПОРТАЛЕ" else "ВОЙТИ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = if (isConnected) {
                        Color.White
                    } else {
                        if (isDark) Color.White else Color(0xFF0F172A)
                    }
                )

                Text(
                    text = if (isConnected) "ТУННЕЛЬ 100%" else "В ПОРТАЛ",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = if (isConnected) {
                        Color(0xFFE0F7FA)
                    } else {
                        primaryColor
                    }
                )
            }
        }
    }
}
